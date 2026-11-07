package com.xlollx.songport.ui

import com.xlollx.songport.ai.config
import com.xlollx.songport.providers.*
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.MatchReview
import com.xlollx.songport.model.TargetSearch
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.SyncEngine
import kotlinx.coroutines.launch

/**
 * Da rivedere dopo (o durante) una sync: gli abbinamenti incerti, da confermare o cambiare, e i
 * brani non trovati, da cercare a mano o ignorare. Ogni scelta finisce in cache e vale per le sync
 * future.
 *
 * Una riga per brano, chiusa: titolo e artista, niente altro. Toccandola si apre, una sola alla
 * volta, con la ricerca sulla destinazione gia' avviata e i candidati da scegliere. Cosi' anche
 * cento brani restano una lista leggibile, non un muro di campi di testo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnmatchedScreen(reportId: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    val data by store.state.collectAsState()
    val report = data.reports.firstOrNull { it.id == reportId }
    val job = report?.let { r -> data.jobs.firstOrNull { it.id == r.jobId } }
    val engine = remember { SyncEngine(ctx) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf<String?>(null) }
    // Rows picked to act on together ("r-" uncertain match, "u-" not found): a long press starts it.
    var picked by remember { mutableStateOf(setOf<String>()) }
    // What the remove dialog is about to take off the target.
    var discarding by remember { mutableStateOf<List<MatchReview>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val reviewKeys = report?.reviewTracks.orEmpty().map { "r-" + it.source.id }.toSet()
    val unmatchedKeys = report?.unmatchedTracks.orEmpty().map { "u-" + it.id }.toSet()
    // Rows done meanwhile drop out of the selection.
    val selection = picked.filter { it in reviewKeys || it in unmatchedKeys }.toSet()
    val selecting = selection.isNotEmpty()
    ScreenBackHandler(onClose)
    BackHandler(enabled = selecting) { picked = emptySet() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting) TopAppBar(
                title = { Text(stringResource(R.string.th_review_selected, selection.size)) },
                navigationIcon = { IconButton(onClick = { picked = emptySet() }) { Icon(Icons.Filled.Close, stringResource(R.string.th_review_clear_selection)) } },
                actions = {
                    if (selection.size < reviewKeys.size + unmatchedKeys.size) {
                        IconButton(onClick = { picked = reviewKeys + unmatchedKeys }) { Icon(Icons.Filled.SelectAll, stringResource(R.string.th_review_select_all)) }
                    }
                },
            ) else TopAppBar(
                title = { Text(stringResource(R.string.th_review_title, report?.jobName ?: "")) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.th_nav_back)) } },
                actions = {
                    HelpButton(Helps.change, Helps.proposal, Helps.ignore, label = stringResource(R.string.th_help_review_title))
                    // The list as text, to keep or send: what was not found and what was matched loosely.
                    if (report != null && (report.unmatchedTracks.isNotEmpty() || report.reviewTracks.isNotEmpty())) {
                        IconButton(onClick = {
                            val text = buildString {
                                append(ctx.getString(R.string.th_review_title, report.jobName)).append('\n')
                                append(ctx.getString(R.string.th_review_summary, report.unmatchedTracks.size, report.reviewTracks.size)).append("\n\n")
                                if (report.unmatchedTracks.isNotEmpty()) {
                                    append(ctx.getString(R.string.th_review_section_unmatched, report.unmatchedTracks.size)).append('\n')
                                    report.unmatchedTracks.forEach { t -> append("- ").append(t.toString()); if (t.album.isNotBlank()) append(" · ").append(t.album); append('\n') }
                                    append('\n')
                                }
                                if (report.reviewTracks.isNotEmpty()) {
                                    append(ctx.getString(R.string.th_review_section_uncertain, report.reviewTracks.size)).append('\n')
                                    report.reviewTracks.forEach { r -> append("- ").append(r.source.toString()).append(" → ").append(r.chosen.toString()).append(" (").append((r.score * 100).toInt()).append("%)\n") }
                                }
                            }
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.th_review_title, report.jobName))
                            runCatching { ctx.startActivity(Intent.createChooser(send, null)) }
                        }) { Icon(Icons.Filled.Share, stringResource(R.string.th_review_share)) }
                    }
                },
            )
        },
        bottomBar = {
            if (selecting && report != null && job != null) {
                val pickedReviews = report.reviewTracks.filter { "r-" + it.source.id in selection }
                val pickedTracks = report.unmatchedTracks.filter { "u-" + it.id in selection }
                BottomAppBar {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        if (pickedTracks.isNotEmpty()) {
                            TextButton(enabled = !busy, onClick = { engine.ignore(job, reportId, pickedTracks); picked = picked - pickedTracks.map { "u-" + it.id }.toSet() }) {
                                Text(stringResource(R.string.th_unmatched_ignore))
                            }
                        }
                        if (pickedReviews.isNotEmpty()) {
                            TextButton(enabled = !busy, onClick = { discarding = pickedReviews }) { Text(stringResource(R.string.th_review_discard)) }
                            FilledTonalButton(enabled = !busy, onClick = { engine.confirmMatches(reportId, pickedReviews); picked = picked - pickedReviews.map { "r-" + it.source.id }.toSet() }) {
                                Text(stringResource(R.string.th_review_keep))
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
        },
    ) { padding ->
        // Once each: a report saved before 1.6.1 can hold the same song twice, and two rows with one
        // key close the app (issue #24).
        val reviews = report?.reviewTracks.orEmpty().distinctBy { it.source.id }
        val tracks = report?.unmatchedTracks.orEmpty().distinctBy { it.id }
        if (report == null || job == null || (tracks.isEmpty() && reviews.isEmpty())) {
            Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.th_unmatched_none), textAlign = TextAlign.Center)
            }
            return@Scaffold
        }
        val dst = Providers.byId(job.target.provider)
        val dstName = dst?.displayName ?: job.target.provider
        // Only when the service has a web search: Subsonic servers other than Navidrome, and Plex, have none.
        val webSearch: ((String) -> String?)? = remember(dst) { dst?.webSearch(ctx) }
        // A candidate opened on the target service, in whatever app the phone opens its links with (#31).
        val openTrack: ((Track) -> String?)? = remember(dst) { dst?.let { p -> { t: Track -> p.trackUrl(ctx, t.id) } } }
        // The user's AI, when they connected one for the generator: the same key helps here.
        val ai = remember { com.xlollx.songport.ai.AiClient.config(ctx)?.takeIf { it.complete } }
        val language = remember { java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.ENGLISH) }
        fun askFor(source: Track): (suspend (List<Track>) -> com.xlollx.songport.ai.AiClient.Suggestion)? =
            ai?.let { cfg -> { cands -> com.xlollx.songport.ai.AiClient.suggestMatch(cfg, source, cands, dstName, language) } }
        fun fail(msg: String) { scope.launch { snackbar.showSnackbar(msg.ifBlank { ctx.getString(R.string.th_error_generic) }) } }
        fun toggle(id: String) { expanded = if (expanded == id) null else id }
        fun pick(key: String) { picked = if (key in selection) selection - key else selection + key }
        // A tap picks while selecting, and opens the row otherwise.
        fun tap(key: String) { if (selecting) pick(key) else toggle(key) }

        discarding?.let { list ->
            val removes = dst?.canRemoveTracks != false
            AlertDialog(
                onDismissRequest = { if (!busy) discarding = null },
                title = { Text(stringResource(R.string.th_review_discard_title, dstName)) },
                text = { Text(stringResource(if (removes) R.string.th_review_discard_body else R.string.th_review_discard_body_kept, dstName)) },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            val n = try {
                                engine.discardMatches(job, reportId, list)
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { fail(e.message ?: ""); null }
                            finally { busy = false; discarding = null }
                            picked = picked - list.map { "r-" + it.source.id }.toSet()
                            // Shown after: the message stays a few seconds and the bar must work meanwhile.
                            if (n != null && removes) snackbar.showSnackbar(ctx.getString(R.string.th_review_discarded, dstName, n))
                        }
                    }) { Text(stringResource(R.string.th_review_discard)) }
                },
                dismissButton = { TextButton(enabled = !busy, onClick = { discarding = null }) { Text(stringResource(R.string.th_cancel)) } },
            )
        }

        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "summary") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.th_review_summary, tracks.size, reviews.size), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.th_review_tap_hint, dstName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (reviews.size + tracks.size > 1) Text(stringResource(R.string.th_review_select_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (report.partial) {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(Icons.Filled.Info, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.tertiary)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.th_review_partial_banner), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                    }
                }
            }
            if (reviews.isNotEmpty()) {
                item(key = "h-review") {
                    SectionTitle(stringResource(R.string.th_review_section_uncertain, reviews.size), onSelectAll = { picked = selection + reviews.map { "r-" + it.source.id } }.takeIf { reviews.size > 1 })
                }
                items(reviews, key = { "r-" + it.source.id }) { review ->
                    ReviewRow(
                        review = review, dstName = dstName,
                        expanded = !selecting && expanded == "r-" + review.source.id, onToggle = { tap("r-" + review.source.id) },
                        selected = ("r-" + review.source.id in selection).takeIf { selecting }, onLongPress = { pick("r-" + review.source.id) },
                        onKeep = { engine.confirmMatch(reportId, review) },
                        onDiscard = { discarding = listOf(review) },
                        search = { q -> engine.searchOnTarget(job, q, review.source) },
                        webSearchUrl = webSearch, openTrack = openTrack, askAi = askFor(review.source),
                        onReplace = { chosen -> engine.replaceMatch(job, reportId, review, chosen) },
                        onError = ::fail,
                    )
                }
            }
            if (tracks.isNotEmpty()) {
                item(key = "h-unmatched") {
                    SectionTitle(stringResource(R.string.th_review_section_unmatched, tracks.size), onSelectAll = { picked = selection + tracks.map { "u-" + it.id } }.takeIf { tracks.size > 1 })
                }
                // What the service has no trace of goes last: there is little to do about it by hand.
                items(tracks.sortedBy { it.id in report.absent }, key = { "u-" + it.id }) { track ->
                    UnmatchedRow(
                        track = track, dstName = dstName, suggestion = report.suggestions[track.id], absent = track.id in report.absent,
                        expanded = !selecting && expanded == "u-" + track.id, onToggle = { tap("u-" + track.id) },
                        selected = ("u-" + track.id in selection).takeIf { selecting }, onLongPress = { pick("u-" + track.id) },
                        search = { q -> engine.searchOnTarget(job, q, track) },
                        webSearchUrl = webSearch, openTrack = openTrack, askAi = askFor(track),
                        onPick = { c -> engine.resolveManually(job, reportId, track, c) },
                        onIgnore = { engine.ignore(job, reportId, track) },
                        onError = ::fail,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, onSelectAll: (() -> Unit)? = null) {
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (onSelectAll != null) TextButton(onClick = onSelectAll) { Text(stringResource(R.string.th_review_select_all)) }
    }
}

/** Riga chiusa: titolo e artista in una riga, freccia a destra. Aperta: le azioni. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun CollapsibleRow(
    track: Track, expanded: Boolean, onToggle: () -> Unit, absent: Boolean = false,
    trailing: @Composable () -> Unit = {}, below: (@Composable () -> Unit)? = null,
    /** Picked or not while rows are being selected; null when they are not. */
    selected: Boolean? = null, onLongPress: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val tap = if (onLongPress != null) Modifier.combinedClickable(onClick = onToggle, onLongClick = onLongPress) else Modifier.clickable(onClick = onToggle)
    val colors = if (selected == true) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors()
    Card(Modifier.fillMaxWidth().then(tap), colors = colors) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected != null) {
                    Checkbox(checked = selected, onCheckedChange = { onToggle() })
                    Spacer(Modifier.width(4.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (absent) Text(stringResource(R.string.th_review_absent), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    Text(
                        listOfNotNull(track.artistLine.ifBlank { null }, track.album.ifBlank { null }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                trailing()
                if (selected == null) Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected == null) below?.invoke()
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 10.dp)) { content() }
            }
        }
    }
}

