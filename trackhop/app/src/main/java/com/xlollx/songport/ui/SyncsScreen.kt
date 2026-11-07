package com.xlollx.songport.ui

import androidx.compose.ui.graphics.vector.ImageVector
import com.xlollx.songport.platform.env
import com.xlollx.songport.sync.Freshness
import androidx.lifecycle.compose.LifecycleResumeEffect
import android.text.format.DateUtils
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.semantics
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.providers.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xlollx.songport.Notifications
import com.xlollx.songport.sync.ShazamLibrary
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Delete
import com.xlollx.songport.R
import com.xlollx.songport.data.StoreData
import androidx.compose.material.icons.filled.Favorite
import com.xlollx.songport.sync.SupportPrompt
import com.xlollx.songport.data.Store
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.SyncState
import java.util.UUID
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.xlollx.songport.sync.SyncFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** From this many syncs on, the ideas' small card is no longer shown: the person has found their way. */
private const val IDEAS_STRIP_MAX_SYNCS = 4

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SyncsScreen(
    data: StoreData,
    onEdit: (SyncJob) -> Unit,
    onPreview: (SyncJob) -> Unit,
    onRun: (SyncJob) -> Unit,
    onRunAll: () -> Unit,
    onDelete: (SyncJob) -> Unit,
    onGoToAccounts: () -> Unit,
    onReview: (String) -> Unit = {},
    onOpenTarget: (SyncJob) -> Unit = {},
    onOpenLog: () -> Unit = {},
    onTour: (() -> Unit)? = null,
    onToggleEnabled: (SyncJob) -> Unit = {},
    /** An idea for one service tapped (ServiceIdeas): its page, filled in. */
    onIdea: (Idea) -> Unit = {},
    /** The "Did you know?" pill tapped: the widgets, shortcuts and automation page. */
) {
    val ctx = LocalContext.current
    val running by SyncState.running.collectAsState()
    // Pause asked for, pause reached (the engine waiting), stop asked for: what each running card shows.
    val pausedSet by SyncState.paused.collectAsState()
    val parkedSet by SyncState.parked.collectAsState()
    val stoppingSet by SyncState.stopping.collectAsState()
    // Find and filter: kept across rotation; only in use from FIND_MIN_SYNCS syncs on.
    var filter by rememberSaveable(stateSaver = SyncFilterSaver) { mutableStateOf(SyncFilter()) }
    if (data.jobs.isEmpty()) {
        val anyConnected = remember { Providers.connectors().any { it.requiresAuth && it.isConnected(ctx) } }
        // With a service connected: what one service alone can do, a tap away, under the usual words.
        val primary = remember { ServiceIdeas.connected(ctx).firstOrNull() }
        if (anyConnected && primary != null) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 100.dp + LocalBottomBarInset.current),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.Sync, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.th_empty_syncs_title), style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text(
                    stringResource(R.string.th_empty_syncs_body_connected), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                ServiceIdeasCard(primary, onIdea = onIdea)
                if (onTour != null) TextButton(onClick = onTour, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.th_tour_offer)) }
            }
        } else if (anyConnected) {
            EmptyState(
                Icons.Filled.Sync, stringResource(R.string.th_empty_syncs_title), stringResource(R.string.th_empty_syncs_body_connected),
                secondaryLabel = stringResource(R.string.th_tour_offer), onSecondary = onTour,
            )
        } else {
            EmptyState(
                Icons.Filled.Sync, stringResource(R.string.th_empty_syncs_title), stringResource(R.string.th_empty_syncs_body),
                stringResource(R.string.th_empty_syncs_cta_accounts), onGoToAccounts,
                secondaryLabel = stringResource(R.string.th_tour_offer), onSecondary = onTour,
            )
        }
        return
    }
    val lastRun = data.jobs.maxOfOrNull { it.lastRunEpoch } ?: 0L
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Moving a card (its menu's Move up/down): the list keeps its scroll position, so only the two
    // cards trade places. Left alone, the list would follow the card at its top by key, and a card
    // moved above it would make the whole list jump.
    val moveAnchor = remember { arrayOfNulls<Pair<Int, Int>>(1) }
    val order = remember(data.jobs) { data.jobs.map { it.id } }
    val lastOrder = remember { arrayOf(order) }
    SideEffect {
        if (order != lastOrder[0]) {
            moveAnchor[0]?.let { (i, o) -> listState.requestScrollToItem(i, o) }
            moveAnchor[0] = null
            lastOrder[0] = order
        }
    }
    val onMove: (SyncJob, Int, Boolean) -> Unit = { job, to, follow ->
        val from = data.jobs.indexOfFirst { it.id == job.id }
        val here = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == job.id }?.index
        // A card moved to a position by number, to the top of the list, or up from the top of the
        // screen is followed there; otherwise the screen stays where it is.
        val followIt = here != null && from >= 0 && (follow || (from > to && (to == 0 || here == listState.firstVisibleItemIndex)))
        moveAnchor[0] = if (followIt) (here!! + (to - from)).coerceAtLeast(0) to 0
        else listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        Store.get(ctx).moveJob(job.id, to)
    }
    // Hold a card and drag it: the order while dragging lives here, and goes to the store when the
    // card is let go (kept until the store's list shows it, so the cards never jump back meanwhile).
    var dragOrder by remember { mutableStateOf<List<String>?>(null) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // The store's order as it is now: the drag callbacks below can outlive the composition they were
    // made in, and an order captured then is stale once a card has moved since (a drop back to that
    // old order was taken for "no change" and the card snapped back).
    val orderNow = { Store.get(ctx).data.jobs.map { it.id } }
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val o = (dragOrder ?: orderNow()).toMutableList()
        val a = o.indexOf(from.key as? String); val b = o.indexOf(to.key as? String)
        if (com.xlollx.songport.BuildConfig.DEBUG) android.util.Log.d("Reorder", "move ${from.key}@${from.index} -> ${to.key}@${to.index} ($a -> $b)")
        if (a >= 0 && b >= 0) {
            // A lazy list keeps its first visible item in view by key: when that item is one of the two
            // swapping (the card half under the pill at the end of the list), the list would follow it,
            // the card under the finger would jump and the drop could land back where it started.
            // Held by position instead, the list stays still and only the cards change places.
            val first = listState.firstVisibleItemIndex
            if (from.index == first || to.index == first) listState.requestScrollToItem(first, listState.firstVisibleItemScrollOffset)
            o.add(b, o.removeAt(a)); dragOrder = o
        }
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentFrequentTick)
    }
    // Any change of order while nothing is dragged (a move from the menu, the store's own list after a
    // drop) ends the drag's own order: the list is the store's again.
    LaunchedEffect(order) { if (!reorder.isAnyItemDragging) dragOrder = null }
    // A sync tapped in a widget (JobFocus): scrolled to and lit up for a moment.
    val focusAsked by JobFocus.request.collectAsState()
    var flashing by remember { mutableStateOf<String?>(null) }
    // "Up to date" is checked, not assumed: each sync's source against its last run, when the list
    // shows and again after a run (FreshnessChecks).
    val toCheck = FreshnessChecks.worthChecking(data.jobs)
    LifecycleResumeEffect(toCheck.map { Triple(it.id, it.lastRunEpoch, it.sourceVersion) }) {
        FreshnessChecks.keepOnly(data.jobs.mapTo(HashSet()) { it.id })
        val check = scope.launch { FreshnessChecks.refresh(ctx.env, toCheck) }
        onPauseOrDispose { check.cancel() }
    }
    val finder = data.jobs.size >= FIND_MIN_SYNCS
    // A colour or a service no sync uses any more has no chip to turn it off: it is dropped.
    val effective = if (finder) filter.pruned(data.jobs) else SyncFilter()
    val runningIds = running.keys
    val shown = remember(data.jobs, data.reports, runningIds, effective) {
        effective.select(data.jobs, data.reports, runningIds) { id -> Providers.byId(id)?.let { it.label(ctx) + " " + it.displayName } ?: id }
    }
    // Pull to refresh = sync all. The indicator stays only while the gesture and the start are under
    // way: the cards show the progress. While syncs run, the pull is off (the pill says how many).
    val pullHaptic = rememberConfirmHaptic()
    var starting by remember { mutableStateOf(false) }
    LaunchedEffect(starting) {
        if (!starting) return@LaunchedEffect
        delay(400)
        withTimeoutOrNull(1500) { SyncState.running.first { it.isNotEmpty() } }
        starting = false
    }
    val pullOff = running.isNotEmpty() && !starting
    // The pill's place in the list (after the hero and, when shown, the finder) and its height once laid out.
    val runAllIndex = if (finder) 2 else 1
    var pillHeight by remember { mutableIntStateOf(0) }
    // Pinned once the list has scrolled past its place: the cards are then passing beneath it.
    val pillPinned by remember(runAllIndex) {
        derivedStateOf { listState.firstVisibleItemIndex > runAllIndex || (listState.firstVisibleItemIndex == runAllIndex && listState.firstVisibleItemScrollOffset > 0) }
    }
    val pullGuard = remember { PullGuard() }
    SideEffect { pullGuard.blocked = pullOff }
    PullToRefreshBox(
        isRefreshing = starting,
        onRefresh = { if (!pullOff) { pullHaptic(); starting = true; onRunAll() } },
        modifier = Modifier.fillMaxSize(),
    ) {
        LaunchedEffect(focusAsked, shown) {
            val id = focusAsked ?: return@LaunchedEffect
            if (data.jobs.none { it.id == id }) { JobFocus.request.value = null; return@LaunchedEffect }
            // Hidden by the search or a filter: the whole list again, then the card.
            if (shown.none { it.id == id }) { filter = SyncFilter(); return@LaunchedEffect }
            kotlinx.coroutines.delay(150)
            // The syncs are the list's last items: the ones before them (summary, notes) come first.
            val index = listState.layoutInfo.totalItemsCount - shown.size + shown.indexOfFirst { it.id == id }
            runCatching { listState.animateScrollToItem(index.coerceAtLeast(0)) }
            JobFocus.request.value = null
            flashing = id
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(pullGuard),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp + LocalBottomBarInset.current),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "hero") {
                val n = data.jobs.size
                val last = if (lastRun > 0) " · " + stringResource(R.string.th_hero_last, formatDate(lastRun)) else ""
                // A few syncs and one service: the ideas for it, small, under the header (in the same
                // item, so the list's positions stay as they are), until closed.
                val single = remember(n) { ServiceIdeas.connected(ctx).singleOrNull() }
                var stripHidden by remember { mutableStateOf(ServiceIdeas.stripDismissed(ctx)) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroHeader(
                        stringResource(R.string.th_hero_title), ctx.resources.getQuantityString(R.plurals.th_hero_jobs, n, n) + last,
                    )
                    if (single != null && n < IDEAS_STRIP_MAX_SYNCS && !stripHidden) {
                        ServiceIdeasStrip(single, onDismiss = { ServiceIdeas.dismissStrip(ctx); stripHidden = true }, onIdea = onIdea)
                    }
                }
            }
            // Under the hero and scrolling with the list, so it never fights the pinned pill below.
            if (finder) item(key = "finder") {
                SyncFinder(data.jobs, effective, shown.size, remember(data.jobs, data.reports, runningIds) { SyncFilter.counts(data.jobs, data.reports, runningIds) }) { filter = it }
            }
            // "Sync all": a pill centred under the header that stays at the top while the list scrolls.
            // While syncs run it says how many, and a tap brings the first running card into view.
            if (data.jobs.size >= 2) stickyHeader(key = "runall") {
                // Index of the first card: hero, finder, pill, then the support card when there is one.
                val firstCard = runAllIndex + 1
                // A card brought into view lands just under the pinned pill, not beneath it.
                val tally = SyncState.tally(running.keys, pausedSet, parkedSet, stoppingSet)
                RunAllPill(tally, pinned = pillPinned, modifier = Modifier.onSizeChanged { pillHeight = it.height }) {
                    // Every running sync paused: the pill is the way to resume them all.
                    if (tally.allPaused) {
                        running.keys.forEach { SyncState.resume(it) }
                        return@RunAllPill
                    }
                    val firstShown = shown.indexOfFirst { running.containsKey(it.id) }
                    val firstAll = data.jobs.indexOfFirst { running.containsKey(it.id) }
                    when {
                        firstShown >= 0 -> scope.launch { listState.animateScrollToItem(firstCard + firstShown, -pillHeight) }
                        // Running, but hidden by the filter: show every card again, then go to it.
                        firstAll >= 0 -> {
                            filter = SyncFilter()
                            scope.launch {
                                withTimeoutOrNull(1000) { snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > firstCard + firstAll } }
                                listState.animateScrollToItem(firstCard + firstAll, -pillHeight)
                            }
                        }
                        else -> onRunAll()
                    }
                }
            }
            // Una volta sola, dopo la prima sync grande: il momento in cui si vede quanto tempo ha fatto risparmiare.
            if (shown.isEmpty()) item(key = "nomatch") { SyncFinderEmpty { filter = SyncFilter() } }
            // Dragging works on the whole list only: with a filter, the cards between are hidden.
            val canDrag = !effective.isActive && shown.size >= 2
            val listed = dragOrder?.let { o -> shown.sortedBy { o.indexOf(it.id) } } ?: shown
            items(listed, key = { it.id }) { job -> ReorderableItem(reorder, key = job.id, enabled = canDrag) { isDragging ->
                val flash = remember(job.id) { androidx.compose.animation.core.Animatable(0f) }
                LaunchedEffect(flashing) {
                    if (flashing == job.id) {
                        repeat(2) {
                            flash.animateTo(1f, androidx.compose.animation.core.tween(220))
                            flash.animateTo(0f, androidx.compose.animation.core.tween(520))
                        }
                        flashing = null
                    }
                }
                val lift by androidx.compose.animation.core.animateDpAsState(if (isDragging) 10.dp else 0.dp, label = "lift")
                Box(Modifier.then(
                    if (flash.value > 0f) Modifier.border(3.dp, MaterialTheme.colorScheme.primary.copy(alpha = flash.value), MaterialTheme.shapes.large) else Modifier,
                ).graphicsLayer {
                    shadowElevation = lift.toPx(); shape = RoundedCornerShape(24.dp); clip = false
                    val k = 1f + 0.03f * (lift.value / 10f); scaleX = k; scaleY = k
                }.pointerInput(job.id, canDrag) {
                    // A card held while partly above the list's top (the one half under the pill at the
                    // end of the list) is brought into view while the finger is still, before the hold
                    // becomes a drag. Left to the drag library, the list is scrolled under the card as
                    // the drag starts and the card stays where the finger is, over the card above: the
                    // first move swapped the two, and the card dragged down came back where it was.
                    if (!canDrag) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val still = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis * 3 / 4) {
                            while (true) {
                                val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                            }
                        } == null
                        if (still) listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == job.id }?.offset?.takeIf { it < 0 }?.let { listState.dispatchRawDelta(it.toFloat()) }
                    }
                }.longPressDraggableHandle(
                    enabled = canDrag,
                    onDragStarted = {
                        if (com.xlollx.songport.BuildConfig.DEBUG) android.util.Log.d("Reorder", "start ${job.id} at $it")
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    },
                    onDragStopped = {
                        val dropped = dragOrder
                        val to = dropped?.indexOf(job.id) ?: -1
                        if (com.xlollx.songport.BuildConfig.DEBUG) android.util.Log.d("Reorder", "stop ${job.id}: to=$to dropped=$dropped now=${orderNow()}")
                        if (to >= 0 && dropped != orderNow()) {
                            Store.get(ctx).moveJob(job.id, to)
                            // The store's list takes over as soon as it shows the move; at the latest, now.
                            scope.launch { kotlinx.coroutines.delay(400); if (!reorder.isAnyItemDragging) dragOrder = null }
                        } else dragOrder = null
                    },
                )) {
                    val run = if (running.containsKey(job.id)) SyncState.runOf(job.id, pausedSet, parkedSet, stoppingSet) else null
                    JobCard(job, data, running[job.id], run, onEdit, onPreview, onRun, onDelete, onReview, onOpenTarget, onOpenLog, onToggleEnabled, onMove)
                }
            } }
        }
    }
}

