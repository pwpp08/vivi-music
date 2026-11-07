package com.xlollx.songport.ui

import com.xlollx.songport.providers.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.xlollx.songport.model.MatchPolicy
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.Track
import com.xlollx.songport.model.UnplayableEntry
import com.xlollx.songport.model.UnplayableFix
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.sync.Matcher
import com.xlollx.songport.sync.SyncEngine
import com.xlollx.songport.sync.LibraryTools
import com.xlollx.songport.sync.Tools
import com.xlollx.songport.sync.Unplayable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What the user decided for one dead entry; nothing happens until "Apply". */
private sealed class Decision {
    class Replace(val with: Track) : Decision()
    object Remove : Decision()
    object Skip : Decision()
}

/**
 * Tracks a service lists but can no longer play, playlist by playlist, each with a playable
 * version proposed by the sync's own matching: accept it, search another, remove the entry or
 * skip it, then apply everything in one go. The originals are kept, so the change can be undone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnplayableScreen(provider: MusicProvider, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val engine = remember { SyncEngine(ctx) }
    val policy = remember { MatchPolicy() }
    var lists by remember(provider.id) { mutableStateOf<List<Playlist>?>(null) }
    // null = every playlist of the service.
    var chosen by remember(provider.id) { mutableStateOf<Playlist?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var scan by remember(provider.id) { mutableStateOf<LibraryTools.UnplayableScan?>(null) }
    var scanning by remember { mutableStateOf<String?>(null) }
    var applying by remember { mutableStateOf<Progress?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val proposals = remember(provider.id) { mutableStateMapOf<String, Matcher.Scored?>() }
    val decisions = remember(provider.id) { mutableStateMapOf<String, Decision>() }
    var openKey by remember { mutableStateOf<String?>(null) }
    var undoable by remember(provider.id) { mutableStateOf<UnplayableFix?>(null) }
    BackHandler { onClose() }

    LaunchedEffect(provider.id) {
        if (!provider.detectsUnplayable) return@LaunchedEffect
        lists = try { Tools.unplayableTargets(ctx, provider) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            snackbar.showSnackbar(e.message ?: ctx.getString(R.string.th_error_generic)); emptyList()
        }
    }

    fun keyOf(pl: Playlist, e: UnplayableEntry) = pl.id + "|" + e.key

    fun runScan() {
        val targets = chosen?.let { listOf(it) } ?: lists ?: return
        scanning = ""
        scan = null
        proposals.clear(); decisions.clear(); openKey = null
        scope.launch {
            val r = try { Tools.scanUnplayable(ctx, provider, targets) { scanning = it } } catch (e: CancellationException) { throw e } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: ctx.getString(R.string.th_error_generic)); null
            }
            scan = r
            scanning = null
            undoable = targets.singleOrNull()?.let { Tools.lastUnplayableFix(ctx, provider, it.id) }
            // One proposal per dead entry, searched in the background; a sure one is pre-accepted.
            r?.found?.forEach { u ->
                u.entries.forEach { e ->
                    val k = keyOf(u.playlist, e)
                    val p = try { Tools.proposeReplacement(ctx, provider, e, policy) } catch (ex: CancellationException) { throw ex } catch (ex: Exception) { null }
                    proposals[k] = p
                    if (Unplayable.confident(p, policy) && k !in decisions) decisions[k] = Decision.Replace(p!!.track)
                }
            }
        }
    }

    fun apply() {
        val r = scan ?: return
        applying = Progress(Progress.Step.ADDING, 0, 0)
        scope.launch {
            var replaced = 0
            var removed = 0
            var lastFix: UnplayableFix? = null
            try {
                for (u in r.found) {
                    val replace = u.entries.mapNotNull { e -> (decisions[keyOf(u.playlist, e)] as? Decision.Replace)?.let { e to it.with } }
                    val removeOnly = u.entries.filter { e -> decisions[keyOf(u.playlist, e)] is Decision.Remove }
                    val res = Tools.applyUnplayable(ctx, provider, u.playlist, replace, removeOnly) { applying = it }
                    replaced += res.replaced; removed += res.removed
                    if (res.fix != null) lastFix = res.fix
                }
                applying = null
                // What is left to look at: the entries skipped or without a decision.
                scan = r.copy(found = r.found.mapNotNull { u ->
                    val rest = u.entries.filter { e -> decisions[keyOf(u.playlist, e)].let { it == null || it is Decision.Skip } }
                    if (rest.isEmpty()) null else u.copy(entries = rest)
                })
                decisions.clear()
                undoable = if (r.found.size == 1) lastFix else null
                val fix = lastFix
                val shown = snackbar.showSnackbar(
                    ctx.getString(R.string.th_unplayable_done, replaced, removed),
                    actionLabel = if (fix != null && r.found.size == 1) ctx.getString(R.string.th_unplayable_undo) else null,
                    duration = SnackbarDuration.Long,
                )
                if (shown == SnackbarResult.ActionPerformed && fix != null) {
                    val n = Tools.undoUnplayable(ctx, provider, fix)
                    undoable = null
                    snackbar.showSnackbar(ctx.getString(R.string.th_unplayable_undone, n))
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                applying = null
                snackbar.showSnackbar(e.message ?: ctx.getString(R.string.th_error_generic))
            }
        }
    }

    val decided = decisions.values.count { it !is Decision.Skip }
    val toReplace = decisions.values.count { it is Decision.Replace }
    val toRemove = decisions.values.count { it is Decision.Remove }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.th_unplayable_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.th_unplayable_confirm_body, toReplace, if (provider.canRemoveTracks) toRemove + toReplace else 0))
                    if (!provider.canRemoveTracks) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.th_unplayable_no_removal, provider.displayName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { confirm = false; apply() }) { Text(stringResource(R.string.th_unplayable_apply, decided)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.th_cancel)) } },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.th_unplayable_title, provider.label(ctx)), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.th_nav_back)) } },
                actions = { HelpButton(Helps.unplayable) },
            )
        },
        bottomBar = {
            val a = applying
            if (decided > 0 || a != null) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                    if (a != null) {
                        CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.th_unplayable_applying, a.done, a.total), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)); Spacer(Modifier.width(12.dp))
                    }
                    Button(enabled = a == null, onClick = { confirm = true }) { Text(stringResource(R.string.th_unplayable_apply, decided)) }
                }
            }
        },
    ) { padding ->
        if (!provider.detectsUnplayable) {
            Text(stringResource(R.string.th_unplayable_unsupported, provider.displayName), modifier = Modifier.padding(padding).padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(R.string.th_tools_unplayable_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                val items = lists
                if (items == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.th_loading)) }
                } else {
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = chosen?.name ?: stringResource(R.string.th_unplayable_all_playlists),
                            onValueChange = {}, readOnly = true,
                            label = { Text(stringResource(R.string.th_editor_playlist)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.th_unplayable_all_playlists)) }, onClick = { chosen = null; expanded = false })
                            items.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { chosen = p; expanded = false }) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        val s = scanning
                        if (s != null) {
                            CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.th_unplayable_scanning, s), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        } else Spacer(Modifier.weight(1f))
                        val fix = undoable
                        if (fix != null && scanning == null) {
                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        val n = Tools.undoUnplayable(ctx, provider, fix)
                                        undoable = null
                                        snackbar.showSnackbar(ctx.getString(R.string.th_unplayable_undone, n))
                                    } catch (e: CancellationException) { throw e } catch (e: Exception) { snackbar.showSnackbar(e.message ?: ctx.getString(R.string.th_error_generic)) }
                                }
                            }) { Text(stringResource(R.string.th_unplayable_undo_last, fix.swaps.size)) }
                            Spacer(Modifier.width(8.dp))
                        }
                        OutlinedButton(enabled = scanning == null && applying == null && items.isNotEmpty(), onClick = { runScan() }) { Text(stringResource(R.string.th_unplayable_scan)) }
                    }
                }
            }
            val r = scan
            if (r != null) {
                item {
                    if (r.count == 0) Text(pluralStringResource(R.plurals.th_unplayable_none_in, r.playlists, r.playlists), style = MaterialTheme.typography.bodyMedium)
                    else {
                        // Two counts, each with its own plural: the tracks, then the playlists they are in.
                        val tracks = pluralStringResource(R.plurals.th_unplayable_tracks_count, r.count, r.count)
                        Text(pluralStringResource(R.plurals.th_unplayable_found_in, r.found.size, tracks, r.found.size), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.th_unplayable_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    r.failed.forEach { Text("${it.name}: ${it.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                r.found.forEach { u ->
                    item(key = "pl-" + u.playlist.id) {
                        Text(u.playlist.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(u.entries, key = { keyOf(u.playlist, it) }) { e ->
                        val k = keyOf(u.playlist, e)
                        val proposal = proposals[k]
                        val searched = k in proposals
                        val decision = decisions[k]
                        CollapsibleRow(
                            track = e.track, expanded = openKey == k, onToggle = { openKey = if (openKey == k) null else k },
                            trailing = { ReasonPill(e.reason) },
                            below = {
                                Spacer(Modifier.height(6.dp))
                                when (decision) {
                                    is Decision.Replace -> Text(stringResource(R.string.th_unplayable_decided_replace, decision.with.toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    is Decision.Remove -> Text(stringResource(R.string.th_unplayable_decided_remove), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    is Decision.Skip -> Text(stringResource(R.string.th_unplayable_skip), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    null -> when {
                                        !searched -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.th_unplayable_searching), style = MaterialTheme.typography.bodySmall) }
                                        proposal == null -> Text(stringResource(R.string.th_unplayable_no_proposal), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        else -> Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                if (e.relinked != null) stringResource(R.string.th_unplayable_relinked, proposal.track.toString())
                                                else stringResource(R.string.th_unplayable_proposal, proposal.track.toString(), (proposal.score * 100).toInt()),
                                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            FilledTonalButton(onClick = { decisions[k] = Decision.Replace(proposal.track) }) { Text(stringResource(R.string.th_review_use_this)) }
                                        }
                                    }
                                }
                            },
                        ) {
                            MatchSearch(
                                initialQuery = if (e.track.artists.isEmpty()) e.track.title else "${e.track.artistLine} - ${e.track.title}",
                                targetName = provider.label(ctx),
                                pickLabel = stringResource(R.string.th_review_use_this),
                                search = { q -> engine.searchOn(provider, u.playlist.id, policy, q, e.track) },
                                onPick = { t -> decisions[k] = Decision.Replace(t); openKey = null },
                                onError = { msg -> scope.launch { snackbar.showSnackbar(msg.ifBlank { ctx.getString(R.string.th_error_generic) }) } },
                                autoSearch = false,
                                webSearchUrl = remember(provider) { provider.webSearch(ctx) },
                                extraActions = { busy ->
                                    if (provider.canRemoveTracks) TextButton(enabled = !busy, onClick = { decisions[k] = Decision.Remove; openKey = null }) { Text(stringResource(R.string.th_unplayable_remove_only)) }
                                    TextButton(enabled = !busy, onClick = { decisions[k] = Decision.Skip; openKey = null }) { Text(stringResource(R.string.th_unplayable_skip)) }
                                    if (decision != null) TextButton(enabled = !busy, onClick = { decisions.remove(k); openKey = null }) { Text(stringResource(R.string.th_unplayable_undecide)) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Why the service refuses the entry, in a word; nothing when it did not say. */
@Composable
private fun ReasonPill(reason: String) {
    val res = when (reason) {
        Unplayable.REASON_MARKET -> R.string.th_unplayable_reason_market
        Unplayable.REASON_PRODUCT -> R.string.th_unplayable_reason_product
        Unplayable.REASON_EXPLICIT -> R.string.th_unplayable_reason_explicit
        Unplayable.REASON_PRIVATE -> R.string.th_unplayable_reason_private
        Unplayable.REASON_DELETED -> R.string.th_unplayable_reason_deleted
        else -> R.string.th_unplayable_reason_unknown
    }
    StatusPill(stringResource(res), Tone.Error)
    Spacer(Modifier.width(6.dp))
}
