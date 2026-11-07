package com.xlollx.songport.ui

import com.xlollx.songport.providers.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.sync.PlaylistOps
import com.xlollx.songport.sync.Tools
import kotlinx.coroutines.launch

private enum class Op(val labelRes: Int) {
    COPY(R.string.th_tools_make_copy), MERGE(R.string.th_tools_make_merge), SPLIT(R.string.th_tools_make_split),
    SORT(R.string.th_tools_make_sort), SHUFFLE(R.string.th_tools_make_shuffle),
}

/**
 * "Nuova playlist da…": copia, unione, divisione, ordinamento, mescolamento. Sempre in una playlist
 * nuova sullo stesso servizio, cosi' gli id dei brani restano validi (niente ricerca) e l'originale
 * non cambia.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MakePlaylistCard(provider: MusicProvider, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lists by remember(provider.id) { mutableStateOf<List<Playlist>?>(null) }
    var op by remember(provider.id) { mutableStateOf(Op.COPY) }
    // Per unire ne servono piu' di una; per il resto la prima e' quella che conta.
    var picked by remember(provider.id) { mutableStateOf<List<Playlist>>(emptyList()) }
    var name by remember(provider.id) { mutableStateOf("") }
    var nameEdited by remember(provider.id) { mutableStateOf(false) }
    var dropDuplicates by remember { mutableStateOf(true) }
    var splitSize by remember { mutableStateOf("50") }
    var sortKey by remember { mutableStateOf(PlaylistOps.SortKey.ARTIST) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Progress?>(null) }

    LaunchedEffect(provider.id) {
        lists = try {
            val base = provider.playlists(ctx)
            if (provider.supportsLikedSongs) listOf(Playlist(MusicProvider.LIKED_ID, ctx.getString(R.string.th_liked_songs))) + base else base
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }

    // Nome proposto: cambia con l'operazione e la playlist finche' l'utente non lo tocca.
    val first = picked.firstOrNull()
    val suggested = when {
        first == null -> ""
        op == Op.COPY -> stringResource(R.string.th_tools_make_copy_suffix, first.name)
        op == Op.MERGE -> stringResource(R.string.th_tools_make_merge_default)
        op == Op.SPLIT -> first.name
        op == Op.SORT -> stringResource(R.string.th_tools_make_sorted_suffix, first.name)
        else -> stringResource(R.string.th_tools_make_shuffled_suffix, first.name)
    }
    val shownName = if (nameEdited) name else suggested

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.th_tools_make_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                HelpButton(Helps.make)
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.th_tools_make_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!provider.canWrite || !provider.canCreatePlaylists) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.th_tools_make_cannot_create, provider.displayName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                return@Column
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Op.entries.forEach { o ->
                    FilterChip(selected = op == o, onClick = { op = o; if (o != Op.MERGE) picked = picked.take(1) }, label = { Text(stringResource(o.labelRes)) })
                }
            }
            Spacer(Modifier.height(10.dp))
            val items = lists
            if (items == null) {
                Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.th_loading)) }
            } else {
                // Le playlist scelte, con la croce per toglierle (solo nell'unione ce n'e' piu' di una).
                picked.forEachIndexed { i, pl ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(pl.name, style = MaterialTheme.typography.bodyMedium)
                            if (pl.trackCount >= 0) Text(stringResource(R.string.th_tracks_count, pl.trackCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { picked = picked.filterIndexed { j, _ -> j != i } }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.th_delete)) }
                    }
                }
                if (picked.isEmpty() || op == Op.MERGE) {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(if (picked.isEmpty()) R.string.th_editor_playlist else R.string.th_tools_make_add_playlist)) },
                            placeholder = { Text(stringResource(R.string.th_editor_choose_playlist)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            items.filter { p -> picked.none { it.id == p.id } }.forEach { p ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(p.name)
                                            if (p.trackCount >= 0) Text(stringResource(R.string.th_tracks_count, p.trackCount), style = MaterialTheme.typography.bodySmall)
                                        }
                                    },
                                    onClick = { picked = picked + p; expanded = false },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            when (op) {
                Op.MERGE -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = dropDuplicates, onCheckedChange = { dropDuplicates = it })
                    Text(stringResource(R.string.th_tools_make_drop_duplicates))
                }
                Op.SPLIT -> OutlinedTextField(
                    value = splitSize, onValueChange = { v -> splitSize = v.filter { it.isDigit() }.take(4) },
                    label = { Text(stringResource(R.string.th_tools_make_split_size)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
                Op.SORT -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        PlaylistOps.SortKey.ARTIST to R.string.th_tools_make_sort_artist,
                        PlaylistOps.SortKey.TITLE to R.string.th_tools_make_sort_title,
                        PlaylistOps.SortKey.ALBUM to R.string.th_tools_make_sort_album,
                        PlaylistOps.SortKey.YEAR to R.string.th_tools_make_sort_year,
                        PlaylistOps.SortKey.ADDED to R.string.th_tools_make_sort_added,
                        PlaylistOps.SortKey.DURATION to R.string.th_tools_make_sort_duration,
                        PlaylistOps.SortKey.REVERSE to R.string.th_tools_make_sort_reverse,
                    ).forEach { (k, res) -> FilterChip(selected = sortKey == k, onClick = { sortKey = k }, label = { Text(stringResource(res)) }) }
                }
                else -> {}
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = shownName, onValueChange = { name = it; nameEdited = true },
                label = { Text(stringResource(R.string.th_editor_new_playlist_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            val ready = picked.isNotEmpty() && shownName.isNotBlank() && (op != Op.MERGE || picked.size >= 2) &&
                (op != Op.SPLIT || (splitSize.toIntOrNull() ?: 0) > 0)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    progress?.let { Text(stringResource(R.string.th_tools_progress_make, it.done, it.total), style = MaterialTheme.typography.bodySmall); Spacer(Modifier.width(8.dp)) }
                }
                Button(enabled = !busy && ready, onClick = {
                    busy = true
                    val chosen = picked
                    val finalName = shownName.trim()
                    scope.launch {
                        try {
                            val sources = chosen.map { provider.tracks(ctx, it.id) }
                            val parts: List<Pair<String, List<Track>>> = when (op) {
                                Op.COPY -> listOf(finalName to sources.first())
                                Op.MERGE -> listOf(finalName to PlaylistOps.merge(sources, dropDuplicates))
                                Op.SPLIT -> PlaylistOps.split(sources.first(), splitSize.toInt()).mapIndexed { i, part ->
                                    ctx.getString(R.string.th_tools_make_part_name, finalName, i + 1) to part
                                }
                                Op.SORT -> listOf(finalName to PlaylistOps.sort(sources.first(), sortKey))
                                Op.SHUFFLE -> listOf(finalName to PlaylistOps.shuffle(sources.first()))
                            }
                            var added = 0
                            var failed = 0
                            for ((n, tracks) in parts) {
                                val r = Tools.createWith(ctx, provider, n, MusicProvider.description(ctx.getString(R.string.th_app_name)), tracks) { progress = it }
                                added += r.added; failed += r.failed.size
                            }
                            val done = if (parts.size == 1) ctx.getString(R.string.th_tools_make_done, parts.first().first, added)
                            else ctx.getString(R.string.th_tools_make_done_parts, parts.size, added)
                            snackbar.showSnackbar(done + (if (failed > 0) " · " + ctx.getString(R.string.th_tools_make_failed, failed) else ""))
                            lists = null
                            lists = try { provider.playlists(ctx) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                            snackbar.showSnackbar(e.message ?: ctx.getString(R.string.th_error_generic))
                        }
                        busy = false
                    }
                }) { Text(stringResource(R.string.th_tools_make_run)) }
            }
        }
    }
}
