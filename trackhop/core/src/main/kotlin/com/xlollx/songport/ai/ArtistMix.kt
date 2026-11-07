package com.xlollx.songport.ai

import com.xlollx.songport.model.Track
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.long
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Matcher
import kotlinx.serialization.json.JsonElement
import kotlin.math.ceil
import kotlin.random.Random

/**
 * The playlist generator that needs no AI key: artists the user likes, the artists ListenBrainz
 * listeners play alongside them, and each one's most played recordings, mixed so that no artist
 * takes over. This file is the pure part (answers read, artists chosen, tracks mixed), tested with
 * saved answers; [ArtistMixSource] asks MusicBrainz and ListenBrainz for them.
 */
object ArtistMix {
    /** "More familiar" to "more discovery": how close the artists stay to the seeds, and how big the hits. */
    enum class Leaning(
        /** Most tracks taken from one seed artist. */
        val seedCap: Int,
        /** Most tracks taken from one similar artist (raised only when there are too few artists). */
        val similarCap: Int,
        /** Share of the playlist that comes from the seed artists themselves. */
        val seedShare: Double,
        /** The most similar artists left out: the obvious names, for discovery. */
        val skipTop: Int,
        /** How deep into each artist's most played recordings the picks reach. */
        val depth: Int,
        /** The biggest hits of each artist skipped (discovery wants the second tier). */
        val skipHits: Int,
    ) {
        FAMILIAR(seedCap = 5, similarCap = 3, seedShare = 0.35, skipTop = 0, depth = 6, skipHits = 0),
        BALANCED(seedCap = 3, similarCap = 2, seedShare = 0.2, skipTop = 0, depth = 12, skipHits = 0),
        DISCOVERY(seedCap = 1, similarCap = 2, seedShare = 0.05, skipTop = 8, depth = 25, skipHits = 2);

        companion object {
            fun of(name: String?): Leaning = entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: BALANCED
        }
    }

    /** An artist as MusicBrainz knows it; [score] is the similarity (or search score) it came with. */
    data class Artist(val mbid: String, val name: String, val score: Double = 0.0)

    /** An artist and their recordings, most played first. */
    data class Pool(val artist: Artist, val tracks: List<Track>)

    /** The lengths offered in the pickers; any other number works as well. */
    val LENGTHS = listOf(25, 50, 100)

    /** At most this many seed artists are looked up (each one costs a search and a similarity call). */
    const val MAX_SEEDS = 5

    // ---------------------------------------------------------------- answers

