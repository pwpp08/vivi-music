package com.xlollx.songport.sync

import com.xlollx.songport.model.Track

/** Which tracks of a playlist can stand for it on a card: one per album, so the mosaic is not four times the same cover. */
object CoverPicks {
    /**
     * The album a track comes from, as a key: title normalised the way the matcher does it
     * ("Hail to the King (Deluxe)" and "Hail To The King" are one album) plus the first artist.
     * A track without an album is its own key.
     */
    fun albumKey(t: Track): String {
        val album = Matcher.normalizeTitle(t.album)
        if (album.isBlank()) return "id:" + t.id
        val artist = t.artists.firstOrNull()?.let { Matcher.normalizeArtist(it) }.orEmpty()
        return "$album|$artist"
    }

    /** The first artist as a key; a track without one is an artist of its own. */
    private fun artistKey(t: Track): String =
        t.artists.firstOrNull()?.let { Matcher.normalizeArtist(it) }?.takeIf { it.isNotEmpty() } ?: ("id:" + t.id)

    /**
     * The tracks whose covers should stand for the playlist, best first, one per album: the
     * artists that appear most come first, one album each (their most frequent album), then a
     * second album of each, and so on, so the mosaic looks like the playlist and not like its
     * first page. Ties keep the playlist's order. The app looks covers up in this order and skips
     * the ones that do not load or repeat an image already picked.
     *
     * Same playlist, same [variant]: same order. Another [variant] ("make it again") varies it a
     * little: equally frequent artists take turns, an artist with several albums shows another
     * one, and past the first [fixed] places the rest of the list is rotated, so the last tiles
     * change while the most present artists stay.
     */
    fun order(tracks: List<Track>, variant: Int = 0, fixed: Int = 3): List<Track> {
        class Album(val first: Int) { val tracks = ArrayList<Track>() }
        class Artist(val first: Int) { var count = 0; val albums = LinkedHashMap<String, Album>() }
        val artists = LinkedHashMap<String, Artist>()
        tracks.forEachIndexed { i, t ->
            val a = artists.getOrPut(artistKey(t)) { Artist(i) }
            a.count++
            a.albums.getOrPut(albumKey(t)) { Album(i) }.tracks += t
        }
        val v = variant.coerceAtLeast(0)
        // Most frequent first; equally frequent artists in playlist order, rotated by the variant.
        val ranked = artists.values.groupBy { it.count }.toSortedMap(compareByDescending { it })
            .values.flatMap { tie -> tie.sortedBy { it.first }.rotated(v) }
        val albumLists = ranked.map { a ->
            a.albums.values.sortedWith(compareByDescending<Album> { it.tracks.size }.thenBy { it.first }).rotated(v)
        }
        val out = ArrayList<Track>()
        var round = 0
        while (true) {
            var any = false
            for (albums in albumLists) {
                val album = albums.getOrNull(round) ?: continue
                out += album.tracks.first(); any = true
            }
            if (!any) break
            round++
        }
        if (v == 0 || out.size <= fixed + 1) return out
        return out.take(fixed) + out.drop(fixed).rotated(v)
    }

    private fun <T> List<T>.rotated(by: Int): List<T> {
        if (size < 2) return this
        val k = Math.floorMod(by, size)
        return if (k == 0) this else drop(k) + take(k)
    }
}
