package com.xlollx.songport.ui

import com.xlollx.songport.ai.config
import com.xlollx.songport.providers.*
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.EditNotifications
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.produceState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.R
import com.xlollx.songport.ads.Ads
import com.xlollx.songport.auth.LockActivity
import com.xlollx.songport.data.Store
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.BackupExport
import com.xlollx.songport.sync.Scheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun openUrl(ctx: android.content.Context, url: String) {
    if (url.isBlank()) return
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/** The attribution and the statement of changes the NOTICE of upstream (AGPL-3.0 section 7(b)) asks a modified version to show. */
internal const val ATTRIBUTION = "Based on Trackhop by xlollx, modified by pwpp08 for Vivi · AGPL-3.0 · github.com/xlollx/Trackhop"

/** The little arrow of a row that leaves the app (browser, system settings). */
@Composable
private fun ExternalMark() = Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))

/** The arrow of a row that opens in place. */
@Composable
private fun ExpandMark(open: Boolean) = Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)

/**
 * Settings, in groups read from the top down by how often they are wanted: how the app looks,
 * how it syncs, what it tells you, the backup folder, privacy, then the advanced corner (log,
 * keys, developer tools) and the about box. Every row says its current value under its name.
 */
@Composable
fun SettingsScreen(
    data: StoreData,
    store: Store,
    snackbar: SnackbarHostState,
    onSetup: (MusicProvider) -> Unit,
    onOpenLog: () -> Unit = {},
    onBackup: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val privacyRequired by Ads.privacyOptionsRequired.collectAsState()
    val scope = rememberCoroutineScope()
    val settings = data.settings

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + LocalBottomBarInset.current),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // ---------------------------------------------------------------- Appearance
        PrefSection(stringResource(R.string.th_settings_section_appearance), accent = JobColor.PURPLE) {
            PrefSwitch(Icons.Filled.Dashboard, stringResource(R.string.th_settings_new_look), stringResource(R.string.th_settings_new_look_summary), settings.newLook, help = { HelpButton(Helps.newLook) }) { v ->
                store.updateSettings { it.copy(newLook = v) }
            }
        }

        // ---------------------------------------------------------------- Syncing
        PrefSection(stringResource(R.string.th_settings_section_syncing), accent = JobColor.BLUE) {
            PrefSwitch(Icons.Filled.Verified, stringResource(R.string.th_settings_isrc), stringResource(R.string.th_settings_isrc_summary), settings.isrcLookup, help = { HelpButton(Helps.isrc) }) { v ->
                store.updateSettings { it.copy(isrcLookup = v) }
            }
            // The link alias lives in the package manager, not in the store: read it from there.
            var musicLinks by remember { mutableStateOf(MusicLinks.enabled(ctx)) }
            PrefSwitch(Icons.Filled.Link, stringResource(R.string.th_settings_music_links), stringResource(R.string.th_settings_music_links_summary), musicLinks, help = { HelpButton(Helps.musicLinks) }) { v ->
                MusicLinks.set(ctx, v); musicLinks = v
            }
            PrefRow(
                Icons.Filled.BatteryAlert, stringResource(R.string.th_settings_battery), stringResource(R.string.th_settings_battery_summary),
                trailing = { ExternalMark() },
            ) {
                // Apre l'elenco di sistema (nessun permesso speciale): l'utente sceglie Trackhop e lo esclude.
                runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        }

        // ---------------------------------------------------------------- Notifications
        PrefSection(stringResource(R.string.th_settings_section_notifications), accent = JobColor.ORANGE) {
            PrefSwitch(Icons.Filled.Notifications, stringResource(R.string.th_settings_notifications), stringResource(R.string.th_settings_notifications_summary), settings.notifyOnSync, help = { HelpButton(Helps.notifications) }) { v ->
                store.updateSettings { it.copy(notifyOnSync = v) }
            }
            // Tasker, MacroDroid…: the commands and the end-of-sync event, off until turned on.
            PrefSwitch(Icons.Filled.Bolt, stringResource(R.string.th_settings_automation), stringResource(R.string.th_settings_automation_summary), settings.automation) { v ->
                store.updateSettings { it.copy(automation = v) }
            }
            if (settings.automation) {
                // The intents' names are for whoever sets up Tasker or MacroDroid: folded away, with a copy button.
                var howOpen by rememberSaveable { mutableStateOf(false) }
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                Column(Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp)) {
                    TextButton(onClick = { howOpen = !howOpen }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text(stringResource(R.string.th_settings_automation_commands))
                        Icon(if (howOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                    }
                    if (howOpen) {
                        val how = stringResource(R.string.th_settings_automation_how)
                        Text(how, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(how)) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Text(stringResource(R.string.th_settings_automation_copy))
                        }
                    }
                }
            }
            PrefSwitch(Icons.Filled.MusicOff, stringResource(R.string.th_settings_unplayable), stringResource(R.string.th_settings_unplayable_summary), settings.unplayableCheck) { v ->
                store.updateSettings { it.copy(unplayableCheck = v) }; Scheduler.applyUnplayable(ctx)
            }
            PrefRow(
                Icons.Filled.EditNotifications, stringResource(R.string.th_settings_notifications_system), stringResource(R.string.th_settings_notifications_system_desc),
                trailing = { ExternalMark() },
            ) {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                }
            }
        }

        // ---------------------------------------------------------------- Backup & restore
        // One place for both kinds (BackupScreen): the set-up as a file, and the playlists' backups.
        PrefSection(stringResource(R.string.th_backup_title), accent = JobColor.GREEN) {
            val folder = settings.backupFolder
            PrefRow(
                Icons.Filled.Backup, stringResource(R.string.th_backup_title),
                if (folder.isNotBlank()) stringResource(R.string.th_backup_row_folder, BackupExport.folderLabel(folder)) else stringResource(R.string.th_backup_row_desc),
            ) { onBackup() }
        }

        // ---------------------------------------------------------------- Privacy and security
        PrefSection(
            stringResource(R.string.th_settings_section_privacy),
            accent = JobColor.TEAL,
        ) {
            PrefSwitch(Icons.Filled.Lock, stringResource(R.string.th_security_lock), stringResource(R.string.th_security_lock_desc), settings.appLock) { v ->
                if (v && !LockActivity.available(ctx)) {
                    scope.launch { snackbar.showSnackbar(ctx.getString(R.string.th_security_lock_unavailable)) }
                } else {
                    store.updateSettings { it.copy(appLock = v) }
                }
            }
            var factsOpen by remember { mutableStateOf(false) }
            PrefRow(Icons.Filled.Security, stringResource(R.string.th_security_title), stringResource(R.string.th_settings_security_summary)) { factsOpen = true }
            if (factsOpen) AlertDialog(
                onDismissRequest = { factsOpen = false },
                title = { Text(stringResource(R.string.th_security_title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(R.string.th_security_fact_1, R.string.th_security_fact_2, R.string.th_security_fact_3, R.string.th_security_fact_4).forEach {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { factsOpen = false }) { Text(stringResource(R.string.th_ok)) } },
            )
            // Play build only, and only where the consent rules ask for it (UMP decides).
            if (privacyRequired) {
                PrefRow(Icons.Filled.PrivacyTip, stringResource(R.string.th_settings_privacy_options), null, trailing = { ExternalMark() }) {
                    (ctx as? Activity)?.let { Ads.showPrivacyOptions(it) }
                }
            }
        }

        // ---------------------------------------------------------------- Plugins
        // Play build only: the GitHub/F-Droid build carries the connectors inside. Neutral on
        // purpose: what a plugin is and which ones are installed, never a name, a link or a hint.
        if (!BridgePlugin.builtIn) {
            // Asking a plugin which services it serves is an IPC call: off the main thread.
            val plugins by produceState<List<Pair<BridgePlugin.Installed, List<String>>>?>(null) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val active = BridgePlugin.packageName(ctx)
                    val services = Providers.services().filter { it.pluginBased && runCatching { it.isConfigured(ctx) }.getOrDefault(false) }.map { it.displayName }
                    BridgePlugin.installedPlugins(ctx).map { it to if (it.packageName == active) services else emptyList() }
                }
            }
            // What a plugin is, above the list; nothing about where plugins come from.
            PrefSection(stringResource(R.string.th_plugins_title), accent = JobColor.PINK, description = stringResource(R.string.th_plugins_desc), footer = stringResource(R.string.th_plugins_where), help = { HelpButton(Helps.plugins) }) {
                val list = plugins
                if (list != null && list.isEmpty()) PrefRow(Icons.Filled.Extension, stringResource(R.string.th_plugins_none))
                list?.forEach { (p, services) ->
                    val launch = ctx.packageManager.getLaunchIntentForPackage(p.packageName)
                    PrefRow(
                        Icons.Filled.Extension, p.label,
                        listOfNotNull(
                            p.version?.let { stringResource(R.string.th_settings_version, it) },
                            services.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.th_plugins_services, it.joinToString(", ")) },
                        ).joinToString(" · "),
                        onClick = launch?.let { i -> { runCatching { ctx.startActivity(i) } } },
                    )
                }
            }
        }

        // ---------------------------------------------------------------- Advanced
        PrefSection(stringResource(R.string.th_settings_section_advanced)) {
            PrefRow(Icons.Filled.History, stringResource(R.string.th_settings_log), stringResource(R.string.th_settings_log_summary), onClick = onOpenLog)
            var keysOpen by remember { mutableStateOf(false) }
            PrefRow(Icons.Filled.VpnKey, stringResource(R.string.th_settings_advanced), stringResource(R.string.th_settings_keys_summary), trailing = { ExpandMark(keysOpen) }) { keysOpen = !keysOpen }
            if (keysOpen) {
                PrefBlock { Text(stringResource(R.string.th_settings_advanced_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Providers.all().filter { it.setupGuide != null && it.slot.isEmpty() }.forEach { p ->
                    val configured = remember(p.id, data.settings) { p.isConfigured(ctx) }
                    PrefRow(
                        null, p.displayName,
                        stringResource(if (configured) R.string.th_setup_using_own else R.string.th_not_configured),
                        leading = { ProviderBadge(p, 40.dp) },
                        trailing = {
                            Text(stringResource(if (configured) R.string.th_edit else R.string.th_setup_now), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        },
                    ) { onSetup(p) }
                }
            }

            PrefSwitch(Icons.Filled.DeveloperMode, stringResource(R.string.th_settings_developer_switch), stringResource(R.string.th_settings_developer_summary), settings.developerOptions) { v ->
                store.updateSettings { it.copy(developerOptions = v) }
            }
            if (settings.developerOptions && settings.captureUnlocked && com.xlollx.songport.providers.BuiltIn.captureIntent(ctx, "https://x") != null) {
                // A service being added (Qobuz…): its web player opens here and its calls are written
                // to a file to share, without cookies, tokens or passwords.
                var captureOpen by remember { mutableStateOf(false) }
                PrefRow(Icons.Filled.NetworkCheck, stringResource(R.string.th_capture_site_title), stringResource(R.string.th_capture_site_desc)) { captureOpen = true }
                if (captureOpen) {
                    var address by remember { mutableStateOf("https://play.qobuz.com") }
                    AlertDialog(
                        onDismissRequest = { captureOpen = false },
                        title = { Text(stringResource(R.string.th_capture_site_title)) },
                        text = { OutlinedTextField(address, { address = it.trim() }, singleLine = true, label = { Text(stringResource(R.string.th_capture_site_address)) }) },
                        confirmButton = {
                            TextButton(enabled = address.startsWith("https://") && address.length > 10, onClick = {
                                captureOpen = false
                                com.xlollx.songport.providers.BuiltIn.captureIntent(ctx, address)?.let { runCatching { ctx.startActivity(it) } }
                            }) { Text(stringResource(R.string.th_capture_site_open)) }
                        },
                        dismissButton = { TextButton(onClick = { captureOpen = false }) { Text(stringResource(android.R.string.cancel)) } },
                    )
                }
            }
            if (settings.developerOptions) {
                // "Nothing leaves your phone except…" made checkable: the hosts this app has talked to.
                var connectionsOpen by remember { mutableStateOf(false) }
                var connectionsVersion by remember { mutableIntStateOf(0) }
                PrefRow(Icons.Filled.NetworkCheck, stringResource(R.string.th_security_connections), stringResource(R.string.th_security_connections_desc), trailing = { ExpandMark(connectionsOpen) }) {
                    connectionsOpen = !connectionsOpen
                }
                if (connectionsOpen) PrefBlock {
                    val entries = remember(connectionsVersion) { com.xlollx.songport.net.HostLog.entries() }
                    if (com.xlollx.songport.providers.BuiltIn.available) Text(stringResource(R.string.th_security_connections_web), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (entries.isEmpty()) Text(stringResource(R.string.th_security_connections_none), style = MaterialTheme.typography.bodySmall)
                    entries.forEach { e ->
                        Column {
                            Text(e.host, style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.th_security_connections_count, e.count, formatDate(e.lastAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (entries.isNotEmpty()) TextButton(onClick = { com.xlollx.songport.net.HostLog.clear(); connectionsVersion++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.th_security_connections_clear))
                    }
                }
            }
        }

        Text(
            ATTRIBUTION,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}
