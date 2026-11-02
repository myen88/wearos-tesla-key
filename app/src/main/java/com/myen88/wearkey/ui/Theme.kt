package com.myen88.wearkey.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

private val WatchColors = Colors(
    primary = Color(0xFFF2C14E),
    primaryVariant = Color(0xFFB8892C),
    secondary = Color(0xFF8FD0A5),
    secondaryVariant = Color(0xFF1E6B45),
    background = Color.Black,
    surface = Color(0xFF141414),
    error = Color(0xFFFFB4AB),
    onPrimary = Color(0xFF2A2108),
    onSecondary = Color(0xFF062010),
    onError = Color(0xFF690005),
    onBackground = Color(0xFFF6F3EC),
    onSurface = Color(0xFFF6F3EC),
    onSurfaceVariant = Color(0xFFB7B1A6),
)

@Composable
fun WatchKeyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = WatchColors, content = content)
}
