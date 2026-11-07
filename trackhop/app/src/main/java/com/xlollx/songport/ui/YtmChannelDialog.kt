package com.xlollx.songport.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.YouTubeBridgeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The channel a YouTube Music account acts as: its own, or one of its brand accounts, where the
 * playlists may live (issue #41). The list comes from the connectors; a tap keeps the choice there,
 * for this account only, and the card refreshes ([onChosen]).
 */
@Composable
fun YtmChannelDialog(p: YouTubeBridgeProvider, onDismiss: () -> Unit, onChosen: () -> Unit) {
    val ctx = LocalContext.current
    var channels by remember { mutableStateOf<List<YouTubeBridgeProvider.Channel>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(p.id) {
        runCatching { withContext(Dispatchers.IO) { p.channels(ctx.env) } }
            .onSuccess { channels = it }
            .onFailure { error = ctx.getString(R.string.th_ytm_channel_failed, it.message ?: it.javaClass.simpleName) }
    }
    // A chosen channel is written to the connectors off the main thread; the dialog closes when it is kept.
    var chosen by remember { mutableStateOf<YouTubeBridgeProvider.Channel?>(null) }
    chosen?.let { c ->
        LaunchedEffect(c.id) {
            saving = true
            runCatching { withContext(Dispatchers.IO) { p.setChannel(ctx.env, c) } }
                .onSuccess { onChosen() }
                .onFailure { error = ctx.getString(R.string.th_ytm_channel_failed, it.message ?: it.javaClass.simpleName); saving = false; chosen = null }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.th_ytm_channel)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.th_ytm_channel_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                val list = channels
                when {
                    error != null -> Text(error!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    list == null -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.width(20.dp).height(20.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.th_loading)) }
                    else -> list.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !saving) { chosen = c }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = c.selected || chosen?.id == c.id, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(c.name, style = MaterialTheme.typography.bodyLarge)
                                val sub = listOfNotNull(c.byline, if (c.id.isEmpty()) stringResource(R.string.th_ytm_channel_own) else null).joinToString(" · ")
                                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
