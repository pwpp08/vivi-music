package com.xlollx.songport.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.R
import com.xlollx.songport.data.ConfigBackup
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.sync.BackupExport
import com.xlollx.songport.sync.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backup & restore, one place for both kinds (from Settings and from Tools): the set-up as one
 * .trackhop file (ConfigBackup: syncs, settings, chosen matches, File playlists; never sign-ins), to
 * keep or to take to another phone, Docker or Trackhop Desktop; the playlists themselves as dated
 * files, now or on a schedule; and a folder outside the phone where both are copied.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    val data by store.state.collectAsState()
    val settings = data.settings
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    ScreenBackHandler(onClose)

    // ---- the set-up as a file
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = ConfigBackup.export(ctx.env, "Vivi ${BuildConfig.VERSION_NAME} (Android)")
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("no stream")
                }.isSuccess
            }
            snackbar.showSnackbar(ctx.getString(if (ok) R.string.th_backup_config_saved else R.string.th_error_generic))
        }
    }
    var pending by remember { mutableStateOf<ConfigBackup.Snapshot?>(null) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching { ConfigBackup.read(ctx.contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) }) }
            }
            read.onSuccess { pending = it }.onFailure { snackbar.showSnackbar(ctx.getString(R.string.th_backup_config_invalid)) }
        }
    }
    pending?.let { snap ->
        val s = ConfigBackup.summary(snap)
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.th_backup_config_confirm_title)) },
            text = { Text(stringResource(R.string.th_backup_config_confirm_body, s.syncs, s.matches, s.files, s.writtenBy.ifBlank { "Vivi" })) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    scope.launch {
                        val done = withContext(Dispatchers.IO) { runCatching { ConfigBackup.import(ctx.env, snap) } }
                        done.onSuccess { r ->
                            // The imported syncs' schedules start here as they would have where they came from.
                            store.data.jobs.forEach { Scheduler.apply(ctx, it) }
                            snackbar.showSnackbar(ctx.getString(R.string.th_backup_config_restored, r.syncs))
                        }.onFailure { snackbar.showSnackbar(it.message ?: ctx.getString(R.string.th_error_generic)) }
                    }
                }) { Text(stringResource(R.string.th_backup_config_restore_action)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.th_cancel)) } },
        )
    }

    // ---- the folder outside the phone
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            store.updateSettings { it.copy(backupFolder = uri.toString()) }
            Scheduler.applyBackup(ctx)
        }
    }
    val running by BackupExport.running.collectAsState()
    val step by BackupExport.step.collectAsState()
    var stateVersion by remember { mutableIntStateOf(0) }
    val state = remember(stateVersion, settings.backupFolder) { BackupExport.state(ctx) }
    // A run started here (or by the schedule) ends whenever the screen is showing: its outcome once.
    val done by BackupExport.finished.collectAsState()
    LaunchedEffect(done) {
        val r = done ?: return@LaunchedEffect
        BackupExport.finished.value = null
        stateVersion++
        // In the screen's scope: clearing [finished] restarts this effect, which would cancel the
        // snackbar the moment it appeared.
        scope.launch { snackbar.showSnackbar(
            if (r.folderMissing) r.failed.first()
            else ctx.getString(R.string.th_backup_now_done, r.services) +
                (if (r.copied > 0) " · " + ctx.getString(R.string.th_backup_folder_copied, r.copied) else "") +
                (if (r.failed.isNotEmpty()) " · " + r.failed.first() else ""),
        ) }
    }
    var scheduleOpen by remember { mutableStateOf(false) }
    if (scheduleOpen) ChoiceDialog(
        stringResource(R.string.th_editor_schedule),
        listOf(Schedule.MANUAL, Schedule.DAILY, Schedule.WEEKLY).map { it to scheduleLabel(it) },
        settings.backupSchedule,
        onDismiss = { scheduleOpen = false },
    ) { s ->
        scheduleOpen = false
        store.updateSettings { it.copy(backupSchedule = s) }; Scheduler.applyBackup(ctx)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.th_backup_title)) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.th_nav_back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // The same margins and gaps as Settings and Tools: the sections stand apart, off the screen's edges.
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            PrefSection(stringResource(R.string.th_backup_config_section), accent = JobColor.BLUE, description = stringResource(R.string.th_backup_config_desc)) {
                PrefRow(Icons.Filled.Save, stringResource(R.string.th_backup_config_save), null, chevron = false) { save.launch(ConfigBackup.fileName()) }
                PrefRow(Icons.Filled.Restore, stringResource(R.string.th_backup_config_restore), null, chevron = false) { open.launch(arrayOf("*/*")) }
            }
            PrefSection(stringResource(R.string.th_backup_playlists_section), accent = JobColor.GREEN, description = stringResource(R.string.th_tools_backup_desc, LocalFilesProvider.KEEP_VERSIONS)) {
                state.lastError?.let { PrefBlock { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }
                    ?: state.lastOk.takeIf { it > 0 }?.let { PrefBlock { Text(stringResource(R.string.th_backup_last_run, formatDate(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                PrefRow(
                    Icons.Filled.Backup, stringResource(R.string.th_backup_now),
                    if (running && step.isNotBlank()) step else stringResource(R.string.th_backup_now_desc),
                    enabled = !running, chevron = false,
                    trailing = if (running) { { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) } } else null,
                ) { BackupExport.start(ctx) }
            }
            val folder = settings.backupFolder
            PrefSection(stringResource(R.string.th_backup_auto_section), accent = JobColor.TEAL, description = stringResource(R.string.th_backup_auto_desc), help = { HelpButton(Helps.backups) }) {
                PrefRow(Icons.Filled.Schedule, stringResource(R.string.th_editor_schedule), scheduleLabel(settings.backupSchedule)) { scheduleOpen = true }
                PrefRow(
                    Icons.Filled.Folder, stringResource(R.string.th_backup_folder_title),
                    if (folder.isBlank()) stringResource(R.string.th_backup_folder_none) else BackupExport.folderLabel(folder),
                    trailing = if (folder.isBlank()) { { TextButton(onClick = { pickFolder.launch(null) }) { Text(stringResource(R.string.th_backup_folder_pick)) } } } else null,
                ) { pickFolder.launch(null) }
                if (folder.isNotBlank()) {
                    PrefRow(Icons.Filled.LinkOff, stringResource(R.string.th_backup_folder_stop), null, enabled = !running, danger = true, chevron = false) {
                        store.updateSettings { it.copy(backupFolder = "") }; Scheduler.applyBackup(ctx)
                    }
                }
            }
        }
    }
}

