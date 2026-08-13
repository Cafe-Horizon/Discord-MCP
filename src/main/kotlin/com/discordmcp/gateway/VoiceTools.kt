package com.discordmcp.gateway

import com.discordmcp.config.AppConfig
import com.discordmcp.discord.DiscordHttpClient
import com.discordmcp.discord.EndpointExecutor
import com.discordmcp.discord.EndpointRegistry
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object VoiceTools {

    fun registerAll(
        server: Server,
        gatewayClient: GatewayClient,
        httpClient: DiscordHttpClient,
        config: AppConfig,
    ) {
        // 1. Join a Voice Channel via Gateway Opcode 4 (Voice State Update)
        server.addTool(
            name = "discord_voice_join",
            description = "Connect the bot to a Discord Voice Channel using Gateway Voice State Update.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("guild_id") {
                        put("type", "string")
                        put("description", "ID of the target guild.")
                    }
                    putJsonObject("channel_id") {
                        put("type", "string")
                        put("description", "ID of the target voice channel.")
                    }
                    putJsonObject("self_mute") {
                        put("type", "boolean")
                        put("description", "If true, joins muted.")
                    }
                    putJsonObject("self_deaf") {
                        put("type", "boolean")
                        put("description", "If true, joins deafened.")
                    }
                },
                required = listOf("guild_id", "channel_id"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false),
        ) { request ->
            val args = request.arguments ?: emptyMap()
            val guildId = args["guild_id"]?.jsonPrimitive?.contentOrNull
                ?: return@addTool CallToolResult(listOf(TextContent("Missing 'guild_id'")), isError = true)
            val channelId = args["channel_id"]?.jsonPrimitive?.contentOrNull
                ?: return@addTool CallToolResult(listOf(TextContent("Missing 'channel_id'")), isError = true)
            val selfMute = (args["self_mute"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val selfDeaf = (args["self_deaf"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false

            val payloadData = buildJsonObject {
                put("guild_id", guildId)
                put("channel_id", channelId)
                put("self_mute", selfMute)
                put("self_deaf", selfDeaf)
            }

            val sent = gatewayClient.sendRaw(op = 4, data = payloadData)
            if (sent) {
                CallToolResult(listOf(TextContent("Voice State Update (op 4) sent to join channel '$channelId' in guild '$guildId'.")), isError = false)
            } else {
                CallToolResult(listOf(TextContent("Failed to send Voice State Update. Gateway may not be connected.")), isError = true)
            }
        }

        // 2. Leave a Voice Channel
        server.addTool(
            name = "discord_voice_leave",
            description = "Disconnect the bot from a Voice Channel in a guild.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("guild_id") {
                        put("type", "string")
                        put("description", "ID of the guild to disconnect voice from.")
                    }
                },
                required = listOf("guild_id"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false),
        ) { request ->
            val args = request.arguments ?: emptyMap()
            val guildId = args["guild_id"]?.jsonPrimitive?.contentOrNull
                ?: return@addTool CallToolResult(listOf(TextContent("Missing 'guild_id'")), isError = true)

            val payloadData = buildJsonObject {
                put("guild_id", guildId)
                put("channel_id", null)
                put("self_mute", false)
                put("self_deaf", false)
            }

            val sent = gatewayClient.sendRaw(op = 4, data = payloadData)
            if (sent) {
                CallToolResult(listOf(TextContent("Voice State Update sent to leave voice channel in guild '$guildId'.")), isError = false)
            } else {
                CallToolResult(listOf(TextContent("Failed to send Voice State Update.")), isError = true)
            }
        }

        // 3. Send a TTS (Text-to-Speech) Message
        server.addTool(
            name = "discord_voice_send_tts_message",
            description = "Send a Text-To-Speech (TTS) audio announcement message in a text/voice channel.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("channel_id") {
                        put("type", "string")
                        put("description", "ID of the target channel.")
                    }
                    putJsonObject("content") {
                        put("type", "string")
                        put("description", "Text message to be spoken via TTS.")
                    }
                },
                required = listOf("channel_id", "content"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false),
        ) { request ->
            val args = request.arguments ?: emptyMap()
            val channelId = args["channel_id"]?.jsonPrimitive?.contentOrNull
                ?: return@addTool CallToolResult(listOf(TextContent("Missing 'channel_id'")), isError = true)
            val content = args["content"]?.jsonPrimitive?.contentOrNull
                ?: return@addTool CallToolResult(listOf(TextContent("Missing 'content'")), isError = true)

            val spec = EndpointRegistry.endpoints.find { it.operationId == "create_message" }
                ?: return@addTool CallToolResult(listOf(TextContent("create_message endpoint spec not found")), isError = true)

            val body = buildJsonObject {
                put("content", content)
                put("tts", true)
            }

            val pathArgs = buildJsonObject {
                put("channel_id", channelId)
            }

            EndpointExecutor.call(
                spec = spec,
                client = httpClient,
                config = config,
                pathArgs = pathArgs,
                queryArgs = buildJsonObject {},
                bodyObject = body,
                files = null,
                auditLogReason = null,
                authOverride = null,
            )
        }
    }
}
