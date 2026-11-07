package com.xlollx.songport.ui

import com.xlollx.songport.providers.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.Scheduler
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Trasferimento in blocco: molte playlist di un servizio verso un altro. Ogni playlist diventa una
 * sync normale (destinazione "da creare", stesso nome), cosi' resta in Sync per rieseguirla o
 * programmarla; le sync partono una dopo l'altra in un solo lavoro, per non far scattare i limiti
 * dei servizi tutte insieme. Una playlist che ha gia' una sync verso quel servizio non ne riceve
 * un'altra: si riesegue quella.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(onClose: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    ScreenBackHandler(onClose)

    // Come nell'editor: i connettori collegati, piu' "File" (sempre disponibile) come destinazione.
    val connected = remember { Providers.connectors().filter { it.isConnected(ctx) } }
    val sources = connected.filter { it.requiresAuth }
    val targets = (connected + LocalFilesProvider).distinctBy { it.id }.filter { it.canWrite && it.canCreatePlaylists }
    var srcId by remember { mutableStateOf(sources.firstOrNull()?.id ?: "") }
    var dstId by remember { mutableStateOf(targets.firstOrNull { it.id != srcId }?.id ?: "") }
    val src = sources.firstOrNull { it.id == srcId }
    val dst = targets.firstOrNull { it.id == dstId }

    var lists by remember { mutableStateOf<List<Playlist>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var schedule by remember { mutableStateOf(Schedule.MANUAL) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }

    LaunchedEffect(srcId) {
        lists = null; error = null; checked = emptySet()
        val p = src ?: return@LaunchedEffect
        try {
            val base = p.playlists(ctx)
            lists = p.libraryEntries(ctx, asTarget = false) + base
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message ?: ctx.getString(R.string.th_error_generic); lists = emptyList() }
    }

    // Sync gia' esistenti da questa origine verso la destinazione scelta, per playlist.
    val existing = remember(srcId, dstId, store.data.jobs) {
        store.data.jobs.filter { it.source.provider == srcId && it.target.provider == dstId && it.source.playlistId != null }
            .associateBy { it.source.playlistId!! }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.th_transfer_title)) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.th_nav_back)) } },
                actions = { HelpButton(Helps.transfer) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.th_transfer_from), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            ServiceChips(sources, srcId) { srcId = it }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.th_transfer_to), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            ServiceChips(targets, dstId) { dstId = it }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.th_editor_schedule), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Schedule.entries.forEach { s -> FilterChip(selected = schedule == s, onClick = { schedule = s }, label = { Text(scheduleLabel(s)) }) }
            }
            Spacer(Modifier.height(10.dp))

            val all = lists
            when {
                srcId == dstId && srcId.isNotEmpty() -> Text(stringResource(R.string.th_transfer_same_service), color = MaterialTheme.colorScheme.error)
                all == null -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.width(20.dp).height(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.th_loading)) }
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                all.isEmpty() -> Text(stringResource(R.string.th_transfer_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { checked = all.filter { dst?.supportsLibrary(it.id, asTarget = true) != false }.map { it.id }.toSet() }) { Text(stringResource(R.string.th_transfer_select_all)) }
                        TextButton(onClick = { checked = emptySet() }) { Text(stringResource(R.string.th_transfer_select_none)) }
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(all, key = { it.id }) { pl ->
                            val on = pl.id in checked
                            val allowed = dst?.supportsLibrary(pl.id, asTarget = true) != false
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable(enabled = allowed) { checked = if (on) checked - pl.id else checked + pl.id },
                            ) {
                                Checkbox(checked = on && allowed, enabled = allowed, onCheckedChange = { checked = if (it) checked + pl.id else checked - pl.id })
                                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                    Text(pl.name, style = MaterialTheme.typography.bodyMedium)
                                    val detail = listOfNotNull(
                                        if (pl.trackCount >= 0) stringResource(R.string.th_tracks_count, pl.trackCount) else null,
                                        if (pl.id in existing) stringResource(R.string.th_transfer_existing) else null,
                                        if (!allowed) stringResource(R.string.th_transfer_unsupported, dst?.label(ctx) ?: "") else null,
                                    ).joinToString(" · ")
                                    if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        if (busy) { CircularProgressIndicator(Modifier.width(20.dp).height(20.dp)); Spacer(Modifier.width(8.dp)) }
                        Button(enabled = !busy && checked.isNotEmpty() && dst != null && src != null && srcId != dstId, onClick = { confirm = true }) {
                            Text(pluralStringResource(R.plurals.th_transfer_run, checked.size, checked.size))
                        }
                    }
                    // Second step: nothing is created until the summary is confirmed (05/11: syncs left on
                    // Manual had to be fixed one by one after they had all started).
                    if (confirm && src != null && dst != null) {
                        val chosen = all.filter { it.id in checked && dst.supportsLibrary(it.id, asTarget = true) }
                        val reused = chosen.count { it.id in existing }
                        val fresh = chosen.size - reused
                        var startNow by remember { mutableStateOf(true) }
                        AlertDialog(
                            onDismissRequest = { confirm = false },
                            title = { Text(stringResource(R.string.th_transfer_confirm_title)) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(stringResource(R.string.th_transfer_confirm_route, src.label(ctx), dst.label(ctx)), style = MaterialTheme.typography.titleSmall)
                                    if (fresh > 0) Text("• " + pluralStringResource(R.plurals.th_transfer_confirm_new, fresh, fresh))
                                    if (reused > 0) Text("• " + pluralStringResource(if (startNow) R.plurals.th_transfer_confirm_rerun else R.plurals.th_transfer_confirm_kept, reused, reused))
                                    if (fresh > 0) {
                                        Text("• " + stringResource(R.string.th_editor_schedule) + ": " + scheduleLabel(schedule))
                                        Text("• " + stringResource(R.string.th_transfer_confirm_adds_only, dst.label(ctx)))
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { startNow = !startNow }) {
                                        Checkbox(checked = startNow, onCheckedChange = { startNow = it })
                                        Text(stringResource(R.string.th_transfer_confirm_start_now))
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(enabled = startNow || fresh > 0, onClick = {
                                    confirm = false
                                    busy = true
                                    val run = startNow
                                    scope.launch {
                                        val ids = chosen.map { pl ->
                                            val job = existing[pl.id] ?: SyncJob(
                                                id = UUID.randomUUID().toString(),
                                                name = pl.name,
                                                source = PlaylistRef(provider = srcId, playlistId = pl.id, playlistName = pl.name),
                                                // Liked songs, albums and artists go to the same library slot on the target.
                                                target = PlaylistRef(provider = dstId, playlistId = pl.id.takeIf { MusicProvider.isLibrary(it) }, playlistName = pl.name),
                                                schedule = schedule,
                                            ).also { store.upsertJob(it); Scheduler.apply(ctx, it) }
                                            job.id
                                        }
                                        if (run) {
                                            Scheduler.runInSequence(ctx, ids)
                                            snackbar.showSnackbar(ctx.getString(R.string.th_transfer_started, ids.size))
                                        } else {
                                            snackbar.showSnackbar(ctx.getString(R.string.th_transfer_created, fresh))
                                        }
                                        busy = false
                                        onDone()
                                    }
                                }) { Text(stringResource(if (startNow) R.string.th_transfer_confirm_create_start else R.string.th_transfer_confirm_create)) }
                            },
                            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.th_cancel)) } },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ServiceChips(available: List<MusicProvider>, selected: String, onSelect: (String) -> Unit) {
    val ctx = LocalContext.current
    ServicePicker(available, selected, onSelect)
}
