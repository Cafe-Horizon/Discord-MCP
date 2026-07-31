package com.discordmcp.gateway

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Named Gateway intent bit flags, see https://docs.discord.com/developers/docs/events/gateway#gateway-intents */
object IntentBits {
    val byName: Map<String, Long> = mapOf(
        "GUILDS" to (1L shl 0),
        "GUILD_MEMBERS" to (1L shl 1),
        "GUILD_MODERATION" to (1L shl 2),
        "GUILD_EXPRESSIONS" to (1L shl 3),
        "GUILD_INTEGRATIONS" to (1L shl 4),
        "GUILD_WEBHOOKS" to (1L shl 5),
        "GUILD_INVITES" to (1L shl 6),
        "GUILD_VOICE_STATES" to (1L shl 7),
        "GUILD_PRESENCES" to (1L shl 8),
        "GUILD_MESSAGES" to (1L shl 9),
        "GUILD_MESSAGE_REACTIONS" to (1L shl 10),
        "GUILD_MESSAGE_TYPING" to (1L shl 11),
        "DIRECT_MESSAGES" to (1L shl 12),
        "DIRECT_MESSAGE_REACTIONS" to (1L shl 13),
        "DIRECT_MESSAGE_TYPING" to (1L shl 14),
        "MESSAGE_CONTENT" to (1L shl 15),
        "GUILD_SCHEDULED_EVENTS" to (1L shl 16),
        "AUTO_MODERATION_CONFIGURATION" to (1L shl 20),
        "AUTO_MODERATION_EXECUTION" to (1L shl 21),
        "GUILD_MESSAGE_POLLS" to (1L shl 24),
        "DIRECT_MESSAGE_POLLS" to (1L shl 25),
    )
    val ALL: Long = byName.values.fold(0L) { acc, v -> acc or v }
}

object GatewayTools {
    fun registerAll(server: Server, gateway: GatewayClient) {
        server.addTool(
            name = "discord_gateway_connect",
            description = "Open a Discord Gateway (websocket) session and start receiving real-time events. " +
                "Provide 'intents' as an integer bitmask and/or 'intentNames' (e.g. [\"GUILDS\", \"GUILD_MESSAGES\", " +
                "\"MESSAGE_CONTENT\"]); they are OR-ed together. Received dispatch events are buffered (last 2000) " +
                "and read back with discord_gateway_events. Note: only the main Gateway is implemented — voice " +
                "audio (UDP/RTP) is out of scope; you can still send raw Voice State Update (op 4) via " +
                "discord_gateway_send.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("intents") {
                        put("type", "integer")
                        put("description", "Raw intents bitmask. Combined (OR-ed) with intentNames if both given. Defaults to 0.")
                    }
                    putJsonObject("intentNames") {
                        put("type", "array")
                        put("description", "Intent names, e.g. GUILDS, GUILD_MESSAGES, MESSAGE_CONTENT, GUILD_MEMBERS (privileged).")
                        putJsonObject("items") { put("type", "string") }
                    }
                    putJsonObject("presence") {
                        put("type", "object")
                        put("description", "Optional initial presence object sent with IDENTIFY (status, activities, ...).")
                    }
                },
            ),
        ) { request ->
            val args = request.arguments ?: emptyMap()
            val rawIntents = args["intents"]?.jsonPrimitive?.longOrNull ?: 0L
            val namedIntents = (args["intentNames"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.uppercase() }
                ?.mapNotNull { IntentBits.byName[it] }
                ?.fold(0L) { acc, v -> acc or v } ?: 0L
            val presence = args["presence"] as? JsonObject

            gateway.connect(rawIntents or namedIntents, presence)
            CallToolResult(content = listOf(TextContent("Connecting. Current status:\n${gateway.statusJson()}")))
        }

        server.addTool(
            name = "discord_gateway_status",
            description = "Get the current Discord Gateway connection state, session id, sequence number, " +
                "buffered event count, and last error (if any).",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
        ) {
            CallToolResult(content = listOf(TextContent(gateway.statusJson().toString())))
        }

        server.addTool(
            name = "discord_gateway_events",
            description = "Read buffered Gateway dispatch events (most recent first is NOT guaranteed order; " +
                "returned in chronological order, oldest of the selected window first). Does not remove them " +
                "from the buffer (the buffer auto-evicts the oldest events beyond 2000 entries). Use 'sinceSeq' " +
                "(the Gateway 's' sequence number) to fetch only events newer than one you already saw.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "Max number of events to return, most-recent window. Defaults to 50, max 2000.")
                    }
                    putJsonObject("eventType") {
                        put("type", "string")
                        put("description", "Optional filter, e.g. MESSAGE_CREATE, GUILD_MEMBER_ADD, PRESENCE_UPDATE.")
                    }
                    putJsonObject("sinceSeq") {
                        put("type", "integer")
                        put("description", "Optional. Only return events with Gateway sequence number greater than this.")
                    }
                },
            ),
        ) { request ->
            val args = request.arguments ?: emptyMap()
            val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 50).coerceIn(1, 2000)
            val type = args["eventType"]?.jsonPrimitive?.contentOrNull
            val since = args["sinceSeq"]?.jsonPrimitive?.intOrNull
            val events = gateway.events(limit, type, since)
            val text = buildJsonObject {
                put("count", events.size)
                putJsonArray("events") {
                    events.forEach {
                        add(
                            buildJsonObject {
                                put("seq", it.seq)
                                put("type", it.type)
                                put("receivedAtEpochMs", it.receivedAtEpochMs)
                                put("data", it.data)
                            },
                        )
                    }
                }
            }
            CallToolResult(content = listOf(TextContent(text.toString())))
        }

        server.addTool(
            name = "discord_gateway_send",
            description = "Send a raw Gateway payload on the current session, e.g. op 3 (Presence Update), " +
                "op 4 (Voice State Update), or op 8 (Request Guild Members). Requires an active connection " +
                "(discord_gateway_connect first).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("op") {
                        put("type", "integer")
                        put("description", "Gateway opcode, e.g. 3, 4, or 8.")
                    }
                    putJsonObject("d") {
                        put("type", "object")
                        put("description", "Payload data object for the given opcode.")
                    }
                },
                required = listOf("op", "d"),
            ),
        ) { request ->
            val args = request.arguments ?: emptyMap()
            val op = args["op"]?.jsonPrimitive?.intOrNull
            val d = args["d"] as? JsonObject
            if (op == null || d == null) {
                return@addTool CallToolResult(
                    content = listOf(TextContent("Both 'op' (integer) and 'd' (object) are required.")),
                    isError = true,
                )
            }
            val ok = gateway.sendRaw(op, d)
            CallToolResult(content = listOf(TextContent(if (ok) "Sent." else "Failed to send: ${gateway.lastError}")), isError = !ok)
        }

        server.addTool(
            name = "discord_gateway_disconnect",
            description = "Close the current Discord Gateway session.",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
        ) {
            gateway.disconnect()
            CallToolResult(content = listOf(TextContent("Disconnected.")))
        }
    }
}
