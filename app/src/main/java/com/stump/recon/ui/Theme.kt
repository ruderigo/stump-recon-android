package com.stump.recon.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object StumpColors {
    val Canvas = Color(0xFF07090E)
    val Surface = Color(0xFF111827)
    val SurfaceElevated = Color(0xFF162032)
    val Border = Color(0xFF1E293B)
    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFF94A3B8)
    val Cyan = Color(0xFF06B6D4)
    val Emerald = Color(0xFF10B981)
    val Amber = Color(0xFFF59E0B)
    val Rose = Color(0xFFF43F5E)
    val Violet = Color(0xFFA855F7)
}

private val DarkScheme = darkColorScheme(
    primary = StumpColors.Cyan,
    secondary = StumpColors.Emerald,
    tertiary = StumpColors.Amber,
    background = StumpColors.Canvas,
    surface = StumpColors.Surface,
    surfaceVariant = StumpColors.SurfaceElevated,
    outline = StumpColors.Border,
    onPrimary = Color.White,
    onBackground = StumpColors.TextPrimary,
    onSurface = StumpColors.TextPrimary,
    onSurfaceVariant = StumpColors.TextSecondary,
    error = StumpColors.Rose
)

@Composable
fun StumpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        content = content
    )
}
