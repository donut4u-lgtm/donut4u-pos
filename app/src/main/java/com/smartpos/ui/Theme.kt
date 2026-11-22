package com.smartpos.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

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

/** The bright pink-to-orange brand colors used by the top bar. */
val BrandPink = Color(0xFFFF3D8B)
val BrandOrange = Color(0xFFFF8A3D)

private val LightColors = lightColorScheme(
    primary = Color(0xFFFF3D8B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E8),
    onPrimaryContainer = Color(0xFF45002A),
    secondary = Color(0xFFFF8A3D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E8),
    onSecondaryContainer = Color(0xFF8A0040),
    tertiary = Color(0xFF7C4DFF),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE6DCFF),
    onTertiaryContainer = Color(0xFF260070),
    background = Color(0xFFFFF4F9),
    onBackground = Color(0xFF2A1B33),
    surface = Color.White,
    onSurface = Color(0xFF2A1B33),
    surfaceVariant = Color(0xFFF9E4EE),
    onSurfaceVariant = Color(0xFF7D6B8A),
    surfaceContainer = Color(0xFFFFEAF3),
    outline = Color(0xFFD9B8CB)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF5FA2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF7A1B4A),
    onPrimaryContainer = Color(0xFFFFD9E8),
    secondary = Color(0xFFFFA45E),
    onSecondary = Color(0xFF3A1B00),
    secondaryContainer = Color(0xFF6B2144),
    onSecondaryContainer = Color(0xFFFFD9E8),
    tertiary = Color(0xFFB69CFF),
    onTertiary = Color(0xFF260070),
    tertiaryContainer = Color(0xFF4A2F9E),
    onTertiaryContainer = Color(0xFFE6DCFF),
    background = Color(0xFF1A1022),
    onBackground = Color(0xFFFBEEFA),
    surface = Color(0xFF271834),
    onSurface = Color(0xFFFBEEFA),
    surfaceVariant = Color(0xFF43294F),
    onSurfaceVariant = Color(0xFFC6A9D6),
    surfaceContainer = Color(0xFF2F1D3D),
    outline = Color(0xFF7A5A8A)
)

private val PosShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
fun PosTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = PosShapes,
        content = content
    )
}
