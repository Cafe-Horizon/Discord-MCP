package com.discordmcp.discord

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class EndpointRegistryTest {
    @Test
    fun testEndpointRegistryLoadsSuccessfully() {
        val endpoints = EndpointRegistry.endpoints
        assertTrue(endpoints.isNotEmpty(), "Endpoints list should not be empty")
    }

    /**
     * Discord's OpenAPI spec offers both application/json and multipart/form-data content types
     * for these operations (multipart being how you attach files -- see DiscordHttpClient.execute
     * and generate_discord_endpoints.py's CONTENT_TYPE_PREFERENCE). If CONTENT_TYPE_PREFERENCE
     * regresses to prefer application/json again, discord_endpoints.json would silently drop the
     * ability to pass 'files' on these tools -- with no other test catching it, since
     * testEndpointRegistryLoadsSuccessfully only checks the list is non-empty. Pinning the known
     * multipart-only-relevant operations here turns that into a build failure instead.
     */
    @Test
    fun testFileUploadEndpointsUseMultipartFormData() {
        val mustBeMultipart = setOf(
            "create_message",
            "update_message",
            "create_thread",
            "create_channel_invite",
            "create_interaction_response",
            "create_lobby_message",
            "execute_webhook",
            "execute_slack_compatible_webhook",
            "update_original_webhook_message",
            "update_webhook_message",
            "upload_application_attachment",
            "create_guild_sticker",
        )

        val byOperationId = EndpointRegistry.endpoints.associateBy { it.operationId }
        val failures = mustBeMultipart.mapNotNull { operationId ->
            val spec = byOperationId[operationId] ?: return@mapNotNull "$operationId: not found in discord_endpoints.json"
            val contentType = spec.body?.contentType
            if (contentType != "multipart/form-data") {
                "$operationId: expected body.contentType 'multipart/form-data', got '$contentType'"
            } else {
                null
            }
        }

        if (failures.isNotEmpty()) {
            fail(
                "The following endpoints must accept multipart/form-data so the 'files' argument " +
                    "works (see EndpointExecutor/DiscordHttpClient), but don't:\n" +
                    failures.joinToString("\n"),
            )
        }
    }
}