/** What a sync card's pill says. */
internal enum class CardStatus { RUNNING, PAUSING, PAUSED, DISABLED, NEVER, ERROR, HELD_BACK, INCOMPLETE, NOT_FOUND, EMPTY_SOURCE, OK }

/**
 * The pill for a card, the most important first. Removals on hold come before "Not found: N": a few
 * songs to look up by hand matter less than a target the sync would empty, which waits for the user.
 * A running sync says where it stands ([run]): "Pausing" while its step finishes, "Paused" once the
 * engine waits; a stop in progress still reads as running, the card's text says it is stopping.
 */
internal fun cardStatus(isRunning: Boolean, enabled: Boolean, report: SyncReport?, run: SyncState.Run? = null, radio: Boolean = false): CardStatus = when {
    isRunning && run == SyncState.Run.PAUSED -> CardStatus.PAUSED
    isRunning && run == SyncState.Run.PAUSING -> CardStatus.PAUSING
    isRunning -> CardStatus.RUNNING
    !enabled -> CardStatus.DISABLED
    report == null -> CardStatus.NEVER
    !report.ok -> CardStatus.ERROR
    report.heldBack > 0 -> CardStatus.HELD_BACK
    report.searchInterrupted -> CardStatus.INCOMPLETE
    report.unmatchedTracks.isNotEmpty() -> CardStatus.NOT_FOUND
    // The source listed nothing at all (no loved tracks yet, an emptied playlist): said, not a silent "+0".
    // A radio sync with nothing new on air is normal.
    !radio && report.sourceEmpty -> CardStatus.EMPTY_SOURCE
    else -> CardStatus.OK
}

/**
 * Turns the pull off while syncs run: it takes what the list leaves of a downward drag at its top,
 * so the pull-to-refresh around it never sees it. Everything else passes through untouched.
 */
private class PullGuard : NestedScrollConnection {
    var blocked = false
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (blocked && source == NestedScrollSource.UserInput && available.y > 0f) Offset(0f, available.y) else Offset.Zero
}

/**
 * The "sync all" pill: filled and inviting when idle, a quiet progress state while syncs run.
 *
 * It floats over the list: pinned, the cards scroll beneath it and show around it (an opaque band
 * edge to edge, from 1.3.2 to 1.4.0, looked like a bar across the screen and was dropped on the
 * owner's request). The pill keeps its shadow, and the "?" beside it sits on a small raised circle of
 * its own, so both read over any card.
 */
@Composable
private fun RunAllPill(tally: SyncState.Tally, pinned: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val haptic = rememberConfirmHaptic()
    val runningCount = tally.total
    val busy = runningCount > 0
    val helpShadow by animateDpAsState(if (pinned) 4.dp else 0.dp, label = "runall-help")
    Box(
        modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Balances the "?" on the other side, so the pill stays centred.
            Spacer(Modifier.width(48.dp))
            androidx.compose.material3.Surface(
                onClick = { if (!busy || tally.allPaused) haptic(); onClick() },
                shape = RoundedCornerShape(50),
                color = if (busy) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primary,
                contentColor = if (busy) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 6.dp,
            ) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    // All of them paused: nothing moves, so no spinner; a still pause sign, and a tap resumes them.
                    when {
                        tally.allPaused -> Icon(Icons.Filled.Pause, null, Modifier.size(20.dp))
                        busy -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        else -> Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            tally.allPaused -> pluralStringResource(R.plurals.th_syncs_paused, runningCount, runningCount)
                            tally.somePaused -> stringResource(R.string.th_syncs_running_paused, tally.running, tally.paused)
                            busy -> pluralStringResource(R.plurals.th_syncs_running, runningCount, runningCount)
                            else -> stringResource(R.string.th_sync_all)
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            // Its own small raised circle: over a card scrolling beneath, the "?" still reads.
            androidx.compose.material3.Surface(
                shape = CircleShape,
                color = if (pinned) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                shadowElevation = helpShadow,
            ) { HelpButton(Helps.syncAll) }
        }
    }
}

