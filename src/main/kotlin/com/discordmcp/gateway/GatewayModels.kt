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

/** Represents the sealed state hierarchy of the Gateway connection. */
sealed interface GatewayState {
    val name: String

    data object Disconnected : GatewayState {
        override val name: String = "DISCONNECTED"
    }

    data object Connecting : GatewayState {
        override val name: String = "CONNECTING"
    }

    data object Identifying : GatewayState {
        override val name: String = "IDENTIFYING"
    }

    data class Connected(
        val sessionId: String,
        val resumeGatewayUrl: String?,
    ) : GatewayState {
        override val name: String = "CONNECTED"
    }

    data class Reconnecting(
        val reason: String? = null,
    ) : GatewayState {
        override val name: String = "RECONNECTING"
    }
}
