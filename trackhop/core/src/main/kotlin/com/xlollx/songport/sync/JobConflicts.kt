package com.xlollx.songport.sync

import com.xlollx.songport.model.SyncJob

/**
 * Syncs that write to the same playlist. Two of them with the same source too are duplicates: the
 * second does nothing the first does not, and both running at once could add the same tracks twice.
 * Two with different sources merge into the target, which is fine, until one mirrors removals: it
 * would take away what the other adds, and the other add it back, on every run. Kotlin puro.
 */
object JobConflicts {
    /** "service|playlist" of [job]'s target; null while it has none yet (a playlist still to create). */
    fun targetKey(job: SyncJob): String? = job.target.playlistId?.takeIf { it.isNotBlank() }?.let { "${job.target.provider}|$it" }

    /** The other syncs writing to [job]'s target; one turned off writes nothing. */
    fun sharingTarget(jobs: List<SyncJob>, job: SyncJob): List<SyncJob> {
        val key = targetKey(job) ?: return emptyList()
        return jobs.filter { it.id != job.id && it.enabled && targetKey(it) == key }
    }

    /** The other syncs with [job]'s source and target both: the same sync twice. */
    fun duplicates(jobs: List<SyncJob>, job: SyncJob): List<SyncJob> =
        sharingTarget(jobs, job).filter { it.source.provider == job.source.provider && it.source.playlistId == job.source.playlistId }

    /** What saving [job] next to [jobs] calls for: nothing, a duplicate, or a target shared with removals. */
    sealed class Conflict {
        data class Duplicate(val other: SyncJob) : Conflict()
        data class SharedTarget(val others: List<SyncJob>) : Conflict()
    }

    fun of(jobs: List<SyncJob>, job: SyncJob): Conflict? {
        duplicates(jobs, job).firstOrNull()?.let { return Conflict.Duplicate(it) }
        val shared = sharingTarget(jobs, job)
        // Merging two sources into one playlist is a choice; it conflicts only when one of them mirrors.
        if (shared.isNotEmpty() && (job.mirrorRemovals || shared.any { it.mirrorRemovals })) return Conflict.SharedTarget(shared)
        return null
    }
}
