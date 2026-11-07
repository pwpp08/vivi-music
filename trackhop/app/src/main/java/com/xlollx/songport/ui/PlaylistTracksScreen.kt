package com.xlollx.songport.ui

import com.xlollx.songport.providers.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.sync.Matcher
import kotlinx.coroutines.launch

/**
 * The tracks of one playlist on a service, to take some out: a search box narrows the list by
 * title, artist or album; each row can be ticked, and a row's menu ticks every track by the same
 * artist or from the same album. One confirmation, then one removal call for the whole selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistTracksScreen(provider: MusicProvider, playlist: Playlist, onClose: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tracks by remember { mutableStateOf<List<Track>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    // Selection by position: the same track can sit in a playlist twice.
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    ScreenBackHandler(onClose)

    LaunchedEffect(version) {
        tracks = null; error = null; selected = emptySet()
        tracks = try { provider.tracks(ctx, playlist.id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message ?: ctx.getString(R.string.th_error_generic); emptyList() }
    }

    val all = tracks.orEmpty()
    val q = Matcher.normalizeTitle(query)
    val shown = remember(all, q) {
        if (q.isBlank()) all.indices.toList()
        else all.indices.filter { i ->
            val t = all[i]
            Matcher.normalizeTitle(t.title).contains(q) || t.artists.any { Matcher.normalizeArtist(it).contains(q) } || Matcher.normalizeTitle(t.album).contains(q)
        }
    }

    if (confirming) {
        val n = selected.size
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(pluralStringResource(R.plurals.th_tracks_remove_n, n, n)) },
            text = { Text(stringResource(R.string.th_tracks_remove_confirm, n, playlist.name, provider.label(ctx))) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false; busy = true
                    val picked = selected.sorted().map { all[it] }
                    scope.launch {
                        try {
                            provider.removeTracks(ctx, playlist.id, picked)
                            snackbar.showSnackbar(ctx.getString(R.string.th_tracks_removed, picked.size))
                            onChanged()
                            version++
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                            snackbar.showSnackbar(e.message ?: ctx.getString(R.string.th_error_generic))
                        }
                        busy = false
                    }
                }) { Text(stringResource(R.string.th_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.th_cancel)) } },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.th_nav_back)) } },
                actions = {
                    if (busy) CircularProgressIndicator(Modifier.padding(end = 16.dp).width(20.dp))
                    else if (selected.isNotEmpty()) TextButton(onClick = { confirming = true }) {
                        Text(pluralStringResource(R.plurals.th_tracks_remove_n, selected.size, selected.size), color = MaterialTheme.colorScheme.error)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.th_tracks_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.th_syncs_find_clear)) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // While loading there is nothing to count yet: "0 of 0" would read as an empty playlist.
                    if (tracks == null) stringResource(R.string.th_loading) else stringResource(R.string.th_tracks_shown, shown.size, all.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                if (shown.isNotEmpty()) {
                    val allShownSelected = shown.all { it in selected }
                    TextButton(onClick = { selected = if (allShownSelected) selected - shown.toSet() else selected + shown }) {
                        Text(stringResource(if (allShownSelected) R.string.th_tracks_unselect_shown else R.string.th_tracks_select_shown))
                    }
                }
            }
            when {
                tracks == null -> SkeletonRows(14)
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
                all.isEmpty() -> Text(stringResource(R.string.th_tracks_none), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    itemsIndexed(shown, key = { _, i -> i }) { _, i ->
                        val t = all[i]
                        val checked = i in selected
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !busy) { selected = if (checked) selected - i else selected + i }
                                .padding(start = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, enabled = !busy, onCheckedChange = { selected = if (it) selected + i else selected - i })
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val sub = listOf(t.artists.joinToString(", "), t.album).filter { it.isNotBlank() }.joinToString(" · ")
                                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val artist = t.artists.firstOrNull().orEmpty()
                            if (artist.isNotBlank() || t.album.isNotBlank()) {
                                var menu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(enabled = !busy, onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.th_more_actions)) }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        if (artist.isNotBlank()) {
                                            val a = Matcher.normalizeArtist(artist)
                                            DropdownMenuItem(text = { Text(stringResource(R.string.th_tracks_select_artist, artist)) }, onClick = {
                                                menu = false
                                                selected = selected + all.indices.filter { j -> all[j].artists.any { Matcher.normalizeArtist(it) == a } }
                                            })
                                        }
                                        if (t.album.isNotBlank()) {
                                            val al = Matcher.normalizeTitle(t.album)
                                            DropdownMenuItem(text = { Text(stringResource(R.string.th_tracks_select_album, t.album)) }, onClick = {
                                                menu = false
                                                selected = selected + all.indices.filter { j -> Matcher.normalizeTitle(all[j].album) == al }
                                            })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
