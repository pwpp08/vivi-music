package com.xlollx.songport.ui

import com.xlollx.songport.providers.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.MusicProvider
import kotlinx.coroutines.launch

/**
 * A single track shared into the app (a link from the service's own app): pick one of your
 * playlists on that service, or the liked songs, and it goes in.
 */
@Composable
fun AddTrackDialog(provider: MusicProvider, track: Track, onClose: () -> Unit, onDone: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf<List<Playlist>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(provider.id) {
        lists = try {
            val own = provider.playlists(ctx.applicationContext).filter { it.ownedByMe && !MusicProvider.isLibrary(it.id) }
            val liked = if (provider.supportsLikedTarget) listOf(Playlist(MusicProvider.LIKED_ID, ctx.getString(R.string.th_liked_songs))) else emptyList()
            liked + own
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message ?: ctx.getString(R.string.th_error_generic); emptyList() }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(stringResource(R.string.th_share_track_title, provider.label(ctx))) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(track.toString(), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.padding(4.dp))
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    lists == null || busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.width(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.th_loading))
                    }
                    lists!!.isEmpty() -> Text(stringResource(R.string.th_manage_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(lists!!, key = { it.id }) { pl ->
                            val name = playlistDisplayName(pl.id, pl.name)
                            Text(
                                name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    busy = true
                                    scope.launch {
                                        try {
                                            provider.addTracks(ctx.applicationContext, pl.id, listOf(track))
                                            onDone(ctx.getString(R.string.th_share_track_added, name))
                                            onClose()
                                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                                            busy = false; error = e.message ?: ctx.getString(R.string.th_error_generic)
                                        }
                                    }
                                }.padding(vertical = 12.dp, horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(stringResource(R.string.th_cancel)) } },
    )
}