    /** A remix, live take, demo or the like, in brackets or after a dash: not the song as people know it. */
    private val VARIANT = Regex(
        """(\(|\[|\s[-–—]\s).*\b(remix|rmx|rework|re-?edit|extended|club mix|dub|live|demo|instrumental|karaoke|a ?cappella|acoustic|unplugged|session|rehearsal)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** True for a [VARIANT] title: left out of the mix, the original is among the artist's songs anyway. */
    fun isVariant(title: String): Boolean = VARIANT.containsMatchIn(title)

    /**
     * The artist a MusicBrainz search means: the first result (MusicBrainz sorts by its score), when
     * that score is decent. Null when nothing comes close.
     */
    fun parseArtistSearch(j: JsonElement?, minScore: Int = 60): Artist? =
        j["artists"].arr.firstOrNull()?.let { a ->
            val id = a["id"].str ?: return null
            val score = a["score"].int ?: 0
            if (score < minScore) null else Artist(id, a["name"].str ?: return null, score.toDouble())
        }

    /** Labs' similar artists, most similar first, without [reference] itself. */
    fun parseSimilar(j: JsonElement?, reference: String? = null): List<Artist> =
        j.arr.mapNotNull { a ->
            val id = a["artist_mbid"].str ?: return@mapNotNull null
            if (id == reference) return@mapNotNull null
            Artist(id, a["name"].str ?: return@mapNotNull null, (a["score"].long ?: 0L).toDouble())
        }.sortedByDescending { it.score }

    /**
     * An artist's recordings from ListenBrainz's popularity answer, most played first: only those
     * credited to [artistMbid], each song once (the live take or the remaster of a song already
     * listed is the same song), at most [limit].
     */
    fun parseTopRecordings(j: JsonElement?, artistMbid: String, limit: Int = 60): List<Track> {
        val seen = HashSet<String>()
        val out = ArrayList<Track>()
        for (r in j.arr) {
            if (out.size >= limit) break
            val mbids = r["artist_mbids"].arr.mapNotNull { it.str }
            if (mbids.isNotEmpty() && artistMbid !in mbids) continue
            val title = r["recording_name"].str?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val id = r["recording_mbid"].str ?: continue
            val artist = r["artist_name"].str?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (isVariant(title)) continue
            val t = Track(id = id, title = title, artists = listOf(artist), album = r["release_name"].str.orEmpty(), durationMs = r["length"].long ?: 0L)
            if (seen.add(Matcher.normalizeTitle(title).ifEmpty { title.lowercase() })) out += t
        }
        return out
    }

    /**
     * The Deezer artist a search means by [name]: among those with the very same name, the one with
     * the most fans (a tribute act or a homonym has few). Null when no name matches.
     */
    fun parseDeezerArtist(j: JsonElement?, name: String): Long? {
        val want = Matcher.normalizeArtist(name)
        return j["data"].arr.filter { Matcher.normalizeArtist(it["name"].str.orEmpty()) == want }
            .maxByOrNull { it["nb_fan"].long ?: 0L }?.get("id").long
    }

    /** Deezer's top tracks of an artist, most played first, each song once, credited to [artistName]. */
    fun parseDeezerTop(j: JsonElement?, artistName: String, limit: Int = 60): List<Track> {
        val want = Matcher.normalizeArtist(artistName)
        val seen = HashSet<String>()
        val out = ArrayList<Track>()
        for (d in j["data"].arr) {
            if (out.size >= limit) break
            val artist = d["artist"]["name"].str ?: continue
            if (Matcher.normalizeArtist(artist) != want) continue
            val title = d["title"].str?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val id = d["id"].long ?: continue
            if (isVariant(title)) continue
            val t = Track(id = "deezer:$id", title = title, artists = listOf(artist), album = d["album"]["title"].str.orEmpty(), durationMs = (d["duration"].long ?: 0L) * 1000)
            if (seen.add(Matcher.normalizeTitle(title).ifEmpty { title.lowercase() })) out += t
        }
        return out
    }

    // ---------------------------------------------------------------- seeds

    /** Artist names typed by the user: one per line or separated by commas or semicolons, each once. */
    fun parseSeeds(text: String): List<String> {
        val seen = HashSet<String>()
        return text.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() && seen.add(Matcher.normalizeArtist(it).ifEmpty { it.lowercase() }) }
    }

    /** The [n] artists a playlist holds most, as written in it: what "based on a playlist of mine" starts from. */
    fun topArtists(tracks: List<Track>, n: Int = MAX_SEEDS): List<String> {
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        for (t in tracks) {
            val name = t.artists.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val key = Matcher.normalizeArtist(name).ifEmpty { name.lowercase() }
            val (shown, c) = counts[key] ?: (name to 0)
            counts[key] = shown to c + 1
        }
        // Ties keep the playlist's order: sortedBy is stable.
        return counts.values.sortedByDescending { it.second }.take(n).map { it.first }
    }

    // ---------------------------------------------------------------- artists

    /**
     * One ranking out of the similar artists of every seed: each list's scores scaled to its best
     * (a famous seed's numbers are not bigger votes), summed, so an artist close to two seeds comes
     * before one close to a single seed. The seeds themselves are left out.
     */
    fun rankSimilar(lists: List<List<Artist>>, seeds: Collection<String>): List<Artist> {
        val total = LinkedHashMap<String, Artist>()
        for (list in lists) {
            val best = list.maxOfOrNull { it.score }?.takeIf { it > 0 } ?: 1.0
            for (a in list) {
                if (a.mbid in seeds) continue
                val prev = total[a.mbid]
                total[a.mbid] = a.copy(score = (prev?.score ?: 0.0) + a.score / best)
            }
        }
        return total.values.sortedByDescending { it.score }
    }

    /**
     * Which of the [ranked] similar artists to use, [n] of them, in rank order. Familiar takes the
     * closest; balanced draws from the closest few dozen, the closer the likelier; discovery leaves
     * out the most obvious names and draws evenly from the rest.
     */
    fun pickArtists(ranked: List<Artist>, n: Int, leaning: Leaning, random: Random): List<Artist> {
        if (n <= 0 || ranked.isEmpty()) return emptyList()
        if (n >= ranked.size) return ranked
        val chosen: List<Artist> = when (leaning) {
            Leaning.FAMILIAR -> ranked.take(n)
            Leaning.BALANCED -> weightedSample(ranked.take(maxOf(n * 2, 20)), n, random) { 1.0 / (ranked.indexOf(it) + 1) + 0.02 }
            Leaning.DISCOVERY -> {
                val skip = minOf(leaning.skipTop, ranked.size - n)
                ranked.drop(skip).shuffled(random).take(n)
            }
        }
        val order = ranked.withIndex().associate { it.value.mbid to it.index }
        return chosen.sortedBy { order[it.mbid] ?: Int.MAX_VALUE }
    }

    private fun <T> weightedSample(items: List<T>, n: Int, random: Random, weight: (T) -> Double): List<T> {
        val left = items.toMutableList()
        val out = ArrayList<T>()
        while (out.size < n && left.isNotEmpty()) {
            val weights = left.map { weight(it).coerceAtLeast(0.0) }
            var x = random.nextDouble() * weights.sum()
            var i = 0
            while (i < left.size - 1 && x >= weights[i]) { x -= weights[i]; i++ }
            out += left.removeAt(i)
        }
        return out
    }

    /** How many similar artists to fetch for [count] tracks, with a margin for those without data. */
    fun similarNeeded(count: Int, seeds: Int, leaning: Leaning): Int {
        val fromSeeds = minOf(seeds * leaning.seedCap, (count * leaning.seedShare).toInt())
        return ceil((count - fromSeeds).coerceAtLeast(0) * 1.25 / leaning.similarCap).toInt() + 2
    }

    // ---------------------------------------------------------------- mix

    /**
     * Up to [count] tracks out of the [seeds]' and the [similar] artists' pools: a share from the
     * seed artists (as [leaning] says), the rest a few per similar artist, never twice the same song
     * and nothing [exclude] holds (by artist and title, like [AiMatch.key]). The order spreads every
     * artist over the whole list, so any first part of it keeps the mix, and two tracks of the same
     * artist are never next to each other while another artist can go between.
     */
    fun mix(seeds: List<Pool>, similar: List<Pool>, count: Int, leaning: Leaning, random: Random, exclude: Collection<Track> = emptyList()): List<Track> {
        if (count <= 0) return emptyList()
        val taken = exclude.mapTo(HashSet()) { AiMatch.key(it) }
        val picks = LinkedHashMap<String, MutableList<Track>>()
        var total = 0

        fun window(p: Pool): List<Track> {
            val skip = if (p.tracks.size > leaning.skipHits + 3) leaning.skipHits else 0
            val w = p.tracks.drop(skip).take(leaning.depth)
            // Familiar keeps the hits in their order; the others draw from the window.
            return if (leaning == Leaning.FAMILIAR) w else w.shuffled(random)
        }

        fun take(p: Pool, cap: Int, budget: Int, from: List<Track>): Int {
            val mine = picks.getOrPut(p.artist.mbid) { ArrayList() }
            var added = 0
            for (t in from) {
                if (mine.size >= cap || added >= budget || total >= count) break
                if (!taken.add(AiMatch.key(t))) continue
                mine += t; added++; total++
            }
            return added
        }

        // The seeds' share, split evenly among them.
        val seedBudget = if (similar.isEmpty()) count else maxOf(if (seeds.isEmpty()) 0 else 1, (count * leaning.seedShare).toInt())
        val windows = HashMap<String, List<Track>>()
        fun windowOf(p: Pool) = windows.getOrPut(p.artist.mbid) { window(p) }
        if (seeds.isNotEmpty()) {
            val each = ceil(seedBudget.toDouble() / seeds.size).toInt()
            var left = seedBudget
            for (p in seeds) left -= take(p, if (similar.isEmpty()) count else leaning.seedCap, minOf(each, left), windowOf(p))
        }
        // The similar artists, a few each; when they run short, a few more each, then deeper picks.
        var cap = leaning.similarCap
        while (total < count && cap <= leaning.similarCap + 4) {
            for (p in similar) take(p, cap, count, windowOf(p))
            if (total < count) {
                for (p in similar) take(p, cap, count, p.tracks)
                for (p in seeds) take(p, leaning.seedCap + cap - leaning.similarCap, count, p.tracks)
            }
            cap++
        }
        return spread(picks.values.filter { it.isNotEmpty() }, random)
    }

    /**
     * Every artist's tracks spread over the list in proportion (the i-th of an artist's k tracks
     * lands near i/k of the way), then neighbours of the same artist pulled apart where possible.
     */
    internal fun spread(groups: List<List<Track>>, random: Random): List<Track> {
        val placed = ArrayList<Pair<Double, Track>>()
        // Each track lands at a random point of its own stretch: no two artists keep the same order round after round.
        for (g in groups) g.forEachIndexed { i, t -> placed += (i + random.nextDouble()) / g.size to t }
        val out = placed.sortedBy { it.first }.map { it.second }.toMutableList()
        fun artist(t: Track) = AiMatch.key(t).substringBefore('|')
        for (i in 1 until out.size) {
            if (artist(out[i]) != artist(out[i - 1])) continue
            // The next track of another artist moves up between the two.
            val j = (i + 1 until out.size).firstOrNull { artist(out[it]) != artist(out[i - 1]) } ?: continue
            val t = out.removeAt(j); out.add(i, t)
        }
        return out
    }
}
