package com.grove.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object GroveColors {
    val Bg = Color(0xFF0F1713)
    val Surface = Color(0xFF1B231E)
    val Surface2 = Color(0xFF242E27)
    val Line = Color(0xFF2F3A33)
    val Text = Color(0xFFECEFE9)
    val Muted = Color(0xFFA3AEA6)
    val Green = Color(0xFF8FCB8B)
    val Amber = Color(0xFFE9A23B)
    val WarnBg = Color(0xFF33281A)
    val Warn = Color(0xFFF2C27A)
}

@Composable
fun GroveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = GroveColors.Green,
            onPrimary = GroveColors.Bg,
            secondary = GroveColors.Amber,
            background = GroveColors.Bg,
            onBackground = GroveColors.Text,
            surface = GroveColors.Surface,
            onSurface = GroveColors.Text,
            surfaceVariant = GroveColors.Surface2,
            onSurfaceVariant = GroveColors.Muted,
            outline = GroveColors.Line,
        ),
        content = content,
    )
}
