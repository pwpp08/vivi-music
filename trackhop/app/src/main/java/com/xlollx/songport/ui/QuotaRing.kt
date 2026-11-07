package com.xlollx.songport.ui

import com.xlollx.songport.providers.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.sync.QuotaMeter
import java.text.NumberFormat

/**
 * Anello "quota usata / quota giornaliera". Un solo colore per il valore, traccia neutra,
 * il numero scritto accanto: il colore non e' mai l'unico modo di leggere il dato.
 * Oltre l'80% compare anche un'icona con testo, non solo un cambio di tinta.
 */
@Composable
fun QuotaRing(used: Long, limit: Long, modifier: Modifier = Modifier) {
    val fraction = (used.toDouble() / limit).coerceIn(0.0, 1.0)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = if (fraction >= 0.8) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(64.dp)) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (fraction > 0) drawArc(fill, -90f, (360 * fraction).toFloat(), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
    }
}

/** Scheda quota YouTube: anello, numeri, e la spiegazione che il limite e' di Google. */
@Composable
fun QuotaCard(used: Long, sharedCredentials: Boolean, estimate: Long? = null) {
    val nf = NumberFormat.getIntegerInstance()
    val limit = QuotaMeter.YOUTUBE_DAILY
    val remaining = (limit - used).coerceAtLeast(0)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        QuotaRing(used, limit)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.th_quota_used, nf.format(used), nf.format(limit)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                HelpButton(Helps.quota)
            }
            Text(
                stringResource(if (sharedCredentials) R.string.th_quota_note_shared else R.string.th_quota_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (estimate != null) {
                Spacer(Modifier.height(4.dp))
                val over = estimate > remaining
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (over) Icons.Filled.Warning else Icons.Filled.Info, null,
                        tint = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (over) stringResource(R.string.th_quota_estimate_over, nf.format(estimate), nf.format(remaining))
                        else stringResource(R.string.th_quota_estimate, nf.format(estimate)),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
