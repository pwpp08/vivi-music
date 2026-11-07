package com.xlollx.songport.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.xlollx.songport.Notifications
import com.xlollx.songport.R
import com.xlollx.songport.data.Diagnostics
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.LocalFilesProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * Radio syncs on the phone. A radio sync notes the songs a station plays in its window (days and
 * hours) in a File playlist, its source, and syncs them to the target.
 *
 * The phone does not keep the stream open for hours: every two minutes in the window it opens it,
 * reads up to the first title the station sends (RadioSampler, a few seconds, tens of KB) and
 * closes it. A foreground service (type dataSync, with a quiet "Listening to …" notification) does
 * this while a window is open and stops when none is. An alarm wakes it at the next window's start;
 * the alarm is armed again after a reboot, an update or a change of time zone (RadioAlarmReceiver).
 */

/** What each listening radio sync is doing, for its card. Lives in the app's process. */
object RadioState {
    enum class Phase { LISTENING, WAITING_WIFI, ERROR }

    data class Status(
        val station: String,
        val phase: Phase,
        /** The last title the station sent (a song, or the station talking). */
        val title: String? = null,
        /** The stream carries no titles at all: nothing to listen for. */
        val noTitles: Boolean = false,
        val error: String? = null,
        /** Songs noted in the source since the window opened. */
        val songs: Int = 0,
    )

    val status = MutableStateFlow<Map<String, Status>>(emptyMap())

    /**
     * Radio syncs whose listening Android stopped after its daily allowance for this kind of work
     * (six hours, Android 15 and later), with when: said on the card, with "Listen again" (the app
     * on screen renews the allowance); not started again by itself before its next window.
     */
    val timedOut = MutableStateFlow<Map<String, LocalDateTime>>(emptyMap())
}

/** Whether the phone is on an unmetered network (usually Wi-Fi), the same test as the "Wi-Fi only" syncs' WorkManager constraint. */
internal fun onUnmetered(ctx: Context): Boolean {
    val cm = ContextCompat.getSystemService(ctx, ConnectivityManager::class.java) ?: return false
    return cm.activeNetwork != null && !cm.isActiveNetworkMetered
}

object RadioPlan {
    const val ACTION_ALARM = "com.xlollx.songport.radio.ALARM"

    /** While a window is open, the alarm stays armed this far ahead: if the process dies, it starts listening again. */
    private const val WATCHDOG_MS = 15 * 60_000L

    /** After a reboot or a change of time with a window open: listening starts from an alarm this soon (not from the boot broadcast, which may not start it). */
    private const val SOON_MS = 60_000L

    fun radios(ctx: Context): List<SyncJob> = Store.get(ctx).data.jobs.filter { it.radio != null && it.enabled }

    /** The radio syncs whose window is open at [now] (and that Android has not stopped for the day). */
    fun open(ctx: Context, now: LocalDateTime = LocalDateTime.now()): List<SyncJob> =
        radios(ctx).filter { RadioTitles.inWindow(it.radio!!, now) && it.id !in RadioState.timedOut.value.keys }

    /**
     * Brings the listening in line with the radio syncs: started when a window is open (and [start]:
     * the caller may start a foreground service: the app on screen, or the alarm), told to look again
     * when it runs, and the alarm armed for what comes next. [listenAgain]: the user asked, on a card
     * Android had stopped (its allowance renewed by the app on screen).
     */
    fun refresh(ctx: Context, start: Boolean, listenAgain: Boolean = false, soon: Boolean = false) {
        val app = ctx.applicationContext
        val now = LocalDateTime.now()
        val all = radios(app)
        // A stop by Android holds until the next window starts (or the user asks to listen again).
        RadioState.timedOut.update { m ->
            if (listenAgain) emptyMap()
            else m.filter { (id, at) ->
                val r = all.firstOrNull { it.id == id }?.radio
                r != null && RadioTitles.inWindow(r, now) && RadioTitles.nextStart(r, at)?.isAfter(now) != false
            }
        }
        if (all.isNotEmpty() && start) com.xlollx.songport.NotificationAsk.onUse()
        val open = open(app, now)
        if (RadioService.running.value) {
            RadioService.poke()
        } else if (open.isNotEmpty() && start) {
            if (RadioService.start(app)) Notifications.cancelRadioBlocked(app)
            else Notifications.radioBlocked(app, open.first().radio!!.station)
        }
        arm(app, all, open.isNotEmpty(), now, soon)
    }