@Composable
private fun JobCard(
    job: SyncJob, data: StoreData, progress: Progress?, run: SyncState.Run?,
    onEdit: (SyncJob) -> Unit, onPreview: (SyncJob) -> Unit, onRun: (SyncJob) -> Unit, onDelete: (SyncJob) -> Unit,
    onReview: (String) -> Unit = {}, onOpenTarget: (SyncJob) -> Unit = {}, onOpenLog: () -> Unit = {},
    onToggleEnabled: (SyncJob) -> Unit = {},
    onMove: (SyncJob, Int, Boolean) -> Unit = { _, _, _ -> },
) {
    val ctx = LocalContext.current
    val isRunning = run != null
    var colorOpen by remember { mutableStateOf(false) }
    var moveToOpen by remember { mutableStateOf(false) }
    if (moveToOpen) {
        val total = data.jobs.size
        val now = data.jobs.indexOfFirst { it.id == job.id } + 1
        var typed by remember { mutableStateOf(now.toString()) }
        val wanted = typed.toIntOrNull()?.takeIf { it in 1..total }
        AlertDialog(
            onDismissRequest = { moveToOpen = false },
            title = { Text(stringResource(R.string.th_sync_menu_move_to)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.th_move_to_now, now, total), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { v -> typed = v.filter { it.isDigit() }.take(4) },
                        label = { Text(stringResource(R.string.th_move_to_label, total)) },
                        singleLine = true,
                        isError = typed.isNotEmpty() && wanted == null,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = wanted != null && wanted != now, onClick = {
                    moveToOpen = false
                    onMove(job, wanted!! - 1, true)
                }) { Text(stringResource(R.string.th_ok)) }
            },
            dismissButton = { TextButton(onClick = { moveToOpen = false }) { Text(stringResource(R.string.th_cancel)) } },
        )
    }
    if (colorOpen) JobColorDialog(job.color, onDismiss = { colorOpen = false }) { c ->
        // A two-way sync is one sync to the user: both halves take the colour.
        val store = Store.get(ctx)
        data.jobs.filter { it.id == job.id || it.id == job.linkedJobId || it.linkedJobId == job.id }.forEach { store.upsertJob(it.copy(color = c)) }
    }
    var listOpen by remember { mutableStateOf<StatList?>(null) }
    listOpen?.let { l -> StatListSheet(l) { listOpen = null } }
    var confirmDelete by remember { mutableStateOf(false) }
    var liveOpen by remember { mutableStateOf(false) }
    if (liveOpen && isRunning) LiveSheet(job) { liveOpen = false }
    val report = data.reports.firstOrNull { it.id == job.lastReportId }
    val src = Providers.byId(job.source.provider)
    val dst = Providers.byId(job.target.provider)
    val srcName = playlistDisplayName(job.source.playlistId, job.source.playlistName, job.source.provider)
    val dstName = job.target.playlistName.ifBlank { stringResource(R.string.th_editor_new_playlist) }
    val haptic = rememberConfirmHaptic()
    Card(
        // While running, a tap opens the live list of tracks processed so far.
        Modifier.fillMaxWidth().animateContentSize().clickable(enabled = isRunning) { liveOpen = true },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = jobContainer(job.color)),
    ) {
        val accent = jobAccent(job.color)
        // A coloured sync wears its colour on its controls too, as a gradient.
        val controls = jobControlBrush(job.color)
        val ink = jobInk(job.color)
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        Column(
            Modifier.drawBehind {
                // The chosen colour as a band along the card's leading edge, like a calendar's.
                if (accent != null) {
                    val w = 6.dp.toPx()
                    drawRect(accent, topLeft = Offset(if (rtl) size.width - w else 0f, 0f), size = Size(w, size.height))
                }
            }.padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(56.dp).height(36.dp)) {
                    ProviderBadge(src, 34.dp)
                    Box(Modifier.align(Alignment.CenterEnd)) { ProviderBadge(dst, 34.dp) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Written by the user's AI: a small spark before the name, and "add tracks" in the menu.
                        if (job.aiPrompt != null) {
                            Icon(Icons.Filled.AutoAwesome, stringResource(R.string.th_ai_badge_desc), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(4.dp))
                        }
                        // Every song the user Shazams: Shazam's mark, in the same place.
                        if (job.shazam) {
                            Icon(ShazamGlyph, stringResource(R.string.th_shazam_badge_desc), Modifier.size(16.dp), tint = shazamTint())
                            Spacer(Modifier.width(4.dp))
                        }
                        // The songs a radio plays: a radio, tinted like the AI's spark.
                        if (job.radio != null) {
                            Icon(Icons.Filled.Radio, stringResource(R.string.th_radio_badge_desc), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(job.name.ifBlank { job.source.playlistName }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        // A radio sync's source is its station, not the File playlist that keeps what it heard.
                        (job.radio?.let { stringResource(R.string.th_editor_source_radio) + " · " + it.station }
                            ?: ((src?.label(ctx) ?: job.source.provider).let { if (job.source.link != null) stringResource(R.string.th_source_link_label, it) else it } + " · " + srcName)) +
                            (if (job.linkedJobId != null) "  ↔  " else "  →  ") +
                            (dst?.label(ctx) ?: job.target.provider) + " · " + dstName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                // A run without errors can still leave something for the user: the pill says what,
                // and a tap leads where it is handled.
                when (cardStatus(isRunning, job.enabled, report, run, radio = job.radio != null)) {
                    CardStatus.RUNNING -> StatusPill(stringResource(R.string.th_pill_running), Tone.Accent, Icons.Filled.Sync)
                    CardStatus.PAUSING -> StatusPill(stringResource(R.string.th_pill_pausing), Tone.Accent, Icons.Filled.Pause)
                    CardStatus.PAUSED -> StatusPill(stringResource(R.string.th_sync_paused), Tone.Warning, Icons.Filled.Pause)
                    CardStatus.DISABLED -> StatusPill(stringResource(R.string.th_disabled), Tone.Neutral)
                    CardStatus.NEVER -> StatusPill(stringResource(R.string.th_pill_never), Tone.Neutral)
                    CardStatus.ERROR -> StatusPill(stringResource(R.string.th_pill_error), Tone.Error, Icons.Filled.Warning)
                    CardStatus.HELD_BACK -> ActionPill(stringResource(R.string.th_pill_held_back), Tone.Warning) { onPreview(job) }
                    CardStatus.INCOMPLETE -> ActionPill(stringResource(R.string.th_pill_incomplete), Tone.Warning, onClick = onOpenLog)
                    CardStatus.NOT_FOUND -> ActionPill(stringResource(R.string.th_pill_not_found, report!!.unmatchedTracks.size), Tone.Warning) { onReview(report!!.id) }
                    CardStatus.EMPTY_SOURCE -> StatusPill(stringResource(R.string.th_pill_source_empty), Tone.Warning, Icons.Filled.Info)
                    // Up to date only once checked: the source seen unchanged since the last run. Changed:
                    // a tap runs it. Not checkable (no version from the service, offline) or not checked
                    // yet: when it last ran, without the tick.
                    CardStatus.OK -> when (FreshnessChecks.stateOf(job)) {
                        Freshness.State.CURRENT -> StatusPill(stringResource(R.string.th_pill_ok), Tone.Ok, Icons.Filled.CheckCircle)
                        Freshness.State.CHANGED -> ActionPill(stringResource(R.string.th_pill_changed), Tone.Accent, Icons.Filled.Sync) { haptic(); onRun(job) }
                        else -> StatusPill(
                            // Under a minute "0 min. ago" reads oddly: "Just ran" instead.
                            if (System.currentTimeMillis() - job.lastRunEpoch < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.th_pill_just_ran)
                            else stringResource(R.string.th_pill_last_run, relativeTime(job.lastRunEpoch)),
                            Tone.Neutral, Icons.Filled.Schedule,
                        )
                    }
                }
            }
            if (run != null) {
                // Pause and stop act between steps (see SyncState.checkpoint): while a step finishes
                // the card says the request is taken ("Pausing after this step…"), and only once the
                // engine waits does it read "Paused", with a bar that stands still.
                val paused = run == SyncState.Run.PAUSED || run == SyncState.Run.PAUSING
                val stopping = run == SyncState.Run.STOPPING
                Spacer(Modifier.height(12.dp))
                val pct = progress?.percent
                if (run == SyncState.Run.PAUSED) LinearProgressIndicator(
                    // Frozen where the sync stopped, greyed: determinate, never the moving bar.
                    progress = { (pct ?: 0) / 100f }, modifier = Modifier.fillMaxWidth().testTag(PAUSED_BAR_TAG),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                else if (pct != null) LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when (run) {
                            SyncState.Run.STOPPING -> stringResource(R.string.th_sync_stopping) + " · "
                            SyncState.Run.PAUSING -> stringResource(R.string.th_sync_pausing) + " · "
                            SyncState.Run.PAUSED -> stringResource(R.string.th_sync_paused) + " · "
                            SyncState.Run.RUNNING -> ""
                        } + progressText(progress) + (pct?.let { " · $it%" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    )
                    IconButton(enabled = !stopping, modifier = Modifier.size(48.dp), onClick = { if (paused) SyncState.resume(job.id) else SyncState.pause(job.id) }) {
                        Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, stringResource(if (paused) R.string.th_sync_resume else R.string.th_sync_pause))
                    }
                    IconButton(enabled = !stopping, modifier = Modifier.size(48.dp), onClick = { SyncState.stop(job.id) }) { Icon(Icons.Filled.Stop, stringResource(R.string.th_sync_stop)) }
                }
            } else if (report != null && data.settings.newLook && report.ok) {
                Spacer(Modifier.height(14.dp))
                RunStats(
                    job.color, report, data.reports.filter { it.jobId == job.id && !it.partial }.sortedBy { it.startedEpoch }.takeLast(10),
                    onAdded = { listOpen = StatList(StatList.Kind.ADDED, report) },
                    onRemoved = { listOpen = StatList(StatList.Kind.REMOVED, report) },
                    onNotFound = {
                        if (report.unmatchedTracks.isNotEmpty() || report.reviewTracks.isNotEmpty()) onReview(report.id)
                        else listOpen = StatList(StatList.Kind.NOT_FOUND, report)
                    },
                    onTracks = if (job.target.playlistId != null) ({ onOpenTarget(job) }) else null,
                )
            } else if (report != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    Notifications.summary(ctx, report), style = MaterialTheme.typography.bodyMedium,
                    color = if (report.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            // Brani da sistemare a mano: raggiungibili appena la ricerca e' finita, anche mentre la
            // sync sta ancora aggiungendo, e dopo, finche' ne restano.
            val toFix = report?.takeIf { it.jobId == job.id }?.let { it.unmatchedTracks.size + it.reviewTracks.size } ?: 0
            if (toFix > 0 && report != null) {
                Spacer(Modifier.height(6.dp))
                FilledTonalButton(
                    onClick = { onReview(report.id) },
                    modifier = Modifier.fillMaxWidth().let { m -> controls?.let { m.clip(ButtonDefaults.shape).background(it) } ?: m },
                    colors = if (controls != null) ButtonDefaults.filledTonalButtonColors(containerColor = Color.Transparent, contentColor = ink ?: Color.Unspecified)
                    else ButtonDefaults.filledTonalButtonColors(),
                ) {
                    Icon(Icons.Filled.Build, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.th_review_now, toFix))
                }
            }
            // A radio sync while it listens: what is on air (or that it waits for Wi-Fi, or what went wrong).
            if (job.radio != null) RadioCardStatus(job)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                val last = if (job.lastRunEpoch > 0) formatDate(job.lastRunEpoch) else stringResource(R.string.th_last_run_never)
                Text(
                    // A two-way pair never removes, whatever an older version saved. A radio sync shows
                    // its window (days and hours) where the others show their schedule.
                    (job.radio?.let { radioWindowLabel(ctx, it) } ?: timingLabel(job)) +
                        (if (job.archive) " · " + stringResource(R.string.th_job_archive_badge) else if (job.mirrorRemovals && job.linkedJobId == null) " · " + stringResource(R.string.th_editor_mirror) else "") + " · " + last,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                FilledTonalIconButton(
                    onClick = { haptic(); onRun(job) }, enabled = !isRunning,
                    modifier = Modifier.size(48.dp).let { m -> controls?.let { m.clip(CircleShape).background(it, alpha = if (isRunning) 0.4f else 1f) } ?: m },
                    colors = if (controls != null) IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color.Transparent, contentColor = ink ?: Color.Unspecified,
                        disabledContainerColor = Color.Transparent,
                    ) else IconButtonDefaults.filledTonalIconButtonColors(),
                ) {
                    Icon(Icons.Filled.PlayArrow, stringResource(R.string.th_run_now))
                }
                // The menu wears the card's colour: a teal card opens a teal-tinted menu.
                OverflowMenu(
                    listOfNotNull(
                        MenuAction(stringResource(R.string.th_preview), { onPreview(job) }, icon = Icons.Outlined.Visibility),
                        MenuAction(stringResource(R.string.th_edit), { onEdit(job) }, icon = Icons.Outlined.Edit),
                        if (job.enabled) MenuAction(stringResource(R.string.th_sync_menu_disable), { onToggleEnabled(job) }, icon = Icons.Outlined.PauseCircle)
                        else MenuAction(stringResource(R.string.th_sync_menu_enable), { onToggleEnabled(job) }, icon = Icons.Outlined.PlayCircle),
                        MenuAction(stringResource(R.string.th_job_color), { colorOpen = true }, icon = Icons.Outlined.Palette),
                        // The order is the list's, Sync all's and the widgets': cards are dragged (held), or put
                        // at a position typed as a number.
                        // Many syncs: a position typed as a number, quicker than many taps on the arrows.
                        if (data.jobs.size >= 2) MenuAction(stringResource(R.string.th_sync_menu_move_to), { moveToOpen = true }, icon = Icons.Outlined.Numbers) else null,
                        MenuAction(stringResource(R.string.th_delete), { confirmDelete = true }, destructive = true, icon = Icons.Outlined.Delete),
                    ),
                    enabled = !isRunning,
                    container = jobMenuContainer(job.color),
                    iconTint = ink ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    title = job.name.ifBlank { job.source.playlistName },
                )
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.th_delete_confirm_title)) },
            text = { Text(stringResource(R.string.th_delete_confirm_text)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(job) }) { Text(stringResource(R.string.th_yes)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.th_no)) } },
        )
    }
}

