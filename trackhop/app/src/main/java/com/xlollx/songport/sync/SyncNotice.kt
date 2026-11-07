package com.xlollx.songport.sync

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.work.ForegroundInfo
import com.xlollx.songport.Notifications
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.Progress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single ongoing notification of the syncs that run in the foreground.
 *
 * Every manual sync worker goes to the foreground under the same id ([Notifications.PROGRESS_ID])
 * and on the same channel, and the content always comes from [SyncState], never from one worker:
 * whichever worker last spoke, the shade shows every sync running. Before, each worker had its
 * own id, so four syncs started together showed up to four entries, and WorkManager's service
 * re-posted a worker's first "Syncing…" placeholder whenever it moved the foreground from one
 * worker to another, a bare bar beside the real ones.
 *
 * While at least one worker holds the foreground ([enter] … [leave]) a collector follows the
 * running syncs, their pauses (asked for and reached) and stops, and refreshes the notification at most every [MIN_GAP] ms.
 */
object SyncNotice {
    private const val MIN_GAP = 400L

    private val lock = Any()
    private var holders = 0
    private var watcher: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val pending = AtomicBoolean(false)
    @Volatile private var lastPost = 0L

    /** What a worker hands WorkManager: the shared id, the shared content, the dataSync type. */
    fun foregroundInfo(ctx: Context): ForegroundInfo {
        val n = Notifications.running(ctx, syncs(ctx))
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(Notifications.PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(Notifications.PROGRESS_ID, n)
    }

    /** A worker is now in the foreground: follow the running syncs until the last one leaves. */
    fun enter(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(lock) {
            holders++
            if (watcher == null) watcher = scope.launch {
                combine(SyncState.running, SyncState.paused, SyncState.parked, SyncState.stopping) { _, _, _, _ -> }.collect { refresh(app) }
            }
        }
        settle(app)
    }

    /** A worker is done. WorkManager hands the foreground to another one with that worker's own copy: put ours back on top. */
    fun leave(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(lock) {
            holders = (holders - 1).coerceAtLeast(0)
            if (holders == 0) { watcher?.cancel(); watcher = null }
        }
        settle(app)
    }

    /** Now and again shortly after: WorkManager's service applies a ForegroundInfo a moment after it is handed over. */
    private fun settle(app: Context) {
        refresh(app)
        main.postDelayed({ refresh(app) }, 600)
        main.postDelayed({ refresh(app) }, 2_000)
    }

    /** Rebuilds the notification from the running syncs, no more often than every [MIN_GAP] ms. */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        if (!pending.compareAndSet(false, true)) return
        val wait = (MIN_GAP - (SystemClock.uptimeMillis() - lastPost)).coerceIn(0, MIN_GAP)
        main.postDelayed({
            pending.set(false)
            lastPost = SystemClock.uptimeMillis()
            post(app)
        }, wait)
    }

    private fun post(ctx: Context) {
        // Nothing in the foreground: WorkManager has taken the notification away, and it stays away.
        if (synchronized(lock) { holders } == 0 || !Notifications.canPost(ctx)) return
        val syncs = syncs(ctx)
        if (syncs.isEmpty()) return
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.notify(Notifications.PROGRESS_ID, Notifications.running(ctx, syncs)) }
    }

    /** Every running sync, by name, with its step: the content of the notification. */
    fun syncs(ctx: Context): List<Notifications.RunningSync> {
        val store = Store.get(ctx)
        val paused = SyncState.paused.value
        val parked = SyncState.parked.value
        val stopping = SyncState.stopping.value
        return SyncState.running.value.map { (id, p) ->
            val job = store.job(id)
            val name = job?.let { it.name.ifBlank { it.source.playlistName } } ?: ctx.getString(R.string.th_running)
            val step = if (id in stopping) ctx.getString(R.string.th_sync_stopping) else stepText(ctx, p)
            Notifications.RunningSync(id, name, step, p?.done ?: 0, p?.total ?: 0, SyncState.runOf(id, paused, parked, stopping))
        }
    }

    fun stepText(ctx: Context, p: Progress?): String = when (p?.step) {
        null -> ctx.getString(R.string.th_running)
        Progress.Step.FETCH_SOURCE -> ctx.getString(R.string.th_progress_fetch_source)
        Progress.Step.CREATE_TARGET -> ctx.getString(R.string.th_progress_create_target)
        Progress.Step.FETCH_TARGET -> ctx.getString(R.string.th_progress_fetch_target)
        Progress.Step.MATCHING -> ctx.getString(R.string.th_progress_matching, p.done, p.total) + (p.label?.let { " · $it" } ?: "")
        Progress.Step.WAITING -> ctx.getString(R.string.th_progress_waiting, (p.total - p.done).coerceAtLeast(0))
        Progress.Step.ADDING -> ctx.getString(R.string.th_progress_adding, p.total) + (p.label?.let { " · $it" } ?: "")
        Progress.Step.REMOVING -> ctx.getString(R.string.th_progress_removing, p.total) + (p.label?.let { " · $it" } ?: "")
        else -> ctx.getString(R.string.th_running)
    }
}
