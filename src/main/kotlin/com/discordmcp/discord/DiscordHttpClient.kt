package com.discordmcp.discord

import com.discordmcp.config.Config
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/** Result of a single Discord REST API call. */
data class DiscordApiResult(
    val status: Int,
    val statusText: String,
    val body: String,
    val rateLimitedRetries: Int = 0,
)

/**
 * Thin, generic HTTP client that executes any [EndpointSpec] against the Discord REST API.
 * Handles Bot-token authentication (with per-call override), audit log reasons, JSON /
 * multipart / form-urlencoded request bodies, and 429 rate-limit backoff.
 */
private fun encodePathSegment(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

class DiscordHttpClient {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    val engine = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
    }

    suspend fun execute(
        spec: EndpointSpec,
        pathValues: Map<String, String>,
        queryValues: Map<String, List<String>>,
        bodyObject: JsonObject?,
        files: JsonArray?,
        auditLogReason: String?,
        authOverride: String?,
    ): DiscordApiResult {
        var resolvedPath = spec.path
        for (p in spec.pathParams) {
            val value = pathValues[p.name]
                ?: return DiscordApiResult(0, "Missing Parameter", "Required path parameter '${p.name}' was not provided.")
            resolvedPath = resolvedPath.replace("{${p.name}}", encodePathSegment(value))
        }

        val urlBuilder = URLBuilder(Config.apiBaseUrl.trimEnd('/') + resolvedPath)
        for ((name, values) in queryValues) {
            for (v in values) urlBuilder.parameters.append(name, v)
        }
        val url = urlBuilder.buildString()

        val authHeader = authOverride
            ?: when (spec.authType) {
                "none" -> null
                else -> Config.botToken?.let { "Bot $it" }
            }

        var attempt = 0
        while (true) {
            attempt++
            val response: HttpResponse = engine.request(url) {
                method = HttpMethod.parse(spec.method)
                if (authHeader != null) header(HttpHeaders.Authorization, authHeader)
                if (!auditLogReason.isNullOrBlank()) header("X-Audit-Log-Reason", auditLogReason)
                header(HttpHeaders.UserAgent, "DiscordBot (discord-mcp, 1.0.0)")

                when (spec.body?.contentType) {
                    "application/json" -> {
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(JsonObject.serializer(), bodyObject ?: JsonObject(emptyMap())))
                    }

                    "multipart/form-data" -> {
                        val parts = formData {
                            if (bodyObject != null) {
                                append("payload_json", json.encodeToString(JsonObject.serializer(), bodyObject))
                            }
                            files?.forEachIndexed { index, element ->
                                val obj = element.jsonObject
                                val filename = obj["filename"]?.jsonPrimitive?.contentOrNull ?: "file$index"
                                val b64 = obj["contentBase64"]?.jsonPrimitive?.contentOrNull
                                val mime = obj["contentType"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
                                if (b64 != null) {
                                    val bytes = Base64.getDecoder().decode(b64)
                                    append(
                                        "files[$index]",
                                        bytes,
                                        io.ktor.http.Headers.build {
                                            append(HttpHeaders.ContentType, mime)
                                            append(HttpHeaders.ContentDisposition, "form-data; name=\"files[$index]\"; filename=\"$filename\"")
                                        },
                                    )
                                }
                            }
                        }
                        setBody(MultiPartFormDataContent(parts))
                    }

                    "application/x-www-form-urlencoded" -> {
                        val formParams = Parameters.build {
                            bodyObject?.forEach { (key, value) ->
                                val prim = value as? JsonPrimitive
                                val text = prim?.contentOrNull
                                    ?: prim?.booleanOrNull?.toString()
                                    ?: prim?.doubleOrNull?.toString()
                                if (text != null) append(key, text)
                            }
                        }
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(formParams.formUrlEncode())
                    }

                    else -> { /* no body */ }
                }
            }

            val text = response.body<String>()

            if (response.status.value == 429 && attempt <= 3) {
                val retryAfterSeconds = extractRetryAfter(text) ?: response.headers["Retry-After"]?.toDoubleOrNull()
                val waitMs = ((retryAfterSeconds ?: 1.0) * 1000).toLong().coerceIn(100, 9_000)
                delay(waitMs)
                continue
            }

            return DiscordApiResult(
                status = response.status.value,
                statusText = if (response.status.isSuccess()) "OK" else response.status.description,
                body = text,
                rateLimitedRetries = attempt - 1,
            )
        }
    }

    private fun extractRetryAfter(body: String): Double? = runCatching {
        json.parseToJsonElement(body).jsonObject["retry_after"]?.jsonPrimitive?.doubleOrNull
    }.getOrNull()

    fun close() = engine.close()
}
