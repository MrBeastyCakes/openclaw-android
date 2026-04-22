package com.openclaw.androidclient.data.network

import com.openclaw.androidclient.data.model.GatewayEvent
import com.openclaw.androidclient.data.model.GatewayResponse
import com.openclaw.androidclient.data.model.string
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class OpenClawWebSocketClient(
    private val onEvent: (GatewayEvent) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onFailure: (String) -> Unit,
    private val onDisconnected: (String) -> Unit,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<GatewayResponse>>()
    private val requestCounter = java.util.concurrent.atomic.AtomicLong(1)
    private val connected = AtomicBoolean(false)

    @Volatile
    private var webSocket: WebSocket? = null

    fun connect(url: String) {
        disconnect()
        onStatus("Connecting…")
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, listener)
    }

    fun disconnect() {
        connected.set(false)
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        failPendingRequests(errorCode = "disconnected", errorMessage = "Disconnected")
    }

    suspend fun request(method: String, params: JsonObject): GatewayResponse {
        val socket = webSocket ?: return GatewayResponse(false, null, "socket_closed", "Socket is not connected", null)
        val id = requestCounter.getAndIncrement().toString()
        val deferred = CompletableDeferred<GatewayResponse>()
        pendingRequests[id] = deferred

        val frame = buildJsonObject {
            put("type", "req")
            put("id", id)
            put("method", method)
            put("params", params)
        }

        val sent = socket.send(json.encodeToString(JsonObject.serializer(), frame))
        if (!sent) {
            pendingRequests.remove(id)
            return GatewayResponse(false, null, "send_failed", "Failed to send $method", null)
        }

        return deferred.await()
    }

    fun close() {
        disconnect()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connected.set(true)
            onStatus("Socket opened")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch {
                try {
                    handleIncomingFrame(text)
                } catch (t: Throwable) {
                    onFailure("Failed to parse gateway frame: ${t.message ?: "unknown error"}")
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            connected.set(false)
            onStatus("Closing: $code $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected.set(false)
            this@OpenClawWebSocketClient.webSocket = null
            val message = "Disconnected: $code $reason"
            failPendingRequests(errorCode = "disconnected", errorMessage = message)
            onDisconnected(message)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connected.set(false)
            this@OpenClawWebSocketClient.webSocket = null
            val detail = response?.message ?: t.message ?: "unknown websocket error"
            failPendingRequests(errorCode = "connection_failed", errorMessage = detail)
            onFailure(detail)
        }
    }

    private fun handleIncomingFrame(text: String) {
        val frame = json.parseToJsonElement(text).jsonObject
        when (frame.string("type")) {
            "res" -> handleResponse(frame)
            "event" -> handleEvent(frame)
            else -> onStatus("Ignored frame: ${frame.string("type") ?: "unknown"}")
        }
    }

    private fun handleResponse(frame: JsonObject) {
        val id = frame.string("id") ?: return
        val response = GatewayResponse(
            ok = frame["ok"]?.jsonPrimitive?.content == "true",
            payload = frame["payload"],
            errorCode = frame["error"]?.jsonObject?.string("code"),
            errorMessage = frame["error"]?.jsonObject?.string("message"),
            errorDetails = frame["error"]?.jsonObject?.get("details"),
        )
        pendingRequests.remove(id)?.complete(response)
    }

    private fun handleEvent(frame: JsonObject) {
        val name = frame.string("event") ?: return
        val payload = frame["payload"]
        when (name) {
            "connect.challenge" -> {
                val nonce = payload?.jsonObject?.string("nonce").orEmpty()
                onEvent(GatewayEvent.Challenge(nonce))
            }

            "chat" -> onEvent(GatewayEvent.Chat(payload?.jsonObject ?: JsonObject(emptyMap())))
            "session.tool" -> onEvent(GatewayEvent.SessionTool(payload?.jsonObject ?: JsonObject(emptyMap())))
            else -> onEvent(GatewayEvent.Unknown(name, payload))
        }
    }

    private fun failPendingRequests(errorCode: String, errorMessage: String) {
        val failures = pendingRequests.values.toList()
        pendingRequests.clear()
        failures.forEach { deferred ->
            deferred.complete(
                GatewayResponse(
                    ok = false,
                    payload = null,
                    errorCode = errorCode,
                    errorMessage = errorMessage,
                    errorDetails = null,
                )
            )
        }
    }
}
