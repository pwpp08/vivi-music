package com.xlollx.songport.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.ChartsProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.ChangingPlaylists
import java.util.Locale
import java.util.UUID

/**
 * Ready-made things to do with a single music service: no second service needed. Each one opens
 * the right page filled in (a sync's editor with source, target and options set, or a tool), so
 * the person checks and saves. Shown on the empty Syncs tab, after the first service is connected,
 * and as a small card on the Syncs tab while there are few syncs. Only what this build has: the
 * charts, the backup, the duplicates and the Wrapped are on every build.
 */
enum class IdeaKind {
    /** The service's weekly playlist kept whole: Discover Weekly, Discover Mix, New Music Mix (archive mode). */
    WEEKLY,
    /** Every playlist backed up, on a schedule (the Backup page). */
    BACKUP,
    /** Liked songs as a real playlist on the same service, that a link can be sent for. */
    LIKED,
    /** The country's chart as a playlist on the service, the latest version each day. */
    CHART,
    /** Duplicates in a playlist (Tools › Duplicates). */
    DEDUPE,
    /** The Wrapped (Discover). */
    WRAPPED,
}

/**
 * One idea for the service [providerId]; [weekly] names the playlist of a [IdeaKind.WEEKLY].
 * [needsQuickRoute]: the official Spotify connection cannot read Spotify's own playlists (Spotify's
 * rule since February 2026), so the idea says so and leads to connecting Spotify by the quick route.
 */
data class Idea(val kind: IdeaKind, val providerId: String, val weekly: String? = null, val needsQuickRoute: Boolean = false)

object ServiceIdeas {
    /** The signed-in music services (not files, charts or public links). */
    fun connected(ctx: Context): List<MusicProvider> = Providers.connectors().filter { it.requiresAuth && it.isConnected(ctx.env) }

    /**
     * What fits [p]: its weekly playlist where Trackhop can read it, the backup, its liked songs
     * as a playlist where it has both, its country's chart where it takes new playlists, the
     * duplicates where it can remove tracks, the Wrapped.
     */
    fun forProvider(p: MusicProvider, quickRoute: Boolean = false): List<Idea> = buildList {
        val writes = p.canWrite && p.canCreatePlaylists
        ChangingPlaylists.weekly(p.serviceId)?.takeIf { writes }?.let { add(Idea(IdeaKind.WEEKLY, p.id, it)) }
        // The official Spotify alone: Discover Weekly only by the quick route, where this build has one.
        if (p.serviceId == SPOTIFY_OFFICIAL && writes && quickRoute) add(Idea(IdeaKind.WEEKLY, p.id, "Discover Weekly", needsQuickRoute = true))
        add(Idea(IdeaKind.BACKUP, p.id))
        if (writes && p.supportsLikedSongs) add(Idea(IdeaKind.LIKED, p.id))
        if (writes) add(Idea(IdeaKind.CHART, p.id))
        if (p.canRemoveTracks) add(Idea(IdeaKind.DEDUPE, p.id))
        add(Idea(IdeaKind.WRAPPED, p.id))
    }

    private const val SPOTIFY_OFFICIAL = "spotify"

    /**
     * Spotify by the quick route can be connected on this phone (built in, or its plugin installed)
     * and is not yet: only then is the official connection's Discover Weekly idea worth showing.
     */
    fun quickRouteForSpotify(ctx: Context): Boolean =
        com.xlollx.songport.providers.BridgePlugin.installed(ctx) &&
            Providers.connectors().none { it.serviceId == "spotify_bridge" && it.isConnected(ctx.env) }

    /** The Apple Music chart of the user's country, where Apple has one; else Deezer's worldwide chart. */
    fun chartFor(country: String = ChartsProvider.country()): Pair<String, String> {
        val cc = country.lowercase()
        return if (cc.matches(Regex("[a-z]{2}")) && cc in CHART_COUNTRIES) "apple:$cc" to Locale("", cc.uppercase()).getDisplayCountry(Locale.getDefault())
        else "deezer:3155776842" to "Top Worldwide"
    }

    /** The chart an idea uses, as (ChartsProvider playlist id, its name as the Charts source lists it). */
    fun chart(ctx: Context): Pair<String, String> {
        val (id, country) = chartFor()
        return id to if (id.startsWith("apple:")) ctx.getString(R.string.th_charts_apple_name, country) else ctx.getString(R.string.th_charts_deezer_name, country)
    }

    /** The countries Apple's chart feed is offered for (ChartsProvider's list). */
    private val CHART_COUNTRIES = setOf(
        "us", "gb", "it", "de", "fr", "es", "nl", "be", "pl", "se", "no", "dk", "fi", "at", "ch", "ie", "pt",
        "tr", "ru", "br", "mx", "ar", "co", "cl", "ca", "au", "nz", "jp", "kr", "in", "id", "ph", "za", "ng", "eg", "sa", "ae",
    )

