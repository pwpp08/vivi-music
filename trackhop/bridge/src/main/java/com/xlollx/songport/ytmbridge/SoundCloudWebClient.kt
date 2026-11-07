package com.xlollx.songport.ytmbridge

import android.content.Context
import com.xlollx.songport.ytmbridge.BridgeNet.withHook
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * SoundCloud through `api-v2.soundcloud.com`, the interface soundcloud.com's own web app uses: the
 * web app's `client_id` on every request and, for the account, `Authorization: OAuth <oauth_token>`.
 * Not an official interface (SoundCloud closed its public API registrations long ago).
 *
 * Tracks are handed over as SoundCloud's own track objects, trimmed to the fields Trackhop reads:
 * Trackhop parses them with the same code that reads public SoundCloud links, so titles, artists and
 * durations come out the same whichever way a track arrives. SoundCloud has few ISRCs; the matching
 * relies on title, artist and duration.
 *
 * A playlist id is SoundCloud's numeric set id, [LIKED] for the liked tracks, or the path of a
 * public page ("user/sets/slug", "user/likes"), read through `/resolve`.
 *
 * Sets are written whole: SoundCloud takes a set's complete track list in one PUT, so adding,
 * removing and reordering read the current list, change it and send it back.
 */
class SoundCloudWebClient(private val ctx: Context) {

