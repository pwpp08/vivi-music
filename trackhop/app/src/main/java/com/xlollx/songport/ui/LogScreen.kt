package com.xlollx.songport.ui

import androidx.compose.material3.AlertDialog
import com.xlollx.songport.providers.*
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.Notifications
import com.xlollx.songport.R
import com.xlollx.songport.data.Diagnostics
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Il registro delle sincronizzazioni, aperto dalle Opzioni: esiti, dettagli e il rapporto tecnico da
 * allegare a una segnalazione. Non e' una scheda principale perche' non serve nell'uso quotidiano.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(data: StoreData, onClose: () -> Unit, onResolve: (String) -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    ScreenBackHandler(onClose)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.th_tab_log)) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.th_nav_back)) } },
                actions = { HelpButton(Helps.notes, Helps.restore, label = stringResource(R.string.th_settings_log)) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { LogList(data, snackbar, onResolve) }
    }
}

@Composable
private fun LogList(data: StoreData, snackbar: SnackbarHostState, onResolve: (String) -> Unit) {
    if (data.reports.isEmpty()) {
        EmptyState(Icons.Filled.History, stringResource(R.string.th_empty_log_title), stringResource(R.string.th_empty_log_body))
        return
    }
    val ctx = LocalContext.current
    var exportOpen by remember { mutableStateOf(false) }
    // The export runs in this screen's scope: the dialog's own ends when it closes, and with it the export.
    val exportScope = rememberCoroutineScope()
    if (exportOpen) ExportLogDialog(onDismiss = { exportOpen = false }, onPick = { span ->
        exportOpen = false
        exportScope.launch { exportLog(ctx, span) }
    })
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "diag") {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                // The whole log, or the part of it a time span holds, as a text file: nothing cut.
                TextButton(onClick = { exportOpen = true }) { Text(stringResource(R.string.th_diag_export)) }
                if (data.settings.developerOptions) TextButton(onClick = {
                    val text = Diagnostics.report(ctx)
                    val share = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.th_app_name) + " diagnostics") }
                    ctx.startActivity(Intent.createChooser(share, ctx.getString(R.string.th_diag_share)))
                }) { Text(stringResource(R.string.th_diag_share)) }
            }
        }
        items(data.reports, key = { it.id }) { r -> ReportRow(r, data.jobs.firstOrNull { it.id == r.jobId }, snackbar, onResolve) }
    }
}

@Composable
private fun ReportRow(r: SyncReport, job: SyncJob?, snackbar: SnackbarHostState, onResolve: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var restoring by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val hasDetails = r.unmatched.isNotEmpty()
    // A sync into "File" lands in the app's private folder: offer to save it as a real document.
    val fileTarget = job?.takeIf { it.target.provider == LocalFilesProvider.serviceId }?.target?.playlistId
    val canSave = r.ok && fileTarget != null
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(LocalFilesProvider.Export.CSV.mime)) { uri ->
        if (uri != null && fileTarget != null) scope.launch {
            try {
                val text = LocalFilesProvider.exportText(ctx, fileTarget, LocalFilesProvider.Export.CSV)
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) } }
                snackbar.showSnackbar(ctx.getString(R.string.th_csv_exported))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: ctx.getString(R.string.th_error_generic))
            }
        }
    }
    Card(
        Modifier.fillMaxWidth().clickable(enabled = hasDetails) { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            val okBg = Tones.successContainer()
            val okFg = Tones.onSuccessContainer()
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(if (r.ok) okBg else MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (r.ok) Icons.Filled.Check else Icons.Filled.PriorityHigh, null,
                    tint = if (r.ok) okFg else MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.jobName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(formatDate(r.startedEpoch), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    Notifications.summary(ctx, r) + " · " + stringResource(R.string.th_log_duration, r.durationMs / 1000),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                if (r.notes.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    r.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                }
                if (expanded && hasDetails) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.th_log_unmatched_title), style = MaterialTheme.typography.labelMedium)
                    r.unmatched.take(5).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (r.unmatched.size > 5) Text(stringResource(R.string.th_preview_more, r.unmatched.size - 5), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val canRestore = job != null && r.removedTracks.isNotEmpty()
                val canReview = job != null && (r.unmatchedTracks.isNotEmpty() || r.reviewTracks.isNotEmpty())
                if (hasDetails || canRestore || canReview || canSave) {
                    Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        if (hasDetails) {
                            TextButton(onClick = { expanded = !expanded }) {
                                Text(stringResource(if (expanded) R.string.th_hide_details else R.string.th_show_details))
                            }
                        }
                        if (canRestore && job != null) {
                            TextButton(enabled = !restoring, onClick = {
                                restoring = true
                                scope.launch {
                                    try {
                                        SyncEngine(ctx).restoreRemoved(job, r)
                                        snackbar.showSnackbar(ctx.getString(R.string.th_restored_done, r.removedTracks.size))
                                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                                        snackbar.showSnackbar(e.message ?: ctx.getString(R.string.th_error_generic))
                                    }
                                    restoring = false
                                }
                            }) { Text(stringResource(R.string.th_restore_removed, r.removedTracks.size)) }
                        }
                        if (canReview) {
                            TextButton(onClick = { onResolve(r.id) }) { Text(stringResource(R.string.th_review_open)) }
                        }
                        if (canSave) {
                            TextButton(onClick = { saveLauncher.launch("$fileTarget.csv") }) { Text(stringResource(R.string.th_log_save_file)) }
                        }
                    }
                }
            }
        }
    }
}

/** The log since [span] ago (null: all of it) as a text file, handed to the share sheet. */
private suspend fun exportLog(ctx: android.content.Context, span: Long?) {
    val file = withContext(Dispatchers.IO) { Diagnostics.exportFile(ctx, span?.let { System.currentTimeMillis() - it }) }
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".cards", file)
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.th_app_name) + " log")
        clipData = android.content.ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(share, ctx.getString(R.string.th_diag_export)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(chooser) }.onFailure { Diagnostics.log(ctx, "log", "export not shared: ${it.message}") }
}

/** Which part of the log goes into the exported file: the last hour, day, week, or all of it. */
@Composable
private fun ExportLogDialog(onDismiss: () -> Unit, onPick: (Long?) -> Unit) {
    val hour = 3_600_000L
    val ranges = listOf(
        R.string.th_diag_range_hour to hour,
        R.string.th_diag_range_day to 24 * hour,
        R.string.th_diag_range_week to 7 * 24 * hour,
        R.string.th_diag_range_all to null,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.th_diag_export_title)) },
        text = {
            Column {
                Text(stringResource(R.string.th_diag_export_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                ranges.forEach { (label, span) ->
                    TextButton(onClick = { onPick(span) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(label), modifier = Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
