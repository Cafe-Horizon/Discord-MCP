package com.discordmcp.config

/** Which MCP server transport to start. */
enum class TransportMode {
    /** STDIO — for client-spawned child processes (e.g. Claude Desktop). */
    STDIO,

    /** HTTP — exposes Stateless Streamable HTTP at `/mcp` (MCP spec 2026-07-28). */
    HTTP,
}

/**
 * Immutable configuration data structure for the application.
 */
data class AppConfig(
    val botToken: String? = System.getenv("DISCORD_BOT_TOKEN")?.takeIf { it.isNotBlank() },
    val botTokens: Map<String, String> = parseBotTokens(System.getenv("DISCORD_BOT_TOKENS"), System.getenv("DISCORD_BOT_TOKEN")),
    val clientId: String? = System.getenv("DISCORD_CLIENT_ID")?.takeIf { it.isNotBlank() },
    val clientSecret: String? = System.getenv("DISCORD_CLIENT_SECRET")?.takeIf { it.isNotBlank() },
    val apiBaseUrl: String = System.getenv("DISCORD_API_BASE_URL")?.takeIf { it.isNotBlank() }
        ?: "https://discord.com/api/v10",
    val gatewayUrl: String = System.getenv("DISCORD_GATEWAY_URL")?.takeIf { it.isNotBlank() }
        ?: "wss://gateway.discord.gg/?v=10&encoding=json",
    val transport: TransportMode = when (System.getenv("MCP_TRANSPORT")?.trim()?.lowercase()) {
        "http" -> TransportMode.HTTP
        else -> TransportMode.STDIO
    },
    val httpHost: String = System.getenv("MCP_HTTP_HOST")?.takeIf { it.isNotBlank() } ?: "0.0.0.0",
    val httpPort: Int = System.getenv("MCP_HTTP_PORT")?.toIntOrNull() ?: 8080,
    val httpAllowedHosts: List<String>? = System.getenv("MCP_ALLOWED_HOSTS")?.toHostList(),
    val httpAllowedOrigins: List<String>? = System.getenv("MCP_ALLOWED_ORIGINS")?.toHostList(),

    // --- Tool surface / context-usage controls ---------------------------------------------
    // The full Discord REST surface is ~250 MCP tools; every registered tool's name,
    // description, and input schema is loaded into the connecting client's context up front.
    // These knobs let an operator shrink that footprint. See docs/SETUP.md.

    /** If set, only endpoints whose [EndpointSpec.category] is in this set are registered. */
    val toolCategories: Set<String>? = System.getenv("DISCORD_MCP_TOOL_CATEGORIES")?.toCsvSet(),

    /** If set, only endpoints whose tool name matches this regex are registered. */
    val includeToolsPattern: Regex? = System.getenv("DISCORD_MCP_INCLUDE_TOOLS")
        ?.takeIf { it.isNotBlank() }?.let { Regex(it) },

    /** If set, endpoints whose tool name matches this regex are excluded (applied after include/category). */
    val excludeToolsPattern: Regex? = System.getenv("DISCORD_MCP_EXCLUDE_TOOLS")
        ?.takeIf { it.isNotBlank() }?.let { Regex(it) },

    /** If true, only GET (read-only) endpoints are registered, regardless of other filters. */
    val readOnly: Boolean = System.getenv("DISCORD_MCP_READONLY").toBooleanFlag(default = false),

    /**
     * If true, replaces the ~250 individually-registered REST tools with two generic tools
     * (`discord_search_tools` + `discord_call_tool`) that resolve the actual endpoint at call
     * time. Cuts baseline context usage to a couple of tool schemas instead of one per endpoint,
     * at the cost of the client no longer seeing each operation as its own named tool up front.
     * Category/include/exclude/readOnly filters still apply to what remains reachable.
     */
    val lazyTools: Boolean = System.getenv("DISCORD_MCP_LAZY_TOOLS").toBooleanFlag(default = false),

    /** If false, the 5 Gateway management tools (discord_gateway_*) are not registered. */
    val enableGateway: Boolean = System.getenv("DISCORD_MCP_ENABLE_GATEWAY").toBooleanFlag(default = true),

    /** If false, authOverride parameter is disabled and excluded from schemas for security. */
    val allowAuthOverride: Boolean = System.getenv("DISCORD_MCP_ALLOW_AUTH_OVERRIDE").toBooleanFlag(default = false),

    /** If false, reading files via filePath is disabled. */
    val allowFilePath: Boolean = System.getenv("DISCORD_MCP_ALLOW_FILE_PATH").toBooleanFlag(default = true),

    /** Restricts filePath reading to files strictly within this directory if set. */
    val allowedFileDir: String? = System.getenv("DISCORD_MCP_ALLOWED_FILE_DIR")?.takeIf { it.isNotBlank() },
) {
    companion object {
        private fun parseBotTokens(jsonString: String?, singleToken: String?): Map<String, String> {
            val map = mutableMapOf<String, String>()
            if (!singleToken.isNullOrBlank()) {
                map["default"] = singleToken
            }
            if (!jsonString.isNullOrBlank()) {
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val parsed = json.decodeFromString<Map<String, String>>(jsonString)
                map.putAll(parsed)
            }
            return map
        }

        private fun String.toHostList(): List<String>? =
            split(",").map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }

        private fun String.toCsvSet(): Set<String>? =
            split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet().takeIf { it.isNotEmpty() }

        private fun String?.toBooleanFlag(default: Boolean): Boolean =
            this?.trim()?.lowercase()?.let { it == "true" || it == "1" || it == "yes" } ?: default
    }
}

/**
 * Global default configuration singleton.
 */
object Config {
    private var defaultAppConfig = AppConfig()

    val current: AppConfig get() = defaultAppConfig

    val botToken: String? get() = current.botToken
    val clientId: String? get() = current.clientId
    val clientSecret: String? get() = current.clientSecret
    val apiBaseUrl: String get() = current.apiBaseUrl
    val gatewayUrl: String get() = current.gatewayUrl
    val transport: TransportMode get() = current.transport
    val httpHost: String get() = current.httpHost
    val httpPort: Int get() = current.httpPort
    val httpAllowedHosts: List<String>? get() = current.httpAllowedHosts
    val httpAllowedOrigins: List<String>? get() = current.httpAllowedOrigins
    val allowAuthOverride: Boolean get() = current.allowAuthOverride
    val allowFilePath: Boolean get() = current.allowFilePath
    val allowedFileDir: String? get() = current.allowedFileDir
}
