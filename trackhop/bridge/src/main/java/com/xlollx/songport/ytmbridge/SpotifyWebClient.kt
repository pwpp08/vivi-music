package com.xlollx.songport.ytmbridge

import com.xlollx.songport.ytmbridge.BridgeNet.withHook
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Spotify through the web player's own backend, the way open.spotify.com itself talks to Spotify.
 *
 * Since December 2025 a first-party token (the web player's, the desktop app's) is refused by the
 * public Web API with a 429 on every call, so the web route cannot go through api.spotify.com any
 * more. The player never did: it reads through the GraphQL gateway `api-partner.spotify.com/pathfinder`
 * (persisted queries only: an operation name, its variables and a SHA-256 naming a query Spotify
 * holds) and writes playlists through `spclient.wg.spotify.com/playlist/v2`. Both want two tokens:
 * the bearer that names the user ([Host.bearer]) and a `client-token` that names the
 * application, granted by clienttoken.spotify.com to the player's client id and version.
 *
 * The persisted-query hashes rot with every player release. They are harvested from the player's
 * own JavaScript bundles (`"name","query","<sha256>"` literals), cached, and harvested again when
 * Spotify answers PersistedQueryNotFound or the bundle changes; the hidden WebView that captures
 * the bearer also reports the hashes and client token it sees the player use.
 *
 * Nothing here is an official interface: it is what the browser does, done from the phone.
 *
 * No Android here: the server build (server/build.gradle) compiles this file as it is, with its own
 * [Host] (pasted cookies, the bearer read from the player page). The app's is [SpotifyBridge.host].
 */
class SpotifyWebClient(private val host: Host) {

    /** Where the client runs ([WebHost]), and the bearer that names the user. */
    interface Host : WebHost {
        /** A valid access token for the player's backend. Blocking. */
        fun bearer(): String
        /** The bearer was refused (401): the next [bearer] fetches a fresh one. */
        fun bearerRefused()
    }

    class Page(val html: String) {
        private val sessionJson: JsonObject? = SESSION_SCRIPT.find(html)?.groupValues?.get(1)?.let { runCatching { parseJson(it) as? JsonObject }.getOrNull() }
        private val serverConfig: JsonObject? = SERVER_CONFIG.find(html)?.groupValues?.get(1)?.let {
            // The MIME decoder skips what is not base64, as Android's lenient decoder did.
            runCatching { parseJson(String(java.util.Base64.getMimeDecoder().decode(it.trim()))) as? JsonObject }.getOrNull()
        }
        val accessToken: String? get() = sessionJson["accessToken"].str
        val isAnonymous: Boolean get() = sessionJson["isAnonymous"]?.toString() == "true"
        val expiresAt: Long get() = sessionJson["accessTokenExpirationTimestampMs"].long ?: (System.currentTimeMillis() + 50 * 60_000)
        /** The web player's client id: in the session block, or anywhere in the page, or the one it has had for years. */
        val clientId: String get() = sessionJson["clientId"].str
            ?: serverConfig["clientId"].str
            ?: Regex(""""clientId"\s*:\s*"([0-9a-f]{32})"""").find(html)?.groupValues?.get(1)
            ?: WEB_PLAYER_CLIENT_ID
        val clientVersion: String? get() = serverConfig["clientVersion"].str
            ?: Regex("""\b1\.\d+\.\d+\.\d+\.g[0-9a-f]{6,}\b""").find(html)?.value
        /** The main bundle of the player: it lists the other chunks. */
        val jsPack: String? get() = Regex("""https://open\.spotifycdn\.com/cdn/build/web-player/web-player\.[^"']+\.js""").find(html)?.value
    }

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).withHook().build()

    private fun cookies(): String = host.cookies() ?: throw BridgeException("not connected")

    // ---- the player page: bearer, client id and version, bundle

