package com.xlollx.songport.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R

/**
 * The colours a sync card can wear, so someone with many syncs tells them apart at a glance.
 * Stored by key (SyncJob.color), never by value: the shade follows light and dark mode.
 */
enum class JobColor(val key: String, @StringRes val label: Int, val light: Color, val dark: Color) {
    RED("red", R.string.th_color_red, Color(0xFFE5484D), Color(0xFFFF7A7A)),
    ORANGE("orange", R.string.th_color_orange, Color(0xFFF76B15), Color(0xFFFF9A5C)),
    YELLOW("yellow", R.string.th_color_yellow, Color(0xFFD69E00), Color(0xFFFFCB47)),
    GREEN("green", R.string.th_color_green, Color(0xFF2F9E62), Color(0xFF5BD18F)),
    TEAL("teal", R.string.th_color_teal, Color(0xFF12A594), Color(0xFF3DD6C3)),
    BLUE("blue", R.string.th_color_blue, Color(0xFF0B84E8), Color(0xFF63B3FF)),
    PURPLE("purple", R.string.th_color_purple, Color(0xFF8E4EC6), Color(0xFFB98AF0)),
    PINK("pink", R.string.th_color_pink, Color(0xFFD6409F), Color(0xFFF57CC6));

    companion object {
        fun of(key: String?): JobColor? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A colour of the user's own, stored as "#RRGGBB" in the same field as the keys: an older Trackhop
 * reads it as no colour (the default look) and keeps it as it is.
 */
object CustomColor {
    fun parse(key: String?): Color? {
        val hex = key?.trim()?.takeIf { it.length == 7 && it[0] == '#' } ?: return null
        val v = hex.substring(1).toLongOrNull(16) ?: return null
        return Color(0xFF000000 or v)
    }

    fun key(c: Color): String = "#%06X".format(c.toArgbInt() and 0xFFFFFF)

    private fun Color.toArgbInt(): Int = ((alpha * 255).toInt() shl 24) or ((red * 255 + 0.5f).toInt() shl 16) or ((green * 255 + 0.5f).toInt() shl 8) or (blue * 255 + 0.5f).toInt()

    /** Relative luminance (WCAG), 0 black to 1 white. */
    fun luminance(c: Color): Float {
        fun ch(v: Float) = if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        return 0.2126f * ch(c.red) + 0.7152f * ch(c.green) + 0.0722f * ch(c.blue)
    }

    /** The colour for dark mode: lifted towards white until it shows on a dark surface. */
    fun forDark(c: Color): Color = tone(c, dark = true)

    /**
     * The colour a card wears for a colour of the user's own: its hue, brought into the range of the
     * nine ready colours, so a very dark or very pale choice (#000460, #EBECF5) still gives a card
     * that reads as that colour rather than a dull grey. A grey stays a grey, at a visible lightness.
     */
    fun tone(c: Color, dark: Boolean): Color {
        val (h, s, l) = hsl(c)
        // Grey by how far apart its channels are (a pale lavender grey has a high HSL saturation).
        val grey = maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue) < 0.08f
        val sat = if (grey) 0f else s.coerceIn(0.45f, 0.85f)
        val light = if (dark) l.coerceIn(0.62f, 0.76f) else l.coerceIn(0.40f, 0.56f)
        return fromHsl(h, sat, light)
    }

    private fun hsl(c: Color): Triple<Float, Float, Float> {
        val r = c.red; val g = c.green; val b = c.blue
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        val l = (max + min) / 2f
        if (max == min) return Triple(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        val h = when (max) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f))
            g -> ((b - r) / d + 2f)
            else -> ((r - g) / d + 4f)
        } * 60f
        return Triple(h, s, l)
    }

    private fun fromHsl(h: Float, s: Float, l: Float): Color {
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f); h < 120f -> Triple(x, c, 0f); h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c); h < 300f -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f))
    }
}

