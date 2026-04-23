package com.openclaw.androidclient.data.repo

import android.content.Context
import android.content.SharedPreferences
import com.openclaw.androidclient.data.auth.DeviceAuthStore
import com.openclaw.androidclient.data.model.ChatMessage
import com.openclaw.androidclient.data.model.TimelineItem
import com.openclaw.androidclient.data.model.ToolItem
import com.openclaw.androidclient.data.model.extractToolItem
import com.openclaw.androidclient.data.model.ConnectAuthBundle
import com.openclaw.androidclient.data.model.ConnectionConfig
import com.openclaw.androidclient.data.model.ConnectionStatus
import com.openclaw.androidclient.data.model.DEFAULT_REQUESTED_SCOPES
import com.openclaw.androidclient.data.model.DeviceIdentity
import com.openclaw.androidclient.data.model.GatewayEvent
import com.openclaw.androidclient.data.model.ROLE_OPERATOR
import com.openclaw.androidclient.data.model.SendMessageResult
import com.openclaw.androidclient.data.model.buildAbortParams
import com.openclaw.androidclient.data.model.buildConnectParams
import com.openclaw.androidclient.data.model.buildHistoryParams
import com.openclaw.androidclient.data.model.buildSendParams
import com.openclaw.androidclient.data.model.extractHelloAuth
import com.openclaw.androidclient.data.model.extractMessageText
import com.openclaw.androidclient.data.model.extractMessagesFromHistory
import com.openclaw.androidclient.data.model.extractPairingRequestId
import com.openclaw.androidclient.data.model.generateIdempotencyKey
import com.openclaw.androidclient.data.model.string
import com.openclaw.androidclient.data.network.OpenClawWebSocketClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

class ChatRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val deviceAuthStore = DeviceAuthStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _timeline = MutableStateFlow<List<TimelineItem>>(emptyList())
    private val _status = MutableStateFlow("Ready")
    private val _isConnected = MutableStateFlow(false)
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.Disconnected)
    private val _currentRunId = MutableStateFlow<String?>(null)
    private val streamingIdsByRun = ConcurrentHashMap<String, String>()
    private val deviceIdentity: DeviceIdentity = deviceAuthStore.loadOrCreateIdentity()

    private var activeConfig: ConnectionConfig? = null
    private var currentNonce: String = ""
    private val socketClient = OpenClawWebSocketClient(
        onEvent = ::handleEvent,
        onStatus = { _status.value = it },
        onFailure = {
            _isConnected.value = false
            _connectionStatus.value = ConnectionStatus.Error
            _status.value = it
        },
        onDisconnected = {
            _isConnected.value = false
            _connectionStatus.value = ConnectionStatus.Disconnected
            _status.value = it
        },
    )

    val timeline: StateFlow<List<TimelineItem>> = _timeline.asStateFlow()
    val status: StateFlow<String> = _status.asStateFlow()
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()
    val currentRunId: StateFlow<String?> = _currentRunId.asStateFlow()

    fun loadSavedConfig(): ConnectionConfig = ConnectionConfig(
        gatewayUrl = prefs.getString(KEY_GATEWAY_URL, com.openclaw.androidclient.data.model.DEFAULT_GATEWAY_URL).orEmpty(),
        gatewayToken = prefs.getString(KEY_TOKEN, "").orEmpty(),
        sessionKey = prefs.getString(KEY_SESSION_KEY, com.openclaw.androidclient.data.model.DEFAULT_SESSION_KEY).orEmpty(),
    )

    fun connect(config: ConnectionConfig) {
        activeConfig = config
        persistConfig(config)
        _timeline.value = emptyList()
        streamingIdsByRun.clear()
        _status.value = "Opening gateway…"
        _isConnected.value = false
        _connectionStatus.value = ConnectionStatus.Connecting
        socketClient.connect(config.gatewayUrl)
    }

    fun disconnect() {
        socketClient.disconnect()
        streamingIdsByRun.clear()
        _isConnected.value = false
        _connectionStatus.value = ConnectionStatus.Disconnected
        _status.value = "Disconnected"
    }

    suspend fun sendMessage(message: String): SendMessageResult {
        val config = activeConfig ?: return SendMessageResult.Failure("Not connected")
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return SendMessageResult.Failure("Message is blank")

        val response = socketClient.request(
            method = "chat.send",
            params = buildSendParams(
                sessionKey = config.sessionKey,
                message = trimmed,
                idempotencyKey = generateIdempotencyKey(),
            ),
        )

        if (!response.ok) {
            _status.value = response.errorMessage ?: "chat.send failed"
            return SendMessageResult.Failure(response.errorMessage ?: "chat.send failed")
        }

        appendItem(ChatMessage(id = UUID.randomUUID().toString(), role = "user", text = trimmed))
        val runId = response.payload?.let { it as? JsonObject }?.string("runId")
        if (runId != null) {
            _currentRunId.value = runId
            val placeholderId = UUID.randomUUID().toString()
            streamingIdsByRun[runId] = placeholderId
            appendItem(ChatMessage(id = placeholderId, role = "assistant", text = "", isStreaming = true))
        }
        _status.value = "Waiting for assistant…"
        return SendMessageResult.Success
    }

    suspend fun abortRun(): SendMessageResult {
        val config = activeConfig ?: return SendMessageResult.Failure("Not connected")
        val runId = _currentRunId.value ?: return SendMessageResult.Failure("No active run")
        val response = socketClient.request(
            method = "chat.abort",
            params = buildAbortParams(config.sessionKey, runId),
        )
        return if (response.ok) SendMessageResult.Success
        else SendMessageResult.Failure(response.errorMessage ?: "abort failed")
    }

    fun close() {
        socketClient.close()
    }

    private fun handleEvent(event: GatewayEvent) {
        when (event) {
            is GatewayEvent.Challenge -> {
                currentNonce = event.nonce
                handleChallenge()
            }
            is GatewayEvent.Chat -> handleChatEvent(event.payload)
            is GatewayEvent.SessionTool -> upsertToolItem(extractToolItem(event.payload))
            is GatewayEvent.Unknown -> {
                if (event.name != "tick") {
                    _status.value = "Event: ${event.name}"
                }
            }
        }
    }

    private fun handleChallenge() {
        val config = activeConfig ?: return
        scope.launch {
            _status.value = "Authenticating…"
            _connectionStatus.value = ConnectionStatus.Authenticating
            val response = socketClient.request(
                "connect",
                buildConnectParams(
                    auth = resolveConnectAuthBundle(config),
                    identity = deviceIdentity,
                    nonce = currentNonce,
                )
            )
            if (!response.ok) {
                _isConnected.value = false
                _connectionStatus.value = ConnectionStatus.Error
                handleConnectFailure(response)
                return@launch
            }

            extractHelloAuth(response.payload)?.let { deviceAuth ->
                deviceAuthStore.storeDeviceToken(deviceIdentity.deviceId, ROLE_OPERATOR, deviceAuth)
            }

            _isConnected.value = true
            _connectionStatus.value = ConnectionStatus.Connected
            _status.value = "Connected"
            loadHistory(config)
        }
    }

    private suspend fun loadHistory(config: ConnectionConfig) {
        val response = socketClient.request("chat.history", buildHistoryParams(config.sessionKey))
        if (!response.ok) {
            _status.value = response.errorMessage ?: "chat.history failed"
            return
        }

        _timeline.value = extractMessagesFromHistory(response.payload)
        _status.value = "History loaded"
    }

    private fun handleChatEvent(payload: JsonObject) {
        val runId = payload.string("runId") ?: return
        val state = payload.string("state").orEmpty()
        val message = payload["message"]
        val text = extractMessageText(message).trim()
        val targetId = streamingIdsByRun[runId] ?: UUID.randomUUID().toString().also { streamingIdsByRun[runId] = it }

        when (state) {
            "delta" -> upsertStreamingMessage(targetId, text, true)
            "final" -> {
                upsertStreamingMessage(targetId, text, false)
                streamingIdsByRun.remove(runId)
                if (_currentRunId.value == runId) _currentRunId.value = null
                _status.value = "Response complete"
            }

            "error" -> {
                val errorText = payload.string("errorMessage") ?: "Unknown gateway error"
                upsertStreamingMessage(targetId, errorText, false)
                streamingIdsByRun.remove(runId)
                if (_currentRunId.value == runId) _currentRunId.value = null
                _status.value = errorText
            }

            "aborted" -> {
                upsertStreamingMessage(targetId, "[aborted]", false)
                streamingIdsByRun.remove(runId)
                if (_currentRunId.value == runId) _currentRunId.value = null
                _status.value = "Run aborted"
            }
        }
    }

    private fun appendItem(item: TimelineItem) {
        _timeline.update { current -> current + item }
    }

    private fun upsertToolItem(item: ToolItem) {
        _timeline.update { current ->
            val index = current.indexOfFirst { it.id == item.id }
            if (index >= 0) current.toMutableList().apply { set(index, item) }
            else current + item
        }
    }

    private fun upsertStreamingMessage(id: String, text: String, isStreaming: Boolean) {
        _timeline.update { current ->
            val index = current.indexOfFirst { it.id == id }
            val existing = current.getOrNull(index) as? ChatMessage
            val resolvedText = text.ifBlank { existing?.text.orEmpty() }
            val updated = ChatMessage(id = id, role = "assistant", text = resolvedText, isStreaming = isStreaming)
            if (index >= 0) {
                current.toMutableList().apply { set(index, updated) }
            } else {
                current + updated
            }
        }
    }

    private fun persistConfig(config: ConnectionConfig) {
        prefs.edit()
            .putString(KEY_GATEWAY_URL, config.gatewayUrl)
            .putString(KEY_TOKEN, config.gatewayToken)
            .putString(KEY_SESSION_KEY, config.sessionKey)
            .apply()
    }

    private fun resolveConnectAuthBundle(config: ConnectionConfig): ConnectAuthBundle {
        val storedDeviceAuth = deviceAuthStore.loadStoredDeviceToken(deviceIdentity.deviceId, ROLE_OPERATOR)
        return if (storedDeviceAuth != null) {
            ConnectAuthBundle(
                authToken = storedDeviceAuth.token,
                deviceToken = storedDeviceAuth.token,
                requestedScopes = if (storedDeviceAuth.scopes.isNotEmpty()) storedDeviceAuth.scopes else DEFAULT_REQUESTED_SCOPES,
            )
        } else {
            ConnectAuthBundle(
                authToken = config.gatewayToken,
                deviceToken = null,
                requestedScopes = DEFAULT_REQUESTED_SCOPES,
            )
        }
    }

    private fun handleConnectFailure(response: com.openclaw.androidclient.data.model.GatewayResponse) {
        val pairingRequestId = extractPairingRequestId(response)
        val message = when {
            pairingRequestId != null -> "Pairing requested: approve $pairingRequestId and reconnect"
            response.errorMessage?.contains("token mismatch", ignoreCase = true) == true -> {
                deviceAuthStore.clearStoredDeviceToken(deviceIdentity.deviceId, ROLE_OPERATOR)
                response.errorMessage
            }
            else -> response.errorMessage ?: "connect failed"
        }
        _status.value = message
    }

    companion object {
        private const val PREFS_NAME = "openclaw_client"
        private const val KEY_GATEWAY_URL = "gateway_url"
        private const val KEY_TOKEN = "gateway_token"
        private const val KEY_SESSION_KEY = "session_key"
    }
}
