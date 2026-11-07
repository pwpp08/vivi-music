package com.xlollx.songport.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xlollx.songport.providers.*

/**
 * The services' marks, drawn as a white glyph on a circle in the service's colour.
 *
 * Glyph paths are in [ServiceMarks] (Simple Icons, CC0), shared with the server's web page. The marks
 * are the services' trademarks and are used only to show which service a playlist or an account belongs to.
 * Services Simple Icons does not carry (Amazon Music, LiveOne, Anghami, Qobuz, ListenBrainz) keep their initial;
 * personal servers without a mark of their own (Subsonic/Navidrome) and files get a generic icon.
 */
object ServiceLogos {
    private val cache = HashMap<String, ImageVector>()

    /** The glyph's SVG path (24×24 viewport) for a service id, or null: the shareable cards draw it on a Canvas. */
    fun pathData(serviceId: String?): String? = ServiceMarks.pathData(serviceId)

    /** The brand glyph for a service id (bridge and web variants share their brand's), or null. */
    fun glyph(serviceId: String?): ImageVector? {
        val path = pathData(serviceId) ?: return null
        return synchronized(cache) {
            cache.getOrPut(serviceId!!) {
                ImageVector.Builder(serviceId, 24.dp, 24.dp, 24f, 24f)
                    .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White))
                    .build()
            }
        }
    }
}

/**
 * Shazam's mark (Simple Icons, CC0): on a Shazam sync's card and in the editor's "Shazam" source,
 * as the sparkles mark a playlist written by the user's AI.
 */
val ShazamGlyph: ImageVector by lazy {
    ImageVector.Builder("shazam", 24.dp, 24.dp, 24f, 24f)
        .addPath(pathData = addPathNodes(ServiceMarks.SHAZAM), fill = SolidColor(Color.White))
        .build()
}

/** Shazam's blue, a little lighter on a dark theme so the mark reads on dark cards. */
@Composable
fun shazamTint(): Color = if (LocalAppDark.current) Color(0xFF4DA8FF) else Color(ServiceMarks.SHAZAM_COLOR)

/**
 * The service's mark: its glyph in white on a circle of its colour; a generic icon for files and
 * personal servers without a mark; the initial otherwise. TalkBack reads the service's name.
 */
@Composable
fun ServiceLogo(p: MusicProvider?, size: Dp = 36.dp, modifier: Modifier = Modifier) =
    ServiceLogo(p?.serviceId, p?.brandColor, p?.displayName.orEmpty(), size, modifier)

/** The same mark from a service's id, colour and name: for a service this build does not carry. */
@Composable
fun ServiceLogo(serviceId: String?, brandColor: Long?, name: String, size: Dp = 36.dp, modifier: Modifier = Modifier) {
    val bg = if (brandColor != null) Color(brandColor) else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (bg.luminance() > 0.45f) Color(0xFF1C1B22) else Color.White
    // A near-black mark (TIDAL) on a dark surface needs an edge to be seen.
    val edge = bg.luminance() < 0.03f
    Box(
        modifier.size(size).clip(CircleShape).background(bg)
            .then(if (edge) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape) else Modifier)
            .semantics { if (name.isNotEmpty()) contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        val glyph = ServiceLogos.glyph(serviceId)
        val generic = when (serviceId) { "csv" -> Icons.Filled.Folder; "subsonic" -> Icons.Filled.Dns; "charts" -> Icons.Filled.BarChart; else -> null }
        when {
            glyph != null -> Image(glyph, null, Modifier.size(size * 0.56f), colorFilter = ColorFilter.tint(fg))
            generic != null -> Icon(generic, null, tint = fg, modifier = Modifier.size(size * 0.56f))
            else -> Text(
                name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                color = fg,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.46f).sp,
            )
        }
    }
}

/**
 * One choice in [ServiceTiles]: a service, or an extra entry such as "Link" with its own icon. With
 * a [brandColor] the icon is a brand's round mark (Shazam), drawn whole in that colour.
 */
class ServiceTile(
    val key: String, val label: String, val provider: MusicProvider? = null, val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val detail: String? = null, val brandColor: Long? = null,
)

/**
 * The service picker: a horizontal row of tiles, the mark large enough to recognise at a glance
 * and the name under it; the selected tile takes the tonal container and a check. Scrolls when
 * the services do not fit, instead of wrapping into uneven rows of chips.
 *
 * When they do not fit, it shows that the row goes on: the tiles are sized from the width so the
 * last one in view is cut about a third of the way ([tileLayout]), a slim bar under the row tells
 * where the view is and how much of the row it shows, and TalkBack hears "N services, scroll for
 * more". The selected tile is brought into view, so a sync on the fifth account opens with it shown.
 */
