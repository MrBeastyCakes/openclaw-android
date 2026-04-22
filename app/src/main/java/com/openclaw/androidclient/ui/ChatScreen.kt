package com.openclaw.androidclient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openclaw.androidclient.data.model.ChatMessage
import com.openclaw.androidclient.data.model.ChatUiState
import com.openclaw.androidclient.data.model.ConnectionStatus

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Composer(
                state = state,
                onDraftChange = viewModel::updateDraftMessage,
                onSend = viewModel::sendMessage,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            ConnectionPanel(
                state = state,
                onGatewayUrlChange = viewModel::updateGatewayUrl,
                onTokenChange = viewModel::updateToken,
                onSessionKeyChange = viewModel::updateSessionKey,
                onConnect = viewModel::connect,
                onDisconnect = viewModel::disconnect,
            )

            HorizontalDivider()

            if (state.messages.isEmpty()) {
                EmptyState(modifier = Modifier.weight(1f), state = state)
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(message = message)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionPanel(
    state: ChatUiState,
    onGatewayUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onSessionKeyChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "OpenClaw gateway",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        StatusChip(status = state.connectionStatus, message = state.statusMessage)
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = state.gatewayUrl,
            onValueChange = onGatewayUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Gateway WebSocket URL") },
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = state.token,
            onValueChange = onTokenChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Gateway token") },
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = state.sessionKey,
            onValueChange = onSessionKeyChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Session key") },
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onConnect,
                enabled = !state.isConnecting && state.connectionStatus != ConnectionStatus.Connected,
            ) {
                if (state.isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                }
                Text("Connect")
            }

            TextButton(
                onClick = onDisconnect,
                enabled = state.connectionStatus != ConnectionStatus.Disconnected,
            ) {
                Text("Disconnect")
            }
        }
    }
}

@Composable
private fun StatusChip(status: ConnectionStatus, message: String) {
    val background = when (status) {
        ConnectionStatus.Connected -> Color(0xFF12351D)
        ConnectionStatus.Error -> Color(0xFF4A1F1F)
        ConnectionStatus.Authenticating,
        ConnectionStatus.Connecting -> Color(0xFF2D2A12)
        ConnectionStatus.Disconnected -> MaterialTheme.colorScheme.surfaceVariant
    }

    val foreground = when (status) {
        ConnectionStatus.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> Color.White
    }

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = background,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = foreground,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier, state: ChatUiState) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (state.connectionStatus == ConnectionStatus.Connected) {
                "Connected. History is empty or still loading."
            } else {
                "Connect to the local OpenClaw gateway to start chatting."
            },
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    val containerColor = when (message.role) {
        "user" -> MaterialTheme.colorScheme.primaryContainer
        "assistant" -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(horizontalAlignment = alignment) {
        Text(
            text = message.role.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Card {
            Column(
                modifier = Modifier
                    .background(containerColor)
                    .padding(12.dp),
            ) {
                Text(text = message.text.ifBlank { "…" })
                if (message.isStreaming) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Streaming…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(
    state: ChatUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(shadowElevation = 6.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = state.draftMessage,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                label = { Text("Message") },
                enabled = state.connectionStatus == ConnectionStatus.Connected && !state.isSending,
                minLines = 2,
                maxLines = 5,
            )
            Button(
                onClick = onSend,
                enabled = state.connectionStatus == ConnectionStatus.Connected && state.draftMessage.isNotBlank() && !state.isSending,
            ) {
                Text("Send")
            }
        }
    }
}
