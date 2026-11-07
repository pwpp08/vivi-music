package com.xlollx.songport.ui

import androidx.compose.material3.IconButton
import com.xlollx.songport.providers.*
import android.net.Uri
import android.content.Intent
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.ai.AiClient
import com.xlollx.songport.model.TargetSearch
import com.xlollx.songport.model.Track
import com.xlollx.songport.sync.Durations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Riga compatta "titolo · artista · album · durata" di un brano. */
@Composable
fun TrackLine(t: Track, modifier: Modifier = Modifier, emphasis: Boolean = false, tag: String? = null) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.title, style = if (emphasis) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
            if (tag != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.extraSmall).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            listOfNotNull(t.artistLine.ifBlank { null }, t.album.ifBlank { null }, Durations.format(t.durationMs).ifBlank { null }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Campo di ricerca sulla destinazione con elenco dei candidati e pulsante "Aggiungi"/"Usa questo".
 * Riusato dalla revisione degli abbinamenti (prima e dopo la sync) e dai brani non trovati.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MatchSearch(
    initialQuery: String,
    targetName: String,
    pickLabel: String,
    search: suspend (String) -> TargetSearch,
    onPick: suspend (Track) -> Unit,
    onError: (String) -> Unit,
    autoSearch: Boolean = false,
    webSearchUrl: ((String) -> String?)? = null,
    /** A candidate's address on the target service, to listen to it before picking it (#31); null = no button. */
    openTrack: ((Track) -> String?)? = null,
    extraActions: @Composable (busy: Boolean) -> Unit = {},
    /** The user's AI, when configured: looks at the candidates and says which one it is, or how else to search. */
    askAi: (suspend (List<Track>) -> AiClient.Suggestion)? = null,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var query by remember { mutableStateOf(initialQuery) }
    var candidates by remember { mutableStateOf<TargetSearch?>(null) }
    var busy by remember { mutableStateOf(false) }
    var aiNote by remember { mutableStateOf<String?>(null) }
    var aiPick by remember { mutableStateOf<String?>(null) }
    // Aperto per sistemare un brano: la prima ricerca parte da sola, un tocco in meno per ogni brano.
    LaunchedEffect(Unit) {
        if (autoSearch && candidates == null && query.isNotBlank()) {
            busy = true
            candidates = try { search(query) } catch (e: CancellationException) { throw e } catch (e: Exception) { onError(e.message ?: ""); TargetSearch(emptyList()) }
            busy = false
        }
    }

    OutlinedTextField(
        value = query, onValueChange = { query = it }, singleLine = true, enabled = !busy,
        label = { Text(stringResource(R.string.th_unmatched_search_hint, targetName)) },
        supportingText = { Text(stringResource(R.string.th_unmatched_search_help)) },
        modifier = Modifier.fillMaxWidth(),
    )
    fun runSearch() {
        busy = true
        scope.launch {
            candidates = try { search(query) } catch (e: CancellationException) { throw e } catch (e: Exception) { onError(e.message ?: ""); TargetSearch(emptyList()) }
            busy = false
        }
    }
    // Find it by hand: the service's own search opens (in its app when installed), the user copies the
    // track's link there and comes back to paste it.
    FlowRow(modifier = Modifier.fillMaxWidth()) {
        if (webSearchUrl != null) {
            TextButton(enabled = !busy, onClick = {
                val q = query.trim().takeIf { it.isNotEmpty() && !it.contains("://") } ?: initialQuery
                webSearchUrl(q)?.let { url ->
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        .onFailure { onError(it.message ?: "") }
                }
            }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.th_search_open_in, targetName), maxLines = 1)
            }
        }
        if (askAi != null) TextButton(enabled = !busy, onClick = {
            busy = true; aiNote = null
            scope.launch {
                try {
                    val seen = candidates?.hits?.map { it.track } ?: emptyList()
                    val s = askAi(seen)
                    aiNote = s.note.ifBlank { null }
                    val current = candidates ?: TargetSearch(emptyList())
                    val picked = s.match?.let { seen.getOrNull(it - 1) }
                    // Its choice goes first, tagged; its alternative searches run and their new hits join the list.
                    val extra = ArrayList<TargetSearch.Hit>()
                    for (q in s.queries) {
                        val r = try { search(q) } catch (e: CancellationException) { throw e } catch (e: Exception) { continue }
                        extra += r.hits
                    }
                    val known = HashSet<String>()
                    val merged = (listOfNotNull(picked?.let { p -> current.hits.first { it.track.id == p.id } }) + current.hits + extra)
                        .filter { known.add(it.track.id) }
                    aiPick = picked?.id
                    candidates = current.copy(hits = merged)
                    if (picked == null && s.queries.isEmpty() && aiNote == null) aiNote = ctx.getString(R.string.th_search_ai_nothing)
                } catch (e: CancellationException) { throw e } catch (e: Exception) { onError(e.message ?: "") }
                busy = false
            }
        }) {
            Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.th_search_ask_ai), maxLines = 1)
        }
        TextButton(enabled = !busy, onClick = {
            val clip = ctx.getSystemService(ClipboardManager::class.java)?.primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim().orEmpty()
            // Share texts carry words around the link: keep the link.
            val link = Regex("""https?://\S+""").find(text)?.value
            if (link == null) onError(ctx.getString(R.string.th_search_clipboard_empty))
            else { query = link; runSearch() }
        }) {
            Icon(Icons.Filled.ContentPaste, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.th_search_paste_link), maxLines = 1)
        }
    }
    // Wraps instead of squeezing: with three extra actions in a narrow card, a Row left the
    // Search button a column of single letters.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp).align(Alignment.CenterVertically))
        extraActions(busy)
        Button(enabled = !busy && query.isNotBlank(), onClick = { runSearch() }) { Text(stringResource(R.string.th_unmatched_search), maxLines = 1, softWrap = false) }
    }
    aiNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
    candidates?.let { result ->
        val pick: @Composable (TargetSearch.Hit, Boolean) -> Unit = { h, primary ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TrackLine(h.track, Modifier.weight(1f), tag = when {
                    h.track.id == aiPick -> stringResource(R.string.th_search_tag_ai)
                    h.kind == TargetSearch.Kind.ALBUM -> stringResource(R.string.th_search_tag_album)
                    h.kind == TargetSearch.Kind.VIDEO -> stringResource(R.string.th_search_tag_video)
                    else -> null
                })
                val act = {
                    busy = true
                    scope.launch {
                        try { onPick(h.track) } catch (e: CancellationException) { throw e } catch (e: Exception) { onError(e.message ?: "") }
                        busy = false
                    }
                    Unit
                }
                // Opened with the link alone, no app named: the phone's default for that address
                // (YouTube Music's links can go to Metrolist or the like, if the user chose so).
                openTrack?.invoke(h.track)?.let { url ->
                    IconButton(onClick = {
                        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.th_search_listen)) }
                }
                if (primary) FilledTonalButton(enabled = !busy, onClick = act) { Text(pickLabel) }
                else TextButton(enabled = !busy, onClick = act) { Text(pickLabel) }
            }
        }
        if (result.hits.isEmpty()) {
            Text(stringResource(R.string.th_unmatched_no_results), style = MaterialTheme.typography.bodySmall)
            return@let
        }
        // The few likeliest first, whatever they came from: most fixes are one look and one tap.
        val top = result.hits.take(TOP_HITS)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.th_search_top), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            HelpButton(Helps.results, Helps.askAi, label = stringResource(R.string.th_help_results_title))
        }
        top.forEachIndexed { i, h -> pick(h, i == 0 && (h.score >= PRIMARY_SCORE || h.track.id == aiPick)) }
        val rest = result.hits.drop(TOP_HITS)
        // Everything else folded by origin: the album (or the news that it is missing), the other
        // songs, the videos. Open only what you need.
        val album = result.album
        if (album != null && result.albumFound == false) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.th_search_album_missing, album, targetName), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FoldedHits(if (album != null) stringResource(R.string.th_search_album_section, album, targetName, rest.count { it.kind == TargetSearch.Kind.ALBUM }) else "", rest.filter { it.kind == TargetSearch.Kind.ALBUM }, pick)
        FoldedHits(stringResource(R.string.th_search_other_results) + " · " + rest.count { it.kind == TargetSearch.Kind.SONG }, rest.filter { it.kind == TargetSearch.Kind.SONG }, pick)
        FoldedHits(stringResource(R.string.th_search_videos_section, rest.count { it.kind == TargetSearch.Kind.VIDEO }), rest.filter { it.kind == TargetSearch.Kind.VIDEO }, pick)
    }
}

private const val TOP_HITS = 3
/** From this similarity up, the first proposal gets the prominent button. */
private const val PRIMARY_SCORE = 0.5

/** A section of results closed by default: its title with the count, the rows on tap. */
@Composable
private fun FoldedHits(title: String, hits: List<TargetSearch.Hit>, pick: @Composable (TargetSearch.Hit, Boolean) -> Unit) {
    if (hits.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { open = !open }) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) hits.forEach { pick(it, false) }
}
