package com.xlollx.songport.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

// ---------------------------------------------------------------------------------------------
// The guided first sync: a few coach marks over the real screens. The screen is dimmed except
// the element a step is about, with one sentence and Next / Skip. Offered, never forced: from the
// end of the onboarding, the empty Syncs tab and How to. An element that cannot be measured (not
// on screen, scrolled away) turns its step into a centred card, so the tour never gets stuck.
// ---------------------------------------------------------------------------------------------

/** The elements a step can point at. Each one is marked with [tourTarget] where it is drawn. */
enum class TourTarget { NEW_SYNC, ACCOUNTS_TAB, EDITOR_SOURCE, EDITOR_TARGET, EDITOR_SCHEDULE, EDITOR_MIRROR, EDITOR_SAVE }

object Tour {
    class Step(val target: TourTarget?, @StringRes val text: Int)

    /** The help audit's first-sync walk, as it is in 1.2.7, in eight sentences. */
    val steps = listOf(
        Step(TourTarget.NEW_SYNC, R.string.th_tour_new_sync),
        Step(TourTarget.ACCOUNTS_TAB, R.string.th_tour_accounts),
        Step(TourTarget.EDITOR_SOURCE, R.string.th_tour_source),
        Step(TourTarget.EDITOR_TARGET, R.string.th_tour_target),
        Step(TourTarget.EDITOR_SCHEDULE, R.string.th_tour_schedule),
        Step(TourTarget.EDITOR_MIRROR, R.string.th_tour_mirror),
        Step(TourTarget.EDITOR_SAVE, R.string.th_tour_save),
        Step(null, R.string.th_tour_results),
    )

    /** The step on screen, or null when no tour runs. One per process: a rotation keeps it. */
    val step = MutableStateFlow<Int?>(null)

    /** Where the marked elements are now, in window coordinates; only kept while a tour runs. */
    internal val bounds = mutableStateMapOf<TourTarget, Rect>()

    fun start() { bounds.clear(); step.value = 0 }

    fun next(store: Store) {
        val i = step.value ?: return
        if (i + 1 < steps.size) step.value = i + 1 else finish(store)
    }

    /** Skipped or done: it is not offered again on its own. */
    fun finish(store: Store) {
        step.value = null
        bounds.clear()
        store.updateSettings { it.copy(tourDone = true) }
    }
}

/**
 * Marks the element [target] names: while a tour runs, its place on screen is kept for the
 * overlay, and when its step comes up it is scrolled into view.
 */
fun Modifier.tourTarget(target: TourTarget): Modifier = composed {
    val step by Tour.step.collectAsState()
    val active = step?.let { Tour.steps.getOrNull(it)?.target } == target
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(active) {
        if (active) {
            // After the screen it lives on has settled (a page opening, a tab switching).
            delay(300)
            runCatching { requester.bringIntoView() }
        }
    }
    DisposableEffect(target) { onDispose { Tour.bounds.remove(target) } }
    bringIntoViewRequester(requester).onGloballyPositioned { c ->
        if (Tour.step.value == null) return@onGloballyPositioned
        val r = c.boundsInWindow()
        if (Tour.bounds[target] != r) Tour.bounds[target] = r
    }
}

/** The tour over everything else in the window: the dimmed screen, the hole, the card. */
@Composable
fun TourOverlay() {
    val current by Tour.step.collectAsState()
    val index = current ?: return
    val step = Tour.steps.getOrNull(index) ?: return
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    val density = LocalDensity.current
    BackHandler { Tour.finish(store) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val pad = with(density) { 8.dp.toPx() }
        // The element, if it is on screen and has a size; a sliver at an edge is no element.
        val hole = step.target?.let { Tour.bounds[it] }
            ?.translate(-origin)
            ?.intersect(Rect(0f, 0f, w, h))
            ?.takeIf { it.width > pad && it.height > pad }
            ?.inflate(pad)
        val scrim = Color.Black.copy(alpha = 0.62f)
        val ring = MaterialTheme.colorScheme.primary
        val radius = with(density) { 16.dp.toPx() }
        val ringWidth = with(density) { 2.dp.toPx() }
        // Drawn as a path with a hole (even-odd): no blend mode, the same on every GPU.
        Canvas(Modifier.fillMaxSize()) {
            if (hole == null) {
                drawRect(scrim)
            } else {
                val path = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, size.width, size.height))
                    addRoundRect(RoundRect(hole, CornerRadius(radius)))
                }
                drawPath(path, scrim)
                drawRoundRect(ring, topLeft = hole.topLeft, size = hole.size, cornerRadius = CornerRadius(radius), style = Stroke(ringWidth))
            }
        }
        // Touches outside the element are caught; the element itself stays usable.
        if (hole == null) {
            TouchShield(Rect(0f, 0f, w, h))
        } else {
            TouchShield(Rect(0f, 0f, w, hole.top))
            TouchShield(Rect(0f, hole.bottom, w, h))
            TouchShield(Rect(0f, hole.top, hole.left, hole.bottom))
            TouchShield(Rect(hole.right, hole.top, w, hole.bottom))
        }
        // The card on the other half of the screen from the element; centred when there is none.
        val align = when {
            hole == null -> Alignment.Center
            hole.center.y > h / 2 -> Alignment.TopCenter
            else -> Alignment.BottomCenter
        }
        TourCard(
            index, step,
            onSkip = { Tour.finish(store) },
            onNext = { Tour.next(store) },
            modifier = Modifier.align(align).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        )
    }
}

/** A transparent area that takes every touch, so the dimmed screen under it cannot be used by mistake. */
@Composable
private fun TouchShield(r: Rect) {
    if (r.width <= 0f || r.height <= 0f) return
    val density = LocalDensity.current
    Box(
        Modifier.offset { IntOffset(r.left.toInt(), r.top.toInt()) }
            .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            },
    )
}

@Composable
private fun TourCard(index: Int, step: Tour.Step, onSkip: () -> Unit, onNext: () -> Unit, modifier: Modifier) {
    val last = index == Tour.steps.lastIndex
    Surface(
        modifier.widthIn(max = 480.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.th_tour_progress, index + 1, Tour.steps.size),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            )
            // Read out as each step arrives.
            Text(
                stringResource(step.text), style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(end = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (!last) TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.th_onboarding_skip)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onNext, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (last) R.string.th_tour_done else R.string.th_onboarding_next))
                }
            }
        }
    }
}

/** "Show me around", at the top of How to: the tour can always be taken again from there. */
@Composable
fun TourOffer(onStart: () -> Unit) {
    androidx.compose.material3.Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            PrefRow(androidx.compose.material.icons.Icons.Filled.Explore, stringResource(R.string.th_tour_offer), stringResource(R.string.th_tour_offer_summary), onClick = onStart)
        }
    }
}
