package com.discordmcp

import com.discordmcp.config.Config
import com.discordmcp.config.TransportMode
import com.discordmcp.discord.DiscordHttpClient
import com.discordmcp.discord.EndpointRegistry
import com.discordmcp.discord.RestToolRegistrar
import com.discordmcp.gateway.GatewayClient
import com.discordmcp.gateway.GatewayTools
import io.ktor.http.HttpMethod
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

/**
 * Discord MCP — exposes (nearly) the entire Discord HTTP API as MCP tools
 * (one tool per operation in Discord's official OpenAPI spec, https://github.com/discord/discord-api-spec),
 * plus a managed Gateway (websocket) session for real-time events.
 *
 * Configuration is via environment variables; see Config.kt / README.md.
 *
 * Supports two transports, selected via MCP_TRANSPORT:
 *  - "stdio" (default): tunnels MCP over stdin/stdout, for client-spawned child processes.
 *  - "http": runs an embedded Ktor server exposing Streamable HTTP at /mcp and legacy SSE at /sse.
 */
fun main() {
    // Some transitive libraries (e.g. kotlin-logging) print a one-line diagnostic banner
    // directly to stdout on first use. Because the STDIO transport reserves stdout
    // exclusively for the MCP JSON-RPC stream, we grab the real stdout handle first and
    // then redirect System.out to stderr, so any stray println from a dependency can never
    // corrupt the protocol stream. This is a no-op concern for the HTTP transport, but doing
    // it unconditionally keeps behavior consistent and costs nothing.
    val realStdOut = System.out
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true))

    if (Config.botToken == null) {
        System.err.println(
            "[discord-mcp] WARNING: DISCORD_BOT_TOKEN is not set. REST tools will fail unless " +
                "an 'authOverride' argument is supplied per call, and the Gateway cannot connect.",
        )
    }

    val restClient = DiscordHttpClient()
    val gatewayClient = GatewayClient()

    val endpointCount = EndpointRegistry.endpoints.size
    System.err.println("[discord-mcp] Loaded $endpointCount Discord REST API operations.")

    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching { restClient.close() }
            runCatching { gatewayClient.shutdown() }
        },
    )

    // Builds a fresh Server instance with all tools registered. The HTTP transports create one
    // session (and therefore one Server) per client connection, so this must be a factory rather
    // than a shared singleton; the underlying restClient/gatewayClient are safely shared across
    // every session since they hold no per-client state (the Gateway itself is a single process-wide
    // connection regardless of how many MCP clients are attached).
    fun buildServer(): Server {
        val server = Server(
            serverInfo = Implementation(
                name = "discord-mcp",
                version = "1.0.0",
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        )
        RestToolRegistrar.registerAll(server, restClient)
        GatewayTools.registerAll(server, gatewayClient)
        return server
    }

    when (Config.transport) {
        TransportMode.STDIO -> runStdio(realStdOut, ::buildServer)
        TransportMode.HTTP -> runHttp(::buildServer)
    }
}

private fun runStdio(realStdOut: java.io.PrintStream, serverFactory: () -> Server) {
    val server = serverFactory()
    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = realStdOut.asSink().buffered(),
    )

    runBlocking {
        val session = server.createSession(transport)
        val done = Job()
        session.onClose { done.complete() }
        System.err.println("[discord-mcp] MCP server ready on stdio.")
        done.join()
    }
}

private fun runHttp(serverFactory: () -> Server) {
    val host = Config.httpHost
    val port = Config.httpPort

    embeddedServer(CIO, host = host, port = port) {
        // Permissive CORS so browser-based clients (e.g. MCP Inspector) can connect during
        // development. Restrict allowedHosts to specific origins before exposing this publicly.
        install(CORS) {
            anyHost()
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Delete)
            allowNonSimpleContentTypes = true
            allowHeader("Mcp-Session-Id")
            allowHeader("Mcp-Protocol-Version")
            exposeHeader("Mcp-Session-Id")
            exposeHeader("Mcp-Protocol-Version")
        }

        // Streamable HTTP — recommended transport for new clients. This also installs the Ktor
        // SSE plugin internally, so we must NOT install(SSE) again below or Ktor throws
        // DuplicatePluginException at startup.
        mcpStreamableHttp(path = "/mcp") { serverFactory() }

        // Legacy SSE transport — kept for older MCP clients that don't yet speak Streamable HTTP.
        routing {
            route("/sse") {
                mcp { serverFactory() }
            }
        }

        System.err.println(
            "[discord-mcp] MCP server ready on http://$host:$port " +
                "(Streamable HTTP: /mcp, SSE: /sse).",
        )
    }.start(wait = true)
}
