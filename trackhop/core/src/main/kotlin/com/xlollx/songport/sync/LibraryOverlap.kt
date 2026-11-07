package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import kotlin.math.roundToInt

/**
 * "Two subscriptions?": how much of the user's music two services share (liked songs and playlist
 * tracks, each side counted once), matched like a sync matches: first the match cache of earlier
 * syncs, then ISRC, title, artist and duration ([Matcher.TrackIndex]). Pure Kotlin, covered by tests.
 *
 * It only measures: it never says which service is better, and the prices are the user's own.
 */
object LibraryOverlap {

    data class Result(
        val sizeA: Int,
        val sizeB: Int,
        val common: List<Compatibility.Common>,
        val onlyA: List<Track>,
        val onlyB: List<Track>,
    ) {
        /** Tracks on either side, the common ones once. */
        val union: Int get() = sizeA + sizeB - common.size
        /** Share of all the music (either side) that is on both, 0–100. */
        val percent: Int get() = if (union == 0) 0 else (100.0 * common.size / union).roundToInt()
    }

    /** One copy of each song: the same track in two playlists (or liked and in a playlist) counts once. */
    fun distinct(tracks: List<Track>): List<Track> =
        tracks.filter { it.kind.isEmpty() && it.title.isNotBlank() }.distinctBy { it.id.ifBlank { Wrapped.key(it) } }.distinctBy { Wrapped.key(it) }

    /**
     * [cached] gives, for a track of A, the id an earlier sync matched it to on B's service (null
     * when none): those count as common without a search.
     */
    fun compute(a: List<Track>, b: List<Track>, cached: (Track) -> String? = { null }): Result {
        val da = distinct(a)
        val db = distinct(b)
        val byId = db.associateBy { it.id }
        val index = Matcher.TrackIndex(db)
        val usedB = HashSet<String>()
        val common = ArrayList<Compatibility.Common>()
        val onlyA = ArrayList<Track>()
        for (t in da) {
            val m = cached(t)?.let { byId[it] }?.takeIf { it.id !in usedB } ?: index.best(t)?.takeIf { it.id !in usedB }
            if (m != null) { usedB += m.id; common += Compatibility.Common(t, m) } else onlyA += t
        }
        val onlyB = db.filter { it.id !in usedB }
        return Result(da.size, db.size, common, onlyA, onlyB)
    }

    /** A price as the user types it ("10,99", "€ 9.99", "11"); null when there is no number. */
    fun parsePrice(text: String): Double? {
        val digits = Regex("""\d+(?:[.,]\d{1,2})?""").find(text.replace(" ", ""))?.value ?: return null
        return digits.replace(',', '.').toDoubleOrNull()
    }
}
