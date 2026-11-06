package com.music.vivi.projector

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Same accent as the phone app. */
val ViviRed = Color(0xFFED5564)

private val Colors = darkColorScheme(
    primary = ViviRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF5C1B24),
    onPrimaryContainer = Color(0xFFFFDADD),
    background = Color(0xFF0E0E12),
    onBackground = Color(0xFFEDEDF0),
    surface = Color(0xFF16161C),
    onSurface = Color(0xFFEDEDF0),
    surfaceVariant = Color(0xFF22222B),
    onSurfaceVariant = Color(0xFFB4B4BE),
    outline = Color(0xFF55555F),
)

// Sizes are a step up from phone defaults because this is read from across a room.
private val Type = Typography(
    headlineLarge = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 18.sp),
    bodyMedium = TextStyle(fontSize = 16.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun ViviProjectorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Type, content = content)
}
