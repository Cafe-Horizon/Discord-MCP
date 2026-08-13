package com.discordmcp

import com.discordmcp.config.AppConfig
import com.discordmcp.discord.EndpointExecutor
import com.discordmcp.discord.EndpointRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfileTest {

    @Test
    fun testBotTokensParsing() {
        val config = AppConfig(
            botTokens = mapOf("default" to "token1", "admin" to "token2")
        )
        assertEquals("token1", config.botTokens["default"])
        assertEquals("token2", config.botTokens["admin"])
    }

    @Test
    fun testValidAndInvalidProfileHandling() {
        val config = AppConfig(
            botToken = "default_token",
            botTokens = mapOf("default" to "default_token", "admin" to "admin_token")
        )
        val spec = EndpointRegistry.endpoints.first()
        
        runBlocking {
            // Invalid profile should trigger explicit error instead of falling back to default token
            val invalidResult = EndpointExecutor.call(
                spec = spec,
                client = com.discordmcp.discord.DiscordHttpClient(config),
                config = config,
                pathArgs = kotlinx.serialization.json.JsonObject(emptyMap()),
                queryArgs = kotlinx.serialization.json.JsonObject(emptyMap()),
                bodyObject = null,
                files = null,
                auditLogReason = null,
                authOverride = null,
                profile = "unknown_profile"
            )
            assertTrue(invalidResult.isError == true)
            val errorText = (invalidResult.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
            assertTrue(errorText.contains("Unknown profile 'unknown_profile'"))
        }
    }
}
