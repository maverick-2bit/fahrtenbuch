package at.zweibit.fahrtenbuch.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val StartGruen = Color(0xFF2E7D32)
val StoppRot = Color(0xFFC62828)

private val Hell = lightColorScheme(
    primary = Color(0xFF1E5AA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3E),
    secondary = Color(0xFF565F71),
    background = Color(0xFFF8F9FC),
    surface = Color(0xFFF8F9FC),
    surfaceVariant = Color(0xFFE1E2EC),
)

private val Dunkel = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF003064),
    primaryContainer = Color(0xFF00468C),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBEC6DC),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
    surfaceVariant = Color(0xFF44474E),
)

@Composable
fun FahrtenbuchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dunkel else Hell,
        content = content,
    )
}
