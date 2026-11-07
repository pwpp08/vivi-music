package com.xlollx.songport.sync

import com.xlollx.songport.providers.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xlollx.songport.Notifications

/**
 * Pausa, ripresa e interruzione dai pulsanti della notifica di avanzamento: di una sync, o di
 * tutte quelle in corso ([Notifications.ALL_JOBS], "Pause all" / "Stop all").
 */
class SyncControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(EXTRA_JOB) ?: return
        val jobs = if (jobId == Notifications.ALL_JOBS) SyncState.running.value.keys else setOf(jobId)
        when (intent.action) {
            ACTION_PAUSE -> jobs.forEach { SyncState.pause(it) }
            ACTION_RESUME -> (if (jobId == Notifications.ALL_JOBS) SyncState.paused.value else jobs).forEach { SyncState.resume(it) }
            ACTION_STOP -> jobs.forEach { SyncState.stop(it) }
        }
        SyncNotice.refresh(context)
    }

    companion object {
        const val ACTION_PAUSE = "com.xlollx.songport.sync.PAUSE"
        const val ACTION_RESUME = "com.xlollx.songport.sync.RESUME"
        const val ACTION_STOP = "com.xlollx.songport.sync.STOP"
        const val EXTRA_JOB = "job"
    }
}
