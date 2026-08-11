package com.discordmcp.discord

import kotlin.test.Test
import kotlin.test.assertTrue

class EndpointRegistryTest {
    @Test
    fun testEndpointRegistryLoadsSuccessfully() {
        val endpoints = EndpointRegistry.endpoints
        assertTrue(endpoints.isNotEmpty(), "Endpoints list should not be empty")
    }
}
