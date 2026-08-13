package com.discordmcp.discord

import com.discordmcp.config.AppConfig
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Alternative to [RestToolRegistrar] for keeping this server's context footprint small.
 *
 * Instead of registering one MCP tool per Discord REST operation (up to ~250 tool schemas,
 * all loaded into the connecting client's context before a single message is sent),
 * this registers exactly two tools:
 *  - `discord_search_tools`: look up available operations by keyword/category/method.
 *  - `discord_call_tool`: invoke one operation by name, resolved against [EndpointSpec] at
 *    call time and executed through the same [EndpointExecutor] used by [RestToolRegistrar].
 *
 * Enabled via `DISCORD_MCP_LAZY_TOOLS=true`. The endpoints passed in are already filtered by
 * [EndpointFilter], so category/include/exclude/readOnly settings still narrow what this proxy
 * can discover and call — the two mechanisms compose.
 */
object LazyToolRegistrar {

    fun registerAll(server: Server, client: DiscordHttpClient, config: AppConfig, endpoints: List<EndpointSpec>) {
        val byToolName = endpoints.associateBy { it.toolName }
        val categoryCounts = endpoints.groupingBy { it.category }.eachCount().toSortedMap()

        server.addTool(
            name = "discord_search_tools",
            description = "Search the Discord REST API operations available on this server by keyword, " +
                "category, and/or HTTP method, then pass the resulting tool name to discord_call_tool. " +
                "Individual operations are not pre-registered as separate tools here (to keep context usage " +
                "low); call this with no arguments to see the full category breakdown first.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Substring matched (case-insensitive) against operation id, path, and category.")
                    }
                    putJsonObject("category") {
                        put("type", "string")
                        put("description", "Restrict to one category, e.g. 'guilds', 'channels', 'webhooks'. See a no-argument call for the full list.")
                    }
                    putJsonObject("method") {
                        put("type", "string")
                        put("description", "Restrict to one HTTP method: GET, POST, PATCH, PUT, or DELETE.")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "Max results to return. Defaults to 25, max 100.")
                    }
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) { request ->
            val args = request.arguments ?: emptyMap()
            val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            val categoryFilter = args["category"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            val methodFilter = args["method"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 25).coerceIn(1, 100)

            if (query == null && categoryFilter == null && methodFilter == null) {
                val text = buildJsonObject {
                    put("totalTools", endpoints.size)
                    putJsonArray("categories") {
                        categoryCounts.forEach { (cat, count) ->
                            add(buildJsonObject { put("category", cat); put("toolCount", count) })
                        }
                    }
                    put("hint", "Call again with 'query' and/or 'category' (and optionally 'method') to list matching tool names.")
                }
                return@addTool CallToolResult(content = listOf(TextContent(text.toString())))
            }

            val matches = endpoints.asSequence()
                .filter { categoryFilter == null || it.category == categoryFilter }
                .filter { methodFilter == null || it.method == methodFilter }
                .filter {
                    query == null ||
                        it.operationId.lowercase().contains(query) ||
                        it.path.lowercase().contains(query) ||
                        it.category.lowercase().contains(query)
                }
                .take(limit)
                .toList()

            val text = buildJsonObject {
                put("matchCount", matches.size)
                putJsonArray("tools") {
                    matches.forEach { spec ->
                        add(
                            buildJsonObject {
                                put("toolName", spec.toolName)
                                put("method", spec.method)
                                put("path", spec.path)
                                put("category", spec.category)
                                putJsonArray("requiredPathParams") {
                                    spec.pathParams.filter { it.required }.forEach { add(it.name) }
                                }
                                put("hasBody", spec.body != null)
                            },
                        )
                    }
                }
                if (matches.isEmpty()) {
                    put("hint", "No matches. Try a broader query, or call discord_search_tools with no arguments to list categories.")
                }
            }
            CallToolResult(content = listOf(TextContent(text.toString())))
        }

        server.addTool(
            name = "discord_call_tool",
            description = "Invoke one Discord REST API operation by its tool name, as returned by " +
                "discord_search_tools (e.g. 'discord_get_guild'). This generic dispatcher stands in for " +
                "one pre-registered tool per operation, to keep this server's context footprint small; " +
                "always call discord_search_tools first if you don't already know the exact tool name.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("toolName") {
                        put("type", "string")
                        put("description", "Exact tool name from discord_search_tools, e.g. 'discord_get_guild'.")
                    }
                    putJsonObject("pathParams") {
                        put("type", "object")
                        put("description", "Path parameter values keyed by name, e.g. {\"guild_id\": \"123\"}.")
                    }
                    putJsonObject("queryParams") {
                        put("type", "object")
                        put("description", "Query parameter values keyed by name. Array-typed params take a JSON array value.")
                    }
                    putJsonObject("body") {
                        put("type", "object")
                        put("description", "JSON request body, for operations that take one.")
                    }
                    putJsonObject("files") {
                        put("type", "array")
                        put("description", "Optional file attachments: [{filename, contentType, contentBase64 (or filePath)}].")
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
                                "Optional. Overrides the Authorization header for this call (default is " +
                                    "'Bot <DISCORD_BOT_TOKEN>'). E.g. 'Bearer <user access token>' for OAuth2 " +
                                    "user-scoped endpoints.",
                            )
                        }
                    }
                },
                required = listOf("toolName"),
            ),
            toolAnnotations = ToolAnnotations(openWorldHint = true),
        ) { request ->
            val args = request.arguments ?: emptyMap()
            val toolName = args["toolName"]?.jsonPrimitive?.contentOrNull
            val spec = toolName?.let { byToolName[it] }

            if (spec == null) {
                return@addTool CallToolResult(
                    content = listOf(
                        TextContent(
                            "Unknown or unavailable tool name '${toolName ?: ""}'. Use discord_search_tools to " +
                                "find a valid one — it only lists operations enabled on this server instance.",
                        ),
                    ),
                    isError = true,
                )
            }

            val fieldsArg = (args["fields"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: (args["fields"]?.jsonPrimitive?.contentOrNull)?.split(",")?.map { it.trim() }

            EndpointExecutor.call(
                spec = spec,
                client = client,
                config = config,
                pathArgs = (args["pathParams"] as? JsonObject) ?: JsonObject(emptyMap()),
                queryArgs = (args["queryParams"] as? JsonObject) ?: JsonObject(emptyMap()),
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
