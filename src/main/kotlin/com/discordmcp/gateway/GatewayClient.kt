package com.discordmcp.gateway

import com.discordmcp.config.AppConfig
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * Manages a single Discord Gateway (websocket) session: HELLO/heartbeat, IDENTIFY/RESUME,
 * dispatch event buffering, and automatic reconnect.
 */
class GatewayClient(
    private val config: AppConfig = Config.current,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val httpClient = HttpClient(CIO) { install(WebSockets) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _stateFlow = MutableStateFlow<GatewayState>(GatewayState.Disconnected)
    val stateFlow: StateFlow<GatewayState> = _stateFlow.asStateFlow()

    val state: GatewayState get() = _stateFlow.value

    private val sessionRef = AtomicReference<DefaultClientWebSocketSession?>(null)
    private var heartbeatJob: Job? = null
    private var receiveJob: Job? = null

    private val sequenceRef = AtomicInteger(-1)
    val sequence: Int? get() = sequenceRef.get().takeIf { it >= 0 }

    private val sessionIdRef = AtomicReference<String?>(null)
    val sessionId: String? get() = sessionIdRef.get()

    private val resumeGatewayUrlRef = AtomicReference<String?>(null)
    @Volatile private var manualDisconnect: Boolean = false

    private val lastErrorRef = AtomicReference<String?>(null)
    val lastError: String? get() = lastErrorRef.get()

    @Volatile private var readyPayload: JsonObject? = null

    private val eventBuffer = ConcurrentLinkedQueue<BufferedEvent>()
    private val maxBufferSize = 2000

    private val intentsRef = AtomicLong(0L)
    @Volatile private var presenceValue: JsonObject? = null

    fun bufferedEventCount(): Int = eventBuffer.size

    fun events(limit: Int, typeFilter: String?, sinceSeq: Int?): List<BufferedEvent> {
        return eventBuffer.asSequence()
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
        put("intents", intentsRef.get())
    }

    suspend fun connect(intents: Long, presence: JsonObject?) {
        if (state !is GatewayState.Disconnected) return
        if (config.botToken == null) {
            lastErrorRef.set("DISCORD_BOT_TOKEN is not configured; cannot open a Gateway session.")
            return
        }
        manualDisconnect = false
        intentsRef.set(intents)
        presenceValue = presence
        doConnect(resume = false)
    }

    suspend fun disconnect() {
        manualDisconnect = true
        heartbeatJob?.cancel()
        receiveJob?.cancel()
        runCatching { sessionRef.getAndSet(null)?.close() }
        _stateFlow.value = GatewayState.Disconnected
    }

    /** Send a raw Gateway payload, e.g. op 3 (Presence Update) or op 4 (Voice State Update). */
    suspend fun sendRaw(op: Int, data: JsonObject): Boolean {
        val s = sessionRef.get() ?: return false
        val payload = buildJsonObject {
            put("op", op)
            put("d", data)
        }
        runCatching { s.send(Frame.Text(json.encodeToString(JsonObject.serializer(), payload))) }
            .onFailure { lastErrorRef.set(it.message); return false }
        return true
    }

    private suspend fun doConnect(resume: Boolean) {
        _stateFlow.value = GatewayState.Connecting
        val url = (if (resume) resumeGatewayUrlRef.get() else null) ?: config.gatewayUrl
        val fullUrl = if (url.contains("?")) url else "$url?v=10&encoding=json"
        val ws = try {
            httpClient.webSocketSession(urlString = fullUrl)
        } catch (e: Exception) {
            lastErrorRef.set("Failed to connect: ${e.message}")
            _stateFlow.value = GatewayState.Disconnected
            return
        }
        sessionRef.set(ws)
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

                if (seq != null) sequenceRef.set(seq)

                when (op) {
                    10 -> { // Hello
                        val interval = d?.jsonObject?.get("heartbeat_interval")?.jsonPrimitive?.intOrNull ?: 41_250
                        heartbeatJob?.cancel()
                        heartbeatJob = scope.launch { heartbeatLoop(ws, interval.toLong()) }
                        _stateFlow.value = GatewayState.Identifying
                        if (wantResume && sessionIdRef.get() != null) {
                            sendResume(ws)
                        } else {
                            sendIdentify(ws)
                        }
                    }

                    0 -> { // Dispatch
                        if (type == "READY" && d != null) {
                            readyPayload = d.jsonObject
                            val sid = d.jsonObject["session_id"]?.jsonPrimitive?.contentOrNull
                            val resumeUrl = d.jsonObject["resume_gateway_url"]?.jsonPrimitive?.contentOrNull
                            sessionIdRef.set(sid)
                            resumeGatewayUrlRef.set(resumeUrl)
                            _stateFlow.value = GatewayState.Connected(sid ?: "", resumeUrl)
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
                        lastErrorRef.set("Server requested reconnect.")
                        break
                    }

                    9 -> { // Invalid session
                        val resumable = d?.jsonPrimitive?.booleanOrNull ?: false
                        if (!resumable) {
                            sessionIdRef.set(null)
                            sequenceRef.set(-1)
                        }
                        wantResume = resumable
                        delay(Random.nextLong(1_000, 5_000))
                        lastErrorRef.set("Invalid session (resumable=$resumable).")
                        break
                    }

                    11 -> { /* Heartbeat ACK */ }
                }
            }
        } catch (e: Exception) {
            lastErrorRef.set("Gateway receive loop error: ${e.message}")
        } finally {
            heartbeatJob?.cancel()
            if (manualDisconnect) {
                _stateFlow.value = GatewayState.Disconnected
            } else {
                val err = lastErrorRef.get()
                _stateFlow.value = GatewayState.Reconnecting(err)
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
        val seq = sequence
        val payload = buildJsonObject {
            put("op", 1)
            put("d", seq)
        }
        runCatching { ws.send(Frame.Text(json.encodeToString(JsonObject.serializer(), payload))) }
    }

    private suspend fun sendIdentify(ws: DefaultClientWebSocketSession) {
        val payload = buildJsonObject {
            put("op", 2)
            putJsonObject("d") {
                put("token", config.botToken)
                put("intents", intentsRef.get())
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
                put("token", config.botToken)
                put("session_id", sid)
                put("seq", seq)
            }
        }
        runCatching { ws.send(Frame.Text(json.encodeToString(JsonObject.serializer(), payload))) }
    }

    private fun addEvent(event: BufferedEvent) {
        eventBuffer.add(event)
        while (eventBuffer.size > maxBufferSize) {
            eventBuffer.poll()
        }
    }

    fun shutdown() {
        runCatching { httpClient.close() }
    }
}
