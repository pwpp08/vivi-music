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
import okhttp3.FormBody

/**
 * Public playlists of NetEase Cloud Music (网易云音乐), read the way the site's own pages read them:
 * no account, no key, only the plain calls behind music.163.com. Not an official interface: when the
 * site changes this stops working until Trackhop is updated, and it says so ([Changed]). Read-only,
 * from a pasted link; NetEase's own terms frown on third-party tools, so there is never a sign-in.
 *
 * A playlist id here is the number in the link (music.163.com/playlist?id=<id>). The detail call
 * lists every track id but, for a user's playlist, the full data of the first few only: the rest
 * is fetched by id in batches.
 */
object NeteasePublic {
    const val SERVICE = "netease"
    const val SITE = "https://music.163.com"
    private const val BATCH = 500
    private const val MAX_TRACKS = 10_000

    /** The playlist is private, deleted, or never existed. */
    class NotPublic : Exception("not public")
    /** The site no longer answers the way this reads it. */
    class Changed(detail: String) : Exception(detail)

    private val HEADERS = mapOf("User-Agent" to Http.DESKTOP_UA, "Referer" to "$SITE/", "Accept" to "application/json")

    /** A track as the site lists it ("name", "ar", "al", "dt"), or null for anything without an id or a name. */
    fun parseTrack(j: JsonElement?): Track? {
        val id = j["id"].long ?: return null
        val name = j["name"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val artists = j["ar"].arr.mapNotNull { it["name"].str?.trim()?.takeIf { n -> n.isNotEmpty() } }
        val published = j["publishTime"].long ?: 0
        return Track(
            id = id.toString(),
            title = name,
            artists = artists,
            album = j["al"]["name"].str?.trim().orEmpty(),
            durationMs = j["dt"].long ?: 0,
            uri = "$SITE/song?id=$id",
            // "noCopyrightRcmd" is set when the song is no longer licensed: listed, greyed out.
            available = if (j["noCopyrightRcmd"].let { it == null || it is kotlinx.serialization.json.JsonNull }) null else false,
            year = if (published > 0) java.time.Instant.ofEpochMilli(published).atZone(java.time.ZoneOffset.UTC).year else 0,
        )
    }

    /** Every track id of the playlist, in its order. */
    fun trackIds(detail: JsonElement): List<Long> = detail["playlist"]["trackIds"].arr.mapNotNull { it["id"].long }

    /** The tracks the detail lists in full, by id. */
    fun listedTracks(detail: JsonElement): Map<Long, Track> =
        detail["playlist"]["tracks"].arr.mapNotNull { parseTrack(it) }.associateBy { it.id.toLong() }

    fun playlistOf(detail: JsonElement, id: String): Playlist {
        val p = detail["playlist"]
        return Playlist(
            id = id,
            name = p["name"].str?.trim()?.takeIf { it.isNotEmpty() } ?: id,
            trackCount = p["trackCount"].int ?: trackIds(detail).size,
            ownedByMe = false,
            description = p["description"].str.orEmpty(),
            imageUrl = p["coverImgUrl"].str,
            isPublic = p["privacy"].int?.let { it == 0 },
        )
    }

    // ---- Network ---------------------------------------------------------------------------

    private suspend fun detail(id: String): JsonElement {
        val r = Relay.send("GET", "$SITE/api/v6/playlist/detail?id=${Http.enc(id)}&n=$MAX_TRACKS", HEADERS, maxRateLimitRetries = 1)
        if (!r.ok) throw Changed("music.163.com HTTP ${r.code}")
        val j = runCatching { parseJson(r.body) }.getOrElse { throw Changed("not JSON") }
        val code = j["code"].int ?: throw Changed("no code")
        // 401 and 404 are the site's words for a private or missing playlist; anything else is the site changing.
        if (code == 401 || code == 404 || (code == 200 && j["playlist"] == null)) throw NotPublic()
        if (code != 200) throw Changed("code $code")
        return j
    }

    /** The tracks [ids] in that order, fetched in batches. */
    private suspend fun songs(ids: List<Long>): Map<Long, Track> {
        val out = HashMap<Long, Track>()
        for (chunk in ids.chunked(BATCH)) {
            val body = FormBody.Builder().add("c", chunk.joinToString(",", "[", "]") { """{"id":$it}""" }).build()
            val r = Relay.send("POST", "$SITE/api/v3/song/detail", HEADERS, body, maxRateLimitRetries = 1, retryServerErrors = true)
            if (!r.ok) throw Changed("song/detail HTTP ${r.code}")
            val j = runCatching { parseJson(r.body) }.getOrElse { throw Changed("song/detail not JSON") }
            j["songs"].arr.mapNotNull { parseTrack(it) }.forEach { out[it.id.toLong()] = it }
        }
        return out
    }

    suspend fun info(id: String): Playlist = playlistOf(detail(id), id)

    suspend fun tracks(id: String): List<Track> {
        val d = detail(id)
        val ids = trackIds(d).take(MAX_TRACKS)
        val listed = listedTracks(d)
        val missing = ids.filter { it !in listed }
        val fetched = if (missing.isEmpty()) emptyMap() else songs(missing)
        return ids.mapNotNull { listed[it] ?: fetched[it] }
    }

    /** Changes when the playlist does: the site's own "tracks updated" stamp. */
    suspend fun version(id: String): String? = detail(id)["playlist"].let { p -> (p["trackUpdateTime"].long ?: p["updateTime"].long)?.toString() }
}