    /**
     * The sync an idea makes, for the editor to open (not saved): a new job, name left empty so the
     * editor treats it as new. Null for the ideas that open a page instead.
     */
    fun job(ctx: Context, idea: Idea): SyncJob? {
        val p = idea.providerId
        fun base(source: PlaylistRef, target: PlaylistRef) = SyncJob(id = UUID.randomUUID().toString(), name = "", source = source, target = target)
        return when (idea.kind) {
            // The source by its name: the editor picks it from the service's list once loaded.
            IdeaKind.WEEKLY -> {
                if (idea.needsQuickRoute) return null
                val name = idea.weekly ?: return null
                base(PlaylistRef(p, null, name), PlaylistRef(p, null, ctx.getString(R.string.th_idea_weekly_target, name)))
                    .copy(archive = true, schedule = Schedule.WEEKLY)
            }
            IdeaKind.LIKED -> base(
                PlaylistRef(p, MusicProvider.LIKED_ID, ctx.getString(R.string.th_liked_songs)),
                PlaylistRef(p, null, ctx.getString(R.string.th_idea_liked_target)),
            ).copy(mirrorRemovals = true, schedule = Schedule.DAILY)
            IdeaKind.CHART -> {
                val (id, name) = chart(ctx)
                base(PlaylistRef(ChartsProvider.SERVICE, id, name), PlaylistRef(p, null, name))
                    .copy(mirrorRemovals = true, followsChanges = true, schedule = Schedule.DAILY)
            }
            else -> null
        }
    }

    /** The small card on the Syncs tab: hidden for good once closed. */
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("ideas", Context.MODE_PRIVATE)
    fun stripDismissed(ctx: Context): Boolean = runCatching { prefs(ctx).getBoolean("strip_dismissed", false) }.getOrDefault(false)
    fun dismissStrip(ctx: Context) { runCatching { prefs(ctx).edit().putBoolean("strip_dismissed", true).apply() } }
}

private fun Idea.icon(): ImageVector = when (kind) {
    IdeaKind.WEEKLY -> Icons.Filled.Inventory2
    IdeaKind.BACKUP -> Icons.Filled.Backup
    IdeaKind.LIKED -> Icons.Filled.Favorite
    IdeaKind.CHART -> Icons.AutoMirrored.Filled.TrendingUp
    IdeaKind.DEDUPE -> Icons.Filled.CleaningServices
    IdeaKind.WRAPPED -> Icons.Filled.AutoAwesome
}

@Composable
private fun Idea.title(): String = when (kind) {
    IdeaKind.WEEKLY -> stringResource(R.string.th_idea_weekly_title, weekly.orEmpty())
    IdeaKind.BACKUP -> stringResource(R.string.th_idea_backup_title)
    IdeaKind.LIKED -> stringResource(R.string.th_idea_liked_title)
    IdeaKind.CHART -> stringResource(R.string.th_idea_chart_title)
    IdeaKind.DEDUPE -> stringResource(R.string.th_idea_dedupe_title)
    IdeaKind.WRAPPED -> stringResource(R.string.th_idea_wrapped_title)
}

@Composable
private fun Idea.description(service: String): String = when (kind) {
    IdeaKind.WEEKLY -> if (needsQuickRoute) stringResource(R.string.th_spotify_own_playlists_note) + " " + stringResource(R.string.th_spotify_own_playlists_quick)
        else stringResource(R.string.th_idea_weekly_desc, weekly.orEmpty())
    IdeaKind.BACKUP -> stringResource(R.string.th_idea_backup_desc)
    IdeaKind.LIKED -> stringResource(R.string.th_idea_liked_desc)
    IdeaKind.CHART -> stringResource(R.string.th_idea_chart_desc, ServiceIdeas.chart(LocalContext.current).second, service)
    IdeaKind.DEDUPE -> stringResource(R.string.th_idea_dedupe_desc)
    IdeaKind.WRAPPED -> stringResource(R.string.th_idea_wrapped_desc)
}

/** The ideas for [p] as a list of rows, each a tap away from its page. */
@Composable
fun ServiceIdeasCard(p: MusicProvider, modifier: Modifier = Modifier, onIdea: (Idea) -> Unit) {
    val ctx = LocalContext.current
    val service = p.label(ctx.env)
    Card(modifier.fillMaxWidth().testTag("ideas"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lightbulb, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.th_ideas_title, service), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            }
            Text(
                stringResource(R.string.th_ideas_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            ServiceIdeas.forProvider(p, ServiceIdeas.quickRouteForSpotify(ctx)).forEach { idea ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onIdea(idea) }
                        .padding(horizontal = 16.dp, vertical = 8.dp).testTag("idea_" + idea.kind.name),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(idea.icon(), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(idea.title(), style = MaterialTheme.typography.bodyLarge)
                        Text(idea.description(service), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** The same ideas, small: one line of chips under the Syncs header, with a way to hide them. */
@Composable
fun ServiceIdeasStrip(p: MusicProvider, onDismiss: () -> Unit, onIdea: (Idea) -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().testTag("ideas_strip")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lightbulb, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.th_ideas_title_short), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.th_ideas_dismiss), Modifier.size(18.dp)) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ServiceIdeas.forProvider(p, ServiceIdeas.quickRouteForSpotify(ctx)).forEach { idea ->
                AssistChip(
                    onClick = { onIdea(idea) },
                    label = { Text(idea.title(), maxLines = 1) },
                    leadingIcon = { Icon(idea.icon(), null, Modifier.size(18.dp)) },
                    modifier = Modifier.testTag("idea_chip_" + idea.kind.name),
                )
            }
        }
    }
}
