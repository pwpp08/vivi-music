package com.xlollx.songport.ui

import com.xlollx.songport.providers.*
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Palette del brand: indaco/viola come colore principale, magenta come accento.
 * Stessa identita' dell'icona, in chiaro e in scuro. E' il tema di default, cosi' l'app e'
 * riconoscibile su ogni telefono; chi vuole i colori del proprio sfondo attiva "Colori dinamici"
 * nelle impostazioni (Android 12+).
 */
object Brand {
    val Indigo = Color(0xFF4338CA)
    val Violet = Color(0xFF7C3AED)
    val Magenta = Color(0xFFDB2777)
    /** The end of the hero gradient: deep enough for white text to read at AA (about 6:1). */
    val MagentaDeep = Color(0xFFB0185F)

    /**
     * "The beam", the brand surface of the icon, the store graphics and the site: a near-black
     * base crossed by a luminous band tilted [BeamAngle] degrees. Across the band, in units of the
     * surface's height from its edge, the colour runs through [BeamStops]: black, a deep red glow,
     * a thin violet-blue line on the edge, cyan, then white; along it a red sweep (Screen blend,
     * black on the left to [BeamSweep] on the right) turns the cyan to white towards the right.
     * Painted by [paintBeam], on the CPU.
     * The same numbers are in scripts/store_graphics.sh, res/drawable/ic_launcher_background.xml
     * and docs/assets/site.css. White text belongs on the dark side (below the edge), where the
     * glow stays under 0.7 red and the contrast above 7:1.
     */
    const val BeamAngle = 12f
    val BeamStops: List<Pair<Float, Color>> = listOf(
        -0.45f to Color(0xFF000000), -0.008f to Color(0xFF3A0A10), 0.008f to Color(0xFFED5564),
        0.08f to Color(0xFFFF8A80), 0.61f to Color(0xFFFFFFFF),
    )
    val BeamSweep = Color(0xFF990000)
}

/**
 * Paints the beam behind the content, with the band's edge through the right border at [edgeY]
 * (a fraction of the height): low enough to cross the top-right corner of a tile in the header,
 * high enough that the text below stays on the dark side.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun Modifier.brandBeam(edgeY: Float = 0.35f): Modifier {
    // Dark on purpose, in light themes too: the text on these surfaces is white.
    val scheme = MaterialTheme.colorScheme
    return background(
        androidx.compose.ui.graphics.Brush.linearGradient(
            listOf(lerp(scheme.primary, Color.Black, 0.62f), lerp(scheme.tertiary, Color.Black, 0.45f)),
        ),
    )
}

/** The beam is all soft gradients: half resolution is indistinguishable, and a quarter of the memory. */
private const val BEAM_SCALE = 0.5f

/**
 * The beam on an [android.graphics.Canvas] of [w]×[h] (a bitmap's, so a software one): the band's
 * edge through the right border at [edgeY] of the height, the stops across it, the red sweep
 * screened along it. One painter for the Compose surfaces and the shareable cards (ui/Cards.kt).
 */
fun paintBeam(c: android.graphics.Canvas, w: Int, h: Int, edgeY: Float) {
    val rad = Math.toRadians(Brand.BeamAngle.toDouble())
    val nx = Math.sin(rad).toFloat(); val ny = -Math.cos(rad).toFloat()
    val ax = w.toFloat(); val ay = h * edgeY
    val first = Brand.BeamStops.first().first
    val span = Brand.BeamStops.last().first - first
    val colors = Brand.BeamStops.map { it.second.toArgb() }.toIntArray()
    val positions = Brand.BeamStops.map { (it.first - first) / span }.toFloatArray()
    val base = android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(
            ax + nx * first * h, ay + ny * first * h, ax + nx * (first + span) * h, ay + ny * (first + span) * h,
            colors, positions, android.graphics.Shader.TileMode.CLAMP,
        )
    }
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), base)
    val sweep = android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), 0f, android.graphics.Color.BLACK, Brand.BeamSweep.toArgb(), android.graphics.Shader.TileMode.CLAMP)
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
    }
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), sweep)
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF5B3FD9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF1B0062),
    secondary = Color(0xFF615B71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFFB4206A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD8E8),
    onTertiaryContainer = Color(0xFF3E0021),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1C1B22),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1C1B22),
    surfaceVariant = Color(0xFFE7E0EE),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F2FC),
    surfaceContainer = Color(0xFFF0ECF7),
    surfaceContainerHigh = Color(0xFFEAE6F1),
    surfaceContainerHighest = Color(0xFFE4E0EB),
    outline = Color(0xFF7A757F),
    outlineVariant = Color(0xFFCBC4D1),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFC8BFFF),
    onPrimary = Color(0xFF2B0A9A),
    primaryContainer = Color(0xFF4327C0),
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFFCBC3DB),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF494458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFFFB0D0),
    onTertiary = Color(0xFF5F1139),
    tertiaryContainer = Color(0xFF8E1B55),
    onTertiaryContainer = Color(0xFFFFD8E8),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E1EA),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E1EA),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceContainerLowest = Color(0xFF0F0D13),
    surfaceContainerLow = Color(0xFF1D1B20),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36343B),
    outline = Color(0xFF948F99),
    outlineVariant = Color(0xFF49454F),
)

