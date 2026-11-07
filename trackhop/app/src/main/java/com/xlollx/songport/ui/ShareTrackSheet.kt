package com.xlollx.songport.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.xlollx.songport.providers.*
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.TargetSearch
import com.xlollx.songport.model.Track
import com.xlollx.songport.sync.Matcher
import com.xlollx.songport.sync.SyncEngine
import com.xlollx.songport.sync.TrackLinks
import com.xlollx.songport.sync.ShazamShare
import com.xlollx.songport.sync.TikTokShare
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.xlollx.songport.sync.TrackLookup
import kotlinx.coroutines.CancellationException

/**
 * A single track link shared from any music app, headed for any of the user's services: a friend's
 * Spotify link lands in a YouTube Music, Navidrome or Jellyfin playlist. The sheet reads what the
 * link is about, lists the connected services as targets (the last one used first), finds the same
 * recording on the chosen one with the sync's own matching, shows the best match with its score and
 * the other candidates, and hands the chosen track to [AddTrackDialog] for the playlist. "Open in"
 * opens the matched track in the target's own app or site; "Copy link" keeps the original address.
 *
 * Everything runs on the phone: the link's own service is asked when connected, otherwise song.link
 * or the service's public page (see [TrackLookup]).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShareTrackSheet(
    text: String,
    ref: TrackLinks.Ref,
    onClose: () -> Unit,
    onAdd: (MusicProvider, Track) -> Unit,
    onMessage: (String) -> Unit,
    onNewSync: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val store = remember { Store.get(ctx) }
    val clipboard = LocalClipboardManager.current
    val engine = remember { SyncEngine(ctx) }
    val link = remember(text) { Regex("""https?://\S+""").find(text)?.value ?: text.trim() }
    val fromName = remember(ref) { serviceName(ref.service) }

    // Every connected service that takes tracks: the link's own one is just one of them.
    val targets = remember { Providers.connectors().filter { it.requiresAuth && it.canWrite && it.isConnected(app) } }
    var target by remember {
        val last = store.data.settings.shareTarget
        mutableStateOf(targets.firstOrNull { it.id == last } ?: targets.firstOrNull { TrackLinks.matches(ref.service, it.serviceId) } ?: targets.firstOrNull())
    }

    // What the link is about, read once; null while loading, a title-less Track when nothing answered.
    var source by remember { mutableStateOf<Track?>(null) }
    var sourceFailed by remember { mutableStateOf(false) }
    // Why a TikTok names no song: its creator's own sound, or a page that did not say.
    var sourceNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ref) {
        source = when (ref.service) {
            // A Shazam share names its song in the text: nothing to ask anyone.
            ShazamShare.SERVICE -> ShazamShare.track(text)
            // A TikTok video: the song its public page names.
            TikTokShare.SERVICE -> when (val sound = withContext(Dispatchers.IO) { TikTokShare.sound(link) }) {
                is TikTokShare.Sound.Song -> { if (sound.recognized) sourceNote = ctx.getString(R.string.th_tiktok_recognized); sound.track }
                is TikTokShare.Sound.Original -> { sourceNote = ctx.getString(R.string.th_tiktok_original_sound); null }
                TikTokShare.Sound.Unknown -> { sourceNote = ctx.getString(R.string.th_tiktok_unreadable); null }
            }
            else -> describe(app, ref, link)
        }
        if (source == null) { sourceFailed = true; source = Track(id = ref.trackId, title = "") }
    }

    // The same recording on the target: exact (its own id, read from the service) or the best of a search.
    var exact by remember { mutableStateOf<Track?>(null) }
    var best by remember { mutableStateOf<TargetSearch.Hit?>(null) }
    var searching by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }
    val src = source
    val dst = target
    LaunchedEffect(dst?.id, src) {
        exact = null; best = null; changing = false
        if (dst == null || src == null) return@LaunchedEffect
        searching = true
        trace("lookup on ${dst.id} for ${ref.service}:${ref.trackId}")
        // The whole lookup (song.link, the service, the matching) is one step off the main thread,
        // and its result is shown in one go. A service that never answers must not leave the sheet
        // on "Finding it…": after a while (real time, whatever the screen's dispatcher) the manual
        // search takes over, where the user can try again or type the title.
        val found: Lookup? = try {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                    val e = exactOn(app, dst, ref, link)
                    trace("exact: ${e?.id}")
                    if (e != null || src.title.isBlank()) Lookup(e, null)
                    else {
                        val hits = engine.searchOnTarget(jobFor(dst), queryOf(src), src).hits
                        trace("search: ${hits.size} hits")
                        Lookup(null, hits.maxByOrNull { it.score }?.takeIf { it.score >= Matcher.DEFAULT_THRESHOLD })
                    }
                }
            }.also { if (it == null) trace("timed out") }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            trace("failed: $e")
            onMain { onMessage(e.message ?: ctx.getString(R.string.th_error_generic)) }; null
        }
        // Shown from the main thread: a write from the worker the lookup ended on could leave the
        // sheet on "Finding it…" (see onMain).
        onMain {
            exact = found?.exact
            best = found?.best
            changing = exact == null && best == null
            searching = false
            trace("shown: exact=${exact != null} best=${best != null}")
        }
    }

    fun open(p: MusicProvider, t: Track) {
        val url = p.trackUrl(app, t.id) ?: return
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { onMessage(ctx.getString(R.string.th_error_generic)) }
    }
    fun add(p: MusicProvider, t: Track) {
        store.updateSettings { it.copy(shareTarget = p.id) }
        onAdd(p, t)
    }

    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.th_share_sheet_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.th_share_sheet_from, fromName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            when {
                src == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.th_loading))
                }
                src.title.isBlank() -> Text(sourceNote ?: stringResource(R.string.th_share_sheet_unknown), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    TrackLine(src, emphasis = true)
                    // Heard in the video's audio rather than named by its page: said, it may be wrong.
                    sourceNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TrackLinks.url(ref.service, ref.trackId)?.let { url ->
                    TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) { Text(stringResource(R.string.th_share_sheet_open, fromName)) }
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(link)); onMessage(ctx.getString(R.string.th_share_sheet_copied)) }) { Text(stringResource(R.string.th_share_sheet_copy)) }
            }
            // From Shazam, and no sync of the Shazam playlist yet: the way to have every Shazam everywhere.
            if (ref.service == ShazamShare.SERVICE && onNewSync != null &&
                store.data.jobs.none { it.shazam || it.source.playlistName.contains("shazam", ignoreCase = true) }
            ) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(stringResource(R.string.th_shazam_share_hint), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onNewSync, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.th_shazam_share_hint_button)) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (targets.isEmpty()) {
                Text(stringResource(R.string.th_share_sheet_no_targets), color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.th_share_sheet_target), style = MaterialTheme.typography.labelLarge)
            // The same logo tiles as the other service pickers: a row that scrolls sideways.
            ServicePicker(targets, dst?.id, onSelect = { id -> targets.firstOrNull { it.id == id }?.let { target = it } })
            Spacer(Modifier.height(8.dp))
            if (dst == null || src == null) return@Column
            val dstName = dst.label(app)
            when {
                searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.th_share_sheet_looking, dstName))
                }
                exact != null -> {
                    val t = exact!!
                    Text(stringResource(R.string.th_share_sheet_exact, dstName), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    TrackLine(t)
                    Actions(dst, t, canOpen = dst.trackUrl(app, t.id) != null, onAdd = { add(dst, t) }, onOpen = { open(dst, t) }, onChange = { exact = null; changing = true })
                }
                best != null && !changing -> {
                    val h = best!!
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.th_share_sheet_best, dstName), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.th_review_score, (h.score * 100).toInt()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                    }
                    TrackLine(h.track)
                    Actions(dst, h.track, canOpen = dst.trackUrl(app, h.track.id) != null, onAdd = { add(dst, h.track) }, onOpen = { open(dst, h.track) }, onChange = { changing = true })
                }
                else -> {
                    if (best == null && src.title.isNotBlank()) Text(stringResource(R.string.th_share_sheet_no_match, dstName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val job = remember(dst.id) { jobFor(dst) }
                    MatchSearch(
                        initialQuery = queryOf(src), targetName = dstName, pickLabel = stringResource(R.string.th_share_sheet_add),
                        search = { q -> engine.searchOnTarget(job, q, src.takeIf { it.title.isNotBlank() }) },
                        onPick = { t -> add(dst, t) }, onError = onMessage, autoSearch = src.title.isNotBlank(),
                        webSearchUrl = remember(dst.id) { dst.webSearch(app) },
                    )
                }
            }
        }
    }
}

private const val SEARCH_TIMEOUT_MS = 30_000L

/** What the lookup found on the target: the very recording, or the best of a search. */
private class Lookup(val exact: Track?, val best: TargetSearch.Hit?)

