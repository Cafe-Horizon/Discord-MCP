package com.discordmcp

import com.discordmcp.config.AppConfig
import com.discordmcp.config.Config
import com.discordmcp.config.TransportMode
import com.discordmcp.discord.DiscordHttpClient
import com.discordmcp.discord.EndpointFilter
import com.discordmcp.discord.EndpointRegistry
import com.discordmcp.discord.LazyToolRegistrar
import com.discordmcp.discord.RestToolRegistrar
import com.discordmcp.gateway.GatewayClient
import com.discordmcp.gateway.GatewayTools
import io.ktor.http.HttpMethod
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
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
 *  - "http": runs an embedded Ktor server exposing Stateless Streamable HTTP at /mcp (MCP spec 2026-07-28).
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

    val appConfig = Config.current

    if (appConfig.botToken == null) {
        System.err.println(
            "[discord-mcp] WARNING: DISCORD_BOT_TOKEN is not set. REST tools will fail unless " +
                "an 'authOverride' argument is supplied per call, and the Gateway cannot connect.",
        )
    }

    val restClient = DiscordHttpClient(appConfig)
    val gatewayClient = GatewayClient(appConfig)

    val allEndpoints = EndpointRegistry.endpoints
    val filteredEndpoints = EndpointFilter.apply(allEndpoints, appConfig)
    System.err.println(
        "[discord-mcp] Loaded ${allEndpoints.size} Discord REST API operations; " +
            "${filteredEndpoints.size} enabled after tool-surface filters" +
            (if (appConfig.lazyTools) " (lazy mode: exposed via discord_search_tools/discord_call_tool)" else "") + ".",
    )

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
                version = "1.0.15",
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        )
        if (appConfig.lazyTools) {
            LazyToolRegistrar.registerAll(server, restClient, appConfig, filteredEndpoints)
        } else {
            RestToolRegistrar.registerAll(server, restClient, appConfig)
        }
        if (appConfig.enableGateway) {
            GatewayTools.registerAll(server, gatewayClient)
        }
        return server
    }

    when (appConfig.transport) {
        TransportMode.STDIO -> runStdio(realStdOut, ::buildServer)
        TransportMode.HTTP -> runHttp(appConfig, ::buildServer)
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

private fun runHttp(config: AppConfig, serverFactory: () -> Server) {
    val host = config.httpHost
    val port = config.httpPort

    embeddedServer(CIO, host = host, port = port) {
        // Permissive CORS so browser-based clients (e.g. MCP Inspector) can connect during
        // development. Restrict allowedHosts to specific origins before exposing this publicly.
        // Note: Mcp-Session-Id is not part of the 2026-07-28 stateless spec and is omitted.
        install(CORS) {
            anyHost()
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Delete)
            allowNonSimpleContentTypes = true
            allowHeader("Mcp-Protocol-Version")
            exposeHeader("Mcp-Protocol-Version")
        }

        // The SDK's DNS-rebinding protection is on by default and only trusts the `Host` header
        // values localhost/127.0.0.1/[::1]. Behind a reverse proxy or a Docker Compose service
        // name (e.g. "discord-mcp:8085"), that hostname must be added via MCP_ALLOWED_HOSTS —
        // otherwise every request is rejected with 403, and clients would need to spoof a
        // "Host: localhost" header just to get through. See Config.kt for details.
        val allowedHosts = config.httpAllowedHosts
        val allowedOrigins = config.httpAllowedOrigins

        // Stateless Streamable HTTP — MCP spec 2026-07-28. Every request is fully self-contained;
        // no session state or Mcp-Session-Id header is used. This allows standard round-robin
        // load balancers without sticky sessions.
        mcpStatelessStreamableHttp(path = "/mcp", allowedHosts = allowedHosts, allowedOrigins = allowedOrigins) {
            serverFactory()
        }

        System.err.println(
            "[discord-mcp] MCP server ready on http://$host:$port/mcp (Stateless Streamable HTTP, MCP spec 2026-07-28).",
        )
        if (allowedHosts != null) {
            System.err.println("[discord-mcp] DNS-rebinding protection: allowed Host header values = $allowedHosts")
        }
    }.start(wait = true)
}

