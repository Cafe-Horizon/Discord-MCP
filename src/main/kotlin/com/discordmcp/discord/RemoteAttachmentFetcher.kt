package com.discordmcp.discord

import com.discordmcp.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.hostWithPortIfSpecified
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URLDecoder

/** Bytes and response metadata of an attachment downloaded by [RemoteAttachmentFetcher]. */
class FetchedAttachment(
    val bytes: ByteArray,
    /** `Content-Type` of the response (parameters such as charset preserved), if any. */
    val contentType: String?,
    /** Last path segment of the final (post-redirect) URL, if it looks like a file name. */
    val filename: String?,
)

class AttachmentFetchException(message: String) : Exception(message)

/**
 * Downloads message attachments from external HTTP(S) URLs — typically presigned object-storage
 * URLs (S3 / GCS / R2 / Azure SAS) — so they can be re-uploaded to Discord as multipart parts
 * without the MCP client having to inline them as Base64.
 *
 * Because the URL comes from the (possibly untrusted) MCP caller, every hop is validated
 * against SSRF before any request is sent:
 *  - only `http` / `https` schemes are accepted;
 *  - if `DISCORD_MCP_ALLOWED_URL_HOSTS` is set, the host must match one of its entries;
 *  - unless `DISCORD_MCP_ALLOW_PRIVATE_URLS=true`, hosts resolving to loopback, private,
 *    link-local (incl. cloud metadata 169.254.169.254), CGNAT, multicast or unspecified
 *    addresses are rejected;
 *  - redirects are followed manually (max [MAX_REDIRECTS]) and each target is re-validated;
 *  - the body is streamed and aborted once it exceeds `DISCORD_MCP_MAX_FILE_URL_BYTES`.
 *
 * No Authorization header is ever sent: presigned URLs carry their credentials in the query
 * string, and the Discord bot token must never leak to a third-party host.
 */
