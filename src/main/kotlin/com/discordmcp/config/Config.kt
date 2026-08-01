package com.discordmcp.config

/** Which MCP server transport to start. */
enum class TransportMode {
    /** STDIO — for client-spawned child processes (e.g. Claude Desktop). */
    STDIO,

    /** HTTP — exposes Streamable HTTP at `/mcp` and legacy SSE at `/sse` on the same embedded server. */
    HTTP,
}

/**
 * Runtime configuration, populated from environment variables.
 *
 * Required:
 *  - DISCORD_BOT_TOKEN : the bot token used as the default "Authorization: Bot <token>" header
 *                         for every REST call and for the Gateway IDENTIFY payload.
 *
 * Optional:
 *  - DISCORD_CLIENT_ID     : used as a default for OAuth2 tools when not supplied as an argument.
 *  - DISCORD_CLIENT_SECRET : used as a default for OAuth2 tools when not supplied as an argument.
 *  - DISCORD_API_BASE_URL  : override the REST API base URL (defaults to the stable v10 endpoint).
 *  - DISCORD_GATEWAY_URL   : override the Gateway websocket URL.
 *  - MCP_TRANSPORT         : "stdio" (default) or "http". "http" starts an embedded Ktor server
 *                            exposing Streamable HTTP at /mcp and legacy SSE at /sse.
 *  - MCP_HTTP_HOST         : bind host for the HTTP transport (default "0.0.0.0").
 *  - MCP_HTTP_PORT         : bind port for the HTTP transport (default 8080).
 *  - MCP_ALLOWED_HOSTS     : comma-separated hostnames (with port, e.g. "discord-mcp:8085") accepted in the
 *                            incoming `Host` header. The MCP Kotlin SDK enables DNS-rebinding protection by
 *                            default and only trusts localhost/127.0.0.1/[::1], so a reverse-proxied or
 *                            containerized deployment reached via a different hostname (e.g. a Docker Compose
 *                            service name) needs its hostname listed here — otherwise every request gets a
 *                            403, and clients would otherwise have to spoof a "Host: localhost" header just
 *                            to get past this check. Leave unset to keep the secure localhost-only default.
 *  - MCP_ALLOWED_ORIGINS   : comma-separated hostnames accepted in the `Origin` header (scheme/port ignored).
 *                            Only relevant for browser-based clients; irrelevant for Docker-to-Docker or CLI
 *                            clients, which don't send an Origin header. Leave unset unless you need it.
 */
object Config {
    val botToken: String? = System.getenv("DISCORD_BOT_TOKEN")?.takeIf { it.isNotBlank() }
    val clientId: String? = System.getenv("DISCORD_CLIENT_ID")?.takeIf { it.isNotBlank() }
    val clientSecret: String? = System.getenv("DISCORD_CLIENT_SECRET")?.takeIf { it.isNotBlank() }
    val apiBaseUrl: String = System.getenv("DISCORD_API_BASE_URL")?.takeIf { it.isNotBlank() }
        ?: "https://discord.com/api/v10"
    val gatewayUrl: String = System.getenv("DISCORD_GATEWAY_URL")?.takeIf { it.isNotBlank() }
        ?: "wss://gateway.discord.gg/?v=10&encoding=json"

    val transport: TransportMode = when (System.getenv("MCP_TRANSPORT")?.trim()?.lowercase()) {
        "http" -> TransportMode.HTTP
        else -> TransportMode.STDIO
    }
    val httpHost: String = System.getenv("MCP_HTTP_HOST")?.takeIf { it.isNotBlank() } ?: "0.0.0.0"
    val httpPort: Int = System.getenv("MCP_HTTP_PORT")?.toIntOrNull() ?: 8080

    val httpAllowedHosts: List<String>? = System.getenv("MCP_ALLOWED_HOSTS")?.toHostList()
    val httpAllowedOrigins: List<String>? = System.getenv("MCP_ALLOWED_ORIGINS")?.toHostList()

    private fun String.toHostList(): List<String>? =
        split(",").map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
}
