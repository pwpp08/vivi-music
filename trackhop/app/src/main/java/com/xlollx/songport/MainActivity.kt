package com.xlollx.songport

import com.xlollx.songport.providers.*
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.xlollx.songport.ui.LocalBottomBarInset
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.xlollx.songport.ads.AdBanner
import com.xlollx.songport.auth.AuthEvents
import com.xlollx.songport.auth.LockActivity
import com.xlollx.songport.ads.Ads
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.YouTubeBridgeProvider
import com.xlollx.songport.providers.YouTubeProvider
import com.xlollx.songport.sync.PlaylistLinks
import com.xlollx.songport.sync.Scheduler
import com.xlollx.songport.sync.SyncWorker
import com.xlollx.songport.ui.AccountsScreen
import com.xlollx.songport.ui.Helps
import com.xlollx.songport.ui.tourTarget
import com.xlollx.songport.ui.AddTrackDialog
import com.xlollx.songport.ui.AppTopBar
import com.xlollx.songport.ui.AppTheme
import com.xlollx.songport.ui.BackAwareScreen
import com.xlollx.songport.ui.TabContent
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.text.style.TextOverflow
import com.xlollx.songport.ui.LockScreen
import com.xlollx.songport.ui.LogScreen
import com.xlollx.songport.ui.OnboardingScreen
import com.xlollx.songport.ui.PreviewScreen
import com.xlollx.songport.ui.SetupWizardScreen
import com.xlollx.songport.ui.SettingsScreen
import com.xlollx.songport.ui.SyncEditorScreen
import com.xlollx.songport.ui.TransferScreen
import com.xlollx.songport.ui.PlaylistsScreen
import com.xlollx.songport.ui.LibraryScanScreen
import com.xlollx.songport.ui.UnplayableScreen
import com.xlollx.songport.ui.SyncsScreen
import com.xlollx.songport.ui.twinOriginal
import com.xlollx.songport.ui.ToolsScreen
import com.xlollx.songport.ui.UnmatchedScreen
import com.xlollx.songport.ui.FilesScreen
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

