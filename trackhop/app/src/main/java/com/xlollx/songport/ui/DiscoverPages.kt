@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.xlollx.songport.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.ai.AiClient
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.entries
import com.xlollx.songport.providers.isConnected
import com.xlollx.songport.providers.libraryEntries
import com.xlollx.songport.providers.playlistInfo
import com.xlollx.songport.providers.playlists
import com.xlollx.songport.providers.tracks
import com.xlollx.songport.sync.LibraryOverlap
import com.xlollx.songport.sync.ServiceSearch
import com.xlollx.songport.sync.UniversalLink
import com.xlollx.songport.sync.Vanished
import com.xlollx.songport.sync.Wrapped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The user's music on one service as Discover reads it: liked songs and their own playlists, read
 * from the service like a backup reads them and kept in memory for a quarter of an hour, so the
 * Wrapped and "Two subscriptions?" do not read everything twice. Nothing is written anywhere.
 */
internal object LibraryReader {
    /** Playlists read per service at most: enough for a portrait, without hours of reading. */
    private const val MAX_PLAYLISTS = 60
    private const val KEEP_MS = 15 * 60_000L
    private val cache = HashMap<String, Pair<Long, List<Track>>>()

    /** The connected music services (not the scrobblers, not files). */
    fun services(ctx: Context): List<MusicProvider> =
        Providers.connectors().filter { it.requiresAuth && it.isConnected(ctx) && it.serviceId != "lastfm" && it.serviceId != "listenbrainz" }

