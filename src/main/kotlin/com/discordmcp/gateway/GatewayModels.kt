package com.discordmcp.gateway

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Raw Discord Gateway payload envelope (https://discord.com/developers/docs/events/gateway). */
@Serializable
data class GatewayPayload(
    val op: Int,
    val d: JsonElement? = null,
    val s: Int? = null,
    val t: String? = null,
)

/** A single buffered dispatch event, exposed to MCP clients via discord_gateway_events. */
@Serializable
data class BufferedEvent(
    val seq: Int?,
    val type: String,
    val receivedAtEpochMs: Long,
    val data: JsonElement,
)

enum class GatewayState { DISCONNECTED, CONNECTING, IDENTIFYING, CONNECTED, RECONNECTING }
