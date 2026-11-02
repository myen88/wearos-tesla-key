package com.myen88.wearkey

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.myen88.wearkey.ui.WatchApp

class MainActivity : ComponentActivity() {
    private var pendingCommand by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingCommand = intent?.getStringExtra(EXTRA_COMMAND)
        setContent {
            WatchApp(
                pendingCommand = pendingCommand,
                onCommandConsumed = { pendingCommand = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingCommand = intent.getStringExtra(EXTRA_COMMAND)
    }

    companion object {
        const val EXTRA_COMMAND = "command"
    }
}