    /** The player page for this session, parsed. Cached for a few minutes: the bearer inside lasts an hour. */
    fun page(): Page {
        cachedPages[host.cacheScope]?.let { (at, p) -> if (System.currentTimeMillis() - at < 5 * 60_000) return p }
        val req = Request.Builder().url("https://open.spotify.com/")
            .header("User-Agent", host.userAgent).header("Cookie", cookies())
            .header("Accept", "text/html,application/xhtml+xml").header("Accept-Language", "en").build()
        val html = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw BridgeException("open.spotify.com answered ${r.code}")
            r.body?.string() ?: ""
        }
        val p = Page(html)
        cachedPages[host.cacheScope] = System.currentTimeMillis() to p
        return p
    }

    // ---- client token: names the application; two weeks, granted to the player's id and version

    /** Null when Spotify would not grant one: the call goes without, and the answer says whether it was needed. */
    private fun clientToken(): String? {
        host.get("clientToken")?.let { t ->
            val exp = host.get("clientTokenExp")?.toLongOrNull() ?: 0
            if (exp > System.currentTimeMillis()) return t
        }
        if (System.currentTimeMillis() - clientTokenFailedAt < 60_000) return null
        return try { grantClientToken() } catch (e: Exception) { clientTokenFailedAt = System.currentTimeMillis(); lastClientTokenError = e.message; null }
    }

    private fun grantClientToken(): String {
        val p = page()
        val clientId = p.clientId
        val version = p.clientVersion ?: host.get("appVersion") ?: DEFAULT_APP_VERSION
        val deviceId = WebHost.cookieValue(cookies(), "sp_t") ?: host.get("deviceId") ?: java.util.UUID.randomUUID().toString().replace("-", "").also { host.put("deviceId", it) }
        val body = jsonObj(
            "client_data" to jsonObj(
                "client_version" to version,
                "client_id" to clientId,
                "js_sdk_data" to jsonObj(
                    "device_brand" to "unknown", "device_model" to "unknown", "os" to "windows", "os_version" to "NT 10.0",
                    "device_id" to deviceId, "device_type" to "computer",
                ),
            ),
        )
        val req = Request.Builder().url("https://clienttoken.spotify.com/v1/clienttoken")
            .post(body.toString().toRequestBody(JSON))
            .header("Accept", "application/json").header("Content-Type", "application/json")
            .header("User-Agent", host.userAgent).header("Origin", "https://open.spotify.com").header("Referer", "https://open.spotify.com/").build()
        var status = 0
        var raw = ""
        val j = http.newCall(req).execute().use { r -> status = r.code; raw = r.body?.string() ?: ""; runCatching { parseJson(raw) }.getOrDefault(JsonNull) }
        val token = j["granted_token"]["token"].str
        if (token == null) {
            // What came back instead, for the diagnostics log: the status, the answer's type and the
            // start of the body with anything token-like removed; the client version tells a stale page.
            BridgeNet.log("spotify", "client token refused: HTTP $status, ${j["response_type"].str ?: "no response_type"}, client version $version, " +
                "body ${raw.replace(Regex("[A-Za-z0-9_\\-]{40,}"), "…").take(200).ifBlank { "empty" }}")
            throw BridgeException("Spotify did not grant a client token (${j["response_type"].str ?: "HTTP $status"})")
        }
        val ttl = (j["granted_token"]["refresh_after_seconds"].long ?: j["granted_token"]["expires_after_seconds"].long ?: 1_209_600L) * 1000
        rememberClientToken(token, System.currentTimeMillis() + ttl - 60_000)
        host.put("appVersion", version)
        return token
    }

    /** A client token seen on the player's own requests (from the WebView hooks) is as good as a granted one. */
    fun rememberClientToken(token: String, expiresAt: Long) {
        host.put("clientToken", token)
        host.put("clientTokenExp", expiresAt.toString())
    }

    private fun appVersion(): String = page().clientVersion ?: host.get("appVersion") ?: DEFAULT_APP_VERSION

    // ---- persisted query hashes, harvested from the player's bundles

    private val NEEDED = listOf(
        "libraryV3", "fetchPlaylist", "fetchLibraryTracks", "searchTracks", "searchDesktop", "getTrack", "profileAttributes",
        "addToPlaylist", "removeFromPlaylist", "addToLibrary", "removeFromLibrary",
    )

    /**
     * A public registry that re-reads the player's bundle every four hours and keeps the hashes of
     * the main queries, verified (spotify-gql-registry on GitHub). Read at most every six hours; its
     * entries win over what this client read from the bundle itself, since a bundle can carry more
     * than one literal for a name and the registry's are the ones the player uses today.
     */
    private fun registry(): Map<String, String> {
        val at = host.get("registryAt")?.toLongOrNull() ?: 0
        val cached = host.get("registry")?.let { runCatching { parseJson(it) as? JsonObject }.getOrNull() }
            ?.entries?.associate { (k, v) -> k to (v.str ?: "") }
        // Ours is refreshed every hour and has the mutations too: read every hour, the public one fills gaps.
        if (cached != null && System.currentTimeMillis() - at < 3600_000) return SHIPPED_HASHES + cached
        fun read(url: String): Map<String, String>? {
            val text = runCatching {
                http.newCall(Request.Builder().url(url).header("User-Agent", host.userAgent).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            }.getOrNull() ?: return null
            val ops = runCatching { parseJson(text)["operations"] as? JsonObject }.getOrNull() ?: return null
            return ops.entries.mapNotNull { (name, v) -> v["hash"].str?.takeIf { it.length == 64 }?.let { name to it } }.toMap()
        }
        val public = read(REGISTRY_URL)
        val ours = read(OWN_REGISTRY_URL)
        if (public == null && ours == null) return SHIPPED_HASHES + (cached ?: emptyMap())
        val out = (public ?: emptyMap()) + (ours ?: emptyMap())
        host.put("registry", JsonObject(out.mapValues { JsonPrimitive(it.value) }).toString())
        host.put("registryAt", System.currentTimeMillis().toString())
        return SHIPPED_HASHES + out
    }

    private fun hashes(): MutableMap<String, String> {
        val raw = host.get("hashes") ?: return HashMap()
        val j = runCatching { parseJson(raw) as? JsonObject }.getOrNull() ?: return HashMap()
        return j.entries.associate { (k, v) -> k to (v.str ?: "") }.filterValues { it.length == 64 }.toMutableMap()
    }

    /**
     * [harvested]: the bundle was really read. Only then does the half-hour pause before the next
     * read start: saving the registry's or the player's hashes used to start it too, and a mutation
     * the registry lacks (addToLibrary, liked songs) then went unsearched for half an hour (#34).
     */
    private fun saveHashes(map: Map<String, String>, pack: String?, harvested: Boolean = false) {
        host.put("hashes", JsonObject(map.mapValues { JsonPrimitive(it.value) }).toString())
        host.put("hashesPack", pack)
        if (harvested) host.put("hashesAt", System.currentTimeMillis().toString())
    }

    /** A hash the WebView saw the player use: kept, and preferred to whatever the bundle said. */
    fun rememberHash(operation: String, hash: String) {
        if (hash.length != 64) return
        val map = hashes()
        if (map[operation] == hash) return
        map[operation] = hash
        saveHashes(map, host.get("hashesPack"))
    }

    private fun refusedHashes(): Map<String, String> =
        host.get("refusedHashes")?.let { runCatching { parseJson(it) as? JsonObject }.getOrNull() }
            ?.entries?.associate { (k, v) -> k to (v.str ?: "") } ?: emptyMap()

    private fun hashFor(operation: String, forceHarvest: Boolean = false): String {
        val map = hashes()
        // The registry answers for the main queries without touching the bundle at all.
        // A hash Spotify refused (PersistedQueryNotFound) is never taken from a list again: the
        // one read from the bundle stays until the lists catch up.
        val refused = refusedHashes()
        val known = registry().filter { (k, v) -> refused[k] != v }
        if (!forceHarvest && known[operation] != null) {
            if (map[operation] != known[operation]) { map[operation] = known[operation]!!; saveHashes(map, host.get("hashesPack")) }
            return known[operation]!!
        }
        // One harvest per half hour at most: a missing operation is not found by asking again.
        val recently = (host.get("hashesAt")?.toLongOrNull() ?: 0) > System.currentTimeMillis() - 30 * 60_000
        val missingNow = map[operation] == null && System.currentTimeMillis() - (missedAt[operation] ?: 0) > 5 * 60_000
        if (forceHarvest || (map[operation] == null && !recently) || missingNow) {
            if (map[operation] == null) missedAt[operation] = System.currentTimeMillis()
            val before = if (forceHarvest) (known[operation] ?: map[operation]) else null
            harvest(map, runCatching { page().jsPack }.getOrNull(), operation, fresh = forceHarvest)
            if (before != null && map[operation] != null && map[operation] != before) {
                host.put("refusedHashes", JsonObject((refused + (operation to before)).mapValues { JsonPrimitive(it.value) }).toString())
            }
        }
        return map[operation] ?: throw BridgeException("Spotify: no query hash for $operation (the player's code changed; try again in a while)")
    }

    /**
     * Downloads the player's main bundle and, chunk by chunk, the others it lists, until every
     * operation this client needs has a hash. Chunk names and hashes are two `{id:"..."}` maps in
     * the main bundle: joined by id they give `<name>.<hash>.js` under the same CDN folder.
     */
    private fun harvest(map: MutableMap<String, String>, packUrl: String?, wanted: String, fresh: Boolean = false) {
        // Bundle literals fill what the registry does not cover (the mutations); the first literal
        // for a name is kept, as the registry's own reader does. [fresh]: Spotify refused the hash
        // we had (PersistedQueryNotFound), so what the bundle says now wins over every list.
        val refused = refusedHashes()
        val known = registry().filter { (k, v) -> refused[k] != v }
        map.putAll(known)
        if (fresh) map.remove(wanted)
        val seen = HashSet<String>()
        fun scan(text: String) {
            HASH.findAll(text).forEach { m ->
                val name = m.groupValues[1]
                if (!seen.add(name)) return@forEach
                if (fresh) map[name] = m.groupValues[3] else if (name !in known) map.putIfAbsent(name, m.groupValues[3])
            }
        }
        val pack = packUrl ?: run { if (wanted in map) { saveHashes(map, null); return }; throw BridgeException("Spotify: the player page has no bundle to read the queries from") }
        fun fetch(url: String): String = http.newCall(Request.Builder().url(url).header("User-Agent", host.userAgent).build())
            .execute().use { r -> if (r.isSuccessful) r.body?.string() ?: "" else "" }
        val main = fetch(pack)
        scan(main)
        // Only what was asked for: the whole set would mean tens of megabytes on first use.
        if (wanted in map) { saveHashes(map, pack, harvested = true); return }
        val maps = CHUNK_MAP.findAll(main).map { m ->
            PAIR.findAll(m.value).associate { it.groupValues[1].toInt() to it.groupValues[2] }
        }.filter { it.size > 3 }.toList()
        val hex = Regex("[0-9a-f]{8,}")
        val hashMaps = maps.filter { it.values.all { v -> v.matches(hex) } }
        val nameMaps = maps.filter { it !in hashMaps }
        val chunks = ArrayList<String>()
        for (h in hashMaps) for (n in nameMaps) {
            val common = h.keys intersect n.keys
            if (common.size < 3) continue
            common.sorted().forEach { id -> chunks += "https://open.spotifycdn.com/cdn/build/web-player/${n[id]}.${h[id]}.js" }
        }
        // The chunks that hold the queries are the routes and the player's core: try likely names first.
        val ordered = chunks.distinct().sortedBy { u -> if (Regex("xpui|routes|playlist|library|search|collection").containsMatchIn(u)) 0 else 1 }
        var bytes = 0L
        for (u in ordered) {
            if (wanted in map || bytes > 60_000_000L) break
            val t = fetch(u)
            bytes += t.length
            scan(t)
        }
        saveHashes(map, pack, harvested = true)
    }

    // ---- GraphQL

    private fun headers(b: Request.Builder): Request.Builder {
        val bearer = host.bearer()
        val ct = clientToken()
        return b.header("Authorization", "Bearer $bearer")
            .apply { if (ct != null) header("client-token", ct) }
            .header("spotify-app-version", appVersion())
            .header("app-platform", "WebPlayer")
            .header("Accept", "application/json").header("Accept-Language", "en")
            .header("Origin", "https://open.spotify.com").header("Referer", "https://open.spotify.com/")
            .header("User-Agent", host.userAgent)
    }

    /** One persisted query. GraphQL errors and a refused write (HTTP 200, a failure typename) both throw. */
    fun gql(operation: String, variables: JsonObject, retry: Boolean = true): JsonElement {
        if (operation.startsWith("search")) paceSearch()
        val body = jsonObj(
            "variables" to variables,
            "operationName" to operation,
            "extensions" to jsonObj("persistedQuery" to jsonObj("version" to 1, "sha256Hash" to hashFor(operation))),
        )
        val req = headers(Request.Builder().url(PATHFINDER).post(body.toString().toRequestBody(JSON))).header("Content-Type", "application/json").build()
        val (code, text, wait) = send(req)
        val j = runCatching { parseJson(text) }.getOrNull() ?: JsonNull
        val errors = j["errors"].arr.mapNotNull { it["message"].str }
        if (code == 401 && retry) { host.bearerRefused(); return gql(operation, variables, retry = false) }
        if (errors.any { it.contains("PersistedQueryNotFound", true) } && retry) {
            hashFor(operation, forceHarvest = true)
            return gql(operation, variables, retry = false)
        }
        if (code == 429) throw BridgeException("Spotify asks to slow down (429, retry in $wait s)")
        // A server error is often a moment's one (a 500 on the library while the next call works):
        // tried once more after a pause, and logged so a report shows whether it came back.
        // Except a write that adds: it may have gone through before the error, and sent again it adds
        // the same tracks twice (#42). It is not repeated; the next sync reads the playlist and adds
        // only what is missing.
        if (code >= 500 && operation in NOT_REPEATED) {
            BridgeNet.log("spotify", "$operation: HTTP $code, outcome unknown: not sent again, the next sync adds what is missing")
            throw BridgeException("Spotify $operation: HTTP $code, $OUTCOME_UNKNOWN")
        }
        if (code >= 500 && retry) {
            BridgeNet.log("spotify", "$operation: HTTP $code, trying once more" + (lastClientTokenError?.let { " (no client token: $it)" } ?: ""))
            Thread.sleep(2_000)
            return gql(operation, variables, retry = false)
        }
        if (code !in 200..299 && errors.isEmpty()) throw BridgeException("Spotify $operation: HTTP $code ${text.take(200)}" + (lastClientTokenError?.let { " (no client token: $it)" } ?: ""))
        if (errors.isNotEmpty()) throw BridgeException("Spotify $operation: ${errors.joinToString("; ").take(300)}")
        val typename = j["data"].let { d -> (d as? JsonObject)?.values?.firstOrNull()?.get("__typename").str }
        if (typename != null && (typename.endsWith("Error") || typename.contains("Failure") || typename.contains("NotFound"))) {
            throw BridgeException("Spotify $operation: $typename")
        }
        return j
    }

    private data class Resp(val code: Int, val text: String, val retryAfter: Int)

    private fun send(req: Request): Resp = http.newCall(req).execute().use { r ->
        Resp(r.code, r.body?.string() ?: "", r.header("Retry-After")?.trim()?.toIntOrNull()?.coerceIn(5, 3600) ?: 60)
    }

    // ---- spclient: the playlist store the player writes to

    private fun spclient(path: String, body: JsonObject, retry: Boolean = true): JsonElement {
        val req = headers(Request.Builder().url("$SPCLIENT$path").post(body.toString().toRequestBody(JSON))).header("Content-Type", "application/json").build()
        val (code, text, wait) = send(req)
        if (code == 401 && retry) { host.bearerRefused(); return spclient(path, body, retry = false) }
        if (code == 429) throw BridgeException("Spotify asks to slow down (429, retry in $wait s)")
        if (code !in 200..299) throw BridgeException("Spotify playlist store: HTTP $code on ${path.substringBefore('?')}" + text.take(200).let { if (it.isBlank()) " (no reason given)" else " $it" })
        return runCatching { parseJson(text) }.getOrNull() ?: JsonNull
    }

    private fun spclientGet(path: String, retry: Boolean = true): JsonElement {
        val req = headers(Request.Builder().url("$SPCLIENT$path").get()).header("Accept", "application/json").build()
        val (code, text, wait) = send(req)
        if (code == 401 && retry) { host.bearerRefused(); return spclientGet(path, retry = false) }
        if (code == 429) throw BridgeException("Spotify asks to slow down (429, retry in $wait s)")
        if (code !in 200..299) throw BridgeException("Spotify playlist store: HTTP $code on ${path.substringBefore('?')} ${text.take(200)}")
        return runCatching { parseJson(text) }.getOrNull() ?: JsonNull
    }

    private fun deltas(vararg ops: JsonObject): JsonObject = jsonObj(
        "deltas" to listOf(jsonObj("ops" to ops.toList(), "info" to jsonObj("source" to jsonObj("client" to 5)))),
        "wantResultingRevisions" to false, "wantSyncResult" to false, "nonces" to emptyList<String>(),
    )

    /**
     * A change to the user's rootlist (the library's playlist list). Unlike a playlist's own
     * changes, the rootlist refuses a change without the revision it applies to (HTTP 400 with an
     * empty body): the rootlist is read for its revision and the change names it as baseRevision.
     * [build] gets the rootlist read, for changes that need an entry's position.
     */
    private fun rootlistChange(build: (root: JsonElement) -> JsonObject) {
        val user = username()
        val root = spclientGet("/playlist/v2/user/$user/rootlist?decorate=revision,length,attributes,timestamp,owner")
        val op = build(root)
        val body = deltas(op).let { d -> root["revision"].str?.let { rev -> JsonObject(d + ("baseRevision" to JsonPrimitive(rev))) } ?: d }
        spclient("/playlist/v2/user/$user/rootlist/changes", body)
    }

    // ---- account

    /** The signed-in user's name, from the account page the player itself uses. Cached in the session. */
    fun profile(): Pair<String?, String?> {
        host.get("userId")?.let { return it to host.account() }
        val req = Request.Builder().url("https://www.spotify.com/api/account-settings/v1/profile")
            .header("Cookie", cookies()).header("User-Agent", host.userAgent).header("Accept", "application/json").build()
        val j = http.newCall(req).execute().use { r -> if (r.isSuccessful) parseJson(r.body?.string() ?: "") else JsonNull }
        var username = j["profile"]["username"].str
        var name = j["profile"]["name"].str ?: j["profile"]["displayName"].str ?: username
        if (username == null) runCatching {
            val p = gql("profileAttributes", jsonObj())["data"]["me"]["profile"]
            username = p["username"].str; name = p["name"].str ?: username
        }
        if (username != null) host.put("userId", username)
        return username to name
    }

    private fun username(): String = profile().first ?: throw BridgeException("Spotify: the account page did not say who is signed in")

    // ---- reads

    fun libraryPlaylists(): List<PlaylistDto> {
        val me = runCatching { profile().first }.getOrNull()
        val out = ArrayList<PlaylistDto>()
        var offset = 0
        while (true) {
            val j = gql("libraryV3", jsonObj(
                "filters" to listOf("Playlists"), "order" to null, "textFilter" to "",
                "features" to listOf("LIKED_SONGS", "YOUR_EPISODES", "PRERELEASES"),
                "limit" to 50, "offset" to offset, "flatten" to true, "expandedFolders" to emptyList<String>(),
                "folderUri" to null, "includeFoldersWhenFlattening" to false,
            ))
            val lib = j["data"]["me"]["libraryV3"]
            val items = lib["items"].arr
            if (offset == 0 && lib == null) unexpected("libraryV3", j, items)
            for (it in items) {
                val d = it["item"]["data"] ?: it["item"]
                val uri = d["uri"].str ?: it["item"]["_uri"].str ?: continue
                if (!uri.startsWith("spotify:playlist:")) continue
                val owner = d["ownerV2"]["data"]["username"].str ?: d["ownerV2"]["data"]["uri"].str?.substringAfterLast(':') ?: d["owner"]["username"].str
                out += PlaylistDto(uri.substringAfterLast(':'), d["name"].str ?: "", -1, owned = me == null || owner == null || owner == me)
            }
            val total = lib["totalCount"].long?.toInt() ?: 0
            offset += 50
            if (items.isEmpty() || offset >= total) break
        }
        return out
    }

    /** Saved albums or followed artists, as the library lists them. */
    fun libraryItems(kind: String): List<TrackDto> {
        val filter = if (kind == "album") "Albums" else "Artists"
        val out = ArrayList<TrackDto>()
        var offset = 0
        while (true) {
            val j = gql("libraryV3", jsonObj(
                "filters" to listOf(filter), "order" to null, "textFilter" to "",
                "features" to listOf("LIKED_SONGS", "YOUR_EPISODES", "PRERELEASES"),
                "limit" to 50, "offset" to offset, "flatten" to true, "expandedFolders" to emptyList<String>(),
                "folderUri" to null, "includeFoldersWhenFlattening" to false,
            ))
            val lib = j["data"]["me"]["libraryV3"]
            val items = lib["items"].arr
            for (it in items) {
                val d = it["item"]["data"] ?: it["item"]
                val t = if (kind == "album") albumDto(d, it["addedAt"]["isoString"].str) else artistDto(d, it["addedAt"]["isoString"].str)
                if (t != null) out += t
            }
            val total = lib["totalCount"].long?.toInt() ?: 0
            offset += 50
            if (items.isEmpty() || offset >= total) break
        }
        return out
    }

    /** A cheap sign of change: total plus the newest entry (liked songs) or the first and last entries (playlists). */
    fun version(id: String): String? {
        if (id == "__liked__") {
            val lib = gql("fetchLibraryTracks", jsonObj("offset" to 0, "limit" to 1))["data"]["me"]["library"]["tracks"]
            return "${lib["totalCount"].long}|${lib["items"][0]["addedAt"]["isoString"].str}"
        }
        if (id.startsWith("__")) return null
        val first = gql("fetchPlaylist", jsonObj("uri" to "spotify:playlist:$id", "offset" to 0, "limit" to 1, "enableWatchFeedEntrypoint" to false))["data"]["playlistV2"]
        first["revisionId"].str?.let { return it }
        val total = first["content"]["totalCount"].long ?: return null
        val last = if (total > 1) gql("fetchPlaylist", jsonObj("uri" to "spotify:playlist:$id", "offset" to total - 1, "limit" to 1, "enableWatchFeedEntrypoint" to false))["data"]["playlistV2"]["content"]["items"][0]["uid"].str else null
        return "$total|${first["content"]["items"][0]["uid"].str}|$last"
    }

    fun playlistInfo(id: String): PlaylistDto {
        val j = gql("fetchPlaylist", jsonObj("uri" to "spotify:playlist:$id", "offset" to 0, "limit" to 1, "enableWatchFeedEntrypoint" to false))
        val p = j["data"]["playlistV2"]
        val me = runCatching { profile().first }.getOrNull()
        val owner = p["ownerV2"]["data"]["username"].str ?: p["ownerV2"]["data"]["uri"].str?.substringAfterLast(':')
        return PlaylistDto(id, p["name"].str ?: id, p["content"]["totalCount"].long?.toInt() ?: -1, owned = me != null && owner == me, description = p["description"].str ?: "")
    }

    fun playlistTracks(id: String): List<TrackDto> {
        val out = ArrayList<TrackDto>()
        var offset = 0
        var broken = 0
        while (true) {
            val j = gql("fetchPlaylist", jsonObj("uri" to "spotify:playlist:$id", "offset" to offset, "limit" to 100, "enableWatchFeedEntrypoint" to false))
            val content = j["data"]["playlistV2"]["content"]
            val items = content["items"].arr
            var parsed = 0
            for (it in items) {
                val d = it["itemV2"]["data"] ?: it["item"]["data"]
                val uri = it["itemV2"]["_uri"].str ?: it["item"]["_uri"].str
                val t = trackDto(d, it["uid"].str, it["addedAt"]["isoString"].str, uri)
                if (t != null) { out += t; parsed++ } else if (isTrackItem(d, uri)) broken++
            }
            val total = content["totalCount"].long?.toInt() ?: 0
            if (parsed == 0 && offset == 0 && (items.isNotEmpty() || content == null || total != 0)) unexpected("fetchPlaylist", j, items, content)
            offset += 100
            if (items.isEmpty() || offset >= total) break
        }
        checkReadable("fetchPlaylist", out.size, broken)
        return out
    }

    /** An entry that is a track (not a local file or an episode) and still did not parse: the page shape moved. */
    private fun isTrackItem(d: JsonElement?, outerUri: String?) = (d["uri"].str ?: outerUri)?.startsWith("spotify:track:") == true

    /**
     * A listing where many track entries could not be read is not a shorter playlist, it is a page
     * the parser no longer understands: handing it over as is would make a mirror sync remove the
     * tracks it could not read. A few unreadable entries (tracks pulled from the catalogue) are normal.
     */
    private fun checkReadable(operation: String, parsed: Int, broken: Int) {
        if (broken > 10 && broken * 5 > parsed + broken) {
            throw BridgeException("Spotify $operation: $broken of ${parsed + broken} tracks could not be read, the page shape has changed")
        }
    }

    fun likedTracks(): List<TrackDto> {
        val out = ArrayList<TrackDto>()
        var offset = 0
        var broken = 0
        while (true) {
            val j = gql("fetchLibraryTracks", jsonObj("offset" to offset, "limit" to 100))
            val lib = j["data"]["me"]["library"]["tracks"]
            val items = lib["items"].arr
            var parsed = 0
            for (it in items) {
                val d = it["track"]["data"] ?: it["item"]["data"]
                val uri = it["track"]["_uri"].str ?: it["item"]["_uri"].str
                val t = trackDto(d, it["uid"].str, it["addedAt"]["isoString"].str, uri)
                if (t != null) { out += t; parsed++ } else if (isTrackItem(d, uri)) broken++
            }
            val total = lib["totalCount"].long?.toInt() ?: 0
            // Zero liked songs is a legitimate answer only when Spotify says the total is zero.
            if (parsed == 0 && offset == 0 && (items.isNotEmpty() || lib == null || total != 0 || lib["totalCount"] == null)) unexpected("fetchLibraryTracks", j, items, lib)
            offset += 100
            if (items.isEmpty() || offset >= total) break
        }
        checkReadable("fetchLibraryTracks", out.size, broken)
        return out
    }

    /** Catalogue search, the player's: tracks, or albums or artists for [kind]. */
    fun search(query: String, kind: String?): List<TrackDto> {
        if (kind == null) {
            val r = runCatching {
                gql("searchTracks", jsonObj("searchTerm" to query, "offset" to 0, "limit" to 10, "numberOfTopResults" to 5, "includeAudiobooks" to false, "includePreReleases" to false))
            }.onFailure { e ->
                // Spotify asking for a pause is said at once: asking the other search would only add to it.
                if (e.message?.contains("429") == true) throw e
                if (searchTracksFailures++ < 3) BridgeNet.log("spotify", "searchTracks failed, trying searchDesktop: ${e.message?.take(200)}")
            }.getOrNull()
            val items = r["data"]["searchV2"]["tracksV2"]["items"].arr
            if (items.isNotEmpty()) return items.mapNotNull { trackDto(it["item"]["data"] ?: it["data"], null, null, it["item"]["_uri"].str) }
        }
        val j = gql("searchDesktop", jsonObj(
            "searchTerm" to query, "offset" to 0, "limit" to 10, "numberOfTopResults" to 5,
            "includeAudiobooks" to false, "includeArtistHasConcertsField" to false, "includePreReleases" to false,
            "includeLocalConcertsField" to false, "includeAuthors" to false,
        ))
        val s = j["data"]["searchV2"]
        return when (kind) {
            "album" -> s["albumsV2"]["items"].arr.mapNotNull { albumDto(it["data"] ?: it["item"]["data"], null) }
            "artist" -> s["artists"]["items"].arr.mapNotNull { artistDto(it["data"] ?: it["item"]["data"], null) }
            else -> s["tracksV2"]["items"].arr.mapNotNull { trackDto(it["item"]["data"] ?: it["data"], null, null, it["item"]["_uri"].str) }
        }
    }

    fun track(id: String): TrackDto? {
        val j = runCatching { gql("getTrack", jsonObj("uri" to "spotify:track:$id")) }.getOrNull() ?: return null
        val d = j["data"]["trackUnion"]
        val artists = (d["firstArtist"]["items"].arr + d["otherArtists"]["items"].arr).mapNotNull { it["profile"]["name"].str }
            .ifEmpty { d["artistsV2"]["items"].arr.mapNotNull { it["profile"]["name"].str } }
        val name = d["name"].str ?: return null
        return TrackDto(id, name, artists, d["albumOfTrack"]["name"].str ?: "", d["duration"]["totalMilliseconds"].long ?: 0, uri = "spotify:track:$id")
    }

    // ---- writes

    fun createPlaylist(name: String, description: String): PlaylistDto {
        val j = spclient("/playlist/v2/playlist", jsonObj(
            "ops" to listOf(jsonObj("kind" to 6, "updateListAttributes" to jsonObj(
                "newAttributes" to jsonObj("values" to jsonObj("name" to name, "description" to description, "formatAttributes" to emptyList<String>(), "pictureSize" to emptyList<String>()), "noValue" to emptyList<String>()),
            ))),
        ))
        val uri = j["uri"].str ?: Regex("spotify:playlist:[A-Za-z0-9]+").find(j.toString())?.value
            ?: throw BridgeException("Spotify: playlist not created")
        // The store has it; the library lists it only once the rootlist does.
        runCatching {
            rootlistChange {
                jsonObj("kind" to 2, "add" to jsonObj("items" to listOf(jsonObj("uri" to uri, "attributes" to jsonObj("timestamp" to System.currentTimeMillis(), "formatAttributes" to emptyList<String>(), "availableSignals" to emptyList<String>()))), "addFirst" to true))
            }
        }
        return PlaylistDto(uri.substringAfterLast(':'), name, 0, owned = true, description = description)
    }

    fun addToPlaylist(id: String, uris: List<String>) {
        uris.chunked(100).forEach { chunk ->
            try {
                gql("addToPlaylist", jsonObj(
                    "uris" to chunk, "playlistItemUris" to chunk, "playlistUri" to "spotify:playlist:$id",
                    "newPosition" to jsonObj("moveType" to "BOTTOM_OF_PLAYLIST", "fromUid" to null),
                ))
            } catch (e: BridgeException) {
                if (e.message?.contains("no query hash") != true && e.message?.contains("429") == true) throw e
                // A server error may hide an add that went through: the same tracks sent again by the
                // other route would be there twice (#42).
                if (e.message?.contains(OUTCOME_UNKNOWN) == true) throw e
                // The store accepts the same change directly.
                spclient("/playlist/v2/playlist/$id/changes", deltas(jsonObj(
                    "kind" to 2, "add" to jsonObj("items" to chunk.map { jsonObj("uri" to it, "attributes" to jsonObj("timestamp" to System.currentTimeMillis(), "formatAttributes" to emptyList<String>(), "availableSignals" to emptyList<String>())) }, "addLast" to true),
                )))
            }
        }
    }

    /** Removes playlist entries by their uid; entries without one are looked up in the playlist. */
    fun removeFromPlaylist(id: String, items: List<RemoveItem>) {
        val missing = items.filter { it.setVideoId.isNullOrEmpty() }.map { it.videoId }.toSet()
        val byTrack = if (missing.isNotEmpty()) playlistTracks(id).filter { it.id in missing }.groupBy { it.id } else emptyMap()
        val uids = items.flatMap { r -> if (!r.setVideoId.isNullOrEmpty()) listOf(r.setVideoId) else byTrack[r.videoId].orEmpty().mapNotNull { it.setVideoId } }
        if (uids.isEmpty()) return
        uids.chunked(100).forEach { chunk ->
            try {
                gql("removeFromPlaylist", jsonObj("playlistUri" to "spotify:playlist:$id", "uids" to chunk))
            } catch (e: BridgeException) {
                if (e.message?.contains("429") == true) throw e
                spclient("/playlist/v2/playlist/$id/changes", deltas(jsonObj(
                    "kind" to 3, "rem" to jsonObj("items" to chunk.map { jsonObj("uid" to it) }, "itemsAsKey" to true),
                )))
            }
        }
    }

    fun renamePlaylist(id: String, name: String) {
        spclient("/playlist/v2/playlist/$id/changes", deltas(jsonObj(
            "kind" to 6, "updateListAttributes" to jsonObj("newAttributes" to jsonObj("values" to jsonObj("name" to name), "noValue" to emptyList<String>())),
        )))
    }

    /**
     * Public means shown on the profile: the `public` attribute of the playlist's entry in the
     * rootlist, which is what the Web API's `public` flag sets too. The entry's position is needed
     * for the change, so the rootlist is read first.
     */
    fun setPublic(id: String, public: Boolean) {
        val uri = "spotify:playlist:$id"
        rootlistChange { root ->
            val index = root["contents"]["items"].arr.indexOfFirst { it["uri"].str == uri }
            if (index < 0) throw BridgeException("Spotify: the playlist is not in your library")
            jsonObj("kind" to 5, "updateItemAttributes" to jsonObj("index" to index, "newAttributes" to jsonObj("values" to jsonObj("public" to public), "noValue" to emptyList<String>())))
        }
    }

    /** Moves "item at from, inserted before to", applied in order: the store's MOV op, a hundred per request. */
    fun moveItems(id: String, moves: List<Pair<Int, Int>>) {
        moves.chunked(100).forEach { chunk ->
            spclient("/playlist/v2/playlist/$id/changes", deltas(*chunk.map { (from, to) ->
                jsonObj("kind" to 4, "mov" to jsonObj("fromIndex" to from, "length" to 1, "toIndex" to to))
            }.toTypedArray()))
        }
    }

    /** Spotify has no delete: a playlist taken off the rootlist leaves the library. */
    fun deletePlaylist(id: String) {
        val uri = "spotify:playlist:$id"
        try {
            gql("removeFromLibrary", jsonObj("uris" to listOf(uri), "libraryItemUris" to listOf(uri)))
        } catch (e: BridgeException) {
            if (e.message?.contains("429") == true) throw e
            rootlistChange { jsonObj("kind" to 3, "rem" to jsonObj("items" to listOf(jsonObj("uri" to uri)), "itemsAsKey" to true)) }
        }
    }

    /** Liked songs, saved albums, followed artists: one mutation, uris of any kind. */
    fun addToLibrary(uris: List<String>) {
        uris.chunked(50).forEach { chunk -> gql("addToLibrary", jsonObj("uris" to chunk, "libraryItemUris" to chunk)) }
    }

    fun removeFromLibrary(uris: List<String>) {
        uris.chunked(50).forEach { chunk -> gql("removeFromLibrary", jsonObj("uris" to chunk, "libraryItemUris" to chunk)) }
    }

    /** The shape of an answer nothing could be read from: the keys, so the parser can be fixed from a screenshot. */
    private fun unexpected(operation: String, j: JsonElement, items: List<JsonElement>, container: JsonElement? = null): Nothing {
        val first = items.firstOrNull()
        fun keys(e: JsonElement?) = (e as? JsonObject)?.keys?.joinToString(",") ?: e?.javaClass?.simpleName ?: "null"
        val what = buildString {
            append("data keys ").append(keys(j["data"]))
            if (container != null) append("; list keys ").append(keys(container))
            if (first != null) {
                append("; item keys ").append(keys(first)).append(", typename ").append(first.findFirst("__typename").str)
                append(", item ").append(first.toString().take(300))
            }
        }
        throw BridgeException("Spotify $operation: nothing readable in the answer ($what)")
    }

    // ---- shapes

    /** [outerUri]: the library and playlist rows carry the uri beside `data` (`_uri`), not always inside it. */
    private fun trackDto(d: JsonElement?, uid: String?, addedAt: String?, outerUri: String? = null): TrackDto? {
        val uri = d["uri"].str ?: outerUri ?: return null
        if (!uri.startsWith("spotify:track:")) return null
        val name = d["name"].str ?: return null
        val artists = d["artists"]["items"].arr.mapNotNull { it["profile"]["name"].str }
            .ifEmpty { d["artistsV2"]["items"].arr.mapNotNull { it["profile"]["name"].str } }
            .ifEmpty { d["albumOfTrack"]["artists"]["items"].arr.mapNotNull { it["profile"]["name"].str } }
        val duration = d["trackDuration"]["totalMilliseconds"].long ?: d["duration"]["totalMilliseconds"].long ?: 0
        val rating = d["contentRating"]["label"].str
        return TrackDto(
            id = uri.substringAfterLast(':'), title = name, artists = artists, album = d["albumOfTrack"]["name"].str ?: "",
            durationMs = duration, setVideoId = uid, uri = uri,
            explicit = rating?.let { it.equals("EXPLICIT", true) },
            addedAt = addedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() },
            year = d["albumOfTrack"]["date"]["year"].long?.toInt(),
            // The player's listing says whether each entry plays here; absent in older page shapes.
            available = (d["playability"]["playable"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull(),
        )
    }

    private fun albumDto(d: JsonElement?, addedAt: String?): TrackDto? {
        val uri = d["uri"].str ?: return null
        if (!uri.startsWith("spotify:album:")) return null
        val name = d["name"].str ?: return null
        return TrackDto(
            id = uri.substringAfterLast(':'), title = name, artists = d["artists"]["items"].arr.mapNotNull { it["profile"]["name"].str },
            uri = uri, kind = "album", year = d["date"]["year"].long?.toInt(),
            addedAt = addedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() },
        )
    }

    private fun artistDto(d: JsonElement?, addedAt: String?): TrackDto? {
        val uri = d["uri"].str ?: return null
        if (!uri.startsWith("spotify:artist:")) return null
        val name = d["profile"]["name"].str ?: d["name"].str ?: return null
        return TrackDto(
            id = uri.substringAfterLast(':'), title = name, artists = listOf(name), uri = uri, kind = "artist",
            addedAt = addedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() },
        )
    }

    companion object {
        /** Writes that add: never sent a second time after an answer that leaves their outcome unknown (#42). */
        private val NOT_REPEATED = setOf("addToPlaylist")
        private const val OUTCOME_UNKNOWN = "outcome unknown"
        /**
         * Searches spaced out, about three a second across every client of the process: a burst of
         * them makes Spotify block for half an hour (issue #30), a steady pace does not.
         */
        private const val SEARCH_GAP_MS = 350L
        @Volatile private var searchTracksFailures = 0
        private var nextSearchAt = 0L
        @JvmStatic private fun paceSearch() {
            val wait = synchronized(this) {
                val now = System.currentTimeMillis()
                val at = maxOf(now, nextSearchAt)
                nextSearchAt = at + SEARCH_GAP_MS
                at - now
            }
            if (wait > 0) Thread.sleep(wait)
        }

        const val PATHFINDER = "https://api-partner.spotify.com/pathfinder/v1/query"
        /** open.spotify.com's own client id, unchanged for years; the page is read first all the same. */
        const val WEB_PLAYER_CLIENT_ID = "d8a5ed958d274c2e8ee717e6a4b0971d"
        const val DEFAULT_APP_VERSION = "1.2.70.0"
        @Volatile private var clientTokenFailedAt = 0L
        @Volatile private var lastClientTokenError: String? = null
        const val SPCLIENT = "https://spclient.wg.spotify.com"
        private val JSON = "application/json".toMediaType()
        val SESSION_SCRIPT = Regex("""<script[^>]*id="session"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        val SERVER_CONFIG = Regex("""<script[^>]*id="appServerConfig"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        /** `"fetchPlaylist","query","<sha256>"`: how the player's bundles name their persisted queries. */
        val HASH = Regex(""""([A-Za-z][A-Za-z0-9_]*)"\s*,\s*"(query|mutation)"\s*,\s*"([0-9a-f]{64})"""")
        const val REGISTRY_URL = "https://raw.githubusercontent.com/Jigen-Ohtsusuki/spotify-gql-registry/main/hashes.json"
        /** Ours: refreshed every hour by the "Spotify hashes" workflow from the live player, mutations included. */
        const val OWN_REGISTRY_URL = "https://raw.githubusercontent.com/xlollx/Trackhop/badges/spotify-hashes.json"
        /** What the player used when this version was built: the last resort when nothing else answers. */
        val SHIPPED_HASHES: Map<String, String> = mapOf(
            "libraryV3" to "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455",
            "fetchPlaylist" to "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf",
            "fetchLibraryTracks" to "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240",
            "searchTracks" to "b02683192a98dde7966b5e6655a79eeb62713eab703eda9902c932818dd52751",
            "searchDesktop" to "eef7cc54888d91bdd6802623477873caa3948ae173a0c34fd86827b267e94c03",
            "getTrack" to "e7b1bf4e95cb3d733d48b442f93006e9712fd504e8b88d7a82e6e3bac9306a6b",
            "profileAttributes" to "08ffb4730af3746e04a8301396f20875dbbce10c75243803091a9274eacc8ac0",
            "addToPlaylist" to "b907ad6b088d5bf7680fa81198996d58398c2d55dfcdf59fcc959e0be98a418a",
            "removeFromPlaylist" to "b907ad6b088d5bf7680fa81198996d58398c2d55dfcdf59fcc959e0be98a418a",
            "addToLibrary" to "896ebcb47815681340860d121cb5d494e157e2a78d3950385cd54e0393c67148",
            "removeFromLibrary" to "896ebcb47815681340860d121cb5d494e157e2a78d3950385cd54e0393c67148",
        )
        /** When each name was last looked for in the bundle without luck. */
        private val missedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val CHUNK_MAP = Regex("""\{\d+:"[^"]+"(?:,\d+:"[^"]+")*\}""")
        private val PAIR = Regex("""(\d+):"([^"]+)"""")
        /** The player page of each account (by [WebHost.cacheScope]): one per session, never another's. */
        private val cachedPages = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Page>>()
        /** Drops the account's cached page ([scope]: its host's [WebHost.cacheScope]): its bearer was refused, the next call fetches a fresh one. */
        fun dropPage(scope: String = "") { cachedPages.remove(scope) }
    }
}