class RemoteAttachmentFetcher(
    private val config: AppConfig,
) {
    private val engine = HttpClient(CIO) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
    }

    suspend fun fetch(rawUrl: String): FetchedAttachment {
        if (!config.allowFileUrl) {
            throw AttachmentFetchException("Attachment download via 'url' is disabled (DISCORD_MCP_ALLOW_FILE_URL=false).")
        }

        var current = parseAndValidate(rawUrl)
        var redirects = 0
        while (true) {
            val outcome = engine.prepareGet(current) {
                header(HttpHeaders.UserAgent, "discord-mcp attachment fetcher")
            }.execute { response ->
                val status = response.status
                if (status.value in 300..399) {
                    val location = response.headers[HttpHeaders.Location]
                        ?: throw AttachmentFetchException("Redirect ${status.value} from '${redact(current)}' has no Location header.")
                    return@execute Redirect(URLBuilder(current).takeFrom(location).build())
                }
                if (!status.isSuccess()) {
                    throw AttachmentFetchException("Downloading '${redact(current)}' failed: HTTP ${status.value} ${status.description}.")
                }

                val limit = config.maxFileUrlBytes
                val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (declared != null && declared > limit) {
                    throw AttachmentFetchException(tooLarge(current, limit))
                }

                val channel = response.bodyAsChannel()
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read == -1) break
                    if (out.size().toLong() + read > limit) throw AttachmentFetchException(tooLarge(current, limit))
                    out.write(buffer, 0, read)
                }

                Downloaded(
                    FetchedAttachment(
                        bytes = out.toByteArray(),
                        contentType = response.headers[HttpHeaders.ContentType]?.takeIf { it.isNotBlank() },
                        filename = filenameFrom(current),
                    ),
                )
            }

            when (outcome) {
                is Downloaded -> return outcome.attachment
                is Redirect -> {
                    if (++redirects > MAX_REDIRECTS) {
                        throw AttachmentFetchException("Too many redirects (> $MAX_REDIRECTS) while downloading '${redact(Url(rawUrl))}'.")
                    }
                    current = validate(outcome.target)
                }
            }
        }
    }

    private sealed interface Outcome
    private class Downloaded(val attachment: FetchedAttachment) : Outcome
    private class Redirect(val target: Url) : Outcome

    private suspend fun parseAndValidate(rawUrl: String): Url {
        val url = runCatching { Url(rawUrl.trim()) }.getOrNull()
            ?: throw AttachmentFetchException("Invalid attachment URL '$rawUrl'.")
        return validate(url)
    }

    private suspend fun validate(url: Url): Url {
        val scheme = url.protocol.name.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw AttachmentFetchException("Unsupported URL scheme '$scheme' for attachment (only http/https are allowed).")
        }
        val host = url.host.lowercase().removePrefix("[").removeSuffix("]")
        if (host.isBlank()) throw AttachmentFetchException("Attachment URL has no host.")

        val allowedHosts = config.allowedUrlHosts
        if (allowedHosts != null && allowedHosts.none { hostMatches(host, it) }) {
            throw AttachmentFetchException(
                "Host '$host' is not in DISCORD_MCP_ALLOWED_URL_HOSTS (${allowedHosts.joinToString()}).",
            )
        }

        if (!config.allowPrivateUrls) {
            val addresses = withContext(Dispatchers.IO) {
                runCatching { InetAddress.getAllByName(host) }.getOrNull()
            } ?: throw AttachmentFetchException("Could not resolve attachment host '$host'.")
            val blocked = addresses.firstOrNull { isNonPublic(it) }
            if (blocked != null) {
                throw AttachmentFetchException(
                    "Host '$host' resolves to non-public address ${blocked.hostAddress}; refusing to download. " +
                        "Set DISCORD_MCP_ALLOW_PRIVATE_URLS=true to allow internal hosts.",
                )
            }
        }
        return url
    }

    fun close() = engine.close()

    companion object {
        const val MAX_REDIRECTS = 5

        /**
         * `example.com` matches only that host; `*.example.com` / `.example.com` match any
         * subdomain of example.com (but not example.com itself).
         */
        internal fun hostMatches(host: String, pattern: String): Boolean {
            val p = pattern.trim().lowercase()
            if (p.isEmpty()) return false
            val suffix = when {
                p.startsWith("*.") -> p.substring(1)
                p.startsWith(".") -> p
                else -> return host == p
            }
            return host.endsWith(suffix) && host.length > suffix.length
        }

        internal fun isNonPublic(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
            ) {
                return true
            }
            val b = address.address
            return when (address) {
                is Inet4Address -> {
                    val first = b[0].toInt() and 0xFF
                    val second = b[1].toInt() and 0xFF
                    first == 0 || // 0.0.0.0/8 "this network"
                        (first == 100 && second in 64..127) || // 100.64.0.0/10 CGNAT
                        (first == 192 && second == 0 && (b[2].toInt() and 0xFF) == 0) || // 192.0.0.0/24
                        (first == 198 && (second == 18 || second == 19)) || // 198.18.0.0/15 benchmarking
                        first >= 240 // 240.0.0.0/4 reserved + broadcast
                }
                is Inet6Address -> {
                    val first = b[0].toInt() and 0xFF
                    val isUniqueLocal = (first and 0xFE) == 0xFC // fc00::/7
                    val isV4Mapped = b.take(10).all { it.toInt() == 0 } &&
                        (b[10].toInt() and 0xFF) == 0xFF && (b[11].toInt() and 0xFF) == 0xFF
                    isUniqueLocal ||
                        (isV4Mapped && isNonPublic(InetAddress.getByAddress(b.copyOfRange(12, 16))))
                }
                else -> false
            }
        }

        private fun filenameFrom(url: Url): String? {
            val segment = url.encodedPath.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
            return runCatching { URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8") }.getOrDefault(segment)
                // Strip characters that would break the multipart Content-Disposition header.
                .filterNot { it == '"' || it == '\\' || it.isISOControl() }
                .takeIf { it.isNotBlank() && it != "." && it != ".." }
        }

        /** Drops the query string so presigned-URL signatures never end up in logs or tool output. */
        private fun redact(url: Url): String =
            "${url.protocol.name}://${url.hostWithPortIfSpecified}${url.encodedPath}"

        private fun tooLarge(url: Url, limit: Long) =
            "Attachment at '${redact(url)}' exceeds the maximum size of $limit bytes (DISCORD_MCP_MAX_FILE_URL_BYTES)."
    }
}