/** The lookup's steps in the debug log (UI test failures show where it stopped). */
private fun trace(msg: String) { if (com.xlollx.songport.BuildConfig.DEBUG) android.util.Log.d("ShareSheet", "[${Thread.currentThread().name}] $msg") }

/** [block]'s result, or null on failure; a cancellation is passed on, never taken for a failure. */
private inline fun <T> attempt(block: () -> T?): T? = try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

@Composable
private fun Actions(p: MusicProvider, t: Track, canOpen: Boolean, onAdd: () -> Unit, onOpen: () -> Unit, onChange: () -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onChange) { Text(stringResource(R.string.th_share_sheet_change)) }
        if (canOpen) TextButton(onClick = onOpen) { Text(stringResource(R.string.th_share_sheet_open, p.label(ctx))) }
        FilledTonalButton(onClick = onAdd) { Text(stringResource(R.string.th_share_sheet_add)) }
    }
}

/** A throwaway job on [p] alone: what the engine's target search needs to know (service, policy). */
private fun jobFor(p: MusicProvider) = SyncJob(id = "share", name = "", source = PlaylistRef(provider = p.id), target = PlaylistRef(provider = p.id))

private fun queryOf(t: Track) = if (t.artists.isEmpty()) t.title else "${t.artistLine} - ${t.title}"

