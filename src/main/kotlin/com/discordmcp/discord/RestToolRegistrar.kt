package com.discordmcp.discord

import com.discordmcp.config.AppConfig
import com.discordmcp.config.Config
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
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
 * Registers one MCP tool per [EndpointSpec], covering the entire Discord REST API surface.
 */
object RestToolRegistrar {

    fun registerAll(server: Server, client: DiscordHttpClient, config: AppConfig = Config.current) {
        for (spec in EndpointRegistry.endpoints) {
            server.addTool(
                name = spec.toolName,
                description = buildDescription(spec),
                inputSchema = buildInputSchema(spec),
                toolAnnotations = ToolAnnotations(
                    readOnlyHint = spec.method == "GET",
                    destructiveHint = spec.method == "DELETE",
                    idempotentHint = spec.method == "PUT" || spec.method == "DELETE",
                    openWorldHint = true,
                ),
            ) { request ->
                val args = request.arguments ?: emptyMap()

                val pathValues = mutableMapOf<String, String>()
                for (p in spec.pathParams) {
                    val v = args[p.name]?.jsonPrimitive?.contentOrNull
                    if (v == null && p.required) {
                        return@addTool CallToolResult(
                            content = listOf(TextContent("Missing required path parameter '${p.name}'.")),
                            isError = true,
                        )
                    }
                    if (v != null) pathValues[p.name] = v
                }

                val queryValues = mutableMapOf<String, List<String>>()
                for (p in spec.queryParams) {
                    val el = args[p.name] ?: continue
                    val values = if (p.jsonType == "array") {
                        (el as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                    } else {
                        listOfNotNull(el.jsonPrimitive.contentOrNull)
                    }
                    if (values.isNotEmpty()) queryValues[p.name] = values
                }

                val bodyObject = (args["body"] as? JsonObject)
                if (spec.body?.required == true && bodyObject == null) {
                    return@addTool CallToolResult(
                        content = listOf(
                            TextContent(
                                "Missing required 'body' object. Expected fields for " +
                                    "${spec.body.schemaName}: ${spec.body.hint?.fields?.joinToString() ?: "(see Discord docs)"}",
                            ),
                        ),
                        isError = true,
                    )
                }

                val files = args["files"] as? JsonArray
                val auditLogReason = args["auditLogReason"]?.jsonPrimitive?.contentOrNull
                val authOverride = args["authOverride"]?.jsonPrimitive?.contentOrNull

                if (spec.authType == "bot" && authOverride == null && config.botToken == null) {
                    return@addTool CallToolResult(
                        content = listOf(
                            TextContent(
                                "DISCORD_BOT_TOKEN is not configured on the server, and no 'authOverride' " +
                                    "argument was supplied. Set the DISCORD_BOT_TOKEN environment variable, or " +
                                    "pass authOverride (e.g. 'Bot <token>' or 'Bearer <user token>').",
                            ),
                        ),
                        isError = true,
                    )
                }

                val result = client.execute(spec, pathValues, queryValues, bodyObject, files, auditLogReason, authOverride)

                when (result) {
                    is DiscordResult.Success -> {
                        val prefix = "HTTP ${result.status} ${result.statusText}" +
                            if (result.rateLimitedRetries > 0) " (after ${result.rateLimitedRetries} rate-limit retry/retries)" else ""
                        CallToolResult(
                            content = listOf(TextContent("$prefix\n\n${result.body}")),
                            isError = false,
                        )
                    }

                    is DiscordResult.Error -> {
                        val prefix = "HTTP ${result.status} ${result.statusText}"
                        CallToolResult(
                            content = listOf(TextContent("$prefix\n\n${result.message}")),
                            isError = true,
                        )
                    }
                }
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
                    "{filename, contentType, contentBase64}.",
            )
        }
        sb.append(" Full field-level reference: https://docs.discord.com/developers/docs")
        return sb.toString()
    }

    private fun jsonSchemaType(jsonType: String): String = when (jsonType) {
        "integer", "number", "boolean", "array", "object" -> jsonType
        else -> "string"
    }

    private fun buildInputSchema(spec: EndpointSpec): ToolSchema {
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
                    put(
                        "description",
                        "JSON request body" +
                            (spec.body.schemaName?.let { " matching Discord's $it schema" } ?: "") +
                            ". Additional properties are allowed and passed through as-is.",
                    )
                }
                if (spec.body.required) required.add("body")
            }
            if (spec.body?.contentType == "multipart/form-data") {
                putJsonObject("files") {
                    put("type", "array")
                    put("description", "Optional file attachments.")
                    putJsonObject("items") {
                        put("type", "object")
                        put("description", "{filename: string, contentType: string, contentBase64: string}")
                    }
                }
            }
            putJsonObject("auditLogReason") {
                put("type", "string")
                put("description", "Optional. Sent as the X-Audit-Log-Reason header for moderation/audit-logged actions.")
            }
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
        return ToolSchema(properties = properties, required = required)
    }
}
