package com.xlollx.songport.ytmbridge

import android.content.Context
import android.webkit.CookieManager
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import com.xlollx.songport.ytmbridge.BridgeNet.withHook
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Talks to the GraphQL endpoint of the Amazon Music web player (`gql.music.amazon.<tld>`), which
 * replaced the old "skyfire" skill API in October 2026. Like the YouTube Music client this is not
 * a public API: operations and their persisted-query hashes were taken from the player's own
 * traffic, captured with the Bridge's "Capture traffic" screen.
 *
 * Authentication is the session's cookies plus the CSRF triple, device and session ids the player
 * sends as HTTP headers. Those values are in the configuration the signed-in page inlines
 * (`amznMusic.appConfig`), so they are read with a plain request; when Amazon refuses them, the
 * headers the real player sent (mirrored by [AmazonBridge] from a WebView) are used instead.
 */
class AmazonClient(private val ctx: Context) {

    /**
     * The player's configuration, from the signed-in page (`amznMusic.appConfig = {...}`) or from
     * `/config.json`. Fields are looked up under every name seen so far; [sessionCookie] fills the
     * session id from the `session-id` cookie, which is where the player itself takes it from.
     */
    class Config(val raw: JsonObject, private val sessionCookie: String? = null) {
        val accessToken get() = (raw["accessToken"].str ?: raw["tokens"]["accessToken"].str ?: raw["access_token"].str).orEmpty()
        val deviceId get() = (raw["deviceId"].str ?: raw["deviceID"].str).orEmpty()
        val deviceType get() = (raw["deviceType"].str ?: raw["deviceTypeId"].str).orEmpty()
        val sessionId get() = (raw["sessionId"].str ?: raw["sessionID"].str ?: sessionCookie).orEmpty()
        val csrfToken get() = (raw["csrf"]["token"].str ?: raw["CSRFTokenConfig"]["csrf_token"].str).orEmpty()
        val csrfTs get() = (raw["csrf"]["ts"] ?: raw["CSRFTokenConfig"]["csrf_ts"]).asText()
        val csrfRnd get() = (raw["csrf"]["rnd"] ?: raw["CSRFTokenConfig"]["csrf_rnd"]).asText()
        val customerId get() = raw["customerId"].str
        /**
         * The country the account's music belongs to, as the player's configuration says it: an
         * account of one store signed in on another's domain (a Dutch account on music.amazon.de)
         * is refused (AUTH_INVALID_CUSTOMER_ID) when the header names the domain's country instead.
         */
        val musicTerritory get() = ((raw["musicTerritory"].str ?: raw["territory"].str ?: raw["marketplaceCountry"].str)?.takeIf { it.length == 2 }
            ?: MARKETPLACES[raw["marketplaceId"].str])?.uppercase()
        val customerName get() = raw["customerName"].str ?: raw["displayName"].str ?: raw["customerDisplayName"].str
            ?: raw["customer"]["name"].str ?: raw["userName"].str
        /** A customer in the configuration: the page was served to a signed-in session. */
        val signedIn get() = accessToken.isNotBlank() || !customerId.isNullOrBlank()
        /** Names of the fields found, for diagnostics: never a value, only what is there. */
        val fields: List<String> get() = raw.keys.sorted()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .addNetworkInterceptor { browserCookies(it) }
        .withHook().build()

    /**
     * Each hop is sent with the WebView's cookie jar as it is at that moment. The jar is only read
     * here, never written: what Amazon set in answers to this client (a 401 among them) went into it
     * in 1.0.51-1.0.54 and left stale copies that even a new sign-in did not remove. Only the
     * WebView, the real browser, keeps the jar.
     */
    private fun browserCookies(chain: Interceptor.Chain): okhttp3.Response {
        val req = chain.request()
        val live = if (req.url.host.contains("amazon.")) {
            runCatching { CookieManager.getInstance().getCookie(req.url.toString()) }.getOrNull()
        } else null
        val sent = if (AmazonSession.looksSignedIn(live)) req.newBuilder().header("Cookie", live!!).build() else req
        return chain.proceed(sent)
    }

    private fun domain(): String = AmazonSession.domain(ctx) ?: throw BridgeException("not connected")
    /**
     * The session's cookies as they are now. Amazon rotates its session cookies as the player is
     * used, so the copy saved at sign-in goes stale within hours (401 on every call): the WebView's
     * cookie jar, which [send] also keeps updated with what Amazon answers, is read first, and the
     * saved copy follows it.
     */
    private fun cookies(): String {
        val stored = AmazonSession.cookies(ctx) ?: throw BridgeException("not connected")
        val domain = domain()
        val live = runCatching { CookieManager.getInstance().getCookie("https://$domain") }.getOrNull()
        if (live.isNullOrBlank() || !AmazonSession.looksSignedIn(live)) return stored
        if (live != stored) AmazonSession.save(ctx, live, domain, null)
        return live
    }

    /** The player configuration for the stored session, cached for ten minutes. */
    fun config(domain: String = domain(), cookies: String = cookies()): Config {
        cachedConfig?.let { (at, c) -> if (System.currentTimeMillis() - at < 600_000 && c.signedIn) return c }
        val page = runCatching { pageConfig(domain, cookies) }.getOrNull()
        val c = page?.takeIf { it.signedIn }
            ?: runCatching { jsonConfig(domain, cookies) }.getOrNull()?.takeIf { it.signedIn }
            ?: page
            ?: jsonConfig(domain, cookies)
        cachedConfig = System.currentTimeMillis() to c
        return c
    }