    /** [onProgress] gets (playlists read, playlists to read). A playlist that cannot be read is skipped. */
    suspend fun read(ctx: Context, p: MusicProvider, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<Track> {
        synchronized(cache) { cache[p.id]?.takeIf { System.currentTimeMillis() - it.first < KEEP_MS }?.let { return it.second } }
        val liked = p.libraryEntries(ctx, asTarget = false).filter { it.id == MusicProvider.LIKED_ID }
        val lists = (liked + p.playlists(ctx).filter { it.ownedByMe }).take(MAX_PLAYLISTS)
        val out = ArrayList<Track>()
        lists.forEachIndexed { i, pl ->
            onProgress(i, lists.size)
            try { out += p.tracks(ctx, pl.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        onProgress(lists.size, lists.size)
        synchronized(cache) { cache[p.id] = System.currentTimeMillis() to out }
        return out
    }
}

private fun locale(ctx: Context): Locale = ctx.resources.configuration.locales[0]

private fun errorText(ctx: Context, e: Exception) = e.message ?: ctx.getString(R.string.th_error_generic)

@Composable
private fun Intro(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Busy(text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun NoServices(text: String, onGoToAccounts: () -> Unit) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
    Button(onClick = onGoToAccounts, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Filled.AccountCircle, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.th_tools_go_accounts))
    }
}

// ---------------------------------------------------------------------------------------- Wrapped

/** A connected service as [Wrapped] knows it. */
private fun connector(p: MusicProvider) = Wrapped.Connector(p.id, p.serviceId, p.displayName, p.familyName, p.brandColor)

private fun songLine(s: Wrapped.Song, withYear: Boolean) = buildString {
    append(s.title)
    if (s.artist.isNotBlank()) append(" – ").append(s.artist)
    if (withYear && s.year > 0) append(" (").append(s.year).append(')')
}

/** The headline of [w]: the musical age, or the songs when the services did not say when they came out. */
private fun wrappedHeadline(ctx: Context, w: Wrapped.Saved): String =
    w.age?.let { ctx.getString(R.string.th_discover_wrapped_age, it) }
        ?: ctx.resources.getQuantityString(R.plurals.th_card_tracks, w.trackCount, w.trackCount)
            .replace(w.trackCount.toString(), NumberFormat.getIntegerInstance(locale(ctx)).format(w.trackCount))

/** [w] as the card and the page show it, in the phone's language. */
internal fun wrappedCard(ctx: Context, w: Wrapped.Saved): Cards.WrappedCard {
    val nf = NumberFormat.getIntegerInstance(locale(ctx))
    val res = ctx.resources
    val tiles = buildList {
        add(nf.format(w.trackCount) to ctx.getString(R.string.th_discover_wrapped_tracks))
        if (w.artistCount > 0) add(nf.format(w.artistCount) to ctx.getString(R.string.th_discover_wrapped_artists))
        val hours = w.durationMs / 3_600_000
        when {
            hours >= 1 -> add(nf.format(hours) to ctx.getString(R.string.th_discover_wrapped_hours))
            w.durationMs >= 60_000 -> add(nf.format(w.durationMs / 60_000) to ctx.getString(R.string.th_discover_wrapped_minutes))
        }
    }
    val facts = buildList {
        w.oldest?.let { add(ctx.getString(R.string.th_discover_wrapped_oldest) to songLine(it, withYear = true)) }
        w.newest?.takeIf { n -> w.oldest?.let { it.title == n.title && it.artist == n.artist } != true }
            ?.let { add(ctx.getString(R.string.th_discover_wrapped_newest) to songLine(it, withYear = false)) }
    }
    return Cards.WrappedCard(
        eyebrow = ctx.getString(R.string.th_discover_wrapped_eyebrow, w.year),
        headline = wrappedHeadline(ctx, w),
        subline = w.bornIn?.let { ctx.getString(R.string.th_discover_wrapped_born, it) },
        artistsTitle = ctx.getString(R.string.th_discover_wrapped_top_artists),
        artists = w.topArtists.map { it.name to res.getQuantityString(R.plurals.th_card_tracks, it.tracks, it.tracks) },
        decadesTitle = ctx.getString(R.string.th_discover_wrapped_decades),
        decades = w.decades.map { ctx.getString(R.string.th_discover_wrapped_decade_label, it.decade) to it.percent },
        tiles = tiles,
        facts = facts,
        servicesTitle = ctx.getString(R.string.th_discover_wrapped_services),
        services = w.services.map { Cards.Mark(it.name, it.color.toInt(), it.serviceId) },
    )
}

/**
 * "Your Wrapped, whenever you want": the figures of [Wrapped] on a card for the stories. The page
 * opens on the last one made (kept in the store with the others, the newest first), lets the user
 * choose which connected services are read for a new one, and lists the older ones to see, share
 * or delete.
 */
@Composable
internal fun WrappedPage(snackbar: SnackbarHostState, onGoToAccounts: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { Store.get(ctx) }
    val data by store.state.collectAsState()
    val history = data.wrapped
    val services = remember { LibraryReader.services(ctx) }
    val labels = remember(services) { Wrapped.chipLabels(services.map(::connector)) }
    val skipped = data.settings.wrappedSkipped
    val chosenIds = Wrapped.chosen(services.map { it.id }, skipped).toSet()
    // The reading lives in [WrappedRun], not in this page: leaving the page (or the tab) while it
    // reads does not stop it, and the new Wrapped is there when the page is opened again.
    val busy = WrappedRun.busy
    val reading = WrappedRun.reading
    val readingFrom = WrappedRun.readingFrom
    val current = WrappedRun.current
    LaunchedEffect(WrappedRun.error) { WrappedRun.error?.let { WrappedRun.error = null; snackbar.showSnackbar(it) } }
    LaunchedEffect(WrappedRun.empty) { if (WrappedRun.empty) { WrappedRun.empty = false; snackbar.showSnackbar(ctx.getString(R.string.th_discover_wrapped_empty)) } }
    var shownId by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = history.firstOrNull { it.id == shownId } ?: history.firstOrNull()
    val card = remember(shown) { shown?.let { wrappedCard(ctx, it) } }
    var bitmaps by remember { mutableStateOf<Map<Cards.Format, Bitmap>?>(null) }
    // The card's art: a cover drawn on the phone from the Wrapped's covers (loaded again from their
    // addresses), in the style chosen; null until the user picks one = mosaic with four covers or
    // more, else the gradient. "Another version" turns the variant.
    var covers by remember { mutableStateOf<List<Bitmap>?>(null) }
    var style by remember(shown?.id) { mutableStateOf<GeneratedCovers.Style?>(null) }
    var variant by remember(shown?.id) { mutableStateOf(0) }
    var rendering by remember { mutableStateOf(false) }
    LaunchedEffect(shown?.id) {
        bitmaps = null; covers = null
        val urls = shown?.covers ?: return@LaunchedEffect
        covers = kotlinx.coroutines.coroutineScope { urls.map { async { Cards.bitmap(it, 400) } }.mapNotNull { it.await() } }
    }
    val artStyle = style ?: if ((covers?.size ?: 0) >= 4) GeneratedCovers.Style.MOSAIC else GeneratedCovers.Style.GRADIENT
    LaunchedEffect(card, covers, artStyle, variant) {
        val c = card ?: return@LaunchedEffect
        val w = shown ?: return@LaunchedEffect
        val got = covers ?: return@LaunchedEffect
        rendering = true
        val art = Cards.WrappedArt(artStyle, GeneratedCovers.Input(c.eyebrow, w.topArtists.map { it.name }, got), variant)
        bitmaps = withContext(Dispatchers.Default) { Cards.Format.entries.associateWith { Cards.wrapped(ctx, c, it, art) } }
        rendering = false
    }

    fun run() {
        WrappedRun.start(ctx.applicationContext, services.filter { it.id in chosenIds }) { saved ->
            store.update { d -> d.copy(wrapped = Wrapped.remember(d.wrapped, saved)) }
            shownId = saved.id
        }
    }

    fun delete(w: Wrapped.Saved) {
        store.update { d -> d.copy(wrapped = d.wrapped.filter { it.id != w.id }) }
        if (shownId == w.id) shownId = null
        scope.launch {
            val r = snackbar.showSnackbar(ctx.getString(R.string.th_discover_wrapped_deleted), actionLabel = ctx.getString(R.string.th_unplayable_undo), duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) store.update { d -> d.copy(wrapped = Wrapped.remember(d.wrapped, w)) }
        }
    }

    Intro(stringResource(R.string.th_discover_wrapped_intro))
    if (services.isEmpty() && history.isEmpty()) { NoServices(stringResource(R.string.th_discover_no_services), onGoToAccounts); return }
    val df = remember { DateFormat.getDateInstance(DateFormat.MEDIUM, locale(ctx)) }
    if (busy) WrappedLoading(readingFrom, reading, current, WrappedRun.coverSearch)
    else if (shown != null && card != null) {
        Text(
            stringResource(R.string.th_discover_wrapped_made_on, df.format(Date(shown.madeAt))),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WrappedSummary(card, shown)
        // The card's art, as the poster's drawn covers.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.th_card_cover_title), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PosterArt.entries.filter { it != PosterArt.AI }.forEach { a ->
                    val st = a.style ?: return@forEach
                    FilterChip(selected = st == artStyle, enabled = covers != null, onClick = { style = st }, label = { Text(stringResource(a.label)) })
                }
            }
        }
        CardActions(
            bitmaps, "vivi-wrapped", busy = bitmaps == null || rendering, onRender = null, renderLabel = "",
            previewDescription = listOf(card.headline, card.subline.orEmpty()).joinToString(". "),
            formats = listOf(Cards.Format.STORY, Cards.Format.SQUARE),
        )
        // Always there (greyed while the card is drawn): appearing late, it pushed what is below it down
        // under a finger already on its way (the history's delete button).
        OutlinedButton(enabled = bitmaps != null && !rendering, onClick = { variant++ }) { Text(stringResource(R.string.th_card_cover_another)) }
    }

    // A new one: the services to read, chosen every time (the last choice comes back ticked).
    if (services.isEmpty()) NoServices(stringResource(R.string.th_discover_no_services), onGoToAccounts)
    else {
        if (shown != null && !busy) HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text(stringResource(R.string.th_discover_wrapped_pick), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.th_discover_wrapped_pick_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            services.forEach { p ->
                val on = p.id in chosenIds
                FilterChip(
                    selected = on, enabled = !busy,
                    onClick = {
                        if (on && chosenIds.size == 1) scope.launch { snackbar.showSnackbar(ctx.getString(R.string.th_discover_wrapped_keep_one)) }
                        else store.updateSettings { st ->
                            // Ids of services no longer connected stay as they were; the others follow the chips.
                            val others = st.wrappedSkipped - services.map { it.id }.toSet()
                            val off = services.map { it.id }.filter { it !in chosenIds }.toSet()
                            st.copy(wrappedSkipped = others + if (on) off + p.id else off - p.id)
                        }
                    },
                    label = { Text(labels[p.id] ?: p.displayName) },
                    leadingIcon = { ServiceLogo(p, size = 20.dp) },
                )
            }
        }
        Button(enabled = !busy, onClick = { run() }, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(stringResource(if (history.isEmpty()) R.string.th_discover_wrapped_button else R.string.th_discover_wrapped_new))
        }
    }

    if (history.isNotEmpty()) {
        Text(stringResource(R.string.th_discover_wrapped_history), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                history.forEach { w ->
                    val selected = !busy && w.id == shown?.id
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                            .selectable(selected = selected, enabled = !busy, onClick = { shownId = w.id })
                            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(df.format(Date(w.madeAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(wrappedHeadline(ctx, w), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            w.topArtist?.let { Text("♪ $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            if (w.services.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                w.services.forEach { sv ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        ServiceLogo(sv.serviceId, sv.color, sv.name, 16.dp); Spacer(Modifier.width(4.dp))
                                        Text(sv.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        IconButton(onClick = { delete(w) }, enabled = !busy) {
                            Icon(Icons.Filled.Delete, stringResource(R.string.th_discover_wrapped_delete))
                        }
                    }
                }
            }
        }
    }
}

/** The Wrapped on the page: the same figures as the card, laid out for reading. */
@Composable
private fun WrappedSummary(card: Cards.WrappedCard, w: Wrapped.Saved) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(card.eyebrow.uppercase(), style = MaterialTheme.typography.labelMedium, color = muted)
                Text(card.headline, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                card.subline?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (w.age == null) Text(stringResource(R.string.th_discover_wrapped_no_age), style = MaterialTheme.typography.bodySmall, color = muted)
            }
            if (card.artists.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(card.artistsTitle, style = MaterialTheme.typography.titleSmall)
                card.artists.forEachIndexed { i, (name, count) ->
                    val style = if (i == 0) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", style = style, color = muted, modifier = Modifier.widthIn(min = 28.dp))
                        Text(name, style = style, fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Text(count, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1)
                    }
                }
            }
            if (card.decades.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(card.decadesTitle, style = MaterialTheme.typography.titleSmall)
                card.decades.forEach { (label, share) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.widthIn(min = 72.dp))
                        Spacer(Modifier.width(8.dp))
                        LinearProgressIndicator(progress = { share / 100f }, modifier = Modifier.weight(1f).height(8.dp), drawStopIndicator = {})
                        Spacer(Modifier.width(8.dp))
                        Text("$share%", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 44.dp))
                    }
                }
            }
            if (card.tiles.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                card.tiles.forEach { (value, label) ->
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(label, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            card.facts.forEach { (label, value) ->
                Column {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = muted)
                    Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            if (w.services.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(card.servicesTitle, style = MaterialTheme.typography.labelMedium, color = muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    w.services.forEach { sv ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ServiceLogo(sv.serviceId, sv.color, sv.name, 24.dp); Spacer(Modifier.width(6.dp))
                            Text(sv.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- Universal link

/** A playlist (or one of its songs) as a trackhop.org/open link that opens on anyone's service. */
@Composable
internal fun UniversalLinkPage(snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var pick by remember { mutableStateOf<CardPick?>(null) }
    var tracks by remember { mutableStateOf<List<Track>?>(null) }
    // One link, or several for a long playlist (each one gets through chat apps on its own).
    var built by remember { mutableStateOf<List<UniversalLink.Built>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(pick) {
        tracks = null; built = emptyList()
        val p = pick ?: return@LaunchedEffect
        busy = true
        try {
            val t = p.provider.tracks(ctx, p.playlist.id)
            tracks = t
            built = withContext(Dispatchers.Default) { UniversalLink.buildParts(p.playlist.name.ifBlank { ctx.getString(R.string.th_card_unnamed_playlist) }, t) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            snackbar.showSnackbar(errorText(ctx, e))
        }
        busy = false
    }

    Intro(stringResource(R.string.th_discover_link_intro))
    PlaylistSource(stringResource(R.string.th_editor_playlist), null, snackbar, memory = "link") { pick = it }
    if (busy) Busy(stringResource(R.string.th_loading))
    val parts = built
    val name = pick?.playlist?.name.orEmpty()
    if (parts.isNotEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (parts.size > 1) stringResource(R.string.th_discover_link_parts, parts.size)
                    else stringResource(R.string.th_discover_link_ready, parts[0].included),
                    style = MaterialTheme.typography.bodyMedium,
                )
                parts.forEach { b ->
                    if (parts.size > 1) Text(
                        stringResource(R.string.th_discover_link_part, b.part, b.parts, b.first, b.last),
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp),
                    )
                    val label = if (parts.size > 1) stringResource(R.string.th_discover_link_part_name, name, b.part, b.parts) else name
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = { shareText(ctx, ctx.getString(R.string.th_discover_link_message, label, b.url)) }) {
                            Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.th_discover_link_share))
                        }
                        OutlinedButton(onClick = {
                            clipboard.setText(AnnotatedString(b.url))
                            scope.launch { snackbar.showSnackbar(ctx.getString(R.string.th_discover_link_copied)) }
                        }) { Text(stringResource(R.string.th_discover_link_copy)) }
                    }
                }
            }
        }
    }
    val list = tracks.orEmpty().filter { it.kind.isEmpty() }
    if (list.isNotEmpty()) {
        Text(stringResource(R.string.th_discover_link_one_song), style = MaterialTheme.typography.titleSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                list.take(50).forEach { t ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artistLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { shareText(ctx, ctx.getString(R.string.th_discover_link_message, t.toString(), UniversalLink.buildTrack(t).url)) }) {
                            Icon(Icons.Filled.Share, stringResource(R.string.th_discover_link_share_song, t.title))
                        }
                    }
                }
                if (list.size > 50) Text(stringResource(R.string.th_preview_more, list.size - 50), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp, 6.dp))
            }
        }
    }
}

// --------------------------------------------------------------------------------- Vanished songs

/** A song gone from a playlist, with where it was and when it was last there. */
private class GoneRow(val track: Track, val where: String, val at: Long, val bySync: Boolean)

/**
 * "Vanished songs": what left the user's playlists, from what the phone already keeps: the dated
 * backup copies of each playlist ([Vanished]) and the tracks mirror syncs removed (their reports).
 * Each one can be searched on the big services' sites.
 */
@Composable
internal fun VanishedPage(onBackup: () -> Unit) {
    val ctx = LocalContext.current
    var rows by remember { mutableStateOf<List<GoneRow>?>(null) }
    LaunchedEffect(Unit) {
        rows = withContext(Dispatchers.IO) {
            val out = ArrayList<GoneRow>()
            val groups = runCatching { LocalFilesProvider.entries(ctx) }.getOrDefault(emptyList())
                .filter { it.service != null && it.stamped }.groupBy { it.base }
            for ((_, versions) in groups) {
                if (versions.size < 2) continue
                val copies = versions.mapNotNull { v -> runCatching { Vanished.Version(v.writtenAt, LocalFilesProvider.tracks(ctx, v.id)) }.getOrNull() }
                val first = versions.first()
                Vanished.between(first.title, first.service, copies).forEach { g ->
                    out += GoneRow(g.track, listOfNotNull(g.playlist, g.service).joinToString(" · "), g.lastSeen, bySync = false)
                }
            }
            // Mirror syncs' removals: the source lost those songs, so the sync took them off its target too.
            // Not those of a playlist that changes by itself (a daylist, Discover Weekly): there songs
            // come and go every day by design, nothing has vanished.
            val data = Store.get(ctx).data
            val rotating = data.jobs.filter { it.followsChanges }.map { it.id }.toSet()
            for (r in data.reports) if (r.jobId !in rotating) for (t in r.removedTracks) out += GoneRow(t, r.jobName, r.startedEpoch, bySync = true)
            out.distinctBy { Wrapped.key(it.track) + "|" + it.where }.sortedByDescending { it.at }.take(300)
        }
    }

    Intro(stringResource(R.string.th_discover_vanished_intro))
    val list = rows
    when {
        list == null -> Busy(stringResource(R.string.th_loading))
        list.isEmpty() -> {
            Text(stringResource(R.string.th_discover_vanished_none), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onBackup) { Text(stringResource(R.string.th_discover_vanished_backup)) }
        }
        else -> {
            Text(ctx.resources.getQuantityString(R.plurals.th_card_tracks, list.size, list.size), style = MaterialTheme.typography.titleSmall)
            val df = remember { DateFormat.getDateInstance(DateFormat.MEDIUM, locale(ctx)) }
            Card(Modifier.fillMaxWidth()) {
                Column {
                    list.forEachIndexed { i, g ->
                        if (i > 0) HorizontalDivider()
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(g.track.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(g.track.artistLine, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val date = df.format(Date(g.at))
                                Text(
                                    if (g.bySync) stringResource(R.string.th_discover_vanished_by_sync, g.where, date) else stringResource(R.string.th_discover_vanished_from, g.where, date),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            SearchOnMenu(g.track)
                        }
                    }
                }
            }
        }
    }
}

/** "Search on…": the track on one of the big services' search pages. */
@Composable
private fun SearchOnMenu(t: Track) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(stringResource(R.string.th_discover_search_on)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val q = ServiceSearch.query(t.title, t.artistLine)
            ServiceSearch.SITES.forEach { s ->
                DropdownMenuItem(text = { Text(s.name) }, onClick = { open = false; openUrl(ctx, s.url(q)) })
            }
        }
    }
}

// ---------------------------------------------------------------------------- Two subscriptions?

private fun pricePrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("discover_prices", Context.MODE_PRIVATE)

/**
 * "Two subscriptions?": how much of the music on two services overlaps ([LibraryOverlap]), and
 * what both cost at the prices the user types (kept on the phone, never ours). It measures; it
 * never says which one to keep.
 */
@Composable
internal fun SubscriptionsPage(snackbar: SnackbarHostState, onGoToAccounts: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val services = remember { LibraryReader.services(ctx) }
    if (services.size < 2) { Intro(stringResource(R.string.th_discover_subs_intro)); NoServices(stringResource(R.string.th_discover_subs_need_two), onGoToAccounts); return }
    var aId by rememberSaveable { mutableStateOf(services[0].id) }
    var bId by rememberSaveable { mutableStateOf(services[1].id) }
    val a = services.firstOrNull { it.id == aId } ?: services[0]
    val b = services.firstOrNull { it.id == bId } ?: services[1]
    var priceA by remember(a.serviceId) { mutableStateOf(pricePrefs(ctx).getString(a.serviceId, "").orEmpty()) }
    var priceB by remember(b.serviceId) { mutableStateOf(pricePrefs(ctx).getString(b.serviceId, "").orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Triple<MusicProvider, MusicProvider, LibraryOverlap.Result>?>(null) }

    fun run() {
        busy = true; result = null
        scope.launch {
            try {
                val ta = LibraryReader.read(ctx, a) { d, t -> step = ctx.getString(R.string.th_discover_reading, a.displayName, d, t) }
                val tb = LibraryReader.read(ctx, b) { d, t -> step = ctx.getString(R.string.th_discover_reading, b.displayName, d, t) }
                val store = Store.get(ctx)
                val r = withContext(Dispatchers.Default) { LibraryOverlap.compute(ta, tb) { t -> store.cachedMatch(a.id, t.id, b.id) } }
                result = Triple(a, b, r)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                snackbar.showSnackbar(errorText(ctx, e))
            }
            busy = false
        }
    }

    Intro(stringResource(R.string.th_discover_subs_intro))
    @Composable
    fun Side(label: String, selected: MusicProvider, onSelect: (String) -> Unit, price: String, onPrice: (String) -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                ServicePicker(services, selected.id, onSelect)
                OutlinedTextField(
                    value = price, onValueChange = { v -> onPrice(v); pricePrefs(ctx).edit().putString(selected.serviceId, v).apply() },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.th_discover_subs_price, selected.displayName)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        }
    }
    Side(stringResource(R.string.th_discover_subs_first), a, { aId = it; result = null }, priceA) { priceA = it }
    Side(stringResource(R.string.th_discover_subs_second), b, { bId = it; result = null }, priceB) { priceB = it }
    val same = a.id == b.id
    if (same) Text(stringResource(R.string.th_discover_subs_same), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(enabled = !busy && !same, onClick = { run() }) { Text(stringResource(R.string.th_discover_subs_run)) }
        if (busy) { Spacer(Modifier.width(12.dp)); Busy(step) }
    }
    result?.let { (pa, pb, r) ->
        val nf = remember { NumberFormat.getIntegerInstance(locale(ctx)) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${r.percent}%", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.th_discover_subs_overlap), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.th_discover_subs_counts, nf.format(r.common.size), nf.format(r.onlyA.size), pa.displayName, nf.format(r.onlyB.size), pb.displayName),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val pA = LibraryOverlap.parsePrice(priceA)
                val pB = LibraryOverlap.parsePrice(priceB)
                if (pA != null && pB != null) {
                    val money = NumberFormat.getNumberInstance(locale(ctx)).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
                    Text(stringResource(R.string.th_discover_subs_cost, money.format(pA + pB)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.th_discover_subs_cost_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OnlyOn(stringResource(R.string.th_discover_subs_only, pa.displayName), r.onlyA)
                OnlyOn(stringResource(R.string.th_discover_subs_only, pb.displayName), r.onlyB)
            }
        }
    }
}

@Composable
private fun OnlyOn(title: String, tracks: List<Track>) {
    if (tracks.isEmpty()) return
    var all by remember { mutableStateOf(false) }
    Spacer(Modifier.size(4.dp))
    Text(title, style = MaterialTheme.typography.titleSmall)
    val shown = if (all) tracks.take(200) else tracks.take(8)
    shown.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    if (!all && tracks.size > 8) TextButton(onClick = { all = true }) { Text(stringResource(R.string.th_preview_more, tracks.size - 8)) }
}


/**
 * The Wrapped being made: a card in the story's colours with a pulsing spark, a line that changes
 * every couple of seconds, one row per service (its logo, a bar while it is read, a tick and the songs
 * found once done) and the songs found so far.
 */
@Composable
private fun WrappedLoading(services: List<MusicProvider>, reading: Map<String, Triple<Int, Int, Int>>, current: String?, coverSearch: Boolean = false) {
    val lines = androidx.compose.ui.res.stringArrayResource(R.array.th_discover_wrapped_loading_lines)
    var line by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2200); line = (line + 1) % lines.size } }
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "spark")
    val scale by pulse.animateFloat(0.85f, 1.15f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "scale")
    val songs = reading.values.sumOf { it.third }
    val brush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(
        androidx.compose.ui.graphics.Color(0xFF5B3FD9), androidx.compose.ui.graphics.Color(0xFFC2185B), androidx.compose.ui.graphics.Color(0xFFFF7043)))
    val white = androidx.compose.ui.graphics.Color.White
    Box(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).background(brush).padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null, tint = white, modifier = Modifier.size(34.dp).graphicsLayer { scaleX = scale; scaleY = scale })
                Spacer(Modifier.width(12.dp))
                androidx.compose.animation.AnimatedContent(targetState = line, label = "line") { i ->
                    Text(lines[i], style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = white)
                }
            }
            services.forEach { p ->
                val r = reading[p.id]
                val done = r != null && current != p.id && (r.first >= r.second)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ServiceLogo(p, size = 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.displayName, style = MaterialTheme.typography.bodyMedium, color = white, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        when {
                            done -> Text(pluralStringResource(R.plurals.th_card_tracks, r!!.third, r.third), style = MaterialTheme.typography.bodySmall, color = white.copy(alpha = 0.8f))
                            current == p.id && r != null && r.second > 0 -> androidx.compose.material3.LinearProgressIndicator(
                                progress = { r.first.toFloat() / r.second }, color = white, trackColor = white.copy(alpha = 0.25f),
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                            current == p.id -> androidx.compose.material3.LinearProgressIndicator(color = white, trackColor = white.copy(alpha = 0.25f), modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                            else -> Text(stringResource(R.string.th_discover_wrapped_waiting), style = MaterialTheme.typography.bodySmall, color = white.copy(alpha = 0.6f))
                        }
                    }
                    if (done) { Spacer(Modifier.width(8.dp)); Icon(Icons.Filled.CheckCircle, null, tint = white) }
                }
            }
            if (songs > 0) Text(stringResource(R.string.th_discover_wrapped_found, songs), style = MaterialTheme.typography.labelLarge, color = white)
            if (coverSearch) Column {
                Text(stringResource(R.string.th_discover_wrapped_covers), style = MaterialTheme.typography.bodyMedium, color = white)
                androidx.compose.material3.LinearProgressIndicator(color = white, trackColor = white.copy(alpha = 0.25f), modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
    }
}


/** The Wrapped being made, outside any page: it goes on when the page is left, and says how far it is. */
internal object WrappedRun {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
    var busy by mutableStateOf(false); private set
    /** Per service: (playlists read, playlists to read, songs). */
    var reading by mutableStateOf<Map<String, Triple<Int, Int, Int>>>(emptyMap()); private set
    var readingFrom by mutableStateOf<List<MusicProvider>>(emptyList()); private set
    var current by mutableStateOf<String?>(null); private set
    /** True while the covers of the top artists are looked up, after the reading. */
    var coverSearch by mutableStateOf(false); private set
    /** A failure or an empty result not yet told to the person (the page says it when it shows). */
    var error by mutableStateOf<String?>(null)
    var empty by mutableStateOf(false)

    /** How long the covers are looked up at most, after the reading. */
    private const val COVER_WAIT_MS = 25_000L

    fun start(ctx: Context, read: List<MusicProvider>, onDone: (Wrapped.Saved) -> Unit) {
        if (busy) return
        busy = true; reading = emptyMap(); current = null; coverSearch = false; readingFrom = read
        scope.launch {
            try {
                val all = ArrayList<Track>()
                val used = ArrayList<MusicProvider>()
                // Which service each song was read from: its covers are looked up there first (YouTube's own pictures).
                val origin = HashMap<Track, String>()
                for (p in read) {
                    current = p.id
                    val got = LibraryReader.read(ctx, p) { done, total ->
                        reading = reading + (p.id to Triple(done, total, reading[p.id]?.third ?: 0))
                    }
                    reading = reading + (p.id to Triple(reading[p.id]?.second ?: 1, reading[p.id]?.second ?: 1, got.size))
                    if (got.isNotEmpty()) { all += got; used += p; for (t in got) origin.putIfAbsent(t, p.serviceId) }
                }
                current = null
                val year = Calendar.getInstance().get(Calendar.YEAR)
                val s = withContext(Dispatchers.Default) { Wrapped.compute(all, year) }
                if (s.trackCount == 0) empty = true
                else {
                    // A few covers of the top artists, for the card's art and colours: best effort and
                    // short, a Wrapped without them is drawn from its title's colours.
                    val covers = ArrayList<String>()
                    coverSearch = true
                    try {
                        kotlinx.coroutines.withTimeoutOrNull(COVER_WAIT_MS) {
                            Cards.distinctCoverUrls(Wrapped.coverCandidates(all).map { it to (origin[it] ?: "") }, Wrapped.MAX_COVERS, covers)
                        }
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { } finally { coverSearch = false }
                    val saved = Wrapped.save(s, Wrapped.services(used.map(::connector)), year, System.currentTimeMillis(), covers = synchronized(covers) { covers.toList() })
                    // Kept even if the page is gone: the store is the app's, not the page's.
                    Store.get(ctx).update { d -> d.copy(wrapped = Wrapped.remember(d.wrapped, saved)) }
                    runCatching { onDone(saved) }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = errorText(ctx, e)
            }
            busy = false
        }
    }
}
