package com.xlollx.songport.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.providers.MusicProvider

/** How many services the sheet shows: the most common streaming ones; the rest are a tap away in Accounts. */
private const val FIRST_SYNC_TILES = 6

/** Personal servers and read-only sources: not where a first-time user's music usually is. */
private val NOT_FIRST = setOf("subsonic", "jellyfin", "emby", "plex", "lastfm", "listenbrainz", "csv")

/**
 * "New sync" with no service connected: instead of an editor with nothing to pick, what a sync
 * needs and the services to connect first. Tapping one hands it to [onPick], which starts the same
 * connect flow as Accounts → add; "See all services" goes to Accounts.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FirstSyncSheet(onDismiss: () -> Unit, onPick: (MusicProvider) -> Unit, onSeeAll: () -> Unit) {
    val ctx = LocalContext.current
    // One tile per service family; where a service has two routes, the easier one.
    val tiles = remember {
        addableServices(ctx).groupBy { it.familyName }
            .filter { (_, members) -> members.first().serviceId !in NOT_FIRST }
            .map { (name, members) -> name to (members.firstOrNull { it.route == MusicProvider.Route.EASY } ?: members.first()) }
            .take(FIRST_SYNC_TILES)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.th_first_sync_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.th_first_sync_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                tiles.forEach { (name, p) -> ServiceGridTile(p, name, enabled = true) { onPick(p) } }
            }
            TextButton(onClick = onSeeAll, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.th_first_sync_see_all))
            }
        }
    }
}
