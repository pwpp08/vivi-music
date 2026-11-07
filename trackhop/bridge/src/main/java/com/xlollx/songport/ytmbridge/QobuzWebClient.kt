package com.xlollx.songport.ytmbridge

import com.xlollx.songport.ytmbridge.BridgeNet.withHook
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Qobuz through play.qobuz.com's own web player: Qobuz gives API access to partners only, so the
 * quick way is the player's.
 *
 * The session is the player's user token (`X-User-Auth-Token`, 86 characters), which every call to
 * `www.qobuz.com/api.json/0.2/` carries as a header next to the player's app id (`X-App-Id`). Trackhop
 * never signs in itself: the user does, on Qobuz's page (the app reads both headers from the
 * player's requests) or pastes them (Desktop and Docker).
 *
 * What the player does, seen in a signed-in session of 04/11/2026, none of it documented:
 * - GET playlist/getUserPlaylists: the account's playlists (its own and those it follows), with `user`;
 * - GET playlist/get (extra=tracks): a playlist's tracks, each with its `playlist_track_id`;
 * - GET favorite/getUserFavorites (type=tracks): the favourite tracks, Trackhop's liked songs;
 * - GET catalog/search: tracks (and albums) for a text, an ISRC included;
 * - GET album/get (extra=track_ids): an album's tracks;
 * - POST playlist/create, addTracks, deleteTracks, update, delete; favorite/create, delete.
 * The POST bodies were not recorded: their fields are in [Form], in one place.
 * Only a few calls are signed (request_ts, request_sig: user/tracking, search/report): none used here.
 *
 * No Android here: the server build compiles this file as it is (server/build.gradle).
 */
