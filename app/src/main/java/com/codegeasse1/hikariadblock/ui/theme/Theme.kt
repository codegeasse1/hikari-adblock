package com.codegeasse1.hikariadblock.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3D5AFE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E2FF),
    onPrimaryContainer = Color(0xFF00155B),
    secondary = Color(0xFF5B5D72),
    secondaryContainer = Color(0xFFE0E1F9),
    onSecondaryContainer = Color(0xFF171B2C),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFF1F1F4),
    onSurfaceVariant = Color(0xFF46464F),
    error = Color(0xFFB3261E)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBEC2FF),
    onPrimary = Color(0xFF002782),
    primaryContainer = Color(0xFF2746B6),
    onPrimaryContainer = Color(0xFFDFE1FF),
    secondary = Color(0xFFC2C5DC),
    secondaryContainer = Color(0xFF23263A),
    onSecondaryContainer = Color(0xFFDEE1F9),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE6E1E9),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE6E1E9),
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFFC7C5D0),
    error = Color(0xFFFFB4AB)
)

@Composable
fun HikariAdBlockTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colorScheme, typography = Typography(), content = content)
}
