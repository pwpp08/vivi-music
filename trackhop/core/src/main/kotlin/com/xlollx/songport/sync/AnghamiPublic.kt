package com.xlollx.songport.sync

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.Relay
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import kotlinx.serialization.json.JsonElement
import java.util.Base64
import java.util.UUID

/**
 * Public playlists of Anghami, read the way its web player reads them for a visitor who has not
 * signed in: one call to its gateway with a random device fingerprint, no account, no key (issue #27).
 * Not an official interface: when the site changes this stops working until Trackhop is updated,
 * and it says so ([Changed]). Read-only, from a pasted link (play.anghami.com/playlist/<id>).
 *
 * The gateway lists a playlist's songs in one of two ways: as JSON in the "song" sections (the
 * playlists Anghami makes), or as "songbuffers", base64 protobuf messages (the users' own
 * playlists). Both are read here; no ISRC either way.
 */
object AnghamiPublic {
    const val SERVICE = "anghami"
    const val SITE = "https://play.anghami.com"
    private const val GATEWAY = "https://coussa.anghami.com/gateway.php"
    private const val MAX_PAGES = 100

    class NotPublic : Exception("not public")
    class Changed(detail: String) : Exception(detail)

    private val HEADERS = mapOf(
        "User-Agent" to Http.DESKTOP_UA, "Origin" to SITE, "Referer" to "$SITE/", "Accept" to "application/json",
    )

    /** A song of a "song" section ("id", "title", "artist", "album", "duration" in seconds as text). */
    fun parseSong(j: JsonElement?): Track? {
        val id = j["id"].str?.takeIf { it.isNotBlank() } ?: return null
        val title = j["title"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Track(
            id = id,
            title = title,
            artists = listOfNotNull(j["artist"].str?.trim()?.takeIf { it.isNotEmpty() }),
            album = j["album"].str?.trim().orEmpty(),
            durationMs = ((j["duration"].str?.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
            year = j["year"].str?.toIntOrNull() ?: 0,
            uri = "$SITE/song/$id",
        )
    }

    /** A song of "songbuffers": {2: {1: id, 2: {1 id, 2 title, 3 album, 5 artist, 8 year, 9 duration (float s)}}}. */
    fun parseBuffer(b64: String): Track? {
        val bytes = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return null
        val wrapper = Proto.read(bytes).firstOrNull { it.field == 2 && it.bytes != null }?.bytes ?: return null
        val song = Proto.read(wrapper).firstOrNull { it.field == 2 && it.bytes != null }?.bytes ?: return null
        val f = Proto.read(song)
        fun text(n: Int) = f.firstOrNull { it.field == n && it.bytes != null }?.text?.trim()?.takeIf { it.isNotEmpty() }
        val id = text(1) ?: return null
        val title = text(2) ?: return null
        val seconds = f.firstOrNull { it.field == 9 }?.float ?: 0f
        return Track(
            id = id, title = title, artists = listOfNotNull(text(5)), album = text(3).orEmpty(),
            durationMs = (seconds * 1000).toLong(), year = text(8)?.toIntOrNull() ?: 0, uri = "$SITE/song/$id",
        )
    }

    /** The songs of one gateway answer, in the playlist's order ("songorder") where it gives one. */
    fun songsOf(page: JsonElement): List<Track> {
        val listed = page["sections"].arr.filter { it["type"].str == "song" }.flatMap { s -> s["data"].arr.mapNotNull { parseSong(it) } }
        val buffered = page["songbuffers"].arr.mapNotNull { e -> e.str?.let { parseBuffer(it) } }
        val all = (listed + buffered).distinctBy { it.id }
        val order = page["songorder"].str?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (order.isEmpty()) return all
        val byId = all.associateBy { it.id }
        return order.mapNotNull { byId[it] } + all.filter { it.id !in order }
    }

    fun playlistOf(page: JsonElement, id: String): Playlist = Playlist(
        id = id,
        name = (page["PlaylistName"].str ?: page["title"].str ?: page["name"].str)?.trim()?.takeIf { it.isNotEmpty() } ?: id,
        trackCount = page["PlaylistCount"].int ?: -1,
        ownedByMe = false,
        isPublic = true,
    )

    // ---- Network ---------------------------------------------------------------------------

    private val fingerprint: String by lazy { UUID.randomUUID().toString() }

    private suspend fun page(id: String, page: Int): JsonElement {
        val url = "$GATEWAY?type=GETplaylistdata&playlistid=${Http.enc(id)}&buffered=1&lang=en&language=en&output=jsonhp" +
            "&web2=true&fingerprint=$fingerprint&angh_type=GETplaylistdata" + (if (page > 0) "&page=$page" else "")
        val r = Relay.send("GET", url, HEADERS, maxRateLimitRetries = 1)
        if (r.code == 404) throw NotPublic()
        if (!r.ok) throw Changed("coussa.anghami.com HTTP ${r.code}")
        val j = runCatching { parseJson(r.body) }.getOrElse { throw Changed("not JSON") }
        if (j["error"] != null && j["sections"] == null && j["songbuffers"] == null) throw NotPublic()
        if (j["public"].str == "false") throw NotPublic()
        return j
    }

    suspend fun info(id: String): Playlist = playlistOf(page(id, 0), id)

    /** Every song, asking for further pages while the playlist says it has more and a page brings new ones. */
    suspend fun tracks(id: String): List<Track> {
        val first = page(id, 0)
        val total = first["PlaylistCount"].int ?: 0
        val out = LinkedHashMap<String, Track>()
        songsOf(first).forEach { out[it.id] = it }
        var p = 1
        while (out.size < total && p < MAX_PAGES) {
            val more = songsOf(page(id, p)).filter { it.id !in out }
            if (more.isEmpty()) break
            more.forEach { out[it.id] = it }
            p++
        }
        return out.values.toList()
    }

    /** Changes when the playlist does: its order of songs. */
    suspend fun version(id: String): String? = page(id, 0)["songorder"].str?.hashCode()?.toString()

    /** A minimal protobuf reader: fields of one message, with their bytes, varint or 32-bit float. */
    internal object Proto {
        class Field(val field: Int, val bytes: ByteArray? = null, val varint: Long? = null, val float: Float? = null) {
            val text: String? get() = bytes?.let { String(it, Charsets.UTF_8) }
        }

        fun read(b: ByteArray): List<Field> {
            val out = ArrayList<Field>()
            var i = 0
            fun varint(): Long { var r = 0L; var s = 0; while (true) { val c = b[i++].toInt() and 0xff; r = r or ((c and 0x7f).toLong() shl s); s += 7; if (c < 0x80) return r } }
            try {
                while (i < b.size) {
                    val key = varint(); val field = (key ushr 3).toInt()
                    when ((key and 7).toInt()) {
                        0 -> out += Field(field, varint = varint())
                        1 -> i += 8
                        2 -> { val n = varint().toInt(); out += Field(field, bytes = b.copyOfRange(i, i + n)); i += n }
                        5 -> { val v = java.nio.ByteBuffer.wrap(b, i, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float; out += Field(field, float = v); i += 4 }
                        else -> return out
                    }
                }
            } catch (e: IndexOutOfBoundsException) { }
            return out
        }
    }
}