    /** Display name of the signed-in customer, best effort. */
    fun accountName(): String? = runCatching { config().customerName }.getOrNull()

    /** `amznMusic.appConfig = {...};` inlined in the page the player is served, read with the session. */
    private fun pageConfig(domain: String, cookies: String): Config? {
        val html = get("https://$domain/", cookies, "text/html,application/xhtml+xml")
        // The name also appears in the player's own code: take the assignment, not every mention.
        for (m in Regex(Regex.escape(APP_CONFIG) + """\s*=\s*\{""").findAll(html)) {
            val obj = braced(html, m.range.last) ?: continue
            val j = runCatching { parseJson(obj) }.getOrNull() as? JsonObject ?: continue
            return Config(j, sessionCookie(cookies))
        }
        return null
    }

    private fun jsonConfig(domain: String, cookies: String): Config {
        val text = get("https://$domain/config.json", cookies, "*/*")
        val j = parseJson(text) as? JsonObject ?: throw BridgeException("Amazon Music config: unexpected response")
        return Config(j, sessionCookie(cookies))
    }

    private fun get(url: String, cookies: String, accept: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Cookie", cookies)
            .header("User-Agent", AmazonBridge.storedUserAgent(ctx) ?: BridgeUa.desktop(ctx))
            .header("Accept", accept)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://${url.substringAfter("https://").substringBefore('/')}/")
            .build()
        val (code, text) = send(req)
        val what = url.substringAfterLast('/').ifBlank { "player page" }
        if (code !in 200..299) throw BridgeException("Amazon Music $code on $what: ${text.take(160)}")
        return text
    }

    /** The whole `{...}` starting at [from], counting braces outside of strings. */
    private fun braced(s: String, from: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in from until s.length) {
            val c = s[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> {}
                c == '{' -> depth++
                c == '}' -> { depth--; if (depth == 0) return s.substring(from, i + 1) }
            }
        }
        return null
    }