/** Origine -> scelto, con il punteggio; aperta: "Va bene" o ricerca di un sostituto. */
@Composable
fun ReviewRow(
    review: MatchReview,
    dstName: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onKeep: () -> Unit,
    search: suspend (String) -> TargetSearch,
    onReplace: suspend (Track) -> Unit,
    onError: (String) -> Unit,
    webSearchUrl: ((String) -> String?)? = null,
    openTrack: ((Track) -> String?)? = null,
    askAi: (suspend (List<Track>) -> com.xlollx.songport.ai.AiClient.Suggestion)? = null,
    /** The match is wrong and no other is wanted (issue #26); null where nothing was written yet. */
    onDiscard: (() -> Unit)? = null,
    selected: Boolean? = null,
    onLongPress: (() -> Unit)? = null,
) {
    var changing by remember { mutableStateOf(false) }
    CollapsibleRow(
        track = review.source, expanded = expanded, onToggle = onToggle, selected = selected, onLongPress = onLongPress,
        trailing = {
            Text(stringResource(R.string.th_review_score, (review.score * 100).toInt()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(6.dp))
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            TrackLine(review.chosen, Modifier.weight(1f))
        }
        if (!changing) {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (onDiscard != null) TextButton(onClick = onDiscard) { Text(stringResource(R.string.th_review_discard)) }
                TextButton(onClick = { changing = true }) { Text(stringResource(R.string.th_review_change)) }
                TextButton(onClick = onKeep) { Text(stringResource(R.string.th_review_keep)) }
            }
        } else {
            val initial = if (review.source.artists.isEmpty()) review.source.title else "${review.source.artistLine} - ${review.source.title}"
            MatchSearch(
                initialQuery = initial, targetName = dstName, pickLabel = stringResource(R.string.th_review_use_this),
                search = search, onPick = onReplace, onError = onError, autoSearch = true, webSearchUrl = webSearchUrl, openTrack = openTrack, askAi = askAi,
                extraActions = { TextButton(onClick = { changing = false }) { Text(stringResource(R.string.th_cancel)) } },
            )
        }
    }
}

