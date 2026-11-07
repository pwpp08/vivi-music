package com.xlollx.songport.sync

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.Relay
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import kotlinx.serialization.json.JsonElement

/**
 * Public playlists (歌单) of QQ Music, read the way y.qq.com's own page reads them: no account, no
 * key, the plain JSON call the web player makes for a playlist's songs. Not an official interface:
 * when the site changes this stops working until Trackhop is updated, and it says so ([Changed]).
 * Read-only, from a pasted link; never a sign-in.
 *
 * A playlist id here is the number in the link (y.qq.com/n/ryqq/playlist/<id>).
 */
object QqMusicPublic {
    const val SERVICE = "qqmusic"
    const val SITE = "https://y.qq.com"
    private const val API = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    // Whatever "song_num" asks, the site answers 30 songs at a time.
    private const val PAGE = 30
    private const val MAX_TRACKS = 10_000

    /** The playlist is private, deleted, or never existed. */
    class NotPublic : Exception("not public")
    /** The site no longer answers the way this reads it. */
    class Changed(detail: String) : Exception(detail)

    private val HEADERS = mapOf("User-Agent" to Http.DESKTOP_UA, "Referer" to "$SITE/", "Accept" to "application/json")

    /** A song as the playlist lists it ("name", "singer", "album", "interval" in seconds), or null without an id or a name. */
    fun parseTrack(j: JsonElement?): Track? {
        val id = j["id"].long ?: return null
        val name = (j["name"].str ?: j["title"].str)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val mid = j["mid"].str?.trim().orEmpty()
        val artists = j["singer"].arr.mapNotNull { it["name"].str?.trim()?.takeIf { n -> n.isNotEmpty() } }
        return Track(
            id = id.toString(),
            title = name,
            artists = artists,
            album = j["album"]["name"].str?.trim().orEmpty(),
            durationMs = (j["interval"].long ?: 0) * 1000,
            uri = if (mid.isNotEmpty()) "$SITE/n/ryqq/songDetail/$mid" else null,
            year = Durations.year(j["time_public"].str),
        )
    }

    /**
     * The playlist's data in one answer ("req_0".data), or null when the answer has none: a missing
     * or private playlist comes back with its own error code and an empty "dirinfo".
     */
    fun data(j: JsonElement): JsonElement? = j["req_0"]["data"].takeIf { it["dirinfo"] != null && (it["code"].int ?: 0) == 0 }

    fun playlistOf(data: JsonElement, id: String): Playlist {
        val d = data["dirinfo"]
        return Playlist(
            id = id,
            name = d["title"].str?.trim()?.takeIf { it.isNotEmpty() } ?: id,
            trackCount = data["total_song_num"].int ?: d["songnum"].int ?: -1,
            ownedByMe = false,
            description = d["desc"].str.orEmpty(),
            imageUrl = d["picurl"].str,
            isPublic = null,
        )
    }

    fun songs(data: JsonElement): List<Track> = data["songlist"].arr.mapNotNull { parseTrack(it) }

    // ---- Network ---------------------------------------------------------------------------

    private suspend fun page(id: String, begin: Int, count: Int): JsonElement {
        val req = """{"req_0":{"module":"music.srfDissInfo.aiDissInfo","method":"uniform_get_Dissinfo","param":{"disstid":$id,"song_begin":$begin,"song_num":$count,"onlysong":0}}}"""
        val r = Relay.send("GET", "$API?format=json&data=${Http.enc(req)}", HEADERS, maxRateLimitRetries = 1)
        if (!r.ok) throw Changed("u.y.qq.com HTTP ${r.code}")
        val j = runCatching { parseJson(r.body) }.getOrElse { throw Changed("not JSON") }
        if (j["code"].int != 0) throw Changed("code ${j["code"].int}")
        val sub = j["req_0"]["code"].int ?: throw Changed("no answer for the playlist")
        if (sub != 0) throw NotPublic()
        return data(j) ?: throw NotPublic()
    }

    suspend fun info(id: String): Playlist = playlistOf(page(id.filter { it.isDigit() }.ifEmpty { throw NotPublic() }, 0, 1), id)

    suspend fun tracks(id: String): List<Track> {
        val clean = id.filter { it.isDigit() }.ifEmpty { throw NotPublic() }
        val out = ArrayList<Track>()
        var begin = 0
        while (begin < MAX_TRACKS) {
            val d = page(clean, begin, PAGE)
            val got = songs(d)
            out += got
            val total = d["total_song_num"].int ?: d["dirinfo"]["songnum"].int ?: out.size
            if (got.isEmpty() || out.size >= total) break
            begin += got.size
        }
        return out
    }

    /** Changes when the playlist does: the site's own "modified" stamp. */
    suspend fun version(id: String): String? = page(id.filter { it.isDigit() }.ifEmpty { return null }, 0, 1)["dirinfo"]["mtime"].long?.toString()
}
