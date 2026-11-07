package com.xlollx.songport.sync

import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether a sync's copy is still current, checked now rather than assumed from its last run: the
 * source's version (MusicProvider.playlistVersion, one light call) against the one the engine kept
 * at the end of the last successful run. A run ten days ago says nothing about today: only an
 * unchanged source does.
 */
object Freshness {
    enum class State {
        /** The source is as it was at the last successful run: nothing to sync. */
        CURRENT,
        /** The source changed since: a run would bring something. */
        CHANGED,
        /** Not known: the service gives no version, the playlist is a library slot it cannot version, or it could not be reached. */
        UNKNOWN,
    }

    private const val TIMEOUT_MS = 15_000L

    suspend fun check(env: Env, job: SyncJob): State {
        val seen = job.sourceVersion ?: return State.UNKNOWN
        val id = job.source.playlistId ?: return State.UNKNOWN
        val provider = Providers.byId(job.source.provider) ?: return State.UNKNOWN
        val now = withTimeoutOrNull(TIMEOUT_MS) { runCatching { provider.playlistVersion(env, id) }.getOrNull() } ?: return State.UNKNOWN
        return if (now == seen) State.CURRENT else State.CHANGED
    }
}
