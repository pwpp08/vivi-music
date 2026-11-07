package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Renews the SoundCloud session without the person: the token the sign-in kept is the mobile site's
 * `oauth_token`, which lasts about an hour, like the site's own access tokens. The site renews it
 * by itself when it is opened, so the Bridge opens it: SoundCloud's mobile site in a hidden WebView,
 * with the WebView's cookie jar (the one the sign-in left), and takes the new token the way the
 * sign-in does ([SoundCloudBridge.pick]), from the Authorization header of the page's own API
 * requests or from the `oauth_token` cookie.
 *
 * The page runs as the sign-in ran (see [WebLoginActivity.identity]): the WebView's own user agent,
 * without the app header, no script injected, every request only observed and left as the page
 * made it. Like [AmazonBridge]'s hidden player: a WebView made on the main thread, a latch, a timeout.
 *
 * When a renewal fails (no new token in time, DataDome's puzzle or block, a page that would not
 * load) the caller keeps the "sign in again" error; the log says why. Tokens are never logged.
 */
object SoundCloudRenewal {
    /** What a hidden page gave: a token other than the old one and the jar as it was then, or why not. */
    class Page(val found: SoundCloudBridge.Found?, val cookies: String?, val problem: String?)

    /** The hidden page, replaced in tests (a JVM test has no WebView). [old] is the token to replace. */
    @Volatile var hiddenPage: (Context, String?) -> Page = { ctx, old -> openHidden(ctx, old) }

    /** A token older than this is renewed before the next call, where its age is known. */
    const val RENEW_AFTER_MS = 50 * 60_000L
    /** One ahead-of-time attempt per this long: a page that renews nothing is not reopened on every call. */
    private const val PROACTIVE_EVERY_MS = 15 * 60_000L
    private const val TIMEOUT_S = 40L
    /** After the page has loaded, how long a renewal is waited for before giving up early. */
    private const val SETTLE_MS = 12_000L
    private const val VIEW_W = 412
    private const val VIEW_H = 915

    private val lastProactive = AtomicLong(0)

    fun forget() { lastProactive.set(0) }

    /**
     * Before a call: when the token is older than about 50 minutes ([SoundCloudBridge.tokenAt]),
     * renewed ahead of time, at most once every 15 minutes. A failure here is only logged: the call
     * goes on with the token there is, and a refusal renews again.
     */
    fun renewIfOld(ctx: Context) {
        val at = SoundCloudBridge.tokenAt(ctx) ?: return
        val now = System.currentTimeMillis()
        val age = now - at
        if (age < RENEW_AFTER_MS) return
        val last = lastProactive.get()
        if (now - last < PROACTIVE_EVERY_MS || !lastProactive.compareAndSet(last, now)) return
        renew(ctx, SoundCloudBridge.token(ctx), "the token is ${age / 60_000} minutes old")
    }

    /**
     * Opens the hidden page and keeps the new token it gives. [refused] is the token a call was just
     * refused with: when the kept one is already another (renewed a moment ago by another call), that
     * one is used without opening anything. True when there is a new token to retry with.
     */
    @Synchronized
    fun renew(ctx: Context, refused: String?, why: String): Boolean {
        val app = ctx.applicationContext
        val current = SoundCloudBridge.token(app)
        if (current != null && current != refused) return true
        val started = System.currentTimeMillis()
        BridgeNet.log("soundcloud", "renewing the session in a hidden page ($why)")
        val page = runCatching { hiddenPage(app, current) }.getOrElse { Page(null, null, it.message ?: it.javaClass.simpleName) }
        val found = page.found
        if (found == null || found.token == current) {
            BridgeNet.log("soundcloud", "the session could not be renewed: ${page.problem ?: "the page gave no new token"}")
            return false
        }
        SoundCloudBridge.rememberRenewedToken(app, found)
        page.cookies?.takeIf { it.isNotBlank() }?.let { SoundCloudBridge.session.save(app, it, SoundCloudBridge.session.account(app)) }
        BridgeNet.log("soundcloud", "session renewed in a hidden page: new token from ${found.source} in ${(System.currentTimeMillis() - started) / 1000} s")
        return true
    }

    /**
     * The token a renewal found: the page's Authorization header first, then the cookie of any
     * SoundCloud host, as at sign-in, and only one other than [old] (the page may send the old one
     * before it renews).
     */
    fun newToken(header: SoundCloudBridge.Found?, cookies: Map<String, String?>, old: String?): SoundCloudBridge.Found? {
        val fresh = header?.takeIf { it.token != old }
        val jars = cookies.mapValues { (_, jar) -> jar?.takeIf { SoundCloudBridge.oauthToken(it) != old } }
        return SoundCloudBridge.pick(fresh, jars, null)
    }

