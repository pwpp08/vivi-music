package com.xlollx.songport.ui

import com.xlollx.songport.providers.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.sync.LibraryScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Repeats across playlists and liked songs in no playlist, for one service; each list can become a file. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScanScreen(provider: MusicProvider, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var result by remember { mutableStateOf<LibraryScan.Result?>(null) }
    var step by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    ScreenBackHandler(onClose)
    LaunchedEffect(provider.id) {
        result = try { LibraryScan.run(ctx, provider) { step = it } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message ?: ctx.getString(R.string.th_error_generic); null }
    }
    fun save(name: String, tracks: List<Track>) {
        scope.launch {
            val id = withContext(Dispatchers.IO) { LocalFilesProvider.importTracks(ctx, name, tracks) }
            snackbar.showSnackbar(ctx.getString(R.string.th_ai_done_file, tracks.size, id))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.th_scan_title, provider.label(ctx)), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.th_nav_back)) } },
            )
        },
    ) { padding ->
        val r = result
        when {
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(padding).padding(24.dp))
            r == null -> Row(Modifier.padding(padding).padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(20.dp)); Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.th_scan_reading, step), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            else -> LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Text(stringResource(R.string.th_scan_summary, r.playlists, r.repeats.size, r.orphanLiked.size), style = MaterialTheme.typography.bodyMedium)
                    r.failed.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                if (r.repeats.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text(stringResource(R.string.th_scan_repeats, r.repeats.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = { save(ctx.getString(R.string.th_scan_repeats_file, provider.displayName), r.repeats.map { it.track }) }) { Text(stringResource(R.string.th_scan_save)) }
                        }
                    }
                    items(r.repeats, key = { "r-" + it.track.id }) { rep ->
                        Column(Modifier.fillMaxWidth()) {
                            TrackLine(rep.track)
                            Text(rep.playlists.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (r.orphanLiked.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                            Text(stringResource(R.string.th_scan_orphans, r.orphanLiked.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = { save(ctx.getString(R.string.th_scan_orphans_file, provider.displayName), r.orphanLiked) }) { Text(stringResource(R.string.th_scan_save)) }
                        }
                    }
                    items(r.orphanLiked, key = { "o-" + it.id }) { t -> TrackLine(t) }
                }
                if (r.repeats.isEmpty() && r.orphanLiked.isEmpty()) item { Text(stringResource(R.string.th_scan_clean), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