class MainActivity : ComponentActivity() {
    /** Leaving the app: the widgets show the syncs as they were left (added, renamed, removed). */
    override fun onStop() {
        super.onStop()
        com.xlollx.songport.widget.Widgets.refresh(this)
        com.xlollx.songport.automation.Automation.updateShortcuts(this)
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.xlollx.songport.ui.AppLocale.wrap(newBase))
    }

    private val messages = MutableStateFlow<String?>(null)
    private val tabRequests = MutableStateFlow(-1)
    /** A tool a notification asks to open (TOOL_*), once the Tools tab is up. */
    private val toolRequests = MutableStateFlow<String?>(null)
    /** Testo arrivato con "Condividi con Trackhop": contiene (si spera) il link di una playlist. */
    private val sharedText = MutableStateFlow<String?>(null)
    /** Scorciatoia "Nuova sync" dall'icona: apre subito l'editor. */
    private val newSyncRequests = MutableStateFlow(false)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Blocco con impronta/PIN: una volta per processo, cosi' non si ripete a ogni rotazione. */
    private val locked = MutableStateFlow(false)
    private val lockLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) { unlockedThisProcess = true; locked.value = false }
    }

    private fun requestUnlock() = lockLauncher.launch(Intent(this, LockActivity::class.java))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Ads.start(this)
        // Asked for on the first sync run or schedule, not here (see NotificationAsk).
        NotificationAsk.request = { askNotificationPermission() }
        handleIntent(intent)
        if (savedInstanceState == null) com.xlollx.songport.sync.SupportPrompt.opened(Store.get(this))
        if (Store.get(this).data.settings.appLock && !unlockedThisProcess) {
            locked.value = true
            requestUnlock()
        }
        setContent {
            // The colours, light or dark, are the host app's (HostTheme).
            AppTheme {
                // The status and navigation bar icons follow that theme, not only the system's.
                val appDark = com.xlollx.songport.ui.LocalAppDark.current
                androidx.compose.runtime.DisposableEffect(appDark) {
                    enableEdgeToEdge(
                        statusBarStyle = androidx.activity.SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { appDark },
                        navigationBarStyle = androidx.activity.SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { appDark },
                    )
                    onDispose {}
                }
                val isLocked by locked.collectAsState()
                if (isLocked) LockScreen { requestUnlock() } else Box(Modifier.fillMaxSize()) {
                    MainScreen(messages, tabRequests, sharedText, newSyncRequests, toolRequests)
                    // The guided first sync, over every screen of the app; nothing unless started.
                    com.xlollx.songport.ui.TourOverlay()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A radio sync whose window is open listens from here if it could not start in the background.
        runCatching { com.xlollx.songport.sync.RadioPlan.refresh(this, start = true) }
    }

    override fun onDestroy() {
        if (isFinishing) NotificationAsk.request = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        when (i?.action) {
            ACTION_SYNC_ALL -> { Scheduler.runNow(this, SyncWorker.ALL); messages.value = getString(R.string.th_running); i.action = null }
            ACTION_NEW_SYNC -> { newSyncRequests.value = true; i.action = null }
            // A launcher shortcut of one sync: run it, and show its card.
            ACTION_RUN_SYNC -> {
                i.getStringExtra(com.xlollx.songport.automation.Automation.EXTRA_SYNC)?.let { id ->
                    if (com.xlollx.songport.data.Store.get(this).job(id) != null) {
                        Scheduler.runNow(this, id)
                        messages.value = getString(R.string.th_running)
                        com.xlollx.songport.ui.JobFocus.request.value = id; tabRequests.value = TAB_SYNCS
                    }
                }
                i.action = null
            }
        }
        if (i?.action == Intent.ACTION_SEND) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText.value = it }
            i.action = null
        }
        // A music link opened with Trackhop (the MusicLinkActivity alias, off unless the user turns it on
        // in Settings): the address is handled like a shared one. The app's own callback links stay apart.
        if (i?.action == Intent.ACTION_VIEW) {
            val url = i.dataString
            if (url != null && (com.xlollx.songport.sync.TrackLinks.parse(url) != null || com.xlollx.songport.sync.PlaylistLinks.parse(url) != null)) {
                sharedText.value = url
                i.action = null
            }
        }
        i?.getStringExtra(EXTRA_MESSAGE)?.let { if (it.isNotBlank()) messages.value = it; i.removeExtra(EXTRA_MESSAGE) }
        if (i?.hasExtra(EXTRA_TAB) == true) { tabRequests.value = i.getIntExtra(EXTRA_TAB, 0); i.removeExtra(EXTRA_TAB) }
        i?.getStringExtra(EXTRA_TOOL)?.let { toolRequests.value = it; i.removeExtra(EXTRA_TOOL) }
        // A sync tapped in a widget: the Syncs tab, scrolled to its card, which lights up.
        i?.getStringExtra(EXTRA_FOCUS_JOB)?.let { com.xlollx.songport.ui.JobFocus.request.value = it; tabRequests.value = TAB_SYNCS; i.removeExtra(EXTRA_FOCUS_JOB) }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_TAB = "tab"
        const val EXTRA_TOOL = "tool"
        const val EXTRA_FOCUS_JOB = "focusJob"
        const val TOOL_UNPLAYABLE = "unplayable"
        /** The scrims enableEdgeToEdge() puts behind three-button navigation by default. */
        private val LIGHT_SCRIM = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val DARK_SCRIM = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)
        const val ACTION_SYNC_ALL = "com.xlollx.songport.SYNC_ALL"
        const val ACTION_NEW_SYNC = "com.xlollx.songport.NEW_SYNC"
        const val ACTION_RUN_SYNC = "com.xlollx.songport.RUN_SYNC"
        const val TAB_SYNCS = 0
        const val TAB_ACCOUNTS = 1
        const val TAB_TOOLS = 2
        /** Non e' una scheda: la notifica di esito chiede cosi' la schermata del registro. */
        const val TAB_LOG = 3
        const val TAB_SETTINGS = 4
        /** Discover: in the middle of the bar, but a new number, so the older ones keep meaning what they did. */
        const val TAB_DISCOVER = 5

        @Volatile var unlockedThisProcess = false
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun MainScreen(
    messages: MutableStateFlow<String?>,
    tabRequests: MutableStateFlow<Int>,
    sharedText: MutableStateFlow<String?>,
    newSyncRequests: MutableStateFlow<Boolean>,
    toolRequests: MutableStateFlow<String?> = MutableStateFlow(null),
) {
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    val data by store.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val snackScope = rememberCoroutineScope()
    // What saving a sync does, from the editor or from a card's menu: stored, and its scheduled work
    // brought in line with it (cancelled when it is off or manual).
    fun saveJob(job: SyncJob) {
        val warning = when (val c = com.xlollx.songport.sync.JobConflicts.of(store.data.jobs, job)) {
            is com.xlollx.songport.sync.JobConflicts.Conflict.Duplicate -> ctx.getString(R.string.th_conflict_duplicate, c.other.name)
            is com.xlollx.songport.sync.JobConflicts.Conflict.SharedTarget -> ctx.getString(R.string.th_conflict_shared_target, c.others.joinToString(", ") { it.name })
            null -> null
        }
        store.upsertJob(job)
        Scheduler.apply(ctx, job)
        // Saved all the same: merging, or a copy kept on purpose, is the user's choice; it is said once.
        if (warning != null) snackScope.launch { snackbar.showSnackbar(warning, withDismissAction = true, duration = SnackbarDuration.Long) }
    }
    // The screen on top survives a rotation and a fold: jobs travel as JSON, providers by id.
    var editing by rememberSaveable(stateSaver = JobSaver) { mutableStateOf<SyncJob?>(null) }
    // The "↔" card whose Edit opened its original sync instead: the editor names it in a note.
    var editingTwinId by rememberSaveable { mutableStateOf<String?>(null) }
    var wizardFor by rememberSaveable(stateSaver = ProviderSaver) { mutableStateOf<MusicProvider?>(null) }
    var unmatchedReport by rememberSaveable { mutableStateOf<String?>(null) }
    // Each tab keeps its own state (where it was scrolled to, above all) while a page covers it or
    // another tab is shown: back from a tool or a setting lands where the user left.
    val tabStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var filesOpen by rememberSaveable { mutableStateOf(false) }
    var logOpen by rememberSaveable { mutableStateOf(false) }
    var transferOpen by rememberSaveable { mutableStateOf(false) }
    var backupOpen by rememberSaveable { mutableStateOf(false) }
    var manageProvider by rememberSaveable(stateSaver = ProviderSaver) { mutableStateOf<MusicProvider?>(null) }
    var scanProvider by rememberSaveable(stateSaver = ProviderSaver) { mutableStateOf<MusicProvider?>(null) }
    var unplayableProvider by rememberSaveable(stateSaver = ProviderSaver) { mutableStateOf<MusicProvider?>(null) }
    // A notification asked for a tool: the first connected service the tool works with.
    val toolRequest by toolRequests.collectAsState()
    LaunchedEffect(toolRequest) {
        if (toolRequest == MainActivity.TOOL_UNPLAYABLE) {
            toolRequests.value = null
            tab = MainActivity.TAB_TOOLS
            unplayableProvider = Providers.connectors().firstOrNull { it.detectsUnplayable && it.isConnected(ctx) }
        }
    }
    var previewJob by rememberSaveable(stateSaver = JobSaver) { mutableStateOf<SyncJob?>(null) }
    // A sync's target playlist, opened from the track count on its card.
    var targetJob by rememberSaveable(stateSaver = JobSaver) { mutableStateOf<SyncJob?>(null) }
    // Two playlist links shared at once: "how compatible are we?" in Discover.
    // Saved, so a rotation keeps the comparison; dropped once its page is left.
    var compareLinks by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    // A tool page open in Tools: it brings its own top bar.
    var toolSubpage by remember { mutableStateOf(false) }
    // The same for a card's page in Discover.
    var discoverSubpage by remember { mutableStateOf(false) }
    var discoverHome by remember { mutableIntStateOf(0) }
    var addConnector by rememberSaveable { mutableStateOf(false) }
    // A single track shared from a service's app: which of your playlists it goes to.
    var sharedTrack by remember { mutableStateOf<Pair<MusicProvider, com.xlollx.songport.model.Track>?>(null) }
    // A track link from any music app, headed for any connected service (ShareTrackSheet).
    var sharedLink by remember { mutableStateOf<Pair<String, com.xlollx.songport.sync.TrackLinks.Ref>?>(null) }

    // "New sync" with no service signed in (files and public links do not count) would open an
    // editor with nothing to pick: first the services to connect, then the offer of a first sync.
    var firstSheet by rememberSaveable { mutableStateOf(false) }
    var connectNow by rememberSaveable(stateSaver = ProviderSaver) { mutableStateOf<MusicProvider?>(null) }
    var firstSyncFor by rememberSaveable { mutableStateOf<String?>(null) }
    var firstSyncReady by rememberSaveable { mutableStateOf<String?>(null) }
    fun startNewSync() {
        if (Providers.connectors().any { it.requiresAuth && it.isConnected(ctx) }) editing = newJob() else firstSheet = true
    }
    // An idea for one service (ServiceIdeas), from the Syncs tab or the first sign-in: its page, filled in.
    var dedupeFor by rememberSaveable { mutableStateOf<String?>(null) }
    var wrappedAsked by rememberSaveable { mutableStateOf(false) }
    fun openIdea(idea: com.xlollx.songport.ui.Idea) {
        when (idea.kind) {
            com.xlollx.songport.ui.IdeaKind.BACKUP -> backupOpen = true
            com.xlollx.songport.ui.IdeaKind.DEDUPE -> { dedupeFor = idea.providerId; tab = MainActivity.TAB_TOOLS }
            com.xlollx.songport.ui.IdeaKind.WRAPPED -> { wrappedAsked = true; tab = MainActivity.TAB_DISCOVER }
            // Discover Weekly with the official Spotify: Spotify's rule keeps it out, the quick route reads it.
            com.xlollx.songport.ui.IdeaKind.WEEKLY -> if (idea.needsQuickRoute) { tab = MainActivity.TAB_ACCOUNTS; addConnector = true }
                else com.xlollx.songport.ui.ServiceIdeas.job(ctx, idea)?.let { tab = MainActivity.TAB_SYNCS; editing = it }
            else -> com.xlollx.songport.ui.ServiceIdeas.job(ctx, idea)?.let { tab = MainActivity.TAB_SYNCS; editing = it }
        }
    }
    // The sign-in comes back through the browser (a resume) or the loopback flow (AuthEvents).
    val authVersion by AuthEvents.version.collectAsState()
    var resumes by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { resumes++; onPauseOrDispose { } }
    LaunchedEffect(firstSyncFor, authVersion, resumes) {
        val id = firstSyncFor ?: return@LaunchedEffect
        if (Providers.byId(id)?.isConnected(ctx) == true) { firstSyncFor = null; firstSyncReady = id }
    }

    val newSync by newSyncRequests.collectAsState()
    LaunchedEffect(newSync) { if (newSync) { newSyncRequests.value = false; tab = MainActivity.TAB_SYNCS; startNewSync() } }

    // Link condiviso da un'altra app: apre l'editor con l'origine gia' impostata.
    val shared by sharedText.collectAsState()
    val shareScope = rememberCoroutineScope()
    LaunchedEffect(shared) {
        val text = shared ?: return@LaunchedEffect
        sharedText.value = null
        // Handled in the screen's own scope: clearing the text changes this effect's key, which would
        // cancel it at its first suspension (the "link received" note, a setlist download).
        shareScope.launch {
            val links = Regex("""https?://\S+|spotify:playlist:\S+""").findAll(text).map { it.value }.filter { PlaylistLinks.parse(it) != null }.distinct().toList()
            if (links.size >= 2) { compareLinks = links[0] to links[1]; tab = MainActivity.TAB_DISCOVER; return@launch }
            // A service this build cannot read (SoundCloud on Google Play, unless a plugin serves it) is
            // no link it can use; a SoundCloud short link is followed to its set first.
            val soundCloud = com.xlollx.songport.sync.SoundCloudPublic
            val ref = (PlaylistLinks.parse(text)
                ?: (if (com.xlollx.songport.providers.linkProvider(ctx, soundCloud.SERVICE) != null) soundCloud.pathFromLink(text)?.let { PlaylistLinks.Ref(soundCloud.SERVICE, it) } else null))
                ?.takeIf { com.xlollx.songport.providers.linkProvider(ctx, it.providerId) != null }
            val setlist = com.xlollx.songport.sync.SetlistImport.linkIn(text)
            val trackRef = if (ref == null && setlist == null) com.xlollx.songport.sync.TrackLinks.parse(text) else null
            // Several "Artist - Title" lines shared as text become a file playlist, like a setlist.
            val lines = if (ref == null && setlist == null && trackRef == null && text.lines().count { it.isNotBlank() } >= 2)
                com.xlollx.songport.sync.PlaylistFiles.parseText(text) else emptyList()
            if (trackRef != null) {
                sharedLink = text to trackRef
            } else if (lines.size >= 2) {
                tab = MainActivity.TAB_SYNCS
                val name = ctx.getString(R.string.th_share_file_name) + " " + java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.ROOT).format(java.util.Date())
                val id = LocalFilesProvider.importTracks(ctx, name, lines)
                editing = newJob().copy(name = name, source = PlaylistRef(provider = LocalFilesProvider.id, playlistId = id, playlistName = id))
                snackbar.showSnackbar(ctx.getString(R.string.th_share_text_imported, lines.size, id))
            } else if (setlist != null) {
                // A concert setlist becomes a file playlist, the source of a new sync.
                tab = MainActivity.TAB_SYNCS
                try {
                    val s = com.xlollx.songport.sync.SetlistImport.fetch(setlist)
                    val id = LocalFilesProvider.importTracks(ctx, s.name, s.tracks)
                    editing = newJob().copy(name = s.name, source = PlaylistRef(provider = LocalFilesProvider.id, playlistId = id, playlistName = id))
                    snackbar.showSnackbar(ctx.getString(R.string.th_setlist_imported, s.tracks.size))
                } catch (e: Exception) {
                    snackbar.showSnackbar(e.message ?: ctx.getString(R.string.th_error_generic))
                }
            } else if (ref == null) {
                snackbar.showSnackbar(ctx.getString(R.string.th_share_invalid))
            } else {
                tab = MainActivity.TAB_SYNCS
                // Link di YouTube: se il Bridge e' collegato, la sync nasce gia' su quel connettore.
                val providerId = if (ref.providerId == YouTubeProvider.SERVICE &&
                    Providers.byId(YouTubeBridgeProvider.SERVICE)?.isConnected(ctx) == true
                ) YouTubeBridgeProvider.SERVICE else ref.providerId
                editing = newJob().copy(source = PlaylistRef(provider = providerId, playlistId = ref.playlistId, playlistName = ""))
                snackbar.showSnackbar(ctx.getString(R.string.th_share_received))
            }
        }
    }

    val message by messages.collectAsState()
    LaunchedEffect(message) { message?.let { if (it.isNotBlank()) snackbar.showSnackbar(it); messages.value = null } }
    // Esiti dei login che non tornano da un'Activity (flusso loopback di Google).
    val authMessage by AuthEvents.message.collectAsState()
    LaunchedEffect(authMessage) { authMessage?.let { snackbar.showSnackbar(it); AuthEvents.consume() } }
    val requestedTab by tabRequests.collectAsState()
    LaunchedEffect(requestedTab) {
        if (requestedTab >= 0) {
            // A widget, a notification or a shortcut asked for a tab: the pages left open over the tabs
            // (How to, the log, a tool…) would hide it, so they close. The sync editor and a setup
            // guide stay open: closing them would lose what was being typed.
            previewJob = null; targetJob = null; unmatchedReport = null; filesOpen = false; logOpen = false
            manageProvider = null; scanProvider = null; unplayableProvider = null
            transferOpen = false; backupOpen = false
            // Il registro non e' piu' una scheda: la notifica lo apre sopra le Opzioni, da cui si raggiunge.
            if (requestedTab == MainActivity.TAB_LOG) { tab = MainActivity.TAB_SETTINGS; logOpen = true } else tab = requestedTab
            tabRequests.value = -1
        }
    }

    // The guided first sync: each step brings its screen (the Syncs tab, then a new sync's editor).
    val tourStep by com.xlollx.songport.ui.Tour.step.collectAsState()
    LaunchedEffect(tourStep) {
        when (tourStep?.let { com.xlollx.songport.ui.Tour.steps[it].target }) {
            com.xlollx.songport.ui.TourTarget.NEW_SYNC -> { tab = MainActivity.TAB_SYNCS }
            com.xlollx.songport.ui.TourTarget.EDITOR_SOURCE -> if (editing == null) editing = newJob()
            else -> {}
        }
    }

    if (!data.settings.onboardingDone) {
        // A fresh install has nothing new to hear about: the welcome marks the newest "What's new" as seen.
        OnboardingScreen(
            onDone = { store.updateSettings { it.copy(onboardingDone = true) } },
            onTour = { store.updateSettings { it.copy(onboardingDone = true) }; com.xlollx.songport.ui.Tour.start() },
        )
        return
    }
    sharedTrack?.let { (p, t) ->
        AddTrackDialog(p, t, onClose = { sharedTrack = null }) { messages.value = it }
    }
    sharedLink?.let { (text, ref) ->
        com.xlollx.songport.ui.ShareTrackSheet(
            text = text, ref = ref,
            onClose = { sharedLink = null },
            onAdd = { p, t -> sharedLink = null; sharedTrack = p to t },
            onMessage = { messages.value = it },
            // "Sync my Shazams": the editor opens on Shazam as the source.
            onNewSync = { sharedLink = null; tab = MainActivity.TAB_SYNCS; editing = newJob().copy(shazam = true) },
        )
    }
    if (firstSheet) com.xlollx.songport.ui.FirstSyncSheet(
        onDismiss = { firstSheet = false },
        onPick = { service ->
            firstSheet = false
            // The same connector Accounts → add makes; one already added and not signed in is reused.
            val p = Providers.connectors().firstOrNull { it.serviceId == service.serviceId && it.requiresAuth && !it.isConnected(ctx) }
                ?: Providers.byId(com.xlollx.songport.ui.addConnector(store, service, service.displayName)) ?: service
            firstSyncFor = p.id
            when {
                p.isConnected(ctx) -> { firstSyncFor = null; firstSyncReady = p.id }
                !p.isConfigured(ctx) && p.installUrl != null -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(p.installUrl))) }
                // No key for it in this build: the setup guide, then its card in Accounts to sign in.
                !p.isConfigured(ctx) && p.setupGuide != null -> { tab = MainActivity.TAB_ACCOUNTS; wizardFor = p }
                else -> connectNow = p
            }
        },
        onSeeAll = { firstSheet = false; tab = MainActivity.TAB_ACCOUNTS; addConnector = true },
    )
    connectNow?.let { p ->
        com.xlollx.songport.ui.ConnectDialog(p, onDismiss = { connectNow = null }) {
            connectNow = null
            com.xlollx.songport.auth.AuthFlow.start(ctx, p)
        }
    }
    firstSyncReady?.let { id ->
        val p = Providers.byId(id)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { firstSyncReady = null },
            title = { Text(stringResource(R.string.th_first_sync_ready_title)) },
            text = {
                // The first service, alone: what it can do by itself, before a sync between two.
                val alone = p?.takeIf { com.xlollx.songport.ui.ServiceIdeas.connected(ctx).singleOrNull()?.id == it.id }
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.th_first_sync_ready_body, p?.label(ctx) ?: id))
                    if (alone != null) com.xlollx.songport.ui.ServiceIdeasCard(alone) { firstSyncReady = null; openIdea(it) }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    firstSyncReady = null
                    tab = MainActivity.TAB_SYNCS
                    editing = newJob().copy(source = PlaylistRef(provider = id))
                }) { Text(stringResource(R.string.th_first_sync_create)) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { firstSyncReady = null }) { Text(stringResource(R.string.th_support_prompt_later)) } },
        )
    }
    // One screen on top of the tabs at a time, in this order of precedence. The key names which
    // one is open, so switching animates and the predictive-back preview knows what to move.
    val preview = previewJob
    val wizard = wizardFor
    val unmatched = unmatchedReport
    val manage = manageProvider
    val scan = scanProvider
    val unplayable = unplayableProvider
    val current = editing
    val targetOf = targetJob
    val targetProvider = targetOf?.let { Providers.byId(it.target.provider) }
    val targetId = targetOf?.target?.playlistId
    val overlay: String? = when {
        preview != null -> "preview:" + preview.id
        targetOf != null && targetProvider != null && targetId != null -> "target:" + targetOf.id
        wizard != null -> "wizard:" + wizard.id
        unmatched != null -> "unmatched:$unmatched"
        filesOpen -> "files"
        logOpen -> "log"
        manage != null -> "manage:" + manage.id
        scan != null -> "scan:" + scan.id
        unplayable != null -> "unplayable:" + unplayable.id
        transferOpen -> "transfer"
        backupOpen -> "backup"
        current != null -> "edit:" + current.id
        else -> null
    }
    if (overlay != null) {
        // The tabs' Scaffold (and its snackbar host) is not composed under a full-screen page: its
        // messages ("Link received: choose where to sync it.") show here, above the page's own bottom bar.
        Box(Modifier.fillMaxSize()) {
        BackAwareScreen(overlay) {
            when {
                preview != null -> PreviewScreen(preview, onClose = { previewJob = null }) { j -> Scheduler.runNow(ctx, j.id); previewJob = null }
                targetOf != null && targetProvider != null && targetId != null -> com.xlollx.songport.ui.PlaylistTracksScreen(
                    targetProvider, com.xlollx.songport.model.Playlist(targetId, targetOf.target.playlistName),
                    onClose = { targetJob = null }, onChanged = {},
                )
                wizard != null -> SetupWizardScreen(wizard) { wizardFor = null }
                unmatched != null -> UnmatchedScreen(unmatched) { unmatchedReport = null }
                filesOpen -> FilesScreen { filesOpen = false }
                logOpen -> LogScreen(data, onClose = { logOpen = false }) { unmatchedReport = it }
                manage != null -> PlaylistsScreen(manage) { manageProvider = null }
                scan != null -> LibraryScanScreen(scan) { scanProvider = null }
                unplayable != null -> UnplayableScreen(unplayable) { unplayableProvider = null }
                transferOpen -> TransferScreen(onClose = { transferOpen = false }, onDone = { transferOpen = false; tab = MainActivity.TAB_SYNCS })
                backupOpen -> com.xlollx.songport.ui.BackupScreen(onClose = { backupOpen = false })
                current != null -> SyncEditorScreen(
                    job = current,
                    onCancel = { editing = null },
                    // To Accounts with its service grid open; the sync not saved yet is left.
                    onConnectService = { editing = null; tab = MainActivity.TAB_ACCOUNTS; addConnector = true },
                    twinName = editingTwinId?.let { store.job(it) }?.takeIf { it.linkedJobId == current.id }?.name,
                    onSave = { job, reverse ->
                        val previous = store.job(job.id)
                        saveJob(job)
                        if (reverse != null) {
                            saveJob(reverse)
                        } else previous?.linkedJobId?.let { old ->
                            // Bidirezionale disattivato: la gemella non ha piu' senso.
                            store.deleteJob(old); Scheduler.cancel(ctx, old)
                        }
                        editing = null
                        // A sync just made: what its first run would do, before it does it. Not a
                        // radio sync: its source fills while it listens, there is nothing to show yet.
                        if (previous == null && job.radio == null) previewJob = job
                    },
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp))
        }
        return
    }

    val haze = remember { HazeState() }
    var barHeightPx by remember { mutableIntStateOf(0) }

    val tabs = listOf(
        Triple(R.string.th_tab_syncs, Icons.Filled.Sync, MainActivity.TAB_SYNCS),
        Triple(R.string.th_tab_accounts, Icons.Filled.AccountCircle, MainActivity.TAB_ACCOUNTS),
        Triple(R.string.th_tab_discover, Icons.Filled.AutoAwesome, MainActivity.TAB_DISCOVER),
        Triple(R.string.th_tab_tools, Icons.Filled.Build, MainActivity.TAB_TOOLS),
        Triple(R.string.th_tab_settings, Icons.Filled.Settings, MainActivity.TAB_SETTINGS),
    )

    Scaffold(
        topBar = {
            // An open tool shows its own bar with the way back: no tab header above it.
            if (!(tab == MainActivity.TAB_TOOLS && toolSubpage) && !(tab == MainActivity.TAB_DISCOVER && discoverSubpage)) {
                val label = tabs.firstOrNull { it.third == tab }?.first ?: R.string.th_app_name
                AppTopBar(stringResource(if (tab == MainActivity.TAB_SYNCS) R.string.th_app_name else label)) {
                    // What the parts of a sync card and of an account card mean: one "?" for each list.
                    when (tab) {
                        MainActivity.TAB_SYNCS -> {
                            if (data.jobs.isNotEmpty()) com.xlollx.songport.ui.HelpButton(
                                Helps.status, Helps.route, Helps.figures, Helps.toFix, Helps.runNow, label = stringResource(R.string.th_help_cards_title),
                            )
                        }
                        MainActivity.TAB_ACCOUNTS -> {
                            val topics = listOfNotNull(
                                Helps.connectors, Helps.tags, Helps.disconnect, Helps.removeAccount, Helps.test,
                                Helps.extraActions.takeIf { BridgePlugin.builtIn },
                            )
                            com.xlollx.songport.ui.HelpButton(*topics.toTypedArray(), label = stringResource(R.string.th_tab_accounts))
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // Glass: the bar is translucent and blurs the content scrolling under it (Android 12+;
            // older phones get a plain translucent surface).
            Column(
                Modifier
                    .onSizeChanged { barHeightPx = it.height }
                    .hazeEffect(state = haze, style = HazeMaterials.thin(MaterialTheme.colorScheme.surface)),
            ) {
                HorizontalDivider(thickness = Dp.Hairline, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                AdBanner()
                // At a large font size the five labels no longer fit side by side: only the
                // selected tab keeps its label, the others are their icon (still read out by name).
                val bigText = LocalDensity.current.fontScale > 1.3f
                NavigationBar(containerColor = Color.Transparent, windowInsets = NavigationBarDefaults.windowInsets) {
                    tabs.forEach { (label, icon, index) ->
                        val name = stringResource(label)
                        NavigationBarItem(
                            modifier = if (index == MainActivity.TAB_ACCOUNTS) Modifier.tourTarget(com.xlollx.songport.ui.TourTarget.ACCOUNTS_TAB) else Modifier,
                            selected = tab == index,
                            // Tapping Discover again while a card is open goes back to the list of cards.
                            onClick = { if (tab == index && index == MainActivity.TAB_DISCOVER) discoverHome++; tab = index },
                            icon = { Icon(icon, contentDescription = if (bigText) name else null) },
                            // Five tabs leave a long word ("Einstellungen") little room: it shrinks a
                            // little to fit instead of losing its end.
                            label = {
                                val style = androidx.compose.material3.LocalTextStyle.current
                                androidx.compose.foundation.text.BasicText(
                                    name,
                                    style = style.copy(color = androidx.compose.material3.LocalContentColor.current),
                                    maxLines = 1,
                                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp), maxFontSize = style.fontSize, stepSize = androidx.compose.ui.unit.TextUnit(0.5f, androidx.compose.ui.unit.TextUnitType.Sp)),
                                )
                            },
                            alwaysShowLabel = !bigText,
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            when (tab) {
                MainActivity.TAB_SYNCS -> ExtendedFloatingActionButton(
                    modifier = Modifier.tourTarget(com.xlollx.songport.ui.TourTarget.NEW_SYNC),
                    onClick = { startNewSync() },
                    // The extended button clears its text from the semantics: the icon names it for TalkBack.
                    icon = { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.th_new_sync)) },
                    text = { Text(stringResource(R.string.th_new_sync)) },
                )
                MainActivity.TAB_ACCOUNTS -> ExtendedFloatingActionButton(
                    onClick = { addConnector = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.th_connector_add)) },
                    text = { Text(stringResource(R.string.th_connector_add)) },
                )
            }
        },
    ) { padding ->
        val barInset = with(LocalDensity.current) { barHeightPx.toDp() }
        // Only the top inset: the content runs under the glass bar, and each screen pads its own list by LocalBottomBarInset.
        CompositionLocalProvider(LocalBottomBarInset provides barInset) {
            Box(Modifier.fillMaxSize().hazeSource(state = haze).padding(top = padding.calculateTopPadding())) {
            TabContent(tab) { shown -> tabStates.SaveableStateProvider(shown) { when (shown) {
                MainActivity.TAB_SYNCS -> SyncsScreen(
                    data = data,
                    // The "↔" half of a two-way pair is edited through its original: one set of settings for both.
                    onEdit = { j ->
                        val original = twinOriginal(j, store.data.jobs)
                        editingTwinId = if (original != null) j.id else null
                        editing = original ?: j
                    },
                    onPreview = { previewJob = it },
                    // On or off from the card's menu: the same flag as the editor's "Enabled", saved the same
                    // way. A two-way pair shares it (as since 1.2.7), so both halves follow.
                    onToggleEnabled = { j ->
                        val on = !j.enabled
                        store.data.jobs.filter { it.id == j.id || it.id == j.linkedJobId || it.linkedJobId == j.id }
                            .forEach { saveJob(it.copy(enabled = on)) }
                        val msg = ctx.getString(if (on) R.string.th_sync_enabled_snack else R.string.th_sync_disabled_snack)
                        snackScope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(msg) }
                    },
                    onRun = { Scheduler.runNow(ctx, it.id) },
                    onRunAll = { Scheduler.runNow(ctx, SyncWorker.ALL) },
                    onDelete = { j ->
                        store.deleteJob(j.id); Scheduler.cancel(ctx, j.id)
                        j.linkedJobId?.let { l -> store.deleteJob(l); Scheduler.cancel(ctx, l) }
                    },
                    onGoToAccounts = { tab = MainActivity.TAB_ACCOUNTS },
                    onReview = { unmatchedReport = it },
                    onOpenTarget = { targetJob = it },
                    onOpenLog = { logOpen = true },
                    // Offered on the empty tab until the tour has been taken or skipped once.
                    onTour = if (!data.settings.tourDone) ({ com.xlollx.songport.ui.Tour.start() }) else null,
                    onIdea = { openIdea(it) },
                )
                MainActivity.TAB_ACCOUNTS -> AccountsScreen(snackbar, addConnector, { addConnector = it }, { wizardFor = it }, onManageFiles = { filesOpen = true })
                MainActivity.TAB_DISCOVER -> com.xlollx.songport.ui.DiscoverScreen(
                    snackbar, compareLinks = compareLinks, onCompareClosed = { compareLinks = null }, onSubpage = { discoverSubpage = it }, homeRequests = discoverHome,
                    onGoToAccounts = { tab = MainActivity.TAB_ACCOUNTS }, onBackup = { backupOpen = true },
                    openWrapped = wrappedAsked, onWrappedOpened = { wrappedAsked = false },
                    // "Make a shared playlist": the songs in common, as a file playlist, the source of a new sync.
                    onNewSyncFromFile = { name, id ->
                        tab = MainActivity.TAB_SYNCS
                        editing = newJob().copy(name = name, source = PlaylistRef(provider = LocalFilesProvider.id, playlistId = id, playlistName = id))
                        snackScope.launch { snackbar.showSnackbar(ctx.getString(R.string.th_discover_compat_shared_made)) }
                    },
                )
                MainActivity.TAB_TOOLS -> ToolsScreen(snackbar, onManageFiles = { filesOpen = true }, onTransfer = { transferOpen = true }, onSyncStarted = { tab = MainActivity.TAB_SYNCS }, onManage = { manageProvider = it }, onScan = { scanProvider = it }, onUnplayable = { unplayableProvider = it }, onSubpage = { toolSubpage = it }, onGoToAccounts = { tab = MainActivity.TAB_ACCOUNTS }, onBackup = { backupOpen = true }, openDedupe = dedupeFor, onDedupeOpened = { dedupeFor = null })
                else -> SettingsScreen(data, store, snackbar, onSetup = { wizardFor = it }, onOpenLog = { logOpen = true }, onBackup = { backupOpen = true })
            } } }
            }
        }
    }
}

/** A sync job in the saved instance state, as the JSON the store already writes. */
private val JobSaver = Saver<SyncJob?, String>(
    save = { it?.let { j -> com.xlollx.songport.net.json.encodeToString(SyncJob.serializer(), j) } },
    restore = { runCatching { com.xlollx.songport.net.json.decodeFromString(SyncJob.serializer(), it) }.getOrNull() },
)

/** A provider in the saved instance state, by id. */
private val ProviderSaver = Saver<MusicProvider?, String>(
    save = { it?.id },
    restore = { Providers.byId(it) },
)

private fun newJob() = SyncJob(
    id = UUID.randomUUID().toString(),
    name = "",
    source = PlaylistRef(provider = ""),
    target = PlaylistRef(provider = ""),
)
