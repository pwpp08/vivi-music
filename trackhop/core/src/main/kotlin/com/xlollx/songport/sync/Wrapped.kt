package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import kotlinx.serialization.Serializable

/**
 * "Your Wrapped, whenever you want": a few playful figures about the user's playlists, computed on
 * the phone from tracks the app has already read. Pure Kotlin, covered by tests.
 *
 * The "musical age" is a joke with a rule behind it: people tend to love most the music that came
 * out when they were about 17, so the median release year of the tracks, minus 17, is the year
 * the user "listens like someone born in"; the age is the years since then.
 *
 * Each Wrapped made is kept ([Saved]: the figures and the services, never the tracks) in a short
 * history ([remember], at most [MAX_HISTORY]), so the page opens on the last one and an older one
 * can be shared again.
 */
object Wrapped {
    /** The age at which, the joke goes, one's taste is set. */
    const val FORMATIVE_AGE = 17

    /** Fewer dated tracks than this and the musical age is not told: a guess from three songs says nothing. */
    const val MIN_DATED = 5

    /** Artists and decades kept in a Wrapped. */
    const val TOP_ARTISTS = 5
    const val TOP_DECADES = 4

    /** Wrapped kept in the history. */
    const val MAX_HISTORY = 20

    /** Covers kept with a Wrapped (a 3×3 mosaic), and the songs looked up at most to find them. */
    const val MAX_COVERS = 9
    const val COVER_LOOKUPS = 24

    /** An artist and the different songs of theirs in the playlists. */
    @Serializable
    data class Artist(val name: String, val tracks: Int)

    /** A decade (its first year, 1990), its songs and their share of the dated songs, in whole percent. */
    @Serializable
    data class Decade(val decade: Int, val tracks: Int, val percent: Int)

    /** A song named on the card: the oldest one, the one added last. */
    @Serializable
    data class Song(val title: String, val artist: String, val year: Int = 0, val addedAt: Long = 0)

    /** A service as the Wrapped shows it: its plain brand name, the id that picks its glyph, its colour (ARGB). */
    @Serializable
    data class Service(val serviceId: String, val name: String, val color: Long = 0)

    data class Summary(
        val topArtist: String?,
        val topArtistTracks: Int,
        /** The decade with most tracks, as its first year (1990); null when no track has a year. */
        val topDecade: Int?,
        /** Different tracks across all playlists read (the same song in two playlists counts once). */
        val trackCount: Int,
        val medianYear: Int?,
        /** "You listen like someone born in …"; null with fewer than [MIN_DATED] dated tracks. */
        val bornIn: Int?,
        val age: Int?,
        /** Up to [TOP_ARTISTS], the most songs first. */
        val topArtists: List<Artist> = emptyList(),
        /** Up to [TOP_DECADES], the biggest share first. */
        val decades: List<Decade> = emptyList(),
        /** Different artists (Various Artists left out). */
        val artistCount: Int = 0,
        /** The songs' length added up; 0 when the services did not say. */
        val durationMs: Long = 0,
        val oldest: Song? = null,
        /** The song added last to a playlist (or liked last), when the services date that. */
        val newest: Song? = null,
    )

    /**
     * One Wrapped as it is kept: its figures, when it was made and from which services. Small (no
     * tracks), so twenty of them sit in the app's store.
     */
    @Serializable
    data class Saved(
        val id: String,
        val madeAt: Long,
        /** The year it speaks of ("My 2026 so far"). */
        val year: Int,
        val trackCount: Int,
        val age: Int? = null,
        val bornIn: Int? = null,
        val topArtists: List<Artist> = emptyList(),
        val decades: List<Decade> = emptyList(),
        val artistCount: Int = 0,
        val durationMs: Long = 0,
        val oldest: Song? = null,
        val newest: Song? = null,
        val services: List<Service> = emptyList(),
        /**
         * The addresses of a few album covers of the artists that appear most ([coverCandidates]),
         * best first, for the card's art and colours; empty in a Wrapped made before they were kept.
         */
        val covers: List<String> = emptyList(),
    ) {
        val topArtist: String? get() = topArtists.firstOrNull()?.name
    }

