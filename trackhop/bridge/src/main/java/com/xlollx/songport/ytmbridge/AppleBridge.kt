package com.xlollx.songport.ytmbridge

import android.content.Context
import android.util.Base64
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import com.xlollx.songport.ytmbridge.BridgeNet.withHook
import okhttp3.Request
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Apple Music through the web player session, without an Apple Developer Program membership.
 *
 * Two tokens are needed by the MusicKit API: a developer token and a music user token. The web
 * player at music.apple.com ships Apple's own developer token inside its JavaScript, and after the
 * user signs in it keeps the music user token in the `media-user-token` cookie. The Bridge reads
 * both and hands them to Trackhop, which then uses its normal Apple Music code.
 */
object AppleBridge {
    /** The service's name in its sessions' storage and in the WebView profiles of its extra accounts. */
    const val PROFILE = "apple"
    val session = WebSession(PROFILE, listOf("music.apple.com", "idmsa.apple.com", "apple.com"))
    const val HOME = "https://music.apple.com/"

    /** The session of one account: the primary's, or an extra slot's own (see [Slots]). */
    fun session(slot: String): WebSession = session.forSlot(slot)
    const val API = "https://api.music.apple.com"
    /** The player's own backend: the only one that takes the player's token. */
    const val AMP = "https://amp-api.music.apple.com"

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).withHook().build()
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    fun signedIn(cookies: String?): Boolean = userToken(cookies) != null

    /** The music user token, URL-decoded when the cookie stores it encoded. */
    fun userToken(cookies: String?): String? {
        val raw = WebSession.cookieValue(cookies, "media-user-token") ?: return null
        return if (raw.contains('%')) runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw) else raw
    }

    /**
     * Apple's web developer token. The sign-in screen captures it from the player itself (the
     * `Authorization` header of its API calls, or MusicKit's instance), which is the reliable source;
     * scraping the player's JavaScript is the fallback for a session saved before that existed. A
     * token past its expiry is still handed over when nothing better is found: the player's own
     * lasts months, and a stale one fails with a clear 401 rather than with "no token".
     */
    @Synchronized
    fun developerToken(ctx: Context, slot: String = Slots.PRIMARY): String {
        val session = session(slot)
        val stored = session.get(ctx, "devToken")
        if (stored != null) {
            val exp = session.get(ctx, "devTokenExp")?.toLongOrNull() ?: 0
            if (exp - 3_600_000 > System.currentTimeMillis()) return stored
        }
        // The scrape is quick when it works; when it failed last time (within a week) the off-screen
        // player goes first, since the bundles probably still hide the token.
        val scrapeFailedAt = session.get(ctx, "scrapeFailedAt")?.toLongOrNull() ?: 0
        val webFirst = scrapeFailedAt > System.currentTimeMillis() - 7L * 24 * 3_600_000
        if (webFirst) viaWebView(ctx, slot)?.let { return keep(ctx, it, slot) }
        val scraped = runCatching { scrape(ctx, slot) }
        scraped.getOrNull()?.let { session.put(ctx, "scrapeFailedAt", null); return it }
        session.put(ctx, "scrapeFailedAt", System.currentTimeMillis().toString())
        // The player's own API calls carry the token: load it off screen and take it from there.
        if (!webFirst) viaWebView(ctx, slot)?.let { return keep(ctx, it, slot) }
        return stored ?: throw BridgeException("${scraped.exceptionOrNull()?.message ?: "Apple Music: developer token not found"}. Disconnect Apple Music and sign in again: the sign-in screen takes the token from the player itself")
    }

    private const val TOKEN_JS = "(function(){try{return MusicKit.getInstance().developerToken||''}catch(e){return ''}})()"

    /**
     * Loads music.apple.com in an off-screen WebView on the main thread and waits, at most 25 s, for
     * the token to show up: in the `Authorization` header of a call the player makes, or in MusicKit's
     * instance. Gives up at once when the main thread is busy (a caller blocking it would deadlock).
     */
    private fun viaWebView(ctx: Context, slot: String): String? {
        val app = ctx.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        if (android.os.Looper.getMainLooper().isCurrentThread) return null
        val started = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        val found = java.util.concurrent.atomic.AtomicReference<String?>(null)
        var web: android.webkit.WebView? = null
        fun offer(raw: String?) {
            val tok = raw?.trim()?.removePrefix("Bearer ")?.trim('"', ' ') ?: return
            if (isJwt(tok) && found.compareAndSet(null, tok)) done.countDown()
        }
        main.post {
            runCatching {
                val w = android.webkit.WebView(app)
                web = w
                // An extra account's player runs in that account's profile: set before anything loads.
                Slots.apply(w, PROFILE, slot)
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.webViewClient = object : SafeWebViewClient() {
                    override fun shouldInterceptRequest(view: android.webkit.WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                        if (request.url.host?.endsWith("music.apple.com") == true) offer(request.requestHeaders?.get("Authorization"))
                        return null
                    }
                    override fun onPageFinished(view: android.webkit.WebView, url: String) { poll(view, 0) }
                    private fun poll(view: android.webkit.WebView, n: Int) {
                        if (found.get() != null || n > 20) return
                        view.evaluateJavascript(TOKEN_JS) { v -> offer(v); if (found.get() == null) main.postDelayed({ poll(view, n + 1) }, 1000) }
                    }
                }
                w.loadUrl(HOME)
            }.onFailure { done.countDown() }
            started.countDown()
        }
        if (!started.await(3, TimeUnit.SECONDS)) return null
        done.await(25, TimeUnit.SECONDS)
        main.post { runCatching { web?.stopLoading(); web?.destroy() } }
        return found.get()
    }

    /** Keeps a developer token seen in the player: from the login screen's own WebView. */
    fun rememberDeveloperToken(ctx: Context, jwt: String?, slot: String = Slots.PRIMARY): Boolean {
        val tok = jwt?.trim()?.removePrefix("Bearer ")?.trim() ?: return false
        if (!isJwt(tok)) return false
        val session = session(slot)
        if (session.get(ctx, "devToken") == tok) return true
        val exp = jwtExpiry(tok) ?: (System.currentTimeMillis() + 30L * 24 * 3_600_000)
        session.put(ctx, "devToken", tok)
        session.put(ctx, "devTokenExp", exp.toString())
        return true
    }

    /**
     * Any JWT, whatever the order of the header's fields: Apple's token used to start with
     * `eyJhbGci` ({"alg" first) and now starts with `eyJ0eXAi` ({"typ" first). A match is taken only
     * when its header decodes to JSON naming an algorithm, which rules out other base64 strings.
     */
    private val JWT = Regex("""eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}""")

    /** A JWT with an algorithm in its header and, when it says so, an expiry still ahead. */
    private fun isJwt(tok: String): Boolean = JWT.matches(tok) && runCatching {
        val header = String(Base64.decode(tok.substringBefore('.'), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        (parseJson(header) as? JsonObject)?.get("alg").str != null && (jwtExpiry(tok)?.let { it > System.currentTimeMillis() } ?: true)
    }.getOrDefault(false)

    private fun findJwt(text: String): String? = JWT.findAll(text).map { it.value }.firstOrNull { isJwt(it) }

    private fun scrape(ctx: Context, slot: String): String = runCatching { scrapePage(ctx, HOME, slot) }
        .recoverCatching { scrapePage(ctx, "https://music.apple.com/us/browse", slot) }
        .getOrThrow()

    private fun scrapePage(ctx: Context, page: String, slot: String): String {
        val html = get(page)
        // The token has been in the main bundle (/assets/index-*.js); take any same-origin script in
        // page order, main bundle first, and the page itself in case it moves inline.
        findJwt(html)?.let { return keep(ctx, it, slot) }
        val scripts = Regex("""(?:src|href)="((?:https://music\.apple\.com)?/assets/[^"]+\.js)"""").findAll(html)
            .map { it.groupValues[1].removePrefix("https://music.apple.com") }.distinct().toList()
            .sortedBy { if (it.contains("/index")) 0 else 1 }
        if (scripts.isEmpty()) throw BridgeException("Apple Music: player script not found")
        for (path in scripts) {
            val js = runCatching { get("https://music.apple.com$path") }.getOrNull() ?: continue
            findJwt(js)?.let { return keep(ctx, it, slot) }
        }
        throw BridgeException("Apple Music: developer token not found in the player script")
    }

    private fun keep(ctx: Context, jwt: String, slot: String): String {
        val exp = jwtExpiry(jwt) ?: (System.currentTimeMillis() + 7L * 24 * 3_600_000)
        val session = session(slot)
        session.put(ctx, "devToken", jwt)
        session.put(ctx, "devTokenExp", exp.toString())
        return jwt
    }

    /** Storefront of the signed-in account (it, de, us...), fetched once. */
    fun storefront(ctx: Context, slot: String = Slots.PRIMARY): String? {
        val session = session(slot)
        session.get(ctx, "storefront")?.let { return it }
        val user = userToken(session.cookies(ctx)) ?: return null
        val req = Request.Builder().url("$AMP/v1/me/storefront")
            .header("Authorization", "Bearer ${developerToken(ctx, slot)}")
            .header("Music-User-Token", user)
            .header("media-user-token", user)
            .header("Origin", "https://music.apple.com")
            .header("Referer", "https://music.apple.com/")
            .header("User-Agent", UA)
            .build()
        http.newCall(req).execute().use { resp ->
            val j = parseJson(resp.body?.string() ?: "") as? JsonObject ?: return null
            val sf = j["data"][0]["id"].str ?: return null
            session.put(ctx, "storefront", sf)
            return sf
        }
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "*/*").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw BridgeException("Apple Music ${resp.code} for $url")
            return resp.body?.string() ?: ""
        }
    }

    private fun jwtExpiry(jwt: String): Long? = runCatching {
        val payload = jwt.split('.')[1]
        val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        (parseJson(json) as? JsonObject)?.get("exp").long?.times(1000)
    }.getOrNull()
}