class QobuzWebClient(private val host: WebHost) {

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).withHook().build()

    private fun token(): String = host.get(TOKEN) ?: throw BridgeException("not connected")

    /** The app id the player was seen with; the one it had on 04/11/2026 until then. */
    private fun appId(): String = host.get(APP_ID)?.takeIf { it.isNotBlank() } ?: DEFAULT_APP_ID

    /** [signed]: the session goes along; a search sends it only when there is one (the catalogue needs just the app id). */
    private fun request(path: String, params: Map<String, String> = emptyMap(), signed: Boolean = true): Request.Builder {
        val url = API + path + if (params.isEmpty()) "" else "?" + params.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val b = Request.Builder().url(url)
            .header("User-Agent", host.userAgent).header("Origin", SITE).header("Referer", "$SITE/").header("Accept", "application/json")
            .header("X-App-Id", appId())
        (if (signed) token() else host.get(TOKEN))?.let { b.header("X-User-Auth-Token", it) }
        return b
    }

    private fun read(req: Request): JsonElement {
        val (code, body) = http.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
        if (code == 401) throw BridgeException(EXPIRED)
        if (code == 429) throw BridgeException("Qobuz asks to slow down (429): try again in a few minutes")
        val j = runCatching { parseJson(body) }.getOrNull()
        if (code !in 200..299) throw BridgeException(problemIn(j) ?: "Qobuz HTTP $code")
        if (j == null) throw BridgeException("Qobuz answered something that is not JSON")
        problemIn(j)?.let { throw BridgeException(it) }
        return j
    }

    private fun get(path: String, params: Map<String, String> = emptyMap(), signed: Boolean = true): JsonElement =
        read(request(path, params, signed).get().build())

    /** A write, as a form: the way the open-source clients post to the same API. */
    private fun post(path: String, form: Map<String, String>): JsonElement {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return read(request(path).post(body).build())
    }

    /** Every item of a paged listing ({limit, offset, total, items}), the listing found by [section] in each answer. */
    private fun all(path: String, params: Map<String, String>, section: (JsonElement) -> JsonElement?, first: JsonElement? = null): List<JsonElement> {
        val out = ArrayList<JsonElement>()
        var page = first ?: get(path, params + mapOf("offset" to "0", "limit" to "$PAGE"))
        var n = 0
        while (true) {
            val s = section(page)
            val items = s["items"].arr
            out += items
            val total = s["total"].num?.toInt() ?: out.size
            if (items.isEmpty() || out.size >= total || ++n >= MAX_PAGES) break
            page = get(path, params + mapOf("offset" to "${out.size}", "limit" to "$PAGE"))
        }
        return out
    }

    // ---- reading

    private fun userPlaylists(): Pair<JsonElement, List<JsonElement>> {
        val first = get("playlist/getUserPlaylists", mapOf("offset" to "0", "limit" to "$PAGE"))
        return first to all("playlist/getUserPlaylists", emptyMap(), { it["playlists"] }, first)
    }

    /** The account's playlists, its own and the ones it follows (those marked not owned). */
    fun playlists(): List<PlaylistDto> {
        val (first, items) = userPlaylists()
        val me = first["user"]["id"].str
        return items.mapNotNull { parsePlaylist(it, me) }
    }

    fun tracks(playlistId: String): List<TrackDto> =
        all("playlist/get", mapOf("playlist_id" to playlistId, "extra" to "tracks"), { it["tracks"] }).mapNotNull { parseTrack(it) }

    fun liked(): List<TrackDto> =
        all("favorite/getUserFavorites", mapOf("type" to "tracks"), { it["tracks"] }).mapNotNull { parseTrack(it) }

    /**
     * The catalogue's tracks: by ISRC first when there is one (the search takes the code as its text;
     * only results that carry that very ISRC are kept), then by [query].
     */
    fun search(query: String, isrc: String? = null, max: Int = 10): List<TrackDto> {
        isrc?.trim()?.uppercase()?.takeIf { it.length >= 10 }?.let { code ->
            val hits = searchTracks(code, max).filter { it.isrc.equals(code, ignoreCase = true) }
            if (hits.isNotEmpty()) return hits
        }
        if (query.isBlank()) return emptyList()
        return searchTracks(query, max)
    }

    private fun searchTracks(query: String, max: Int): List<TrackDto> =
        parseSearch(get("catalog/search", mapOf("query" to query, "offset" to "0", "limit" to "$max"), signed = false)).take(max)

    /** An album's tracks, with the album's title and artist where the tracks leave them out. */
    fun album(albumId: String): List<TrackDto> = parseAlbum(get("album/get", mapOf("album_id" to albumId, "extra" to "track_ids")))

    /** Checks the session: reads the playlists; the account name, when the answer gives one. */
    fun probe(): String = accountIn(get("playlist/getUserPlaylists", mapOf("offset" to "0", "limit" to "50"))) ?: host.account() ?: "Qobuz"

    // ---- writing

    fun create(name: String, description: String = "", public: Boolean = false): PlaylistDto {
        val j = post("playlist/create", mapOf(Form.NAME to name, Form.DESCRIPTION to description, Form.IS_PUBLIC to public.toString(), Form.IS_COLLABORATIVE to "false"))
        val id = j["id"].str ?: throw BridgeException("Qobuz did not create the playlist")
        return PlaylistDto(id = id, name = j["name"].str ?: name, count = 0, description = description)
    }

    fun add(playlistId: String, trackIds: List<String>) =
        trackIds.chunked(CHUNK).forEach { post("playlist/addTracks", mapOf(Form.PLAYLIST_ID to playlistId, Form.TRACK_IDS to it.joinToString(","), Form.NO_DUPLICATE to "true")) }

    /**
     * Removes by entry: Qobuz deletes a playlist's `playlist_track_id`s, not track ids. [entryIds] are
     * those the listing gave (null where unknown); a track without one has every copy of it removed,
     * its entries read from the playlist.
     */
    fun remove(playlistId: String, trackIds: List<String>, entryIds: List<String?> = emptyList()) {
        val known = entryIds.filterNotNull().filter { it.isNotBlank() }
        val unknown = trackIds.filterIndexed { i, _ -> entryIds.getOrNull(i).isNullOrBlank() }.toSet()
        val looked = if (unknown.isEmpty()) emptyList() else tracks(playlistId).filter { it.id in unknown }.mapNotNull { it.setVideoId }
        (known + looked).distinct().chunked(CHUNK).forEach {
            post("playlist/deleteTracks", mapOf(Form.PLAYLIST_ID to playlistId, Form.PLAYLIST_TRACK_IDS to it.joinToString(",")))
        }
    }

    /** Favourite tracks are Trackhop's liked songs. */
    fun like(trackIds: List<String>, on: Boolean) =
        trackIds.chunked(CHUNK).forEach { post(if (on) "favorite/create" else "favorite/delete", mapOf(Form.TRACK_IDS to it.joinToString(","))) }

    fun rename(playlistId: String, name: String) { post("playlist/update", mapOf(Form.PLAYLIST_ID to playlistId, Form.NAME to name)) }

    fun setPublic(playlistId: String, public: Boolean) { post("playlist/update", mapOf(Form.PLAYLIST_ID to playlistId, Form.IS_PUBLIC to public.toString())) }

    fun delete(playlistId: String) { post("playlist/delete", mapOf(Form.PLAYLIST_ID to playlistId)) }

    /**
     * The fields of the POST forms. NOT captured yet (the player's request bodies were not recorded
     * on 04/11/2026): these are the names the open-source Qobuz clients (streamrip, qobuz-dl) send to
     * the same API. A capture that shows otherwise is fixed here, one line each.
     */
    object Form {
        const val PLAYLIST_ID = "playlist_id"
        const val NAME = "name"
        const val DESCRIPTION = "description"
        const val IS_PUBLIC = "is_public"
        const val IS_COLLABORATIVE = "is_collaborative"
        /** Comma-separated track ids (addTracks, favorite/create and favorite/delete). */
        const val TRACK_IDS = "track_ids"
        /** Comma-separated entry ids (deleteTracks): each item's `playlist_track_id`, not its track id. */
        const val PLAYLIST_TRACK_IDS = "playlist_track_ids"
        const val NO_DUPLICATE = "no_duplicate"
    }

    companion object {
        const val SITE = "https://play.qobuz.com"
        const val LOGIN_URL = "https://play.qobuz.com/login"
        const val API_HOST = "www.qobuz.com"
        private const val API = "https://www.qobuz.com/api.json/0.2/"
        /** The web player's app id as seen on 04/11/2026: used until the sign-in has seen the current one. */
        const val DEFAULT_APP_ID = "798273057"
        private const val PAGE = 500
        private const val CHUNK = 50
        private const val MAX_PAGES = 100
        const val TOKEN = "userAuthToken"; const val APP_ID = "appId"
        const val EXPIRED = "the Qobuz session has ended: sign in again"

        /** A user token as the player sends it: 86 characters seen, letters, digits, '-' and '_'. */
        private const val TOKEN_CHARS = """[A-Za-z0-9_\-]{40,200}"""
        private val TOKEN_HEADER = Regex("""(?i)x-user-auth-token["']?\s*[:=]\s*["']?($TOKEN_CHARS)""")
        private val TOKEN_PARAM = Regex("""(?i)[?&"]user_auth_token["']?\s*[=:]\s*["']?($TOKEN_CHARS)""")
        private val TOKEN_ALONE = Regex("""^$TOKEN_CHARS$""")
        private val APP_HEADER = Regex("""(?i)x-app-id["']?\s*[:=]\s*["']?(\d{6,12})""")
        private val APP_PARAM = Regex("""(?i)[?&"]app_id["']?\s*[=:]\s*["']?(\d{6,12})""")

        /**
         * The session in [text]: the user token, with the app id when [text] shows it. What is pasted
         * can be a request copied from the developer tools (as cURL, as fetch, or its headers), an
         * address with `user_auth_token=`, or the token alone; null without a token.
         */
        fun sessionIn(text: String?): Pair<String, String?>? {
            val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val token = TOKEN_HEADER.find(t)?.groupValues?.get(1) ?: TOKEN_PARAM.find(t)?.groupValues?.get(1)
                ?: t.takeIf { TOKEN_ALONE.matches(it) } ?: return null
            val app = APP_HEADER.find(t)?.groupValues?.get(1) ?: APP_PARAM.find(t)?.groupValues?.get(1)
            return token to app
        }

        /** The session a player request to [url] carries in its [headers] (any case), or null. */
        fun sessionOf(url: String, headers: Map<String, String>?): Pair<String, String?>? {
            val u = runCatching { java.net.URI(url) }.getOrNull() ?: return null
            if (u.host != API_HOST || u.path?.startsWith("/api.json/") != true || headers == null) return null
            fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()
            val token = header("X-User-Auth-Token")?.takeIf { TOKEN_ALONE.matches(it) } ?: return null
            return token to header("X-App-Id")?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
        }

        /** Qobuz's refusals: `{"status":"error","code":401,"message":"User authentication is required."}`. */
        fun problemIn(j: JsonElement?): String? {
            if (j["status"].str != "error") return null
            val msg = j["message"].str ?: "refused"
            return if (j["code"].num == 401L) EXPIRED else "Qobuz: $msg"
        }

        /** The account's name as the playlists' answer gives it: its `user`, else the owner of one of its own playlists. */
        fun accountIn(j: JsonElement?): String? {
            val u = j["user"]
            listOf("display_name", "name", "login").firstNotNullOfOrNull { u[it].str?.trim()?.takeIf { s -> s.isNotEmpty() } }?.let { return it }
            val me = u["id"].str ?: return null
            return j["playlists"]["items"].arr.firstOrNull { it["owner"]["id"].str == me }["owner"]["name"].str?.takeIf { it.isNotBlank() }
        }

        fun parsePlaylist(p: JsonElement?, me: String?): PlaylistDto? {
            val id = p["id"].str ?: return null
            val name = p["name"].str ?: return null
            val owner = p["owner"]["id"].str
            return PlaylistDto(
                id = id, name = name, count = p["tracks_count"].num?.toInt() ?: -1,
                // Followed playlists come in the same list: readable, not writable.
                owned = me == null || owner == null || owner == me,
                description = p["description"].str.orEmpty(),
            )
        }

        /** One track of any listing (search, playlist, favourites, album); [album] and [albumArtist] fill what an album's tracks leave out. */
        fun parseTrack(j: JsonElement?, album: String? = null, albumArtist: String? = null, albumYear: Int? = null): TrackDto? {
            val id = j["id"].str?.takeIf { it.isNotBlank() } ?: return null
            val base = j["title"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            // "Song" + "Live": the version is what tells a live take or a remix from the studio one.
            val version = j["version"].str?.trim()?.takeIf { it.isNotEmpty() && !base.contains(it.trim('(', ')'), ignoreCase = true) }
            val title = when {
                version == null -> base
                version.startsWith("(") || version.startsWith("[") -> "$base $version"
                else -> "$base ($version)"
            }
            val a = j["album"]
            return TrackDto(
                id = id, title = title,
                artists = listOfNotNull((j["performer"]["name"].str ?: a["artist"]["name"].str ?: albumArtist)?.trim()?.takeIf { it.isNotEmpty() }),
                album = (a["title"].str ?: album).orEmpty().trim(),
                durationMs = (j["duration"].num ?: 0) * 1000,
                setVideoId = j["playlist_track_id"].str,
                explicit = j["parental_warning"].bool,
                year = (a["release_date_original"].str ?: j["release_date_original"].str)?.take(4)?.toIntOrNull()?.takeIf { it in 1000..2999 } ?: albumYear,
                available = j["streamable"].bool,
                isrc = j["isrc"].str?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        fun parseSearch(j: JsonElement?): List<TrackDto> = j["tracks"]["items"].arr.mapNotNull { parseTrack(it) }

        fun parseAlbum(j: JsonElement?): List<TrackDto> {
            val year = j["release_date_original"].str?.take(4)?.toIntOrNull()
            return j["tracks"]["items"].arr.mapNotNull { parseTrack(it, j["title"].str, j["artist"]["name"].str, year) }
        }
    }
}

/** A number Qobuz may send as a number or as a string. */
private val JsonElement?.num: Long? get() = long ?: str?.trim()?.toLongOrNull()
private val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.booleanOrNull