    /** Why a DataDome page ends a renewal: there is nobody to solve or wait out anything in a hidden page. */
    fun dataDomeProblem(kind: DataDome.Kind): String? = when (kind) {
        DataDome.Kind.CAPTCHA -> "the bot protection (DataDome) showed a puzzle in the hidden page, which only a person can solve"
        DataDome.Kind.BLOCKED -> "the bot protection (DataDome) blocked the hidden page"
        DataDome.Kind.CHECK -> null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openHidden(app: Context, old: String?): Page {
        if (Looper.myLooper() == Looper.getMainLooper()) return Page(null, null, "asked on the main thread")
        val main = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val header = AtomicReference<SoundCloudBridge.Found?>(null)
        val problem = AtomicReference<String?>(null)
        val loadedAt = AtomicLong(0)
        val over = AtomicBoolean(false)
        var web: WebView? = null
        val cm = CookieManager.getInstance()
        fun jars() = SoundCloudBridge.COOKIE_SITES.associateWith { runCatching { cm.getCookie(it) }.getOrNull() }

        // Only watched, as at sign-in: the client id and the token the page's API calls carry.
        fun watch(request: WebResourceRequest) {
            DataDome.kindOf(request.url.toString())?.let { kind -> dataDomeProblem(kind)?.let { problem.compareAndSet(null, it); latch.countDown() } }
            val host = request.url.host
            if (!SoundCloudBridge.isApiHost(host)) return
            runCatching { SoundCloudBridge.rememberClientId(app, request.url.toString()) }
            val auth = request.requestHeaders?.entries?.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.value ?: return
            val token = SoundCloudBridge.tokenOfAuthorization(auth) ?: return
            if (token != old && header.compareAndSet(null, SoundCloudBridge.Found(token, "header:$host"))) latch.countDown()
        }
        val workers = WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)

        main.post {
            try {
                cm.setAcceptCookie(true)
                val w = WebView(app)
                web = w
                w.settings.apply { javaScriptEnabled = true; domStorageEnabled = true }
                cm.setAcceptThirdPartyCookies(w, true)
                // The sign-in's identity: the WebView's own user agent, without the app header, no script.
                BridgeUa.dropAppHeader(w)
                // A page nobody sees still needs a size to lay itself out and start.
                w.measure(View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(VIEW_H, View.MeasureSpec.EXACTLY))
                w.layout(0, 0, VIEW_W, VIEW_H)
                if (workers) runCatching {
                    ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                        override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? { watch(request); return null }
                    })
                }
                w.webViewClient = object : SafeWebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? { watch(request); return null }
                    // "Open in app" and the stores: the page stays where it is.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        SoundCloudBridge.isAppHandoff(request.url.toString())
                    override fun onPageFinished(view: WebView, url: String) { loadedAt.compareAndSet(0, System.currentTimeMillis()) }
                    override fun onReceivedError(view: WebView, req: WebResourceRequest, err: android.webkit.WebResourceError) {
                        if (req.isForMainFrame) { problem.compareAndSet(null, "the page did not load (error ${err.errorCode})"); latch.countDown() }
                    }
                }
                w.loadUrl(SoundCloudBridge.MOBILE_SITE + "/")
                // The cookie the page renews has no request to show it: the jar is looked at every second,
                // and a page that has loaded and renewed nothing for a while is given up on.
                main.postDelayed(object : Runnable {
                    override fun run() {
                        if (over.get()) return
                        if (newToken(null, jars(), old) != null) { latch.countDown(); return }
                        val at = loadedAt.get()
                        if (at > 0 && System.currentTimeMillis() - at > SETTLE_MS) { problem.compareAndSet(null, "the page loaded but renewed nothing"); latch.countDown(); return }
                        main.postDelayed(this, 1_000)
                    }
                }, 1_000)
            } catch (e: Exception) {
                problem.compareAndSet(null, e.message ?: e.javaClass.simpleName)
                latch.countDown()
            }
        }
        val inTime = latch.await(TIMEOUT_S, TimeUnit.SECONDS)
        over.set(true)
        val result = AtomicReference<Page>()
        val done = CountDownLatch(1)
        main.post {
            try {
                val jars = jars()
                val found = newToken(header.get(), jars, old)
                val why = problem.get() ?: if (!inTime) "no new token within $TIMEOUT_S s" else null
                // DataDome's page means the renewal failed, whatever the jar says.
                val fail = problem.get()?.contains("DataDome") == true
                result.set(Page(if (fail) null else found, SoundCloudBridge.mergeCookies(jars.values.toList()), if (fail || found == null) why else null))
            } finally {
                runCatching { web?.stopLoading(); web?.destroy() }
                if (workers) runCatching {
                    ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                        override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = null
                    })
                }
                done.countDown()
            }
        }
        done.await(10, TimeUnit.SECONDS)
        return result.get() ?: Page(null, null, "the hidden page did not close in time")
    }
}
