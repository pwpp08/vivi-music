package com.xlollx.songport.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.data.Diagnostics
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.sync.Scheduler
import com.xlollx.songport.sync.SyncWorker

/**
 * Trackhop for automation apps (Tasker, MacroDroid, Automate, Samsung Modes and Routines through
 * the shortcuts): commands that run syncs, and an event at the end of each sync. Off until the user
 * turns it on in Settings, since any app on the phone can send these commands; with it off, they
 * are ignored and nothing is announced.
 */
object Automation {
    /** Runs every sync. */
    const val ACTION_RUN_ALL = "com.xlollx.songport.action.RUN_ALL"
    /** Runs one sync: extra [EXTRA_SYNC] with its name (any case) or its id. */
    const val ACTION_RUN = "com.xlollx.songport.action.RUN"
    const val EXTRA_SYNC = "sync"

    /** Sent when a sync ends, for automation apps listening to it (Tasker's "Intent Received"). */
    const val EVENT_FINISHED = "com.xlollx.songport.event.SYNC_FINISHED"
    const val EXTRA_ID = "id"
    const val EXTRA_ADDED = "added"
    const val EXTRA_REMOVED = "removed"
    const val EXTRA_NOT_FOUND = "notFound"
    const val EXTRA_TO_REVIEW = "toReview"
    const val EXTRA_OK = "ok"
    const val EXTRA_ERROR = "error"

    fun enabled(ctx: Context): Boolean = Store.get(ctx).data.settings.automation

    /** The sync named [key] (its name, case aside, or its id). */
    fun find(jobs: List<SyncJob>, key: String): SyncJob? {
        val k = key.trim()
        return jobs.firstOrNull { it.id == k } ?: jobs.firstOrNull { it.name.trim().equals(k, ignoreCase = true) }
    }

    /** The end of a sync, told to automation apps: counts and outcome, never a track or an account. */
    fun announce(ctx: Context, report: SyncReport) {
        if (!enabled(ctx)) return
        val i = Intent(EVENT_FINISHED)
            .putExtra(EXTRA_SYNC, report.jobName)
            .putExtra(EXTRA_ID, report.jobId)
            .putExtra(EXTRA_ADDED, report.added)
            .putExtra(EXTRA_REMOVED, report.removed)
            .putExtra(EXTRA_NOT_FOUND, report.unmatched.size)
            .putExtra(EXTRA_TO_REVIEW, report.reviewTracks.size)
            .putExtra(EXTRA_OK, report.error == null)
        report.error?.let { i.putExtra(EXTRA_ERROR, it.take(300)) }
        runCatching { ctx.sendBroadcast(i) }
    }

    /**
     * Long-press shortcuts on the launcher icon for the syncs run most recently (besides the fixed
     * "Sync all" and "New sync"): a tap runs that sync. Launchers show four or five in all.
     */
    fun updateShortcuts(ctx: Context) = runCatching {
        val data = Store.get(ctx).data
        val lastRun = data.reports.groupBy { it.jobId }.mapValues { (_, rs) -> rs.maxOf { it.startedEpoch } }
        val jobs = data.jobs.filter { it.enabled }.sortedByDescending { lastRun[it.id] ?: 0L }.take(MAX_SHORTCUTS)
        val shortcuts = jobs.map { j ->
            ShortcutInfoCompat.Builder(ctx, "run-${j.id}")
                .setShortLabel(j.name.take(24).ifBlank { ctx.getString(R.string.th_app_name) })
                .setLongLabel(ctx.getString(R.string.th_automation_shortcut_run, j.name).take(40))
                .setIcon(IconCompat.createWithResource(ctx, R.drawable.th_ic_widget_play))
                .setIntent(Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_RUN_SYNC).putExtra(EXTRA_SYNC, j.id))
                .build()
        }
        ShortcutManagerCompat.setDynamicShortcuts(ctx, shortcuts)
    }

    private const val MAX_SHORTCUTS = 2
}

/** The commands, from automation apps: honoured only with automation on in Settings. */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Automation.enabled(ctx)) {
            Diagnostics.log(ctx, "automation", "${intent.action?.substringAfterLast('.')} ignored: automation is off in Settings")
            return
        }
        when (intent.action) {
            Automation.ACTION_RUN_ALL -> {
                Diagnostics.log(ctx, "automation", "run all")
                Scheduler.runNow(ctx, SyncWorker.ALL)
            }
            Automation.ACTION_RUN -> {
                val key = intent.getStringExtra(Automation.EXTRA_SYNC).orEmpty()
                val job = Automation.find(Store.get(ctx).data.jobs, key)
                if (job == null) Diagnostics.log(ctx, "automation", "run: no sync called \"${key.take(60)}\"")
                else { Diagnostics.log(ctx, "automation", "run ${job.name}"); Scheduler.runNow(ctx, job.id) }
            }
        }
    }
}