@Composable
fun ServiceTiles(tiles: List<ServiceTile>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val layout = tileLayout(maxWidth, tiles.size)
        val state = androidx.compose.foundation.lazy.rememberLazyListState()
        val stepPx = with(density) { (layout.width + TILE_GAP).toPx() }
        val tilePx = with(density) { layout.width.toPx() }
        val viewPx = with(density) { maxWidth.toPx() }
        val contentPx = tiles.size * stepPx - with(density) { TILE_GAP.toPx() }
        val overflows = contentPx > viewPx + 1f
        // The selected tile wholly in view: at once when the picker opens, smoothly after a tap on a cut one.
        val index = tiles.indexOfFirst { it.key == selected }
        var placed by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(index, layout.width, overflows) {
            if (index < 0 || !overflows) { placed = true; return@LaunchedEffect }
            val pos = state.firstVisibleItemIndex * stepPx + state.firstVisibleItemScrollOffset
            val start = index * stepPx
            val target = when {
                start < pos -> start
                start + tilePx > pos + viewPx -> start + tilePx - viewPx
                else -> null
            }
            if (target != null) {
                val first = (target / stepPx).toInt().coerceIn(0, tiles.size - 1)
                val offset = (target - first * stepPx).toInt().coerceAtLeast(0)
                if (placed) state.animateScrollToItem(first, offset) else state.scrollToItem(first, offset)
            }
            placed = true
        }
        val hint = androidx.compose.ui.res.pluralStringResource(com.xlollx.songport.R.plurals.th_service_tiles_scroll, tiles.size, tiles.size)
        androidx.compose.foundation.layout.Column {
            androidx.compose.foundation.lazy.LazyRow(
                Modifier.fillMaxWidth().then(if (overflows) Modifier.semantics { contentDescription = hint } else Modifier),
                state = state,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(TILE_GAP),
            ) {
                items(tiles.size, key = { tiles[it].key }) { i -> Tile(tiles[i], tiles[i].key == selected, layout.width, onSelect) }
            }
            if (overflows) {
                // Where the view is along the row (the thumb's place) and how much of it shows (its length).
                val track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                val thumb = MaterialTheme.colorScheme.primary
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                Box(
                    Modifier.align(Alignment.CenterHorizontally).fillMaxWidth(0.4f).height(4.dp).clip(CircleShape).background(track)
                        .drawBehind {
                            val maxScroll = (contentPx - viewPx).coerceAtLeast(1f)
                            val pos = (state.firstVisibleItemIndex * stepPx + state.firstVisibleItemScrollOffset).coerceIn(0f, maxScroll)
                            val len = (size.width * viewPx / contentPx).coerceIn(size.height * 3, size.width)
                            val x = (size.width - len) * pos / maxScroll
                            drawRoundRect(thumb, topLeft = androidx.compose.ui.geometry.Offset(x, 0f), size = androidx.compose.ui.geometry.Size(len, size.height),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
                        },
                )
            }
        }
    }
}

private val TILE_GAP = 8.dp
private val TILE_WIDTH = 96.dp
private val TILE_MIN = 80.dp
private val TILE_MAX = 124.dp
/** The share of the last tile in view left showing when the row goes on. */
private const val PEEK = 0.35f

internal class TileLayout(val width: Dp, val inView: Float)

/**
 * The tiles' width for a row [available] wide: the usual width when all [count] fit; otherwise the
 * width closest to it that leaves a whole number of tiles in view plus [PEEK] of the next, so the
 * row never ends on a clean edge that hides the rest.
 */
internal fun tileLayout(available: Dp, count: Int): TileLayout {
    val gap = TILE_GAP.value
    val all = count * TILE_WIDTH.value + (count - 1).coerceAtLeast(0) * gap
    if (count <= 1 || all <= available.value) return TileLayout(TILE_WIDTH, count.toFloat())
    val best = (1 until count).map { whole ->
        // whole tiles and their gaps, then a gap and the peek of the next.
        whole to (available.value - whole * gap) / (whole + PEEK)
    }.filter { (_, w) -> w > 0f }.minByOrNull { (_, w) -> kotlin.math.abs(w - TILE_WIDTH.value) }
        ?: return TileLayout(TILE_WIDTH, available.value / (TILE_WIDTH.value + gap))
    val w = best.second.coerceIn(TILE_MIN.value, TILE_MAX.value)
    return TileLayout(w.dp, best.first + PEEK)
}

@Composable
private fun Tile(t: ServiceTile, on: Boolean, width: Dp, onSelect: (String) -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    androidx.compose.material3.Surface(
        onClick = { if (!on) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); onSelect(t.key) },
        shape = shape,
        color = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (on) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.semantics { this.selected = on }.size(width = width, height = 104.dp),
    ) {
        Box {
            androidx.compose.foundation.layout.Column(
                Modifier.align(Alignment.Center).padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (t.provider != null) ServiceLogo(t.provider, 40.dp)
                else if (t.icon != null && t.brandColor != null) Icon(t.icon, null, tint = Color(t.brandColor), modifier = Modifier.size(40.dp))
                else Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    t.icon?.let { Icon(it, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)) }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                Text(
                    t.label, style = MaterialTheme.typography.labelMedium, maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                )
                t.detail?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (on) Icon(
                Icons.Filled.CheckCircle, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
            )
        }
    }
}

/** The usual service tiles for a list of connected providers. */
@Composable
fun ServicePicker(providers: List<MusicProvider>, selectedId: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    ServiceTiles(providers.map { ServiceTile(it.id, it.label(ctx), it) }, selectedId, onSelect, modifier)
}

