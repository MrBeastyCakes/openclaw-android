package com.openclaw.androidclient

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.openclaw.androidclient.ui.ChatScreen
import com.openclaw.androidclient.ui.ChatViewModel
import com.openclaw.androidclient.ui.ChatViewModelFactory
import com.openclaw.androidclient.ui.theme.OpenClawTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels {
        ChatViewModelFactory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            OpenClawTheme {
                ChatScreen(viewModel = viewModel)
            }
        }
    }
}
