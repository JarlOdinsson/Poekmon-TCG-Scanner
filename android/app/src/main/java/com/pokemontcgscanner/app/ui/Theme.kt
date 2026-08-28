package com.pokemontcgscanner.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF82A9FF), secondary = Color(0xFFFFCB47),
    background = Color(0xFF0D111B), surface = Color(0xFF151B28), surfaceVariant = Color(0xFF212A3A)
)
private val LightColors = lightColorScheme(primary = Color(0xFF285FC4), secondary = Color(0xFF8A6100))

@Composable
fun CardDexTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
