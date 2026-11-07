package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Shared sign-in screen for the web sessions (Spotify, Apple Music, Deezer, SoundCloud, LiveOne, Anghami, Qobuz). The user signs in on the
 * service's own page; the activity polls the cookie jar of the service's domain and, as soon as the
 * session cookie is there, stores the cookies encrypted and closes. Trackhop starts it with the
 * actions declared in the manifest.
 *
 * SoundCloud's session is the OAuth token its web app sends, taken from what the page itself does
 * (see [SoundCloudBridge]): the Authorization header of its API requests, else the `oauth_token`
 * cookie of any SoundCloud host, else, once the page is signed in, its localStorage.
 */
class WebLoginActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }
    private lateinit var web: WebView
    private lateinit var frame: android.widget.FrameLayout
    private val popups = ArrayList<WebView>()

    private fun closePopup(w: WebView) {
        popups.remove(w)
        frame.removeView(w)
        runCatching { w.destroy() }
        check()
    }
    private val handler = Handler(Looper.getMainLooper())
    private var done = false
    private lateinit var service: String
    /** The account this sign-in is for: "" for the one there has always been, "2", "3"… for an extra one (see [Slots]). */
    private var slot: String = Slots.PRIMARY
    private lateinit var notice: android.widget.TextView
    private var seen: DataDome.Kind? = null

    // SoundCloud: what the page shows of its session. The first three are written from WebView threads.
    @Volatile private var headerFound: SoundCloudBridge.Found? = null
    private val apiSeen = ConcurrentHashMap<String, AtomicInteger>()
    private val schemesSeen: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var storageFound: SoundCloudBridge.Found? = null
    private var storageKeys: List<String>? = null
    private var storageReadOn: String? = null
    private var storagePending = false
    private var lastStorageRead = 0L
    private val storageWaiters = ArrayList<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        service = when (intent.action) {
            ACTION_SPOTIFY -> SPOTIFY
            ACTION_APPLE -> APPLE
            ACTION_DEEZER -> DEEZER
            ACTION_SOUNDCLOUD -> SOUNDCLOUD
            ACTION_LIVEONE -> LIVEONE
            ACTION_ANGHAMI -> ANGHAMI
            ACTION_QOBUZ -> QOBUZ
            else -> intent.getStringExtra(EXTRA_SERVICE) ?: SPOTIFY
        }
        // An extra account signs in inside its own WebView profile; SoundCloud serves one account.
        slot = if (service == SOUNDCLOUD) Slots.PRIMARY else Slots.valid(intent.getStringExtra(Slots.EXTRA))
        if (slot.isNotEmpty() && !Slots.supported()) {
            android.widget.Toast.makeText(this, Slots.UNSUPPORTED, android.widget.Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED); finish(); return
        }
        // Deezer and SoundCloud came with a notice that names them: an acceptance of the older one does not cover them.
        Disclaimer.require(this, if (service == DEEZER || service == SOUNDCLOUD || service == LIVEONE || service == ANGHAMI || service == QOBUZ) Disclaimer.VERSION else 1) { setup() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setup() {
        // A "Done" button next to the page: a service that answers something unexpected must never
        // leave the user trapped in this screen.
        val root = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val bar = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(24, 12, 24, 12) }
        bar.addView(android.widget.TextView(this).apply {
            text = getString(R.string.th_bridge_login_hint)
            textSize = 12f
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        // LiveOne: where the in-app sign-in cannot finish (Google may refuse an embedded browser), the
        // player's `slackerUser` entry copied from a desktop browser works the same.
        if (service == LIVEONE || service == ANGHAMI || service == QOBUZ) bar.addView(android.widget.Button(this).apply {
            text = getString(android.R.string.paste)
            setOnClickListener { when (service) { ANGHAMI -> pasteAnghami(); QOBUZ -> pasteQobuz(); else -> pasteLiveOne() } }
        })
        bar.addView(android.widget.Button(this).apply {
            text = getString(R.string.th_bridge_login_done)
            setOnClickListener {
                if (check()) return@setOnClickListener
                // SoundCloud: a signed-in page may keep its token in localStorage only; it is read
                // now (once the page has left the sign-in form) before saying there is no session.
                if (service == SOUNDCLOUD && readStorage(force = true) { if (!check()) noSession() }) return@setOnClickListener
                noSession()
            }
        })
        // Said in words when the service's bot protection refuses the sign-in: its own page can be a
        // dead end ("You have been blocked") with nothing to tap.
        notice = android.widget.TextView(this).apply {
            textSize = 14f
            setPadding(24, 16, 24, 16)
            setBackgroundColor(0x33FF5252)
            visibility = android.view.View.GONE
        }
        // The page sits in a frame so a sign-in popup (Facebook, Google, Apple ID) can open above it.
        frame = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        web = WebView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        }
        // An extra account's page runs in that account's profile: set before anything loads.
        Slots.apply(web, service, slot)
        frame.addView(web)
        root.addView(bar); root.addView(notice); root.addView(frame)
        setContentView(root)
        // Edge to edge: the bar would otherwise sit under the status bar, and the keyboard over the fields.
        BridgeInsets.fit(root)
        jar().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // Spotify refuses its web player to anything whose user agent says "wv" (a WebView) and
            // shows "Playback disabled - incompatible browser". A desktop identity avoids that page;
            // Apple's pages work with the default one, and SoundCloud needs it (see [identity]).
        }
        identity(web)
        // Qobuz's web player wants a window at least 1024 px wide ("download the app" otherwise,
        // issue #37 on a foldable): the page is given a desktop one, as the capture screen does.
        val desktopEarly = service != QOBUZ || DesktopPage.apply(web)
        if (service == SOUNDCLOUD) {
            // A DataDome cookie from an earlier attempt (as desktop Chrome, or already flagged) would
            // tie this one to it: the sign-in starts as a new visitor. The account's cookies stay.
            val dropped = dropCookie(SoundCloudBridge.SITE, "datadome")
            BridgeNet.log("soundcloud", "sign-in started as the phone's WebView (${BridgeUa.describe()}; ${if (dropped) "old DataDome cookie removed" else "no DataDome cookie"})")
            // A page with a service worker makes its API calls from there, out of the WebViewClient's
            // sight: those are observed the same way (and also go on as they were made).
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) runCatching {
                ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                        watchSoundCloud(request)
                        return null
                    }
                })
            }
        }
        web.webViewClient = object : SafeWebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (googleIdentity(view, request)) return true
                val scheme = request.url.scheme
                // SoundCloud's mobile site offers its app ("Listen in app", banners) by intent://,
                // a store or a deep-link address, tapped or not: the sign-in stays on this page.
                if (service == SOUNDCLOUD && SoundCloudBridge.isAppHandoff(request.url.toString())) {
                    BridgeNet.log("soundcloud", "sign-in: a link to the app or a store (${scheme}://${request.url.host.orEmpty()}) was kept on this page")
                    return true
                }
                if (scheme == "http" || scheme == "https") return false
                if (scheme == "intent") {
                    runCatching {
                        Intent.parseUri(request.url.toString(), Intent.URI_INTENT_SCHEME).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE); component = null; selector = null
                        }.let(::startActivity)
                    }
                }
                return true
            }
            // The player sends Apple's developer token as the bearer of every API call it makes: that
            // is where the Bridge takes it from, instead of guessing which script bundle holds it.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (service == APPLE && request.url.host?.endsWith("music.apple.com") == true) {
                    request.requestHeaders?.get("Authorization")?.let { AppleBridge.rememberDeveloperToken(applicationContext, it, slot) }
                }
                // SoundCloud's web app names itself with a client id on every API call, and signed in
                // sends the account's token: both kept for ours (see [watchSoundCloud]).
                watchSoundCloud(request)
                watchAnghami(request.url.toString())
                // Qobuz: the session travels as headers of the player's API calls, not in their address.
                watchQobuz(request)
                watchBotCheck(request.url.toString())
                // Only watched: every request goes on as the page made it.
                return null
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (!desktopEarly) DesktopPage.lateInject(view)
                // A new page is a new chance: its own block, if any, shows the notice again.
                seen = null
                notice.visibility = android.view.View.GONE
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (service == APPLE) view.evaluateJavascript(
                    "(function(){try{return MusicKit.getInstance().developerToken||''}catch(e){return ''}})()"
                ) { v -> AppleBridge.rememberDeveloperToken(applicationContext, v?.trim('"'), slot) }
                check()
            }
        }
        web.settings.apply { setSupportMultipleWindows(true); javaScriptCanOpenWindowsAutomatically = true }
        web.webChromeClient = object : WebChromeClient() {
            // "Continue with Facebook" and the like open a popup, sign in there, and hand the result
            // back to the page that opened it: a real second view, sharing the cookie jar, keeps that
            // handshake intact. Without it the sign-in ran in the main page and never came back.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val popup = WebView(this@WebLoginActivity).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                    // The popup shares the page's profile (and so its cookie jar), as it shares the default one.
                    runCatching { Slots.apply(this, service, slot) }
                    settings.apply {
                        javaScriptEnabled = true; domStorageEnabled = true
                        setSupportMultipleWindows(true); javaScriptCanOpenWindowsAutomatically = true
                    }
                    identity(this)
                    jar().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : SafeWebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            watchSoundCloud(request)
                            watchBotCheck(request.url.toString())
                            return null
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (googleIdentity(view, request)) return true
                            if (service != SOUNDCLOUD || !SoundCloudBridge.isAppHandoff(request.url.toString())) return false
                            // A window opened only to hand over to the app goes away; the sign-in stays.
                            BridgeNet.log("soundcloud", "sign-in: a link to the app or a store (${request.url.scheme}://${request.url.host.orEmpty()}) was kept from leaving")
                            if (view.url.isNullOrEmpty() || view.url == "about:blank") handler.post { if (view in popups) closePopup(view) }
                            return true
                        }
                        override fun onPageFinished(view: WebView, url: String) { check() }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onCloseWindow(window: WebView) { closePopup(window) }
                    }
                }
                frame.addView(popup)
                popups += popup
                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }
        onBackPressedDispatcher.addCallback(this) {
            val top = popups.lastOrNull()
            when {
                top != null -> if (top.canGoBack()) top.goBack() else closePopup(top)
                web.canGoBack() -> web.goBack()
                else -> { setResult(RESULT_CANCELED); finish() }
            }
        }
        web.loadUrl(when (service) {
            APPLE -> AppleBridge.HOME
            DEEZER -> DeezerBridge.LOGIN_URL
            SOUNDCLOUD -> SoundCloudBridge.LOGIN_URL
            LIVEONE -> LiveOneBridge.LOGIN_URL
            ANGHAMI -> AnghamiWebClient.LOGIN_URL
            QOBUZ -> QobuzWebClient.LOGIN_URL
            else -> SpotifyBridge.LOGIN_URL
        })
        // Sign-in overlays (Apple ID) do not navigate: poll the cookie jar while the screen is open.
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (done || isFinishing) return
                // SoundCloud's last resort: the signed-in page's localStorage, read now and then.
                if (!check() && service == SOUNDCLOUD) readStorage(force = false)
                handler.postDelayed(this, 1500)
            }
        }, 1500)
    }

    /**
     * The browser identity of the page and its popups.
     * - Apple: the WebView's own, as it always was.
     * - SoundCloud: the WebView's own as well, without the app header and without any script.
     *   soundcloud.com sits behind DataDome, which reads the browser from inside the page (touch,
     *   screen, platform, how navigator's properties are defined) and blocked a WebView whose user
     *   agent, client hints and patched navigator.userAgentData said desktop Chrome on Windows while
     *   everything else said Android. A plain mobile browser has nothing to disagree with.
     * - Spotify and Deezer: desktop Chrome, as before (Spotify refuses a "wv" user agent).
     */
    /**
     * "Continue with Google": Google refuses its sign-in to a desktop identity on a phone ("This
     * browser or app may not be secure"), so on Google's pages the view is the phone's own WebView,
     * as in the YouTube Music sign-in, and the desktop identity comes back on the service's pages.
     * Only page loads by GET are loaded again with the other identity (a form's POST goes on as it is).
     * True when this call loaded the page itself.
     */
    private fun googleIdentity(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame || service == APPLE || service == SOUNDCLOUD) return false
        val url = request.url
        if (url.scheme != "https") return false
        val google = BridgeUa.isGoogleSignIn(url.host)
        val changed = if (google) BridgeUa.native(view) else BridgeUa.desktopAgain(this, view)
        if (!changed) return false
        BridgeNet.log(service, if (google) "sign-in: Google's page, with the phone's own browser identity" else "sign-in: back from Google, desktop identity again")
        // Back from Google the page goes on as it is (the identity counts from the next load): a
        // second load of Google's answer would only add a step to Back, and Google refuses it (400).
        if (!google || !request.method.equals("GET", ignoreCase = true)) return false
        view.loadUrl(url.toString())
        return true
    }

    private fun identity(w: WebView) {
        when (service) {
            APPLE -> Unit
            SOUNDCLOUD -> BridgeUa.dropAppHeader(w)
            else -> BridgeUa.apply(this, w)
        }
    }

    /** Called from WebView threads with every address a page loads; only reads it. */
    private fun watchBotCheck(url: String) {
        val kind = DataDome.kindOf(url) ?: return
        handler.post { botCheck(kind) }
    }

    /**
     * SoundCloud, called from WebView threads with every request the page (or its service worker)
     * makes; only reads it. A request to one of SoundCloud's API hosts gives the web app's client id
     * and, once signed in, `Authorization: OAuth <token>`: the session, as the page itself uses it.
     */
    private fun watchSoundCloud(request: WebResourceRequest) {
        if (service != SOUNDCLOUD) return
        val host = request.url.host
        if (!SoundCloudBridge.isApiHost(host)) return
        apiSeen.getOrPut(host!!) { AtomicInteger() }.incrementAndGet()
        val url = request.url.toString()
        runCatching { SoundCloudBridge.rememberClientId(applicationContext, url) }
        val auth = request.requestHeaders?.entries?.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.value ?: return
        SoundCloudBridge.schemeOf(auth)?.let { schemesSeen += it }
        val token = SoundCloudBridge.tokenOfAuthorization(auth) ?: return
        if (headerFound?.token != token) headerFound = SoundCloudBridge.Found(token, "header:$host")
    }

    /**
     * SoundCloud's last resort: the page's localStorage, read with one evaluateJavascript on the main
     * frame ([SoundCloudBridge.STORAGE_SCRIPT], a read and nothing else). Only once the page has left
     * the sign-in form ([SoundCloudBridge.looksSignedIn]) and no sign-in window is open above it, so
     * it never runs while the person is typing into the form; nothing is ever injected at document
     * start. [then] runs when the read is back. False when no read was started or is under way.
     */
    private fun readStorage(force: Boolean, then: (() -> Unit)? = null): Boolean {
        if (done || isFinishing || popups.isNotEmpty() || !SoundCloudBridge.looksSignedIn(web.url)) return false
        if (storagePending) { then?.let { storageWaiters += it }; return true }
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastStorageRead < 4_500) return false
        lastStorageRead = now
        storagePending = true
        then?.let { storageWaiters += it }
        val host = Uri.parse(web.url).host
        web.evaluateJavascript(SoundCloudBridge.STORAGE_SCRIPT) { raw ->
            storagePending = false
            val waiting = storageWaiters.toList()
            storageWaiters.clear()
            if (done || isFinishing) return@evaluateJavascript
            storageKeys = SoundCloudBridge.storageKeys(raw)
            storageReadOn = host
            SoundCloudBridge.tokenFromStorage(raw)?.let { storageFound = it }
            if (waiting.isEmpty()) { if (storageFound != null) check() } else waiting.forEach { it() }
        }
        return true
    }

    /** DONE without a session: said to the person (in words that name no app), and for SoundCloud, what was tried. */
    private fun noSession() {
        if (service == SOUNDCLOUD) BridgeNet.log("soundcloud", noSessionReport())
        // Always a way out: a sign-in that cannot finish ("No Spotify account found" after Google, a
        // page whose Back only leads to redirects) must not keep the user on this screen.
        android.app.AlertDialog.Builder(this)
            .setMessage(R.string.th_bridge_login_not_signed_in)
            .setPositiveButton(R.string.th_bridge_login_stay, null)
            .setNegativeButton(R.string.th_bridge_login_leave) { _, _ -> setResult(RESULT_CANCELED); finish() }
            .show()
    }

    /** Which sources were tried, by name only: cookie names, hosts, header schemes and localStorage keys, never a value. */
    /** The cookie jar of this sign-in: the WebView's own, or the extra account's profile. */
    private fun jar(): CookieManager = Slots.cookies(service, slot)

    private fun noSessionReport(): String {
        val cm = jar()
        val jars = SoundCloudBridge.COOKIE_SITES.joinToString(", ") { site ->
            val names = SoundCloudBridge.cookieNames(runCatching { cm.getCookie(site) }.getOrNull())
            "${site.substringAfter("://")} [${names.joinToString(" ")}]"
        }
        val api = apiSeen.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ×${it.value.get()}" }.ifEmpty { "none" }
        val schemes = schemesSeen.sorted().joinToString(", ").ifEmpty { "none" }
        val page = Uri.parse(web.url ?: "").let { u -> "${u.host ?: "none"}${if (SoundCloudBridge.looksSignedIn(web.url)) "" else ", on the sign-in"}" }
        val storage = storageKeys?.let { "read on ${storageReadOn ?: "?"}, keys [${it.joinToString(" ")}], no token" }
            ?: "not read (the page had not left the sign-in, or a sign-in window was open)"
        return "sign-in: DONE without a session. Tried: Authorization header: ${if (headerFound != null) "seen" else "not seen"} " +
            "(API requests: $api; schemes: $schemes; client id seen: ${if (SoundCloudBridge.session.get(applicationContext, "clientId") != null) "yes" else "no"}); " +
            "oauth_token cookie: none in $jars; localStorage: $storage; page: $page"
    }

    private fun botCheck(kind: DataDome.Kind) {
        if (done || isFinishing || kind == seen) return
        seen = kind
        BridgeNet.log(service, when (kind) {
            DataDome.Kind.BLOCKED -> "sign-in: the bot protection (DataDome) blocked the page"
            DataDome.Kind.CAPTCHA -> "sign-in: the bot protection (DataDome) shows a puzzle"
            DataDome.Kind.CHECK -> "sign-in: the bot protection (DataDome) checks the browser"
        })
        // A puzzle or the browser check works in the page itself: nothing is put in its way.
        if (kind != DataDome.Kind.BLOCKED) return
        notice.text = getString(R.string.th_bridge_login_blocked, serviceName())
        notice.visibility = android.view.View.VISIBLE
    }

    private fun serviceOrNull(): String? = if (::service.isInitialized) service else null

    private fun serviceName() = when (service) {
        APPLE -> "Apple Music"
        DEEZER -> "Deezer"
        SOUNDCLOUD -> "SoundCloud"
        LIVEONE -> "LiveOne"
        ANGHAMI -> "Anghami"
        QOBUZ -> "Qobuz"
        else -> "Spotify"
    }

    /** Removes one cookie of [site]'s domain; true when there was one. */
    private fun dropCookie(site: String, name: String): Boolean = runCatching {
        val cm = jar()
        if (WebSession.cookieValue(cm.getCookie(site), name) == null) return@runCatching false
        val domain = site.substringAfter("://").substringBefore('/')
        cm.setCookie(site, "$name=; Max-Age=0; Path=/")
        cm.setCookie(site, "$name=; Max-Age=0; Domain=.$domain; Path=/")
        cm.flush()
        true
    }.getOrDefault(false)

    /** @return true when a session was captured (the screen then closes). */
    private fun check(): Boolean {
        if (done) return true
        if (service == SOUNDCLOUD) return checkSoundCloud()
        if (service == LIVEONE) return checkLiveOne()
        if (service == ANGHAMI || service == QOBUZ) return false
        val origin = when (service) {
            APPLE -> "https://music.apple.com"
            DEEZER -> DeezerBridge.ORIGIN
            else -> "https://open.spotify.com"
        }
        val cookies = jar().getCookie(origin) ?: return false
        val ok = when (service) {
            APPLE -> AppleBridge.signedIn(cookies)
            DEEZER -> DeezerBridge.signedIn(cookies)
            else -> SpotifyBridge.signedIn(cookies)
        }
        if (!ok) return false
        done = true
        val sess = when (service) {
            APPLE -> AppleBridge.session(slot)
            DEEZER -> DeezerBridge.session(slot)
            else -> SpotifyBridge.session(slot)
        }
        // A new sign-in may be another account: nothing cached from the previous one is reused.
        if (service == DEEZER) { sess.put(applicationContext, "sid", null); DeezerWebClient.forget(slot) }
        if (service == SPOTIFY) SpotifyBridge.invalidateToken(slot)
        signedIn(sess, cookies)
        return true
    }

    /**
     * LiveOne: once the page is back on www.liveone.com, the player keeps its session in local
     * storage (`slackerUser`, with the refresh token). Read asynchronously; the screen closes when it
     * holds a usable session. False until then.
     */
    private var liveOneReading = false
    /** The last session tried and refused: not tried again while the page still holds it. */
    private var liveOneRefused: String? = null
    private fun checkLiveOne(): Boolean {
        if (liveOneReading || !::web.isInitialized) return false
        val uri = runCatching { android.net.Uri.parse(web.url ?: "") }.getOrNull() ?: return false
        // After the sign-in LiveOne passes through /helper/onAuthResponse.jsp, before the player
        // exchanges the sign-in for its tokens: the storage there still holds the earlier (visitor) session.
        if (uri.host != "www.liveone.com" || uri.path.orEmpty().startsWith("/helper/")) return false
        liveOneReading = true
        web.evaluateJavascript(LiveOneBridge.STORAGE_SCRIPT) { raw ->
            // evaluateJavascript answers with a JSON string literal.
            val value = runCatching { parseJson(raw ?: "").str }.getOrNull()
            val token = value?.let { LiveOneWebClient.tokenIn(it) }
            if (done || isFinishing || !LiveOneBridge.usable(value) || token == liveOneRefused) { liveOneReading = false; return@evaluateJavascript }
            // Tried before the screen closes: a session LiveOne refuses (a visitor's, one already
            // renewed by the page) is not kept, and the page is read again a moment later.
            verifyLiveOne(value!!, token!!)
        }
        return false
    }

    private fun verifyLiveOne(value: String, token: String) {
        val app = applicationContext
        thread {
            val why = LiveOneBridge.keep(app, value, slot) ?: runCatching { LiveOneWebClient(app, slot).apply { wsToken(renew = true); presets() }; null }
                .getOrElse { it.message ?: it.javaClass.simpleName }
            handler.post {
                liveOneReading = false
                if (done || isFinishing) return@post
                if (why == null) { finishLiveOne(); return@post }
                liveOneRefused = token
                LiveOneBridge.session(slot).clear(app)
                BridgeNet.log("liveone", "sign-in: the page's session was refused ($why); waiting for the page to renew it")
            }
        }
    }

    private fun finishLiveOne() {
        done = true
        BridgeNet.log("liveone", "sign-in: session kept")
        setResult(RESULT_OK)
        finish()
    }

    /** LiveOne: the session pasted from a desktop browser (its `slackerUser` entry). */
    private fun pasteLiveOne() {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        val text = runCatching { cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull().orEmpty()
        if (!LiveOneBridge.usable(text)) {
            val why = LiveOneWebClient.tokenIn(text)?.let { LiveOneWebClient.problemWith(it) } ?: "no LiveOne session in the clipboard"
            android.widget.Toast.makeText(this, "LiveOne: $why", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        keepLiveOne(text)
    }

    /**
     * Anghami: once signed in, the player names its session (sid) on every request to its gateway.
     * The first one seen is tried (reading the playlists) before the screen closes; one Anghami
     * refuses is not tried again.
     */
    @Volatile private var anghamiTrying = false
    private var anghamiRefused: String? = null
    private fun watchAnghami(url: String) {
        if (service != ANGHAMI || done || anghamiTrying) return
        val (sid, fingerprint) = AnghamiBridge.fromRequest(url) ?: return
        if (sid == anghamiRefused) return
        anghamiTrying = true
        handler.post { keepAnghami(sid, fingerprint, fromPage = true) }
    }

    private fun pasteAnghami() {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        val text = runCatching { cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull().orEmpty()
        // A whole request address copied from the developer tools carries the fingerprint too.
        val (sid, fingerprint) = AnghamiWebClient.sessionIn(text) ?: (null to null)
        if (sid == null) {
            android.widget.Toast.makeText(this, "Anghami: no session in the clipboard", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        keepAnghami(sid, fingerprint, fromPage = false)
    }

    private fun keepAnghami(sid: String, fingerprint: String?, fromPage: Boolean) {
        if (done) return
        val app = applicationContext
        thread {
            val why = AnghamiBridge.keep(app, sid, fingerprint, slot) ?: runCatching { AnghamiWebClient(app, slot).playlists(); null }
                .getOrElse { it.message ?: it.javaClass.simpleName }
            handler.post {
                anghamiTrying = false
                if (done || isFinishing) return@post
                if (why == null) {
                    done = true
                    BridgeNet.log("anghami", "sign-in: session kept")
                    setResult(RESULT_OK)
                    finish()
                    return@post
                }
                AnghamiBridge.session(slot).clear(app)
                if (fromPage) { anghamiRefused = sid; BridgeNet.log("anghami", "sign-in: the page's session was refused ($why)") }
                else android.widget.Toast.makeText(this, "Anghami: $why", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Qobuz: once signed in, the player sends its session (X-User-Auth-Token) and its app id
     * (X-App-Id) as headers of every call to www.qobuz.com/api.json. The first one seen is tried
     * (reading the playlists, which also gives the account's name) before the screen closes; one
     * Qobuz refuses is not tried again.
     */
    @Volatile private var qobuzTrying = false
    private var qobuzRefused: String? = null
    private fun watchQobuz(request: WebResourceRequest) {
        if (service != QOBUZ || done || qobuzTrying) return
        val (token, appId) = QobuzBridge.fromRequest(request.url.toString(), request.requestHeaders) ?: return
        if (token == qobuzRefused) return
        qobuzTrying = true
        handler.post { keepQobuz(token, appId, fromPage = true) }
    }

    private fun pasteQobuz() {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        val text = runCatching { cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull().orEmpty()
        // A whole request copied from the developer tools (as cURL) carries the app id too.
        val (token, appId) = QobuzWebClient.sessionIn(text) ?: (null to null)
        if (token == null) {
            android.widget.Toast.makeText(this, "Qobuz: no session in the clipboard", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        keepQobuz(token, appId, fromPage = false)
    }

    private fun keepQobuz(token: String, appId: String?, fromPage: Boolean) {
        if (done) { qobuzTrying = false; return }
        val app = applicationContext
        thread {
            var account: String? = null
            val why = QobuzBridge.keep(app, token, appId, slot = slot) ?: runCatching { account = QobuzWebClient(app, slot).probe(); null }
                .getOrElse { it.message ?: it.javaClass.simpleName }
            handler.post {
                qobuzTrying = false
                if (done || isFinishing) return@post
                if (why == null) {
                    done = true
                    account?.let { QobuzBridge.name(app, it, slot) }
                    BridgeNet.log("qobuz", "sign-in: session kept")
                    setResult(RESULT_OK)
                    finish()
                    return@post
                }
                QobuzBridge.session(slot).clear(app)
                if (fromPage) { qobuzRefused = token; BridgeNet.log("qobuz", "sign-in: the page's session was refused ($why)") }
                else android.widget.Toast.makeText(this, "Qobuz: $why", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun keepLiveOne(value: String) {
        if (done) return
        val app = applicationContext
        thread {
            val why = LiveOneBridge.keep(app, value, slot) ?: runCatching { LiveOneWebClient(app, slot).apply { wsToken(renew = true); presets() }; null }
                .getOrElse { it.message ?: it.javaClass.simpleName }
            handler.post {
                if (done || isFinishing) return@post
                if (why == null) return@post finishLiveOne()
                LiveOneBridge.session(slot).clear(app)
                android.widget.Toast.makeText(this, "LiveOne: $why", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * SoundCloud: the token from the first source that has it ([SoundCloudBridge.pick]: the page's
     * Authorization header, the cookie of any SoundCloud host, localStorage), kept with the client
     * id (already kept as the page's requests went by), the sign-in's user agent and the cookies.
     */
    private fun checkSoundCloud(): Boolean {
        val cm = jar()
        val jars = SoundCloudBridge.COOKIE_SITES.associateWith { runCatching { cm.getCookie(it) }.getOrNull() }
        val found = SoundCloudBridge.pick(headerFound, jars, storageFound) ?: return false
        done = true
        val app = applicationContext
        val sess = SoundCloudBridge.session
        // A new sign-in may be another account: nothing cached from the previous one is reused.
        sess.put(app, "userId", null)
        SoundCloudBridge.rememberToken(app, found)
        SoundCloudBridge.rememberUserAgent(app, web.settings.userAgentString)
        BridgeNet.log("soundcloud", "sign-in: session taken from ${found.source}")
        signedIn(sess, SoundCloudBridge.mergeCookies(jars.values.toList()))
        return true
    }

    private fun signedIn(sess: WebSession, cookies: String) {
        val app = applicationContext
        sess.save(app, cookies, null)
        // Close at once: the sign-in is done. The account name needs a token or an API call, which can
        // take a while, so it is fetched afterwards and shown when the screen refreshes.
        setResult(RESULT_OK)
        finish()
        thread {
            val account = runCatching {
                when (service) {
                    APPLE -> AppleBridge.storefront(app, slot)?.let { "Apple Music (${it.uppercase()})" }
                    DEEZER -> DeezerBridge.accountName(app, slot)
                    SOUNDCLOUD -> SoundCloudBridge.accountName(app)
                    else -> SpotifyBridge.accountInfo(app, slot).let { (id, name) -> id?.let { sess.put(app, "userId", it) }; name }
                }
            }.getOrNull()
            if (account != null) sess.save(app, cookies, account)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // The service-worker observer was this screen's: it goes with it.
        if (serviceOrNull() == SOUNDCLOUD && WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) runCatching {
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = null
            })
        }
        popups.forEach { runCatching { it.destroy() } }
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SPOTIFY = "com.xlollx.songport.ytmbridge.SPOTIFY_LOGIN"
        const val ACTION_APPLE = "com.xlollx.songport.ytmbridge.APPLE_LOGIN"
        const val ACTION_DEEZER = "com.xlollx.songport.ytmbridge.DEEZER_LOGIN"
        const val ACTION_SOUNDCLOUD = "com.xlollx.songport.ytmbridge.SOUNDCLOUD_LOGIN"
        const val ACTION_LIVEONE = "com.xlollx.songport.ytmbridge.LIVEONE_LOGIN"
        const val ACTION_ANGHAMI = "com.xlollx.songport.ytmbridge.ANGHAMI_LOGIN"
        const val ACTION_QOBUZ = "com.xlollx.songport.ytmbridge.QOBUZ_LOGIN"
        const val EXTRA_SERVICE = "service"
        const val SPOTIFY = "spotify"
        const val APPLE = "apple"
        const val DEEZER = "deezer"
        const val SOUNDCLOUD = "soundcloud"
        const val LIVEONE = "liveone"
        const val ANGHAMI = "anghami"
        const val QOBUZ = "qobuz"

        /** The sign-in of [service], for the primary account or for the extra account [slot] (see [Slots]). */
        fun intent(ctx: android.content.Context, service: String, slot: String = Slots.PRIMARY): Intent =
            Intent(ctx, WebLoginActivity::class.java).setAction(when (service) {
                APPLE -> ACTION_APPLE
                DEEZER -> ACTION_DEEZER
                SOUNDCLOUD -> ACTION_SOUNDCLOUD
                LIVEONE -> ACTION_LIVEONE
                ANGHAMI -> ACTION_ANGHAMI
                QOBUZ -> ACTION_QOBUZ
                else -> ACTION_SPOTIFY
            }).apply { if (slot.isNotEmpty()) putExtra(Slots.EXTRA, slot) }
    }
}
