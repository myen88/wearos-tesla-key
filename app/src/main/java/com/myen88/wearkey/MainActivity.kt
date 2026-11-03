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
    private var pendingVin by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingCommand = intent?.getStringExtra(EXTRA_COMMAND)
        pendingVin = intent?.getStringExtra(EXTRA_VIN)
        setContent {
            WatchApp(
                pendingCommand = pendingCommand,
                onCommandConsumed = { pendingCommand = null },
                pendingVin = pendingVin,
                onVinConsumed = { pendingVin = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingCommand = intent.getStringExtra(EXTRA_COMMAND)
        pendingVin = intent.getStringExtra(EXTRA_VIN)
    }

    companion object {
        const val EXTRA_COMMAND = "command"
        const val EXTRA_VIN = "vin"
    }
}