/** "3 days ago", "5 min ago": how long since [epoch], in the user's language. */
internal fun relativeTime(epoch: Long, now: Long = System.currentTimeMillis()): String =
    DateUtils.getRelativeTimeSpanString(epoch, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

/** The still, greyed bar of a paused card (for the UI tests). */
internal const val PAUSED_BAR_TAG = "paused-bar"

/** A status pill that leads somewhere: the warning icon, and a tap target as tall as a button. */
@Composable
private fun ActionPill(text: String, tone: Tone, icon: ImageVector = Icons.Filled.Warning, onClick: () -> Unit) {
    StatusPill(
        text, tone, icon,
        Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onClick),
    )
}

/**
 * The original sync of [job]'s two-way pair when [job] is the "↔" half the editor made, else null.
 * Pairs saved before the [SyncJob.twin] flag are told apart by the name the editor gave the twin.
 */
fun twinOriginal(job: SyncJob, jobs: List<SyncJob>): SyncJob? {
    val other = job.linkedJobId?.let { id -> jobs.firstOrNull { it.id == id } } ?: return null
    if (other.twin) return null
    return other.takeIf { job.twin || job.name == other.name + " ↔" }
}

/** Live view of a running sync: what was searched, found, taken from the cache or not found. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveSheet(job: SyncJob, onDismiss: () -> Unit) {
    val all by SyncState.items.collectAsState()
    val running by SyncState.running.collectAsState()
    val items = all[job.id].orEmpty()
    val progress = running[job.id]
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.th_live_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                HelpButton(Helps.cached)
            }
            Text(progressText(progress), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            if (items.isEmpty()) Text(stringResource(R.string.th_live_empty), style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items.size) { i ->
                    val it = items[i]
                    Row(verticalAlignment = Alignment.Top) {
                        val (icon, tint) = when (it.outcome) {
                            SyncState.Outcome.FOUND -> Icons.Filled.CheckCircle to Tones.onSuccessContainer()
                            SyncState.Outcome.CACHED -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.onSurfaceVariant
                            SyncState.Outcome.NOT_FOUND -> Icons.Filled.Warning to MaterialTheme.colorScheme.error
                            SyncState.Outcome.ADDED -> Icons.Filled.CheckCircle to Tones.onSuccessContainer()
                            SyncState.Outcome.ADD_FAILED -> Icons.Filled.Warning to MaterialTheme.colorScheme.error
                            SyncState.Outcome.REMOVED -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp).padding(top = 1.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(it.source, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when (it.outcome) {
                                    SyncState.Outcome.FOUND -> stringResource(R.string.th_live_found, it.result ?: "")
                                    SyncState.Outcome.CACHED -> stringResource(R.string.th_live_cached, it.result ?: "")
                                    SyncState.Outcome.NOT_FOUND -> stringResource(R.string.th_live_not_found)
                                    SyncState.Outcome.ADDED -> stringResource(R.string.th_live_added)
                                    SyncState.Outcome.ADD_FAILED -> stringResource(R.string.th_live_add_failed, it.result ?: "")
                                    SyncState.Outcome.REMOVED -> stringResource(R.string.th_live_removed)
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun progressText(p: Progress?): String = when (p?.step) {
    null -> stringResource(R.string.th_running)
    Progress.Step.FETCH_SOURCE -> stringResource(R.string.th_progress_fetch_source)
    Progress.Step.CREATE_TARGET -> stringResource(R.string.th_progress_create_target)
    Progress.Step.FETCH_TARGET -> stringResource(R.string.th_progress_fetch_target)
    Progress.Step.MATCHING -> stringResource(R.string.th_progress_matching, p?.done ?: 0, p?.total ?: 0) + (p?.label?.let { " · $it" } ?: "")
    Progress.Step.WAITING -> stringResource(R.string.th_progress_waiting, ((p?.total ?: 0) - (p?.done ?: 0)).coerceAtLeast(0))
    Progress.Step.ADDING -> stringResource(R.string.th_progress_adding, p?.total ?: 0) + (p?.label?.let { " · $it" } ?: "")
    Progress.Step.REMOVING -> stringResource(R.string.th_progress_removing, p?.total ?: 0) + (p?.label?.let { " · $it" } ?: "")
    Progress.Step.BACKUP -> stringResource(R.string.th_tools_progress_backup, p?.done ?: 0, p?.total ?: 0)
    Progress.Step.DEDUPE -> stringResource(R.string.th_tools_progress_dedupe, p?.done ?: 0, p?.total ?: 0)
}

// ---------------------------------------------------------------------------------------------
// Editor
// ---------------------------------------------------------------------------------------------

private sealed class Loaded {
    object Loading : Loaded()
    data class Ok(val items: List<Playlist>) : Loaded()
    data class Failed(val message: String) : Loaded()
}

/** A playlist in the saved instance state, as JSON. */
private val PlaylistSaver = androidx.compose.runtime.saveable.Saver<Playlist?, String>(
    save = { it?.let { p -> com.xlollx.songport.net.json.encodeToString(Playlist.serializer(), p) } },
    restore = { runCatching { com.xlollx.songport.net.json.decodeFromString(Playlist.serializer(), it) }.getOrNull() },
)