/** The colour itself in the current mode, or null for the default look. */
@Composable
fun jobAccent(key: String?): Color? {
    val dark = LocalAppDark.current
    JobColor.of(key)?.let { return if (dark) it.dark else it.light }
    return CustomColor.parse(key)?.let { CustomColor.tone(it, dark) }
}

/** A card's background in that colour: the usual surface, tinted just enough to tell. */
@Composable
fun jobContainer(key: String?): Color {
    val base = MaterialTheme.colorScheme.surfaceContainerLow
    val accent = jobAccent(key) ?: return base
    return lerp(base, accent, if (LocalAppDark.current) 0.20f else 0.13f)
}

/**
 * A card's menu in the card's colour: the menu surface, tinted a little more than the card so it
 * stands out above it. The plain menu surface for a sync with no colour. A plain mix, no blend mode.
 */
@Composable
fun jobMenuContainer(key: String?): Color {
    val base = MaterialTheme.colorScheme.surfaceContainer
    val accent = jobAccent(key) ?: return base
    return lerp(base, accent, if (LocalAppDark.current) 0.24f else 0.16f)
}

/** The sync's colour for its controls (figures, run button): a gradient of it, or null for the default look. */
@Composable
fun jobControlBrush(key: String?): Brush? {
    val accent = jobAccent(key) ?: return null
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val dark = LocalAppDark.current
    return Brush.linearGradient(listOf(lerp(base, accent, if (dark) 0.45f else 0.34f), lerp(base, accent, if (dark) 0.16f else 0.10f)))
}

/** Text and icons in the sync's colour, deep enough to read on its gradient. */
@Composable
fun jobInk(key: String?): Color? {
    val accent = jobAccent(key) ?: return null
    val dark = LocalAppDark.current
    if (JobColor.of(key) != null) return if (dark) accent else lerp(accent, Color.Black, 0.3f)
    // A colour of the user's own can be anything, a pale yellow too: moved until the text reads.
    var x = if (dark) accent else lerp(accent, Color.Black, 0.3f)
    var i = 0
    if (dark) while (CustomColor.luminance(x) < 0.35f && i++ < 12) x = lerp(x, Color.White, 0.15f)
    else while (CustomColor.luminance(x) > 0.18f && i++ < 12) x = lerp(x, Color.Black, 0.15f)
    return x
}

/** One dot per colour plus the default, the chosen one ticked. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun JobColorPicker(selected: String?, onSelect: (String?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Swatch(MaterialTheme.colorScheme.surfaceContainerHighest, stringResource(R.string.th_job_color_default), selected == null) { onSelect(null) }
        JobColor.entries.forEach { c ->
            Swatch(if (LocalAppDark.current) c.dark else c.light, stringResource(c.label), selected == c.key) { onSelect(c.key) }
        }
        // Last: any colour, from a picker. Shows the chosen one when it is a colour of the user's own.
        val own = CustomColor.parse(selected)
        var open by remember { mutableStateOf(false) }
        val ownName = name(own)
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(own?.let { Brush.linearGradient(listOf(it, it)) } ?: Brush.sweepGradient(RAINBOW))
                .border(if (own != null) 3.dp else 1.dp, if (own != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .selectable(selected = own != null, role = Role.RadioButton, onClick = { open = true })
                .semantics { contentDescription = ownName },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (own != null) Icons.Filled.Check else Icons.Filled.Add, null, Modifier.size(20.dp),
                tint = if (own == null || own.luminanceIsDark()) Color.White else Color.Black)
        }
        if (open) CustomColorDialog(own ?: JobColor.BLUE.light, onDismiss = { open = false }) { open = false; onSelect(CustomColor.key(it)) }
    }
}

private val RAINBOW = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

@Composable
private fun name(own: Color?): String = stringResource(R.string.th_job_color_custom) + (own?.let { " " + CustomColor.key(it) } ?: "")

/**
 * Any colour: a square for saturation (across) and brightness (down) with a draggable point, a hue
 * bar under it, the preview on a card and the HEX code, which can also be typed.
 */
