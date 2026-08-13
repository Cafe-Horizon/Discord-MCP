package com.discordmcp

import com.discordmcp.config.AppConfig
import com.discordmcp.discord.DiscordHttpClient
import com.discordmcp.gateway.GatewayClient
import com.discordmcp.gateway.VoiceTools
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlin.test.Test
import kotlin.test.assertNotNull

class VoiceToolsTest {

    @Test
    fun testVoiceToolsRegistration() {
        val server = Server(
            serverInfo = Implementation("test", "1.0.0"),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))),
        )
        val config = AppConfig(botToken = "dummy_token")
        val httpClient = DiscordHttpClient(config)
        val gatewayClient = GatewayClient(config)

        VoiceTools.registerAll(server, gatewayClient, httpClient, config)
        assertNotNull(server)
    }
}
