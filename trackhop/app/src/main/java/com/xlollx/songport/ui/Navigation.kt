package com.xlollx.songport.ui

import com.xlollx.songport.providers.*
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.coroutines.cancellation.CancellationException

/**
 * How far the back gesture has come on the screen on top (0..1), and from which edge. The
 * screens handle "back" themselves with [ScreenBackHandler]; [BackAwareScreen], around them in
 * MainActivity, reads this to draw the predictive-back preview (the screen shrinks and slides a
 * little as the finger moves, and springs back if the gesture is cancelled).
 */
class BackProgress {
    var value by mutableFloatStateOf(0f)
    /** 0 = left edge, 1 = right edge (BackEventCompat.EDGE_*). */
    var edge by mutableIntStateOf(0)
}

val LocalBackProgress = compositionLocalOf<BackProgress?> { null }

/**
 * Closes the screen on back, with the system's predictive animation on Android 14+ where the
 * gesture is enabled. A one-line replacement for `BackHandler { onClose() }`.
 */
@Composable
fun ScreenBackHandler(onClose: () -> Unit) {
    val progress = LocalBackProgress.current
    PredictiveBackHandler { events ->
        try {
            events.collect { e ->
                progress?.let { it.value = e.progress; it.edge = e.swipeEdge }
            }
            progress?.value = 0f
            onClose()
        } catch (_: CancellationException) {
            progress?.value = 0f
        }
    }
}

/**
 * The frame the overlay screens are drawn in: a soft enter (fade and a slight zoom), and the
 * predictive-back preview driven by [BackProgress]. [key] names the screen, so a change of
 * screen animates and a recomposition of the same screen does not.
 */
@Composable
fun BackAwareScreen(key: Any?, content: @Composable () -> Unit) {
    val progress = remember { BackProgress() }
    AnimatedContent(
        targetState = key,
        transitionSpec = {
            (fadeIn(tween(220)) + scaleIn(initialScale = 0.96f, animationSpec = spring(stiffness = 600f)))
                .togetherWith(fadeOut(tween(120)))
        },
        label = "screen",
    ) { _ ->
        val p = progress.value
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                // Material's predictive back: shrink to 90% at most, slide towards the gesture's edge.
                val scale = 1f - 0.1f * p
                scaleX = scale; scaleY = scale
                translationX = (if (progress.edge == 0) 1f else -1f) * p * size.width * 0.08f
            },
        ) {
            CompositionLocalProvider(LocalBackProgress provides progress) { content() }
        }
    }
}

/** The main tabs cross-fade: no slide, the glass bar stays where it is. */
@Composable
fun TabContent(tab: Int, content: @Composable (Int) -> Unit) {
    AnimatedContent(
        targetState = tab,
        transitionSpec = { (fadeIn(tween(180)) + scaleIn(initialScale = 0.98f, animationSpec = tween(180))).togetherWith(fadeOut(tween(90))) },
        label = "tab",
    ) { t -> content(t) }
}