    /** The web player sends the `session-id` cookie as `x-amzn-session-id`. */
    private fun sessionCookie(cookies: String): String? = cookies.split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("session-id=") }
        ?.substringAfter('=')
        ?.takeIf { it.isNotBlank() }

    /**
     * One HTTP exchange, retried on transport errors. A phone switching between Wi-Fi and mobile data
     * fails a lookup or a connection for a moment ("Unable to resolve host"): that is not Amazon
     * answering, so the call is repeated after a short pause before giving up.
     */
    private fun send(req: Request): Pair<Int, String> {
        var attempt = 0
        while (true) {
            try {
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string() ?: ""
                    if (resp.code == 401 || resp.code == 403) logRefusal(req, resp, text)
                    return resp.code to text
                }
            } catch (e: java.io.IOException) {
                if (attempt >= 2) throw BridgeException("Amazon Music unreachable: ${e.message ?: e.javaClass.simpleName}")
                Thread.sleep(if (attempt == 0) 2_000 else 5_000)
                attempt++
            }
        }
    }

    // ------------------------------------------------------------------ GraphQL

    /** Logs [message] under [key] at most once every ten minutes. */
    private fun logOnce(key: String, message: String) {
        val now = System.currentTimeMillis()
        if (now - (logged[key] ?: 0L) < 600_000) return
        logged[key] = now
        BridgeNet.log("amazon", message)
    }

    /** One header of the player's last mirrored call, or "" when none was seen. */
    private fun playerHeader(name: String): String = runCatching {
        (parseJson(AmazonSession.headers(ctx) ?: return "") as? JsonObject)?.get(name).str.orEmpty()
    }.getOrDefault("")

    /**
     * What Amazon said when it refused a request, for the diagnostics log: its error type and
     * message, and the names (never the values) of the headers that went with the request, so a
     * missing or extra one shows. Anything that looks like a token is cut out of the body.
     */
    private fun logRefusal(req: Request, resp: okhttp3.Response, body: String) {
        val type = listOf("x-amzn-errortype", "x-amzn-error-type", "www-authenticate", "x-amzn-requestid")
            .mapNotNull { h -> resp.header(h)?.let { "$h=${it.take(80)}" } }
        val sent = req.headers.names().map { it.lowercase() }.sorted()
        noteInvalidCustomer(body + " " + type.joinToString())
        BridgeNet.log("amazon", "${resp.code} from ${req.url.host}${req.url.encodedPath}: ${type.joinToString().ifBlank { "no error header" }}; " +
            "territory sent ${req.header("music-territory") ?: "none"}, player's ${playerHeader("music-territory").ifBlank { "unseen" }}; " +
            "body: ${redact(body).take(500).ifBlank { "empty" }}; sent: ${sent.joinToString(",")}")
    }

    /** One answer: its data, and the error codes GraphQL returned next to it (DUPLICATE_TRACK...). */
    private class Answer(val data: JsonElement?, val codes: List<String>, val messages: List<String>)

    /** The HTTP headers the player sends, rebuilt from the page configuration. */
    private fun headersFromConfig(domain: String): Map<String, String> {
        val c = config(domain)
        if (!c.signedIn) throw BridgeException(
            "Amazon Music served the player to a signed-out visitor: the Amazon session has expired. Accounts, Amazon Music: disconnect and connect again.",
        )
        val built = baseHeaders(domain, c)
        // The account's region as each side sees it: a mismatch is the usual cause of
        // AUTH_INVALID_CUSTOMER_ID. Country codes and field names only, never a value of the session.
        logOnce("region", "domain $domain; territory ${built["music-territory"]} (${when {
            c.raw["musicTerritory"].str != null -> "musicTerritory"
            c.raw["territory"].str != null -> "territory"
            c.raw["marketplaceCountry"].str != null -> "marketplaceCountry"
            MARKETPLACES[c.raw["marketplaceId"].str] != null -> "marketplaceId"
            playerHeader("music-territory").length == 2 -> "the player's header"
            else -> "from the domain"
        }}); marketplace ${c.raw["marketplaceId"].str?.takeIf { it.length in 10..16 && it.all(Char::isLetterOrDigit) } ?: describe(c.raw["marketplaceId"])}; device type ${if (c.deviceType.isBlank()) "missing" else "present"}; " +
            "configuration fields: ${c.fields.joinToString(",").take(600)}")
        if (built["csrf-token"] != null) return built
        // The configuration no longer carries the CSRF triple where it used to (October 2026: sent
        // without it, Amazon answers AUTH_INVALID_CUSTOMER_ID): the player's own values fill it.
        val fromPlayer = listOf("csrf-token", "csrf-rnd", "csrf-ts", "x-amzn-session-id")
            .mapNotNull { k -> playerHeader(k).takeIf { it.isNotBlank() }?.let { k to it } }.toMap()
        logOnce("csrf", "page configuration has no CSRF token (csrf field: ${describe(c.raw["csrf"])}); " +
            if (fromPlayer.isEmpty()) "the player's headers have none either" else "using the player's")
        return built + fromPlayer
    }

    /** What a configuration field holds, by shape and key names only, for the diagnostics log. */
    private fun describe(e: JsonElement?): String = when (e) {
        null -> "absent"
        is JsonObject -> "object with " + e.keys.sorted().joinToString(",")
        is JsonPrimitive -> "a value of ${e.content.length} chars"
        else -> e.javaClass.simpleName
    }

    /** The headers every call carries, from the configuration; empty values left out. */
    private fun baseHeaders(domain: String, c: Config): Map<String, String> {
        val lang = (DOMAINS[domain]?.language ?: "en_US").replace('_', '-')
        return mapOf(
            "x-api-key" to API_KEY,
            "x-amzn-device-id" to c.deviceId,
            "x-amzn-device-type" to c.deviceType,
            "x-amzn-session-id" to c.sessionId,
            "csrf-token" to c.csrfToken,
            "csrf-rnd" to c.csrfRnd,
            "csrf-ts" to c.csrfTs,
            // The account's own region first (an amazon.nl account plays on music.amazon.com, which
            // has no Dutch site: issue #33), then the player's own header, the domain's last.
            "music-territory" to (c.musicTerritory ?: playerHeader("music-territory").takeIf { it.length == 2 } ?: territory(domain)),
            // The player names the subscription with each call; taken from its own headers when seen.
            "x-amzn-client-subscription-tier" to playerHeader("x-amzn-client-subscription-tier"),
            "x-amzn-feature-flags" to FEATURE_FLAGS,
            "content-language" to lang,
            "accept-language" to lang,
        ).filterValues { it.isNotBlank() }
    }

    /**
     * Runs a persisted query. Headers come from the page configuration. When Amazon refuses the
     * session (401/403, or an authentication error) it is renewed the way a browser renews it, one
     * step further each time: the player page is fetched again (new CSRF values, renewed cookies),
     * then the real player is run in a WebView and the call is made with its headers. If Amazon
     * still refuses our HTTP client, the call is sent by the player page itself, and so are the
     * following ones for a while: only when the player is refused too is the session really over.
     */
    private fun gql(operation: String, variables: JsonObject): Answer {
        val domain = domain()
        if (System.currentTimeMillis() < pageModeUntil && AmazonBridge.pageOpen) {
            inPage(domain, operation, variables)?.let { return it }
        }
        val first = runCatching { gqlWith(domain, operation, variables, headersFromConfig(domain)) }
            .recoverCatching { e ->
                if (!refused(e)) throw e
                cachedConfig = null
                gqlWith(domain, operation, variables, headersFromConfig(domain))
            }
        // Only a refused session is renewed: an outage or an unknown query is said as it is, without
        // running the player and sending the call again.
        first.exceptionOrNull()?.let { e -> if (!refused(e) && e.message?.contains("signed-out") != true) throw e }
        val answer = first.getOrNull()
        if (answer != null && !answer.codes.any { it in AUTH_CODES }) return answer
        val reason = first.exceptionOrNull()?.message ?: answer?.codes?.joinToString()
        BridgeNet.log("amazon", "$operation refused ($reason) after reloading the page: running the player")

        // Cached or stored player headers carry the same stale session: the player runs again.
        AmazonBridge.forget()
        val run = runCatching { AmazonBridge.player(ctx, domain, refresh = true) }
        val player = run.getOrNull()
        cachedConfig = null
        // The player's own headers, over the configuration's for whatever it did not send (a mirrored
        // set can lack the API key or the language, and Amazon then answers AUTH_INVALID_CLIENT_ID).
        val headers = player?.takeIf { it.signedIn }?.headers?.filterKeys { it.lowercase() !in SKIPPED_HEADERS }?.let { own ->
            val base = runCatching { baseHeaders(domain, config(domain)) }.getOrDefault(emptyMap())
            base.filterKeys { k -> own.keys.none { it.equals(k, ignoreCase = true) } } + own
        }
        val again = runCatching { gqlWith(domain, operation, variables, headers ?: headersFromConfig(domain)) }
        val last = again.getOrNull()
        if (last != null && !last.codes.any { it in AUTH_CODES }) return last
        val e = again.exceptionOrNull()
        if (e != null && !refused(e) && e.message?.contains("signed-out") != true) throw e

        BridgeNet.log("amazon", "player ${if (player?.signedIn == true) "ran" else "sent no headers"}" +
            (run.exceptionOrNull()?.message?.let { " (${it.take(120)})" } ?: "") +
            // Where the player's calls went instead: the API host Trackhop should follow, if Amazon moved it.
            (player?.otherRequests?.takeIf { it.isNotEmpty() && player?.signedIn != true }?.let { "; its calls went to: ${it.joinToString(", ")}" } ?: "") +
            "; our call with its headers refused: asking the page")
        // Accepted from the browser only? The player page sends the call.
        var pageSaid = run.exceptionOrNull()?.message?.let { "player: ${it.take(120)}" } ?: if (player?.signedIn == true) "player ran" else "the player sent no headers"
        if (player?.signedIn == true) {
            val (code, text) = AmazonBridge.gqlInPage(ctx, domain, body(operation, variables))
            val viaPage = runCatching { parse(code, text, operation) }
            viaPage.getOrNull()?.takeIf { a -> !a.codes.any { it in AUTH_CODES } }?.let {
                BridgeNet.log("amazon", "$operation accepted from the page")
                pageModeUntil = System.currentTimeMillis() + PAGE_MODE_MS
                return it
            }
            pageSaid = "the page's call: " + (viaPage.exceptionOrNull()?.message ?: viaPage.getOrNull()?.codes?.joinToString()).orEmpty().take(120)
        }
        // Refused for the whole account, or only for this playlist? The library says which.
        val playlistId = variables["id"].str
        val library = if (player?.signedIn == true && playlistId != null) libraryInPage(domain) else null
        if (library != null) {
            val (count, ids) = library
            if (playlistId !in ids) throw BridgeException(
                "Amazon Music: this playlist is no longer in your library (the session works: $count playlists listed). " +
                    "It was deleted, or it belongs to someone else: edit the sync and pick another destination.",
            )
            throw BridgeException(
                "Amazon Music accepts the session (library: $count playlists, this one included) but refuses to open this playlist ($reason). " +
                    "Open it once in the Amazon Music app; if it still fails, send a diagnostics report.",
            )
        }
        val dup = AmazonSession.duplicateCookies("https://${gqlHost(domain)}/")
        BridgeNet.log("amazon", "$pageSaid; library refused too; cookies sent twice: ${dup.joinToString().ifBlank { "none" }}")
        AmazonBridge.forget()
        throw BridgeException(
            "Amazon Music no longer accepts the session ($reason; again after renewing it; $pageSaid" +
                (if (player?.signedIn == true && playlistId != null) "; library refused too" else "") + "). " +
                "Accounts, Amazon Music: disconnect and connect again.",
        )
    }

    /** Playlist count and ids of the library, asked through the player page; null when refused. */
    private fun libraryInPage(domain: String): Pair<Int, Set<String>>? = runCatching {
        val (code, text) = AmazonBridge.gqlInPage(ctx, domain, body("AddToPlaylistModalUserPlaylists", jsonObj("firstLimit" to PLAYLISTS_LIMIT)))
        val a = parse(code, text, "playlists")
        if (a.codes.any { it in AUTH_CODES }) return@runCatching null
        val ids = a.data["user"]["playlists"]["edges"].arr.mapNotNull { it["node"]["id"].str }.toSet()
        if (a.data["user"] == null) null else ids.size to ids
    }.getOrNull()

    /** One call through the open player page; null when the page refused it (the caller starts over). */
    private fun inPage(domain: String, operation: String, variables: JsonObject): Answer? {
        val (code, text) = AmazonBridge.gqlInPage(ctx, domain, body(operation, variables))
        val a = runCatching { parse(code, text, operation) }
        a.exceptionOrNull()?.let { if (!refused(it) && code != -1) throw it }
        val answer = a.getOrNull()
        if (answer == null || answer.codes.any { it in AUTH_CODES }) { pageModeUntil = 0; return null }
        pageModeUntil = System.currentTimeMillis() + PAGE_MODE_MS
        return answer
    }

    private fun refused(e: Throwable) = e.message?.let { it.contains(" 401 ") || it.contains(" 403 ") } == true

    private fun body(operation: String, variables: JsonObject): String {
        val hash = HASHES[operation] ?: throw BridgeException("Amazon Music: unknown operation $operation")
        return jsonObj(
            "operationName" to operation,
            "variables" to variables,
            // As the player sends it: Apollo names itself next to the persisted query.
            "extensions" to jsonObj(
                "clientLibrary" to jsonObj("name" to "@apollo/client", "version" to "4.1.6"),
                "persistedQuery" to jsonObj("version" to 1, "sha256Hash" to hash),
            ),
        ).toString()
    }

    /** The API host for [domain]: the one the player was seen using, else `gql.<domain>`. */
    private fun gqlHost(domain: String): String = AmazonSession.gqlHost(ctx)?.takeIf { it.endsWith(domain.removePrefix("music.")) } ?: "gql.$domain"

    private fun gqlWith(domain: String, operation: String, variables: JsonObject, headers: Map<String, String>): Answer {
        val b = Request.Builder()
            .url("https://${gqlHost(domain)}/")
            .post(body(operation, variables).toRequestBody(JSON))
            .header("Cookie", cookies())
        b
            .header("Origin", "https://$domain")
            .header("Referer", "https://$domain/")
            .header("User-Agent", AmazonBridge.storedUserAgent(ctx) ?: BridgeUa.desktop(ctx))
            .header("Accept", "application/graphql-response+json, application/json")
        headers.forEach { (k, v) -> b.header(k, v) }
        val (code, text) = send(b.build())
        return parse(code, text, operation)
    }

    /** An answer of the GraphQL endpoint, from our client or from the player page (-1: no answer). */
    private fun parse(code: Int, text: String, operation: String): Answer {
        if (code == 401 || code == 403) {
            cachedConfig = null
            noteInvalidCustomer(text)
            throw BridgeException("Amazon Music $code on $operation" + redact(text).take(120).let { if (it.isBlank()) "" else ": $it" })
        }
        if (code == -1) throw BridgeException("Amazon Music: $operation not sent (${text.take(160)})")
        val j = runCatching { parseJson(text) }.getOrNull()
            ?: throw BridgeException("Amazon Music $code on $operation: ${text.take(200)}")
        val errors = j["errors"].arr
        val codes = errors.mapNotNull { it["extensions"]["code"].str }
        val messages = errors.mapNotNull { it["message"].str }
        noteInvalidCustomer((codes + messages).joinToString())
        if (codes.any { it == "PERSISTED_QUERY_NOT_FOUND" } || messages.any { it.contains("PersistedQueryNotFound", true) }) {
            throw BridgeException("Amazon Music updated its web player ($operation is no longer known): the connector needs an update.")
        }
        // 294 is how this endpoint answers "done with GraphQL errors"; anything else outside 2xx is a failure.
        if (code !in 200..299 && errors.isEmpty()) throw BridgeException("Amazon Music $code on $operation: ${text.take(200)}")
        return Answer(j["data"], codes, messages)
    }

    private fun Answer.orThrow(operation: String): JsonElement? {
        if (data == null || (codes.isNotEmpty() && data.findFirst("id") == null)) {
            throw BridgeException("Amazon Music $operation: ${(codes + messages).joinToString("; ").take(200).ifBlank { "no answer" }}")
        }
        return data
    }

    // ------------------------------------------------------------------ library

    /** The user's own playlists, as the "add to playlist" dialog lists them. */
    fun libraryPlaylists(): List<PlaylistDto> {
        val d = gql("AddToPlaylistModalUserPlaylists", jsonObj("firstLimit" to PLAYLISTS_LIMIT)).orThrow("playlists")
        val out = d["user"]["playlists"]["edges"].arr.mapNotNull { e ->
            val n = e["node"]
            val id = n["id"].str ?: return@mapNotNull null
            PlaylistDto(id, n["title"].str ?: id, n["trackCount"].long?.toInt() ?: -1)
        }
        libraryCounts = System.currentTimeMillis() to out.filter { it.count >= 0 }.associate { it.id to it.count }
        return out
    }

    /** The playlist's track count as the library listed it a moment ago (a sync lists it first); null when not known. */
    private fun libraryCount(id: String): Int? = libraryCounts
        ?.takeIf { (at, _) -> System.currentTimeMillis() - at < LIBRARY_COUNTS_MS }?.second?.get(id)

    /**
     * Tracks of a playlist, page by page; the entry cursor ("index:uuid") is what removing takes.
     *
     * Every entry with an id is kept, even one Amazon describes only in part (no title, as for a
     * track it no longer offers in the user's country): up to 1.2.7 such an entry was left out, a
     * sync took it for missing and added it on every run, and Amazon answered DUPLICATE_TRACK. When
     * the listing still does not add up (entries without any id, GraphQL errors next to the data,
     * a count other than the library's), the diagnostics log says so.
     */
    fun playlistTracks(id: String): List<TrackDto> {
        val out = ArrayList<TrackDto>()
        var after: String? = null
        var pages = 0
        var untitled = 0
        var noId = 0
        var declared: Int? = null
        val errors = LinkedHashSet<String>()
        while (true) {
            val vars = jsonObj(
                "id" to id, "firstLimit" to PAGE_SIZE, "isAuthenticated" to true, "includeCollaborators" to false,
                "afterCursor" to after,
            )
            val a = gql("playlistDetail", vars)
            val p = a.orThrow("playlist")["playlist"]
            errors += a.codes.ifEmpty { a.messages.map { it.take(60) } }
            if (declared == null) declared = p["trackCount"].long?.toInt()
            val tracks = p["tracks"]
            val edges = tracks["edges"].arr
            for (e in edges) {
                val t = entryOf(e["node"], e["cursor"].str)
                when {
                    t == null -> noId++
                    else -> { if (t.title.isEmpty()) untitled++; out += t }
                }
            }
            val info = tracks["pageInfo"]
            // A page that names no end cursor is followed from its last entry; a full page that does
            // not say whether more follow is followed too, until a page comes back short or empty.
            val next = info["endCursor"].str ?: edges.lastOrNull()["cursor"].str
            val more = info["hasNextPage"].bool ?: (edges.size >= PAGE_SIZE)
            if (!more || edges.isEmpty() || next == null || next == after || ++pages > 400) break
            after = next
        }
        val expected = declared ?: libraryCount(id)
        if (untitled > 0 || noId > 0 || errors.isNotEmpty() || (expected != null && expected != out.size)) {
            BridgeNet.log("amazon", "playlist ${id.takeLast(8)}: ${out.size} entries listed" +
                (expected?.let { ", the library counts $it" } ?: "") +
                (if (untitled > 0) "; $untitled without a title (kept by id)" else "") +
                (if (noId > 0) "; $noId without an id (left out)" else "") +
                (if (errors.isNotEmpty()) "; errors: ${errors.joinToString().take(160)}" else ""))
        }
        return out
    }

    /** Catalogue search, songs only. Track ids are ASINs. */
    fun searchTracks(query: String): List<TrackDto> {
        val vars = jsonObj(
            "searchText" to query,
            "specifications" to listOf(mapOf("label" to SONGS_LABEL, "types" to listOf("track"), "limit" to 25)),
            "isAuthenticated" to true, "audiobooksEnabled" to false, "merchEnabled" to false, "categoryInEntityUnionEnabled" to false,
        )
        val d = gql("SpecSearchPage", vars).orThrow("search")
        val nodes = d["specificationSearch"]["result"].arr.flatMap { r -> r["edges"].arr.map { it["node"] } }
            .filter { it["__typename"].str == "Track" }
        // Amazon lists the same recording once per edition (album, single, deluxe): one row per
        // title and artist is enough, and a longer list lets the right artist show up further down.
        val seen = HashSet<String>()
        return nodes.mapNotNull { trackOf(it, null) }
            .filter { seen.add(it.title.lowercase() + "\u0000" + it.artists.joinToString(",").lowercase()) }
            .take(25)
    }

    /** New playlists are private, as on every other service. */
    fun createPlaylist(name: String): PlaylistDto {
        val d = gql("CreatePlaylist", jsonObj("title" to name, "visibility" to "PRIVATE")).orThrow("create playlist")
        val p = d["createPlaylist"]
        val id = p["id"].str ?: throw BridgeException("Amazon Music: playlist not created")
        return PlaylistDto(id, p["title"].str ?: name, 0)
    }

    /**
     * Appends one track: true when added, false when Amazon says the playlist already holds it
     * (DUPLICATE_TRACK). That is not a failure, but it is not an addition either: the caller tells
     * the sync, which counts the track as present instead of added (its listing may leave it out).
     */
    fun addTrack(playlistId: String, trackId: String): Boolean {
        libraryCounts = null
        val a = gql("addTrackToPlaylist", jsonObj("id" to playlistId, "trackId" to trackId, "rejectDuplicateTracks" to true))
        if (a.codes.any { it == "DUPLICATE_TRACK" }) return false
        if (a.data["appendTracks"]["id"].str == null) {
            throw BridgeException("Amazon Music did not add $trackId: ${(a.codes + a.messages).joinToString("; ").take(160)}")
        }
        return true
    }

    /**
     * Removes the entries of [ids] from a playlist and says which of them it still lists afterwards.
     *
     * Entries go by cursor ("index:uuid", [entryIds] in the same order as [ids]); one without a
     * cursor (an old cache, a cursor from before the GraphQL player) is looked up in the playlist.
     * Highest index first, so no entry's position shifts before its turn.
     *
     * Amazon answers removeTrackFromPlaylist with the playlist's id whether or not an entry went, so
     * that answer alone cannot tell a removal from nothing at all (the "Metal & More" report: "-1" on
     * every run, the entry still there). The playlist is read again: an entry still listed is tried
     * once more with the cursor the fresh listing gives it, in case the first one was stale; what
     * still stays is logged by title with its cursor's index and its position in the listing (an
     * index that does not match the position is a cursor Amazon does not take) and returned by id,
     * so the sync counts it as not removed.
     */
    fun removeEntries(playlistId: String, ids: List<String>, entryIds: List<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val fresh = ids.indices.any { entryIds.getOrNull(it)?.contains(':') != true }
        val byTrack = if (fresh) playlistTracks(playlistId).associate { it.id to (it.setVideoId ?: "") } else emptyMap()
        val cursors = ids.mapIndexedNotNull { i, id -> entryIds.getOrNull(i)?.takeIf { it.contains(':') } ?: byTrack[id]?.takeIf { it.isNotEmpty() } }
        removeByCursors(playlistId, cursors)
        val wanted = ids.toSet()
        var after = playlistTracks(playlistId)
        var stayed = after.filter { it.id in wanted }
        val retry = stayed.mapNotNull { it.setVideoId }.filter { it.contains(':') && it !in cursors }
        if (retry.isNotEmpty()) {
            BridgeNet.log("amazon", "playlist ${playlistId.takeLast(8)}: ${stayed.size} entries still listed after removing them; trying again with the cursors the playlist gives them now")
            removeByCursors(playlistId, retry)
            after = playlistTracks(playlistId)
            stayed = after.filter { it.id in wanted }
        }
        if (stayed.isNotEmpty()) {
            val used = ids.indices.associate { i -> ids[i] to cursors.getOrNull(i) }
            BridgeNet.log("amazon", "playlist ${playlistId.takeLast(8)}: removing answered, but ${stayed.size} of ${ids.size} entries still listed: " +
                stayed.take(10).joinToString("; ") { t ->
                    "${describeEntry(t)} (removed with cursor index ${used[t.id]?.substringBefore(':') ?: "none"}, now cursor index ${t.setVideoId?.substringBefore(':') ?: "none"} at position ${after.indexOf(t)})"
                })
        }
        return stayed.map { it.id }.toSet()
    }

    private fun removeByCursors(playlistId: String, cursors: List<String>) {
        cursors.sortedByDescending { it.substringBefore(':').toIntOrNull() ?: 0 }
            .forEach { removeTrack(playlistId, it); Thread.sleep(removePauseMs) }
    }

    /** An entry as the log names it: artist and title, or that it came without a title. */
    private fun describeEntry(t: TrackDto): String =
        if (t.title.isBlank()) "an entry listed without a title" else (t.artists.take(1) + t.title).joinToString(" – ")

    /** Removes one entry by its cursor ("index:uuid"), as read from [playlistTracks]. */
    fun removeTrack(playlistId: String, cursor: String) {
        libraryCounts = null
        val a = gql("removeTrackFromPlaylist", jsonObj("id" to playlistId, "cursor" to cursor))
        if (a.data["removeTracks"]["id"].str == null) {
            throw BridgeException("Amazon Music did not remove the track: ${(a.codes + a.messages).joinToString("; ").take(160)}")
        }
    }

    fun removePlaylist(playlistId: String) {
        val a = gql("deletePlaylist", jsonObj("id" to playlistId))
        if (a.data["deletePlaylist"]["isDeleted"].bool != true) throw BridgeException("Amazon Music did not delete the playlist")
    }

    // ------------------------------------------------------------------ parsing

    /**
     * One entry of playlistDetail. Usually a Track node like the search's; an entry Amazon describes
     * only in part (no title, as for a track no longer offered in the user's country) is still in the
     * playlist, and Amazon refuses to add it again, so it is kept with its id and an empty title: a
     * sync finds it by id, and never matches anything to it by name. An entry of another shape, the
     * track inside it, is read from inside. Null only when the entry carries no id at all.
     */
    private fun entryOf(n: JsonElement?, cursor: String?): TrackDto? {
        if (n !is JsonObject) return null
        trackOf(n, cursor)?.let { return it }
        val inner = ENTRY_WRAPPERS.firstNotNullOfOrNull { k -> (n[k] as? JsonObject)?.takeIf { idOf(it) != null } }
        return if (inner != null) trackOf(inner, cursor) ?: trackOf(inner, cursor, requireTitle = false)
        else trackOf(n, cursor, requireTitle = false)
    }

    private fun idOf(n: JsonElement?): String? = n["id"].str ?: n["asin"].str

    /** A Track node of playlistDetail or of the search; without a title only when [requireTitle] is false. */
    private fun trackOf(n: JsonElement?, cursor: String?, requireTitle: Boolean = true): TrackDto? {
        if (n !is JsonObject) return null
        val id = idOf(n) ?: return null
        val title = n["shortTitle"].str ?: n["title"].str ?: n["name"].str ?: if (requireTitle) return null else ""
        val edges = n["contributingArtists"]["edges"].arr
        val artists = (edges.filter { it["role"].str == "PRIMARY" } + edges.filter { it["role"].str != "PRIMARY" })
            .mapNotNull { it["node"]["name"].str }.distinct()
        return TrackDto(
            id = id, title = title, artists = artists,
            album = n["album"]["shortTitle"].str ?: n["album"]["title"].str ?: "",
            durationMs = (n["duration"].long ?: 0) * 1000,
            setVideoId = cursor,
            explicit = n["parentalSettings"]["hasExplicitLanguage"].bool,
        )
    }

    class Region(val region: String, val language: String)

    companion object {
        /** Where the signed-in player inlines its configuration in the page. */
        private const val APP_CONFIG = "amznMusic.appConfig"
        /** The web player's application key, sent with every GraphQL call (the same for every user). */
        private const val API_KEY = "amzn1.application.4ff5579ca2e3407aba989a1f5dbdaf69"
        private const val FEATURE_FLAGS = "api-cache,use-ups"
        private const val SONGS_LABEL = "dmusic_find_song_results_label"
        private const val PLAYLISTS_LIMIT = 500
        /** Entries per page of playlistDetail, as the player asks for them. */
        private const val PAGE_SIZE = 100
        /** Where an entry of another shape keeps its track. */
        private val ENTRY_WRAPPERS = listOf("track", "item", "entity", "content")
        /** Track counts of the library's playlists, by id, from the last listing: checked against a playlist's entries. */
        @Volatile private var libraryCounts: Pair<Long, Map<String, Int>>? = null
        private const val LIBRARY_COUNTS_MS = 5 * 60_000L
        /** Between two removals, as the player spaces them. */
        @Volatile var removePauseMs = 150L
        private val JSON = "application/json".toMediaType()
        /** GraphQL error codes that mean "these credentials are not accepted". */
        private val AUTH_CODES = setOf("UNAUTHENTICATED", "UNAUTHORIZED", "FORBIDDEN", "INVALID_CSRF", "CSRF_VALIDATION_FAILED")
        /** Mirrored headers the request sets itself. */
        private val SKIPPED_HEADERS = setOf("cookie", "content-length", "host", "origin", "referer", "user-agent", "accept", "content-type")

        /** Persisted queries of the web player (October 2026), by operation name. */
        private val HASHES = mapOf(
            "AddToPlaylistModalUserPlaylists" to "8a2d8eb88470373f0191fdac28083d74233a31c769cefb4c2da22ae8e9706cd9",
            "playlistDetail" to "797157fe477bc056a1ee78bd0c505ae089ee358ca163df66d4f13825744cf109",
            "SpecSearchPage" to "87bf53f123f26696e4db91cc88f2bcbd2ff941986db1506081a30e71155537cf",
            "CreatePlaylist" to "5d91097d90465b2ca7b9ba52a96449ff1993750b76373a470663ce210ee1f829",
            "addTrackToPlaylist" to "b812f642b8a343680492dca706aefa5bf9e4c82bb800bf7031e09c55495aef8e",
            "removeTrackFromPlaylist" to "3c7d125e1c584c70c80d97367054aebff7f2866944297d54cc3dc9d4822e0d26",
            "deletePlaylist" to "abb52606395c74450ce2db367b2c2bf5106ff6077a50f529ba46b8aee6ff6ce3",
        )

        @Volatile private var cachedConfig: Pair<Long, Config>? = null
        /** Until when calls go straight through the player page, after it was the only way in. */
        @Volatile private var pageModeUntil = 0L
        private val logged = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private const val PAGE_MODE_MS = 30 * 60_000L
        fun forgetConfig() { cachedConfig = null; libraryCounts = null }

        /** When Amazon last refused a call as AUTH_INVALID_CUSTOMER_ID: [AmazonRoute] may then try the skill API. */
        @Volatile private var invalidCustomerAt = 0L
        private fun noteInvalidCustomer(text: String) {
            if (text.contains("AUTH_INVALID_CUSTOMER_ID")) invalidCustomerAt = System.currentTimeMillis()
        }
        fun invalidCustomerSince(at: Long): Boolean = invalidCustomerAt >= at

        /** Regional web player domains, with the language the player sends. */
        val DOMAINS: Map<String, Region> = mapOf(
            "music.amazon.com" to Region("NA", "en_US"),
            "music.amazon.com.mx" to Region("NA", "es_MX"),
            "music.amazon.com.br" to Region("NA", "pt_BR"),
            "music.amazon.ca" to Region("NA", "en_CA"),
            "music.amazon.co.uk" to Region("EU", "en_GB"),
            "music.amazon.de" to Region("EU", "de_DE"),
            "music.amazon.fr" to Region("EU", "fr_FR"),
            "music.amazon.it" to Region("EU", "it_IT"),
            "music.amazon.es" to Region("EU", "es_ES"),
            "music.amazon.nl" to Region("EU", "nl_NL"),
            "music.amazon.se" to Region("EU", "sv_SE"),
            "music.amazon.pl" to Region("EU", "pl_PL"),
            "music.amazon.in" to Region("EU", "en_IN"),
            "music.amazon.sa" to Region("EU", "ar_SA"),
            "music.amazon.ae" to Region("EU", "ar_AE"),
            "music.amazon.co.jp" to Region("FE", "ja_JP"),
            "music.amazon.com.au" to Region("FE", "en_AU"),
        )

        /**
         * Amazon's public marketplace ids and their countries: the store an account belongs to,
         * which the player's configuration names even on a domain of another country.
         */
        val MARKETPLACES: Map<String?, String> = mapOf(
            "ATVPDKIKX0DER" to "US", "A2EUQ1WTGCTBG2" to "CA", "A1AM78C64UM0Y8" to "MX", "A2Q3Y263D00KWC" to "BR",
            "A1F83G8C2ARO7P" to "GB", "A1PA6795UKMFR9" to "DE", "A13V1IB3VIYZZH" to "FR", "APJ6JRA9NG5V4" to "IT",
            "A1RKKUPIHCS9HS" to "ES", "A1805IZSGTT6HS" to "NL", "A2NODRKZP88ZB9" to "SE", "A1C3SOZRARQ6R3" to "PL",
            "AMEN7PMS3EDWL" to "BE", "A33AVAJ2PDY3EV" to "TR", "A21TJRUUN4KGV" to "IN", "A17E79C6D8DWNP" to "SA",
            "A2VIGQ35RCS4UG" to "AE", "A1VC38T7YXB528" to "JP", "A39IBJ37TRP1C6" to "AU",
        )

        /** A host the player's API calls may go to: "gql", in any form, in front of a known player domain. */
        fun isGqlHost(host: String): Boolean {
            val h = host.lowercase()
            val at = h.indexOf(".music.amazon.")
            if (at <= 0 || h.substring(at + 1) !in DOMAINS) return false
            val front = h.substring(0, at)
            return front.contains("gql") && front.all { it.isLetterOrDigit() || it == '.' || it == '-' }
        }

        /** The territory header ("IT", "GB"...): the country part of the domain's language. */
        fun territory(domain: String): String = DOMAINS[domain]?.language?.substringAfter('_') ?: "US"

        fun isMusicDomain(host: String?): Boolean = host != null && (host in DOMAINS || host.startsWith("music.amazon."))
    }
}

private val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

private fun JsonElement?.asText(): String = when (this) {
    is JsonPrimitive -> content
    null -> ""
    else -> toString()
}

/** Tokens, JWTs and long opaque values cut out of a text that goes to the diagnostics log. */
private fun redact(text: String): String = text
    .replace(Regex("""(Atn[a-z]?|Atza)\|[A-Za-z0-9_\-|.=+/]+"""), "[token]")
    .replace(Regex("""eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]*"""), "[jwt]")
    .replace(Regex("""[A-Za-z0-9+/=_\-]{40,}"""), "[...]")
    .replace('\n', ' ')
