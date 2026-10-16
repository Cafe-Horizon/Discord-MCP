package com.discordmcp.discord

import com.discordmcp.config.AppConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteAttachmentFetcherTest {

    private lateinit var server: EmbeddedServer<*, *>
    private var port = 0

    /** Raw request URIs seen by the "storage" routes, plus whether an Authorization header was present. */
    private val storageRequests = CopyOnWriteArrayList<Pair<String, Boolean>>()
    private val discordBodies = CopyOnWriteArrayList<String>()

    private val presignedQuery =
        "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIDEXAMPLE%2F20261016%2Fus-east-1%2Fs3%2Faws4_request" +
            "&X-Amz-Expires=300&X-Amz-Signature=abc%2Bdef%3D"

    @BeforeTest
    fun startServer() {
        server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/bucket/{name}") {
                    storageRequests.add(call.request.uri to (call.request.header(HttpHeaders.Authorization) != null))
                    call.respondBytes("hello from storage".toByteArray(), ContentType.Text.Plain)
                }
                get("/big") {
                    storageRequests.add(call.request.uri to false)
                    call.respondBytes(ByteArray(2048) { 'x'.code.toByte() }, ContentType.Application.OctetStream)
                }
                get("/redirect") {
                    call.response.header(HttpHeaders.Location, "/bucket/redirected.txt")
                    call.respond(HttpStatusCode.Found)
                }
                get("/loop") {
                    call.response.header(HttpHeaders.Location, "/loop")
                    call.respond(HttpStatusCode.Found)
                }
                get("/missing") {
                    call.respond(HttpStatusCode.Forbidden)
                }
                post("/channels/{id}/messages") {
                    discordBodies.add(call.receiveText())
                    call.respondText("""{"id":"999"}""", ContentType.Application.Json)
                }
            }
        }
        server.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @AfterTest
    fun stopServer() {
        server.stop(100, 500)
    }

    private fun config(
        allowPrivate: Boolean = true,
        allowUrl: Boolean = true,
        hosts: Set<String>? = null,
        maxBytes: Long = 25L * 1024 * 1024,
    ) = AppConfig(
        apiBaseUrl = "http://127.0.0.1:$port",
        botToken = "dummy_token",
        allowFileUrl = allowUrl,
        allowPrivateUrls = allowPrivate,
        allowedUrlHosts = hosts,
        maxFileUrlBytes = maxBytes,
    )

    private val createMessage = EndpointSpec(
        operationId = "create_message",
        method = "POST",
        path = "/channels/{channel_id}/messages",
        pathParams = listOf(ParamSpec("channel_id", required = true)),
        body = BodySpec(contentType = "multipart/form-data"),
    )

    private fun urlFile(url: String, extra: Map<String, String> = emptyMap()) = JsonArray(
        listOf(JsonObject(mapOf("url" to JsonPrimitive(url)) + extra.mapValues { JsonPrimitive(it.value) })),
    )

    private suspend fun send(config: AppConfig, files: JsonArray): DiscordResult {
        val client = DiscordHttpClient(config)
        return try {
            client.execute(
                createMessage,
                mapOf("channel_id" to "1"),
                emptyMap(),
                JsonObject(mapOf("content" to JsonPrimitive("see attached"))),
                files,
                null,
                null,
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun uploadsAttachmentFromPresignedUrl() = runBlocking {
        val url = "http://127.0.0.1:$port/bucket/report%20final.txt?$presignedQuery"
        val result = send(config(), urlFile(url))

        assertTrue(result is DiscordResult.Success, "Expected success but got $result")
        assertEquals(1, storageRequests.size)
        val (uri, hadAuth) = storageRequests.single()
        assertEquals("/bucket/report%20final.txt?$presignedQuery", uri, "Presigned query string must be sent unmodified")
        assertFalse(hadAuth, "The bot token must never be sent to the attachment host")

        val body = discordBodies.single()
        assertTrue(body.contains("name=\"files[0]\"; filename=\"report final.txt\""), body)
        assertTrue(body.contains("Content-Type: text/plain"), body)
        assertTrue(body.contains("hello from storage"), body)
        assertTrue(body.contains("name=payload_json") || body.contains("name=\"payload_json\""), body)
    }

    @Test
    fun explicitFilenameAndContentTypeOverrideUrlMetadata() = runBlocking {
        val url = "http://127.0.0.1:$port/bucket/blob?$presignedQuery"
        val result = send(config(), urlFile(url, mapOf("filename" to "notes.md", "contentType" to "text/markdown")))

        assertTrue(result is DiscordResult.Success, "Expected success but got $result")
        val body = discordBodies.single()
        assertTrue(body.contains("filename=\"notes.md\""), body)
        assertTrue(body.contains("Content-Type: text/markdown"), body)
    }

    @Test
    fun followsRedirects() = runBlocking {
        val result = send(config(), urlFile("http://127.0.0.1:$port/redirect"))

        assertTrue(result is DiscordResult.Success, "Expected success but got $result")
        assertTrue(discordBodies.single().contains("filename=\"redirected.txt\""))
    }

    @Test
    fun rejectsRedirectLoops() = runBlocking {
        val result = send(config(), urlFile("http://127.0.0.1:$port/loop"))

        assertTrue(result is DiscordResult.Error && result.message.contains("Too many redirects"), "$result")
        assertTrue(discordBodies.isEmpty())
    }

    @Test
    fun privateAddressesAreBlockedByDefault() = runBlocking {
        val result = send(config(allowPrivate = false), urlFile("http://127.0.0.1:$port/bucket/a.txt?$presignedQuery"))

        assertTrue(result is DiscordResult.Error && result.message.contains("non-public address"), "$result")
        assertTrue(storageRequests.isEmpty(), "No request may reach a private host")
        assertTrue(discordBodies.isEmpty(), "Discord must not be called when an attachment fails")
        assertFalse(result.message.contains("X-Amz-Signature"), "Signatures must not be echoed")
    }

    @Test
    fun hostAllowlistIsEnforced() = runBlocking {
        val result = send(config(hosts = setOf("*.amazonaws.com")), urlFile("http://127.0.0.1:$port/bucket/a.txt"))

        assertTrue(result is DiscordResult.Error && result.message.contains("DISCORD_MCP_ALLOWED_URL_HOSTS"), "$result")
        assertTrue(storageRequests.isEmpty())
    }

    @Test
    fun urlDownloadsCanBeDisabled() = runBlocking {
        val result = send(config(allowUrl = false), urlFile("http://127.0.0.1:$port/bucket/a.txt"))

        assertTrue(result is DiscordResult.Error && result.message.contains("DISCORD_MCP_ALLOW_FILE_URL"), "$result")
        assertTrue(storageRequests.isEmpty())
    }

    @Test
    fun rejectsNonHttpSchemes() = runBlocking {
        val result = send(config(), urlFile("file:///etc/passwd"))

        assertTrue(result is DiscordResult.Error && result.message.contains("scheme"), "$result")
    }

    @Test
    fun enforcesMaximumSize() = runBlocking {
        val result = send(config(maxBytes = 1024), urlFile("http://127.0.0.1:$port/big"))

        assertTrue(result is DiscordResult.Error && result.message.contains("maximum size"), "$result")
        assertTrue(discordBodies.isEmpty())
    }

    @Test
    fun reportsHttpErrorsFromStorage() = runBlocking {
        val result = send(config(), urlFile("http://127.0.0.1:$port/missing?$presignedQuery"))

        assertTrue(result is DiscordResult.Error && result.message.contains("HTTP 403"), "$result")
        assertFalse(result.message.contains("X-Amz-Signature"))
    }

    @Test
    fun hostMatching() {
        assertTrue(RemoteAttachmentFetcher.hostMatches("cdn.example.com", "cdn.example.com"))
        assertFalse(RemoteAttachmentFetcher.hostMatches("evil.example.com", "cdn.example.com"))
        assertTrue(RemoteAttachmentFetcher.hostMatches("bucket.s3.amazonaws.com", "*.amazonaws.com"))
        assertTrue(RemoteAttachmentFetcher.hostMatches("bucket.s3.amazonaws.com", ".amazonaws.com"))
        assertFalse(RemoteAttachmentFetcher.hostMatches("amazonaws.com", "*.amazonaws.com"))
        assertFalse(RemoteAttachmentFetcher.hostMatches("evilamazonaws.com", "*.amazonaws.com"))
    }

    @Test
    fun nonPublicAddressDetection() {
        fun ip(s: String) = InetAddress.getByName(s)
        listOf(
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1",
            "0.0.0.0", "::1", "fd00::1", "fe80::1", "::ffff:127.0.0.1", "224.0.0.1", "255.255.255.255",
        ).forEach { assertTrue(RemoteAttachmentFetcher.isNonPublic(ip(it)), "$it should be non-public") }
        listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111").forEach {
            assertFalse(RemoteAttachmentFetcher.isNonPublic(ip(it)), "$it should be public")
        }
    }

}
