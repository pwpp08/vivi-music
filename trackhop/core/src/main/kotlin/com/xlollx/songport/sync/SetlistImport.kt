package com.xlollx.songport.sync

import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http

/**
 * Da un link di setlist.fm alla lista dei brani suonati: la pagina pubblica basta, senza chiave
 * API. Il nome della playlist viene dal titolo della pagina ("Artist Setlist at Venue, City, date").
 */
object SetlistImport {

    private val LINK = Regex("""https?://(?:www\.)?setlist\.fm/setlist/[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val SONG = Regex("""class="songLabel"[^>]*>([^<]+)<""")
    private val TITLE = Regex("""<meta property="og:title" content="([^"]+)"""")
    private val COVER_OF = Regex("""\s*\((.*?)\bcover\)$""", RegexOption.IGNORE_CASE)

    fun isLink(text: String): Boolean = LINK.containsMatchIn(text)
    fun linkIn(text: String): String? = LINK.find(text)?.value

    data class Setlist(val name: String, val artist: String, val tracks: List<Track>)

    suspend fun fetch(url: String): Setlist {
        // The User-Agent keeps "Songport" after the rename to Trackhop: an identifier the sites already see.
        val r = Http.send("GET", url, mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) Songport", "Accept" to "text/html"))
        if (!r.ok) throw ProviderException("setlist.fm ${r.code}")
        return parse(r.body)
    }

    /** Kotlin puro per i test: HTML della pagina -> nome e brani. */
    fun parse(html: String): Setlist {
        val title = TITLE.find(html)?.groupValues?.get(1)?.let { unescape(it) } ?: "Setlist"
        val artist = title.substringBefore(" Setlist").substringBefore(" Concert").trim()
        val tracks = SONG.findAll(html).map { unescape(it.groupValues[1]).trim() }.filter { it.isNotEmpty() }.map { raw ->
            // "Song (Original Artist cover)": the song is by the original artist, not the band on stage.
            val cover = COVER_OF.find(raw)
            val songTitle = if (cover != null) raw.removeRange(cover.range).trim() else raw
            val by = cover?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: artist
            CsvCodec.withStableId(Track(id = "", title = songTitle, artists = listOfNotNull(by.takeIf { it.isNotEmpty() })))
        }.toList()
        if (tracks.isEmpty()) throw ProviderException("setlist.fm: no songs on this page")
        val venue = title.substringAfter(" Setlist at ", "").substringBefore(",").trim()
        val name = if (venue.isEmpty()) artist.ifBlank { "Setlist" } else "$artist · $venue"
        return Setlist(name, artist, tracks)
    }

    private fun unescape(s: String): String = s
        .replace("&amp;", "&").replace("&#39;", "'").replace("&apos;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&#x27;", "'")
}
