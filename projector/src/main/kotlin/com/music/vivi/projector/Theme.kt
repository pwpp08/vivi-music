package com.music.vivi.projector

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Accent taken from the phone app's green palette. */
val ViviAccent = Color(0xFFB7CF9E)

private val Colors = darkColorScheme(
    primary = Color(0xFFB7CF9E),
    onPrimary = Color(0xFF223514),
    primaryContainer = Color(0xFF3A4C2E),
    onPrimaryContainer = Color(0xFFD3EBB8),
    secondary = Color(0xFFBFCBAD),
    onSecondary = Color(0xFF2A3420),
    secondaryContainer = Color(0xFF3F4A34),
    onSecondaryContainer = Color(0xFFDBE7C8),
    tertiary = Color(0xFFFFF8E6),
    background = Color(0xFF0C100B),
    onBackground = Color(0xFFE2E4D8),
    surface = Color(0xFF151A13),
    onSurface = Color(0xFFE2E4D8),
    surfaceVariant = Color(0xFF1F251C),
    onSurfaceVariant = Color(0xFFC3C9B6),
    outline = Color(0xFF8D9382),
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
