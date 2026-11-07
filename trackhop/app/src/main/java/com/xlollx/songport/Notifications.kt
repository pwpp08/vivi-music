package com.xlollx.songport

import com.xlollx.songport.providers.*
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.sync.SyncState

/**
 * Notifications are asked for when they are first needed, a sync run or scheduled, not on first
 * start: the visible activity registers the request here and the scheduler calls it.
 */
object NotificationAsk {
    @Volatile var request: (() -> Unit)? = null
    @Volatile private var asked = false

    fun onUse() {
        if (asked) return
        val r = request ?: return
        asked = true
        android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { r() } }
    }
}

object Notifications {
    private const val CHANNEL_ID = "sync_results"
    const val PROGRESS_CHANNEL_ID = "sync_progress"
    /**
     * The one ongoing notification of the syncs running: every worker that goes to the foreground
     * posts under this id, so WorkManager's service and the progress updates share a single entry.
     */
    const val PROGRESS_ID = 1001
    /** The completion notices, bundled under one summary when several syncs finish. */
    const val RESULTS_GROUP = "sync_results"
    private const val RESULTS_SUMMARY_ID = 1000
    /** The value of [com.xlollx.songport.sync.SyncControlReceiver.EXTRA_JOB] that means every running sync. */
    const val ALL_JOBS = "*"

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.th_notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.th_notif_channel_desc) }
        )
        mgr.createNotificationChannel(
            NotificationChannel(PROGRESS_CHANNEL_ID, context.getString(R.string.th_notif_progress_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.th_notif_progress_desc); setShowBadge(false) }
        )
        // Quiet, like the progress: the ongoing "Listening to …" of a radio sync, and its rare "tap to listen".
        mgr.createNotificationChannel(
            NotificationChannel(RADIO_CHANNEL_ID, context.getString(R.string.th_radio_notif_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.th_radio_notif_channel_desc); setShowBadge(false) }
        )
    }

    const val RADIO_CHANNEL_ID = "radio_listening"
    /** The ongoing notification of the radio listening service (see com.xlollx.songport.sync.RadioService). */
    const val RADIO_ID = 1003
    /** "Tap to listen": the listening could not start by itself. */
    const val RADIO_BLOCKED_ID = 1004

    private fun openApp(context: Context, code: Int): PendingIntent {
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SYNCS)
        }
        return PendingIntent.getActivity(context, code, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * While radio syncs listen: "Listening to <station>" and the last song heard (or that it waits
     * for Wi-Fi, or what went wrong). Several stations at once share the one notification.
     */
    fun radio(context: Context, lines: List<Pair<String, String>>): android.app.Notification {
        val title = context.getString(R.string.th_radio_notif_listening, lines.joinToString(", ") { it.first }.ifEmpty { "…" })
        val text = lines.joinToString(" · ") { it.second }
        val b = NotificationCompat.Builder(context, RADIO_CHANNEL_ID)
            .setSmallIcon(R.drawable.th_ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context, RADIO_ID))
        if (lines.size > 1) {
            val inbox = NotificationCompat.InboxStyle().setBigContentTitle(title)
            lines.forEach { inbox.addLine(it.first + " — " + it.second) }
            b.setStyle(inbox)
        }
        return b.build()
    }

    /** A radio sync's window is open but Android did not let the listening start in the background: a tap opens the app, which starts it. */
    fun radioBlocked(context: Context, station: String) {
        if (!canPost(context)) return
        val text = context.getString(R.string.th_radio_notif_blocked_text)
        val n = NotificationCompat.Builder(context, RADIO_CHANNEL_ID)
            .setSmallIcon(R.drawable.th_ic_notification)
            .setContentTitle(context.getString(R.string.th_radio_notif_blocked_title, station))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context, RADIO_BLOCKED_ID))
            .setAutoCancel(true)
            .build()
        ContextCompat.getSystemService(context, NotificationManager::class.java)?.notify(RADIO_BLOCKED_ID, n)
    }

    fun cancelRadioBlocked(context: Context) {
        runCatching { ContextCompat.getSystemService(context, NotificationManager::class.java)?.cancel(RADIO_BLOCKED_ID) }
    }

    /**
     * One running sync as the ongoing notification shows it: its name, the step it is at, and where
     * it stands ([SyncState.Run]: "Pausing…" while its step finishes, "Paused" once it waits).
     */
    class RunningSync(
        val jobId: String, val name: String, val step: String, val done: Int = 0, val total: Int = 0,
        val run: SyncState.Run = SyncState.Run.RUNNING,
    ) {
        /** Waiting for real: its bar stands still. */
        val paused: Boolean get() = run == SyncState.Run.PAUSED
        /** Pause asked for, reached or not: its button is Resume. */
        val pauseAsked: Boolean get() = run == SyncState.Run.PAUSED || run == SyncState.Run.PAUSING
    }

    /**
     * The ongoing notification while syncs run, built from all of them at once. One sync: its name,
     * its step, its bar, Pause/Resume and Stop. Several: "N syncs running", one line per sync with
     * its step, the overall bar, and Pause all/Resume all and Stop all.
     *
     * Paused syncs say so: the title becomes "N syncs paused" (all of them) or "N running · M paused",
     * each line "Paused" once its sync really waits ("Pausing…" while its step finishes), and the bar
     * stands still, never indeterminate, when nothing is moving.
     */
    fun running(context: Context, syncs: List<RunningSync>): android.app.Notification {
        val open = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pi = PendingIntent.getActivity(context, PROGRESS_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.th_ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pi)
        // Request codes differ per job: PendingIntents with equal codes and intents that differ
        // only in their extras replace each other, and Pause on one sync would pause another.
        fun action(jobId: String, act: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
            context, (jobId.hashCode() shl 2) or code,
            Intent(context, com.xlollx.songport.sync.SyncControlReceiver::class.java).setAction(act).putExtra(com.xlollx.songport.sync.SyncControlReceiver.EXTRA_JOB, jobId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val one = syncs.singleOrNull()
        if (syncs.size <= 1) {
            val text = one?.let { lineText(context, it) }.orEmpty()
            b.setContentTitle(one?.name ?: context.getString(R.string.th_running))
                .setContentText(text)
            if (one != null && one.paused) b.setProgress(100, percent(one), false)
            else b.setProgress(one?.total?.coerceAtLeast(0) ?: 0, one?.done ?: 0, (one?.total ?: 0) <= 0)
            if (one != null) {
                if (one.pauseAsked) b.addAction(0, context.getString(R.string.th_sync_resume), action(one.jobId, com.xlollx.songport.sync.SyncControlReceiver.ACTION_RESUME, 1))
                else b.addAction(0, context.getString(R.string.th_sync_pause), action(one.jobId, com.xlollx.songport.sync.SyncControlReceiver.ACTION_PAUSE, 2))
                b.addAction(0, context.getString(R.string.th_sync_stop), action(one.jobId, com.xlollx.songport.sync.SyncControlReceiver.ACTION_STOP, 3))
            }
            return b.build()
        }
        val tally = SyncState.Tally(syncs.size, syncs.count { it.paused })
        val title = when {
            tally.allPaused -> context.resources.getQuantityString(R.plurals.th_syncs_paused, syncs.size, syncs.size)
            tally.somePaused -> context.getString(R.string.th_syncs_running_paused, tally.running, tally.paused)
            else -> context.resources.getQuantityString(R.plurals.th_syncs_running, syncs.size, syncs.size)
        }
        val lines = syncs.map { "${it.name} — ${lineText(context, it)}" }
        val inbox = NotificationCompat.InboxStyle().setBigContentTitle(title)
        lines.take(MAX_LINES).forEach { inbox.addLine(it) }
        if (lines.size > MAX_LINES) inbox.setSummaryText(context.resources.getQuantityString(R.plurals.th_notif_more_running, lines.size - MAX_LINES, lines.size - MAX_LINES))
        // The overall bar: each sync weighs the same, one with no count yet as not started.
        val known = syncs.filter { it.total > 0 }
        val overall = syncs.sumOf { s -> percent(s).toDouble() } / syncs.size
        b.setContentTitle(title)
            .setContentText(lines.joinToString(" · "))
            .setStyle(inbox)
            .setNumber(syncs.size)
            // Everything paused: a still bar at where they stopped, not a moving one.
            .setProgress(100, overall.toInt(), known.isEmpty() && !tally.allPaused)
        if (syncs.all { it.pauseAsked }) b.addAction(0, context.getString(R.string.th_sync_resume_all), action(ALL_JOBS, com.xlollx.songport.sync.SyncControlReceiver.ACTION_RESUME, 1))
        else b.addAction(0, context.getString(R.string.th_sync_pause_all), action(ALL_JOBS, com.xlollx.songport.sync.SyncControlReceiver.ACTION_PAUSE, 2))
        b.addAction(0, context.getString(R.string.th_sync_stop_all), action(ALL_JOBS, com.xlollx.songport.sync.SyncControlReceiver.ACTION_STOP, 3))
        return b.build()
    }

    /** Lines past this many are counted ("+N more syncs running"), not listed. */
    private const val MAX_LINES = 6

    /** One sync's share of the bar: none until its step has a count. */
    private fun percent(s: RunningSync): Int = if (s.total > 0) (s.done.coerceIn(0, s.total) * 100L / s.total).toInt() else 0

    /** What one sync is doing, with "Paused" (or "Pausing…") before it and its percentage after. */
    private fun lineText(context: Context, s: RunningSync): String =
        when (s.run) {
            SyncState.Run.PAUSED -> context.getString(R.string.th_sync_paused) + " · "
            SyncState.Run.PAUSING -> context.getString(R.string.th_sync_pausing_short) + " · "
            else -> ""
        } + s.step +
            (if (s.total > 0) " · ${(s.done.coerceIn(0, s.total) * 100L / s.total)}%" else "")

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Testo riassuntivo di un report, condiviso tra notifiche e UI. */
    fun summary(context: Context, r: SyncReport): String {
        if (!r.ok) return context.getString(R.string.th_result_error, r.error)
        val parts = ArrayList<String>()
        if (r.added > 0 || r.removed > 0) parts += context.getString(R.string.th_result_added_removed, r.added, r.removed)
        if (r.unmatched.isNotEmpty()) parts += context.getString(R.string.th_result_unmatched, r.unmatched.size)
        if (r.ignored > 0) parts += context.getString(R.string.th_result_ignored, r.ignored)
        if (parts.isEmpty()) parts += context.getString(R.string.th_result_ok)
        return parts.joinToString(", ")
    }

    /** A one-line notice that opens the app on its Settings (the backup folder gone, and the like). */
    fun notice(context: Context, id: Int, title: String, text: String) {
        if (!canPost(context)) return
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SETTINGS)
        }
        val pi = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.th_ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        ContextCompat.getSystemService(context, NotificationManager::class.java)?.notify(id, n)
    }

    /** "N tracks can no longer be played": opens the Unplayable tracks tool. */
    fun unplayable(context: Context, id: Int, count: Int) {
        if (!canPost(context)) return
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_TOOLS)
            putExtra(MainActivity.EXTRA_TOOL, MainActivity.TOOL_UNPLAYABLE)
        }
        val pi = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = context.resources.getQuantityString(R.plurals.th_notif_unplayable_text, count, count)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.th_ic_notification)
            .setContentTitle(context.getString(R.string.th_notif_unplayable_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        ContextCompat.getSystemService(context, NotificationManager::class.java)?.notify(id, n)
    }

    fun syncResult(context: Context, report: SyncReport) {
        if (!canPost(context)) return
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_LOG)
        }
        val pi = PendingIntent.getActivity(context, report.jobId.hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = summary(context, report)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.th_ic_notification)
            .setContentTitle(context.getString(R.string.th_notif_sync_title, report.jobName))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setGroup(RESULTS_GROUP)
            .build()
        val mgr = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        mgr.notify(report.jobId.hashCode(), n)
        resultsSummary(context, mgr)
    }

    /**
     * The summary that bundles the completion notices: posted once two or more are in the shade, so
     * syncs finishing together collapse into one group. A lone notice stays as it is.
     */
    private fun resultsSummary(context: Context, mgr: NotificationManager) {
        val children = runCatching { mgr.activeNotifications.toList() }.getOrDefault(emptyList())
            .filter { it.notification.group == RESULTS_GROUP && it.id != RESULTS_SUMMARY_ID }
        if (children.size < 2) return
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_LOG)
        }
        val pi = PendingIntent.getActivity(context, RESULTS_SUMMARY_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = context.getString(R.string.th_notif_channel_name)
        val inbox = NotificationCompat.InboxStyle().setBigContentTitle(title)
        children.mapNotNull { it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE) }.forEach { inbox.addLine(it) }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.th_ic_notification)
            .setContentTitle(title)
            .setStyle(inbox)
            .setNumber(children.size)
            .setGroup(RESULTS_GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { mgr.notify(RESULTS_SUMMARY_ID, n) }
    }
}
