package com.example.privprint.service.realtime

import com.example.privprint.data.api.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

enum class RealtimeEventType {
    SESSION_CREATED,
    QR_SCANNED,
    PAIRED,
    AUTHORIZED,
    UPLOAD_STARTED,
    UPLOAD_COMPLETED,
    JOB_CREATED,
    JOB_QUEUED,
    PRINT_STARTED,
    PRINT_PROGRESS,
    COPY_STARTED,
    COPY_COMPLETED,
    PRINT_COMPLETED,
    PRINT_FAILED,
    JOB_CANCELLED,
    SESSION_REVOKED,
    SESSION_EXPIRED,
    CLEANUP_COMPLETED,
    HEARTBEAT_PING,
    HEARTBEAT_PONG
}

data class RealtimeEvent(
    val eventId: String,
    val sequenceNumber: Long,
    val eventType: RealtimeEventType,
    val targetChannel: String,
    val jobId: String? = null,
    val sessionId: String? = null,
    val shopId: String? = null,
    val payloadJson: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val channel: String) : ConnectionState()
    data class Reconnecting(val attempt: Int, val nextRetryMs: Long) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * Enterprise Realtime Transport Client with OkHttp WebSocket integration,
 * exponential reconnect backoff, heartbeat management, and channel resubscription.
 */
class RealtimeTransportClient(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val _events = MutableSharedFlow<RealtimeEvent>(replay = 5, extraBufferCapacity = 50)
    val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()

    private val _connectionState = MutableSharedFlow<ConnectionState>(replay = 1)
    val connectionState: SharedFlow<ConnectionState> = _connectionState.asSharedFlow()

    private val seenEventIds = ConcurrentHashMap.newKeySet<String>()
    private var currentChannel: String? = null
    private var authToken: String? = null
    @Volatile private var isConnected = false
    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0

    fun connect(channel: String, token: String) {
        if (isConnected && currentChannel == channel) return
        this.currentChannel = channel
        this.authToken = token
        this.isConnected = true
        this.reconnectAttempt = 0

        scope.launch {
            _connectionState.emit(ConnectionState.Connecting)
            startWebSocketConnection(channel, token)
        }
    }

    private fun startWebSocketConnection(channel: String, token: String) {
        val wsUrl = ApiClient.DEFAULT_BASE_URL.replace("http://", "ws://").replace("https://", "wss://") + "api/v1/realtime/ws?token=$token"

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = ApiClient.okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                reconnectAttempt = 0
                scope.launch {
                    _connectionState.emit(ConnectionState.Connected(channel))
                    // Send subscription request
                    val subMsg = JSONObject().apply {
                        put("type", "subscribe")
                        put("channel", channel)
                    }.toString()
                    ws.send(subMsg)
                    startHeartbeat()
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val msgType = json.optString("type")

                    if (msgType == "event") {
                        val evtId = json.optString("event_id", "evt_${System.currentTimeMillis()}")
                        val evtName = json.optString("event", "JOB_CREATED")
                        val evtChannel = json.optString("channel", channel)
                        val dataObj = json.optJSONObject("data") ?: JSONObject()

                        val mappedType = try {
                            RealtimeEventType.valueOf(evtName)
                        } catch (e: Exception) {
                            RealtimeEventType.JOB_CREATED
                        }

                        val evt = RealtimeEvent(
                            eventId = evtId,
                            sequenceNumber = System.currentTimeMillis(),
                            eventType = mappedType,
                            targetChannel = evtChannel,
                            jobId = dataObj.optString("job_id", null),
                            sessionId = dataObj.optString("session_id", null),
                            shopId = dataObj.optString("shop_id", null),
                            payloadJson = dataObj.toString()
                        )

                        scope.launch { publishEvent(evt) }
                    } else if (msgType == "error") {
                        val code = json.optString("code")
                        if (code == "TOKEN_EXPIRED") {
                            disconnect()
                        }
                    }
                } catch (e: Exception) {
                    // Ignore malformed frames
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                handleDisconnectOrFailure(t.message ?: "WebSocket failure")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                handleDisconnectOrFailure("Closed: $reason")
            }
        })
    }

    private fun handleDisconnectOrFailure(reason: String) {
        heartbeatJob?.cancel()
        if (!isConnected || authToken.isNullOrEmpty() || currentChannel.isNullOrEmpty()) {
            scope.launch { _connectionState.emit(ConnectionState.Disconnected) }
            return
        }

        reconnectAttempt++
        val delayMs = min(1000L * (1 shl min(reconnectAttempt, 5)), 30_000L)

        scope.launch {
            _connectionState.emit(ConnectionState.Reconnecting(reconnectAttempt, delayMs))
        }

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (isConnected && !authToken.isNullOrEmpty() && !currentChannel.isNullOrEmpty()) {
                startWebSocketConnection(currentChannel!!, authToken!!)
            }
        }
    }

    fun disconnect() {
        isConnected = false
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        webSocket?.close(1000, "Client disconnected")
        webSocket = null
        currentChannel = null
        authToken = null
        scope.launch {
            _connectionState.emit(ConnectionState.Disconnected)
        }
    }

    suspend fun publishEvent(event: RealtimeEvent) {
        if (seenEventIds.add(event.eventId)) {
            _events.emit(event)
        }
    }

    suspend fun emitPrintProgress(
        jobId: String,
        shopId: String,
        currentPage: Int,
        totalPages: Int,
        currentCopy: Int,
        totalCopies: Int,
        progressPercent: Float
    ) {
        val event = RealtimeEvent(
            eventId = "evt-prg-${jobId}-${currentCopy}-${currentPage}",
            sequenceNumber = System.currentTimeMillis(),
            eventType = RealtimeEventType.PRINT_PROGRESS,
            targetChannel = "job:$jobId",
            jobId = jobId,
            shopId = shopId,
            payloadJson = """{"currentPage":$currentPage,"totalPages":$totalPages,"currentCopy":$currentCopy,"totalCopies":$totalCopies,"percent":$progressPercent}"""
        )
        publishEvent(event)
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && isConnected) {
                delay(20_000)
                webSocket?.send(JSONObject().apply { put("type", "ping") }.toString())
            }
        }
    }

    fun isSubscribedTo(channel: String): Boolean {
        return isConnected && (currentChannel == channel || currentChannel == "all")
    }
}
