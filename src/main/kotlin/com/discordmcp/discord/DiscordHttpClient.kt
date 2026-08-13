package com.discordmcp.discord

import com.discordmcp.config.AppConfig
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
import java.net.URLEncoder
import java.util.Base64

/**
 * Thin, generic HTTP client that executes any [EndpointSpec] against the Discord REST API.
 * Handles Bot-token authentication (with per-call override), audit log reasons, JSON /
 * multipart / form-urlencoded request bodies, and 429 rate-limit backoff.
 */
class DiscordHttpClient(
    private val config: AppConfig = Config.current,
) {
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
    ): DiscordResult {
        var resolvedPath = spec.path
        for (p in spec.pathParams) {
            val value = pathValues[p.name]
                ?: return DiscordResult.Error(0, "Missing Parameter", "Required path parameter '${p.name}' was not provided.")
            resolvedPath = resolvedPath.replace("{${p.name}}", encodePathSegment(value))
        }

        val urlBuilder = URLBuilder(config.apiBaseUrl.trimEnd('/') + resolvedPath)
        for ((name, values) in queryValues) {
            for (v in values) urlBuilder.parameters.append(name, v)
        }
        val url = urlBuilder.buildString()

        val authHeader = authOverride
            ?: when (spec.authType) {
                "none" -> null
                else -> config.botToken?.let { "Bot $it" }
            }

        var attempt = 0
        while (true) {
            attempt++
            val response: HttpResponse = try {
                engine.request(url) {
                    method = HttpMethod.parse(spec.method)
                    if (authHeader != null) header(HttpHeaders.Authorization, authHeader)
                    if (!auditLogReason.isNullOrBlank()) header("X-Audit-Log-Reason", auditLogReason)
                    header(HttpHeaders.UserAgent, "DiscordBot (discord-mcp, 1.0.1)")

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
                                    val filePath = obj["filePath"]?.jsonPrimitive?.contentOrNull
                                    val mime = obj["contentType"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
                                    val bytes = when {
                                        filePath != null -> try {
                                            if (!config.allowFilePath) {
                                                System.err.println("[discord-mcp] SECURITY WARNING: filePath reading is disabled (DISCORD_MCP_ALLOW_FILE_PATH=false). Skipping '$filePath'")
                                                null
                                            } else {
                                                val targetFile = java.io.File(filePath).canonicalFile
                                                val allowedDir = config.allowedFileDir?.let { java.io.File(it).canonicalFile }
                                                if (allowedDir != null && !targetFile.path.startsWith(allowedDir.path)) {
                                                    System.err.println("[discord-mcp] SECURITY WARNING: filePath '$filePath' is outside allowed directory '${allowedDir.path}'. Access denied.")
                                                    null
                                                } else if (targetFile.exists() && targetFile.isFile) {
                                                    targetFile.readBytes()
                                                } else {
                                                    null
                                                }
                                            }
                                        } catch (e: Exception) {
                                            System.err.println("[discord-mcp] Error reading file at $filePath: ${e.message}")
                                            null
                                        }
                                        b64 != null -> Base64.getDecoder().decode(b64)
                                        else -> null
                                    }
                                    if (bytes != null) {
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
            } catch (e: Exception) {
                return DiscordResult.Error(
                    status = 0,
                    statusText = "Request Failed",
                    message = "Network or execution error: ${e.message}",
                )
            }

            val text = response.body<String>()

            if (response.status.value == 429 && attempt <= 3) {
                val retryAfterSeconds = extractRetryAfter(text) ?: response.headers["Retry-After"]?.toDoubleOrNull()
                val waitMs = ((retryAfterSeconds ?: 1.0) * 1000).toLong().coerceIn(100, 9_000)
                delay(waitMs)
                continue
            }

            return if (response.status.isSuccess()) {
                DiscordResult.Success(
                    status = response.status.value,
                    statusText = "OK",
                    body = text,
                    rateLimitedRetries = attempt - 1,
                )
            } else {
                DiscordResult.Error(
                    status = response.status.value,
                    statusText = response.status.description,
                    message = text,
                )
            }
        }
    }

    private fun extractRetryAfter(body: String): Double? = runCatching {
        json.parseToJsonElement(body).jsonObject["retry_after"]?.jsonPrimitive?.doubleOrNull
    }.getOrNull()

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun close() = engine.close()
}