    /** The alarm: at the next window's start, or, with a window open, a little ahead as a watchdog. None without radio syncs. */
    internal fun arm(ctx: Context, all: List<SyncJob> = radios(ctx), anyOpen: Boolean = open(ctx).isNotEmpty(), now: LocalDateTime = LocalDateTime.now(), soon: Boolean = false) {
        val am = ContextCompat.getSystemService(ctx, AlarmManager::class.java) ?: return
        val pi = alarmIntent(ctx)
        val at = nextAlarm(all.mapNotNull { it.radio }, now, anyOpen, soon)?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
        if (at == null) {
            am.cancel(pi)
            return
        }
        // An exact alarm is what lets the listening start with the app in the background (Android 12+);
        // without the permission, an inexact one still wakes the app, which then asks for a tap.
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /**
     * When the alarm should ring: the earliest next window start of [radios], or sooner, with a
     * window open now ([anyOpen]), the watchdog ([soon]: in a minute, after a reboot). Null: no alarm.
     */
    internal fun nextAlarm(radios: List<com.xlollx.songport.model.RadioSource>, now: LocalDateTime, anyOpen: Boolean, soon: Boolean): LocalDateTime? {
        val next = radios.mapNotNull { RadioTitles.nextStart(it, now) }.minOrNull()
        val watchdog = if (anyOpen) now.plusNanos((if (soon) SOON_MS else WATCHDOG_MS) * 1_000_000) else null
        return listOfNotNull(next, watchdog).minOrNull()
    }

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ContextCompat.getSystemService(ctx, AlarmManager::class.java)?.canScheduleExactAlarms() == true

    private fun alarmIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, RadioAlarmReceiver::class.java).setAction(ACTION_ALARM),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** The radio's alarm, and the moments alarms are lost or shift (a reboot, an update, a new time or time zone). */
class RadioAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Nothing to do (and nothing loaded) on phones without radio syncs.
        if (RadioPlan.radios(context).isEmpty() && !RadioService.running.value) {
            if (intent.action == RadioPlan.ACTION_ALARM) RadioPlan.arm(context)
            return
        }
        when (intent.action) {
            RadioPlan.ACTION_ALARM -> RadioPlan.refresh(context, start = true)
            // Android 15 does not start a dataSync service from the boot broadcast: an alarm in a minute does.
            else -> RadioPlan.refresh(context, start = false, soon = true)
        }
    }
}

