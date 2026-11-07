package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The credentials of the Amazon Music web player, taken from the player itself.
 *
 * Since October 2026 the player talks GraphQL to `gql.music.amazon.<tld>`, authenticated by its
 * cookies and a few HTTP headers (CSRF triple, device and session ids). [AmazonClient] rebuilds
 * those from the page configuration; when Amazon refuses them, the ones mirrored here from the real
 * player are used: exactly what the player sends, with no guessing.
 *
 * Two chances to see them, because a player that nobody is looking at does not always start:
 * the sign-in and traffic-capture screens install [HOOK] in their own visible WebView and store
 * whatever it sees, and [player] can also run the player in an offscreen WebView when the stored
 * headers are too old. Nothing leaves the phone: the headers stay in this app, encrypted with the
 * session, and are used only for its own requests.
 */
object AmazonBridge {

    /** What the player uses: its API headers (preferred) or, failing that, its configuration object. */
    class Player(
        val headers: Map<String, String>, val config: JsonObject?, val userAgent: String?, val at: Long,
        /** Calls the offscreen player made without a token: it ran, but as a signed-out visitor. */
        val anonymousCalls: Int = 0,
        /** Where its other requests went (host and path), for the error when no API call was seen. */
        val otherRequests: List<String> = emptyList(),
    ) {
        /** The GraphQL player authenticates with its cookies and a CSRF triple: a mirrored set carries it. */
        val signedIn: Boolean get() = headers["csrf-token"]?.isNotBlank() == true
    }

    /** An access token lasts about an hour; well before that the headers are captured again. */
    private const val TTL_MS = 30 * 60_000L

    @Volatile private var cached: Player? = null

    fun forget() { cached = null }

    /** True if usable credentials are already at hand, without starting anything. */
    fun ready(ctx: Context): Boolean = stored(ctx) != null

    /**
     * One of the player's calls to its skill API (`<region>.web.skill.music.a2z.com/api/<method>`),
     * reported by [HOOK]: its host, its `x-amzn-*` headers (from the request body) and userHash are
     * kept, and the account's API is updated (see [AmazonRoute]). True when it carried usable
     * credentials. Only names and lengths reach the log, never a value.
     */
    fun rememberSkill(ctx: Context, host: String, method: String, headersJson: String, userHash: String, userAgent: String?, pageSawGql: Boolean): Boolean {
        if (!AmazonSkillClient.isSkillHost(host)) return false
        val app = ctx.applicationContext
        val evidence = AmazonSkillClient.isEvidence(method)
        val headers = headersJson.takeIf { it.isNotBlank() }?.let { AmazonSkillClient.headersOf(it) }
        val hash = userHash.takeIf { runCatching { parseJson(it) is JsonObject }.getOrDefault(false) }
        // showHome goes to na. even for the Dutch player, whose library calls go to eu.: the host is
        // taken from library and catalogue calls only.
        AmazonSession.saveSkill(app, if (evidence) host else null, headers?.let { headersJson }, hash, userAgent)
        AmazonRoute.afterPlayerCall(AmazonSession.api(app), skill = true, method = method, pageSawGql = pageSawGql)?.let { AmazonSession.setApi(app, it) }
        val key = "$host/$method/${headers != null}"
        val now = System.currentTimeMillis()
        if (now - (skillLogged[key] ?: 0L) > 600_000) {
            skillLogged[key] = now
            BridgeNet.log("amazon", "the player's skill call $method on $host: " +
                (headers?.entries?.sortedBy { it.key }?.joinToString(",") { "${it.key}(${it.value.length})" }
                    ?: if (headersJson.isBlank()) "no headers" else "headers without credentials") +
                (if (pageSawGql) "; the page also called GraphQL" else ""))
        }
        return headers != null
    }

    private val skillLogged = ConcurrentHashMap<String, Long>()

    /** A GraphQL call of the player: an account it had on the skill API is back on GraphQL. */
    private fun sawGql(app: Context) {
        AmazonRoute.afterPlayerCall(AmazonSession.api(app), skill = false, method = "", pageSawGql = true)?.let { AmazonSession.setApi(app, it) }
    }

