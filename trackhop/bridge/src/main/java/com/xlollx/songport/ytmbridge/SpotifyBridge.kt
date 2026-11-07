package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import com.xlollx.songport.ytmbridge.BridgeNet.withHook
import okhttp3.Request
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spotify through the web player session.
 *
 * The user signs in once on accounts.spotify.com inside a WebView. Whenever Trackhop needs to talk to
 * Spotify, the Bridge loads open.spotify.com in a hidden WebView and captures the access token the
 * web player itself requests at start-up (`/api/token`). That token is a first-party token that the
 * public Web API accepts, so Trackhop reuses its normal Spotify code; it lasts about an hour and is
 * renewed the same way. Letting the real web player fetch the token keeps this working when Spotify
 * changes the anti-abuse parameters of that endpoint, because the page computes them.
 *
 * Three ways to get hold of it, cheapest first: the page's own HTML carries the session as a
 * `<script id="session">` block, so a plain GET with the cookies is often enough; otherwise the
 * hidden WebView loads the player, and the token request is seen both at the network level
 * ([WebViewClient.shouldInterceptRequest], which also covers requests made from workers) and by
 * a hook on fetch/XMLHttpRequest inside the page.
 */
object SpotifyBridge {
    /** The service's name in its sessions' storage and in the WebView profiles of its extra accounts. */
    const val PROFILE = "spotify"
    val session = WebSession(PROFILE, listOf("open.spotify.com", "accounts.spotify.com", "www.spotify.com", "spotify.com"))
    const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
    /** Desktop Chrome: the web player refuses any user agent marked as a WebView ("wv"). */
    const val USER_AGENT = BridgeUa.FALLBACK

    class Token(val value: String, val expiresAt: Long)