/** The matching rules in the saved instance state, as JSON. */
private val PolicySaver = androidx.compose.runtime.saveable.Saver<com.xlollx.songport.model.MatchPolicy, String>(
    save = { com.xlollx.songport.net.json.encodeToString(com.xlollx.songport.model.MatchPolicy.serializer(), it) },
    restore = { runCatching { com.xlollx.songport.net.json.decodeFromString(com.xlollx.songport.model.MatchPolicy.serializer(), it) }.getOrNull() },
)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SyncEditorScreen(job: SyncJob, onCancel: () -> Unit, onConnectService: () -> Unit = {}, twinName: String? = null, onSave: (SyncJob, SyncJob?) -> Unit) {
    val ctx = LocalContext.current
    ScreenBackHandler(onCancel)
    val saveHaptic = rememberConfirmHaptic()
    val isNew = job.name.isEmpty()
    // Solo i connettori aggiunti nella scheda Account: uno tolto di li' non deve piu' essere
    // scegliibile, anche se il token o la sessione nell'app plugin sono ancora validi. I servizi gia'
    // usati dal job che si sta modificando restano nell'elenco per non cambiarlo di nascosto.
    val available = remember {
        (Providers.connectors().filter { it.isConnected(ctx) } +
            listOfNotNull(Providers.byId(job.source.provider), Providers.byId(job.target.provider))).distinctBy { it.id }
    }

    // Saved, so a rotation or a fold keeps what was chosen (the job itself travels as JSON).
    var name by rememberSaveable { mutableStateOf(job.name) }
    var srcProvider by rememberSaveable { mutableStateOf(job.source.provider.ifEmpty { available.firstOrNull()?.id ?: "" }) }
    var srcPlaylist by rememberSaveable(stateSaver = PlaylistSaver) { mutableStateOf<Playlist?>(job.source.playlistId?.let { Playlist(it, job.source.playlistName) }) }
    // A pasted link is a source of its own: the field stays, the service's playlist list does not.
    var linkMode by rememberSaveable { mutableStateOf(job.source.link != null) }
    var linkText by rememberSaveable { mutableStateOf(job.source.link ?: "") }
    // Every song the user Shazams: the source is the playlist the Shazam app fills on Spotify or
    // Apple Music, looked up among the connected accounts instead of picked from a list.
    var shazamMode by rememberSaveable { mutableStateOf(job.shazam) }
    var shazamLookup by remember { mutableStateOf<ShazamLookup>(ShazamLookup.Looking) }
    var shazamTry by remember { mutableIntStateOf(0) }
    // Written by the AI: the source is the AI's own list, kept in a file; nothing to pick.
    val aiSource = job.aiPrompt != null && job.source.provider == com.xlollx.songport.providers.LocalFilesProvider.id
    // The songs a radio plays: the station, its days and hours. The source, a File playlist, is made on saving.
    var radioMode by rememberSaveable { mutableStateOf(job.radio != null) }
    var radioStation by rememberSaveable { mutableStateOf(job.radio?.station ?: "") }
    var radioStream by rememberSaveable { mutableStateOf(job.radio?.stream ?: "") }
    var radioHome by rememberSaveable { mutableStateOf(job.radio?.homepage ?: "") }
    // As "1,2,3": a list of days kept across a rotation.
    var radioDays by rememberSaveable { mutableStateOf((job.radio?.days ?: (1..7).toList()).joinToString(",")) }
    var radioStart by rememberSaveable { mutableIntStateOf(job.radio?.startMinute ?: RADIO_DEFAULT_START) }
    var radioEnd by rememberSaveable { mutableIntStateOf(job.radio?.endMinute ?: RADIO_DEFAULT_END) }
    var radioSaving by remember { mutableStateOf(false) }
    val radioName = radioStation.trim().takeIf { radioMode && it.isNotEmpty() }?.let { stringResource(R.string.th_radio_sync_name, it) }
    val editorScope = rememberCoroutineScope()
    // With a single service there is nowhere else to write to: a file on the phone always is, so a
    // first sync is possible before a second service is connected.
    val writable = available.filter { it.canWrite }.let { w ->
        if (w.size < 2 && w.none { it.id == com.xlollx.songport.providers.LocalFilesProvider.id }) w + com.xlollx.songport.providers.LocalFilesProvider else w
    }
    val loginServices = remember(available) { available.count { it.requiresAuth && it.isConnected(ctx) } }
    var dstProvider by rememberSaveable { mutableStateOf(job.target.provider.ifEmpty { writable.getOrNull(1)?.id ?: writable.firstOrNull()?.id ?: "" }) }
    var createNew by rememberSaveable { mutableStateOf(job.target.playlistId == null) }
    var dstPlaylist by rememberSaveable(stateSaver = PlaylistSaver) { mutableStateOf<Playlist?>(job.target.playlistId?.let { Playlist(it, job.target.playlistName) }) }
    var newName by rememberSaveable { mutableStateOf(if (job.target.playlistId == null) job.target.playlistName else "") }
    // A new Shazam sync checks every hour: a song Shazamed in the morning is there by lunch.
    var schedule by rememberSaveable { mutableStateOf(if (isNew && job.shazam) Schedule.HOURLY else job.schedule) }
    // The timing (sync/Timing): how often, at what time, on which days.
    var everyM by rememberSaveable { mutableStateOf(if (isNew && job.shazam) 60 else com.xlollx.songport.sync.Timing.interval(job)) }
    var atStr by rememberSaveable { mutableStateOf(job.at ?: "09:00") }
    var daysCsv by rememberSaveable { mutableStateOf((job.days.ifEmpty { listOfNotNull(legacyWeeklyDay(job) ?: java.time.LocalDate.now().dayOfWeek.value.takeIf { !isNew && com.xlollx.songport.sync.Timing.interval(job) >= 10080 }) }).joinToString(",")) }
    var mirror by rememberSaveable { mutableStateOf(job.mirrorRemovals) }
    var keepOrder by rememberSaveable { mutableStateOf(job.keepOrder) }
    var follows by rememberSaveable { mutableStateOf(job.followsChanges) }
    // Keep every track (an archive): never removes, adds only what is new; Mirror, its "changes by
    // itself" and the order are then out of the question, and hidden.
    var archive by rememberSaveable { mutableStateOf(job.archive) }
    // A source named before its id is known (an idea: "Discover Weekly"): picked from the list once it loads.
    var pickByName by rememberSaveable { mutableStateOf(job.source.playlistId == null && job.source.playlistName.isNotBlank()) }
    var wifiOnly by rememberSaveable { mutableStateOf(job.wifiOnly) }
    var enabled by rememberSaveable { mutableStateOf(job.enabled) }
    var color by rememberSaveable { mutableStateOf(job.color) }
    var bidirectional by rememberSaveable { mutableStateOf(job.linkedJobId != null) }
    var policy by rememberSaveable(stateSaver = PolicySaver) { mutableStateOf(job.policy) }
    var matchingOpen by rememberSaveable { mutableStateOf(job.policy != com.xlollx.songport.model.MatchPolicy()) }
    var error by remember { mutableStateOf<String?>(null) }

    var srcLists by remember { mutableStateOf<Loaded>(Loaded.Loading) }
    var dstLists by remember { mutableStateOf<Loaded>(Loaded.Loading) }

    LaunchedEffect(srcProvider) {
        srcLists = Loaded.Loading
        srcLists = loadPlaylists(ctx, srcProvider, forTarget = false)
    }
    LaunchedEffect(dstProvider) {
        dstLists = Loaded.Loading
        dstLists = loadPlaylists(ctx, dstProvider, forTarget = true)
    }
    // Liked songs to a service that can like: propose "liked songs" as the target instead of a new playlist.
    // Not on the same account: liked songs into the same liked songs would be one list synced onto itself.
    LaunchedEffect(srcPlaylist?.id, dstProvider) {
        val dstObj = Providers.byId(dstProvider)
        val sid = srcPlaylist?.id
        if (MusicProvider.isLibrary(sid) && dstProvider != srcProvider && dstObj?.supportsLibrary(sid, asTarget = true) == true && dstPlaylist == null) {
            createNew = false
            dstPlaylist = Playlist(sid!!, srcPlaylist?.name ?: "")
        }
    }
    LaunchedEffect(srcLists) {
        if (!pickByName || srcProvider != job.source.provider) return@LaunchedEffect
        val items = (srcLists as? Loaded.Ok)?.items ?: return@LaunchedEffect
        pickByName = false
        val wanted = job.source.playlistName.trim()
        items.firstOrNull { it.name.trim().equals(wanted, ignoreCase = true) }?.let { if (srcPlaylist == null) srcPlaylist = it }
    }
    // Job creato da un link condiviso: l'id c'e', il nome ancora no.
    LaunchedEffect(Unit) {
        val sp = srcPlaylist
        if (sp != null && sp.name.isBlank() && !MusicProvider.isLibrary(sp.id)) {
            val p = Providers.byId(srcProvider)
            if (p != null) srcPlaylist = runCatching { p.playlistInfo(ctx, sp.id) }.getOrElse { Playlist(sp.id, p.displayName) }
        }
    }

    // Shazam as the source: its playlist, once found (null: looked up by the effect below).
    fun useShazam(playlist: Playlist? = null) {
        if (!shazamMode && isNew) { schedule = Schedule.HOURLY; everyM = 60; mirror = false }
        shazamMode = true
        linkMode = false
        radioMode = false
        srcPlaylist = playlist
    }
    // A radio as the source: listened to on Wi-Fi unless the user says otherwise, run by its window, never mirrored.
    fun useRadio() {
        if (!radioMode && isNew) { schedule = Schedule.MANUAL; everyM = 0; mirror = false; wifiOnly = true; bidirectional = false; keepOrder = false }
        radioMode = true
        linkMode = false
        shazamMode = false
        srcPlaylist = null
    }
    // Looks for Shazam's playlist on every connected Spotify and Apple Music account, the source's own first.
    LaunchedEffect(shazamMode, shazamTry) {
        if (!shazamMode) return@LaunchedEffect
        shazamLookup = ShazamLookup.Looking
        val hosts = (listOfNotNull(Providers.byId(srcProvider)) + Providers.connectors())
            .filter { ShazamLibrary.canHost(it.serviceId) && it.isConnected(ctx) }.distinctBy { it.id }
        val errors = ArrayList<String>()
        for (p in hosts) {
            val l = loadPlaylists(ctx, p.id, forTarget = false)
            if (l is Loaded.Failed) errors += p.label(ctx) + ": " + l.message
            val found = (l as? Loaded.Ok)?.items?.let { ShazamLibrary.find(it) } ?: continue
            srcProvider = p.id
            srcPlaylist = found
            shazamLookup = ShazamLookup.Found
            return@LaunchedEffect
        }
        shazamLookup = ShazamLookup.Missing(hostConnected = hosts.isNotEmpty(), errors = errors)
    }

    // Save, from the bottom bar: the same checks and the same jobs as before the cards.
    fun saveRadio() {
        if (radioSaving) return
        val station = radioStation.trim()
        val stream = radioStream.trim()
        val dp = dstPlaylist
        if (station.isEmpty() || stream.isEmpty() || dstProvider.isEmpty() || (!createNew && dp == null)) {
            error = ctx.getString(R.string.th_editor_error_incomplete)
            return
        }
        val days = radioDays.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.distinct().sorted()
        if (days.isEmpty()) {
            error = ctx.getString(R.string.th_radio_window_no_day)
            return
        }
        val radio = com.xlollx.songport.model.RadioSource(station, stream, radioHome.trim().ifEmpty { null }, days, radioStart, radioEnd)
        val defaultName = ctx.getString(R.string.th_radio_sync_name, station)
        val target = if (createNew) PlaylistRef(dstProvider, null, newName.ifBlank { defaultName }) else PlaylistRef(dstProvider, dp!!.id, dp.name)
        radioSaving = true
        editorScope.launch {
            try {
                val files = com.xlollx.songport.providers.LocalFilesProvider
                // The songs heard are kept in a File playlist of their own, as on the server: the sync's source.
                val source = job.source.takeIf { job.radio != null && it.provider == files.id && it.playlistId != null }
                    ?: files.createPlaylist(ctx, "Radio - $station", "").let { PlaylistRef(files.id, it.id, it.name) }
                saveHaptic()
                onSave(
                    job.copy(
                        name = name.ifBlank { defaultName }, source = source, target = target,
                        schedule = Schedule.MANUAL, mirrorRemovals = false, wifiOnly = wifiOnly, enabled = enabled, policy = policy,
                        keepOrder = false, color = color, radio = radio, shazam = false, linkedJobId = null, archive = false,
                    ),
                    null,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: ctx.getString(R.string.th_error_generic)
            } finally {
                radioSaving = false
            }
        }
    }

    fun save() {
        if (radioMode) return saveRadio()
        val sp = srcPlaylist
        val dp = dstPlaylist
        if (srcProvider.isEmpty() || dstProvider.isEmpty() || sp == null || (!createNew && dp == null)) {
            error = ctx.getString(R.string.th_editor_error_incomplete)
            return
        }
        // Two playlists of the same account are fine (an archive of Discover Weekly); one playlist onto itself is not.
        if (srcProvider == dstProvider && !createNew && dp?.id == sp.id) {
            error = ctx.getString(R.string.th_error_same_playlist)
            return
        }
        val srcName = sp.name
        // A link whose name could not be read yet: the run reads it and names the new playlist after it.
        val target = if (createNew) PlaylistRef(dstProvider, null, newName.ifBlank { if (linkMode) "" else srcName })
        else PlaylistRef(dstProvider, dp!!.id, dp.name)
        val source = PlaylistRef(srcProvider, sp.id, srcName, link = linkText.trim().takeIf { linkMode && it.isNotBlank() })
        fun timed(j: SyncJob): SyncJob = com.xlollx.songport.sync.Timing.apply(j, everyM, atStr, daysCsv.split(",").mapNotNull { it.trim().toIntOrNull() })
        val main = job.copy(
            name = name.ifBlank { srcName },
            source = source,
            target = target,
            schedule = schedule, mirrorRemovals = mirror && !archive, wifiOnly = wifiOnly, enabled = enabled, policy = policy,
            keepOrder = keepOrder && !archive, color = color, followsChanges = mirror && follows && !archive,
            archive = archive, shazam = shazamMode && !linkMode,
        ).let { timed(it) }
        // The other half of the pair takes only what the two share (schedule, rules, the two playlists
        // swapped); its name, ignore list, reports and order setting stay its own. A pair never removes.
        val partner = job.linkedJobId?.let { Store.get(ctx).job(it) }
        val reverse = if (bidirectional && !createNew && dp != null) {
            if (partner != null) {
                val moved = partner.source != target || partner.target != source
                partner.copy(
                    // Renamed with this sync only when it carries the name the editor gave it.
                    name = if (partner.name == "${job.name} ↔") "${main.name} ↔" else partner.name,
                    // Pairs saved before the flag existed get it here.
                    twin = partner.twin || partner.name == "${job.name} ↔",
                    source = target, target = source,
                    schedule = schedule, mirrorRemovals = false, wifiOnly = wifiOnly, enabled = enabled, policy = policy,
                    linkedJobId = job.id, color = color,
                    sourceVersion = if (moved) null else partner.sourceVersion,
                    targetVersion = if (moved) null else partner.targetVersion,
                ).let { timed(it) }
            } else SyncJob(
                id = UUID.randomUUID().toString(),
                name = "${main.name} ↔",
                source = target,
                target = source,
                schedule = schedule, mirrorRemovals = false, wifiOnly = wifiOnly, enabled = enabled, policy = policy,
                linkedJobId = job.id, color = color, twin = true,
            ).let { timed(it) }
        } else null
        saveHaptic()
        onSave(main.copy(linkedJobId = reverse?.id), reverse)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (isNew) R.string.th_editor_title_new else R.string.th_editor_title_edit)) },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.th_cancel)) } },
            )
        },
        // Save stays in reach at the bottom whatever the length of the form.
        bottomBar = {
            androidx.compose.material3.Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(52.dp)) { Text(stringResource(R.string.th_cancel)) }
                        Button(onClick = { save() }, modifier = Modifier.weight(1f).height(52.dp).tourTarget(TourTarget.EDITOR_SAVE)) { Text(stringResource(R.string.th_save)) }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Opened from the "↔" card: its settings follow this sync, so this is where they change.
            if (twinName != null) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Text(
                        stringResource(R.string.th_editor_twin_note, twinName), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(16.dp),
                    )
                }
            }
            // Nothing to sync from yet, or only one service to sync between: say so, with the way to connect one.
            if (loginServices == 0) {
                ConnectServiceCard(stringResource(R.string.th_no_connected_providers), onConnectService)
            } else if (loginServices == 1) {
                ConnectServiceCard(stringResource(R.string.th_editor_one_service_hint), onConnectService, help = Helps.oneService)
            }
            EditorStep(1, stringResource(R.string.th_editor_source), Modifier.tourTarget(TourTarget.EDITOR_SOURCE), help = { HelpButton(Helps.library, Helps.link, label = stringResource(R.string.th_editor_source)) }) {
                if (aiSource) {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Filled.AutoAwesome, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(stringResource(R.string.th_editor_source_ai), style = MaterialTheme.typography.titleSmall)
                                job.aiPrompt?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                Text(stringResource(R.string.th_editor_source_ai_desc, job.source.playlistName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else if (job.radio != null) {
                    // A radio sync stays one: its station is shown, its days and hours change below.
                    RadioStationCard(radioStation, radioStream, onChange = null)
                } else {
                    // Il servizio di un link incollato puo' non essere fra quelli collegati (playlist pubblica).
                    val srcProviders = (available + listOfNotNull(Providers.byId(srcProvider))).distinctBy { it.id }
                    ProviderPicker(srcProviders, if (linkMode || shazamMode || radioMode) "" else srcProvider, link = true, linkSelected = linkMode,
                        onLink = { if (!linkMode) { linkMode = true; shazamMode = false; radioMode = false; srcPlaylist = null } },
                        shazam = true, shazamSelected = shazamMode, onShazam = { if (!shazamMode) useShazam() },
                        radio = true, radioSelected = radioMode, onRadio = { useRadio() },
                    ) { srcProvider = it; srcPlaylist = null; linkMode = false; shazamMode = false; radioMode = false }
                    if (radioMode) {
                        if (radioStream.isEmpty()) RadioStationPicker { st ->
                            radioStation = st.name
                            radioStream = st.stream
                            radioHome = st.homepage
                        } else RadioStationCard(radioStation, radioStream) { radioStream = ""; radioStation = ""; radioHome = "" }
                    } else if (shazamMode) {
                        ShazamSource(
                            shazamLookup, srcProvider, srcPlaylist,
                            onRetry = { shazamTry++ },
                            onConnect = onConnectService,
                        )
                    } else if (linkMode) {
                        LinkImportField(initial = linkText, keep = true) { providerId, playlist, text ->
                            srcProvider = providerId
                            srcPlaylist = playlist
                            linkText = text
                        }
                        srcPlaylist?.let { sp ->
                            Text((Providers.byId(srcProvider)?.label(ctx) ?: srcProvider) + " · " + playlistDisplayName(sp.id, sp.name, srcProvider), style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        PlaylistPicker(srcLists, srcPlaylist, label = stringResource(R.string.th_editor_playlist), provider = srcProvider) { srcPlaylist = it }
                        // A playlist that changes by itself (Discover Weekly, a daylist, a chart): replace or keep everything, said here.
                        val changing = srcPlaylist?.let { com.xlollx.songport.sync.ChangingPlaylists.isSelfChanging(Providers.byId(srcProvider)?.serviceId, it.name) } == true
                        if (changing) ChangesChoice(
                            replace = mirror && !archive, archive = archive,
                            onReplace = { archive = false; mirror = true; follows = true; bidirectional = false },
                            onArchive = { archive = true; mirror = false; follows = false; keepOrder = false },
                        )
                        // Spotify's own playlists through the official connection: empty by Spotify's rule, said plainly.
                        if (Providers.byId(srcProvider)?.serviceId == "spotify" && com.xlollx.songport.sync.ChangingPlaylists.madeBySpotify(srcPlaylist?.name)) {
                            SpotifyOwnPlaylistNote(quickRoute = com.xlollx.songport.providers.BridgePlugin.installed(ctx), onConnect = onConnectService)
                        }
                        // The playlist Shazam fills on Spotify or Apple Music: one tap makes this a Shazam sync.
                        val shazam = (srcLists as? Loaded.Ok)?.items?.takeIf { ShazamLibrary.canHost(Providers.byId(srcProvider)?.serviceId.orEmpty()) }?.let { ShazamLibrary.find(it) }
                        if (shazam != null) {
                            androidx.compose.material3.AssistChip(
                                onClick = { useShazam(shazam) },
                                label = { Text(stringResource(R.string.th_shazam_editor_suggest, shazam.name)) },
                                leadingIcon = { Icon(ShazamGlyph, null, Modifier.size(18.dp), tint = shazamTint()) },
                            )
                        }
                    }
                }
            }

            EditorStep(2, stringResource(R.string.th_editor_target), Modifier.tourTarget(TourTarget.EDITOR_TARGET), help = { HelpButton(Helps.target, Helps.liked, label = stringResource(R.string.th_editor_target)) }) {
                ProviderPicker(writable, dstProvider) { dstProvider = it; dstPlaylist = null }
                val dstObj = Providers.byId(dstProvider)
                val canCreate = dstObj?.canCreatePlaylists != false
                if (!canCreate && createNew) createNew = false
                SwitchRow(stringResource(R.string.th_editor_new_playlist), null, createNew, enabled = canCreate, help = { HelpButton(Helps.newPlaylist) }) { createNew = it }
                if (!canCreate && dstObj != null) {
                    Text(stringResource(R.string.th_error_create_unsupported, dstObj.displayName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (createNew) {
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.th_editor_new_playlist_name)) },
                        placeholder = { Text(radioName ?: srcPlaylist?.let { playlistDisplayName(it.id, it.name) } ?: "") },
                        supportingText = { Text(stringResource(R.string.th_editor_new_playlist_name_hint)) })
                } else {
                    PlaylistPicker(dstLists, dstPlaylist, label = stringResource(R.string.th_editor_playlist), onlyOwned = true) { dstPlaylist = it }
                }
            }

            EditorStep(3, stringResource(R.string.th_editor_step_options)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.th_editor_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(radioName ?: srcPlaylist?.let { playlistDisplayName(it.id, it.name) } ?: "") })
                Text(stringResource(R.string.th_job_color), style = MaterialTheme.typography.labelLarge)
                JobColorPicker(color) { color = it }
                // A radio sync runs by its window: days and hours instead of a schedule, and no
                // removals, two-way or reordering (its source only grows).
                if (radioMode) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RadioWindowPicker(
                        days = radioDays.split(',').mapNotNull { it.trim().toIntOrNull() },
                        start = radioStart, end = radioEnd,
                        onDays = { radioDays = it.joinToString(",") }, onStart = { radioStart = it }, onEnd = { radioEnd = it },
                    )
                    RadioAlarmHint()
                } else Column(Modifier.tourTarget(TourTarget.EDITOR_SCHEDULE), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HelpLabel(stringResource(R.string.th_editor_schedule)) { HelpButton(Helps.schedule) }
                    // I chip vanno a capo da soli, quante righe servono per lo schermo.
                    TimingPicker(everyM, atStr, daysCsv,
                        onEvery = { everyM = it; schedule = com.xlollx.songport.sync.Timing.legacy(it) },
                        onAt = { atStr = it }, onDays = { daysCsv = it })
                    if (everyM > 0 || daysCsv.isNotBlank()) {
                        // A sync at a time of day waits for that time; one by interval starts soon after saving.
                        Text(stringResource(if (everyM >= 1440 || daysCsv.isNotBlank()) R.string.th_editor_schedule_desc_timed else R.string.th_editor_schedule_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // Two-way and Mirror removals don't go together: a track added on the target could be
                // removed by this sync before the reverse one copies it back.
                if (!radioMode) {
                    if (bidirectional && mirror) mirror = false
                    SwitchRow(
                        stringResource(R.string.th_editor_changes_archive), stringResource(R.string.th_editor_archive_desc), archive,
                        modifier = Modifier.testTag("editor_archive"),
                    ) { archive = it; if (it) { mirror = false; follows = false; keepOrder = false } }
                    // An archive never removes: the removal switches would only say what it does not do.
                    if (!archive) SwitchRow(
                        stringResource(R.string.th_editor_mirror),
                        stringResource(if (bidirectional) R.string.th_editor_mirror_two_way else R.string.th_editor_mirror_desc),
                        mirror, enabled = !bidirectional,
                        modifier = Modifier.tourTarget(TourTarget.EDITOR_MIRROR),
                        // Towards Liked songs, mirroring means unliking: said where the switch is.
                        help = { if (MusicProvider.isLibrary(dstPlaylist?.id) && !createNew) HelpButton(Helps.mirror, Helps.liked, label = stringResource(R.string.th_editor_mirror)) else HelpButton(Helps.mirror) },
                    ) { mirror = it }
                    // Into Liked songs a removal is an unlike, of everything liked there that the source lacks:
                    // said in plain words under the switch, not only behind the "?".
                    if (mirror && !archive && !createNew && dstPlaylist?.id == MusicProvider.LIKED_ID) {
                        Text(
                            stringResource(R.string.th_editor_mirror_liked_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    // A source that renews itself (daylist, Discover Weekly): its big turnovers are not mistakes.
                    if (mirror && !bidirectional && !archive) {
                        SwitchRow(stringResource(R.string.th_editor_follows), stringResource(R.string.th_editor_follows_desc), follows) { follows = it }
                    }
                    // Liked songs on a service that orders them by the second of saving (Spotify): the
                    // switch decides between one a second, in order, and batches at full speed.
                    val likedSpacing = if (!createNew && dstPlaylist?.id == MusicProvider.LIKED_ID) Providers.byId(dstProvider)?.addSpacingMs(MusicProvider.LIKED_ID) ?: 0 else 0
                    if ((Providers.byId(dstProvider)?.canReorder == true || likedSpacing > 0) && !archive) {
                        SwitchRow(stringResource(R.string.th_editor_keep_order), stringResource(R.string.th_editor_keep_order_desc), keepOrder, help = { HelpButton(Helps.keepOrder) }) { keepOrder = it }
                        if (likedSpacing > 0) Text(
                            stringResource(if (keepOrder) R.string.th_editor_keep_order_liked_slow else R.string.th_editor_keep_order_liked_fast),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (keepOrder) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val dstProviderObj = Providers.byId(dstProvider)
                    if (mirror && !archive && dstProviderObj != null && !dstProviderObj.canRemoveTracks) {
                        Text(
                            stringResource(R.string.th_editor_mirror_unsupported, dstProviderObj.displayName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    // Bidirezionale: crea la sync gemella al contrario, senza rimozioni (niente cancellazioni a ping-pong).
                    val srcObj = Providers.byId(srcProvider)
                    // A public link or someone else's playlist can be read, not written: nothing to send back to.
                    val srcOwned = srcPlaylist?.ownedByMe == true && srcObj?.isConnected(ctx) == true
                    // Not into Shazam's own playlist: Shazam alone writes there.
                    val biAllowed = !shazamMode && !createNew && srcObj?.canWrite == true && srcOwned &&
                        srcObj.supportsLibrary(srcPlaylist?.id, asTarget = true) &&
                        dstProviderObj?.supportsLibrary(dstPlaylist?.id, asTarget = false) == true
                    if (!biAllowed && bidirectional) bidirectional = false
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.th_editor_bidirectional))
                            Text(
                                stringResource(
                                    when {
                                        biAllowed -> R.string.th_editor_bidirectional_desc
                                        srcPlaylist != null && !srcOwned -> R.string.th_editor_bidirectional_not_owned
                                        else -> R.string.th_editor_bidirectional_unavailable
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HelpButton(Helps.twoWay)
                        Switch(checked = bidirectional, enabled = biAllowed, onCheckedChange = { bidirectional = it })
                    }
                }
                SwitchRow(stringResource(R.string.th_editor_wifi_only), stringResource(if (radioMode) R.string.th_radio_wifi_desc else R.string.th_editor_wifi_only_desc), wifiOnly) { wifiOnly = it }
                SwitchRow(stringResource(R.string.th_editor_enabled), null, enabled, help = { HelpButton(Helps.enabled) }) { enabled = it }

                // How candidates are chosen: folded, because the defaults suit most syncs.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { matchingOpen = !matchingOpen }) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.th_policy_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.th_policy_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (matchingOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (matchingOpen) {
                    HelpLabel(stringResource(R.string.th_policy_strictness)) { HelpButton(Helps.strictness) }
                    // I chip vanno a capo: in alcune lingue tre etichette non stanno su una riga.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(-1 to R.string.th_policy_loose, 0 to R.string.th_policy_normal, 1 to R.string.th_policy_strict).forEach { (v, res) ->
                            FilterChip(selected = policy.strictness == v, onClick = { policy = policy.copy(strictness = v) }, label = { Text(stringResource(res)) })
                        }
                    }
                    Text(stringResource(R.string.th_policy_strictness_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HelpLabel(stringResource(R.string.th_policy_explicit)) { HelpButton(Helps.explicit) }
                    // I chip vanno a capo: in alcune lingue tre etichette non stanno su una riga.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(0 to R.string.th_policy_explicit_any, 1 to R.string.th_policy_explicit_yes, -1 to R.string.th_policy_explicit_clean).forEach { (v, res) ->
                            FilterChip(selected = policy.explicit == v, onClick = { policy = policy.copy(explicit = v) }, label = { Text(stringResource(res)) })
                        }
                    }
                    SwitchRow(stringResource(R.string.th_policy_same_album), stringResource(R.string.th_policy_same_album_desc), policy.sameAlbum, help = { HelpButton(Helps.sameAlbum) }) { policy = policy.copy(sameAlbum = it) }
                    SwitchRow(stringResource(R.string.th_policy_studio), stringResource(R.string.th_policy_studio_desc), policy.studioOnly) { policy = policy.copy(studioOnly = it) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Under a source that changes by itself: what the target does with each new version, as two plain
 * choices. Neither is chosen until the user picks one (or the sync already had one).
 */
@Composable
private fun ChangesChoice(replace: Boolean, archive: Boolean, onReplace: () -> Unit, onArchive: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("changes_choice"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.th_editor_changes_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(stringResource(R.string.th_editor_changes_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            listOf(
                Triple(replace, R.string.th_editor_changes_replace to R.string.th_editor_changes_replace_desc, onReplace),
                Triple(archive, R.string.th_editor_changes_archive to R.string.th_editor_changes_archive_desc, onArchive),
            ).forEach { (selected, texts, onClick) ->
                Row(
                    Modifier.fillMaxWidth().testTag(if (texts.first == R.string.th_editor_changes_archive) "changes_archive" else "changes_replace").heightIn(min = 48.dp).selectable(selected = selected, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(selected = selected, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(texts.first), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(stringResource(texts.second), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
    }
}

/**
 * The official Spotify connection cannot read the playlists Spotify makes (Discover Weekly, Release
 * Radar, daylist) since February 2026: Spotify's rule, not an error. Where this phone can connect
 * Spotify by the quick route, the way there.
 */
@Composable
private fun SpotifyOwnPlaylistNote(quickRoute: Boolean, onConnect: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("spotify_own_note"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.th_spotify_own_playlists_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            if (quickRoute) {
                Text(stringResource(R.string.th_spotify_own_playlists_quick), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                FilledTonalButton(onClick = onConnect, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.th_empty_syncs_cta_accounts)) }
            }
        }
    }
}

/** A note at the top of the editor with the way to connect a service. */
@Composable
private fun ConnectServiceCard(text: String, onConnect: () -> Unit, help: HelpTopic? = null) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.End)) {
                if (help != null) HelpButton(help, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                FilledTonalButton(onClick = onConnect) { Text(stringResource(R.string.th_empty_syncs_cta_accounts)) }
            }
        }
    }
}

/** One of the editor's three steps: a numbered card (source, target, options). */
@Composable
private fun EditorStep(
    number: Int, title: String, modifier: Modifier = Modifier, help: (@Composable () -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.material3.Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Text(number.toString(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (help != null) help()
            }
            content()
        }
    }
}

/** An editor label with its "?" at the end of the line. */
@Composable
private fun HelpLabel(text: String, help: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        help()
    }
}

/** Where the editor's search for Shazam's playlist stands. */
private sealed class ShazamLookup {
    object Looking : ShazamLookup()
    object Found : ShazamLookup()
    /** Not on any connected account; [hostConnected] false: no Spotify or Apple Music connected at all. */
    data class Missing(val hostConnected: Boolean, val errors: List<String>) : ShazamLookup()
}

/**
 * The source of a Shazam sync: Shazam's playlist once found, with the service it is on and its
 * size; otherwise how to have Shazam keep one (its settings › connect Spotify or Apple Music), a
 * "Check again", and the way to connect one of those two here when neither is.
 */
@Composable
private fun ShazamSource(lookup: ShazamLookup, providerId: String, playlist: Playlist?, onRetry: () -> Unit, onConnect: () -> Unit) {
    val ctx = LocalContext.current
    when {
        lookup is ShazamLookup.Looking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.th_shazam_source_looking), style = MaterialTheme.typography.bodyMedium)
        }
        // Found, or (when it could not be looked up now) the one an existing sync already reads.
        playlist != null -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                Icon(ShazamGlyph, null, Modifier.size(24.dp), tint = shazamTint())
                Spacer(Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.th_shazam_source_title), style = MaterialTheme.typography.titleSmall)
                    val service = Providers.byId(providerId)?.label(ctx) ?: providerId
                    val count = playlist.trackCount.takeIf { it >= 0 }?.let { pluralStringResource(R.plurals.th_card_tracks, it, it) }
                    Text(stringResource(R.string.th_shazam_source_found, playlist.name, service) + (count?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.th_shazam_source_found_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        else -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(ShazamGlyph, null, Modifier.size(24.dp), tint = shazamTint())
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.th_shazam_howto_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Text(stringResource(R.string.th_shazam_howto_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                val hostConnected = (lookup as? ShazamLookup.Missing)?.hostConnected == true
                if (!hostConnected) {
                    Text(stringResource(R.string.th_shazam_howto_connect), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.th_shazam_howto_retry)) }
                    if (!hostConnected) FilledTonalButton(onClick = onConnect) { Text(stringResource(R.string.th_empty_syncs_cta_accounts)) }
                }
            }
        }
    }
    (lookup as? ShazamLookup.Missing)?.errors?.forEach {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

private suspend fun loadPlaylists(ctx: android.content.Context, providerId: String, forTarget: Boolean): Loaded {
    val p = Providers.byId(providerId) ?: return Loaded.Ok(emptyList())
    // Servizio non collegato (es. playlist pubblica da link): niente elenco, nessun errore.
    if (!p.isConnected(ctx)) return Loaded.Ok(emptyList())
    return try {
        val lists = p.playlists(ctx)
        // "Brani preferiti": come origine dove il servizio li espone, come destinazione dove si possono scrivere.
        Loaded.Ok(p.libraryEntries(ctx, asTarget = forTarget) + lists)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        Loaded.Failed(e.message ?: ctx.getString(R.string.th_error_generic))
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ProviderPicker(
    available: List<MusicProvider>, selected: String,
    link: Boolean = false, linkSelected: Boolean = false, onLink: () -> Unit = {},
    shazam: Boolean = false, shazamSelected: Boolean = false, onShazam: () -> Unit = {},
    radio: Boolean = false, radioSelected: Boolean = false, onRadio: () -> Unit = {},
    onSelect: (String) -> Unit,
) {
    val ctx = LocalContext.current
    // A row of tiles that scrolls: with several accounts and personal servers there can be many.
    val linkLabel = stringResource(R.string.th_editor_source_link)
    val shazamLabel = stringResource(R.string.th_editor_source_shazam)
    val radioLabel = stringResource(R.string.th_editor_source_radio)
    val tiles = available.map { ServiceTile(it.id, it.label(ctx), it) } +
        (if (link) listOf(ServiceTile(LINK_KEY, linkLabel, icon = Icons.Filled.Link)) else emptyList()) +
        (if (shazam) listOf(ServiceTile(SHAZAM_KEY, shazamLabel, icon = ShazamGlyph, brandColor = ServiceMarks.SHAZAM_COLOR)) else emptyList()) +
        (if (radio) listOf(ServiceTile(RADIO_KEY, radioLabel, icon = Icons.Filled.Radio)) else emptyList())
    val current = when { linkSelected -> LINK_KEY; shazamSelected -> SHAZAM_KEY; radioSelected -> RADIO_KEY; else -> selected }
    ServiceTiles(tiles, current, { key -> when (key) { LINK_KEY -> onLink(); SHAZAM_KEY -> onShazam(); RADIO_KEY -> onRadio(); else -> onSelect(key) } })
}

/** The "from a link" tile among the services. */
private const val LINK_KEY = "link:"
private const val SHAZAM_KEY = "shazam:"
private const val RADIO_KEY = "radio:"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistPicker(loaded: Loaded, selected: Playlist?, label: String, onlyOwned: Boolean = false, provider: String? = null, onSelect: (Playlist) -> Unit) {
    when (loaded) {
        Loaded.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.width(20.dp).height(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.th_loading))
        }
        is Loaded.Failed -> Text(loaded.message, color = MaterialTheme.colorScheme.error)
        is Loaded.Ok -> {
            var expanded by remember { mutableStateOf(false) }
            val all = if (onlyOwned) loaded.items.filter { it.ownedByMe } else loaded.items
            // Le sezioni della libreria (album, artisti, podcast, ascolti) stanno a parte dalle playlist, dietro
            // un selettore: di default si vedono le playlist. I brani preferiti restano con le playlist, in testa.
            val isSection = { p: Playlist -> MusicProvider.isLibrary(p.id) && p.id != MusicProvider.LIKED_ID }
            val (library, playlists) = all.partition(isSection)
            var showLibrary by remember { mutableStateOf(selected?.let(isSection) ?: false) }
            if (library.isEmpty()) showLibrary = false
            val items = if (showLibrary) library else playlists
            if (library.isNotEmpty()) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = !showLibrary, onClick = { showLibrary = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)) { Text(stringResource(R.string.th_editor_section_playlists)) }
                    SegmentedButton(selected = showLibrary, onClick = { showLibrary = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)) { Text(stringResource(R.string.th_editor_section_library)) }
                }
            }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = selected?.let { playlistDisplayName(it.id, it.name, provider) } ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(if (showLibrary) stringResource(R.string.th_editor_section_library) else label) },
                    placeholder = { Text(stringResource(if (showLibrary) R.string.th_editor_choose_library else R.string.th_editor_choose_playlist)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    items.forEach { p ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(playlistDisplayName(p.id, p.name, provider))
                                    // What a list of a service without playlists is (Last.fm's loved, recent, most played).
                                    if (provider == com.xlollx.songport.providers.LastFmProvider.SERVICE && p.description.isNotBlank()) {
                                        Text(p.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (p.trackCount >= 0) Text(stringResource(R.string.th_tracks_count, p.trackCount), style = MaterialTheme.typography.bodySmall)
                                }
                            },
                            onClick = { onSelect(p); expanded = false },
                        )
                    }
                }
            }
        }
    }
}

/** Campo per incollare il link di una playlist pubblica e usarla come origine. */
@Composable
private fun LinkImportField(initial: String = "", keep: Boolean = false, onResolved: (String, Playlist, String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = null },
            label = { Text(stringResource(R.string.th_link_paste)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(
                enabled = !busy && text.isNotBlank(),
                onClick = {
                    error = null
                    busy = true
                    scope.launch {
                        // Short share links (open.spotify.com/s/…) are followed to the playlist first.
                        val ref = com.xlollx.songport.sync.LinkResolver.resolve(ctx, text)
                        // A Spotify link opens with whichever Spotify route is connected, web or official.
                        val provider = ref?.let { r ->
                            val named = com.xlollx.songport.providers.linkProvider(ctx, r.providerId)
                            named?.takeIf { it.canRead(ctx, r.playlistId) }
                                ?: Providers.connectors().firstOrNull { it.familyName == named?.familyName && it.canRead(ctx, r.playlistId) }
                                ?: named
                        }
                        if (ref == null || provider == null) {
                            error = ctx.getString(R.string.th_link_invalid)
                        } else if (!provider.canRead(ctx, ref.playlistId)) {
                            error = ctx.getString(R.string.th_link_not_connected, provider.displayName)
                        } else {
                            // No name when the service will not say yet: the run fills it in. A
                            // link-only source has nothing else to go on: its error (a private set) shows here.
                            val got = runCatching { provider.playlistInfo(ctx, ref.playlistId) }
                            val failed = got.exceptionOrNull()?.takeIf { provider.reportsLinkErrors(ref.playlistId) && it !is kotlinx.coroutines.CancellationException }
                            if (failed != null) {
                                error = failed.message ?: ctx.getString(R.string.th_error_generic)
                            } else {
                                val info = got.getOrElse { Playlist(ref.playlistId, "", ownedByMe = false) }
                                val pasted = text.trim()
                                if (!keep) text = ""
                                onResolved(provider.id, info, pasted)
                            }
                        }
                        busy = false
                    }
                },
            ) { Text(stringResource(R.string.th_link_use)) }
        }
        if (busy) Text(stringResource(R.string.th_link_resolving), style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

/**
 * The last run in numbers: added, removed, not found, and the playlist's size, large enough to
 * read at a glance; under them the last runs as a strip of marks (filled: ok, red: failed), oldest
 * on the left, so a sync that keeps failing shows before anyone opens the log.
 */
@Composable
private fun RunStats(
    color: String?, r: SyncReport, history: List<SyncReport>,
    onAdded: () -> Unit, onRemoved: () -> Unit, onNotFound: () -> Unit, onTracks: (() -> Unit)?,
) {
    val brush = jobControlBrush(color)
    val ink = jobInk(color)
    // Each figure opens what it counts: the tracks added, removed or not found, or the playlist itself.
    // Equal heights: a label that needs two lines (German, large fonts) doesn't make its tile taller than the rest.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat("+${r.added}", stringResource(R.string.th_stat_added), Modifier.weight(1f), brush, ink ?: MaterialTheme.colorScheme.primary, onAdded.takeIf { r.added > 0 })
        Stat("−${r.removed}", stringResource(R.string.th_stat_removed), Modifier.weight(1f), brush, onClick = onRemoved.takeIf { r.removed > 0 })
        Stat("${r.unmatched.size}", stringResource(R.string.th_stat_not_found), Modifier.weight(1f), brush, if (r.unmatched.isNotEmpty()) MaterialTheme.colorScheme.tertiary else null, onNotFound.takeIf { r.unmatched.isNotEmpty() })
        // The tile opens the synced playlist, so it counts that one: what it held before the run, plus
        // and minus this run's changes. A source that replaces its songs (a daylist) without Mirror
        // removals leaves the target larger than the source: +43 on 50 reads 93, not 50.
        val size = if (r.targetCount > 0 || r.added > 0 || r.removed > 0) (r.targetCount + r.added - r.removed).coerceAtLeast(0) else r.sourceCount
        Stat("$size", stringResource(R.string.th_stat_tracks), Modifier.weight(1f), brush, onClick = onTracks)
    }
    if (history.size >= 2) {
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics(mergeDescendants = true) {}) {
            history.forEach { h ->
                Box(
                    Modifier.size(width = 14.dp, height = 6.dp).clip(RoundedCornerShape(3.dp)).background(
                        if (h.ok) (ink ?: MaterialTheme.colorScheme.primary).copy(alpha = if (h.added + h.removed > 0) 1f else 0.4f)
                        else MaterialTheme.colorScheme.error,
                    ),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                pluralStringResource(R.plurals.th_stat_history, history.size, history.size, history.count { !it.ok }),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier, brush: Brush? = null, tint: Color? = null, onClick: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(16.dp))
            .let { if (brush != null) it.background(brush) else it.background(MaterialTheme.colorScheme.surfaceContainerHigh) }
            .let { if (onClick != null) it.clickable(onClickLabel = label, onClick = onClick) else it }
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // A long figure ("+1150", "12345") shrinks to fit its tile instead of losing its last digits.
        val ink = tint ?: MaterialTheme.colorScheme.onSurface
        val big = MaterialTheme.typography.headlineSmall
        androidx.compose.foundation.text.BasicText(
            value,
            style = big.copy(fontWeight = FontWeight.Bold, color = ink, textAlign = TextAlign.Center),
            maxLines = 1,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = big.fontSize, stepSize = 1.sp),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Which list a figure on the card opens, from which run. */
private data class StatList(val kind: Kind, val report: SyncReport) {
    enum class Kind { ADDED, REMOVED, NOT_FOUND }
}

/** The tracks behind a figure: what the last run added, removed or could not find. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatListSheet(list: StatList, onDismiss: () -> Unit) {
    val r = list.report
    val (title, lines, total) = when (list.kind) {
        StatList.Kind.ADDED -> Triple(stringResource(R.string.th_stat_list_added), r.addedTracks.map { it.toString() }, r.added)
        StatList.Kind.REMOVED -> Triple(stringResource(R.string.th_stat_list_removed), r.removedTracks.map { it.toString() }, r.removed)
        StatList.Kind.NOT_FOUND -> Triple(stringResource(R.string.th_stat_list_not_found), r.unmatched, r.unmatched.size)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(formatDate(r.startedEpoch), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            // Reports from before this list existed only kept the count.
            if (lines.isEmpty()) Text(stringResource(R.string.th_stat_list_unavailable), style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(lines.size) { i -> Text(lines[i], style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (lines.isNotEmpty() && total > lines.size) item {
                    Text(stringResource(R.string.th_stat_list_more, total - lines.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
