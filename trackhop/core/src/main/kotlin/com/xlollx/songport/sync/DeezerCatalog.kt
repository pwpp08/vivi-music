package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str

/**
 * Cover art for a track, from Deezer's public catalogue (no key, no account: the same lookups
 * anyone makes on deezer.com). Tracks carry no image of their own in Trackhop; a card that shows
 * them needs one. By ISRC when the track has one, else by the same search [IsrcOracle] does, and
 * only an exact enough match. Null when nothing fits: the card draws a placeholder.
 */
object DeezerCatalog {
    private const val API = "https://api.deezer.com"

    suspend fun coverUrl(t: Track): String? {
        t.isrcNorm?.let { isrc ->
            val r = runCatching { Http.send("GET", "$API/track/isrc:$isrc", maxRateLimitRetries = 0) }.getOrNull()
            if (r != null && r.ok) {
                val j = parseJson(r.body)
                cover(j["album"])?.let { return it }
            }
        }
        val artist = t.artists.firstOrNull()?.let { Matcher.searchArtist(it) }.orEmpty()
        val title = Matcher.searchTitle(t.title)
        if (title.isBlank()) return null
        // Deezer's artist:"…" filter answers nothing (#46): the title filtered, the artist as plain words.
        val q = if (artist.isBlank()) title else "track:\"$title\" $artist"
        val resp = runCatching { Http.send("GET", "$API/search?limit=8&q=" + Http.enc(q), maxRateLimitRetries = 0) }.getOrNull() ?: return null
        if (!resp.ok) return null
        val hits = parseJson(resp.body)["data"].arr
        val candidates = hits.mapNotNull { d ->
            val id = d["id"].long ?: return@mapNotNull null
            Track(
                id = id.toString(), title = d["title"].str ?: return@mapNotNull null,
                artists = listOfNotNull(d["artist"]["name"].str), album = d["album"]["title"].str ?: "",
                durationMs = (d["duration"].long ?: 0) * 1000,
            )
        }
        val best = Matcher.bestScored(t, candidates, 0.0)?.takeIf { it.score >= 0.80 } ?: return null
        val hit = hits.firstOrNull { it["id"].long?.toString() == best.track.id } ?: return null
        return cover(hit["album"])
    }

    /** Thumbnails YouTube serves for any video id without an API call: 1280×720, else 320×180 (no bars). */
    fun ownCover(id: String, serviceId: String): List<String> =
        if ((serviceId == "youtube" || serviceId == "ytm") && Regex("[A-Za-z0-9_-]{11}").matches(id))
            listOf("https://i.ytimg.com/vi/$id/hq720.jpg", "https://i.ytimg.com/vi/$id/mqdefault.jpg")
        else emptyList()

    /** The iTunes Search API (public, no key): artwork for the best matching song, at 600 px. */
    suspend fun itunesCover(t: Track): String? {
        val artist = t.artists.firstOrNull()?.let { Matcher.searchArtist(it) }.orEmpty()
        val title = Matcher.searchTitle(t.title)
        if (title.isBlank()) return null
        val term = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
        val resp = runCatching { Http.send("GET", "https://itunes.apple.com/search?entity=song&limit=8&term=" + Http.enc(term), maxRateLimitRetries = 0) }.getOrNull() ?: return null
        if (!resp.ok) return null
        val hits = parseJson(resp.body)["results"].arr
        val candidates = hits.mapIndexedNotNull { i, d ->
            Track(
                id = i.toString(), title = d["trackName"].str ?: return@mapIndexedNotNull null,
                artists = listOfNotNull(d["artistName"].str), album = d["collectionName"].str ?: "",
                durationMs = d["trackTimeMillis"].long ?: 0,
            )
        }
        val best = Matcher.bestScored(t, candidates, 0.0)?.takeIf { it.score >= 0.75 } ?: return null
        val art = hits.getOrNull(best.track.id.toInt())?.get("artworkUrl100").str ?: return null
        return art.replace("100x100bb", "600x600bb")
    }

    private fun cover(album: kotlinx.serialization.json.JsonElement?): String? =
        album["cover_big"].str ?: album["cover_medium"].str ?: album["cover"].str
}