private fun serviceName(service: String) = when (service) {
    "spotify" -> "Spotify"; "apple" -> "Apple Music"; "youtube" -> "YouTube Music"; "amazon" -> "Amazon Music"
    "deezer" -> "Deezer"; "tidal" -> "TIDAL"; TikTokShare.SERVICE -> "TikTok"; else -> service.replaceFirstChar { it.uppercase() }
}

/**
 * The track behind the link: from a connected account of its own service, else from any connected
 * service song.link knows it on, else from the service's public endpoints. Null when none answers.
 */
private suspend fun describe(app: android.content.Context, ref: TrackLinks.Ref, link: String): Track? {
    val connected = Providers.connectors().filter { it.requiresAuth && it.isConnected(app) }
    connected.firstOrNull { TrackLinks.matches(ref.service, it.serviceId) }?.let { own ->
        attempt { own.track(app, ref.trackId) }?.takeIf { it.title.isNotBlank() }?.let { return it }
    }
    for (p in connected.filter { !TrackLinks.matches(ref.service, it.serviceId) }) {
        val there = attempt { TrackLinks.viaOdesli(link, p.serviceId) } ?: continue
        attempt { p.track(app, there.trackId) }?.takeIf { it.title.isNotBlank() }?.let { return it }
    }
    return TrackLookup.describe(ref, link)
}

/** The very recording on [dst], when its id is known there: the link's own service, or song.link's word. */
private suspend fun exactOn(app: android.content.Context, dst: MusicProvider, ref: TrackLinks.Ref, link: String): Track? {
    // Shazam's ids are its own: the song is found by searching what it is called.
    if (ref.service == ShazamShare.SERVICE || ref.service == TikTokShare.SERVICE) return null
    val id = if (TrackLinks.matches(ref.service, dst.serviceId)) ref.trackId
        else attempt { TrackLinks.viaOdesli(link, dst.serviceId) }?.trackId ?: return null
    return attempt { dst.track(app, id) }?.takeIf { it.title.isNotBlank() }
}