/**
 * Forme: le schede principali sono ampie e morbide (large), gli elementi dentro una scheda medi,
 * chip e pillole restano tonde. Una regola sola, cosi' i raggi non cambiano da una schermata all'altra.
 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * Scala tipografica propria, sul font di sistema: titoli e numeri in grassetto e un po' piu'
 * stretti, cosi' si vede subito cosa conta ("3 syncs", "+12") e il resto resta leggibile. Niente
 * font scaricato: la build F-Droid deve restare riproducibile e l'app funzionare senza rete.
 */
private val Display = TextStyle(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
private val AppTypography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.merge(Display),
        displayMedium = t.displayMedium.merge(Display),
        displaySmall = t.displaySmall.merge(Display),
        headlineLarge = t.headlineLarge.merge(Display),
        headlineMedium = t.headlineMedium.merge(Display),
        headlineSmall = t.headlineSmall.merge(Display.copy(letterSpacing = (-0.25).sp)),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.15).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.copy(lineHeight = 24.sp),
        bodyMedium = t.bodyMedium.copy(lineHeight = 20.sp),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Numbers that line up in columns (stats, counters): the same digits always take the same width. */
val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

/**
 * Height of the glass navigation bar the main screens scroll under: their lists add it to their
 * bottom padding, so the last card clears the bar while the content still shows through it.
 */
val LocalBottomBarInset = compositionLocalOf { 0.dp }

/** Whether the app is drawn dark right now (the chosen theme, not only the system's). */
val LocalAppDark = staticCompositionLocalOf { false }

/** Colori "esito positivo" (Material 3 non ne ha uno): sempre accompagnati da icona e testo. */
object Tones {
    @Composable fun successContainer() = if (LocalAppDark.current) Color(0xFF1F4D2E) else Color(0xFFDDF5E3)
    @Composable fun onSuccessContainer() = if (LocalAppDark.current) Color(0xFFB9EDC6) else Color(0xFF0F5132)
}

/** How the app chooses light or dark: the system's choice, or always one of the two. */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK;
    companion object { fun of(name: String) = entries.firstOrNull { it.name.equals(name, true) } ?: SYSTEM }
}

/** Set by the host app at start: wraps every screen in the host's own theme, so these screens look like the rest of it. */
object HostTheme {
    @Volatile var wrap: (@Composable (content: @Composable () -> Unit) -> Unit)? = null
}

/** True on phones that can hand out Material You colours (Android 12 and newer). */
val dynamicColorAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun AppTheme(mode: ThemeMode = ThemeMode.SYSTEM, dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    HostTheme.wrap?.let { host ->
        host {
            val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
            CompositionLocalProvider(LocalAppDark provides dark, content = content)
        }
        return
    }
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val ctx = LocalContext.current
    val scheme = when {
        dynamicColor && dynamicColorAvailable -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkScheme
        else -> LightScheme
    }
    CompositionLocalProvider(LocalAppDark provides dark) {
        MaterialTheme(colorScheme = scheme, shapes = AppShapes, typography = AppTypography, content = content)
    }
}