@Composable
private fun UnmatchedRow(
    track: Track,
    dstName: String,
    suggestion: Track?,
    absent: Boolean = false,
    expanded: Boolean,
    onToggle: () -> Unit,
    search: suspend (String) -> TargetSearch,
    onPick: suspend (Track) -> Unit,
    onIgnore: () -> Unit,
    onError: (String) -> Unit,
    webSearchUrl: ((String) -> String?)? = null,
    openTrack: ((Track) -> String?)? = null,
    askAi: (suspend (List<Track>) -> com.xlollx.songport.ai.AiClient.Suggestion)? = null,
    selected: Boolean? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var accepting by remember { mutableStateOf(false) }
    // The sync's own runner-up, when it had one: accepted with a tap, no search to read through.
    val proposal: (@Composable () -> Unit)? = if (suggestion != null && !expanded) {
        {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(stringResource(R.string.th_search_suggested), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                TrackLine(suggestion, Modifier.weight(1f))
                FilledTonalButton(enabled = !accepting, onClick = {
                    accepting = true
                    scope.launch {
                        try { onPick(suggestion) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { onError(e.message ?: "") }
                        accepting = false
                    }
                }) { Text(stringResource(R.string.th_unmatched_add)) }
            }
        }
    } else null
    CollapsibleRow(track = track, expanded = expanded, onToggle = onToggle, absent = absent, below = proposal, selected = selected, onLongPress = onLongPress) {
        MatchSearch(
            initialQuery = if (track.artists.isEmpty()) track.title else "${track.artistLine} - ${track.title}",
            targetName = dstName,
            pickLabel = stringResource(R.string.th_unmatched_add),
            search = search, onPick = onPick, onError = onError, autoSearch = true, webSearchUrl = webSearchUrl, openTrack = openTrack, askAi = askAi,
            extraActions = { busy -> TextButton(enabled = !busy, onClick = onIgnore) { Text(stringResource(R.string.th_unmatched_ignore)) } },
        )
    }
}