class RadioService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    /** What each listening sync has heard in this window. */
    private class Heard(var lastTitle: String? = null, var songs: Int = 0, var pending: Int = 0, var lastRun: Long = 0)
    private val heard = java.util.concurrent.ConcurrentHashMap<String, Heard>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground(lines())) {
            stopSelf()
            return START_NOT_STICKY
        }
        _running.value = true
        if (loop?.isActive != true) loop = scope.launch { listen() } else poke()
        // Not sticky: a restart by the system from the background could not go to the foreground;
        // the watchdog alarm (RadioPlan) brings the listening back instead.
        return START_NOT_STICKY
    }

    private fun goForeground(lines: List<Pair<String, String>>): Boolean = try {
        ServiceCompat.startForeground(
            this, Notifications.RADIO_ID, Notifications.radio(this, lines),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        true
    } catch (e: Exception) {
        // Android 12+ from the background without an exemption, or the day's allowance used up (Android 15).
        Diagnostics.log(this, "radio", "could not listen in the foreground: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        false
    }

    private suspend fun listen() {
        val ctx = applicationContext
        try {
            while (currentCoroutineContext().isActive) {
                val now = LocalDateTime.now()
                val open = RadioPlan.open(ctx, now)
                val openIds = open.map { it.id }.toSet()
                // Windows closed since the last round: what was heard and not yet synced goes now.
                for (id in heard.keys - openIds) {
                    val h = heard.remove(id) ?: continue
                    if (h.pending > 0) Scheduler.runRadio(ctx, id, final = true)
                    Store.get(ctx).job(id)?.let { Diagnostics.log(ctx, "radio", "${it.name}: window closed, ${h.songs} new songs") }
                }
                RadioState.status.update { it.filterKeys { k -> k in openIds } }
                if (open.isEmpty()) break
                val unmetered = onUnmetered(ctx)
                for (job in open) {
                    val h = heard.getOrPut(job.id) {
                        Diagnostics.log(ctx, "radio", "${job.name}: listening to ${job.radio!!.station}")
                        Heard()
                    }
                    if (job.wifiOnly && !unmetered) {
                        status(job, h, RadioState.Phase.WAITING_WIFI)
                        continue
                    }
                    sample(ctx, job, h)
                    // New songs reach the target in batches: a run at most every 10 minutes.
                    val t = System.currentTimeMillis()
                    if (h.pending > 0 && t - h.lastRun >= RUN_EVERY_MS) {
                        Scheduler.runRadio(ctx, job.id, final = false)
                        h.lastRun = t
                        h.pending = 0
                    }
                }
                // The notification follows: posted again under the service's id, not a new foreground start.
                if (Notifications.canPost(ctx)) runCatching {
                    ContextCompat.getSystemService(ctx, android.app.NotificationManager::class.java)?.notify(Notifications.RADIO_ID, Notifications.radio(ctx, lines()))
                }
                RadioPlan.arm(ctx, anyOpen = true)
                withTimeoutOrNull(SAMPLE_EVERY_MS) { wake.receive() }
            }
        } finally {
            // Stopped (the window over, Android's limit, the app closed): nothing heard is left behind.
            for ((id, h) in heard) if (h.pending > 0) Scheduler.runRadio(ctx, id, final = true)
            heard.clear()
            RadioState.status.value = emptyMap()
        }
        withContext(Dispatchers.Main) {
            // A sync saved with its window open while this round ended: listen on rather than stop.
            if (RadioPlan.open(ctx).isNotEmpty()) loop = scope.launch { listen() }
            else stop()
        }
    }

    /** One visit to the station: the title on air, and the song noted in the source when it is new. */
    private suspend fun sample(ctx: Context, job: SyncJob, h: Heard) {
        val radio = job.radio!!
        val pm = ContextCompat.getSystemService(ctx, PowerManager::class.java)
        // Awake for the few seconds of the visit only: between visits the phone may sleep.
        val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Vivi:playlistSyncRadio")?.apply { setReferenceCounted(false); acquire(60_000) }
        try {
            val s = RadioSampler.sample(radio.stream)
            val title = s.title
            if (title != null && title != h.lastTitle) {
                h.lastTitle = title
                note(ctx, job, title, h)
            }
            status(job, h, RadioState.Phase.LISTENING)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: RadioSampler.NoTitles) {
            status(job, h, RadioState.Phase.ERROR, noTitles = true)
        } catch (e: Exception) {
            status(job, h, RadioState.Phase.ERROR, error = e.message ?: e.javaClass.simpleName)
            Diagnostics.log(ctx, "radio", "${job.name}: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            if (wl?.isHeld == true) wl.release()
        }
    }

    /** A new title: noted in the sync's source (its File playlist) when it is a song not there yet. */
    private suspend fun note(ctx: Context, job: SyncJob, title: String, h: Heard) {
        val track = RadioTitles.track(title, job.radio!!.station) ?: return
        val playlist = job.source.playlistId ?: return
        val seen = LocalFilesProvider.tracks(ctx.env, playlist).map { RadioTitles.key(it) }.toHashSet()
        if (RadioTitles.key(track) in seen) return
        LocalFilesProvider.addTracks(ctx.env, playlist, listOf(track))
        h.songs++
        h.pending++
        Diagnostics.log(ctx, "radio", "${job.name}: ${track.artists.joinToString(", ")} - ${track.title}")
    }

    private fun status(job: SyncJob, h: Heard, phase: RadioState.Phase, noTitles: Boolean = false, error: String? = null) {
        RadioState.status.update {
            it + (job.id to RadioState.Status(job.radio!!.station, phase, h.lastTitle?.let { RadioTitles.display(it, job.radio!!.station) }, noTitles, error, h.songs))
        }
    }

    /** The notification's lines: each station with its last song, or what it waits for. */
    private fun lines(): List<Pair<String, String>> {
        val st = RadioState.status.value
        val open = runCatching { RadioPlan.open(applicationContext) }.getOrDefault(emptyList())
        return open.map { job ->
            val s = st[job.id]
            job.radio!!.station to when {
                s == null -> getString(R.string.th_radio_listening)
                s.phase == RadioState.Phase.WAITING_WIFI -> getString(R.string.th_radio_waiting_wifi)
                s.phase == RadioState.Phase.ERROR && s.noTitles -> getString(R.string.th_radio_no_titles)
                s.phase == RadioState.Phase.ERROR -> getString(R.string.th_radio_error, s.error.orEmpty())
                s.title != null -> s.title
                else -> getString(R.string.th_radio_listening)
            }
        }
    }

    /**
     * Android 15 lets a dataSync service run six hours a day: past that it asks it to stop at once.
     * Stopped gracefully, the songs heard synced, and the cards say why.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val ctx = applicationContext
        val now = LocalDateTime.now()
        RadioState.timedOut.update { m -> m + RadioPlan.radios(ctx).filter { j -> j.id in heard.keys || RadioTitles.inWindow(j.radio!!, now) }.associate { j -> j.id to now } }
        Diagnostics.log(ctx, "radio", "Android's daily limit for listening reached: stopped")
        for ((id, h) in heard) if (h.pending > 0) { Scheduler.runRadio(ctx, id, final = true); h.pending = 0 }
        stop()
    }

    private fun stop() {
        loop?.cancel()
        _running.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        _running.value = false
        scope.cancel()
        RadioState.status.value = emptyMap()
        // The next window's alarm (or the watchdog, if one is still open).
        runCatching { RadioPlan.arm(applicationContext) }
        super.onDestroy()
    }

    companion object {
        /** A visit to the station every two minutes: songs last three and more. */
        const val SAMPLE_EVERY_MS = 2 * 60_000L
        /** New songs reach the target in batches: a run at most every 10 minutes while listening. */
        const val RUN_EVERY_MS = 10 * 60_000L

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running
        private val wake = Channel<Unit>(Channel.CONFLATED)

        /** Look again now (a sync saved, turned off or deleted) rather than at the next visit. */
        fun poke() { wake.trySend(Unit) }

        /** Starts listening; false when Android does not allow it from where the app is now. */
        fun start(ctx: Context): Boolean = try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RadioService::class.java))
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (Android 12+, app in the background) and the like.
            Diagnostics.log(ctx, "radio", "listening not started: ${e.javaClass.simpleName}")
            false
        }
    }
}