    class NotFound : Exception("not found")

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).withHook().build()
    private val session get() = SoundCloudBridge.session

    private fun token(): String? = SoundCloudBridge.token(ctx)

    // ---- the web app's client id

    /**
     * The client id the web app uses: the one seen on its requests at sign-in, else read from its
     * scripts the way Trackhop's public-link reader does (the last bundles of the home page carry
     * `client_id:"…"`). Fetched again when SoundCloud refuses the one kept.
     */
    fun clientId(refresh: Boolean = false): String {
        if (!refresh) session.get(ctx, "clientId")?.let { return it }
        val home = fetch("$SITE/", "text/html")
        for (url in scriptUrls(home)) {
            val js = runCatching { fetch(url, "*/*") }.getOrNull() ?: continue
            clientIdIn(js)?.let { session.put(ctx, "clientId", it); return it }
        }
        throw BridgeException("SoundCloud changed its web app: no client id in its scripts")
    }

    /** The site's page and scripts, read without the account (as Trackhop's public-link reader does, as desktop Chrome). */
    private fun fetch(url: String, accept: String): String {
        val req = Request.Builder().url(url).header("User-Agent", BridgeUa.desktop(ctx)).header("Accept", accept).build()
        val resp = send(req)
        if (resp.first == 403 && DataDome.kindOfBody(resp.second) != null) throw BridgeException(SoundCloudBridge.BLOCKED)
        if (resp.first !in 200..299) throw BridgeException("SoundCloud: HTTP ${resp.first} for ${url.substringBefore('?')}")
        return resp.second
    }

    // ---- api-v2

    /**
     * One call. A 401 or 403 is first taken for a rotated client id (fetched again, once); a second
     * refusal with the account's token means the session is gone. A 404 is [NotFound]. A refusal
     * from DataDome (its 403 names captcha-delivery.com) is said as such at once: asking again,
     * for a client id or anything else, would only add to what it counts.
     *
     * The headers match the sign-in: its user agent and, when DataDome left one, its cookie.
     *
     * The calls go to api-v2, the desktop web app's interface. A token taken from the mobile site's
     * requests (api-mobi.soundcloud.com) is SoundCloud's one OAuth token for the account and is
     * expected to work on api-v2 as well, as the desktop site's cookie token does; should api-v2
     * refuse it, the same call is tried once on api-mobi, which has the same paths, and when that
     * one answers the session's calls go there from then on (both said in the diagnostics log).
     *
     * The token itself lasts about an hour (the mobile site's `oauth_token`). A token older than
     * about 50 minutes is renewed before the call, and a token still refused after all of the above
     * is renewed then: SoundCloud's mobile site, opened in a hidden page, renews its own session
     * ([SoundCloudRenewal]), and the call is made once more with the new token. Only when that fails
     * is the session over ([EXPIRED]).
     */
    private fun api(method: String, path: String, body: JsonElement? = null): JsonElement {
        SoundCloudRenewal.renewIfOld(ctx)
        var refreshed = false
        var target = session.get(ctx, "apiHost") ?: SoundCloudBridge.API_V2
        var switched = false
        var renewed = false
        while (true) {
            val mobi = target == SoundCloudBridge.API_MOBI
            val cid = (if (mobi) session.get(ctx, "clientIdMobi") else null) ?: clientId(refresh = false)
            val given = (if (path.startsWith("http")) path else API + path).toHttpUrl()
            val url = given.newBuilder()
                .apply { if (given.host == SoundCloudBridge.API_V2 || given.host == SoundCloudBridge.API_MOBI) host(target) }
                .removeAllQueryParameters("client_id").addQueryParameter("client_id", cid).build()
            val origin = if (mobi) SoundCloudBridge.MOBILE_SITE else SITE
            val b = Request.Builder().url(url)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("User-Agent", SoundCloudBridge.userAgent(ctx))
                .header("Origin", origin).header("Referer", "$origin/")
            val jar = session.cookies(ctx)
            val tok = token()
            if (tok != null) b.header("Authorization", "OAuth $tok")
            SoundCloudBridge.dataDomeCookie(jar)?.let { b.header("Cookie", "datadome=$it") }
            val payload = (body?.toString() ?: "").toRequestBody(JSON)
            when (method) {
                "GET" -> b.get()
                "DELETE" -> b.delete()
                else -> b.method(method, payload)
            }
            val (code, text) = send(b.build())
            if (switched && code in 200..299) {
                session.put(ctx, "apiHost", target)
                BridgeNet.log("soundcloud", "api-v2 refused the session's token (HTTP 401) and ${SoundCloudBridge.API_MOBI} accepted it: the account's calls go there")
            } else if (switched && code == 401) {
                BridgeNet.log("soundcloud", "the session's token was refused by api-v2 and by ${SoundCloudBridge.API_MOBI} (HTTP 401)")
            }
            when {
                code in 200..299 -> return runCatching { parseJson(text) }.getOrNull() ?: JsonNull
                code == 404 -> throw NotFound()
                code == 403 && DataDome.kindOfBody(text) != null -> throw BridgeException(SoundCloudBridge.BLOCKED)
                (code == 401 || code == 403) && !refreshed -> { refreshed = true; runCatching { clientId(refresh = true) } }
                // A token seen on the mobile site's API that api-v2 does not take: tried there, once.
                code == 401 && tok != null && !switched && !renewed && !mobi && seenOnMobi() -> { switched = true; target = SoundCloudBridge.API_MOBI }
                // Refused everywhere: the token has expired. The site renews it, and the call is made once more.
                code == 401 && tok != null && !renewed -> {
                    renewed = true
                    if (!SoundCloudRenewal.renew(ctx, tok, "the token was refused with HTTP 401 on ${url.host}")) throw BridgeException(EXPIRED)
                    switched = false
                    target = session.get(ctx, "apiHost") ?: SoundCloudBridge.API_V2
                }
                code == 401 -> throw BridgeException(if (tok != null) EXPIRED else "SoundCloud refused the web app's client id: it may have changed its web app")
                code == 403 -> throw BridgeException("SoundCloud refused this ($method ${url.encodedPath}): HTTP 403")
                code == 422 -> throw BridgeException("SoundCloud did not accept the change ($method ${url.encodedPath}): ${text.take(200)}")
                code == 429 -> throw BridgeException("SoundCloud asks to slow down (429): try again in a few minutes")
                else -> throw BridgeException("SoundCloud $method ${url.encodedPath}: HTTP $code ${text.take(160)}")
            }
        }
    }

    /** Whether the sign-in saw the mobile site's API: its token came from there, or it named itself there with a client id. */
    private fun seenOnMobi(): Boolean =
        SoundCloudBridge.tokenSource(ctx)?.endsWith(SoundCloudBridge.API_MOBI) == true || session.get(ctx, "clientIdMobi") != null

    /** One exchange, retried once on a transport error. */
    private fun send(req: Request): Pair<Int, String> {
        var attempt = 0
        while (true) {
            try {
                http.newCall(req).execute().use { r -> return r.code to (r.body?.string() ?: "") }
            } catch (e: java.io.IOException) {
                if (attempt >= 1) throw BridgeException("SoundCloud unreachable: ${e.message ?: e.javaClass.simpleName}")
                Thread.sleep(1_500)
                attempt++
            }
        }
    }

    private fun resolve(pageUrl: String): JsonElement =
        api("GET", "$API/resolve".toHttpUrl().newBuilder().addQueryParameter("url", pageUrl).build().toString())

    // ---- the account

    /** The signed-in user's id and name, from `/me`; kept in the session. */
    fun me(refresh: Boolean = false): Pair<String, String?> {
        if (!refresh) session.get(ctx, "userId")?.let { return it to session.account(ctx) }
        if (token() == null) throw BridgeException(if (session.isConnected(ctx)) EXPIRED else "not connected")
        val j = api("GET", "/me")
        val id = j["id"].long?.toString() ?: throw BridgeException(EXPIRED)
        if (refresh) BridgeNet.log("soundcloud", "account read through ${session.get(ctx, "apiHost") ?: SoundCloudBridge.API_V2} with the token from ${SoundCloudBridge.tokenSource(ctx) ?: "the soundcloud.com cookie"}")
        session.put(ctx, "userId", id)
        return id to (j["username"].str ?: j["full_name"].str)
    }

    private fun meId(): String = me().first

    // ---- reads

    /** The user's own sets, then the sets they liked (read-only). Albums are not listed. */
    fun libraryPlaylists(): List<PlaylistDto> {
        val me = meId()
        val out = ArrayList<PlaylistDto>()
        paged("$API/users/$me/playlists_without_albums?limit=50&linked_partitioning=1") { item -> playlistDto(item, me)?.let { out += it } }
        // Liked sets are extra: a change there must not cost the user's own list.
        runCatching {
            paged("$API/users/$me/playlist_likes?limit=50&linked_partitioning=1") { item ->
                playlistDto(item["playlist"] ?: item, me)?.let { p -> if (out.none { it.id == p.id }) out += p }
            }
        }
        return out
    }

    private fun paged(first: String, each: (JsonElement) -> Unit) {
        var next: String? = first
        var pages = 0
        while (next != null && pages++ < 100) {
            val j = api("GET", next)
            j["collection"].arr.forEach(each)
            next = j["next_href"].str?.takeIf { it.isNotBlank() }
        }
    }

    private fun playlistDto(p: JsonElement?, me: String?, id: String? = null): PlaylistDto? {
        val pid = id ?: p["id"].long?.toString() ?: return null
        val owner = p["user_id"].long?.toString() ?: p["user"]["id"].long?.toString()
        return PlaylistDto(pid, p["title"].str ?: "", (p["track_count"].long ?: p["tracks"].arr.size.toLong()).toInt(),
            owned = me != null && owner == me, description = p["description"].str.orEmpty())
    }

    fun playlistInfo(id: String): PlaylistDto {
        if (isPath(id)) {
            if (isLikes(id)) {
                val u = user(id)
                return PlaylistDto(id, "${u["username"].str ?: id.substringBefore('/')} · Likes", (u["likes_count"].long ?: -1).toInt(), owned = false)
            }
            return playlistDto(set(id), null, id) ?: throw BridgeException(NOT_PUBLIC)
        }
        if (id == LIKED) return PlaylistDto(LIKED, "Likes", -1)
        val me = runCatching { meId() }.getOrNull()
        return playlistDto(playlist(id), me) ?: throw BridgeException("SoundCloud: playlist $id not found")
    }

    /** What changes when a set changes: its modification date and size; for likes, see [likesVersion]. */
    fun version(id: String): String? {
        if (id == LIKED) {
            val me = api("GET", "/me")
            return likesVersion(me["id"].long?.toString() ?: meId(), me["likes_count"].long)
        }
        if (isPath(id) && isLikes(id)) {
            val u = user(id)
            return likesVersion(u["id"].long?.toString() ?: return null, u["likes_count"].long)
        }
        val s = if (isPath(id)) set(id) else playlist(id)
        return listOfNotNull(s["last_modified"].str, s["track_count"].long?.toString()).joinToString("|").ifEmpty { null }
    }

    /**
     * Likes have no modification date: their count and the newest like (when, and which track)
     * stand in for one. A new like changes the newest, an unlike the count; one small page read.
     */
    private fun likesVersion(uid: String, count: Long?): String? {
        val newest = api("GET", "$API/users/$uid/track_likes?limit=1&linked_partitioning=1")["collection"].arr.firstOrNull()
        val parts = listOfNotNull(count?.toString(), newest?.get("created_at").str, newest?.get("track")?.get("id").long?.toString())
        return parts.joinToString("|").ifEmpty { null }
    }

    /** The tracks of a set, the liked tracks, or a public page's, as trimmed track objects. */
    fun tracks(id: String): List<JsonObject> = when {
        id == LIKED -> likes(meId())
        isPath(id) && isLikes(id) -> likes(user(id)["id"].long?.toString() ?: throw BridgeException(NOT_PUBLIC))
        isPath(id) -> setTracks(set(id))
        else -> setTracks(playlist(id))
    }

    private fun likes(uid: String): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        var next: String? = "$API/users/$uid/track_likes?limit=200&linked_partitioning=1"
        while (next != null && out.size < MAX_LIKES) {
            val j = api("GET", next)
            for (like in j["collection"].arr) {
                val t = trim(like["track"]) ?: continue
                out += JsonObject(t + ("liked_at" to (like["created_at"] ?: JsonNull)))
            }
            next = j["next_href"].str?.takeIf { it.isNotBlank() }
        }
        return out.distinctBy { it["id"].long }
    }

    /** A set lists its first tracks in full and the rest as stubs (an id only): the stubs are filled in by batch. */
    private fun setTracks(set: JsonElement): List<JsonObject> {
        val listed = set["tracks"].arr
        val missing = listed.filter { it["title"] == null }.mapNotNull { it["id"].long }.distinct()
        val fetched = HashMap<Long, JsonObject>()
        missing.chunked(BATCH).forEach { chunk ->
            api("GET", "/tracks?ids=${chunk.joinToString(",")}").arr.forEach { t -> t["id"].long?.let { tid -> trim(t)?.let { fetched[tid] = it } } }
        }
        return listed.mapNotNull { t -> if (t["title"] != null) trim(t) else t["id"].long?.let { fetched[it] } }
    }

    private fun playlist(id: String): JsonElement = try {
        api("GET", "/playlists/${numeric(id)}?representation=full")
    } catch (e: NotFound) {
        throw BridgeException("SoundCloud: playlist $id not found")
    }

    private fun set(path: String): JsonElement {
        val j = try { resolve("$SITE/$path") } catch (e: NotFound) { throw BridgeException(NOT_PUBLIC) }
        if (j["kind"].str != "playlist") throw BridgeException(NOT_PUBLIC)
        return j
    }

    private fun user(path: String): JsonElement {
        val j = try { resolve("$SITE/${path.substringBefore('/')}") } catch (e: NotFound) { throw BridgeException(NOT_PUBLIC) }
        if (j["kind"].str != "user") throw BridgeException(NOT_PUBLIC)
        return j
    }

    fun search(query: String): List<JsonObject> {
        if (query.isBlank()) return emptyList()
        val url = "$API/search/tracks".toHttpUrl().newBuilder().addQueryParameter("q", query).addQueryParameter("limit", "10").build().toString()
        return api("GET", url)["collection"].arr.mapNotNull { trim(it) }
    }

    // ---- writes

    /** A new set, private: syncing a private list must not publish it. */
    fun createPlaylist(name: String, description: String): PlaylistDto {
        val j = api("POST", "/playlists", jsonObj("playlist" to jsonObj(
            "title" to name, "description" to description, "sharing" to "private",
            "tracks" to JsonArray(emptyList()), "_resource_type" to "playlist",
        )))
        val id = j["id"].long?.toString() ?: throw BridgeException("SoundCloud: playlist not created")
        return PlaylistDto(id, j["title"].str ?: name, 0, owned = true, description = description)
    }

    /** The ids a set lists now, in order, stubs included (a track SoundCloud no longer shows still counts). */
    fun trackIds(id: String): List<Long> = playlist(id)["tracks"].arr.mapNotNull { it["id"].long }

    private fun put(id: String, fields: JsonObject) {
        api("PUT", "/playlists/${numeric(id)}", jsonObj("playlist" to fields))
    }

    private fun ids(list: List<Long>) = JsonArray(list.map { JsonPrimitive(it) })

    fun add(id: String, tracks: List<String>) {
        if (id == LIKED) return like(tracks, on = true)
        val current = trackIds(id)
        val added = tracks.map { numericTrack(it) }
        val next = current + added
        if (next.size > MAX_SET) throw BridgeException("a SoundCloud playlist holds at most $MAX_SET tracks (this one would have ${next.size})")
        put(id, jsonObj("tracks" to ids(next)))
    }

    /** By track id: every copy of each track leaves the set, as removal by id does on the other services. */
    fun remove(id: String, tracks: List<String>) {
        if (id == LIKED) return like(tracks, on = false)
        val gone = tracks.map { numericTrack(it) }.toSet()
        val current = trackIds(id)
        val next = current.filter { it !in gone }
        if (next.size == current.size) return
        put(id, jsonObj("tracks" to ids(next)))
    }

    /** The whole set in [ordered] order: the same tracks as now, or nothing is sent. */
    fun reorder(id: String, ordered: List<String>) {
        val wanted = ordered.map { numericTrack(it) }
        val current = trackIds(id)
        if (wanted.sorted() != current.sorted()) throw BridgeException("SoundCloud: the playlist changed while it was being reordered; nothing was moved")
        if (wanted == current) return
        put(id, jsonObj("tracks" to ids(wanted)))
    }

    /** The track list goes along unchanged, so an edit of the title alone cannot empty the set. */
    fun rename(id: String, name: String) {
        put(id, jsonObj("title" to name, "tracks" to ids(trackIds(id))))
    }

    fun setPublic(id: String, public: Boolean) {
        put(id, jsonObj("sharing" to if (public) "public" else "private", "tracks" to ids(trackIds(id))))
    }

    fun delete(id: String) {
        api("DELETE", "/playlists/${numeric(id)}")
    }

    /** Liking and unliking, one track at a time as the web app does, a little apart. */
    fun like(tracks: List<String>, on: Boolean) {
        val me = meId()
        tracks.forEachIndexed { i, t ->
            if (i > 0) Thread.sleep(pauseMs)
            try {
                api(if (on) "PUT" else "DELETE", "/users/$me/track_likes/${numericTrack(t)}")
            } catch (e: NotFound) {
                // Unliking a track that is no longer liked (or no longer exists) is already done.
                if (on) throw BridgeException("SoundCloud: track $t not found")
            }
        }
    }

    private fun numeric(id: String): Long {
        if (isPath(id) || id.startsWith("__")) throw BridgeException("this SoundCloud playlist is read-only here")
        return id.toLongOrNull() ?: throw BridgeException("SoundCloud: not a playlist id: $id")
    }

    private fun numericTrack(id: String): Long = id.toLongOrNull() ?: throw BridgeException("SoundCloud: not a track id: $id")

    companion object {
        const val SITE = SoundCloudBridge.SITE
        const val API = "https://api-v2.soundcloud.com"
        const val LIKED = "__liked__"
        /** What the person reads when the session is gone: no app named, the same text in every build. */
        const val EXPIRED = "the SoundCloud web session has expired: sign out of SoundCloud and sign in again"
        const val NOT_PUBLIC = "this SoundCloud playlist is private or doesn't exist"
        /** SoundCloud's own limit on a set. */
        const val MAX_SET = 500
        private const val MAX_LIKES = 10_000
        private const val BATCH = 50
        private val JSON = "application/json".toMediaType()
        @Volatile var pauseMs = 150L

        /** The fields of a track object Trackhop reads (see SoundCloudPublic.parseTrack in its engine). */
        private val TRACK_KEYS = setOf(
            "id", "kind", "title", "duration", "full_duration", "permalink_url", "policy",
            "release_date", "display_date", "created_at",
        )
        private val META_KEYS = setOf("artist", "album_title", "isrc", "explicit")

        /** A track object cut down to what Trackhop reads: a few hundred bytes instead of several kilobytes. */
        fun trim(t: JsonElement?): JsonObject? {
            val o = t as? JsonObject ?: return null
            if (o["id"].long == null || (o["kind"].str ?: "track") != "track") return null
            val out = LinkedHashMap<String, JsonElement>(o.filterKeys { it in TRACK_KEYS })
            (o["user"] as? JsonObject)?.let { u -> u["username"]?.let { out["user"] = JsonObject(mapOf("username" to it)) } }
            (o["publisher_metadata"] as? JsonObject)?.let { m -> out["publisher_metadata"] = JsonObject(m.filterKeys { it in META_KEYS }) }
            return JsonObject(out)
        }

        fun isPath(id: String) = '/' in id
        fun isLikes(path: String) = path.endsWith("/likes")

        // The same two patterns as SoundCloudPublic in Trackhop's engine, which this module cannot
        // depend on (the Bridge app copies only :bridge and :ytm): keep them in step.
        private val SCRIPT = Regex("""<script[^>]+src="(https://a-v2\.sndcdn\.com/assets/[^"]+\.js)"""")
        private val CLIENT_ID = Regex("""client_id\s*:\s*"([A-Za-z0-9]{32})"""")

        /** The scripts of the site's page, last first: the client id sits in one of the last bundles. */
        fun scriptUrls(html: String): List<String> = SCRIPT.findAll(html).map { it.groupValues[1] }.distinct().toList().asReversed()
        fun clientIdIn(js: String): String? = CLIENT_ID.find(js)?.groupValues?.get(1)

        /** [url] without its client_id parameter. */
        fun withoutClientId(url: String): String =
            url.toHttpUrlOrNull()?.newBuilder()?.removeAllQueryParameters("client_id")?.build()?.toString() ?: url
    }
}
