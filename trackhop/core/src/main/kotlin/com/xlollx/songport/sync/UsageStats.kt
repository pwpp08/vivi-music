package com.xlollx.songport.sync

import com.xlollx.songport.data.FileStore
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * "Trackhop in numbers": what the syncs have done, counted only from what the app already keeps
 * (the jobs and the run history in [StoreData]). Nothing is estimated except the time saved, which
 * is a stated rule ([SECONDS_PER_TRACK] per track moved), and a figure the history cannot back is
 * left out (null or empty) rather than guessed. The history keeps the latest
 * [FileStore.MAX_REPORTS] runs, so "all time" means "the runs still on record".
 */
object UsageStats {
    /** The time saved is an estimate: this many seconds to find and add one track by hand. */
    const val SECONDS_PER_TRACK = 30

    /** Fewer listed tracks than this and the top artists say nothing: they are left out. */
    const val MIN_TRACKS_FOR_ARTISTS = 20

    /** An artist added once is no favourite. */
    const val MIN_ARTIST_TRACKS = 2

    const val TOP_ARTISTS = 5

    /** A source → target pair of services, by connector id (for the logos), with what ran on it. */
    data class Route(val sourceProvider: String, val targetProvider: String, val runs: Int, val added: Int)

    /** The biggest source among the current syncs, as its latest run counted it. */
    data class Longest(val jobId: String, val name: String, val tracks: Int, val sourceProvider: String, val targetProvider: String)

    data class ArtistCount(val name: String, val tracks: Int)

    data class Summary(
        /** Completed runs on record (failed and unfinished runs are not counted). */
        val runs: Int,
        /** When the oldest run on record started; 0 when there is none. */
        val sinceEpoch: Long,
        /** The history is full: older runs were dropped and are not in these numbers. */
        val historyCapped: Boolean,
        /** Tracks added to targets, over the runs on record. */
        val added: Int,
        /** Tracks added by runs started in the calendar month of "now", in the given time zone. */
        val addedThisMonth: Int,
        /** Syncs set up now. */
        val syncs: Int,
        /** The route with the most completed runs (ties: more tracks); null when no run belongs to a current sync. */
        val route: Route?,
        /** Tracks found on the target, out of those looked for, in each current sync's latest run. */
        val matchFound: Int,
        val matchSearched: Int,
        val longest: Longest?,
        /** The most added artists; empty when [artistSample] is under [MIN_TRACKS_FOR_ARTISTS]. */
        val topArtists: List<ArtistCount>,
        /** Listed added tracks the artists were counted over (older runs kept only a count). */
        val artistSample: Int,
    ) {
        val isEmpty: Boolean get() = runs == 0

        /** Whole percent, rounded down so that "100%" only ever means every track; null with nothing looked for. */
        val matchPercent: Int? get() = if (matchSearched <= 0) null else (matchFound.toLong() * 100 / matchSearched).toInt()

        /** The estimate: [SECONDS_PER_TRACK] per track moved. */
        val secondsSaved: Long get() = added.toLong() * SECONDS_PER_TRACK
    }

    /** Start (inclusive) and end (exclusive) of the calendar month holding [now], in [zone]. */
    fun monthBounds(now: Long, zone: ZoneId): Pair<Long, Long> {
        val start = Instant.ofEpochMilli(now).atZone(zone).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)
        return start.toInstant().toEpochMilli() to start.plusMonths(1).toInstant().toEpochMilli()
    }

    fun compute(data: StoreData, now: Long, zone: ZoneId, historyLimit: Int = FileStore.MAX_REPORTS): Summary {
        // A run still going (or cut off before its end) has not added anything yet: left out.
        val finished = data.reports.filter { !it.partial }
        val completed = finished.filter { it.ok }
        val (monthStart, monthEnd) = monthBounds(now, zone)
        val jobs = data.jobs.associateBy { it.id }

        // Routes: by service family, so two Spotify accounts or the web and official routes count as one.
        class Acc(val source: String, val target: String) { var runs = 0; var added = 0 }
        val routes = LinkedHashMap<String, Acc>()
        for (r in completed) {
            val job = jobs[r.jobId] ?: continue
            if (job.source.provider.isBlank() || job.target.provider.isBlank()) continue
            val key = FileStore.family(job.source.provider) + ">" + FileStore.family(job.target.provider)
            // Reports are newest first: the logos follow the connectors used most recently.
            val acc = routes.getOrPut(key) { Acc(job.source.provider, job.target.provider) }
            acc.runs++; acc.added += r.added
        }
        val route = routes.values.maxWithOrNull(compareBy<Acc>({ it.runs }, { it.added }))
            ?.let { Route(it.source, it.target, it.runs, it.added) }

        // Each current sync, as its latest run that read the source saw it ("unchanged" runs read nothing).
        val latest: List<Pair<SyncJob, SyncReport>> = data.jobs.mapNotNull { job ->
            completed.firstOrNull { it.jobId == job.id && it.sourceCount > 0 }?.let { job to it }
        }
        var searched = 0; var found = 0
        for ((_, r) in latest) {
            val looked = (r.sourceCount - r.ignored).coerceAtLeast(0)
            searched += looked
            found += (looked - r.unmatched.size).coerceAtLeast(0)
        }
        val longest = latest.maxByOrNull { it.second.sourceCount }?.let { (job, r) ->
            Longest(job.id, job.name.ifBlank { r.jobName }.ifBlank { job.source.playlistName }, r.sourceCount, job.source.provider, job.target.provider)
        }

        // Artists: the first credited artist of each song listed as added (albums, artists and
        // podcasts are not songs); spellings that differ only in case count together.
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        var sample = 0
        for (r in finished) for (t in r.addedTracks) {
            if (t.kind.isNotEmpty()) continue
            val artist = t.artists.firstOrNull()?.trim().orEmpty()
            if (artist.isEmpty()) continue
            sample++
            val key = artist.lowercase()
            val prev = counts[key]
            counts[key] = (prev?.first ?: artist) to ((prev?.second ?: 0) + 1)
        }
        val top = if (sample < MIN_TRACKS_FOR_ARTISTS) emptyList() else counts.values
            .filter { it.second >= MIN_ARTIST_TRACKS }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
            .take(TOP_ARTISTS)
            .map { ArtistCount(it.first, it.second) }

        return Summary(
            runs = completed.size,
            sinceEpoch = finished.minOfOrNull { it.startedEpoch } ?: 0L,
            historyCapped = data.reports.size >= historyLimit,
            added = finished.sumOf { it.added },
            addedThisMonth = finished.filter { it.startedEpoch in monthStart until monthEnd }.sumOf { it.added },
            syncs = data.jobs.size,
            route = route,
            matchFound = found,
            matchSearched = searched,
            longest = longest,
            topArtists = top,
            artistSample = sample,
        )
    }
}
