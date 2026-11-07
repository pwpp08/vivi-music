package com.xlollx.songport.ytmbridge

import com.xlollx.songport.ytmbridge.BridgeNet.withHook
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Deezer through the web player's own gateway, `www.deezer.com/ajax/gw-light.php`, the way
 * deezer.com talks to Deezer: a POST naming a method (`playlist.getSongs`, `favorite_song.add`...)
 * with a JSON body, authenticated by the session cookies (`arl`, then the `sid` the gateway hands
 * back) and by an `api_token` that `deezer.getUserData` returns as `checkForm`. The method names and
 * shapes are the ones open-source clients (deemix, deezer-py) have used for years; none of it is a
 * documented interface.
 *
 * Catalogue search goes to the public API, `api.deezer.com`, which needs no account at all: the
 * same request anyone's browser makes, and it gives the ISRC of a track looked up by ISRC.
 *
 * No Android here: the server build (server/build.gradle) compiles this file as it is, with its own
 * [WebHost] (pasted cookies). The app's is [DeezerBridge.host].
 */
class DeezerWebClient(private val host: WebHost) {

    class User(val id: String, val name: String?, val token: String, val lovedId: String?)

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).withHook().build()
    // ---- session

    /**
     * The session's cookies (in the app the WebView's jar while it still holds the sign-in, see
     * [DeezerBridge.host]), with the gateway's own `sid` (set by its answers to this client) on top,
     * so the token and the session it was issued to travel together.
     */
    private fun cookies(): String {
        val base = host.cookies() ?: throw BridgeException("not connected")
        return host.get("sid")?.let { withCookie(base, "sid", it) } ?: base
    }

    /** Who is signed in, with the gateway token. Cached ten minutes; a refused token fetches it again. */
    fun user(refresh: Boolean = false): User {
        if (!refresh) cachedUsers[host.cacheScope]?.let { (at, u) -> if (System.currentTimeMillis() - at < 600_000) return u }
        val r = gw("deezer.getUserData", jsonObj(), retry = false)
        val u = r["USER"]
        val id = u["USER_ID"].str?.takeIf { it.isNotBlank() && it != "0" } ?: throw BridgeException(EXPIRED)
        val token = r["checkForm"].str?.takeIf { it.isNotBlank() } ?: throw BridgeException("Deezer did not hand out a gateway token: sign out of Deezer and sign in again")
        val user = User(id, u["BLOG_NAME"].str?.takeIf { it.isNotBlank() } ?: u["FIRSTNAME"].str, token, u["LOVEDTRACKS_ID"].str)
        cachedUsers[host.cacheScope] = System.currentTimeMillis() to user
        return user
    }

    // ---- the gateway

    /** One gateway call; its `results`. A stale token is renewed once; a signed-out session says so plainly. */
    fun gw(method: String, args: JsonObject, retry: Boolean = true): JsonElement? {
        val token = if (method == "deezer.getUserData") "null" else user().token
        val url = GW.toHttpUrl().newBuilder()
            .addQueryParameter("method", method).addQueryParameter("input", "3")
            .addQueryParameter("api_version", "1.0").addQueryParameter("api_token", token).build()
        val req = Request.Builder().url(url).post(args.toString().toRequestBody(JSON))
            .header("Cookie", cookies())
            .header("User-Agent", host.userAgent)
            .header("Accept", "*/*").header("Accept-Language", "en-US,en;q=0.9")
            .header("Content-Type", "application/json")
            .header("Origin", ORIGIN).header("Referer", "$ORIGIN/")
            .build()
        val resp = send(req)
        resp.setCookies.forEach { c ->
            val pair = c.substringBefore(';')
            if (pair.substringBefore('=').trim() == "sid") pair.substringAfter('=', "").trim().takeIf { it.isNotEmpty() }?.let { host.put("sid", it) }
        }
        if (resp.code == 401 || resp.code == 403) throw BridgeException(EXPIRED)
        if (resp.code == 429) throw BridgeException("Deezer asks to slow down (429): try again in a few minutes")
        if (resp.code !in 200..299) throw BridgeException("Deezer $method: HTTP ${resp.code} ${resp.text.take(160)}")
        val j = runCatching { parseJson(resp.text) }.getOrNull() ?: throw BridgeException("Deezer $method: unreadable answer")
        val error = errorOf(j["error"])
        if (error != null) {
            val keys = (j["error"] as? JsonObject)?.keys.orEmpty()
            if (retry && (keys.any { it in TOKEN_ERRORS } || error.contains("CSRF", true))) {
                cachedUsers.remove(host.cacheScope)
                return gw(method, args, retry = false)
            }
            if (keys.any { it in AUTH_ERRORS } || keys.any { it in TOKEN_ERRORS }) throw BridgeException(EXPIRED)
            throw BridgeException("Deezer $method: ${error.take(300)}")
        }
        return j["results"]
    }

    private class Resp(val code: Int, val text: String, val setCookies: List<String>)

    /** One exchange, retried once on a transport error (a network switching between Wi-Fi and mobile data). */
    private fun send(req: Request): Resp {
        var attempt = 0
        while (true) {
            try {
                http.newCall(req).execute().use { r -> return Resp(r.code, r.body?.string() ?: "", r.headers("Set-Cookie")) }
            } catch (e: java.io.IOException) {
                if (attempt >= 1) throw BridgeException("Deezer unreachable: ${e.message ?: e.javaClass.simpleName}")
                Thread.sleep(1_500)
                attempt++
            }
        }
    }

    // ---- reads

    /** The user's playlists, own and followed, without the "Loved tracks" one (that is the liked slot). */
    fun libraryPlaylists(): List<PlaylistDto> {
        val me = user()
        val r = gw("deezer.pageProfile", jsonObj("USER_ID" to me.id, "tab" to "playlists", "nb" to MAX_PLAYLISTS))
        val tab = r["TAB"]["playlists"] ?: throw BridgeException("Deezer deezer.pageProfile: no playlists in the answer (keys ${keys(r)})")
        return tab["data"].arr.mapNotNull { p ->
            val id = p["PLAYLIST_ID"].str ?: return@mapNotNull null
            if (id == me.lovedId) return@mapNotNull null
            val owner = p["PARENT_USER_ID"].str
            PlaylistDto(id, p["TITLE"].str ?: "", p["NB_SONG"].num?.toInt() ?: -1, owned = owner == null || owner == me.id, description = p["DESCRIPTION"].str.orEmpty())
        }
    }

    private fun page(id: String): JsonElement? =
        gw("deezer.pagePlaylist", jsonObj("PLAYLIST_ID" to id, "lang" to "en", "nb" to 0, "start" to 0, "tab" to 0, "tags" to true, "header" to true))

    fun playlistInfo(id: String): PlaylistDto {
        val d = page(id)["DATA"] ?: throw BridgeException("Deezer: playlist $id not found")
        val me = runCatching { user().id }.getOrNull()
        return PlaylistDto(id, d["TITLE"].str ?: id, d["NB_SONG"].num?.toInt() ?: -1, owned = me != null && d["PARENT_USER_ID"].str == me, description = d["DESCRIPTION"].str.orEmpty())
    }

    /** A sign that changes with the content: the playlist's checksum, or the liked list's. */
    fun version(id: String): String? = when {
        id == LIKED -> gw("song.getFavoriteIds", jsonObj("nb" to 1, "start" to 0)).let { r ->
            r["checksum"].str ?: "${r["total"].num}|${r["data"][0]["SNG_ID"].str}"
        }
        id.startsWith("__") -> null
        else -> page(id)["DATA"]["CHECKSUM"].str
    }

    fun playlistTracks(id: String): List<TrackDto> {
        val out = ArrayList<TrackDto>()
        var broken = 0
        var start = 0
        var rounds = 0
        while (true) {
            val r = gw("playlist.getSongs", jsonObj("PLAYLIST_ID" to id, "nb" to -1, "start" to start))
            val data = r["data"].arr
            val total = r["total"].num?.toInt()
            for (s in data) song(s)?.let { out += it } ?: run { if (isCatalogSong(s)) broken++ }
            if (start == 0 && out.isEmpty() && data.isEmpty() && (r == null || (total ?: 0) > 0)) unexpected("playlist.getSongs", r)
            start += data.size
            // nb=-1 has always meant "all of them"; should it ever be capped, the rest is asked for.
            if (data.isEmpty() || total == null || start >= total || ++rounds >= 50) break
        }
        checkReadable("playlist.getSongs", out.size, broken)
        return out
    }

    /** Favourite tracks, newest first as Deezer lists them: their ids, then their details in batches. */
    fun likedTracks(): List<TrackDto> {
        val r = gw("song.getFavoriteIds", jsonObj("nb" to MAX_LIKED, "start" to 0))
        val favs = r["data"].arr.mapNotNull { f -> f["SNG_ID"].str?.let { it to f["DATE_ADD"].num } }
        if (favs.isEmpty()) {
            if ((r["total"].num ?: 0) > 0 || r == null) unexpected("song.getFavoriteIds", r)
            return emptyList()
        }
        val byId = HashMap<String, JsonElement>()
        favs.map { it.first }.chunked(BATCH).forEach { chunk ->
            gw("song.getListData", jsonObj("SNG_IDS" to chunk))["data"].arr.forEach { s -> s["SNG_ID"].str?.let { byId[it] = s } }
        }
        var broken = 0
        val out = favs.mapNotNull { (id, added) -> song(byId[id], added?.times(1000)) ?: run { broken++; null } }
        checkReadable("song.getListData", out.size, broken)
        return out
    }

    /** Catalogue search through the public API: by ISRC first when there is one, then by text. */
    fun search(query: String, isrc: String?): List<TrackDto> {
        isrc?.trim()?.uppercase()?.takeIf { it.length >= 10 }?.let { code ->
            catalog("$API/track/isrc:$code")?.let { catalogTrack(it) }?.let { return listOf(it) }
        }
        if (query.isBlank()) return emptyList()
        val url = "$API/search".toHttpUrl().newBuilder().addQueryParameter("q", query).addQueryParameter("limit", "10").build().toString()
        return catalog(url)["data"].arr.mapNotNull { catalogTrack(it) }
    }

    /** One track of the public catalogue (with its ISRC), or null when Deezer has none by that id. */
    fun track(id: String): TrackDto? = catalog("$API/track/$id")?.let { catalogTrack(it) }

    /**
     * A GET on the public API. Its errors come as JSON with HTTP 200: 800 is "no data" (null here),
     * 4 is its quota (50 requests in 5 seconds), waited out at most twice.
     */
    private fun catalog(url: String): JsonElement? {
        var attempt = 0
        while (true) {
            val req = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", host.userAgent).build()
            val resp = send(req)
            if (resp.code !in 200..299) throw BridgeException("Deezer catalogue: HTTP ${resp.code}")
            val j = parseJson(resp.text)
            val err = j["error"] ?: return j
            when (err["code"].num) {
                800L -> return null
                4L -> if (attempt < 2) { attempt++; Thread.sleep(quotaWaitMs); continue }
            }
            throw BridgeException("Deezer catalogue: ${err["message"].str ?: err.toString()}")
        }
    }

    // ---- writes

    /** A new playlist, private: syncing a private list must not publish it. */
    fun createPlaylist(name: String, description: String): PlaylistDto {
        val r = gw("playlist.create", jsonObj("title" to name, "description" to description, "status" to STATUS_PRIVATE, "songs" to emptyList<Any>()))
        val id = r.str?.takeIf { it.isNotBlank() && it != "0" } ?: r["PLAYLIST_ID"].str ?: throw BridgeException("Deezer: playlist not created")
        return PlaylistDto(id, name, 0, owned = true, description = description)
    }

    fun addToPlaylist(id: String, songIds: List<String>) {
        songIds.chunked(BATCH_WRITE).forEach { chunk ->
            gw("playlist.addSongs", jsonObj("playlist_id" to num(id), "songs" to chunk.map { listOf(num(it), 0) }, "offset" to -1))
        }
    }

    /** By track id: every copy of it leaves the playlist (Deezer keeps one copy per track anyway). */
    fun removeFromPlaylist(id: String, songIds: List<String>) {
        songIds.distinct().chunked(BATCH_WRITE).forEach { chunk ->
            gw("playlist.deleteSongs", jsonObj("playlist_id" to num(id), "songs" to chunk.map { listOf(num(it), 0) }))
        }
    }

    fun renamePlaylist(id: String, name: String) {
        gw("playlist.update", jsonObj("playlist_id" to num(id), "title" to name))
    }

    fun deletePlaylist(id: String) {
        gw("playlist.delete", jsonObj("playlist_id" to num(id)))
    }

    /** Favourite tracks, one call each as the player does, a little apart. */
    fun like(songIds: List<String>, on: Boolean) {
        songIds.forEachIndexed { i, s ->
            if (i > 0) Thread.sleep(pauseMs)
            gw(if (on) "favorite_song.add" else "favorite_song.remove", jsonObj("SNG_ID" to num(s)))
        }
    }

    // ---- shapes

    /** A song of the gateway; null for anything else and for the user's own uploads (negative ids, not in the catalogue). */
    private fun song(s: JsonElement?, addedAt: Long? = null): TrackDto? {
        if (s == null || s["__TYPE__"].str?.let { it != "song" } == true) return null
        val id = s["SNG_ID"].str ?: return null
        if (id.startsWith("-") || id == "0") return null
        val base = s["SNG_TITLE"].str ?: return null
        // "Song" + "(Live)": the version is what tells a live take from the studio one.
        val version = s["VERSION"].str?.trim()?.takeIf { it.isNotEmpty() && !base.contains(it, ignoreCase = true) }
        val artists = s["ARTISTS"].arr.mapNotNull { it["ART_NAME"].str }.ifEmpty { listOfNotNull(s["ART_NAME"].str) }
        val explicit = (s["EXPLICIT_TRACK_CONTENT"]["EXPLICIT_LYRICS_STATUS"].num ?: s["EXPLICIT_LYRICS"].num)?.let { it == 1L }
        return TrackDto(
            id = id, title = if (version != null) "$base $version" else base, artists = artists,
            album = s["ALB_TITLE"].str.orEmpty(), durationMs = (s["DURATION"].num ?: 0) * 1000,
            explicit = explicit, addedAt = addedAt ?: s["DATE_ADD"].num?.times(1000),
            year = (s["PHYSICAL_RELEASE_DATE"].str ?: s["DIGITAL_RELEASE_DATE"].str)?.take(4)?.toIntOrNull()?.takeIf { it in 1000..2999 },
            isrc = s["ISRC"].str?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun isCatalogSong(s: JsonElement?): Boolean = s["SNG_ID"].str?.let { !it.startsWith("-") } == true

    private fun catalogTrack(t: JsonElement?): TrackDto? {
        if (t["type"].str?.let { it != "track" } == true) return null
        val id = t["id"].str ?: return null
        val title = t["title"].str ?: return null
        val artists = t["contributors"].arr.mapNotNull { it["name"].str }.ifEmpty { listOfNotNull(t["artist"]["name"].str) }
        return TrackDto(
            id = id, title = title, artists = artists, album = t["album"]["title"].str.orEmpty(),
            durationMs = (t["duration"].num ?: 0) * 1000, explicit = t["explicit_lyrics"].bool,
            available = t["readable"].bool, year = t["release_date"].str?.take(4)?.toIntOrNull()?.takeIf { it in 1000..2999 },
            isrc = t["isrc"].str?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * A listing where many entries could not be read is a shape the parser no longer understands,
     * not a shorter playlist: handing it over would make a mirror sync remove what it could not read.
     */
    private fun checkReadable(method: String, parsed: Int, broken: Int) {
        if (broken > 10 && broken * 5 > parsed + broken) throw BridgeException("Deezer $method: $broken of ${parsed + broken} tracks could not be read, the answer's shape has changed")
    }

    private fun unexpected(method: String, r: JsonElement?): Nothing =
        throw BridgeException("Deezer $method: nothing readable in the answer (keys ${keys(r)})")

    private fun keys(e: JsonElement?) = (e as? JsonObject)?.keys?.joinToString(",") ?: e?.javaClass?.simpleName ?: "null"

    companion object {
        const val ORIGIN = "https://www.deezer.com"
        const val GW = "https://www.deezer.com/ajax/gw-light.php"
        const val API = "https://api.deezer.com"
        const val LIKED = "__liked__"
        /** What the person reads when the session is gone: no app named, the same text in every build. */
        const val EXPIRED = "the Deezer web session has expired: sign out of Deezer and sign in again"
        private const val STATUS_PRIVATE = 1
        private const val MAX_PLAYLISTS = 2000
        private const val MAX_LIKED = 10_000
        private const val BATCH = 500
        private const val BATCH_WRITE = 100
        private val JSON = "application/json".toMediaType()
        private val TOKEN_ERRORS = setOf("VALID_TOKEN_REQUIRED", "INVALID_CSRF_TOKEN")
        private val AUTH_ERRORS = setOf("NEED_USER_AUTH_REQUIRED", "USER_AUTH_REQUIRED", "GATEWAY_ERROR_AUTH")
        /** The signed-in user of each account (by [WebHost.cacheScope]): one per session, never another's. */
        private val cachedUsers = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, User>>()
        /** Pauses: the public API's quota window, the gap between one-by-one writes. Shortened by the tests. */
        @Volatile var quotaWaitMs = 5_000L
        @Volatile var pauseMs = 120L

        /** Forgets the account's cached user and token ([scope]: its host's [WebHost.cacheScope]): after a sign-out, or a sign-in to another account. */
        fun forget(scope: String = "") { cachedUsers.remove(scope) }

        /** The gateway's `error`: an empty array or object when all is well, a map of codes to messages when not. */
        fun errorOf(e: JsonElement?): String? = when (e) {
            null, JsonNull -> null
            is JsonObject -> if (e.isEmpty()) null else e.entries.joinToString("; ") { (k, v) -> "$k: ${v.str ?: v.toString()}" }
            is JsonArray -> if (e.isEmpty()) null else e.joinToString("; ") { it.str ?: it.toString() }
            is JsonPrimitive -> e.str?.takeIf { it.isNotBlank() && it != "false" }
        }

        /** [cookies] with [name] set to [value], replacing what was there. */
        fun withCookie(cookies: String, name: String, value: String): String =
            (cookies.split(';').map { it.trim() }.filter { it.isNotEmpty() && it.substringBefore('=') != name } + "$name=$value").joinToString("; ")

        /** Ids go out as numbers, the way the player sends them. */
        private fun num(id: String): Any = id.toLongOrNull() ?: id
    }
}

/** A number the gateway may send as a number or as a string ("215"). */
private val JsonElement?.num: Long? get() = long ?: str?.trim()?.toLongOrNull()
private val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.booleanOrNull
