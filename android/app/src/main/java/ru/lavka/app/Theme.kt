package ru.lavka.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFE8553D)
val Ok = Color(0xFF2F7D5B)

private val Light = lightColorScheme(
    primary = Accent, onPrimary = Color.White,
    primaryContainer = Color(0xFFFDE9E4), onPrimaryContainer = Color(0xFF5A1A10),
    background = Color(0xFFF6F2EC), onBackground = Color(0xFF1F1B17),
    surface = Color.White, onSurface = Color(0xFF1F1B17),
    surfaceVariant = Color(0xFFE7E0D6), onSurfaceVariant = Color(0xFF7A7168),
    outline = Color(0xFFE7E0D6),
)

private val Dark = darkColorScheme(
    primary = Accent, onPrimary = Color.White,
    primaryContainer = Color(0xFF3A1F19), onPrimaryContainer = Color(0xFFFFDAD2),
    background = Color(0xFF14110F), onBackground = Color(0xFFF3EDE6),
    surface = Color(0xFF1E1A17), onSurface = Color(0xFFF3EDE6),
    surfaceVariant = Color(0xFF332D28), onSurfaceVariant = Color(0xFF9A9086),
    outline = Color(0xFF332D28),
)

@Composable
fun LavkaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