    /** The token of each account (by slot), and its last failed capture with the reason: within
     * [FAIL_COOLDOWN_MS] the same answer is given without loading the player again. */
    private val cached = java.util.concurrent.ConcurrentHashMap<String, Token>()
    private val lastFailure = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
    private const val FAIL_COOLDOWN_MS = 20_000L
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).withHook().build()

    /** The session of one account: the primary's, or an extra slot's own (see [Slots]). */
    fun session(slot: String): WebSession = session.forSlot(slot)

    fun signedIn(cookies: String?): Boolean = WebSession.cookieValue(cookies, "sp_dc") != null

    /** The bearer was refused (401): the next [token] captures a fresh one. */
    fun invalidateToken(slot: String = Slots.PRIMARY) { cached.remove(slot); SpotifyWebClient.dropPage(slot) }

    /** A valid access token, from cache or freshly captured. Blocking; never call on the main thread. */
    @Synchronized
    fun token(ctx: Context, slot: String = Slots.PRIMARY): Token {
        if (!session(slot).isConnected(ctx)) throw BridgeException("not connected")
        cached[slot]?.takeIf { it.expiresAt - 90_000 > System.currentTimeMillis() }?.let { return it }
        lastFailure[slot]?.let { (at, why) -> if (System.currentTimeMillis() - at < FAIL_COOLDOWN_MS) throw BridgeException(why) }
        val t = try { capture(ctx.applicationContext, slot) } catch (e: BridgeException) { lastFailure[slot] = System.currentTimeMillis() to (e.message ?: "no token"); throw e }
        lastFailure.remove(slot)
        cached[slot] = t
        return t
    }

    /** The app's side of [SpotifyWebClient]: the WebView sign-in in [session], the token the player hands out. */
    fun host(ctx: Context, slot: String = Slots.PRIMARY): SpotifyWebClient.Host = object : SpotifyWebClient.Host {
        private val sess = session(slot)
        override fun cookies(): String? = sess.cookies(ctx)
        override fun account(): String? = sess.account(ctx)
        override fun get(key: String): String? = sess.get(ctx, key)
        override fun put(key: String, value: String?) = sess.put(ctx, key, value)
        override val userAgent: String get() = BridgeUa.desktop(ctx)
        override val slot: String get() = sess.slot
        override fun bearer(): String = token(ctx, sess.slot).value
        override fun bearerRefused() = invalidateToken(sess.slot)
    }

    /** Username and display name, from the account page (the Web API refuses this token since December 2025). */
    fun accountInfo(ctx: Context, slot: String = Slots.PRIMARY): Pair<String?, String?> = SpotifyWebClient(ctx.applicationContext, slot).profile()

    private val SESSION_SCRIPT = Regex("""<script[^>]*id="session"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    private val TOKEN_URL = Regex("""^(?:https?://open\.spotify\.com)?/(?:api/token|get_access_token)(?:[?#]|$)""")

    /** The session block the web player's HTML ships with, fetched with the saved cookies. No WebView. */
    private fun fromHtml(app: Context, slot: String): Token? {
        val p = SpotifyWebClient(app, slot).page()
        if (p.isAnonymous) return null
        val value = p.accessToken ?: return null
        return Token(value, p.expiresAt)
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun capture(app: Context, slot: String): Token {
        runCatching { fromHtml(app, slot) }.getOrNull()?.let { return it }
        val sess = session(slot)
        val latch = CountDownLatch(1)
        var result: Token? = null
        var error: String? = null
        var web: WebView? = null
        val main = Handler(Looper.getMainLooper())

        val sink = object {
            @JavascriptInterface
            fun token(json: String) {
                val j = runCatching { parseJson(json) as? JsonObject }.getOrNull() ?: return
                val value = j["accessToken"].str ?: return
                if (j["isAnonymous"]?.toString() == "true") { error = "the Spotify web session has expired: sign in again"; latch.countDown(); return }
                val exp = j["accessTokenExpirationTimestampMs"].long ?: (System.currentTimeMillis() + 50 * 60_000)
                result = Token(value, exp)
                latch.countDown()
            }
            @JavascriptInterface
            fun fail(message: String) { if (result == null) error = message }
            /** A request the player made to its GraphQL gateway: its query hash and client token are kept. */
            @JavascriptInterface
            fun seen(json: String) {
                val j = runCatching { parseJson(json) as? JsonObject }.getOrNull() ?: return
                val client = SpotifyWebClient(app, slot)
                val op = j["op"].str; val hash = j["hash"].str
                if (op != null && hash != null) client.rememberHash(op, hash)
                j["clientToken"].str?.takeIf { it.isNotBlank() }?.let { client.rememberClientToken(it, System.currentTimeMillis() + 13L * 24 * 3600_000) }
                j["appVersion"].str?.takeIf { it.isNotBlank() }?.let { sess.put(app, "appVersion", it) }
            }
        }

        // The token request, wherever it is made from (page, worker): fetched here with the page's own
        // headers and cookies, answered to the page unchanged, and read on the way.
        fun intercept(request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            // The player's own GraphQL calls carry the client token and version: kept for our calls.
            if (url.contains("api-partner.spotify.com/pathfinder")) {
                val h = request.requestHeaders
                val ct = h.entries.firstOrNull { it.key.equals("client-token", true) }?.value
                val ver = h.entries.firstOrNull { it.key.equals("spotify-app-version", true) }?.value
                if (!ct.isNullOrBlank()) SpotifyWebClient(app, slot).rememberClientToken(ct, System.currentTimeMillis() + 13L * 24 * 3600_000)
                if (!ver.isNullOrBlank()) sess.put(app, "appVersion", ver)
                return null
            }
            if (!TOKEN_URL.containsMatchIn(url) || request.method != "GET") return null
            return try {
                val b = Request.Builder().url(url)
                request.requestHeaders.forEach { (k, v) -> if (!k.equals("Cookie", true)) b.header(k, v) }
                sess.jar().getCookie(url)?.let { b.header("Cookie", it) }
                http.newCall(b.build()).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    sink.token(body)
                    val headers = resp.headers.names().filter { !it.equals("content-encoding", true) && !it.equals("content-length", true) }
                        .associateWith { resp.header(it) ?: "" }
                    WebResourceResponse(resp.header("content-type")?.substringBefore(';')?.trim() ?: "application/json", "utf-8", resp.code, resp.message.ifBlank { "OK" }, headers, body.byteInputStream())
                }
            } catch (e: Exception) { null }
        }

        main.post {
            try {
                val w = WebView(app)
                web = w
                // An extra account's player runs in that account's profile: set before anything loads.
                Slots.apply(w, PROFILE, slot)
                w.settings.apply { javaScriptEnabled = true; domStorageEnabled = true }
                BridgeUa.apply(app, w)
                w.addJavascriptInterface(sink, "SpBridge")
                val early = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                if (early) WebViewCompat.addDocumentStartJavaScript(w, HOOK, setOf("*"))
                w.webViewClient = object : SafeWebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        if (!early) view.evaluateJavascript(HOOK, null)
                    }
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        intercept(request) ?: super.shouldInterceptRequest(view, request)
                    override fun onPageFinished(view: WebView, url: String) {
                        // Belt and braces: the session block in the HTML, then the endpoint from the page context.
                        view.evaluateJavascript(FALLBACK, null)
                    }
                }
                w.loadUrl("https://open.spotify.com/")
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                latch.countDown()
            }
        }
        latch.await(35, TimeUnit.SECONDS)
        main.post { runCatching { web?.stopLoading(); web?.destroy() } }
        return result ?: throw BridgeException(
            "Spotify did not hand out a token" + (error?.let { ": $it" } ?: " (timed out)") +
                ". Sign in to Spotify again."
        )
    }

    // The web player requests its token at start-up; mirror that response to SpBridge.token.
    private val HOOK = """
        (function(){
          if (window.__spTok) return; window.__spTok = 1;
          var isTok = function(u){ return /^(https?:\/\/open\.spotify\.com)?\/(api\/token|get_access_token)([?#]|$)/.test(String(u||'')); };
          var isGql = function(u){ return /api-partner\.spotify\.com\/pathfinder/.test(String(u||'')); };
          var hdr = function(h, name){ try { if (!h) return ''; if (typeof h.get === 'function') return h.get(name) || ''; for (var k in h) { if (String(k).toLowerCase() === name) return h[k]; } } catch(e){} return ''; };
          var report = function(url, headers, body){
            try {
              var op = '', hash = '';
              var m = /operationName=([A-Za-z0-9_]+)/.exec(url); if (m) op = m[1];
              var h = /sha256Hash(?:%22|")?(?:%3A|:)(?:%22|")?([0-9a-f]{64})/.exec(url); if (h) hash = h[1];
              if (body && typeof body === 'string') { try { var b = JSON.parse(body); op = b.operationName || op; hash = (b.extensions && b.extensions.persistedQuery && b.extensions.persistedQuery.sha256Hash) || hash; } catch(e){} }
              SpBridge.seen(JSON.stringify({op: op, hash: hash, clientToken: hdr(headers, 'client-token'), appVersion: hdr(headers, 'spotify-app-version')}));
            } catch(e){}
          };
          var of = window.fetch;
          if (of) window.fetch = function(input, init){
            var url = (typeof input === 'string') ? input : (input && input.url) || '';
            if (isGql(url)) report(url, (init && init.headers) || (input && input.headers), init && init.body);
            var p = of.apply(this, arguments);
            if (isTok(url)) p.then(function(r){ try { r.clone().text().then(function(t){ SpBridge.token(t); }); } catch(e){} });
            return p;
          };
          var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send, oh = XMLHttpRequest.prototype.setRequestHeader;
          XMLHttpRequest.prototype.open = function(m, u){ this.__u = u; this.__h = {}; return oo.apply(this, arguments); };
          XMLHttpRequest.prototype.setRequestHeader = function(k, v){ try { if (this.__h) this.__h[String(k).toLowerCase()] = v; } catch(e){} return oh.apply(this, arguments); };
          XMLHttpRequest.prototype.send = function(b){
            var x = this;
            if (isTok(x.__u)) x.addEventListener('loadend', function(){ try { SpBridge.token(String(x.responseText || '')); } catch(e){} });
            if (isGql(x.__u)) report(String(x.__u), x.__h, b);
            return os.apply(this, arguments);
          };
        })();
    """.trimIndent()

    private val FALLBACK = """
        (function(){
          try { var s = document.getElementById('session'); if (s && s.textContent) SpBridge.token(s.textContent); } catch(e){}
          var tryUrl = function(u){ return fetch(u, {credentials: 'include'}).then(function(r){ return r.text(); }).then(function(t){ SpBridge.token(t); }); };
          tryUrl('/api/token?reason=init&productType=web-player').catch(function(e){
            tryUrl('/get_access_token?reason=transport&productType=web_player').catch(function(e2){ SpBridge.fail(String(e2)); });
          });
        })();
    """.trimIndent()
}

/** The client as the app has always built it, from a Context; [slot] names an extra account. */
fun SpotifyWebClient(ctx: Context, slot: String = Slots.PRIMARY): SpotifyWebClient = SpotifyWebClient(SpotifyBridge.host(ctx, slot))
