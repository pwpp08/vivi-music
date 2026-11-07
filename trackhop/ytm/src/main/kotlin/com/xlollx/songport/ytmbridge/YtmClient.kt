package com.xlollx.songport.ytmbridge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Talks to the same endpoints the music.youtube.com web player uses (`youtubei/v1/...`).
 *
 * This is not a public API: Google can change the response shapes at any time. Parsing therefore
 * never relies on exact paths; it looks for well-known renderer names anywhere in the tree and
 * degrades gracefully (a track without a video id is skipped, never guessed).
 *
 * Where the session, the browser engine and Google's block live is the [host]'s business: the app
 * (:bridge) or the server.
 */
class YtmClient(private val host: YtmHost) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .apply { host.interceptor?.let { addInterceptor(it) } }.build()

    // ------------------------------------------------------------------ transport

    private fun cookies(): String = host.cookies() ?: throw BridgeException("not connected")

    /** The brand account the signed-in calls are made as, when one was chosen (see [YtmHost.channel]). */
    private val channel: String? get() = host.channel?.takeIf { it.isNotBlank() }

    /** Thrown on 403/429: the service is refusing requests for now, not a broken session. */
    class Throttled(message: String) : Exception(message)

    /**
     * One innertube call. With [auth] the request carries the session (cookies, SAPISIDHASH); without
     * it the request is anonymous, exactly like a visitor browsing music.youtube.com without signing
     * in. Anonymous calls are used for catalogue searches: they do not count against the signed-in
     * account, whose quota is what triggers the frequent 403s during a long sync.
     */
    private fun call(endpoint: String, body: Map<String, Any?> = emptyMap(), query: String = "", auth: Boolean = true): JsonElement {
        val ck = if (auth) cookies() else null
        val v = visitor(ck)
        val payload = jsonObj(
            "context" to mapOf(
                "client" to buildMap<String, Any?> {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", v.clientVersion)
                    put("hl", "en")
                    put("gl", "US")
                    put("platform", "DESKTOP")
                    v.visitorData?.let { put("visitorData", it) }
                },
                // A chosen brand account (channels): the player names it in the context and in a header.
                "user" to buildMap<String, Any?> {
                    put("lockedSafetyMode", false)
                    if (ck != null) channel?.let { put("onBehalfOfUser", it) }
                },
            ),
            *body.toList().toTypedArray(),
        )
        val path = "/youtubei/v1/$endpoint?alt=json&prettyPrint=false$query"
        // The headers the player sends with its own calls. Cookies and Origin are the browser's
        // business on the WebView route; on plain HTTP they are added below.
        val apiHeaders = linkedMapOf(
            "Content-Type" to "application/json",
            "X-Origin" to YtmAuth.ORIGIN,
            "X-YouTube-Client-Name" to "67",
            "X-YouTube-Client-Version" to v.clientVersion,
            "Accept-Language" to "en-US,en;q=0.9",
        ).apply {
            v.visitorData?.let { put("X-Goog-Visitor-Id", it) }
            if (ck != null) { put("Authorization", YtmAuth.authorization(ck)); put("X-Goog-AuthUser", "0") }
            if (ck != null) channel?.let { put("X-Goog-PageId", it) }
        }
        val body = payload.toString()
        val req = Request.Builder()
            .url(YtmAuth.ORIGIN + path)
            .post(body.toRequestBody(JSON_TYPE))
            .apply { apiHeaders.forEach { (k, v2) -> header(k, v2) } }
            // Consent cookie only, as a visitor who dismissed the cookie banner.
            .header("Cookie", ck ?: ANON_COOKIES)
            .header("Origin", YtmAuth.ORIGIN)
            .header("Referer", "${YtmAuth.ORIGIN}/")
            .header("User-Agent", host.userAgent)
            .build()
        // The web interface throttles bursts (403/429): pace the calls, slow down further after each
        // refusal, and retry once after a short pause. Longer waits are Trackhop's job, which shows
        // them to the user as a countdown. A 401 is a dead session, no point retrying.
        var attempt = 0
        val lane = if (auth) SESSION_LANE else ANON_LANE
        while (true) {
            // Google's block lifts only when the requests stop: while it lasts nothing goes out, and
            // Trackhop is told exactly how long to wait.
            val blocked = Verification.remainingSeconds(host.verification)
            if (blocked > 0) throw Throttled(
                "YouTube Music is blocked by Google's abuse page for this network address; it lifts only if requests stop. Retry in $blocked s" +
                    (host.blockedHint?.let { ". $it" } ?: ""),
            )
            lane.pace()
            val t0 = System.currentTimeMillis()
            // Chrome's fingerprint through the WebView keeps Google's abuse page away; plain HTTP is
            // the fallback when the engine cannot start in this process.
            val web = host.web
            val viaWeb = web?.usable() == true
            val r = if (viaWeb) runCatching { web!!.post(path, body, apiHeaders, credentials = ck != null) }.getOrNull() else null
            val (code, text) = if (r != null) r.code to r.body else http.newCall(req).execute().use { resp -> resp.code to (resp.body?.string() ?: "") }
            run {
                Stats.record(auth, code, System.currentTimeMillis() - t0, text, viaWeb && r != null)
                // Google's abuse page: the browser was redirected to it. That address, opened by the user
                // in a visible WebView, lets them pass the verification that lifts the block.
                if ((code == 403 || code == 429) && text.contains("<title>Sorry", true)) Verification.noticed(host.verification, text)
                when {
                    code == 401 -> throw BridgeException("YouTube Music rejected the session (401): ${host.signInAgain}")
                    code == 403 || code == 429 -> {
                        lane.slowDown()
                        // A refusal can also mean a stale visitor id: refresh it before the retry.
                        invalidateVisitor(auth)
                        // A signed-in call is retried once after a pause; an anonymous one is not, its
                        // caller falls back to the session at once instead of paying the pause per search.
                        if (auth && attempt < 1) { Thread.sleep(3_000); attempt++ }
                        else {
                            val reason = Regex(""""message"\s*:\s*"([^"]{1,160})"""").find(text)?.groupValues?.get(1)
                                ?: if (text.contains("<title>Sorry", true)) "Google's abuse detection page" else text.replace(Regex("\\s+"), " ").trim().take(120).ifBlank { null }
                            val who = if (auth) "signed-in" else "anonymous"
                            val advice = if (Verification.pending(host.verification)) "Google blocked this network address; it lifts only if requests stop. Retry in ${Verification.remainingSeconds(host.verification)} s" +
                                    (host.blockedHint?.let { ". $it" } ?: "")
                                else "Vivi will wait and retry; if it persists, ${host.signInAgain}"
                            throw Throttled(
                                "YouTube Music refused the $who request ($code): too many requests" +
                                    (reason?.let { " [$it]" } ?: "") + ". " + advice,
                            )
                        }
                    }
                    code !in 200..299 -> throw BridgeException("YouTube Music $code: ${text.take(200)}")
                    else -> { lane.speedUp(); return parseJson(text) }
                }
            }
        }
    }

    private data class Visitor(val visitorData: String?, val clientVersion: String, val at: Long)

    /**
     * Visitor id and client version of the real web player, read from the music.youtube.com page
     * with the session cookies (as the player itself does). Requests that carry them look like the
     * player's own and are refused far less often. Cached for an hour.
     */
    private fun visitor(ck: String?): Visitor {
        val cached = if (ck != null) cachedVisitors[host.cacheScope] else cachedAnonVisitor
        cached?.takeIf { System.currentTimeMillis() - it.at < 3_600_000 }?.let { return it }
        val fresh = runCatching {
            val req = Request.Builder().url("${YtmAuth.ORIGIN}/").header("Cookie", ck ?: ANON_COOKIES).header("User-Agent", host.userAgent)
                .header("Accept-Language", "en-US,en;q=0.9").build()
            http.newCall(req).execute().use { resp ->
                val html = resp.body?.string() ?: ""
                val vd = Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
                val ver = Regex(""""INNERTUBE_CLIENT_VERSION"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
                Visitor(vd, ver ?: CLIENT_VERSION, System.currentTimeMillis())
            }
        }.getOrElse { Visitor(null, CLIENT_VERSION, System.currentTimeMillis()) }
            // In Europe an anonymous page is a consent screen without visitor data: ask the endpoint
            // the player itself uses to be assigned one.
            .let { if (ck == null && it.visitorData == null) it.copy(visitorData = anonymousVisitorId(it.clientVersion)) else it }
        if (ck != null) cachedVisitors[host.cacheScope] = fresh else cachedAnonVisitor = fresh
        return fresh
    }

    private fun anonymousVisitorId(clientVersion: String): String? = runCatching {
        val body = jsonObj("context" to mapOf("client" to mapOf("clientName" to "WEB_REMIX", "clientVersion" to clientVersion, "hl" to "en", "gl" to "US")))
        val req = Request.Builder().url("${YtmAuth.ORIGIN}/youtubei/v1/visitor_id?prettyPrint=false")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .header("Cookie", ANON_COOKIES).header("User-Agent", host.userAgent).header("Origin", YtmAuth.ORIGIN)
            .header("X-YouTube-Client-Name", "67").header("X-YouTube-Client-Version", clientVersion).build()
        http.newCall(req).execute().use { resp -> parseJson(resp.body?.string() ?: "")["responseContext"]["visitorData"].str }
    }.getOrNull()

    private fun invalidateVisitor(auth: Boolean) { if (auth) cachedVisitors.remove(host.cacheScope) else cachedAnonVisitor = null }

    /**
     * Adaptive pacing shared by the whole process, one lane per kind of request: anonymous searches
     * are limited per address and tolerate a brisk rhythm, signed-in calls count against the account
     * and are kept gentler. Each lane slows down after every refusal and speeds up again slowly after
     * a run of successes, never below its floor.
     */
    private class Lane(private val floorMs: Long, private val ceilingMs: Long) {
        private var lastCall = 0L
        private var paceMs = floorMs
        private var successes = 0
        val current: Long get() = paceMs

        fun pace() {
            synchronized(this) {
                val wait = lastCall + paceMs - System.currentTimeMillis()
                if (wait > 0) Thread.sleep(wait)
                lastCall = System.currentTimeMillis()
            }
        }

        fun slowDown() { synchronized(this) { paceMs = (paceMs + 500).coerceAtMost(ceilingMs); successes = 0 } }

        fun speedUp() {
            synchronized(this) { if (++successes >= 10) { successes = 0; paceMs = (paceMs - 500).coerceAtLeast(floorMs) } }
        }
    }

    /**
     * Google's "Sorry..." block on this address. The page is a captcha: once a person passes it in a
     * browser with the same cookie jar, Google sets an exemption cookie and the calls flow again.
     */
    object Verification {
        // Kept by the host too (a time and Google's page, nothing secret): the block outlives this
        // process, so the button and the pause must still be there after the app is reopened.
        @Volatile private var blockedUntil = 0L
        @Volatile private var lastSeen = 0L
        @Volatile private var level = 0

        /**
         * Google's page says it plainly: "the block will expire shortly after those requests stop".
         * So every request during the block prolongs it. On each page seen, all YouTube Music calls
         * stop for a window that doubles each time (3, 6, 12, 24 minutes) and Trackhop is told how
         * long; a quiet half hour resets the ladder.
         */
        fun noticed(store: YtmVerificationStore, html: String) {
            val now = System.currentTimeMillis()
            if (now - lastSeen > 30 * 60_000) level = 0
            level = (level + 1).coerceAtMost(4)
            lastSeen = now
            blockedUntil = now + (3 shl (level - 1)) * 60_000L
            store.save(blockedUntil, lastSeen, level, html.take(60_000))
        }

        private fun load(store: YtmVerificationStore) {
            if (lastSeen == 0L) {
                blockedUntil = store.until(); lastSeen = store.seen(); level = store.level()
            }
        }

        /** Seconds still to wait before YouTube Music may be called again; 0 when free. */
        fun remainingSeconds(store: YtmVerificationStore): Long { load(store); return ((blockedUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(0) }
        /** True while blocks were seen in the last ten minutes. */
        fun pending(store: YtmVerificationStore? = null): Boolean { store?.let { load(it) }; return System.currentTimeMillis() - lastSeen < 10 * 60_000 }
        /** Google's own page, as it came back, to show the person what Google says. */
        fun html(store: YtmVerificationStore): String? = store.html()
        fun passed(store: YtmVerificationStore) { blockedUntil = 0L; lastSeen = 0L; level = 0; store.clear() }
    }

    /** Counters for the diagnostics report: what the two routes are doing, nothing about content. */
    object Stats {
        private var calls = 0; private var ok = 0; private var refusedAnon = 0; private var refusedAuth = 0; private var viaWeb = 0
        private var okMs = 0L; private var slowestMs = 0L
        private var lastAnon: String? = null; private var lastAuth: String? = null

        @Synchronized fun record(auth: Boolean, code: Int, ms: Long, body: String, web: Boolean) {
            calls++
            if (web) viaWeb++
            if (code in 200..299) { ok++; okMs += ms; if (ms > slowestMs) slowestMs = ms }
            if (code == 403 || code == 429) {
                // The service's own words, trimmed: they tell a rate limit from a rejected request.
                val why = Regex(""""message"\s*:\s*"([^"]{1,120})"""").find(body)?.groupValues?.get(1)
                    ?: if (body.contains("<title>Sorry", true)) "Google abuse page" else body.replace(Regex("\\s+"), " ").trim().take(100).ifBlank { "(empty body)" }
                if (auth) { refusedAuth++; lastAuth = "$code $why" } else { refusedAnon++; lastAnon = "$code $why" }
            }
        }

        @Synchronized fun summary(): String =
            "calls $calls ($viaWeb via browser engine), ok $ok, avg ok ${if (ok == 0) 0 else okMs / ok} ms, slowest $slowestMs ms, " +
                "refused anonymous $refusedAnon" + (lastAnon?.let { " (last: $it)" } ?: "") +
                ", refused signed-in $refusedAuth" + (lastAuth?.let { " (last: $it)" } ?: "") +
                ", pace anonymous ${ANON_LANE.current} ms, pace signed-in ${SESSION_LANE.current} ms" +
                (if (Verification.pending()) ", Google block seen recently" else "") +
                (if (System.currentTimeMillis() - anonRefusedAt < ANON_COOLDOWN_MS) ", anonymous route cooling down" else "")
    }

    private class Cont(val token: String, val legacy: Boolean)

    /** Continuation token inside [scope], old style (`nextContinuationData`) or new style (`continuationCommand`). */
    private fun continuationOf(scope: JsonElement?): Cont? {
        scope.findFirst("nextContinuationData")?.get("continuation").str?.let { return Cont(it, true) }
        scope.findFirst("continuationCommand")?.get("token").str?.let { return Cont(it, false) }
        return null
    }

    private fun continueBrowse(c: Cont): JsonElement =
        if (c.legacy) call("browse", query = "&ctoken=${enc(c.token)}&continuation=${enc(c.token)}&type=next")
        else call("browse", mapOf("continuation" to c.token))

    // ------------------------------------------------------------------ account

    fun accountName(): String? =
        runCatching { call("account/account_menu").findFirst("accountName").runsText() }.getOrNull()

    /**
     * The channels the signed-in Google account can act as: its own and its brand accounts, as the
     * player's account switcher lists them (`account/accounts_list`). A brand account carries the
     * page id the calls are made with ([ChannelDto.id]); the account's own channel has none ("").
     * Parsed by renderer name, wherever Google puts them; an account without brand accounts lists
     * just itself.
     */
    fun channels(): List<ChannelDto> {
        val resp = call("account/accounts_list", mapOf("accountReadMask" to mapOf("returnOwner" to true, "returnBrandAccounts" to true, "returnPersonaAccounts" to true)))
        val out = LinkedHashMap<String, ChannelDto>()
        for (item in resp.findAll("accountItem")) {
            val name = item["accountName"].runsText() ?: item["accountName"]["simpleText"].str ?: continue
            val pageId = item.findAll("pageIdToken").firstNotNullOfOrNull { it["pageId"].str }.orEmpty()
            val byline = item["accountByline"].runsText() ?: item["accountByline"]["simpleText"].str
            val selected = item["isSelected"]?.toString() == "true"
            out.putIfAbsent(pageId, ChannelDto(pageId, name, byline, selected, item["hasChannel"]?.toString() != "false"))
        }
        return out.values.toList()
    }

    // ------------------------------------------------------------------ playlists

    fun playlists(): List<PlaylistDto> {
        val out = LinkedHashMap<String, PlaylistDto>()
        var resp = call("browse", mapOf("browseId" to "FEmusic_liked_playlists"))
        var pages = 0
        while (true) {
            var added = 0
            for (item in resp.findAll("musicTwoRowItemRenderer")) {
                val browseId = item["navigationEndpoint"]["browseEndpoint"]["browseId"].str ?: continue
                if (!browseId.startsWith("VL")) continue
                val id = browseId.removePrefix("VL")
                // "LM" is liked music (exposed separately by Trackhop), "SE" saved episodes.
                if (id == "LM" || id == "SE") continue
                val name = item["title"].runsText() ?: continue
                val count = item["subtitle"].runsText()?.let { parseCount(it) } ?: -1
                if (out.putIfAbsent(id, PlaylistDto(id, name, count)) == null) added++
            }
            val scope = resp.findFirst("gridRenderer") ?: resp.findFirst("gridContinuation") ?: resp.findFirst("onResponseReceivedActions")
            val next = continuationOf(scope) ?: break
            if (added == 0 || ++pages > 50) break
            resp = continueBrowse(next)
        }
        return out.values.toList()
    }

    fun playlistInfo(playlistId: String): PlaylistDto {
        val resp = call("browse", mapOf("browseId" to browseIdOf(playlistId)))
        val title = resp.findFirst("musicResponsiveHeaderRenderer")["title"].runsText()
            ?: resp.findFirst("musicDetailHeaderRenderer")["title"].runsText()
            ?: resp.findFirst("musicEditablePlaylistDetailHeaderRenderer").findFirst("title").runsText()
            ?: playlistId
        return PlaylistDto(playlistId, title)
    }

    // ------------------------------------------------------------------ tracks

    fun tracks(playlistId: String): List<TrackDto> {
        val out = LinkedHashMap<String, TrackDto>()
        var resp = call("browse", mapOf("browseId" to browseIdOf(playlistId)))
        // The size the page's header states ("2,084 songs"), to tell a listing cut short from a whole one.
        val declared = declaredCount(resp)
        var pages = 0
        while (true) {
            var added = 0
            val shelf = resp.findFirst("musicPlaylistShelfRenderer")
                ?: resp.findFirst("musicPlaylistShelfContinuation")
                ?: resp.findFirst("onResponseReceivedActions")
                ?: resp
            for (item in shelf.findAll("musicResponsiveListItemRenderer")) {
                val t = parseItem(item) ?: continue
                // The same video can appear twice in a playlist: key by set id when we have one.
                val key = t.setVideoId ?: t.id
                if (out.putIfAbsent(key, t) == null) added++
            }
            val next = continuationOf(shelf) ?: break
            if (added == 0 || ++pages > 300) break
            resp = continueBrowse(next)
        }
        // Under half of what the header states: a page left out or sent in another layout. Returned as
        // is, it would read as an emptied playlist, and a sync would write everything to it again.
        if (declared >= SHORT_LISTING_MIN && out.size * 2 < declared) {
            throw BridgeException("YouTube Music listed ${out.size} of the $declared songs of this playlist: try again in a while")
        }
        return out.values.toList()
    }

    /** The song count in a playlist page's header (its second subtitle, "2,084 songs • 120+ hours"), or -1. */
    private fun declaredCount(resp: JsonElement): Int {
        val header = resp.findFirst("musicResponsiveHeaderRenderer") ?: resp.findFirst("musicDetailHeaderRenderer") ?: return -1
        val text = header["secondSubtitle"].runsText() ?: return -1
        return parseCount(text)
    }

    /**
     * Catalogue search. Runs anonymously first: a search does not need the account, and the account is
     * exactly what YouTube throttles after a few hundred rapid searches (the "403 every few songs" of
     * a long sync). Only when the anonymous route is refused does it fall back to the signed-in one,
     * so a sync keeps going in the worst case instead of stopping.
     */
    /**
     * Catalogue search with the "Songs" filter, or with the "Videos" filter when [videos] is set: a
     * track missing from the music catalogue often exists as a YouTube video, which a YouTube Music
     * playlist accepts all the same. Callers ask for videos only after songs came back empty-handed.
     */
    fun search(query: String, videos: Boolean = false): List<TrackDto> {
        if (query.isBlank()) return emptyList()
        val body = mapOf("query" to query, "params" to if (videos) VIDEOS_FILTER else SONGS_FILTER)
        // Once the anonymous route is refused it usually stays refused for a while: searches go
        // through the session for a few minutes rather than paying a refusal on every one of them.
        val anonymousCold = System.currentTimeMillis() - anonRefusedAt < ANON_COOLDOWN_MS
        val signedIn = host.cookies() != null
        if (anonymousCold && signedIn) return results(call("search", body, auth = true))
        val anonymous = try {
            results(call("search", body, auth = false))
        } catch (e: Throttled) {
            if (!signedIn) throw e
            anonRefusedAt = System.currentTimeMillis()
            return results(call("search", body, auth = true))
        }
        // YouTube can answer a visitor's filtered search with "No results" for any song (the catalogue
        // hidden from visitors: issue #34's "No results for a well-known track"). An empty anonymous
        // answer is asked again with the session, and the next searches go through it for a while.
        if (anonymous.isNotEmpty() || !signedIn) return anonymous
        val withSession = results(call("search", body, auth = true))
        if (withSession.isNotEmpty()) {
            anonRefusedAt = System.currentTimeMillis()
            host.log("ytm: search: no results as a visitor, found with the session; searches use it for a while")
        }
        return withSession
    }

    private fun results(resp: JsonElement): List<TrackDto> {
        val seen = HashSet<String>()
        return resp.findAll("musicResponsiveListItemRenderer")
            .mapNotNull { parseItem(it) }
            .filter { seen.add(it.id) }
            .take(20)
    }

    // ------------------------------------------------------------------ library: albums and artists
    // Paths as ytmusicapi reads them (get_library_albums, get_library_subscriptions, rate_playlist,
    // subscribe_artists): the album's playlist id (OLAK5uy_...) is what "like" wants, the browse id
    // (MPREb...) is what identifies it; an artist is its channel (UC...).

    /** Saved albums: the "Albums" tab of the library. */
    fun libraryAlbums(): List<TrackDto> {
        val out = LinkedHashMap<String, TrackDto>()
        var resp = call("browse", mapOf("browseId" to "FEmusic_liked_albums"))
        var pages = 0
        while (true) {
            var added = 0
            for (item in resp.findAll("musicTwoRowItemRenderer")) {
                val t = albumRow(item) ?: continue
                if (out.putIfAbsent(t.id, t) == null) added++
            }
            val scope = resp.findFirst("gridRenderer") ?: resp.findFirst("gridContinuation") ?: resp.findFirst("onResponseReceivedActions")
            val next = continuationOf(scope) ?: break
            if (added == 0 || ++pages > 50) break
            resp = continueBrowse(next)
        }
        return out.values.toList()
    }

    /** Subscribed artists: the "Artists" tab of the library, subscriptions only. */
    fun librarySubscriptions(): List<TrackDto> {
        val out = LinkedHashMap<String, TrackDto>()
        var resp = call("browse", mapOf("browseId" to "FEmusic_library_corpus_artists"))
        var pages = 0
        while (true) {
            var added = 0
            for (item in resp.findAll("musicResponsiveListItemRenderer")) {
                val t = artistRow(item) ?: continue
                if (out.putIfAbsent(t.id, t) == null) added++
            }
            val scope = resp.findFirst("musicShelfRenderer") ?: resp.findFirst("musicShelfContinuation") ?: resp.findFirst("onResponseReceivedActions")
            val next = continuationOf(scope) ?: break
            if (added == 0 || ++pages > 50) break
            resp = continueBrowse(next)
        }
        return out.values.toList()
    }

    /** The listening history (ytmusicapi's get_history): newest first, a video once. */
    fun history(): List<TrackDto> {
        val resp = call("browse", mapOf("browseId" to "FEmusic_history"))
        val seen = HashSet<String>()
        return resp.findAll("musicResponsiveListItemRenderer").mapNotNull { parseItem(it) }.filter { seen.add(it.id) }
    }

    /** Catalogue search for albums or artists (the "Albums" and "Artists" filters). */
    fun searchKind(query: String, kind: String): List<TrackDto> {
        if (query.isBlank()) return emptyList()
        val body = mapOf("query" to query, "params" to if (kind == "artist") ARTISTS_FILTER else ALBUMS_FILTER)
        val resp = try { call("search", body, auth = false) } catch (e: Throttled) { call("search", body, auth = true) }
        val seen = HashSet<String>()
        return resp.findAll("musicResponsiveListItemRenderer")
            .mapNotNull { if (kind == "artist") artistRow(it) else albumRow(it) }
            .filter { seen.add(it.id) }
            .take(10)
    }

    /** The album's playlist id, from its page when the row did not carry it. */
    private fun albumPlaylistId(browseId: String): String? {
        val page = call("browse", mapOf("browseId" to browseId))
        return page.findFirst("watchPlaylistEndpoint")?.get("playlistId").str ?: page.findFirst("playlistId").str
    }

    /** Saves or removes albums: a "like" on the album's playlist. [uris] carry playlist ids when known. */
    fun rateAlbums(browseIds: List<String>, uris: List<String?>, like: Boolean) {
        browseIds.forEachIndexed { i, id ->
            val known = uris.getOrNull(i)?.takeIf { !it.isNullOrBlank() && !it.startsWith("MPREb") }
            // Only an album's browse id (MPREb...) or its playlist (OLAK5uy...) can be saved: a song's
            // video id here made Google answer "400: invalid argument".
            if (known == null && !id.startsWith("MPREb")) throw BridgeException("$id is not an album")
            val pl = known ?: albumPlaylistId(id)
                ?: throw BridgeException("album $id has no playlist to like")
            call(if (like) "like/like" else "like/removelike", mapOf("target" to mapOf("playlistId" to pl)))
        }
    }

    fun subscribeArtists(channelIds: List<String>, on: Boolean) {
        if (channelIds.isEmpty()) return
        channelIds.chunked(20).forEach { call(if (on) "subscription/subscribe" else "subscription/unsubscribe", mapOf("channelIds" to it)) }
    }

    /** An album row (library grid or search list): browse id MPREb..., title, artists, year, playlist id when present. */
    private fun albumRow(item: JsonElement): TrackDto? {
        val browseId = item["title"]["runs"][0]["navigationEndpoint"]["browseEndpoint"]["browseId"].str
            ?: item["navigationEndpoint"]["browseEndpoint"]["browseId"].str ?: return null
        if (!browseId.startsWith("MPREb")) return null
        val cols = item["flexColumns"].arr.map { it["musicResponsiveListItemFlexColumnRenderer"]["text"] }
        val title = item["title"].runsText() ?: cols.getOrNull(0).runsText() ?: return null
        val runs = item["subtitle"]["runs"].arr.ifEmpty { cols.getOrNull(1)["runs"].arr }
        val artists = runs.filter { r -> r["navigationEndpoint"]["browseEndpoint"]["browseId"].str?.let { it.startsWith("UC") || it.startsWith("MPLA") } == true }
            .mapNotNull { it["text"].str }
        val segments = runs.mapNotNull { it["text"].str }.joinToString("").split('•').map { it.trim() }
        val year = segments.lastOrNull { Regex("""^\d{4}$""").matches(it) }?.toIntOrNull()
        val fallbackArtists = segments.getOrNull(1)?.takeIf { artists.isEmpty() && !Regex("""^\d{4}$""").matches(it) }
            ?.split(Regex(""",\s+|\s+&\s+"""))?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val playlistId = item.findFirst("watchPlaylistEndpoint")?.get("playlistId").str
        return TrackDto(browseId, title, artists.ifEmpty { fallbackArtists }, uri = playlistId, kind = "album", year = year)
    }

    private fun artistRow(item: JsonElement): TrackDto? {
        val channel = item["navigationEndpoint"]["browseEndpoint"]["browseId"].str ?: return null
        if (!channel.startsWith("UC")) return null
        val cols = item["flexColumns"].arr.map { it["musicResponsiveListItemFlexColumnRenderer"]["text"] }
        val name = cols.getOrNull(0).runsText() ?: item["title"].runsText() ?: return null
        return TrackDto(channel, name, listOf(name), kind = "artist")
    }

    // ------------------------------------------------------------------ writes

    fun createPlaylist(name: String, description: String): PlaylistDto {
        val resp = call("playlist/create", mapOf("title" to name, "description" to description, "privacyStatus" to "PRIVATE"))
        val id = resp["playlistId"].str ?: throw BridgeException("playlist not created: ${resp.toString().take(200)}")
        return PlaylistDto(id, name, 0)
    }

    fun renamePlaylist(playlistId: String, name: String) {
        val resp = call(
            "browse/edit_playlist",
            mapOf("playlistId" to playlistId.removePrefix("VL"), "actions" to listOf(mapOf("action" to "ACTION_SET_PLAYLIST_NAME", "playlistName" to name))),
        )
        checkStatus(resp)
    }

    fun deletePlaylist(playlistId: String) {
        call("playlist/delete", mapOf("playlistId" to playlistId.removePrefix("VL")))
    }

    /** "Put this entry right before that one", by set ids, as the site's drag and drop sends it; fifty per call. */
    fun moveBefore(playlistId: String, steps: List<Pair<String, String>>) {
        steps.chunked(50).forEach { chunk ->
            val resp = call(
                "browse/edit_playlist",
                mapOf("playlistId" to playlistId.removePrefix("VL"), "actions" to chunk.map { (item, successor) ->
                    mapOf("action" to "ACTION_MOVE_VIDEO_BEFORE", "setVideoId" to item, "movedSetVideoIdSuccessor" to successor)
                }),
            )
            checkStatus(resp)
        }
    }

    /** The playlist's privacy: PUBLIC or PRIVATE (the same edit call the site's "Edit playlist" makes). */
    fun setPrivacy(playlistId: String, public: Boolean) {
        val resp = call(
            "browse/edit_playlist",
            mapOf("playlistId" to playlistId.removePrefix("VL"), "actions" to listOf(mapOf("action" to "ACTION_SET_PLAYLIST_PRIVACY", "playlistPrivacy" to if (public) "PUBLIC" else "PRIVATE"))),
        )
        checkStatus(resp)
    }

    /**
     * Adds to a playlist, or likes for [LIKED]. Returns the liked ids whose answer says they went
     * somewhere other than Liked music (see [likeGoesElsewhere]): liked, but never listed there.
     */
    fun addTracks(playlistId: String, videoIds: List<String>): Set<String> {
        if (playlistId == LIKED) {
            val elsewhere = LinkedHashSet<String>()
            videoIds.forEach { if (likeGoesElsewhere(call("like/like", mapOf("target" to mapOf("videoId" to it))))) elsewhere += it }
            return elsewhere
        }
        videoIds.chunked(50).forEach { chunk ->
            val resp = call(
                "browse/edit_playlist",
                mapOf("playlistId" to playlistId.removePrefix("VL"), "actions" to chunk.map { mapOf("action" to "ACTION_ADD_VIDEO", "addedVideoId" to it) }),
            )
            checkStatus(resp)
        }
        return emptySet()
    }

    /**
     * Whether the answer to a like says the item went to Liked videos rather than Liked music.
     *
     * As far as has been seen, like/like answers a song, a video and an already liked item alike: a
     * success with no status and nothing about where the item went, which is why a sync towards
     * Liked songs cannot tell, at the moment of liking, that Liked music will not list it (the
     * engine finds out on the next run). Should an answer carry a message (a toast) that names Liked
     * videos and not music, that is used. The fields and messages of each kind of answer are logged
     * once (never an id), so a diagnostics report shows what YouTube says.
     */
    internal fun likeGoesElsewhere(resp: JsonElement): Boolean {
        val texts = resp["actions"].findAll("text").flatMap { t -> listOfNotNull(t.str, t.runsText()) }.distinct()
        val fields = (resp as? JsonObject)?.keys?.filter { it != "responseContext" }?.sorted().orEmpty()
        val shape = fields.joinToString(",") + "|" + texts.joinToString("/")
        if (likeShapes.size < 10 && likeShapes.add(shape)) {
            host.log("like answered with ${if (fields.isEmpty()) "no fields besides responseContext" else "fields " + fields.joinToString(", ")}" +
                if (texts.isEmpty()) "" else "; says: " + texts.joinToString(" / ").take(160))
        }
        val said = texts.joinToString(" ").lowercase()
        return said.contains("liked videos") && !said.contains("music")
    }

    fun removeTracks(playlistId: String, items: List<RemoveItem>) {
        if (playlistId == LIKED) {
            items.forEach { call("like/removelike", mapOf("target" to mapOf("videoId" to it.videoId))) }
            return
        }
        // Removal needs the set id of each entry; look it up when Trackhop only knows the video id.
        val missing = items.filter { it.setVideoId == null }
        val resolved = if (missing.isEmpty()) items else {
            val current = tracks(playlistId).groupBy { it.id }
            items.map { it.copy(setVideoId = it.setVideoId ?: current[it.videoId]?.firstOrNull()?.setVideoId) }
        }
        resolved.filter { it.setVideoId != null }.chunked(50).forEach { chunk ->
            val resp = call(
                "browse/edit_playlist",
                mapOf(
                    "playlistId" to playlistId.removePrefix("VL"),
                    "actions" to chunk.map { mapOf("action" to "ACTION_REMOVE_VIDEO", "removedVideoId" to it.videoId, "setVideoId" to it.setVideoId) },
                ),
            )
            checkStatus(resp)
        }
    }

    private fun checkStatus(resp: JsonElement) {
        val status = resp["status"].str
            ?: throw BridgeException("YouTube Music did not confirm the change: ${resp.toString().take(240)}")
        if (status != "STATUS_SUCCEEDED") throw BridgeException("YouTube Music answered $status: ${resp.toString().take(200)}")
    }

    // ------------------------------------------------------------------ parsing

    private fun parseItem(r: JsonElement): TrackDto? {
        val videoId = r["playlistItemData"]["videoId"].str
            ?: r.findFirst("watchEndpoint")?.get("videoId").str
            ?: return null
        val setId = r["playlistItemData"]["playlistSetVideoId"].str
        val cols = r["flexColumns"].arr.map { it["musicResponsiveListItemFlexColumnRenderer"]["text"] }
        val title = cols.getOrNull(0).runsText() ?: return null
        val runs1 = cols.getOrNull(1)["runs"].arr
        var artists = runs1.filter { run ->
            val b = run["navigationEndpoint"]["browseEndpoint"]["browseId"].str
            b != null && (b.startsWith("UC") || b.startsWith("MPLA"))
        }.mapNotNull { it["text"].str }
        // The subtitle reads "Artist, Artist • Album • 3:35" for songs and "Channel • 1.2M views • 3:35"
        // for videos. Anonymous results often carry no links, so the segments between the bullets
        // are the fallback: the first is the artists, the second the album unless it is a count.
        val segments = runs1.mapNotNull { it["text"].str }.joinToString("").split('•').map { it.trim() }.filter { it.isNotEmpty() }
        if (artists.isEmpty()) {
            artists = segments.firstOrNull()?.takeIf { !DURATION.matches(it) && !COUNT.matches(it) }
                ?.split(Regex(""",\s+|\s+&\s+"""))?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        }
        val album = (cols.getOrNull(2)["runs"].arr + runs1)
            .firstOrNull { it["navigationEndpoint"]["browseEndpoint"]["browseId"].str?.startsWith("MPREb") == true }["text"].str
            ?: segments.getOrNull(1)?.takeIf { !DURATION.matches(it) && !COUNT.matches(it) }
            ?: ""
        val durationText = r["fixedColumns"].arr.firstOrNull()["musicResponsiveListItemFixedColumnRenderer"]["text"].runsText()
            ?: runs1.mapNotNull { it["text"].str?.trim() }.firstOrNull { DURATION.matches(it) }
        // A greyed-out entry (pulled, blocked in this country) keeps its row but gets this display policy.
        val available = r["musicItemRendererDisplayPolicy"].str != "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT"
        return TrackDto(videoId, title, artists, album, parseDuration(durationText), setId, available = available)
    }

    private fun parseDuration(text: String?): Long {
        if (text == null || !DURATION.matches(text.trim())) return 0
        val parts = text.trim().split(':').map { it.toLongOrNull() ?: 0 }
        return parts.fold(0L) { acc, p -> acc * 60 + p } * 1000
    }

    private fun parseCount(subtitle: String): Int =
        Regex("(\\d[\\d,.]*)\\s*(song|track|brani|brano|titoli|video|canzoni)", RegexOption.IGNORE_CASE)
            .find(subtitle)?.groupValues?.get(1)?.filter { it.isDigit() }?.toIntOrNull() ?: -1

    private fun browseIdOf(playlistId: String): String = when {
        playlistId == LIKED -> "VLLM"
        playlistId.startsWith("VL") -> playlistId
        else -> "VL$playlistId"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        /** From this many songs stated in a playlist's header, a listing under half of it is refused. */
        private const val SHORT_LISTING_MIN = 20
        /** Trackhop's id for "Liked songs"; maps to the LM playlist here. */
        const val LIKED = "__liked__"
        /** Kinds of like answers already logged, for this process. */
        private val likeShapes: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        /** Signed-in calls: what a person clicking around the player would produce. */
        private val SESSION_LANE = Lane(1_500, 6_000)
        /** Anonymous searches: several a second are fine, Trackhop also runs a few in parallel. */
        private val ANON_LANE = Lane(1_000, 6_000)
        @Volatile private var anonRefusedAt = 0L
        private const val ANON_COOLDOWN_MS = 5 * 60_000L
        /** The signed-in player page of each session (by [YtmHost.cacheScope]): never another account's. */
        private val cachedVisitors = java.util.concurrent.ConcurrentHashMap<String, Visitor>()
        @Volatile private var cachedAnonVisitor: Visitor? = null
        /** Cookie jar of an anonymous visitor: only the consent choice, no account. */
        private const val ANON_COOKIES = "SOCS=CAI"
        const val CLIENT_VERSION = "1.20250901.01.00"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        /** Search filter "Songs". */
        /** "1.2M views", "12 Mln di visualizzazioni", "3,4 M de vues": a play count, not an album. */
        private val COUNT = Regex("""^\d[\d.,\s]*\s*(K|M|B|Mln|Mrd|mila|k|m)?\.?\s*(di\s+)?(views?|plays?|visualizzazioni|riproduzioni|vues?|Aufrufe|Wiedergaben|visualizaciones)?$""", RegexOption.IGNORE_CASE)
        private const val SONGS_FILTER = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
        // The same parameters with the type nibble for albums (IY) and artists (Ig), as ytmusicapi builds them.
        private const val ALBUMS_FILTER = "EgWKAQIYAWoMEA4QChADEAQQCRAF"
        private const val ARTISTS_FILTER = "EgWKAQIgAWoMEA4QChADEAQQCRAF"
        // Same parameters as the songs filter with the type nibble set to videos (ytmusicapi: I -> Q).
        private const val VIDEOS_FILTER = "EgWKAQIQAWoMEA4QChADEAQQCRAF"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val DURATION = Regex("^\\d{1,2}(:\\d{2}){1,2}$")
    }
}
