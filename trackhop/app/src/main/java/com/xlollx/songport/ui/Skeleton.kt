package com.xlollx.songport.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R

/**
 * A light sweeping across the placeholder shapes while a list loads. Still when the phone asks
 * for no animations (animator duration scale 0), like the rest of the app's motion.
 */
@Composable
private fun shimmer(): Brush {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val light = MaterialTheme.colorScheme.surfaceContainerHighest
    val still = android.provider.Settings.Global.getFloat(
        LocalContext.current.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
    ) == 0f
    if (still) return Brush.linearGradient(listOf(base, base))
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "shimmer-x")
    val band = 500f
    val start = -band + x * band * 3
    return Brush.linearGradient(listOf(base, light, base), start = Offset(start, 0f), end = Offset(start + band, band / 4))
}

/**
 * The shape of a list of tracks or playlists before it arrives: a leading square (checkbox or
 * cover) and two lines of varied length, so the page has its final look from the first frame.
 */
@Composable
fun SkeletonRows(count: Int = 12, leading: Dp = 22.dp, modifier: Modifier = Modifier) {
    val brush = shimmer()
    val loading = stringResource(R.string.th_loading)
    Column(modifier.fillMaxWidth().semantics { contentDescription = loading }) {
        repeat(count) { i ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(leading).clip(RoundedCornerShape(6.dp)).background(brush))
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    // Fixed but uneven lengths: real titles are never all the same width.
                    Spacer(Modifier.fillMaxWidth(TITLE[i % TITLE.size]).height(14.dp).clip(RoundedCornerShape(7.dp)).background(brush))
                    Spacer(Modifier.height(8.dp))
                    Spacer(Modifier.fillMaxWidth(SUB[i % SUB.size]).height(10.dp).clip(RoundedCornerShape(5.dp)).background(brush))
                }
            }
        }
    }
}

private val TITLE = floatArrayOf(0.72f, 0.55f, 0.84f, 0.63f, 0.78f, 0.5f, 0.68f)
private val SUB = floatArrayOf(0.42f, 0.6f, 0.35f, 0.52f, 0.46f, 0.38f, 0.57f)
