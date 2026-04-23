package com.openclaw.androidclient.ui

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openclaw.androidclient.data.model.ChatMessage
import com.openclaw.androidclient.data.model.ChatUiState
import com.openclaw.androidclient.data.model.ConnectionStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateToSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    // Scroll to bottom when a new message is appended
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    // Follow streaming deltas only when already at bottom
    val streamingText = state.messages.lastOrNull()?.takeIf { it.isStreaming }?.text
    LaunchedEffect(streamingText) {
        if (streamingText != null && !listState.canScrollForward && state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    StatusDot(
                        status = state.connectionStatus,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                },
                title = {
                    Text(
                        text = "OpenClaw",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                state = state,
                onDraftChange = viewModel::updateDraftMessage,
                onSend = viewModel::sendMessage,
                onAbort = viewModel::abort,
            )
        },
    ) { innerPadding ->
        if (state.messages.isEmpty()) {
            EmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                state = state,
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
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

@Composable
fun StatusChip(status: ConnectionStatus, message: String) {
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
private fun StatusDot(status: ConnectionStatus, modifier: Modifier = Modifier) {
    val isAnimating = status == ConnectionStatus.Connecting || status == ConnectionStatus.Authenticating
    val color = when (status) {
        ConnectionStatus.Connected -> Color(0xFF4CAF50)
        ConnectionStatus.Error -> Color(0xFFF44336)
        ConnectionStatus.Connecting, ConnectionStatus.Authenticating -> Color(0xFFFFC107)
        ConnectionStatus.Disconnected -> Color(0xFF9E9E9E)
    }

    val transition = rememberInfiniteTransition(label = "status_dot")
    val pulseAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = InfiniteRepeatableSpec(tween(600), repeatMode = RepeatMode.Reverse),
        label = "pulse",
    )

    Box(
        modifier = modifier
            .size(10.dp)
            .background(
                color = color.copy(alpha = if (isAnimating) pulseAlpha else 1f),
                shape = CircleShape,
            ),
    )
}

@Composable
private fun EmptyState(modifier: Modifier, state: ChatUiState) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (state.connectionStatus == ConnectionStatus.Connected) {
                "Connected. History is empty or still loading."
            } else if (state.isConnecting) {
                "Connecting…"
            } else {
                "Tap the settings icon to configure the gateway."
            },
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
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
                if (message.isStreaming) {
                    StreamingText(text = message.text)
                } else {
                    Text(text = message.text.ifBlank { "…" })
                }
            }
        }
    }
}

@Composable
private fun StreamingText(text: String) {
    val transition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = InfiniteRepeatableSpec(tween(500), repeatMode = RepeatMode.Reverse),
        label = "blink",
    )

    Row(verticalAlignment = Alignment.Bottom) {
        if (text.isNotEmpty()) {
            Text(text = text)
        }
        Text(
            text = "▋",
            modifier = Modifier.alpha(cursorAlpha),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun Composer(
    state: ChatUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onAbort: () -> Unit,
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

            if (state.currentRunId != null) {
                Button(
                    onClick = onAbort,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Stop")
                }
            } else {
                Button(
                    onClick = onSend,
                    enabled = state.connectionStatus == ConnectionStatus.Connected
                            && state.draftMessage.isNotBlank()
                            && !state.isSending,
                ) {
                    Text("Send")
                }
            }
        }
    }
}
