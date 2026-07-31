package com.discordmcp.discord

import kotlinx.serialization.json.Json

/**
 * Loads the full set of Discord REST API operations bundled at build time as
 * `src/main/resources/discord_endpoints.json` (generated from Discord's official
 * OpenAPI spec, plus two hand-added OAuth2 token endpoints not present in that spec).
 */
object EndpointRegistry {
    private val json = Json { ignoreUnknownKeys = true }

    val endpoints: List<EndpointSpec> by lazy {
        val text = requireNotNull(EndpointRegistry::class.java.getResourceAsStream("/discord_endpoints.json")) {
            "discord_endpoints.json resource not found on classpath"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        json.decodeFromString<List<EndpointSpec>>(text)
    }
}
