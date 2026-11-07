package com.xlollx.songport.ytmbridge

import com.xlollx.songport.ytmbridge.BridgeNet.withHook
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Anghami through play.anghami.com's own web player: it has no public API (issue #27).
 *
 * The session is the player's `sid` (shaped `xx:xxxxxxxx:…`, about 140 characters), which every call
 * to its gateway (`coussa.anghami.com/gateway.php`) carries as a parameter, with a device fingerprint.
 * The sign-in itself is encrypted by the player, so Trackhop never signs in: the user does, on
 * Anghami's page (the app reads the session from it) or pastes the sid (Desktop and Docker).
 *
 * What the player does, seen in a signed-in session of 03/11/2026, none of it documented:
 * - GETplaylists: the account's playlists; the liked songs are the one named `$…LIKED#`, other
 *   `$…#` names are the player's own lists (downloads, podcasts) and are left out;
 * - GETplaylistdata: a playlist's songs, as JSON or as base64 protobuf "songbuffers";
 * - PUTplaylist: create (name, public, songID), append or delete a song (playlistid or the liked
 *   list's name, songid, action);
 * - UPDATEplaylist: rename (action=update, newname), delete (action=delete), visibility (action=share);
 * - GETSearchResults.view: search, without an account.
 *
 * No Android here: the server build compiles this file as it is (server/build.gradle).
 */
class AnghamiWebClient(private val host: WebHost) {

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).withHook().build()

    private fun sid(): String = host.get(SID) ?: throw BridgeException("not connected")

    private fun fingerprint(): String = host.get(FP) ?: UUID.randomUUID().toString().also { host.put(FP, it) }

    /** A GET carries its type in the address; a POST in its form, the address then naming it only as angh_type. */
    private fun gatewayUrl(type: String, params: Map<String, String> = emptyMap(), signed: Boolean = true, typeInQuery: Boolean = true): String {
        val all = (if (typeInQuery) mapOf("type" to type) else emptyMap()) + params + mapOf(
            "lang" to "en", "language" to "en", "output" to "jsonhp", "web2" to "true",
            "fingerprint" to fingerprint(), "angh_type" to type,
        ) + (if (signed) mapOf("sid" to sid()) else emptyMap())
        return GATEWAY + "?" + all.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
    }

    private fun request(url: String): Request.Builder = Request.Builder().url(url)
        .header("User-Agent", host.userAgent).header("Origin", SITE).header("Referer", "$SITE/").header("Accept", "application/json")

    private fun read(req: Request): JsonElement {
        val (code, body) = http.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
        if (code == 401 || code == 403) throw BridgeException(EXPIRED)
        if (code !in 200..299) throw BridgeException("Anghami HTTP $code")
        val j = runCatching { parseJson(body) }.getOrElse { throw BridgeException("Anghami answered something that is not JSON") }
        problemIn(j)?.let { throw BridgeException(it) }
        return j
    }

    private fun get(type: String, params: Map<String, String> = emptyMap(), signed: Boolean = true): JsonElement =
        read(request(gatewayUrl(type, params, signed)).get().build())

    private fun post(type: String, form: Map<String, String>): JsonElement {
        val body = FormBody.Builder().apply { add("type", type); form.forEach { (k, v) -> add(k, v) } }.build()
        return read(request(gatewayUrl(type, typeInQuery = false)).post(body).build())
    }

    // ---- reading

    /** The account's own playlists, the liked songs and the player's own lists left out. */
    fun playlists(): List<PlaylistDto> = parsePlaylists(get("GETplaylists")).filter { !it.id.startsWith("$") }

    /** The liked songs' list id ("$…LIKED#" playlist), or null when the account has none yet. */
    private fun likedListId(): String? = parseLikedId(get("GETplaylists"))

    fun tracks(playlistId: String): List<TrackDto> {
        val first = get("GETplaylistdata", mapOf("playlistid" to playlistId, "buffered" to "1"))
        val total = first["PlaylistCount"].str?.toIntOrNull() ?: 0
        val out = LinkedHashMap<String, TrackDto>()
        parseSongs(first).forEach { out[it.id] = it }
        var page = 1
        while (out.size < total && page < MAX_PAGES) {
            val more = parseSongs(get("GETplaylistdata", mapOf("playlistid" to playlistId, "buffered" to "1", "page" to "$page"))).filter { it.id !in out }
            if (more.isEmpty()) break
            more.forEach { out[it.id] = it }
            page++
        }
        return out.values.toList()
    }

    fun liked(): List<TrackDto> = likedListId()?.let { tracks(it) } ?: emptyList()

    fun search(query: String, max: Int = 10): List<TrackDto> {
        val params = mapOf(
            "query" to query, "page" to "0", "filter_type" to "top", "simple_results" to "false",
            "language" to "en", "lang" to "en", "web2" to "true", "output" to "jsonhp", "fingerprint" to fingerprint(),
        )
        val url = SEARCH + "?" + params.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        return parseSearch(read(request(url).get().build())).take(max)
    }

    /** Checks the session: reads the playlists; the account name, when the answer gives one. */
    fun probe(): String { get("GETplaylists"); return host.account() ?: "Anghami" }

    // ---- writing

    fun create(name: String, public: Boolean = false): PlaylistDto {
        val j = post("PUTplaylist", mapOf("name" to name, "public" to public.toString()))
        val id = j["playlist"]["id"].str ?: throw BridgeException("Anghami did not create the playlist")
        return PlaylistDto(id = id, name = name, count = 0)
    }

    fun add(playlistId: String, songIds: List<String>) =
        songIds.forEach { post("PUTplaylist", mapOf("playlistid" to playlistId, "action" to "append", "songID" to it)) }

    fun remove(playlistId: String, songIds: List<String>) =
        songIds.forEach { post("PUTplaylist", mapOf("playlistid" to playlistId, "action" to "delete", "songid" to it)) }

    /** Likes or unlikes: the liked list is addressed by its name, as the player does. */
    fun like(songIds: List<String>, on: Boolean) =
        songIds.forEach { post("PUTplaylist", mapOf("name" to LIKED_NAME, "action" to if (on) "append" else "delete", "songid" to it)) }

    fun rename(playlistId: String, name: String) { post("UPDATEplaylist", mapOf("playlistid" to playlistId, "action" to "update", "newname" to name)) }

    fun delete(playlistId: String) { post("UPDATEplaylist", mapOf("playlistid" to playlistId, "action" to "delete")) }

    fun setPublic(playlistId: String, public: Boolean) { post("UPDATEplaylist", mapOf("playlistid" to playlistId, "action" to "share", "public" to public.toString())) }

    companion object {
        const val SITE = "https://play.anghami.com"
        const val LOGIN_URL = "https://play.anghami.com/login"
        private const val GATEWAY = "https://coussa.anghami.com/gateway.php"
        private const val SEARCH = "https://coussa.anghami.com/rest/v2/GETSearchResults.view"
        private const val MAX_PAGES = 100
        const val SID = "sid"; const val FP = "fingerprint"
        /** The liked songs' list as the player names it when it writes to it. */
        const val LIKED_NAME = "\$1234567890LIKED#"
        const val EXPIRED = "the Anghami session has ended: sign in again"

        /** A session id as the player keeps it: two characters, a colon, a few, a colon, a long token. */
        private val SID_SHAPE = Regex("""[A-Za-z0-9]{2}:[A-Za-z0-9]{4,16}:[A-Za-z0-9+/=_-]{40,}""")

        /** The sid in [text] (a pasted value, a request's address, a JSON entry), or null. */
        fun sidIn(text: String?): String? = text?.let { SID_SHAPE.find(it)?.value }

        /**
         * The session in [text]: the sid, with the fingerprint it was used with when [text] is a whole
         * request address of the player (`…gateway.php?…&fingerprint=…&sid=…`); null without a sid.
         */
        fun sessionIn(text: String?): Pair<String, String?>? {
            val t = text?.trim() ?: return null
            val query = t.substringAfter('?', "")
            val params = query.split('&').filter { '=' in it }.associate { kv ->
                val k = runCatching { URLDecoder.decode(kv.substringBefore('='), "UTF-8") }.getOrDefault("")
                k to runCatching { URLDecoder.decode(kv.substringAfter('='), "UTF-8") }.getOrDefault("")
            }
            val sid = sidIn(params["sid"]) ?: sidIn(t) ?: return null
            return sid to params["fingerprint"]?.takeIf { it.isNotBlank() && params["sid"] != null }
        }

        /**
         * The gateway's own refusals: `{"error":{"_attributes":{"code":"111","message":"Please login again…","logoff":"true"}}}`
         * (also without `_attributes`), or a "failed" status.
         */
        fun problemIn(j: JsonElement?): String? {
            val e = j["error"]
            if (e == null) return if ((j["status"].str ?: j["_attributes"]["status"].str) == "failed") "Anghami refused the request" else null
            val a = e["_attributes"] ?: e
            val msg = a["message"].str ?: e.str ?: "refused"
            return if (a["logoff"].str == "true" || a["code"].str == "111" || msg.contains("login", true)) EXPIRED else "Anghami: $msg"
        }

        fun parsePlaylists(j: JsonElement?): List<PlaylistDto> =
            j["sections"].arr.filter { it["type"].str == "playlist" }.flatMap { s -> s["data"].arr }.mapNotNull { p ->
                val id = p["id"].str ?: return@mapNotNull null
                val name = p["name"].str ?: p["PlaylistName"].str ?: return@mapNotNull null
                // The player's own lists ("$…LIKED#", "$…DOWNLOADED#", "$…PODCASTS#") get a "$" id so callers can tell them apart.
                if (name.startsWith("$") && name.endsWith("#")) PlaylistDto(id = "$" + name, name = name, count = p["count"].str?.toIntOrNull() ?: -1)
                else PlaylistDto(id = id, name = name, count = p["count"].str?.toIntOrNull() ?: -1)
            }

        fun parseLikedId(j: JsonElement?): String? =
            j["sections"].arr.filter { it["type"].str == "playlist" }.flatMap { s -> s["data"].arr }
                .firstOrNull { (it["name"].str ?: "").let { n -> n.startsWith("$") && n.endsWith("LIKED#") } }?.get("id")?.str

        fun parseSong(j: JsonElement?): TrackDto? {
            val id = j["id"].str?.takeIf { it.isNotBlank() } ?: return null
            val title = j["title"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return TrackDto(
                id = id, title = title,
                artists = listOfNotNull(j["artist"].str?.trim()?.takeIf { it.isNotEmpty() }),
                album = j["album"].str?.trim().orEmpty(),
                durationMs = ((j["duration"].str?.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                year = j["year"].str?.toIntOrNull(),
            )
        }

        /** A song of "songbuffers": {2: {1: id, 2: {1 id, 2 title, 3 album, 5 artist, 8 year, 9 duration (float s)}}}. */
        fun parseBuffer(b64: String): TrackDto? {
            val bytes = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return null
            val wrapper = Proto.read(bytes).firstOrNull { it.field == 2 && it.bytes != null }?.bytes ?: return null
            val song = Proto.read(wrapper).firstOrNull { it.field == 2 && it.bytes != null }?.bytes ?: return null
            val f = Proto.read(song)
            fun text(n: Int) = f.firstOrNull { it.field == n && it.bytes != null }?.text?.trim()?.takeIf { it.isNotEmpty() }
            val id = text(1) ?: return null
            val title = text(2) ?: return null
            val seconds = f.firstOrNull { it.field == 9 }?.float ?: 0f
            return TrackDto(id = id, title = title, artists = listOfNotNull(text(5)), album = text(3).orEmpty(),
                durationMs = (seconds * 1000).toLong(), year = text(8)?.toIntOrNull())
        }

        fun parseSongs(page: JsonElement?): List<TrackDto> {
            val listed = page["sections"].arr.filter { it["type"].str == "song" }.flatMap { s -> s["data"].arr.mapNotNull { parseSong(it) } }
            val buffered = page["songbuffers"].arr.mapNotNull { e -> e.str?.let { parseBuffer(it) } }
            val all = (listed + buffered).distinctBy { it.id }
            val order = page["songorder"].str?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            if (order.isEmpty()) return all
            val byId = all.associateBy { it.id }
            return order.mapNotNull { byId[it] } + all.filter { it.id !in order }
        }

        /** The songs of a search: the results that have an album and a duration (artists and albums have neither). */
        fun parseSearch(j: JsonElement?): List<TrackDto> =
            j["sections"].arr.flatMap { it["data"].arr }.filter { it["albumID"] != null && it["duration"] != null }.mapNotNull { parseSong(it) }
    }

    /** A minimal protobuf reader: the fields of one message, with their bytes, varint or 32-bit float. */
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
                        5 -> { out += Field(field, float = java.nio.ByteBuffer.wrap(b, i, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float); i += 4 }
                        else -> return out
                    }
                }
            } catch (e: IndexOutOfBoundsException) { }
            return out
        }
    }
}
