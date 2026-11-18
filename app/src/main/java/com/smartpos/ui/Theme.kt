package com.smartpos.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** One color per label column. Index 0..11 (see LABEL_COLOR_COUNT in data/Entities.kt). */
val LabelPalette = listOf(
    Color(0xFFE53935), // red
    Color(0xFFFB8C00), // orange
    Color(0xFFF9A825), // amber
    Color(0xFF43A047), // green
    Color(0xFF00897B), // teal
    Color(0xFF00ACC1), // cyan
    Color(0xFF1E88E5), // blue
    Color(0xFF3949AB), // indigo
    Color(0xFF8E24AA), // purple
    Color(0xFFD81B60), // pink
    Color(0xFF6D4C41), // brown
    Color(0xFF546E7A)  // slate
)

fun labelColor(index: Int): Color = LabelPalette[Math.floorMod(index, LabelPalette.size)]

fun onLabelColor(c: Color): Color = if (c.luminance() > 0.45f) Color.Black else Color.White

private val LightColors = lightColorScheme(
    primary = Color(0xFF2D5BFF),
    onPrimary = Color.White,
    secondary = Color(0xFF00897B),
    background = Color(0xFFF6F7FB),
    surface = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB4FF),
    onPrimary = Color(0xFF002A8C),
    secondary = Color(0xFF4DB6AC)
)

@Composable
fun PosTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