    /** The year one "is born in" and the age it gives in [currentYear]. */
    fun musicalAge(medianYear: Int, currentYear: Int): Pair<Int, Int> {
        val born = minOf(medianYear, currentYear) - FORMATIVE_AGE
        return born to (currentYear - born)
    }

    /** The middle year (the lower of the two middle ones for an even count); null for none. */
    fun median(years: List<Int>): Int? {
        if (years.isEmpty()) return null
        val s = years.sorted()
        return s[(s.size - 1) / 2]
    }

    /** A track's identity across playlists and services: its ISRC, else its normalized title and first artist. */
    fun key(t: Track): String =
        t.isrcNorm ?: (Matcher.normalizeTitle(t.title) + "|" + (t.artists.firstOrNull()?.let { Matcher.normalizeArtist(it) } ?: ""))

    private fun song(t: Track) = Song(t.title.trim(), t.artists.firstOrNull { it.isNotBlank() }?.trim().orEmpty(), t.year, t.addedAt)

    fun compute(tracks: List<Track>, currentYear: Int): Summary {
        val songs = tracks.filter { it.kind.isEmpty() && it.title.isNotBlank() }.distinctBy { key(it) }
        // The artists signing most different tracks, spelled as first met.
        val counts = HashMap<String, Int>()
        val spelling = LinkedHashMap<String, String>()
        for (t in songs) for (a in t.artists.distinct()) {
            val k = Matcher.normalizeArtist(a)
            if (k.isEmpty() || k == "various artists") continue
            spelling.putIfAbsent(k, a.trim())
            counts[k] = (counts[k] ?: 0) + 1
        }
        val ranked = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val top = ranked.take(TOP_ARTISTS).map { Artist(spelling.getValue(it.key), it.value) }
        val validYear = 1900..(currentYear + 1)
        val dated = songs.filter { it.year in validYear }
        val years = dated.map { it.year }
        val decades = years.groupingBy { it / 10 * 10 }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .take(TOP_DECADES).map { Decade(it.key, it.value, Math.round(it.value * 100.0 / years.size).toInt()) }
        val median = median(years)
        val age = median?.takeIf { years.size >= MIN_DATED }?.let { musicalAge(it, currentYear) }
        val oldest = dated.minByOrNull { it.year }
        val newest = songs.filter { it.addedAt > 0 }.maxByOrNull { it.addedAt }
        return Summary(
            topArtist = top.firstOrNull()?.name, topArtistTracks = top.firstOrNull()?.tracks ?: 0,
            topDecade = decades.firstOrNull()?.decade, trackCount = songs.size, medianYear = median,
            bornIn = age?.first, age = age?.second,
            topArtists = top, decades = decades, artistCount = counts.size,
            durationMs = songs.sumOf { it.durationMs.coerceAtLeast(0) },
            oldest = oldest?.let { song(it) }, newest = newest?.let { song(it) },
        )
    }

    /** [s] as it is kept, made at [madeAt] from [services], with the [covers] found for it ([covers] keeps the first [MAX_COVERS] different ones). */
    fun save(s: Summary, services: List<Service>, year: Int, madeAt: Long, id: String = madeAt.toString(36), covers: List<String?> = emptyList()): Saved = Saved(
        id = id, madeAt = madeAt, year = year, trackCount = s.trackCount, age = s.age, bornIn = s.bornIn,
        topArtists = s.topArtists, decades = s.decades, artistCount = s.artistCount, durationMs = s.durationMs,
        oldest = s.oldest, newest = s.newest, services = services, covers = covers(covers),
    )

    // ------------------------------------------------------------------ covers

    /**
     * The songs whose covers can stand for the Wrapped, best first, at most [max]: each song once
     * (as [compute] counts them), the artists with most songs first and one album each, then a
     * second album of each, and so on ([CoverPicks.order]), so the first covers are those of the
     * top artists and nine of them are nine different artists where there are that many. The app
     * looks them up in this order and keeps the first that load ([covers]).
     */
    fun coverCandidates(tracks: List<Track>, max: Int = COVER_LOOKUPS): List<Track> {
        val songs = tracks.filter { it.kind.isEmpty() && it.title.isNotBlank() }.distinctBy { key(it) }
        return CoverPicks.order(songs).take(max)
    }

