package com.xlollx.songport.sync

import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.LocalFilesProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * "Sync all": several syncs at once, never two on the same account. Two syncs that share a
 * service account (two from the same Spotify, say) run one after the other, as every sync did
 * before 1.5.0: services slow down or refuse an account that asks too much at once, and two syncs
 * writing to one account could step on each other. Syncs on different accounts run side by side,
 * up to [MAX] together. Kotlin puro: coperto dai test.
 */
object ParallelRuns {
    const val MAX = 3

    /** What a sync holds while it runs: each account it reads or writes; for files, the file itself. */
    fun keys(job: SyncJob): Set<String> = listOf(job.source, job.target).map { ref ->
        if (ref.provider == LocalFilesProvider.id) "${ref.provider}:${ref.playlistId ?: ref.playlistName}" else ref.provider
    }.toSet()

    /** Runs [block] for every job in [jobs], in their order, as many at once as the accounts allow. */
    suspend fun run(jobs: List<SyncJob>, max: Int = MAX, block: suspend (SyncJob) -> Unit) = coroutineScope {
        val pending = jobs.toMutableList()
        val busy = HashSet<String>()
        var active = 0
        val finished = Channel<SyncJob>(Channel.UNLIMITED)
        while (pending.isNotEmpty() || active > 0) {
            while (active < max) {
                val next = pending.firstOrNull { job -> keys(job).none { it in busy } } ?: break
                pending.remove(next)
                busy += keys(next)
                active++
                launch { try { block(next) } finally { finished.send(next) } }
            }
            val done = finished.receive()
            busy -= keys(done)
            active--
        }
    }
}
