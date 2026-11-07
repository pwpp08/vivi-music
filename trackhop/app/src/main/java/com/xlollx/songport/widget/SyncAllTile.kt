package com.xlollx.songport.widget

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.text.format.DateUtils
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.sync.Scheduler
import com.xlollx.songport.sync.SyncState
import com.xlollx.songport.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.text.DateFormat

/**
 * Quick Settings tile "Sync all": a tap runs every enabled sync, like the widget and the shortcut.
 * Its subtitle says "Syncing…" while any sync runs, otherwise when the last one ran; with no sync to
 * run it is unavailable. While syncs run, a tap opens the Syncs tab instead, where they show progress.
 *
 * Not an active tile: Android asks for its state each time the panel opens, and while the panel is
 * open it follows the running syncs and the store, so it is never stale when seen.
 */
class SyncAllTile : TileService() {
    private var scope: CoroutineScope? = null

    override fun onTileAdded() {
        super.onTileAdded()
        render(Store.get(this).data, SyncState.running.value.isNotEmpty())
    }

    override fun onStartListening() {
        super.onStartListening()
        scope?.cancel()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            combine(Store.get(this@SyncAllTile).state, SyncState.running) { data, running -> Shown.of(data, running.isNotEmpty()) }
                .distinctUntilChanged()
                .collect { render(it) }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val data = Store.get(this).data
        if (data.jobs.none { it.enabled }) return
        if (SyncState.running.value.isNotEmpty()) {
            openSyncs()
            return
        }
        // Running syncs uses the user's accounts: on a locked phone, only after unlocking.
        val start = Runnable { Scheduler.runNow(applicationContext, SyncWorker.ALL) }
        if (isLocked) unlockAndRun(start) else start.run()
    }

    private fun openSyncs() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SYNCS)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    /** What the tile shows, so the store's other changes (caches, settings) do not redraw it. */
    private data class Shown(val jobs: Int, val enabled: Int, val busy: Boolean, val lastRun: Long) {
        companion object {
            fun of(data: StoreData, busy: Boolean) = Shown(
                data.jobs.size, data.jobs.count { it.enabled }, busy, data.jobs.maxOfOrNull { it.lastRunEpoch } ?: 0L,
            )
        }
    }

    private fun render(data: StoreData, busy: Boolean) = render(Shown.of(data, busy))

    private fun render(s: Shown) {
        val tile = qsTile ?: return
        val subtitle = when {
            s.jobs == 0 -> getString(R.string.th_empty_syncs_title)
            s.enabled == 0 -> getString(R.string.th_tile_all_disabled)
            s.busy -> getString(R.string.th_running)
            s.lastRun <= 0 -> getString(R.string.th_last_run_never)
            // The time today, the date before: short enough for a tile.
            else -> getString(R.string.th_hero_last, DateUtils.formatSameDayTime(s.lastRun, System.currentTimeMillis(), DateFormat.SHORT, DateFormat.SHORT))
        }
        tile.label = getString(R.string.th_tile_sync_all)
        tile.icon = Icon.createWithResource(this, R.drawable.th_ic_notification)
        tile.state = when {
            s.enabled == 0 -> Tile.STATE_UNAVAILABLE
            s.busy -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = subtitle
        // TalkBack would say "off" for an inactive tile: this is an action, so it hears the subtitle.
        if (Build.VERSION.SDK_INT >= 30) tile.stateDescription = subtitle
        tile.updateTile()
    }
}
