package com.discordmcp.discord

import com.discordmcp.config.AppConfig
import com.discordmcp.config.Config
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Registers one MCP tool per [EndpointSpec], covering the (filtered) Discord REST API surface.
 *
 * Which endpoints get registered is controlled by [EndpointFilter] (DISCORD_MCP_TOOL_CATEGORIES /
 * DISCORD_MCP_INCLUDE_TOOLS / DISCORD_MCP_EXCLUDE_TOOLS / DISCORD_MCP_READONLY). If
 * `config.lazyTools` is set, this registrar is bypassed entirely in favor of
 * [LazyToolRegistrar], which exposes the same filtered set through two generic tools instead of
 * one tool per endpoint — see Main.kt.
 */
object RestToolRegistrar {

    fun registerAll(server: Server, client: DiscordHttpClient, config: AppConfig = Config.current) {
        val endpoints = EndpointFilter.apply(EndpointRegistry.endpoints, config)
        for (spec in endpoints) {
            server.addTool(
                name = spec.toolName,
                description = buildDescription(spec),
                inputSchema = buildInputSchema(spec, config),
                toolAnnotations = ToolAnnotations(
                    readOnlyHint = spec.method == "GET",
                    destructiveHint = spec.method == "DELETE",
                    idempotentHint = spec.method == "PUT" || spec.method == "DELETE",
                    openWorldHint = true,
                ),
            ) { request ->
                val args = request.arguments ?: emptyMap()
                val argsObj = JsonObject(args)

                val fieldsArg = (args["fields"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: (args["fields"]?.jsonPrimitive?.contentOrNull)?.split(",")?.map { it.trim() }

                EndpointExecutor.call(
                    spec = spec,
                    client = client,
                    config = config,
                    pathArgs = argsObj,
                    queryArgs = argsObj,
                    bodyObject = args["body"] as? JsonObject,
                    files = args["files"] as? JsonArray,
                    auditLogReason = args["auditLogReason"]?.jsonPrimitive?.contentOrNull,
                    authOverride = args["authOverride"]?.jsonPrimitive?.contentOrNull,
                    fields = fieldsArg,
                    summaryMode = (args["summaryMode"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull(),
                    profile = args["profile"]?.jsonPrimitive?.contentOrNull,
                )
            }
        }
    }

    private fun buildDescription(spec: EndpointSpec): String {
        val sb = StringBuilder()
        sb.append("${spec.method} ${spec.path} — Discord REST API (operationId: ${spec.operationId}).")
        val body = spec.body
        if (body != null) {
            sb.append(" Request body via 'body' (${body.contentType}")
            if (body.schemaName != null) sb.append(", schema ${body.schemaName}")
            sb.append(if (body.required) ", required" else ", optional")
            sb.append(").")
            val hint = body.hint
            if (hint != null && hint.fields.isNotEmpty()) {
                sb.append(" Known fields: ${hint.fields.joinToString()}.")
                if (hint.required.isNotEmpty()) sb.append(" Required fields: ${hint.required.joinToString()}.")
            }
        }
        if (body?.contentType == "multipart/form-data") {
            sb.append(
                " To attach files, pass 'files' as an array of " +
                    "{filename, contentType, contentBase64 (or filePath)}.",
            )
        }
        sb.append(" Full field-level reference: https://docs.discord.com/developers/docs")
        return sb.toString()
    }

    private fun jsonSchemaType(jsonType: String): String = when (jsonType) {
        "integer", "number", "boolean", "array", "object" -> jsonType
        else -> "string"
    }

    private fun buildInputSchema(spec: EndpointSpec, config: AppConfig): ToolSchema {
        val required = mutableListOf<String>()
        val properties = buildJsonObject {
            for (p in spec.pathParams) {
                putJsonObject(p.name) {
                    put("type", jsonSchemaType(p.jsonType))
                    put("description", "Path parameter.")
                    if (p.pattern != null) put("pattern", p.pattern)
                    if (p.enum != null) putJsonArray("enum") { p.enum.forEach { add(it) } }
                }
                if (p.required) required.add(p.name)
            }
            for (p in spec.queryParams) {
                if (p.jsonType == "array") {
                    putJsonObject(p.name) {
                        put("type", "array")
                        put("description", "Query parameter (array).")
                        putJsonObject("items") {
                            put("type", jsonSchemaType(p.items?.jsonType ?: "string"))
                            if (p.items?.enum != null) putJsonArray("enum") { p.items.enum.forEach { add(it) } }
                        }
                    }
                } else {
                    putJsonObject(p.name) {
                        put("type", jsonSchemaType(p.jsonType))
                        put("description", "Query parameter.")
                        if (p.pattern != null) put("pattern", p.pattern)
                        if (p.enum != null) putJsonArray("enum") { p.enum.forEach { add(it) } }
                    }
                }
                if (p.required) required.add(p.name)
            }
            if (spec.body != null) {
                putJsonObject("body") {
                    put("type", "object")
                    val desc = "JSON request body" +
                        (spec.body.schemaName?.let { " matching Discord's $it schema" } ?: "") +
                        ". Additional properties are allowed and passed through as-is."
                    put("description", desc)
                    val hint = spec.body.hint
                    if (hint != null && hint.fields.isNotEmpty()) {
                        putJsonObject("properties") {
                            for (field in hint.fields) {
                                putJsonObject(field) {
                                    put("description", "Known field for ${spec.body.schemaName ?: "request body"}.")
                                }
                            }
                        }
                        if (hint.required.isNotEmpty()) {
                            putJsonArray("required") {
                                hint.required.forEach { add(it) }
                            }
                        }
                    }
                }
                if (spec.body.required) required.add("body")
            }
            if (spec.body?.contentType == "multipart/form-data") {
                putJsonObject("files") {
                    put("type", "array")
                    put("description", "Optional file attachments.")
                    putJsonObject("items") {
                        put("type", "object")
                        put("description", "{filename: string, contentType: string, contentBase64: string (or filePath: string)}")
                    }
                }
            }
            putJsonObject("auditLogReason") {
                put("type", "string")
                put("description", "Optional. Sent as the X-Audit-Log-Reason header for moderation/audit-logged actions.")
            }
            putJsonObject("fields") {
                put("type", "string")
                put("description", "Optional. Comma-separated list of JSON keys/fields to include in the response, filtering out unnecessary fields to save context tokens.")
            }
            putJsonObject("summaryMode") {
                put("type", "boolean")
                put("description", "Optional. If true, returns a short human-readable summary instead of full raw JSON response.")
            }
            putJsonObject("profile") {
                put("type", "string")
                put("description", "Optional. Bot profile name defined in DISCORD_BOT_TOKENS to override the bot token for this request.")
            }
            if (config.allowAuthOverride) {
                putJsonObject("authOverride") {
                    put("type", "string")
                    put(
                        "description",
                        "Optional. Overrides the Authorization header for this call " +
                            "(default is 'Bot <DISCORD_BOT_TOKEN>'). E.g. 'Bearer <user access token>' " +
                            "for OAuth2 user-scoped endpoints.",
                    )
                }
            }
        }
        return ToolSchema(properties = properties, required = required)
    }
}
