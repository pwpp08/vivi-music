package com.xlollx.songport.ytmbridge

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * SoundCloud through the web app's session. The user signs in on SoundCloud's site inside a WebView
 * (on a phone, the WebView's own mobile identity gets the mobile site, m.soundcloud.com). What is
 * kept (encrypted in [session], never handed to Trackhop) is the account's OAuth token, the web
 * app's `client_id` and the cookie jar (for DataDome's cookie).
 *
 * The token is the bearer the web app itself sends as `Authorization: OAuth <token>` to SoundCloud's
 * API hosts. The sign-in takes it, in this order, from:
 * 1. that header, on the requests the signed-in page makes by itself (observed, never changed);
 * 2. the `oauth_token` cookie of soundcloud.com, m.soundcloud.com or secure.soundcloud.com;
 * 3. the page's localStorage, read once the page is signed in (see [STORAGE_SCRIPT]).
 * The desktop site keeps it in the cookie of soundcloud.com, the only place 1.3.1 looked; the
 * mobile site's API calls go to api-mobi.soundcloud.com rather than api-v2.
 */
object SoundCloudBridge {
    val session = WebSession("soundcloud", listOf("soundcloud.com", "secure.soundcloud.com", "api-auth.soundcloud.com", "m.soundcloud.com"))
    const val SITE = "https://soundcloud.com"
    const val MOBILE_SITE = "https://m.soundcloud.com"
    const val LOGIN_URL = "https://soundcloud.com/signin"
    const val API_V2 = "api-v2.soundcloud.com"
    const val API_MOBI = "api-mobi.soundcloud.com"

    /** The hosts whose requests carry the web app's client id and, signed in, its token. */
    val API_HOSTS = setOf(API_V2, API_MOBI, "api.soundcloud.com")

    /** Every site whose cookie jar may hold `oauth_token`, the desktop one first. */
    val COOKIE_SITES = listOf(SITE, MOBILE_SITE, "https://secure.soundcloud.com")

    fun oauthToken(cookies: String?): String? = WebSession.cookieValue(cookies, "oauth_token")
    fun signedIn(cookies: String?): Boolean = oauthToken(cookies) != null

    // ---- where the session came from

    /** A token and where it was found (`header:<host>`, `cookie:<host>`, `localStorage:<key>`): the place is logged, the token never. */
    class Found(val token: String, val source: String) {
        override fun toString() = "Found(source=$source)"
    }

    /**
     * The session in order of preference: the token the page sends, the cookie of any SoundCloud
     * host, then the one read from localStorage. [cookies] maps a site of [COOKIE_SITES] to its jar.
     */
    fun pick(header: Found?, cookies: Map<String, String?>, storage: Found?): Found? {
        header?.let { return it }
        for (site in COOKIE_SITES) {
            oauthToken(cookies[site])?.let { return Found(it, "cookie:${site.substringAfter("://")}") }
        }
        return storage
    }

    /** The jars of several hosts as one, each cookie once (the first host's wins): what the session keeps. */
    fun mergeCookies(jars: List<String?>): String {
        val out = LinkedHashMap<String, String>()
        jars.filterNotNull().flatMap { it.split(';') }.map { it.trim() }.filter { '=' in it }.forEach { c ->
            val name = c.substringBefore('=').trim()
            if (name.isNotEmpty() && name !in out) out[name] = c
        }
        return out.values.joinToString("; ")
    }

    /** The names of the cookies in a jar, for the diagnostics: never their values. */
    fun cookieNames(cookies: String?): List<String> =
        cookies?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }.orEmpty()

    // ---- the Authorization header and the client id the web app sends

    fun isApiHost(host: String?) = host != null && host in API_HOSTS

    private val TOKEN_CHARS = Regex("""[A-Za-z0-9._~+/=-]{8,}""")

    /**
     * The token of an `Authorization: OAuth <token>` value; null for any other scheme, an empty one,
     * or a signed-out page's placeholder ("OAuth undefined", "OAuth null"): a token has a digit.
     */
    fun tokenOfAuthorization(value: String?): String? {
        val v = value?.trim() ?: return null
        if (' ' !in v) return null
        if (!v.substringBefore(' ').equals("OAuth", ignoreCase = true)) return null
        return v.substringAfter(' ').trim().takeIf { TOKEN_CHARS.matches(it) && it.any(Char::isDigit) }
    }

    /** The scheme of an Authorization value ("OAuth", "Bearer"…), for the diagnostics: never what follows it. */
    fun schemeOf(value: String?): String? =
        value?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() && it.length <= 20 && it.all(Char::isLetter) } ?: value?.let { "other" }

    private val CLIENT_ID_PARAM = Regex("""[?&]client_id=([A-Za-z0-9]{32})(?:&|#|$)""")

