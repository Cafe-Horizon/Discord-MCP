package com.discordmcp

import com.discordmcp.config.AppConfig
import com.discordmcp.discord.DiscordHttpClient
import com.discordmcp.discord.InteractionTools
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlin.test.Test
import kotlin.test.assertNotNull

class InteractionToolsTest {

    @Test
    fun testInteractionToolsRegistration() {
        val server = Server(
            serverInfo = Implementation("test", "1.0.0"),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))),
        )
        val config = AppConfig(botToken = "dummy_token")
        val client = DiscordHttpClient(config)

        try {
            InteractionTools.registerAll(server, client, config)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
        assertNotNull(server)
    }
}
