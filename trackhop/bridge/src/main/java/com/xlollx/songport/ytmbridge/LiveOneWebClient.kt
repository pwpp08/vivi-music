package com.xlollx.songport.ytmbridge

import com.xlollx.songport.ytmbridge.BridgeNet.withHook
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * LiveOne (formerly Slacker Radio) through liveone.com's own web player: it has no public API.
 *
 * The session is the web player's refresh token (the `refresh_token` entry of liveone.com's local
 * storage, a JWT that names the account and lasts years). With it, as the player does:
 * 1. `sapi.slacker.com/auth/oauth/token`, `grant_type=refresh_token`, signed with Basic
 *    `slackersite:<key>|ki:45|si:1|ts:<seconds>|<sha1>` → an access token for two hours and a new
 *    refresh token (kept: the old one may not work twice);
 * 2. `slacker.liveone.com/wsv1/auth/wstokenbridge` with that access token → the "WSToken" the
 *    player's other calls carry as `BBI-AUTHORIZATION`.
 * The two signing keys are the player's: read at run time from its script (CDN, versioned), not
 * written here, so a new player version brings its own.
 *
 * What a free account has is its "presets" (`mixer/presets/LIVEXLIVE/US/<account>/default`): a list
 * of songs, read whole, added to with a JSON Patch. Playlists of one's own need a paid plan and are
 * not handled yet. Search needs no account. Seen in a signed-in session of 02/11/2026; none of it
 * is a documented interface.
 *
 * No Android here: the server build compiles this file as it is (server/build.gradle).
 */
