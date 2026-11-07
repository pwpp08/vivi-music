package com.xlollx.songport.sync

import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import java.util.Locale

/**
 * Find and filter on the Syncs tab, for someone with many syncs: a search over the names, and
 * chips by status, by service and by colour.
 *
 * Choices in one group widen (errors OR running), groups narrow each other (errors AND Spotify).
 * An empty filter keeps every sync, in the order given.
 */
data class SyncFilter(
    val query: String = "",
    val statuses: Set<Status> = emptySet(),
    /** Provider ids (an account: "spotify", "spotify@2"): a sync matches when its source or target is one. */
    val services: Set<String> = emptySet(),
    /** Colour keys (SyncJob.color). */
    val colors: Set<String> = emptySet(),
) {
    /**
     * By the last run: UP_TO_DATE ran fine with nothing left to look at; NEEDS_FIX ran but left songs
     * not found, matches to check or removals held back; ERROR failed; NEVER_RUN has not run yet.
     * RUNNING and DISABLED are about now, whatever the last run was.
     */
    enum class Status { UP_TO_DATE, ERROR, NEEDS_FIX, RUNNING, NEVER_RUN, DISABLED }

    val isActive: Boolean get() = query.isNotBlank() || statuses.isNotEmpty() || services.isNotEmpty() || colors.isNotEmpty()

    fun toggle(status: Status) = copy(statuses = statuses.toggled(status))
    fun toggleService(id: String) = copy(services = services.toggled(id))
    fun toggleColor(key: String) = copy(colors = colors.toggled(key))

    /**
     * Without the services and colours no sync uses any more (a sync deleted or recoloured since):
     * their chips are gone, so they could not be turned off and would hide every card.
     */
    fun pruned(jobs: List<SyncJob>): SyncFilter {
        val s = services.intersect(servicesInUse(jobs).toSet())
        val c = colors.intersect(colorsInUse(jobs))
        return if (s.size == services.size && c.size == colors.size) this else copy(services = s, colors = c)
    }

    /**
     * The syncs this filter keeps. [running] are the ids of the syncs running now; [serviceName]
     * gives what a service is called on screen (its label, the account name), so the search finds
     * "spotify" in a sync that only names its playlists.
     */
    fun select(
        jobs: List<SyncJob>,
        reports: List<SyncReport>,
        running: Set<String>,
        serviceName: (String) -> String = { it },
    ): List<SyncJob> {
        if (!isActive) return jobs
        val terms = terms(query)
        val last = lastReports(jobs, reports)
        return jobs.filter { job ->
            (statuses.isEmpty() || statuses.any { s -> has(job, s, last, running) }) &&
                (services.isEmpty() || job.source.provider in services || job.target.provider in services) &&
                (colors.isEmpty() || job.color in colors) &&
                (terms.isEmpty() || haystack(job, serviceName).let { h -> terms.all { it in h } })
        }
    }

    companion object {
        /** How many syncs each status holds now, for the numbers on the chips. */
        fun counts(jobs: List<SyncJob>, reports: List<SyncReport>, running: Set<String>): Map<Status, Int> {
            val last = lastReports(jobs, reports)
            return Status.entries.associateWith { s -> jobs.count { has(it, s, last, running) } }
        }

        private fun lastReports(jobs: List<SyncJob>, reports: List<SyncReport>): Map<String, SyncReport> {
            val ids = jobs.mapNotNullTo(HashSet()) { it.lastReportId }
            return reports.filter { it.id in ids }.associateBy { it.id }
        }

        private fun has(job: SyncJob, s: Status, last: Map<String, SyncReport>, running: Set<String>): Boolean {
            val r = job.lastReportId?.let { last[it] }
            return when (s) {
                Status.ERROR -> r != null && !r.ok
                Status.NEEDS_FIX -> r != null && r.ok && needsFix(r)
                Status.UP_TO_DATE -> r != null && r.ok && !needsFix(r) && !r.partial
                Status.NEVER_RUN -> job.lastReportId == null
                Status.RUNNING -> job.id in running
                Status.DISABLED -> !job.enabled
            }
        }

        private fun needsFix(r: SyncReport) = r.unmatched.isNotEmpty() || r.reviewTracks.isNotEmpty() || r.heldBack > 0

        /** The services the syncs use, each once, in the order the list shows them. */
        fun servicesInUse(jobs: List<SyncJob>): List<String> =
            jobs.flatMap { listOf(it.source.provider, it.target.provider) }.filter { it.isNotBlank() }.distinct()

        /** The colours the syncs wear, each once (the default look is not one). */
        fun colorsInUse(jobs: List<SyncJob>): Set<String> = jobs.mapNotNullTo(LinkedHashSet()) { it.color }

        /** Lower case, without accents, split on spaces: "Café  Mix" -> [cafe, mix]. */
        fun terms(query: String): List<String> = fold(query).split(' ').filter { it.isNotEmpty() }

        private val SPACES = Regex("\\s+")

        private fun fold(s: String): String = Matcher.stripDiacritics(s).lowercase(Locale.ROOT).replace(SPACES, " ").trim()

        private fun haystack(job: SyncJob, serviceName: (String) -> String): String = fold(
            listOf(
                job.name, job.source.playlistName, job.target.playlistName,
                serviceName(job.source.provider), serviceName(job.target.provider),
            ).joinToString(" "),
        )

        private fun <T> Set<T>.toggled(x: T): Set<T> = if (x in this) this - x else this + x
    }
}