    /** The client id an API address carries, or null. */
    fun clientIdOf(url: String): String? = CLIENT_ID_PARAM.find(url)?.groupValues?.get(1)

    /** The client id of a request to one of SoundCloud's API hosts ([API_HOSTS]); null for any other address. */
    fun apiClientId(url: String): String? = if (isApiHost(url.toHttpUrlOrNull()?.host)) clientIdOf(url) else null

    /**
     * Keeps the client id of a request the web app made (from the sign-in WebView). The mobile site's
     * id (api-mobi) is also kept apart, for the calls that go there, and stands in for the other
     * until one is seen.
     */
    fun rememberClientId(ctx: Context, url: String) {
        val id = apiClientId(url) ?: return
        val mobi = url.toHttpUrlOrNull()?.host == API_MOBI
        if (mobi) {
            if (session.get(ctx, "clientIdMobi") != id) session.put(ctx, "clientIdMobi", id)
            if (session.get(ctx, "clientId") != null) return
        }
        if (session.get(ctx, "clientId") != id) session.put(ctx, "clientId", id)
    }

    // ---- localStorage, the last resort

    /**
     * Read once the page is signed in, never while the sign-in form is up, and never injected at
     * document start: a single `evaluateJavascript` on the main frame, which only reads. It returns
     * every key, and a value only for the entries that may hold a token (a key or a value naming
     * oauth/token/auth/session, or a value shaped like SoundCloud's tokens); the others come back null.
     */
    const val STORAGE_SCRIPT = """(function(){try{var s=window.localStorage,o={};for(var i=0;i<s.length;i++){var k=s.key(i),v=s.getItem(k);o[k]=(/oauth|token|auth|session/i.test(k)||/oauth|token|\d-\d{3,}-\d+-[A-Za-z0-9]{4,}/i.test(v||''))?String(v).slice(0,16384):null;}return JSON.stringify(o);}catch(e){return '';}})()"""

    /** SoundCloud's user tokens look like `2-290000-123456789-AbCdEfGh`. */
    private val SC_TOKEN = Regex("""^\d-\d{3,}-\d+-[A-Za-z0-9]{4,}$""")
    private val TOKEN_FIELD = Regex("""^(oauth_?token|access_?token|oauth)$""", RegexOption.IGNORE_CASE)

    private fun namesToken(key: String) = key.contains("oauth", true) || key.contains("token", true)