class LiveOneWebClient(private val host: WebHost) {

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).withHook().build()

    /** The player's version and its two keys (decoded), cached a day. */
    class Player(val version: String, val k: String, val oauthK: String)

    // ---- the session

    /** The account id, from the refresh token itself (its `accountId` claim). */
    fun accountId(): String = host.get(ACCOUNT) ?: accountOf(refreshToken()) ?: throw BridgeException(EXPIRED)

    private fun refreshToken(): String = host.get(REFRESH) ?: host.cookies()?.let { tokenIn(it) } ?: throw BridgeException("not connected")

    /** The WSToken for the player's calls: renewed when older than [TOKEN_MS] or refused. */
    fun wsToken(renew: Boolean = false): String {
        if (!renew) {
            val at = host.get(WS_AT)?.toLongOrNull() ?: 0
            host.get(WS)?.takeIf { System.currentTimeMillis() - at < TOKEN_MS }?.let { return it }
        }
        val p = player()
        val ts = (System.currentTimeMillis() / 1000).toString()
        val data = "${p.oauthK}|ki:$CLIENT_ID|si:1|ts:$ts"
        val basic = Base64.getEncoder().encodeToString("$OAUTH_CLIENT:$data|${sha1(p.k + data)}".toByteArray())
        val form = "grant_type=refresh_token&refresh_token=" + java.net.URLEncoder.encode(refreshToken(), "UTF-8")
        val tok = send(
            Request.Builder().url(TOKEN_URL).post(form.toRequestBody(FORM)).header("Authorization", "Basic $basic").headers(p).build(),
        )
        if (tok.code != 200) {
            val j = runCatching { parseJson(tok.text) }.getOrNull()
            val why = j["error"].str
            val detail = j["error_description"].str
            // The token itself refused (spent, signed out, not a refresh token); "Unauthorized" with no
            // description is the client's signature, ours to fix.
            if (why == "invalid_grant" || why == "invalid_token") throw BridgeException("$EXPIRED (${detail ?: why})")
            throw BridgeException("LiveOne sign-in refused (HTTP ${tok.code}${why?.let { ", $it" } ?: ""}${detail?.let { ": $it" } ?: ""})")
        }
        val j = parseJson(tok.text)
        val access = j["access_token"].str ?: throw BridgeException(EXPIRED)
        j["refresh_token"].str?.let { host.put(REFRESH, it) }
        j["accountId"].str?.let { host.put(ACCOUNT, it) }
        val bridge = send(
            Request.Builder().url(BRIDGE_URL).post("".toRequestBody(FORM)).header("Authorization", "Bearer $access").headers(p).build(),
        )
        if (bridge.code != 200) throw BridgeException("LiveOne sign-in (bridge): HTTP ${bridge.code}")
        val ws = Regex("<WSToken>([^<]+)</WSToken>").find(bridge.text)?.groupValues?.get(1) ?: throw BridgeException(EXPIRED)
        host.put(WS, ws)
        host.put(WS_AT, System.currentTimeMillis().toString())
        return ws
    }

    /** The player's version and keys: from www.liveone.com, its script on the CDN, cached a day. */
    fun player(): Player {
        val at = host.get(KEYS_AT)?.toLongOrNull() ?: 0
        if (System.currentTimeMillis() - at < 86_400_000L) {
            val v = host.get(VERSION); val k = host.get(K); val o = host.get(OAUTH_K)
            if (v != null && k != null && o != null) return Player(v, k, o)
        }
        val page = send(Request.Builder().url("https://www.liveone.com/").header("User-Agent", host.userAgent).build()).text
        val version = Regex("""cdn\.liveone\.com/s=([0-9.]+)/js/concat\.js""").find(page)?.groupValues?.get(1)
            ?: throw BridgeException("LiveOne's player has changed (no script found): Vivi needs an update")
        val script = send(Request.Builder().url("https://cdn.liveone.com/s=$version/js/concat.js").header("User-Agent", host.userAgent).build()).text
        // x-scid names the player's own version (8.4.0.1426), which the page's links carry as `av`.
        val appVersion = Regex("""av(?:%3D|=)([0-9]+(?:\.[0-9]+){2,})""").find(page)?.groupValues?.get(1) ?: version
        val p = parsePlayer(appVersion, script) ?: throw BridgeException("LiveOne's player has changed (no keys found): Vivi needs an update")
        host.put(VERSION, p.version); host.put(K, p.k); host.put(OAUTH_K, p.oauthK); host.put(KEYS_AT, System.currentTimeMillis().toString())
        return p
    }

    // ---- the presets

    /** The presets' songs, in their order. */
    fun presets(): List<TrackDto> = parsePresets(call("GET", presetsUrl(), null))

    /** Adds songs (by song id) at the end of the presets. */
    fun addPresets(songIds: List<String>) {
        if (songIds.isEmpty()) return
        val patch = JsonArray(songIds.map { jsonObj("op" to "add", "path" to "/content/-", "value" to jsonObj("type" to "song", "id" to it)) })
        call("PATCH", presetsUrl(), patch.toString())
    }

    private fun presetsUrl() = "https://slacker.liveone.com/mixer/presets/LIVEXLIVE/US/${accountId()}/default"

    /** A player call with the WSToken; a refused token is renewed once. */
    private fun call(method: String, url: String, json: String?, retry: Boolean = true): JsonElement? {
        val p = player()
        // A PATCH is a JSON Patch, and the server wants it named so (plain JSON gets a 415).
        val type = if (method == "PATCH") JSON_PATCH else JSON
        val req = Request.Builder().url(url).method(method, json?.toRequestBody(type))
            .header("BBI-AUTHORIZATION", wsToken()).header("Accept", "application/json").headers(p)
            .build()
        val r = send(req)
        if ((r.code == 401 || r.code == 403) && retry) { wsToken(renew = true); return call(method, url, json, retry = false) }
        if (r.code == 401 || r.code == 403) throw BridgeException(EXPIRED)
        if (r.code !in 200..299) throw BridgeException("LiveOne: HTTP ${r.code} ${r.text.take(160)}")
        return parseJson(r.text)
    }

    // ---- search (no account)

    /** Songs for a query, best first; ids are song ids, as the presets take them. */
    fun search(query: String, max: Int = 10): List<TrackDto> {
        val url = "https://slacker.liveone.com/search/quicksearch?maxResults=$max&searchFor=song&query=" + java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20") +
            "&includeFilter=basic,ondemand,paywall&newsearch=1&site=slacker&partner=&explicit=1&territory=US"
        val r = send(Request.Builder().url(url).header("User-Agent", host.userAgent).header("Accept", "application/json").build())
        if (r.code != 200) throw BridgeException("LiveOne search: HTTP ${r.code}")
        return parseSearch(r.text)
    }

    // ---- plumbing

    private fun Request.Builder.headers(p: Player): Request.Builder = this
        .header("User-Agent", host.userAgent)
        .header("x-scid", "00_webplayer_en_${p.version}_site:slacker,partner:lxl")
        .header("Origin", "https://www.liveone.com").header("Referer", "https://www.liveone.com/")

    private class Resp(val code: Int, val text: String)

    private fun send(req: Request): Resp = http.newCall(req).execute().use { Resp(it.code, it.body?.string().orEmpty()) }

    companion object {
        const val EXPIRED = "the LiveOne session has ended: paste a fresh refresh token"
        private const val TOKEN_URL = "https://sapi.slacker.com/auth/oauth/token"
        private const val BRIDGE_URL = "https://slacker.liveone.com/wsv1/auth/wstokenbridge"
        private const val OAUTH_CLIENT = "slackersite"
        private const val CLIENT_ID = 45
        /** Under the access token's two hours. */
        private const val TOKEN_MS = 100 * 60_000L
        const val REFRESH = "refresh"; const val ACCOUNT = "account"
        private const val WS = "ws"; private const val WS_AT = "wsAt"
        private const val VERSION = "playerVersion"; private const val K = "k"; private const val OAUTH_K = "oauthK"; private const val KEYS_AT = "keysAt"
        private val FORM = "application/x-www-form-urlencoded".toMediaType()
        private val JSON = "application/json".toMediaType()
        private val JSON_PATCH = "application/json-patch+json".toMediaType()

        fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Three hex digits a character, as the player writes its keys. */
        private fun hex3(h: String): String = (h.indices step 3).joinToString("") { i -> h.substring(i, minOf(i + 3, h.length)).toInt(16).toChar().toString() }

        /** A key as the script holds it: a function, in hex, that returns a string, in hex. */
        private fun key(encoded: String): String? = Regex("""var str\s*=\s*"([0-9a-f]+)"""").find(hex3(encoded))?.groupValues?.get(1)?.let { hex3(it) }

        fun parsePlayer(version: String, script: String): Player? {
            val k = Regex("""tokenClientId:45,K:"([0-9a-f]+)"""").find(script)?.groupValues?.get(1)?.let { key(it) } ?: return null
            // The web player's own config object (`OAUTH_K:"…"`): the script also holds the Fire TV's,
            // the Samsung TV's and others, assigned as `CONFIG.slackerConfig.OAUTH_K="…"`.
            val o = Regex("""[{,]OAUTH_K:"([0-9a-f]+)"""").find(script)?.groupValues?.get(1)?.let { key(it) } ?: return null
            return Player(version, k, o)
        }

        /** The refresh token in what was pasted: the JWT alone, or inside the `slackerUser` entry. */
        fun tokenIn(pasted: String): String? {
            val t = pasted.trim().trim('"')
            Regex(""""refresh_token"\s*:\s*"([^"]+)"""").find(t)?.let { return it.groupValues[1] }
            return Regex("""eyJ[\w-]+\.[\w-]+\.[\w-]+""").find(t)?.value
        }

        private fun claims(jwt: String): JsonElement? = runCatching {
            val payload = jwt.split('.')[1]
            parseJson(String(Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '='))))
        }.getOrNull()

        /** The `accountId` claim of a JWT. */
        fun accountOf(jwt: String): String? = claims(jwt)["accountId"].str?.takeIf { it.isNotBlank() }

        /**
         * What is wrong with a pasted token, or null when it is a refresh token still valid: the
         * access token (two hours, no `ati` claim) looks the same at a glance.
         */
        fun problemWith(jwt: String, now: Long = System.currentTimeMillis()): String? {
            val c = claims(jwt) ?: return "this is not a LiveOne token"
            if (c["ati"] == null) return "this is the access token (it lasts two hours), not the refresh token"
            val exp = c["exp"].long ?: return null
            return if (exp * 1000 < now) "this refresh token has expired: sign in to liveone.com again and copy a fresh one" else null
        }

        fun parsePresets(j: JsonElement?): List<TrackDto> = j["content"].arr.mapNotNull { c ->
            val it = c["item"]
            val songId = it["songId"].str ?: it["objectId"].str?.substringAfter("song:") ?: return@mapNotNull null
            val track = it["track"]
            TrackDto(
                id = songId,
                title = it["songName"].str ?: track["trackName"].str ?: return@mapNotNull null,
                artists = track["artists"].arr.mapNotNull { a -> a["artist"]["artistName"].str }.ifEmpty { listOfNotNull(track["artist"]["artistName"].str) },
                album = track["album"]["albumName"].str.orEmpty(),
                durationMs = (track["length"].long ?: 0) * 1000,
            )
        }

        fun parseSearch(body: String): List<TrackDto> {
            val results = parseJson(body)["results"]
            val items = results.arr.ifEmpty { (results as? JsonObject)?.values?.flatMap { it.arr }.orEmpty() }
            return items.filter { it["type"].str == "song" }.mapNotNull { j ->
                TrackDto(
                    id = j["id"].str ?: return@mapNotNull null,
                    title = j["name"].str ?: return@mapNotNull null,
                    artists = listOfNotNull(j["artistName"].str),
                    album = j["albumName"].str.orEmpty(),
                )
            }
        }
    }
}
