package com.xlollx.songport.ui

import com.xlollx.songport.providers.*
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.providers.LocalFilesProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The file playlists (imports and backups), on their own screen: a search field, one section per
 * origin ("Spotify - Rap" sits under "Spotify" as "Rap"), one compact row per playlist and a menu
 * with export and delete. Dozens of backups stay browsable instead of stretching a card for pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var lists by remember { mutableStateOf<List<LocalFilesProvider.Entry>>(emptyList()) }
    var version by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var openVersions by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(version) { lists = withContext(Dispatchers.IO) { LocalFilesProvider.entries(ctx) } }
    ScreenBackHandler(onClose)

    // One launcher per format: CreateDocument fixes the MIME type when it is built.
    var exporting by remember { mutableStateOf<String?>(null) }
    fun writeExport(uri: Uri?, format: LocalFilesProvider.Export) {
        val id = exporting
        exporting = null
        if (uri == null || id == null) return
        scope.launch {
            withContext(Dispatchers.IO) {
                ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(LocalFilesProvider.exportText(ctx, id, format)) }
            }
            snackbar.showSnackbar(ctx.getString(R.string.th_csv_exported))
        }
    }
    val export = rememberPlaylistExporter { uri, format -> writeExport(uri, format) }

    val filtered = lists.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    // Backups group under their service, then by playlist: the newest version is the row, the
    // older ones fold under it. Imports and other files sit under a plain heading.
    val groups = filtered.groupBy { it.service ?: "" }
        .mapValues { (_, items) -> items.groupBy { it.base }.values.map { v -> v.sortedByDescending { it.writtenAt } }.sortedBy { it.first().title.lowercase() } }
        .toSortedMap(compareBy<String> { it.isEmpty() }.thenBy { it.lowercase() })
    val total = lists.sumOf { it.trackCount.coerceAtLeast(0) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.th_files_title))
                        Text(stringResource(R.string.th_files_summary, lists.size, total), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.th_nav_back)) } },
                actions = { HelpButton(Helps.files) },
            )
        },
    ) { padding ->
        if (lists.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.th_csv_empty), textAlign = TextAlign.Center)
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (lists.size > 6) item(key = "search") {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text(stringResource(R.string.th_files_search)) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
            }
            groups.forEach { (group, playlists) ->
                item(key = "g-$group") {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        (group.ifEmpty { stringResource(R.string.th_files_group_other) }) + " · " + playlists.size,
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(playlists, key = { "p-" + it.first().base }) { versions ->
                    val latest = versions.first()
                    val key = latest.base
                    val open = key in openVersions
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column {
                            FileRow(
                                title = latest.title, entry = latest,
                                onExport = { format -> exporting = latest.id; export(format, latest.name) },
                                onDelete = { LocalFilesProvider.delete(ctx, latest.id); version++ },
                            )
                            if (versions.size > 1) {
                                TextButton(onClick = { openVersions = if (open) openVersions - key else openVersions + key }, modifier = Modifier.padding(start = 4.dp)) {
                                    Text(
                                        if (open) stringResource(R.string.th_files_hide_versions)
                                        else pluralStringResource(R.plurals.th_files_versions, versions.size - 1, versions.size - 1),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                if (open) versions.drop(1).forEach { v ->
                                    FileRow(
                                        title = null, entry = v,
                                        onExport = { format -> exporting = v.id; export(format, v.name) },
                                        onDelete = { LocalFilesProvider.delete(ctx, v.id); version++ },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One file: its name (or just the date for an older version), track count and when it was written, with the menu. */
@Composable
private fun FileRow(title: String?, entry: LocalFilesProvider.Entry, onExport: (LocalFilesProvider.Export) -> Unit, onDelete: () -> Unit) {
    Row(Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (title != null) Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.th_tracks_count, entry.trackCount) + " · " + formatDate(entry.writtenAt),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        var menu by remember { mutableStateOf(false) }
        var choosing by remember { mutableStateOf(false) }
        if (choosing) ExportFormatDialog(onDismiss = { choosing = false }) { choosing = false; onExport(it) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.th_csv_export)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                ExportMenuItem { menu = false; choosing = true }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.th_delete), color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}