    /** The dump [STORAGE_SCRIPT] returns, as evaluateJavascript hands it over (a JSON string of the JSON object), or the object itself. */
    private fun storageObject(raw: String?): JsonObject? {
        val first = runCatching { parseJson(raw ?: return null) }.getOrNull() ?: return null
        if (first is JsonObject) return first
        val inner = (first as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return runCatching { parseJson(inner) as? JsonObject }.getOrNull()
    }

    /** The localStorage keys in a dump, for the diagnostics: names only. */
    fun storageKeys(raw: String?): List<String> = storageObject(raw)?.keys?.toList().orEmpty()

    /**
     * The token in a localStorage dump. First a value shaped like SoundCloud's tokens under a key,
     * or a field of a stored JSON object, naming oauth/token; else any plausible value under a key
     * or field named `oauth_token`/`access_token`. Null when there is none.
     */
    fun tokenFromStorage(raw: String?): Found? {
        val dump = storageObject(raw) ?: return null
        var loose: Found? = null
        for ((key, v) in dump) {
            val value = (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: continue
            if (namesToken(key) && SC_TOKEN.matches(value)) return Found(value, "localStorage:$key")
            val inner = runCatching { parseJson(value) }.getOrNull()
            if (inner is JsonObject || inner is JsonArray) {
                fieldToken(inner, strict = true)?.let { return Found(it, "localStorage:$key") }
                if (loose == null) fieldToken(inner, strict = false)?.let { loose = Found(it, "localStorage:$key") }
            } else if (loose == null && TOKEN_FIELD.matches(key)) {
                val s = (inner as? JsonPrimitive)?.takeIf { it.isString }?.content ?: value
                if (TOKEN_CHARS.matches(s)) loose = Found(s, "localStorage:$key")
            }
        }
        return loose
    }

    /** strict: a token-shaped string under a field naming oauth/token; loose: any plausible string under oauth_token/access_token. */
    private fun fieldToken(e: JsonElement, strict: Boolean): String? = when (e) {
        is JsonObject -> e.entries.firstNotNullOfOrNull { (k, v) ->
            val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            when {
                s != null && strict && namesToken(k) && SC_TOKEN.matches(s) -> s
                s != null && !strict && TOKEN_FIELD.matches(k) && TOKEN_CHARS.matches(s) -> s
                v is JsonObject || v is JsonArray -> fieldToken(v, strict)
                else -> null
            }
        }
        is JsonArray -> e.firstNotNullOfOrNull { fieldToken(it, strict) }
        else -> null
    }

    /**
     * Whether the page has left the sign-in: a SoundCloud page (desktop or mobile site) that is not
     * the sign-in form. secure.soundcloud.com hosts the form itself, and any other host (Google,
     * Facebook, Apple ID) is a sign-in in progress.
     */
    fun looksSignedIn(url: String?): Boolean {
        val u = url?.toHttpUrlOrNull() ?: return false
        if (u.host != "soundcloud.com" && u.host != "m.soundcloud.com") return false
        val p = u.encodedPath.lowercase()
        return listOf("/signin", "/login", "/signup", "/register", "/connect", "/oauth", "/web-auth").none { p.startsWith(it) }
    }

    /**
     * An address that would hand the person over to SoundCloud's app or an app store ("Listen in
     * app", "Open in app", a smart banner): it must not take them out of the sign-in. Any scheme
     * other than http(s) (intent:, market:, soundcloud:), the stores, and the deep-link services.
     */
    fun isAppHandoff(url: String): Boolean {
        val scheme = url.substringBefore(':').lowercase()
        if (scheme != "http" && scheme != "https") return true
        val host = url.toHttpUrlOrNull()?.host ?: return false
        fun under(d: String) = host == d || host.endsWith(".$d")
        return host == "play.google.com" || host == "market.android.com" || under("apps.apple.com") || under("itunes.apple.com") ||
            under("app.goo.gl") || under("page.link") || under("app.link") || under("onelink.me")
    }

    // ---- the session kept

    /** Keeps the token the sign-in found and where; the API host is found again for a new token. */
    fun rememberToken(ctx: Context, found: Found) {
        rememberRenewedToken(ctx, found)
        session.put(ctx, "apiHost", null)
    }

    /**
     * Keeps a token a renewal found ([SoundCloudRenewal]), and when: the same account's token, so the
     * API host that took the old one stays.
     */
    fun rememberRenewedToken(ctx: Context, found: Found) {
        session.put(ctx, "oauthToken", found.token)
        session.put(ctx, "tokenSource", found.source)
        session.put(ctx, "tokenAt", System.currentTimeMillis().toString())
    }

    /** When the kept token was obtained (epoch ms); null for a session from before this was kept. */
    fun tokenAt(ctx: Context): Long? = session.get(ctx, "tokenAt")?.toLongOrNull()

    /** The account's token: the one the sign-in kept, else (a session from 1.3.1 or before) the cookie. */
    fun token(ctx: Context): String? = session.get(ctx, "oauthToken") ?: oauthToken(session.cookies(ctx))

    /**
     * Sign-out: the sites' web storage goes with their cookies ([WebSession.clear]), so a mobile site
     * that kept its session in localStorage does not sign the next sign-in straight back in.
     */
    fun forgetWebStorage() {
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { android.webkit.WebStorage.getInstance().let { ws -> COOKIE_SITES.forEach(ws::deleteOrigin) } }
            }
        }
    }

    /** Where the token was found (see [Found.source]); null for a session from before this was kept. */
    fun tokenSource(ctx: Context): String? = session.get(ctx, "tokenSource")

    /**
     * The sign-in runs as the WebView itself (its own user agent, no desktop disguise, no scripts):
     * soundcloud.com sits behind DataDome, which blocked the disguised WebView, whose user agent and
     * client hints said desktop Chrome while everything else about it said Android. The API calls
     * afterwards carry the user agent the sign-in ran with, so the two never disagree.
     */
    fun rememberUserAgent(ctx: Context, ua: String?) { session.put(ctx, "userAgent", ua?.takeIf { it.isNotBlank() }) }

    /** The user agent of the sign-in; a session from before it was kept ran as desktop Chrome. */
    fun userAgent(ctx: Context): String = session.get(ctx, "userAgent") ?: BridgeUa.desktop(ctx)

    /** DataDome's cookie from the sign-in, which says this browser passed its checks. */
    fun dataDomeCookie(cookies: String?): String? = WebSession.cookieValue(cookies, "datadome")

    /** What the person reads when DataDome refuses: no app named, the same text in every build. */
    const val BLOCKED = "SoundCloud blocked these requests as unusual activity: wait a while (an hour or more), then sign out of SoundCloud and sign in again, preferably on Wi-Fi"

    /** The account's name, read once after sign-in. Blocking; never on the main thread. */
    fun accountName(ctx: Context): String? = SoundCloudWebClient(ctx.applicationContext).me(refresh = true).second
}
