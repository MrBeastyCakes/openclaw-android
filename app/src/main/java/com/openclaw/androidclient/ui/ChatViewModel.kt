package com.openclaw.androidclient.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openclaw.androidclient.data.model.ChatUiState
import com.openclaw.androidclient.data.model.ConnectionConfig
import com.openclaw.androidclient.data.model.ConnectionStatus
import com.openclaw.androidclient.data.repo.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ChatRepository(application.applicationContext)
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        val saved = repository.loadSavedConfig()
        _uiState.value = _uiState.value.copy(
            gatewayUrl = saved.gatewayUrl,
            token = saved.gatewayToken,
            sessionKey = saved.sessionKey,
        )

        viewModelScope.launch {
            repository.messages.collectLatest { messages ->
                _uiState.value = _uiState.value.copy(messages = messages)
            }
        }

        viewModelScope.launch {
            repository.status.collectLatest { status ->
                _uiState.value = _uiState.value.copy(
                    statusMessage = status,
                )
            }
        }

        viewModelScope.launch {
            repository.connectionStatus.collectLatest { connectionStatus ->
                _uiState.value = _uiState.value.copy(
                    connectionStatus = connectionStatus,
                    isConnecting = connectionStatus == ConnectionStatus.Connecting || connectionStatus == ConnectionStatus.Authenticating,
                )
            }
        }
    }

    fun updateGatewayUrl(value: String) {
        _uiState.value = _uiState.value.copy(gatewayUrl = value)
    }

    fun updateToken(value: String) {
        _uiState.value = _uiState.value.copy(token = value)
    }

    fun updateSessionKey(value: String) {
        _uiState.value = _uiState.value.copy(sessionKey = value)
    }

    fun updateDraftMessage(value: String) {
        _uiState.value = _uiState.value.copy(draftMessage = value)
    }

    fun connect() {
        val state = _uiState.value
        if (state.gatewayUrl.isBlank() || state.token.isBlank() || state.sessionKey.isBlank()) {
            _uiState.value = state.copy(
                connectionStatus = ConnectionStatus.Error,
                statusMessage = "Gateway URL, token, and session key are required",
            )
            return
        }

        _uiState.value = state.copy(
            isConnecting = true,
            connectionStatus = ConnectionStatus.Connecting,
            statusMessage = "Opening gateway…",
        )
        repository.connect(
            ConnectionConfig(
                gatewayUrl = state.gatewayUrl.trim(),
                gatewayToken = state.token.trim(),
                sessionKey = state.sessionKey.trim(),
            )
        )
    }

    fun disconnect() {
        repository.disconnect()
        _uiState.value = _uiState.value.copy(
            isConnecting = false,
            connectionStatus = ConnectionStatus.Disconnected,
            statusMessage = "Disconnected",
        )
    }

    fun sendMessage() {
        val message = _uiState.value.draftMessage.trim()
        if (message.isEmpty()) return
        _uiState.value = _uiState.value.copy(isSending = true)
        viewModelScope.launch {
            when (val result = repository.sendMessage(message)) {
                is com.openclaw.androidclient.data.model.SendMessageResult.Success -> {
                    _uiState.value = _uiState.value.copy(isSending = false, draftMessage = "")
                }

                is com.openclaw.androidclient.data.model.SendMessageResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        draftMessage = message,
                        statusMessage = result.message,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        repository.close()
        super.onCleared()
    }
}

class ChatViewModelFactory(
    private val applicationContext: android.content.Context,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(applicationContext.applicationContext as Application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
