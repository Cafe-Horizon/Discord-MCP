package com.discordmcp.discord

import com.discordmcp.config.AppConfig
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Executes one [EndpointSpec] against the Discord REST API and converts the result into an MCP
 * [CallToolResult]. Shared by [RestToolRegistrar] (one MCP tool per endpoint) and
 * [LazyToolRegistrar] (one generic dispatcher tool for all endpoints), so parameter validation,
 * auth-token checks, and result formatting stay in exactly one place.
 */
object EndpointExecutor {

    suspend fun call(
        spec: EndpointSpec,
        client: DiscordHttpClient,
        config: AppConfig,
        pathArgs: JsonObject,
        queryArgs: JsonObject,
        bodyObject: JsonObject?,
        files: JsonArray?,
        auditLogReason: String?,
        authOverride: String?,
        fields: List<String>? = null,
        summaryMode: Boolean? = false,
        profile: String? = null,
    ): CallToolResult {
        val resolvedAuthOverride = authOverride ?: profile?.let {
            config.botTokens[it]?.let { token ->
                if (token.startsWith("Bot ") || token.startsWith("Bearer ")) token else "Bot $token"
            }
        }

        if (profile != null && authOverride == null && config.botTokens[profile] == null) {
            return CallToolResult(
                content = listOf(
                    TextContent(
                        "Unknown profile '$profile'. Configured profiles in DISCORD_BOT_TOKENS are: ${if (config.botTokens.isEmpty()) "(none)" else config.botTokens.keys.joinToString()}.",
                    ),
                ),
                isError = true,
            )
        }

        val pathValues = mutableMapOf<String, String>()
        for (p in spec.pathParams) {
            val v = pathArgs[p.name]?.jsonPrimitive?.contentOrNull
            if (v == null && p.required) {
                return CallToolResult(
                    content = listOf(TextContent("Missing required path parameter '${p.name}'.")),
                    isError = true,
                )
            }
            if (v != null) pathValues[p.name] = v
        }

        val queryValues = mutableMapOf<String, List<String>>()
        for (p in spec.queryParams) {
            val el = queryArgs[p.name] ?: continue
            val values = if (p.jsonType == "array") {
                (el as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            } else {
                listOfNotNull(el.jsonPrimitive.contentOrNull)
            }
            if (values.isNotEmpty()) queryValues[p.name] = values
        }

        if (spec.body?.required == true && bodyObject == null) {
            return CallToolResult(
                content = listOf(
                    TextContent(
                        "Missing required 'body' object. Expected fields for " +
                            "${spec.body.schemaName}: ${spec.body.hint?.fields?.joinToString() ?: "(see Discord docs)"}",
                    ),
                ),
                isError = true,
            )
        }

        if (authOverride != null && !config.allowAuthOverride) {
            return CallToolResult(
                content = listOf(
                    TextContent(
                        "'authOverride' is disabled on this server. Enable DISCORD_MCP_ALLOW_AUTH_OVERRIDE " +
                            "environment variable to allow token overrides.",
                    ),
                ),
                isError = true,
            )
        }

        if (spec.authType == "bot" && resolvedAuthOverride == null && config.botToken == null) {
            return CallToolResult(
                content = listOf(
                    TextContent(
                        "DISCORD_BOT_TOKEN is not configured on the server, and no 'authOverride' or 'profile' " +
                            "argument was supplied. Set DISCORD_BOT_TOKEN / DISCORD_BOT_TOKENS environment variables, or " +
                            "pass profile / authOverride.",
                    ),
                ),
                isError = true,
            )
        }

        val result = client.execute(spec, pathValues, queryValues, bodyObject, files, auditLogReason, resolvedAuthOverride)

        return when (result) {
            is DiscordResult.Success -> {
                val prefix = "HTTP ${result.status} ${result.statusText}" +
                    if (result.rateLimitedRetries > 0) " (after ${result.rateLimitedRetries} rate-limit retry/retries)" else ""
                
                val processedBody = processResponseBody(result.body, fields, summaryMode ?: false)
                CallToolResult(
                    content = listOf(TextContent("$prefix\n\n$processedBody")),
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

    private fun processResponseBody(rawBody: String, fields: List<String>?, summaryMode: Boolean): String {
        if (rawBody.isBlank()) return rawBody
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }

        val element = runCatching { json.parseToJsonElement(rawBody) }.getOrNull() ?: return rawBody

        var filtered = element
        if (!fields.isNullOrEmpty()) {
            filtered = filterFields(element, fields.toSet())
        }

        if (summaryMode) {
            return summarizeJson(filtered)
        }

        return json.encodeToString(filtered)
    }

    private fun filterFields(element: kotlinx.serialization.json.JsonElement, allowedFields: Set<String>): kotlinx.serialization.json.JsonElement {
        return when (element) {
            is JsonObject -> JsonObject(
                element.filterKeys { it in allowedFields || allowedFields.any { f -> f.startsWith("$it.") } }
                    .mapValues { (key, valElem) ->
                        val subFields = allowedFields.filter { it.startsWith("$key.") }.map { it.removePrefix("$key.") }.toSet()
                        if (subFields.isNotEmpty()) filterFields(valElem, subFields) else valElem
                    }
            )
            is JsonArray -> JsonArray(element.map { filterFields(it, allowedFields) })
            else -> element
        }
    }

    private fun summarizeJson(element: kotlinx.serialization.json.JsonElement): String {
        return when (element) {
            is JsonArray -> "Array (${element.size} items):\n" + element.take(10).joinToString("\n") { summarizeElement(it) } +
                if (element.size > 10) "\n... (${element.size - 10} more items omitted)" else ""
            is JsonObject -> summarizeElement(element)
            else -> element.toString()
        }
    }

    private fun summarizeElement(element: kotlinx.serialization.json.JsonElement): String {
        if (element !is JsonObject) return element.toString()
        val id = element["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val name = element["name"]?.jsonPrimitive?.contentOrNull ?: element["username"]?.jsonPrimitive?.contentOrNull ?: ""
        val content = element["content"]?.jsonPrimitive?.contentOrNull ?: ""
        
        val parts = mutableListOf<String>()
        if (id.isNotEmpty()) parts.add("id=$id")
        if (name.isNotEmpty()) parts.add("name='$name'")
        if (content.isNotEmpty()) parts.add("content='${content.take(50)}${if (content.length > 50) "..." else ""}'")
        
        return if (parts.isNotEmpty()) "{ ${parts.joinToString(", ")} }" else element.toString()
    }

}
