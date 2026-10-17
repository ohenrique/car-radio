package com.ohenrique.carradio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object RadioColors {
    val Background = Color(0xFF0B0B0C)
    val Surface = Color(0xFF17171A)
    val SurfaceHigh = Color(0xFF222226)
    val Text = Color(0xFFF2F2F2)
    val TextDim = Color(0xFF8A8A8E)
    val TextFaint = Color(0xFF4A4A4F)
    val Accent = Color(0xFFFF9F0A)
    val Error = Color(0xFFFF6B5E)
}

/** Always dark: a car screen at night should never flash white. */
@Composable
fun RadioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = RadioColors.Accent,
            onPrimary = Color.Black,
            background = RadioColors.Background,
            onBackground = RadioColors.Text,
            surface = RadioColors.Surface,
            onSurface = RadioColors.Text,
            surfaceVariant = RadioColors.SurfaceHigh,
            onSurfaceVariant = RadioColors.TextDim,
            error = RadioColors.Error,
        ),
        content = content,
    )
}
