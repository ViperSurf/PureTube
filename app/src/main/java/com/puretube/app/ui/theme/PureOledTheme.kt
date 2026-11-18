package com.puretube.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PureBlack = Color(0xFF000000)
val PureSurface = Color(0xFF0D0D0D)
val PureBorder = Color(0xFF1A1A1A)
val PureTextSecondary = Color(0xFFB0B0B0)
val PureAccent = Color(0xFF10B981)

private val PureOledColorScheme = darkColorScheme(
    primary = PureAccent,
    onPrimary = Color.Black,
    background = PureBlack,
    onBackground = Color.White,
    surface = PureSurface,
    onSurface = Color.White,
    surfaceVariant = PureSurface,
    onSurfaceVariant = PureTextSecondary,
    outline = PureBorder
)

/** Pure OLED theme: true #000000 canvas, #1A1A1A borders, emerald accent. */
@Composable
fun PureOledTheme(content: @Composable () -> Unit) {
    // The app is OLED-dark by design regardless of the system setting.
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = PureOledColorScheme, content = content)
}
