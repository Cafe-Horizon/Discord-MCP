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
) {
    companion object {
        private fun String.toHostList(): List<String>? =
            split(",").map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
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
}