@Composable
fun CustomColorDialog(initial: Color, onDismiss: () -> Unit, onPick: (Color) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(android.graphics.Color.rgb((initial.red * 255).toInt(), (initial.green * 255).toInt(), (initial.blue * 255).toInt()), it) } }
    var hue by remember { mutableStateOf(hsv[0]) }
    var sat by remember { mutableStateOf(hsv[1]) }
    var value by remember { mutableStateOf(hsv[2]) }
    val color = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
    var hex by remember { mutableStateOf(CustomColor.key(color)) }
    // Typing a code moves the square and the bar; moving them rewrites the code.
    fun setFrom(c: Color) {
        val out = FloatArray(3)
        android.graphics.Color.colorToHSV(android.graphics.Color.rgb((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()), out)
        hue = out[0]; sat = out[1]; value = out[2]
    }
    val pure = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    val svName = stringResource(R.string.th_job_color_shade)
    val hueName = stringResource(R.string.th_job_color_hue)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.th_job_color_custom)) },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Saturation left to right, brightness top to bottom.
                androidx.compose.foundation.Canvas(
                    Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                fun at(o: Offset) { sat = (o.x / size.width).coerceIn(0f, 1f); value = 1f - (o.y / size.height).coerceIn(0f, 1f); hex = CustomColor.key(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))) }
                                at(down.position)
                                drag(down.id) { at(it.position); it.consume() }
                            }
                        }
                        .semantics { contentDescription = svName },
                ) {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val c = Offset(sat * size.width, (1f - value) * size.height)
                    drawCircle(Color.White, 11.dp.toPx(), c, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
                    drawCircle(Color.Black.copy(alpha = 0.4f), 13.dp.toPx(), c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                }
                // Hue.
                androidx.compose.foundation.Canvas(
                    Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(14.dp))
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                fun at(o: Offset) { hue = (o.x / size.width).coerceIn(0f, 1f) * 360f; hex = CustomColor.key(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))) }
                                at(down.position)
                                drag(down.id) { at(it.position); it.consume() }
                            }
                        }
                        .semantics { contentDescription = hueName },
                ) {
                    drawRect(Brush.horizontalGradient(RAINBOW))
                    val x = hue / 360f * size.width
                    drawCircle(Color.White, size.height / 2 - 2.dp.toPx(), Offset(x, size.height / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(color).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)))
                    androidx.compose.material3.OutlinedTextField(
                        value = hex,
                        onValueChange = { t ->
                            val v = ("#" + t.removePrefix("#").filter { it.isLetterOrDigit() }.take(6)).uppercase()
                            hex = v
                            CustomColor.parse(v)?.let { setFrom(it) }
                        },
                        label = { Text("HEX") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                }
                // How a card will wear it, in this theme: the same tint and text colour as the list.
                val key = CustomColor.key(color)
                Box(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(jobContainer(key))
                        .border(1.dp, jobAccent(key) ?: MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(stringResource(R.string.th_job_color_preview), Modifier.padding(horizontal = 14.dp), color = jobInk(key) ?: MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color) }) { Text(stringResource(R.string.th_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.th_cancel)) } },
    )
}

@Composable
private fun Swatch(color: Color, name: String, chosen: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(color)
            .border(if (chosen) 3.dp else 1.dp, if (chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        if (chosen) Icon(Icons.Filled.Check, null, Modifier.size(20.dp), tint = if (color.luminanceIsDark()) Color.White else Color.Black)
    }
}

private fun Color.luminanceIsDark(): Boolean = (0.299f * red + 0.587f * green + 0.114f * blue) < 0.6f

/** The picker on its own, from a card's menu. */
@Composable
fun JobColorDialog(selected: String?, onDismiss: () -> Unit, onSelect: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.th_job_color)) },
        text = { JobColorPicker(selected) { onSelect(it); onDismiss() } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.th_cancel)) } },
    )
}
