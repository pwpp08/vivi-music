package com.xlollx.songport.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.sync.Scheduler
import com.xlollx.songport.sync.SyncState
import com.xlollx.songport.sync.SyncWorker
import com.xlollx.songport.ui.JobColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * The home-screen widgets made with Glance, in the phone's own colours (Material You):
 * - [SyncListWidget]: every sync, a tap on ▶ runs it, a failed one shows in red;
 * - [OneSyncWidget]: one sync chosen when the widget is placed, with its last result and ▶;
 * - [SyncAllWidget]: one tile, "Sync all".
 * The first widget ([SyncWidget], plain RemoteViews) stays as it was for those who have it.
 */
object Widgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Redraws every widget: after a sync starts or ends, or the syncs change. */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        SyncWidget.refresh(app)
        scope.launch {
            runCatching { SyncListWidget().updateAll(app) }
            runCatching { OneSyncWidget().updateAll(app) }
            runCatching { SyncAllWidget().updateAll(app) }
        }
    }

    /** A sync as the widgets show it. */
    class Row(val id: String, val name: String, val color: androidx.compose.ui.graphics.Color?, val status: String, val failed: Boolean, val running: Boolean, val toCheck: Int,
        /** The services, "Spotify → TIDAL": syncs of one playlist to several services share a name. */
        val route: String, val target: String,
        /** The connectors' ids, for their marks. */
        val sourceId: String, val targetId: String)

    fun rows(ctx: Context): List<Row> {
        val data = Store.get(ctx).data
        val running = SyncState.running.value.keys
        return data.jobs.filter { !it.twin }.map { row(ctx, it, running) }
    }

    fun row(ctx: Context, job: SyncJob, running: Set<String> = SyncState.running.value.keys): Row {
        val data = Store.get(ctx).data
        val last = data.reports.firstOrNull { it.jobId == job.id && !it.partial }
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val status = when {
            job.id in running -> ctx.getString(R.string.th_widget_running)
            last == null -> ctx.getString(R.string.th_last_run_never)
            !last.ok -> ctx.getString(R.string.th_result_error, last.error)
            else -> ctx.getString(R.string.th_widget_last, time.format(Date(last.startedEpoch)), ctx.getString(R.string.th_result_added_removed, last.added, last.removed))
        }
        fun service(id: String) = com.xlollx.songport.providers.Providers.byId(id)?.familyName ?: id
        val from = job.radio?.station ?: service(job.source.provider)
        val to = service(job.target.provider)
        return Row(job.id, job.name, JobColor.of(job.color)?.light ?: com.xlollx.songport.ui.CustomColor.parse(job.color)?.let { com.xlollx.songport.ui.CustomColor.tone(it, dark = false) }, status, last != null && !last.ok && job.id !in running, job.id in running,
            (last?.reviewTracks?.size ?: 0) + (last?.unmatched?.size ?: 0), "$from → $to", to,
            job.source.provider, job.target.provider)
    }

    internal val JOB = ActionParameters.Key<String>("job")
    internal val CHOSEN = stringPreferencesKey("job")
}

/** ▶ on a widget: runs that sync (or all of them), as the app's own button does. */
class RunSyncAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Scheduler.runNow(context, parameters[Widgets.JOB] ?: SyncWorker.ALL)
        Widgets.refresh(context)
    }
}

private val red = ColorProvider(Color(0xFFE5484D))

@Composable
private fun accent(c: androidx.compose.ui.graphics.Color?): ColorProvider = c?.let { ColorProvider(it) } ?: GlanceTheme.colors.primary

@Composable
private fun RunButton(job: String?, size: Int = 40) {
    Box(
        GlanceModifier.size(size.dp).cornerRadius((size / 2).dp).background(GlanceTheme.colors.primaryContainer)
            .clickable(actionRunCallback<RunSyncAction>(if (job != null) actionParametersOf(Widgets.JOB to job) else actionParametersOf())),
        contentAlignment = Alignment.Center,
    ) {
        Image(ImageProvider(if (job == null) R.drawable.th_ic_widget_sync else R.drawable.th_ic_widget_play), LocalContext.current.getString(if (job == null) R.string.th_sync_all else R.string.th_widget_run),
            GlanceModifier.size((size / 2).dp), colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer))
    }
}

private fun openApp(ctx: Context) = actionStartActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