    /**
     * Stores headers seen in a visible WebView (sign-in, traffic capture). Called from JavaScript,
     * so it accepts whatever arrives and simply ignores anything without a token.
     */
    fun remember(ctx: Context, headersJson: String, userAgent: String?, host: String?) {
        val map = headersOf(headersJson) ?: return
        AmazonSession.saveHeaders(ctx.applicationContext, headersJson, userAgent, host)
        cached = Player(map, null, userAgent, System.currentTimeMillis())
    }

    /**
     * Installs the hook into a WebView the user can see (sign-in, traffic capture): a player someone
     * is actually looking at always starts, so this is the surest place to see its headers. Returns
     * true when the script runs by itself at every document start; otherwise the caller must also
     * evaluate [HOOK] in its page callbacks.
     */
    @SuppressLint("AddJavascriptInterface")
    fun install(ctx: Context, web: WebView): Boolean {
        val app = ctx.applicationContext
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun headers(json: String, ua: String?, host: String?) { remember(app, json, ua, host) }
            /** The host the player's API calls go to (an EU-side one for a "Rest of Europe" account). */
            @JavascriptInterface
            fun gqlHost(host: String) { AmazonSession.saveGqlHost(app, host); sawGql(app) }
            /** One of the player's skill API calls (an account left on the older API, issue #35). */
            @JavascriptInterface
            fun skill(host: String, method: String, headers: String, userHash: String, ua: String?, sawGql: Boolean) {
                rememberSkill(app, host, method, headers, userHash, ua, sawGql)
            }
            /** Status of one of the player's own API calls, for the diagnostics log. */
            @JavascriptInterface
            fun status(op: String, code: Int) { playerStatus(op, code) }
            /** The configuration is only used here for the display name shown on the status screen. */
            @JavascriptInterface
            fun config(json: String, ua: String?) {
                val j = parseJson(json) as? JsonObject ?: return
                AmazonClient.Config(j).customerName?.takeIf { it.isNotBlank() }?.let { AmazonSession.saveAccount(app, it) }
            }
        }, "AmzBridge")
        val early = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (early) WebViewCompat.addDocumentStartJavaScript(web, HOOK, setOf("*"))
        return early
    }

    /**
     * The player's credentials, from cache or freshly captured; [refresh] always runs the player,
     * which also renews the session cookies in the WebView's jar. Blocking; never on the main thread.
     */
    @Synchronized
    fun player(ctx: Context, domain: String, refresh: Boolean = false): Player {
        if (!refresh) {
            cached?.takeIf { fresh(it.at) && it.signedIn }?.let { return it }
            stored(ctx)?.let { cached = it; return it }
        }
        val p = capture(ctx.applicationContext, domain, keep = refresh)
        cached = p
        return p
    }

    /**
     * Runs the player offscreen until it makes a skill API call with its credentials (or a GraphQL
     * one), for [AmazonSkillClient]: what it saw is in [AmazonSession]. Blocking; never on the main thread.
     */
    @Synchronized
    fun skillPlayer(ctx: Context, domain: String) {
        capture(ctx.applicationContext, domain, skill = true)
    }

    // ------------------------------------------------------------------ calls from inside the player

    /** The offscreen player kept open after a refresh, and when it was last used. Main thread only. */
    @Volatile private var page: WebView? = null
    @Volatile private var pageUsed = 0L
    private val results = ConcurrentHashMap<String, CompletableFuture<Pair<Int, String>>>()
    private val ids = AtomicInteger(0)
    private const val PAGE_IDLE_MS = 3 * 60_000L

    /** True while a player page is open to run calls in. */
    val pageOpen: Boolean get() = page != null

    /**
     * Sends one GraphQL request from inside the running player page, as the player itself would:
     * the browser adds the cookies, renews what it renews, and the request carries the headers the
     * player last sent. Used when Amazon accepts the session from the browser but not from our own
     * HTTP client. Opens the player first if it is not running. Blocking; never on the main thread.
     */
    fun gqlInPage(ctx: Context, domain: String, body: String): Pair<Int, String> {
        if (page == null) player(ctx, domain, refresh = true)
        val id = "q" + ids.incrementAndGet()
        val future = CompletableFuture<Pair<Int, String>>()
        results[id] = future
        pageUsed = System.currentTimeMillis()
        val js = "window.__amzCall && window.__amzCall(${JsonPrimitive(id)}, ${JsonPrimitive(body)});"
        Handler(Looper.getMainLooper()).post {
            val w = page
            if (w == null) future.complete(-1 to "the player page is closed") else w.evaluateJavascript(js, null)
        }
        return try {
            future.get(45, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            -1 to "no answer from the player page in 45 s"
        } finally {
            results.remove(id)
            pageUsed = System.currentTimeMillis()
        }
    }

    private val statusLogged = ConcurrentHashMap<String, Long>()

    /** Logs how Amazon answered one of the player's own calls: each operation and status once in ten minutes. */
    private fun playerStatus(op: String, code: Int) {
        val key = "$op:$code"
        val now = System.currentTimeMillis()
        if (now - (statusLogged[key] ?: 0L) < 600_000) return
        statusLogged[key] = now
        BridgeNet.log("amazon", "the player's own ${op.ifBlank { "call" }}: $code")
    }

    /** Called from the page with the answer to [gqlInPage]. */
    private fun answer(id: String, status: Int, text: String) { results[id]?.complete(status to text) }

    /** Closes the kept player once nobody has used it for a while. */
    private fun scheduleClose(main: Handler) {
        main.postDelayed({
            val w = page ?: return@postDelayed
            if (System.currentTimeMillis() - pageUsed < PAGE_IDLE_MS) scheduleClose(main)
            else { page = null; runCatching { w.stopLoading(); w.destroy() } }
        }, 60_000L)
    }

    private fun fresh(at: Long) = System.currentTimeMillis() - at < TTL_MS

    /** The headers kept with the session, while they are young enough to still carry a live token. */
    private fun stored(ctx: Context): Player? {
        val app = ctx.applicationContext
        val at = AmazonSession.headersAt(app)
        if (!fresh(at)) return null
        val map = headersOf(AmazonSession.headers(app) ?: return null) ?: return null
        return Player(map, null, AmazonSession.headersUserAgent(app), at)
    }

    /** Parses a mirrored set of the player's GraphQL headers, keeping it only when it carries the CSRF token. */
    private fun headersOf(json: String): Map<String, String>? {
        val j = parseJson(json) as? JsonObject ?: return null
        val map = j.mapNotNull { (k, v) -> v.str?.let { k.lowercase() to it } }.toMap()
        return if (map["csrf-token"].isNullOrBlank()) null else map
    }

    /** The user agent the player ran with when its headers were mirrored. */
    fun storedUserAgent(ctx: Context): String? = AmazonSession.headersUserAgent(ctx.applicationContext)

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun capture(app: Context, domain: String, keep: Boolean = false, skill: Boolean = false): Player {
        val latch = CountDownLatch(1)
        // Written from the WebView thread, read here: atomic holders, not plain locals.
        val headers = AtomicReference<Map<String, String>?>(null)
        val config = AtomicReference<JsonObject?>(null)
        val userAgent = AtomicReference<String?>(null)
        val lastUrl = AtomicReference("")
        val problem = AtomicReference<String?>(null)
        val calls = AtomicInteger(0)
        val anonymous = AtomicInteger(0)
        val skillSeen = java.util.concurrent.atomic.AtomicBoolean(false)
        val others = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
        var web: WebView? = null
        val main = Handler(Looper.getMainLooper())

        val sink = object {
            @JavascriptInterface
            fun headers(json: String, ua: String?, host: String?) {
                calls.incrementAndGet()
                val map = headersOf(json) ?: run { anonymous.incrementAndGet(); return }
                AmazonSession.saveHeaders(app, json, ua, host)
                headers.set(map)
                userAgent.set(ua)
                latch.countDown()
            }

            /** `window.amznMusic.appConfig`, used only when no call of the player was seen. */
            @JavascriptInterface
            fun config(json: String, ua: String?) {
                config.compareAndSet(null, parseJson(json) as? JsonObject ?: return)
                userAgent.compareAndSet(null, ua)
            }

            /** Host and path of a request that is not an API call we know. */
            @JavascriptInterface
            fun other(where: String) { if (others.size < 40) others.add(where.take(80)) }

            @JavascriptInterface
            fun gqlHost(host: String) { AmazonSession.saveGqlHost(app, host); sawGql(app) }

            /**
             * One of the player's skill API calls: kept like in [install]. It ends the wait only when
             * the skill API asked for this run ([skillPlayer]); a GraphQL run keeps waiting for GraphQL.
             */
            @JavascriptInterface
            fun skill(host: String, method: String, headers: String, userHash: String, ua: String?, sawGql: Boolean) {
                if (rememberSkill(app, host, method, headers, userHash, ua, sawGql) && AmazonSkillClient.isEvidence(method)) {
                    skillSeen.set(true)
                    if (skill) latch.countDown()
                }
            }

            /** The player showed a page instead of starting ("update your browser", "get the app"). */
            @JavascriptInterface
            fun page(text: String) { problem.compareAndSet(null, "page says: " + text.take(120)) }

            @JavascriptInterface
            fun status(op: String, code: Int) { playerStatus(op, code) }

            /** The answer to a call made with [gqlInPage]. */
            @JavascriptInterface
            fun result(id: String, status: Int, text: String) { answer(id, status, text) }
        }

        // A kept page from an earlier refresh is replaced by this one.
        main.post { page?.let { old -> page = null; runCatching { old.stopLoading(); old.destroy() } } }

        main.post {
            try {
                CookieManager.getInstance().apply { setAcceptCookie(true); flush() }
                val w = WebView(app)
                web = w
                w.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; loadWithOverviewMode = true; useWideViewPort = true }
                CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
                // A window this player is never shown in still needs a size: without a viewport the
                // single page application lays nothing out and makes no calls at all.
                w.measure(View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(VIEW_H, View.MeasureSpec.EXACTLY))
                w.layout(0, 0, VIEW_W, VIEW_H)
                // Desktop Chrome, as in the traffic capture, whose player Amazon accepts on this session.
                BridgeUa.apply(app, w)
                w.addJavascriptInterface(sink, "AmzBridge")
                val early = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                if (early) WebViewCompat.addDocumentStartJavaScript(w, HOOK, setOf("*"))
                w.webViewClient = object : SafeWebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        lastUrl.set(url ?: "")
                        if (!early) view.evaluateJavascript(HOOK, null)
                        // Sent to the sign-in page: the cookies no longer open the player. No point waiting.
                        if (url != null && url.contains("/ap/signin")) { problem.compareAndSet(null, SIGN_IN_AGAIN); latch.countDown() }
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        lastUrl.set(url)
                        if (!early) view.evaluateJavascript(HOOK, null)
                        view.evaluateJavascript(CONFIG, null)
                    }
                    override fun onReceivedError(view: WebView, req: android.webkit.WebResourceRequest, err: android.webkit.WebResourceError) {
                        if (req.isForMainFrame) problem.compareAndSet(null, "page error ${err.errorCode}")
                    }
                }
                w.loadUrl("https://${AmazonSession.headersHost(app) ?: domain}/")
                // The configuration is worth asking for even if the page never reports itself finished.
                listOf(5_000L, 12_000L, 25_000L, 40_000L).forEach { at ->
                    main.postDelayed({ runCatching { web?.evaluateJavascript(CONFIG, null) } }, at)
                }
            } catch (e: Exception) {
                problem.compareAndSet(null, e.message ?: e.javaClass.simpleName)
                latch.countDown()
            }
        }
        latch.await(50, TimeUnit.SECONDS)
        val usable = headers.get() != null
        main.post {
            if (keep && usable && web != null) {
                page = web
                pageUsed = System.currentTimeMillis()
                scheduleClose(main)
            } else runCatching { web?.stopLoading(); web?.destroy() }
        }

        val h = headers.get()
        val c = config.get()
        if (h == null && problem.get() == SIGN_IN_AGAIN) throw BridgeException(
            "the Amazon session no longer opens the player (it asked to sign in again). Sign out of Amazon Music and sign in again.",
        )
        if (h == null && problem.get()?.startsWith("page says") == true) throw BridgeException(
            "the Amazon Music player did not start [${problem.get()}, url: ${lastUrl.get().take(80)}].",
        )
        if (h == null && c == null && !(skill && skillSeen.get())) throw BridgeException(
            "the Amazon Music player did not hand over its credentials in time" +
                " [url: ${lastUrl.get().take(80).ifBlank { "none" }}" +
                ", calls seen: ${calls.get()}" +
                (problem.get()?.let { ", $it" } ?: "") + "]." +
                " Run Capture traffic for Amazon Music once and let the player load, then try again.",
        )
        val seen = synchronized(others) { others.filterNot { it.startsWith("m.media-amazon") || it.contains("/images") }.take(12) }
        return Player(h ?: emptyMap(), c, userAgent.get(), System.currentTimeMillis(), anonymous.get(), seen)
    }

    private const val VIEW_W = 1280
    private const val VIEW_H = 2000
    private const val SIGN_IN_AGAIN = "sign-in page"

    /**
     * Mirrors the HTTP headers of the player's own GraphQL calls (CSRF triple, device and session
     * ids), and the body headers of its skill API calls for the accounts still on it (issue #35), and
     * meets the desktop player's browser checks so that it starts at all.
     */
    val HOOK = """
        (function(){
          if (window.__amzHdr) return; window.__amzHdr = 1;
          // What the desktop player checks before it starts (taken from its own code, see the traffic
          // capture): not Linux, no touch, FLAC in MP4 through MSE and Widevine, a window.chrome.
          // Nothing is ever played here, so the codec answers are given.
          try { Object.defineProperty(Navigator.prototype, 'platform', { get: function(){ return 'Win32'; }, configurable: true }); } catch(e){}
          try { Object.defineProperty(Navigator.prototype, 'maxTouchPoints', { get: function(){ return 0; }, configurable: true }); } catch(e){}
          try {
            var isType = MediaSource.isTypeSupported.bind(MediaSource);
            MediaSource.isTypeSupported = function(t){ return /audio\/mp4/.test(String(t)) ? true : isType(t); };
          } catch(e){}
          try {
            var realEme = navigator.requestMediaKeySystemAccess.bind(navigator);
            var stub = function(ks, cfgs){
              var cfg = (cfgs && cfgs[0]) || {};
              return { keySystem: ks, getConfiguration: function(){ return cfg; },
                createMediaKeys: function(){ return Promise.resolve({
                  createSession: function(){ return { addEventListener: function(){}, removeEventListener: function(){}, generateRequest: function(){ return Promise.resolve(); }, update: function(){ return Promise.resolve(); }, close: function(){ return Promise.resolve(); }, closed: Promise.resolve() }; },
                  setServerCertificate: function(){ return Promise.resolve(true); } }); } };
            };
            navigator.requestMediaKeySystemAccess = function(ks, cfgs){ return realEme(ks, cfgs).catch(function(){ return stub(ks, cfgs); }); };
          } catch(e){}
          try { if (!window.chrome) window.chrome = { runtime: {}, app: { isInstalled: false }, loadTimes: function(){ return {}; }, csi: function(){ return {}; } }; } catch(e){}
          // A page in place of the player: said, so the error names it instead of a silent timeout.
          var probe = function(){
            try {
              var t = String(document.body && document.body.innerText || '');
              var m = t.match(/[^\n]*(Update Chrome|Aggiornare Chrome|no longer supported|non è più supportat|Get the app|Scarica l.app|Open the app|Apri l.app)[^\n]*/i);
              if (m && AmzBridge.page) AmzBridge.page(m[0]);
            } catch(e){}
          };
          setTimeout(probe, 8000); setTimeout(probe, 20000);
          // The GraphQL endpoint the player has used since October 2026: its HTTP headers (CSRF triple,
          // device and session ids) are what a request needs besides the cookies.
          // Also an EU-side host for the "Rest of Europe" accounts served on music.amazon.com
          // (gql-eu…, eu.gql…): whatever host carries "gql" under music.amazon.
          var gqlHostOf = function(u){ try { var h = new URL(String(u||''), location.href).host; return /^[a-z0-9-]*gql[a-z0-9.-]*\.music\.amazon\./.test(h) ? h : null; } catch(e) { return null; } };
          var isGql = function(u){ return gqlHostOf(u) != null; };
          // The older skill API (issue #35): some "Rest of Europe" accounts' players call only
          // <region>.web.skill.music.a2z.com/api/<method>, with the x-amzn-* headers inside the JSON
          // body (a JSON string under "headers") next to the userHash. Host, method, those headers and
          // the userHash go to the Bridge, with whether this page has called GraphQL too.
          var skillOf = function(u){ try { var x = new URL(String(u||''), location.href); return /^(na|eu|fe)\.web\.skill\.music\.a2z\.com$/.test(x.host) && /^\/api\//.test(x.pathname) ? x : null; } catch(e) { return null; } };
          var skill = function(x, b){
            try {
              if (!AmzBridge.skill) return;
              var h = '', uh = '';
              try {
                var j = (typeof b === 'string') ? JSON.parse(b) : null;
                if (j && j.headers != null) h = (typeof j.headers === 'string') ? j.headers : JSON.stringify(j.headers);
                if (j && j.userHash != null) uh = String(j.userHash);
              } catch(e){}
              AmzBridge.skill(x.host, x.pathname.split('/').pop(), h, uh, navigator.userAgent, !!window.__amzSawGql);
            } catch(e){}
          };
          var plain = function(h){
            var out = {};
            try {
              if (!h) return out;
              if (typeof h.forEach === 'function' && !(h instanceof Array)) h.forEach(function(v, k){ out[k] = String(v); });
              else if (h instanceof Array) h.forEach(function(p){ out[p[0]] = String(p[1]); });
              else Object.keys(h).forEach(function(k){ out[k] = String(h[k]); });
            } catch(e){}
            return out;
          };
          var grab = function(h, u){
            try {
              var gh = gqlHostOf(u); if (gh) { window.__amzGqlHost = gh; if (AmzBridge.gqlHost) AmzBridge.gqlHost(gh); }
              var o = plain(h); if (o['csrf-token']) { window.__amzLast = o; AmzBridge.headers(JSON.stringify(o), navigator.userAgent, location.host); }
            } catch(e){}
          };
          // A call Trackhop asks for, sent by the page itself with the headers the player last used.
          window.__amzCall = function(id, body){
            var done = function(st, t){ try { AmzBridge.result(id, st, String(t)); } catch(e){} };
            try {
              var h = {}, last = window.__amzLast || {};
              Object.keys(last).forEach(function(k){ h[k] = last[k]; });
              h['content-type'] = 'application/json';
              h['accept'] = 'application/graphql-response+json, application/json';
              if (h['x-amzn-trace-start']) h['x-amzn-trace-start'] = String(Date.now());
              window.fetch('https://' + (window.__amzGqlHost || ('gql.' + location.host)) + '/', { method: 'POST', headers: h, body: body, credentials: 'include' })
                .then(function(r){ return r.text().then(function(t){ done(r.status, t); }); })
                .catch(function(e){ done(-1, e); });
            } catch(e) { done(-1, e); }
          };
          // Where the player's other requests go (host and path only): if Amazon moves its API again,
          // the error says where to, instead of "no calls".
          var other = function(u){ try { if (!AmzBridge.other) return; var x = new URL(String(u), location.href); AmzBridge.other(x.host + x.pathname.split('/').slice(0, 3).join('/')); } catch(e){} };
          var of = window.fetch;
          if (of) window.fetch = function(input, init){
            var url = (typeof input === 'string') ? input : (input && input.url) || '';
            if (isGql(url)) { window.__amzSawGql = 1; grab((init && init.headers) || (input && input.headers), url); }
            else { var sk = skillOf(url); if (sk) skill(sk, init && init.body); other(url); }
            var p = of.apply(this, arguments);
            // How Amazon answers the player's own calls: tells "the session is refused" from "our
            // copy of the player's request is refused".
            if (isGql(url) && AmzBridge.status) try {
              var op = ''; try { op = JSON.parse(String((init && init.body) || '{}')).operationName || ''; } catch(e){}
              p.then(function(r){ try { AmzBridge.status(op, r.status); } catch(e){} }, function(){});
            } catch(e){}
            return p;
          };
          var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send, osh = XMLHttpRequest.prototype.setRequestHeader;
          XMLHttpRequest.prototype.setRequestHeader = function(k, v){ (this.__h = this.__h || []).push([k, v]); return osh.apply(this, arguments); };
          XMLHttpRequest.prototype.open = function(m, u){ this.__u = u; return oo.apply(this, arguments); };
          XMLHttpRequest.prototype.send = function(b){
            if (isGql(this.__u)) { window.__amzSawGql = 1; grab(this.__h, this.__u); }
            else { var sk = skillOf(this.__u); if (sk) skill(sk, b); other(this.__u); }
            return os.apply(this, arguments);
          };
        })();
    """.trimIndent()

    /** The configuration object, retried while the single page application boots. */
    val CONFIG = """
        (function(){
          if (window.__amzCfg) return; window.__amzCfg = 1;
          var tries = 0;
          var look = function(){
            try {
              var c = window.amznMusic && window.amznMusic.appConfig;
              if (c) { AmzBridge.config(JSON.stringify(c), navigator.userAgent); return; }
            } catch(e){}
            if (++tries < 30) setTimeout(look, 1000);
          };
          look();
        })();
    """.trimIndent()
}
