package com.discordmcp

import com.discordmcp.config.Config
import com.discordmcp.discord.DiscordHttpClient
import com.discordmcp.discord.EndpointRegistry
import com.discordmcp.discord.RestToolRegistrar
import com.discordmcp.gateway.GatewayClient
import com.discordmcp.gateway.GatewayTools
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

/**
 * Discord MCP Server — exposes (nearly) the entire Discord HTTP API as MCP tools
 * (one tool per operation in Discord's official OpenAPI spec, https://github.com/discord/discord-api-spec),
 * plus a managed Gateway (websocket) session for real-time events.
 *
 * Configuration is via environment variables; see Config.kt / README.md.
 */
fun main() {
    // Some transitive libraries (e.g. kotlin-logging) print a one-line diagnostic banner
    // directly to stdout on first use. Because the STDIO transport reserves stdout
    // exclusively for the MCP JSON-RPC stream, we grab the real stdout handle first and
    // then redirect System.out to stderr, so any stray println from a dependency can never
    // corrupt the protocol stream.
    val realStdOut = System.out
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true))

    if (Config.botToken == null) {
        System.err.println(
            "[discord-mcp-server] WARNING: DISCORD_BOT_TOKEN is not set. REST tools will fail unless " +
                "an 'authOverride' argument is supplied per call, and the Gateway cannot connect.",
        )
    }

    val restClient = DiscordHttpClient()
    val gatewayClient = GatewayClient()

    val endpointCount = EndpointRegistry.endpoints.size
    System.err.println("[discord-mcp-server] Loaded $endpointCount Discord REST API operations.")

    val server = Server(
        serverInfo = Implementation(
            name = "discord-mcp-server",
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

    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = realStdOut.asSink().buffered(),
    )

    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching { restClient.close() }
            runCatching { gatewayClient.shutdown() }
        },
    )

    runBlocking {
        val session = server.createSession(transport)
        val done = Job()
        session.onClose { done.complete() }
        System.err.println("[discord-mcp-server] MCP server ready on stdio.")
        done.join()
    }
}
