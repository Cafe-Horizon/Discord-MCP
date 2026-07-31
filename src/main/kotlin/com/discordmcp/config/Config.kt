package com.discordmcp.config

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
 */
object Config {
    val botToken: String? = System.getenv("DISCORD_BOT_TOKEN")?.takeIf { it.isNotBlank() }
    val clientId: String? = System.getenv("DISCORD_CLIENT_ID")?.takeIf { it.isNotBlank() }
    val clientSecret: String? = System.getenv("DISCORD_CLIENT_SECRET")?.takeIf { it.isNotBlank() }
    val apiBaseUrl: String = System.getenv("DISCORD_API_BASE_URL")?.takeIf { it.isNotBlank() }
        ?: "https://discord.com/api/v10"
    val gatewayUrl: String = System.getenv("DISCORD_GATEWAY_URL")?.takeIf { it.isNotBlank() }
        ?: "wss://gateway.discord.gg/?v=10&encoding=json"
}