/** Opens the app on the Syncs tab, scrolled to [jobId]'s card, which lights up for a moment. */
private fun openSync(ctx: Context, jobId: String) = actionStartActivity(
    Intent(ctx, MainActivity::class.java)
        // One address per sync: otherwise the system keeps a single pending intent for every row.
        .setData(android.net.Uri.parse("playlistsync://sync/" + android.net.Uri.encode(jobId)))
        .putExtra(MainActivity.EXTRA_FOCUS_JOB, jobId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
)

/** The pair of service marks, source then target, as on the sync cards. */
@Composable
private fun Marks(r: Widgets.Row, height: Int) {
    val px = (height * LocalContext.current.resources.displayMetrics.density).toInt()
    Image(ImageProvider(LogoBitmaps.pair(r.sourceId, r.targetId, px)), r.route, GlanceModifier.width((height * 1.62f).toInt().dp).height(height.dp))
}

// ---------------------------------------------------------------- every sync

class SyncListWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val rows = Widgets.rows(context)
        provideContent { GlanceTheme { SyncList(rows) } }
    }

    @Composable
    private fun SyncList(rows: List<Widgets.Row>) {
        val ctx = LocalContext.current
        Column(GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(GlanceTheme.colors.widgetBackground).padding(12.dp)) {
            Row(GlanceModifier.fillMaxWidth().clickable(openApp(ctx)), verticalAlignment = Alignment.CenterVertically) {
                Text(ctx.getString(R.string.th_app_name), GlanceModifier.defaultWeight(),
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                RunButton(null, 36)
            }
            Spacer(GlanceModifier.height(8.dp))
            if (rows.isEmpty()) {
                Text(ctx.getString(R.string.th_widget_no_syncs), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
            } else LazyColumn {
                items(rows, itemId = { it.id.hashCode().toLong() }) { r ->
                    Column(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Row(GlanceModifier.fillMaxWidth().cornerRadius(16.dp).background(GlanceTheme.colors.secondaryContainer).padding(10.dp).clickable(openSync(ctx, r.id)),
                            verticalAlignment = Alignment.CenterVertically) {
                            Marks(r, 26)
                            Spacer(GlanceModifier.width(10.dp))
                            Column(GlanceModifier.defaultWeight()) {
                                Text(r.name, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                                Text(r.status, maxLines = 1, style = TextStyle(color = if (r.failed) red else GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
                            }
                            Spacer(GlanceModifier.width(8.dp))
                            RunButton(r.id, 34)
                        }
                    }
                }
            }
        }
    }
}

class SyncListWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SyncListWidget()
}

// ---------------------------------------------------------------- one sync

class OneSyncWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val chosen = currentState<Preferences>()[Widgets.CHOSEN]
            val job = chosen?.let { Store.get(context).job(it) }
            GlanceTheme { One(job?.let { Widgets.row(context, it) }, job) }
        }
    }

    @Composable
    private fun One(r: Widgets.Row?, job: SyncJob?) {
        val ctx = LocalContext.current
        val wide = LocalSize.current.width >= 200.dp
        Column(GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(GlanceTheme.colors.widgetBackground).padding(14.dp)
            .clickable(if (r != null) openSync(ctx, r.id) else openApp(ctx))) {
            if (r == null || job == null) {
                Text(ctx.getString(R.string.th_widget_sync_gone), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
                return@Column
            }
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Marks(r, 28)
                Spacer(GlanceModifier.width(10.dp))
                Text(r.name, GlanceModifier.defaultWeight(), maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                RunButton(r.id, 40)
            }
            Spacer(GlanceModifier.height(6.dp))
            if (wide) Text(r.route, maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
            Text(r.status, maxLines = 2, style = TextStyle(color = if (r.failed) red else GlanceTheme.colors.onSurface, fontSize = 13.sp))
            if (r.toCheck > 0) Text(ctx.resources.getQuantityString(R.plurals.th_widget_to_check, r.toCheck, r.toCheck), maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.tertiary, fontSize = 12.sp, fontWeight = FontWeight.Medium))
        }
    }
}

class OneSyncWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = OneSyncWidget()
}

/** Keeps the sync chosen for a newly placed [OneSyncWidget] and draws it. */
suspend fun chooseSyncForWidget(context: Context, appWidgetId: Int, jobId: String) {
    val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
    updateAppWidgetState(context, glanceId) { it[Widgets.CHOSEN] = jobId }
    OneSyncWidget().update(context, glanceId)
}

// ---------------------------------------------------------------- sync all, one tile

class SyncAllWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(48.dp, 48.dp), DpSize(110.dp, 48.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { GlanceTheme { Tile() } }
    }

    @Composable
    private fun Tile() {
        val ctx = LocalContext.current
        val running = SyncState.running.value.isNotEmpty()
        val wide = LocalSize.current.width >= 110.dp
        Row(
            GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(GlanceTheme.colors.primaryContainer)
                .clickable(actionRunCallback<RunSyncAction>()).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(ImageProvider(R.drawable.th_ic_widget_sync), ctx.getString(R.string.th_sync_all), GlanceModifier.size(24.dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer))
            if (wide) {
                Spacer(GlanceModifier.width(8.dp))
                Text(ctx.getString(if (running) R.string.th_widget_running else R.string.th_widget_sync_all_short), maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 14.sp, fontWeight = FontWeight.Medium))
            }
        }
    }
}

class SyncAllWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SyncAllWidget()
}
