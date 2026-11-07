package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "How compatible are we?": two playlists, from any services, compared with the same matching the
 * sync uses (ISRC, then title, artist and duration). Pure Kotlin, covered by tests.
 *
 * The score (0–100) is 70% how much of the smaller playlist is also in the other one, plus 30% how
 * many of the smaller side's artists appear on the other side. The smaller side as the base, so
 * a short playlist that is entirely inside a long one scores high rather than being drowned.
 */
object Compatibility {

    /** One track present on both sides: [a] as the first playlist lists it, [b] as the second one does. */
    data class Common(val a: Track, val b: Track)

    data class Result(
        val sizeA: Int,
        val sizeB: Int,
        val common: List<Common>,
        /** Artists on both sides, spelled as the first playlist spells them, most shared tracks first. */
        val sharedArtists: List<String>,
        /** Tracks of B by shared artists that A does not have: what the first side would likely enjoy. */
        val forA: List<Track>,
        /** The same the other way round. */
        val forB: List<Track>,
        val score: Int,
    ) {
        val commonPercent: Int get() = if (min(sizeA, sizeB) == 0) 0 else (100.0 * common.size / min(sizeA, sizeB)).roundToInt()
    }

    private fun artistsOf(tracks: List<Track>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (t in tracks) for (a in t.artists) {
            val key = Matcher.normalizeArtist(a)
            if (key.isNotEmpty()) out.putIfAbsent(key, a.trim())
        }
        return out
    }

    fun compare(a: List<Track>, b: List<Track>): Result {
        val index = Matcher.TrackIndex(b)
        val common = ArrayList<Common>()
        val usedB = HashSet<String>()
        val usedA = HashSet<String>()
        for (t in a) {
            if (!usedA.add(t.id)) continue
            val m = index.best(t) ?: continue
            if (usedB.add(m.id)) common += Common(t, m)
        }
        val artistsA = artistsOf(a)
        val artistsB = artistsOf(b)
        val shared = artistsA.keys.intersect(artistsB.keys)
        // Order the shared artists by how many of the common tracks they sign.
        val weight = HashMap<String, Int>()
        for (c in common) for (name in c.a.artists) {
            val k = Matcher.normalizeArtist(name)
            if (k in shared) weight[k] = (weight[k] ?: 0) + 1
        }
        val sharedArtists = shared.sortedWith(compareByDescending<String> { weight[it] ?: 0 }.thenBy { artistsA[it] }).map { artistsA.getValue(it) }

        fun suggestions(from: List<Track>, taken: Set<String>): List<Track> = from
            .filter { t -> t.id !in taken && t.artists.any { Matcher.normalizeArtist(it) in shared } }
            .distinctBy { Matcher.normalizeTitle(it.title) + "|" + it.artists.firstOrNull()?.let { a -> Matcher.normalizeArtist(a) } }
            .take(10)
        val forA = suggestions(b, usedB)
        val forB = suggestions(a, common.map { it.a.id }.toSet())

        val smallest = min(a.distinctBy { it.id }.size, b.distinctBy { it.id }.size)
        val trackPart = if (smallest == 0) 0.0 else common.size.toDouble() / smallest
        val fewestArtists = min(artistsA.size, artistsB.size)
        val artistPart = if (fewestArtists == 0) 0.0 else shared.size.toDouble() / fewestArtists
        val score = (100.0 * (0.7 * trackPart + 0.3 * artistPart)).roundToInt().coerceIn(0, 100)
        return Result(a.size, b.size, common, sharedArtists, forA, forB, score)
    }
}
