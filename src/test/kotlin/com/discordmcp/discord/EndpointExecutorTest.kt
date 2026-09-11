package com.discordmcp.discord

import com.discordmcp.config.AppConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EndpointExecutorTest {

    @Test
    fun testEndpointExecutorReturnsPureJsonWithoutHttpPrefix() = runBlocking {
        val server = embeddedServer(CIO, port = 0) {
            routing {
                get("/test") {
                    call.respondText(
                        text = """{"id": "123", "name": "guild"}""",
                        contentType = io.ktor.http.ContentType.Application.Json,
                        status = io.ktor.http.HttpStatusCode.OK,
                    )
                }
            }
        }
        server.start(wait = false)
        val serverPort = server.engine.resolvedConnectors().first().port

        val config = AppConfig(
            apiBaseUrl = "http://127.0.0.1:$serverPort",
            botToken = "dummy_token",
        )
        val client = DiscordHttpClient(config)

        val spec = EndpointSpec(
            operationId = "test_op",
            method = "GET",
            path = "/test",
            authType = "bot",
        )

        val result = EndpointExecutor.call(
            spec = spec,
            client = client,
            config = config,
            pathArgs = JsonObject(emptyMap()),
            queryArgs = JsonObject(emptyMap()),
            bodyObject = null,
            files = null,
            auditLogReason = null,
            authOverride = null,
        )

        server.stop(500, 1000)
        client.close()

        assertFalse(result.isError == true)
        val text = (result.content.first() as TextContent).text
        assertFalse(text.startsWith("HTTP 200"), "Response text must not start with HTTP 200 prefix")
        assertTrue(text.contains("\"id\": \"123\""), "Response text should contain JSON body")
        assertTrue(text.contains("\"name\": \"guild\""), "Response text should contain JSON body")
    }

    @Test
    fun testEndpointExecutorHandlesNoContentGracefully() = runBlocking {
        val server = embeddedServer(CIO, port = 0) {
            routing {
                get("/nocontent") {
                    call.respondText(
                        text = "",
                        status = io.ktor.http.HttpStatusCode.NoContent,
                    )
                }
            }
        }
        server.start(wait = false)
        val serverPort = server.engine.resolvedConnectors().first().port

        val config = AppConfig(
            apiBaseUrl = "http://127.0.0.1:$serverPort",
            botToken = "dummy_token",
        )
        val client = DiscordHttpClient(config)

        val spec = EndpointSpec(
            operationId = "test_nocontent",
            method = "GET",
            path = "/nocontent",
            authType = "bot",
        )

        val result = EndpointExecutor.call(
            spec = spec,
            client = client,
            config = config,
            pathArgs = JsonObject(emptyMap()),
            queryArgs = JsonObject(emptyMap()),
            bodyObject = null,
            files = null,
            auditLogReason = null,
            authOverride = null,
        )

        server.stop(500, 1000)
        client.close()

        assertFalse(result.isError == true)
        val text = (result.content.first() as TextContent).text
        assertEquals("{}", text, "Empty response body should fallback to '{}'")
    }
}

