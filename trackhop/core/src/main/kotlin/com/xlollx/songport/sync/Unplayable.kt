package com.xlollx.songport.sync

import com.xlollx.songport.model.MatchPolicy
import com.xlollx.songport.model.Track
import com.xlollx.songport.model.UnplayableEntry

/**
 * The "Unplayable tracks" tool, the part without a service: which candidate replaces a dead entry,
 * and where the replacement goes. Pure Kotlin, covered by tests.
 *
 * A track goes grey on a service when it is pulled from the catalogue, loses its licence in the
 * user's country or falls outside the plan. The same recording is usually still there under another
 * id (another edition, a re-release): the sync's own matching finds it, ISRC first, then title,
 * artist and duration.
 */
object Unplayable {
    /** Why the service refuses the entry, as short codes the UI names; "" when it did not say. */
    const val REASON_MARKET = "market"
    const val REASON_PRODUCT = "product"
    const val REASON_EXPLICIT = "explicit"
    const val REASON_PRIVATE = "private"
    const val REASON_DELETED = "deleted"

    private const val GREY_OUT = "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT"

    /** TIDAL says what a track allows (STREAM, DJ, STEM): playable when STREAM is among them; null when it said nothing. */
    fun tidalStreamable(availability: List<String>?): Boolean? = availability?.any { it.equals("STREAM", ignoreCase = true) }

    /** YouTube Music greys an entry out through its display policy; no policy means a normal entry. */
    fun ytmAvailable(displayPolicy: String?): Boolean = displayPolicy != GREY_OUT

    /**
     * The candidates that could stand in for [dead], best first: never the dead entry itself, never
     * another entry the service cannot play, and only those the sync would accept as the same
     * recording under [policy]. The threshold is the sync's own acceptance threshold.
     */
    fun ranked(dead: Track, candidates: List<Track>, policy: MatchPolicy = MatchPolicy()): List<Matcher.Scored> =
        candidates.asSequence()
            .filter { it.id != dead.id && it.available != false }
            .distinctBy { it.id }
            .map { Matcher.Scored(it, Matcher.score(dead, it, policy)) }
            .filter { it.score >= policy.acceptThreshold }
            .sortedByDescending { it.score }
            .toList()

    /**
     * What to propose for [entry]: the service's own playable version when it offers one (a sure
     * thing), else the best ranked candidate, or null when nothing convincing came back.
     */
    fun proposal(entry: UnplayableEntry, candidates: List<Track>, policy: MatchPolicy = MatchPolicy()): Matcher.Scored? =
        entry.relinked?.let { Matcher.Scored(it, 1.0) } ?: ranked(entry.track, candidates, policy).firstOrNull()

    /** A proposal good enough to apply without a look: at or above the sync's review threshold. */
    fun confident(scored: Matcher.Scored?, policy: MatchPolicy = MatchPolicy()): Boolean =
        scored != null && scored.score >= policy.reviewThreshold

    /**
     * The order a playlist should have once the fixes are applied: [current] (the listing after
     * the replacements were added and the dead entries removed) with each replacement moved to the
     * spot its dead entry had. Entries that the listing does not hold are left alone; a replacement
     * already in the right place is not moved.
     */
    fun orderAfter(current: List<Track>, fixes: List<Pair<UnplayableEntry, Track>>): List<Track> {
        val out = current.toMutableList()
        for ((entry, replacement) in fixes.sortedBy { it.first.index }) {
            val from = out.indexOfFirst { it.id == replacement.id }
            if (from < 0) continue
            val item = out.removeAt(from)
            out.add(entry.index.coerceIn(0, out.size), item)
        }
        return out
    }
}
