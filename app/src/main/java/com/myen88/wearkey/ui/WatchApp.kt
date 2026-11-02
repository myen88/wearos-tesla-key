package com.myen88.wearkey.ui

import android.Manifest
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListItemScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition

@Composable
fun WatchApp(
    pendingCommand: String?,
    onCommandConsumed: () -> Unit,
    model: WatchKeyViewModel = viewModel(),
) {
    val state by model.state.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        model.onPermissionsChanged()
    }
    LaunchedEffect(pendingCommand, state.screen) {
        if (pendingCommand != null && state.screen != Screen.PERMISSION) {
            model.onExternalCommand(pendingCommand)
            onCommandConsumed()
        }
    }
    val view = LocalView.current
    DisposableEffect(state.busy) {
        view.keepScreenOn = state.busy
        onDispose { view.keepScreenOn = false }
    }
    WatchKeyTheme {
        val listState = rememberScalingLazyListState()
        val round = LocalConfiguration.current.isScreenRound
        Scaffold(
            timeText = { TimeText() },
            vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
            positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
        ) {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(
                    top = 28.dp,
                    bottom = 28.dp,
                    start = if (round) 14.dp else 8.dp,
                    end = if (round) 14.dp else 8.dp,
                ),
                autoCentering = AutoCenteringParams(itemIndex = 0),
            ) {
                item { Text("Watch Key", style = MaterialTheme.typography.title3) }
                if (state.busy) {
                    item { CircularProgressIndicator(modifier = Modifier.padding(vertical = 6.dp)) }
                }
                state.banner?.let { banner ->
                    item {
                        Text(
                            text = banner,
                            color = if (state.bannerIsError) MaterialTheme.colors.error else MaterialTheme.colors.secondary,
                            style = MaterialTheme.typography.caption1,
                        )
                    }
                }
                when (state.screen) {
                    Screen.PERMISSION -> item {
                        ColumnCopy(
                            "The watch needs Bluetooth to talk to the car directly. Nothing is sent to a server.",
                        )
                    }
                    Screen.ONBOARDING -> {
                        item {
                            ColumnCopy("Enter the VIN. This watch creates its own key. The car asks you to approve it with a key card.")
                        }
                        item { VinField(state.vin, model::setVinDraft) }
                        state.vinError?.let { error ->
                            item { Text(error, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption2) }
                        }
                    }
                    Screen.PAIRING -> {
                        item { Text(state.fingerprint, style = MaterialTheme.typography.title3) }
                        item {
                            ColumnCopy(
                                if (state.keyIsHardwareOnly) {
                                    "Private key stays in the watch keystore and cannot be exported."
                                } else {
                                    "This watch exposed the private key. Do not pair it."
                                },
                            )
                        }
                        item {
                            ColumnCopy("Stand beside the car. When the screen prompts you, tap the key card on the center console and confirm.")
                        }
                        item {
                            val label = if (state.androidFormFactor) "Key type: Android device" else "Key type: cloud key"
                            Chip(
                                onClick = model::toggleFormFactor,
                                enabled = !state.busy,
                                label = { Text(label) },
                                modifier = Modifier.fillParentMaxWidth(),
                            )
                        }
                    }
                    Screen.DASHBOARD -> {
                        item {
                            Text(
                                state.vin,
                                style = MaterialTheme.typography.caption2,
                                color = MaterialTheme.colors.onSurfaceVariant,
                            )
                        }
                        item { Text(state.lockLabel, style = MaterialTheme.typography.title2) }
                        item {
                            val extra = buildString {
                                if (state.asleep == true) append("Car asleep. ")
                                if (state.trunkOpen) append("Trunk open. ")
                                if (state.frunkOpen) append("Frunk open.")
                            }.trim()
                            Text(
                                text = listOf(state.batteryLabel, extra).filter { it.isNotBlank() }.joinToString("  "),
                                style = MaterialTheme.typography.caption1,
                                color = MaterialTheme.colors.onSurfaceVariant,
                            )
                        }
                        item { ActionChip("Unlock", primary = true, enabled = !state.busy, onClick = model::unlock) }
                        item { ActionChip("Lock", enabled = !state.busy, onClick = model::lock) }
                        item { ActionChip("Open trunk", enabled = !state.busy, onClick = model::trunk) }
                        item { ActionChip("Open frunk", enabled = !state.busy, onClick = model::frunk) }
                        item { ActionChip("Climate on", enabled = !state.busy) { model.climate(true) } }
                        item { ActionChip("Climate off", enabled = !state.busy) { model.climate(false) } }
                        item { ActionChip("Refresh", enabled = !state.busy, onClick = model::refresh) }
                        item {
                            Text(
                                "Key ${state.fingerprint}",
                                style = MaterialTheme.typography.caption2,
                                color = MaterialTheme.colors.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    when (state.screen) {
                        Screen.PERMISSION -> ActionChip("Allow Bluetooth", primary = true) {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.BLUETOOTH_SCAN,
                                    Manifest.permission.BLUETOOTH_CONNECT,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ),
                            )
                        }
                        Screen.ONBOARDING -> ActionChip("Create key", primary = true, enabled = !state.busy, onClick = model::continueFromVin)
                        Screen.PAIRING -> ActionChip("Start pairing", primary = true, enabled = !state.busy && state.keyIsHardwareOnly, onClick = model::startPairing)
                        Screen.DASHBOARD -> {
                            if (state.confirmForget) {
                                ActionChip("Remove key from watch", primary = false, enabled = !state.busy, onClick = model::forget)
                            } else {
                                ActionChip("Remove key from watch", enabled = !state.busy, onClick = model::requestForget)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnCopy(text: String) {
    Text(text, style = MaterialTheme.typography.body2)
}

@Composable
private fun ScalingLazyListItemScope.ActionChip(
    label: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Chip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        colors = if (primary) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillParentMaxWidth(),
    )
}

@Composable
private fun VinField(value: String, onChange: (String) -> Unit) {
    val color = MaterialTheme.colors.onSurface.toArgb()
    val hint = MaterialTheme.colors.onSurfaceVariant.toArgb()
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        factory = { context ->
            EditText(context).apply {
                setText(value)
                setTextColor(color)
                setHintTextColor(hint)
                this.hint = "5YJ…"
                textSize = 16f
                isSingleLine = true
                background = null
                filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(17))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                addTextChangedListener(SimpleWatcher { onChange(it) })
            }
        },
        update = { field ->
            if (field.text.toString() != value) {
                field.setText(value)
                field.setSelection(field.text.length)
            }
        },
    )
}

private class SimpleWatcher(val onChange: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    override fun afterTextChanged(s: android.text.Editable?) {
        onChange(s?.toString().orEmpty())
    }
}
