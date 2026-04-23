package com.openclaw.androidclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openclaw.androidclient.data.model.ConnectionStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Gateway connection", style = MaterialTheme.typography.titleMedium)

            StatusChip(status = state.connectionStatus, message = state.statusMessage)

            OutlinedTextField(
                value = state.gatewayUrl,
                onValueChange = viewModel::updateGatewayUrl,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Gateway WebSocket URL") },
                singleLine = true,
                enabled = state.connectionStatus == ConnectionStatus.Disconnected || state.connectionStatus == ConnectionStatus.Error,
            )
            OutlinedTextField(
                value = state.token,
                onValueChange = viewModel::updateToken,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Gateway token") },
                singleLine = true,
                enabled = state.connectionStatus == ConnectionStatus.Disconnected || state.connectionStatus == ConnectionStatus.Error,
            )
            OutlinedTextField(
                value = state.sessionKey,
                onValueChange = viewModel::updateSessionKey,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Session key") },
                singleLine = true,
                enabled = state.connectionStatus == ConnectionStatus.Disconnected || state.connectionStatus == ConnectionStatus.Error,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = viewModel::connect,
                    enabled = !state.isConnecting && state.connectionStatus != ConnectionStatus.Connected,
                ) {
                    if (state.isConnecting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.size(8.dp))
                    }
                    Text("Connect")
                }
                TextButton(
                    onClick = viewModel::disconnect,
                    enabled = state.connectionStatus != ConnectionStatus.Disconnected,
                ) {
                    Text("Disconnect")
                }
            }
        }
    }
}
