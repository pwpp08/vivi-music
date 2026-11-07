package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.get
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str

/**
 * What a shared track link is about (title, artist, album, duration, ISRC when the catalogue gives
 * it), read from public endpoints that need no login: a Spotify link shared by a friend is of use
 * to someone whose Spotify is not connected. The parsers are pure and covered by tests; [describe]
 * does the requests. Everything here is best effort: a null answer only means "search by hand".
 */
object TrackLookup {

    /** Deezer's public catalogue, by track id: the fullest answer, ISRC included. */
    fun parseDeezer(json: String): Track? {
        val t = runCatching { parseJson(json) }.getOrNull() ?: return null
        val id = t["id"].long?.toString() ?: return null
        val title = t["title"].str?.takeIf { it.isNotBlank() } ?: return null
        return Track(
            id = id, title = title,
            artists = listOfNotNull(t["artist"]["name"].str?.takeIf { it.isNotBlank() }),
            album = t["album"]["title"].str.orEmpty(),
            durationMs = (t["duration"].long ?: 0) * 1000,
            isrc = t["isrc"].str?.takeIf { it.isNotBlank() },
        )
    }

    /** The iTunes lookup for an Apple Music song id (`results[0]`). */
    fun parseItunes(json: String): Track? {
        val r = runCatching { parseJson(json) }.getOrNull()?.get("results")?.get(0) ?: return null
        val id = (r["trackId"].long ?: return null).toString()
        val title = r["trackName"].str?.takeIf { it.isNotBlank() } ?: return null
        return Track(
            id = id, title = title,
            artists = listOfNotNull(r["artistName"].str?.takeIf { it.isNotBlank() }),
            album = r["collectionName"].str.orEmpty(),
            durationMs = r["trackTimeMillis"].long ?: 0,
        )
    }

    /**
     * An oEmbed answer (Spotify, YouTube, TIDAL): a title, and for YouTube the channel as the
     * artist. YouTube titles often read "Artist - Title (Official Video)": split like a file line.
     */
    fun parseOEmbed(json: String, id: String, service: String): Track? {
        val j = runCatching { parseJson(json) }.getOrNull() ?: return null
        val title = j["title"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val author = j["author_name"].str?.trim().orEmpty()
        return fromTitle(id, title, author.takeIf { service == "youtube" })
    }

    /**
     * The `og:title` and `og:description` of a track page. Spotify's description reads
     * "Artist · Song · 2024", TIDAL's "Listen to Title by Artist on TIDAL", Amazon's "Artist · Song".
     */
    fun parseOpenGraph(html: String, id: String): Track? {
        val title = meta(html, "og:title")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val desc = meta(html, "og:description")?.trim().orEmpty()
        val artist = Regex("""^(.+?)\s+·\s""").find(desc)?.groupValues?.get(1)?.trim()
            ?: Regex("""\bby\s+(.+?)\s+on\s+\w+\s*$""").find(desc)?.groupValues?.get(1)?.trim()
        return fromTitle(id, title, artist)
    }

    private fun meta(html: String, property: String): String? {
        val m = Regex("""<meta\s+[^>]*?(?:property|name)=["']${Regex.escape(property)}["'][^>]*?content=["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<meta\s+[^>]*?content=["']([^"']*)["'][^>]*?(?:property|name)=["']${Regex.escape(property)}["']""", RegexOption.IGNORE_CASE).find(html)
        return m?.groupValues?.get(1)?.let { unescape(it) }
    }

    private fun unescape(s: String): String = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">")

    /**
     * A title that may carry the artist ("Artist - Title", "Title | Artist") and a channel name that
     * may be the artist ("Artist", "Artist - Topic", "ArtistVEVO"). Platform suffixes are dropped.
     */
    fun fromTitle(id: String, rawTitle: String, author: String?): Track {
        var title = rawTitle.replace(Regex("""\s*[|–-]\s*(Spotify|YouTube( Music)?|TIDAL|Amazon Music|Deezer|Apple Music)\s*$""", RegexOption.IGNORE_CASE), "").trim()
        var artists = emptyList<String>()
        if (!author.isNullOrBlank()) {
            val a = author.replace(Regex("""\s*-\s*Topic$"""), "").replace(Regex("""VEVO$"""), "").trim()
            if (a.isNotEmpty()) artists = listOf(a)
        }
        // Spotify's page title: "Title - song and lyrics by Artist | Spotify".
        Regex("""^(.+?)\s*[-–]\s*song (?:and lyrics )?by (.+?)\s*$""", RegexOption.IGNORE_CASE).find(title)?.let { m ->
            return Track(id = id, title = m.groupValues[1].trim(), artists = CsvCodec.splitArtists(m.groupValues[2]))
        }
        // "Artist - Title" inside the title wins over a channel name, which is often a label;
        // "Title | Artist" is how some players head their pages.
        val (fromLineArtists, fromLineTitle) = PlaylistFiles.splitArtistTitle(title)
        if (fromLineArtists.isNotEmpty() && fromLineTitle.isNotBlank()) { artists = fromLineArtists; title = fromLineTitle }
        else title.split(" | ").takeIf { it.size == 2 && it.all { p -> p.isNotBlank() } }?.let { (t, a) -> title = t.trim(); if (artists.isEmpty()) artists = CsvCodec.splitArtists(a) }
        return Track(id = id, title = title, artists = artists)
    }

    /** The track behind a link, by the public means its service offers; null when none answers. */
    suspend fun describe(ref: TrackLinks.Ref, link: String): Track? = runCatching {
        when (ref.service) {
            "deezer" -> getText("https://api.deezer.com/track/${ref.trackId}")?.let { parseDeezer(it) }
            "apple" -> getText("https://itunes.apple.com/lookup?id=${ref.trackId}&entity=song")?.let { parseItunes(it) }
            "spotify" -> getText("https://open.spotify.com/oembed?url=" + Http.enc("https://open.spotify.com/track/${ref.trackId}"))
                ?.let { parseOEmbed(it, ref.trackId, ref.service) }?.takeIf { it.artists.isNotEmpty() }
                ?: page("https://open.spotify.com/track/${ref.trackId}", ref.trackId)
            "youtube" -> getText("https://www.youtube.com/oembed?format=json&url=" + Http.enc("https://www.youtube.com/watch?v=${ref.trackId}"))
                ?.let { parseOEmbed(it, ref.trackId, ref.service) }
            "tidal" -> getText("https://embed.tidal.com/oembed?url=" + Http.enc("https://tidal.com/browse/track/${ref.trackId}"))
                ?.let { parseOEmbed(it, ref.trackId, ref.service) }?.takeIf { it.artists.isNotEmpty() }
                ?: page("https://tidal.com/browse/track/${ref.trackId}", ref.trackId)
            "amazon" -> page(Regex("""https?://\S+""").find(link)?.value ?: "https://music.amazon.com/tracks/${ref.trackId}", ref.trackId)
            else -> null
        }
    }.getOrNull()

    private suspend fun getText(url: String): String? {
        val r = Http.send("GET", url, headers = mapOf("Accept" to "application/json"), maxRateLimitRetries = 0)
        return r.body.takeIf { r.ok && it.isNotBlank() }
    }

    private suspend fun page(url: String, id: String): Track? {
        val landing = Http.follow(url)
        return parseOpenGraph(landing.page, id)
    }
}
