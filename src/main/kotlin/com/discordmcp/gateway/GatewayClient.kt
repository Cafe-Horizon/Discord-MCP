package com.discordmcp.gateway

import com.discordmcp.config.Config
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import kotlin.random.Random

/**
 * Manages a single Discord Gateway (websocket) session: HELLO/heartbeat, IDENTIFY/RESUME,
 * dispatch event buffering, and automatic reconnect. Voice (UDP/RTP audio) is out of scope —
 * only the main Gateway (guild/channel/message/presence/... events, and raw op sends such as
 * Voice State Update) is implemented.
 */
class GatewayClient {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val httpClient = HttpClient(CIO) { install(WebSockets) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile var state: GatewayState = GatewayState.DISCONNECTED
        private set
    @Volatile private var session: DefaultClientWebSocketSession? = null
    @Volatile private var heartbeatJob: Job? = null
    @Volatile private var receiveJob: Job? = null
    @Volatile private var sequence: Int? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var resumeGatewayUrl: String? = null
    @Volatile private var manualDisconnect: Boolean = false
    @Volatile var lastError: String? = null
        private set
    @Volatile private var readyPayload: JsonObject? = null

    private val bufferLock = Any()
    private val eventBuffer = ArrayDeque<BufferedEvent>()
    private val maxBufferSize = 2000

    private var intentsValue: Long = 0
    private var presenceValue: JsonObject? = null

    fun bufferedEventCount(): Int = synchronized(bufferLock) { eventBuffer.size }

    fun events(limit: Int, typeFilter: String?, sinceSeq: Int?): List<BufferedEvent> = synchronized(bufferLock) {
        eventBuffer.asSequence()
            .filter { typeFilter == null || it.type.equals(typeFilter, ignoreCase = true) }
            .filter { sinceSeq == null || (it.seq ?: 0) > sinceSeq }
            .toList()
            .takeLast(limit)
    }

    fun statusJson(): JsonObject = buildJsonObject {
        put("state", state.name)
        put("sessionId", sessionId)
        put("sequence", sequence)
        put("bufferedEventCount", bufferedEventCount())
        put("lastError", lastError)
        put("intents", intentsValue)
    }

    suspend fun connect(intents: Long, presence: JsonObject?) {
        if (state != GatewayState.DISCONNECTED) return
        if (Config.botToken == null) {
            lastError = "DISCORD_BOT_TOKEN is not configured; cannot open a Gateway session."
            return
        }
        manualDisconnect = false
        intentsValue = intents
        presenceValue = presence
        doConnect(resume = false)
    }

    suspend fun disconnect() {
        manualDisconnect = true
        heartbeatJob?.cancel()
        receiveJob?.cancel()
        runCatching { session?.close() }
        session = null
        state = GatewayState.DISCONNECTED
    }

    /** Send a raw Gateway payload, e.g. op 3 (Presence Update) or op 4 (Voice State Update). */
    suspend fun sendRaw(op: Int, data: JsonObject): Boolean {
        val s = session ?: return false
        val payload = buildJsonObject {
            put("op", op)
            put("d", data)
        }
        runCatching { s.send(Frame.Text(json.encodeToString(JsonObject.serializer(), payload))) }
            .onFailure { lastError = it.message; return false }
        return true
    }

    private suspend fun doConnect(resume: Boolean) {
        state = GatewayState.CONNECTING
        val url = (if (resume) resumeGatewayUrl else null) ?: Config.gatewayUrl
        val fullUrl = if (url.contains("?")) url else "$url?v=10&encoding=json"
        val ws = try {
            httpClient.webSocketSession(urlString = fullUrl)
        } catch (e: Exception) {
            lastError = "Failed to connect: ${e.message}"
            state = GatewayState.DISCONNECTED
            return
        }
        session = ws
        receiveJob = scope.launch { receiveLoop(ws, resume) }
    }

