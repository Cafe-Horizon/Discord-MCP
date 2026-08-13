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
    ): CallToolResult {
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

        if (spec.authType == "bot" && authOverride == null && config.botToken == null) {
            return CallToolResult(
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

        return when (result) {
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
