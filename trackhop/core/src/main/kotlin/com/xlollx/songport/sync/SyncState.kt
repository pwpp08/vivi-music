package com.xlollx.songport.sync

import com.xlollx.songport.model.Progress
import com.xlollx.songport.platform.Env
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Stato in memoria delle sync in corso (per la UI), con pausa e interruzione richieste dall'utente. */
object SyncState {
    private val _running = MutableStateFlow<Map<String, Progress?>>(emptyMap())
    val running: StateFlow<Map<String, Progress?>> get() = _running
    private val _paused = MutableStateFlow<Set<String>>(emptySet())
    val paused: StateFlow<Set<String>> get() = _paused
    private val _stopping = MutableStateFlow<Set<String>>(emptySet())
    /** Stop asked for, not yet reached: the engine stops at its next step, the card says so meanwhile. */
    val stopping: StateFlow<Set<String>> get() = _stopping
    private val _parked = MutableStateFlow<Set<String>>(emptySet())
    /**
     * Syncs whose engine is really waiting in [checkpoint]. Pause is cooperative: it is asked for at
     * once ([paused]) but the engine only parks at its next step, so a sync in [paused] and not here
     * is still finishing a step ("Pausing"); one in both is paused for real ("Paused").
     */
    val parked: StateFlow<Set<String>> get() = _parked

    /** Where a running sync stands, as every surface (cards, top pill, notification) shows it. */
    enum class Run { RUNNING, PAUSING, PAUSED, STOPPING }

    /** How many of the running syncs are paused for real; the rest (pausing ones too) are still at work. */
    data class Tally(val total: Int, val paused: Int) {
        val running: Int get() = total - paused
        val allPaused: Boolean get() = total > 0 && paused == total
        val somePaused: Boolean get() = paused in 1 until total
    }

    /** A stop comes first (it goes through a pause); "Paused" only once the engine has parked. */
    fun runOf(pauseAsked: Boolean, parked: Boolean, stopping: Boolean): Run = when {
        stopping -> Run.STOPPING
        pauseAsked && parked -> Run.PAUSED
        pauseAsked -> Run.PAUSING
        else -> Run.RUNNING
    }

    fun runOf(jobId: String, paused: Set<String>, parked: Set<String>, stopping: Set<String>): Run =
        runOf(jobId in paused, jobId in parked, jobId in stopping)

    fun run(jobId: String): Run = runOf(jobId, _paused.value, _parked.value, _stopping.value)

    fun tally(running: Collection<String>, paused: Set<String>, parked: Set<String>, stopping: Set<String>): Tally =
        Tally(running.size, running.count { runOf(it, paused, parked, stopping) == Run.PAUSED })

    /** What happened to one source track during the run, for the live view. */
    enum class Outcome { FOUND, CACHED, NOT_FOUND, ADDED, ADD_FAILED, REMOVED }
    class LiveItem(val source: String, val result: String?, val outcome: Outcome)

    private val _items = MutableStateFlow<Map<String, List<LiveItem>>>(emptyMap())
    /** Tracks processed so far per running job, newest first (capped). */
    val items: StateFlow<Map<String, List<LiveItem>>> get() = _items

    // Every change is an atomic update: syncs run on several threads at once, and a lost write
    // would bring a finished sync back as running for good.
    fun start(jobId: String) { _running.update { it + (jobId to null) }; _items.update { it + (jobId to emptyList()) } }
    fun progress(jobId: String, p: Progress) { _running.update { it + (jobId to p) } }
    fun item(jobId: String, item: LiveItem) {
        _items.update { all -> all + (jobId to (listOf(item) + all[jobId].orEmpty()).take(MAX_ITEMS)) }
    }
    fun finish(jobId: String) {
        _running.update { it - jobId }
        _paused.update { it - jobId }
        _items.update { it - jobId }
        _stopping.update { it - jobId }
        _parked.update { it - jobId }
    }

    private const val MAX_ITEMS = 1000

    fun pause(jobId: String) { _paused.update { it + jobId } }
    fun resume(jobId: String) { _paused.update { it - jobId } }
    // A paused sync stays shown as paused until it actually stops: checkpoint() lets a stop through a pause.
    fun stop(jobId: String) { _stopping.update { it + jobId } }
    fun isPaused(jobId: String) = jobId in _paused.value

    /**
     * Chiamato dal motore a ogni passo: attende finche' la sync e' in pausa e interrompe se l'utente
     * l'ha fermata. Bloccante di proposito: il motore gira su Dispatchers.IO (app e server), dove
     * un thread fermo non toglie posto alle altre sync ne' alla UI.
     */
    fun checkpoint(env: Env, jobId: String) {
        if (jobId in _paused.value && jobId !in _stopping.value) {
            // The step is done and the engine waits here: from now on the sync is paused for real.
            _parked.update { it + jobId }
            try {
                while (jobId in _paused.value && jobId !in _stopping.value) Thread.sleep(250)
            } finally {
                _parked.update { it - jobId }
            }
        }
        stopCheck(env, jobId)
    }

    /** Solo l'interruzione, senza attendere la pausa: per i passi che devono scorrere comunque. */
    fun stopCheck(env: Env, jobId: String) {
        if (jobId in _stopping.value) throw SyncStoppedException(env.text("sync_stopped"))
    }
}

class SyncStoppedException(message: String) : Exception(message)
