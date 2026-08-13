package com.discordmcp.discord

import kotlinx.serialization.Serializable

/**
 * Type/shape info shared by parameters and array item types.
 */
@Serializable
data class ParamTypeInfo(
    val jsonType: String = "string",
    val enum: List<String>? = null,
    val pattern: String? = null,
    val items: ParamTypeInfo? = null,
)

@Serializable
data class ParamSpec(
    val name: String,
    val required: Boolean = false,
    val jsonType: String = "string",
    val enum: List<String>? = null,
    val pattern: String? = null,
    val items: ParamTypeInfo? = null,
)

@Serializable
data class BodyHint(
    val fields: List<String> = emptyList(),
    val required: List<String> = emptyList(),
)

@Serializable
data class BodySpec(
    val contentType: String,
    val schemaName: String? = null,
    val required: Boolean = false,
    val hint: BodyHint? = null,
)

/**
 * One Discord HTTP API operation, generated from Discord's official OpenAPI specification
 * (https://github.com/discord/discord-api-spec). Loaded at runtime from
 * `discord_endpoints.json` on the classpath and turned into one MCP tool each.
 */
@Serializable
data class EndpointSpec(
    val operationId: String,
    val method: String,
    val path: String,
    val pathParams: List<ParamSpec> = emptyList(),
    val queryParams: List<ParamSpec> = emptyList(),
    val body: BodySpec? = null,
    val authType: String = "bot",
) {
    /** MCP tool name for this operation. */
    val toolName: String get() = "discord_$operationId"

    /**
     * Coarse resource category derived from the first path segment (e.g. "/guilds/{id}/..." ->
     * "guilds", "/channels/{id}/messages" -> "channels"). Used to group and filter tools without
     * requiring per-endpoint metadata in discord_endpoints.json.
     */
    val category: String get() = path.trim('/').substringBefore('/').ifBlank { "misc" }
}
