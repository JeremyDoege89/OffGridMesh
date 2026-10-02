package dev.offgridmesh.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Fixed high-contrast palette — no dynamic color; this gets read in sunlight with gloves on (§7). */
private val Dark = darkColorScheme(
    primary = Color(0xFFF2B33D),
    onPrimary = Color(0xFF1A1200),
    primaryContainer = Color(0xFF2E4A3E),
    onPrimaryContainer = Color(0xFFE6F2EC),
    secondaryContainer = Color(0xFF33413A),
    onSecondaryContainer = Color(0xFFE6F2EC),
    background = Color(0xFF0E1412),
    surface = Color(0xFF0E1412),
    surfaceContainer = Color(0xFF18211D),
    error = Color(0xFFFF6B5E),
)

private val Light = lightColorScheme(
    primary = Color(0xFF8A5A00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE6DA),
    onPrimaryContainer = Color(0xFF0E2A1E),
    secondaryContainer = Color(0xFFDDE7E1),
    onSecondaryContainer = Color(0xFF14211B),
    background = Color(0xFFF7F8F6),
    surface = Color(0xFFF7F8F6),
    surfaceContainer = Color(0xFFE9EEEA),
    error = Color(0xFFB3261E),
)

@Composable
fun OffGridTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}