    private suspend fun receiveLoop(ws: DefaultClientWebSocketSession, resumeAttempt: Boolean) {
        var wantResume = resumeAttempt
        try {
            for (frame in ws.incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                val payload = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
                val op = payload["op"]?.jsonPrimitive?.intOrNull ?: continue
                val seq = payload["s"]?.jsonPrimitive?.intOrNull
                val type = payload["t"]?.jsonPrimitive?.contentOrNull
                val d = payload["d"]

                if (seq != null) sequence = seq

                when (op) {
                    10 -> { // Hello
                        val interval = d?.jsonObject?.get("heartbeat_interval")?.jsonPrimitive?.intOrNull ?: 41_250
                        heartbeatJob?.cancel()
                        heartbeatJob = scope.launch { heartbeatLoop(ws, interval.toLong()) }
                        if (wantResume && sessionId != null) {
                            state = GatewayState.IDENTIFYING
                            sendResume(ws)
                        } else {
                            state = GatewayState.IDENTIFYING
                            sendIdentify(ws)
                        }
                    }

                    0 -> { // Dispatch
                        if (type == "READY" && d != null) {
                            readyPayload = d.jsonObject
                            sessionId = d.jsonObject["session_id"]?.jsonPrimitive?.contentOrNull
                            resumeGatewayUrl = d.jsonObject["resume_gateway_url"]?.jsonPrimitive?.contentOrNull
                            state = GatewayState.CONNECTED
                        }
                        if (type != null && d != null) {
                            addEvent(BufferedEvent(seq, type, Instant.now().toEpochMilli(), d))
                        }
                    }

                    1 -> { // Server-requested heartbeat
                        sendHeartbeat(ws)
                    }

                    7 -> { // Reconnect
                        wantResume = true
                        lastError = "Server requested reconnect."
                        break
                    }

                    9 -> { // Invalid session
                        val resumable = d?.jsonPrimitive?.booleanOrNull ?: false
                        if (!resumable) {
                            sessionId = null
                            sequence = null
                        }
                        wantResume = resumable
                        delay(Random.nextLong(1_000, 5_000))
                        lastError = "Invalid session (resumable=$resumable)."
                        break
                    }

                    11 -> { /* Heartbeat ACK - nothing to do beyond noting liveness */ }
                }
            }
        } catch (e: Exception) {
            lastError = "Gateway receive loop error: ${e.message}"
        } finally {
            heartbeatJob?.cancel()
            if (manualDisconnect) {
                state = GatewayState.DISCONNECTED
            } else {
                state = GatewayState.RECONNECTING
                delay(1_000)
                doConnect(resume = wantResume)
            }
        }
    }

    private suspend fun heartbeatLoop(ws: DefaultClientWebSocketSession, intervalMs: Long) {
        delay((intervalMs * Random.nextDouble()).toLong())
        while (ws.isActive) {
            sendHeartbeat(ws)
            delay(intervalMs)
        }
    }

    private suspend fun sendHeartbeat(ws: DefaultClientWebSocketSession) {
        val payload = buildJsonObject {
            put("op", 1)
            put("d", sequence)
        }
        runCatching { ws.send(Frame.Text(json.encodeToString(JsonObject.serializer(), payload))) }
    }

    private suspend fun sendIdentify(ws: DefaultClientWebSocketSession) {
        val payload = buildJsonObject {
            put("op", 2)
            putJsonObject("d") {
                put("token", Config.botToken)
                put("intents", intentsValue)
                putJsonObject("properties") {
                    put("os", System.getProperty("os.name") ?: "linux")
                    put("browser", "discord-mcp")
                    put("device", "discord-mcp")
                }
                presenceValue?.let { put("presence", it) }
            }
        }
        runCatching { ws.send(Frame.Text(json.encodeToString(JsonObject.serializer(), payload))) }
    }

    private suspend fun sendResume(ws: DefaultClientWebSocketSession) {
        val sid = sessionId
        val seq = sequence
        if (sid == null || seq == null) {
            sendIdentify(ws)
            return
        }
        val payload = buildJsonObject {
            put("op", 6)
            putJsonObject("d") {
                put("token", Config.botToken)
                put("session_id", sid)
                put("seq", seq)
            }
        }
        runCatching { ws.send(Frame.Text(json.encodeToString(JsonObject.serializer(), payload))) }
    }

    private fun addEvent(event: BufferedEvent) = synchronized(bufferLock) {
        eventBuffer.addLast(event)
        while (eventBuffer.size > maxBufferSize) eventBuffer.removeFirst()
    }

    fun shutdown() {
        runCatching { httpClient.close() }
    }
}