    /** The cover addresses found, in order: blank ones and repeats left out, at most [max]. */
    fun covers(found: List<String?>, max: Int = MAX_COVERS): List<String> =
        found.mapNotNull { it?.trim()?.takeIf { u -> u.isNotEmpty() } }.distinct().take(max)

    /** [history] with [w] added: the newest first, the same id once, at most [max]. */
    fun remember(history: List<Saved>, w: Saved, max: Int = MAX_HISTORY): List<Saved> =
        (listOf(w) + history.filter { it.id != w.id }).sortedByDescending { it.madeAt }.take(max)

    // ------------------------------------------------------------------ services

    /** A connector's name without the route in brackets: "Spotify (web)" → "Spotify", "YouTube Music (Google API)" → "YouTube Music". */
    fun brandName(name: String): String = name.replace(Regex("""\s*\([^()]*\)\s*$"""), "").trim().ifEmpty { name.trim() }

    /** A connected connector, as the Wrapped needs it: its own id, its service, its names and colour. */
    data class Connector(val id: String, val serviceId: String, val displayName: String, val familyName: String, val color: Long)

    /** Glyphs to prefer when a service has two routes: the music app's own mark (YouTube Music's, not YouTube's). */
    private val PREFERRED = listOf("spotify", "apple", "ytm", "deezer", "tidal", "soundcloud", "amazon")

    /**
     * Each service once, by its plain brand name, in the order first met: two routes (Spotify and
     * Spotify (web)) or two accounts of one service are one service. The glyph is the one of the
     * route the app knows best ([PREFERRED]), else the first met's.
     */
    fun services(connectors: List<Connector>): List<Service> =
        connectors.groupBy { brandName(it.familyName).lowercase() }.values.map { members ->
            val pick = members.minByOrNull { m -> PREFERRED.indexOf(m.serviceId).let { if (it < 0) Int.MAX_VALUE else it } } ?: members.first()
            Service(pick.serviceId, brandName(members.first().familyName), pick.color)
        }

    /**
     * The label of each connector on the chips that choose what is read: its plain brand name, or
     * its full name where two connected connectors share the brand (Spotify and Spotify (web)),
     * and the account's number where even that is the same (two Spotify accounts).
     */
    fun chipLabels(connectors: List<Connector>): Map<String, String> {
        val byBrand = connectors.groupingBy { brandName(it.familyName).lowercase() }.eachCount()
        val byFull = connectors.groupingBy { it.displayName }.eachCount()
        return connectors.associate { c ->
            val slot = c.id.substringAfter('@', "")
            val label = when {
                byBrand.getValue(brandName(c.familyName).lowercase()) == 1 -> brandName(c.familyName)
                byFull.getValue(c.displayName) == 1 -> c.displayName
                slot.isEmpty() -> c.displayName
                else -> "${c.displayName} $slot"
            }
            c.id to label
        }
    }

    /** The connectors to read: all but those the user left out; never none (all of them then). */
    fun chosen(ids: List<String>, skipped: Set<String>): List<String> = ids.filter { it !in skipped }.ifEmpty { ids }

    /**
     * [names] packed into at most [maxLines] lines of [width], joined by [sep], each name whole:
     * what does not fit is counted in a last "+N" (made by [more]). A single name wider than a line
     * stays alone on its line (the drawing then cuts it at its end).
     */
    fun packNames(names: List<String>, width: Float, maxLines: Int, measure: (String) -> Float, sep: String = " · ", more: (Int) -> String = { "+$it" }): List<String> {
        if (names.isEmpty() || maxLines <= 0) return emptyList()
        val lines = ArrayList<MutableList<String>>()
        var i = 0
        while (i < names.size && lines.size < maxLines) {
            val line = mutableListOf(names[i++])
            while (i < names.size && measure((line + names[i]).joinToString(sep)) <= width) line += names[i++]
            lines += line
        }
        var left = names.size - i
        if (left > 0) {
            // Room for "+N" on the last line: names come off it until the count fits (one name always stays).
            val last = lines.last()
            while (last.size > 1 && measure(last.joinToString(sep) + sep + more(left)) > width) { last.removeAt(last.size - 1); left++ }
            last += more(left)
        }
        return lines.map { it.joinToString(sep) }
    }
}
