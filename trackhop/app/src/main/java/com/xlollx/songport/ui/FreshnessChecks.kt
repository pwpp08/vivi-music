package com.xlollx.songport.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateMapOf
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.platform.Env
import com.xlollx.songport.sync.Freshness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The sync cards' "Up to date", checked rather than assumed (Freshness): a sync whose last run ended
 * well is called up to date only once its source has been seen unchanged since that run. One light
 * call per sync when the list shows, a few at a time, kept [KEEP_MS]; checked again sooner when the
 * sync runs (its last run or the version seen at it change).
 *
 * The results are kept on the phone too ([init]): a card opens with what the last check found, not
 * with a blank that fills in a second later, and a new check replaces it only once it has an answer.
 */
internal object FreshnessChecks {
    private class Entry(val state: Freshness.State, val at: Long, val lastRun: Long, val seen: String?)

    private const val KEEP_MS = 10 * 60_000L
    private val entries = ConcurrentHashMap<String, Entry>()
    private val slots = Semaphore(3)
    private var prefs: SharedPreferences? = null

    /** What the cards read: written on the main thread, so a card recomposes when its check ends. */
    private val states = mutableStateMapOf<String, Freshness.State>()

    /** The checks kept from earlier opens of the app; once, on the main thread, at start. */
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("freshness", Context.MODE_PRIVATE)
        prefs = p
        for ((id, value) in p.all) {
            val e = (value as? String)?.let(::decode) ?: continue
            entries[id] = e
            states[id] = e.state
        }
    }

    /** The checked state of [job], or null while it is not checked for its latest run. */
    fun stateOf(job: SyncJob): Freshness.State? {
        val state = states[job.id] ?: return null
        val e = entries[job.id] ?: return null
        return state.takeIf { e.lastRun == job.lastRunEpoch && e.seen == job.sourceVersion }
    }

    /** The syncs worth checking: on, run before with a version kept (others cannot be called up to date). */
    fun worthChecking(jobs: List<SyncJob>): List<SyncJob> =
        jobs.filter { it.enabled && it.radio == null && it.lastRunEpoch > 0 && it.sourceVersion != null }

    /** Checks the [jobs] not checked lately for their latest run. */
    suspend fun refresh(env: Env, jobs: List<SyncJob>) = coroutineScope {
        val now = System.currentTimeMillis()
        jobs.filter { job ->
            val e = entries[job.id]
            e == null || e.lastRun != job.lastRunEpoch || e.seen != job.sourceVersion || now - e.at > KEEP_MS
        }.forEach { job ->
            launch(Dispatchers.IO) {
                slots.withPermit {
                    val found = Freshness.check(env, job)
                    val before = entries[job.id]?.takeIf { it.lastRun == job.lastRunEpoch && it.seen == job.sourceVersion }
                    // A check that could not get an answer (no network, a slow service) leaves the
                    // last answer for this same run in place rather than wiping it.
                    val state = if (found == Freshness.State.UNKNOWN && before != null) before.state else found
                    val e = Entry(state, System.currentTimeMillis(), job.lastRunEpoch, job.sourceVersion)
                    entries[job.id] = e
                    prefs?.edit()?.putString(job.id, encode(e))?.apply()
                    withContext(Dispatchers.Main) { states[job.id] = state }
                }
            }
        }
    }

    /** Drops what is kept for syncs that no longer exist. */
    fun keepOnly(ids: Set<String>) {
        val gone = entries.keys.filter { it !in ids }
        if (gone.isEmpty()) return
        gone.forEach { entries.remove(it); states.remove(it) }
        prefs?.edit()?.apply { gone.forEach { remove(it) } }?.apply()
    }

    // "STATE\tat\tlastRun\tseen": the version last, so whatever it holds stays whole.
    private fun encode(e: Entry) = listOf(e.state.name, e.at, e.lastRun, e.seen ?: "").joinToString("\t")

    private fun decode(s: String): Entry? {
        val p = s.split('\t', limit = 4)
        if (p.size != 4) return null
        val state = runCatching { Freshness.State.valueOf(p[0]) }.getOrNull() ?: return null
        return Entry(state, p[1].toLongOrNull() ?: return null, p[2].toLongOrNull() ?: return null, p[3].ifEmpty { null })
    }
}
