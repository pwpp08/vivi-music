package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import kotlin.concurrent.thread

/**
 * The user signs in on accounts.google.com inside a WebView, exactly as in a browser. When Google
 * lands on music.youtube.com with a session cookie, the cookies are stored encrypted and the
 * activity closes. Trackhop starts this screen with the action `com.xlollx.songport.ytmbridge.LOGIN`.
 *
 * The WebView keeps its default user agent on purpose. Overriding it with a desktop string makes
 * Google reject the sign-in ("this browser or app may not be secure") because the string no longer
 * matches the client hints the WebView sends; the desktop identity is only used for API calls.
 */
class LoginActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }
    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var done = false
    /** The account this sign-in is for: "" for the one there has always been, "2", "3"… for an extra one (see [Slots]). */
    private var slot: String = Slots.PRIMARY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        slot = Slots.valid(intent.getStringExtra(Slots.EXTRA))
        if (slot.isNotEmpty() && !Slots.supported()) {
            android.widget.Toast.makeText(this, Slots.UNSUPPORTED, android.widget.Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED); finish(); return
        }
        // The liability notice comes first, also when Trackhop starts this screen directly.
        Disclaimer.require(this) { setup() }
    }

    /** The cookie jar of this sign-in: the WebView's own, or the extra account's profile. */
    private fun jar(): CookieManager = Slots.cookies(Session.PROFILE, slot)

    @SuppressLint("SetJavaScriptEnabled")
    private fun setup() {
        web = WebView(this)
        // An extra account signs in inside its own profile: set before anything loads.
        Slots.apply(web, Session.PROFILE, slot)
        // In a frame that keeps clear of the status bar (the screen draws edge to edge).
        val root = android.widget.FrameLayout(this).apply { addView(web) }
        setContentView(root)
        BridgeInsets.fit(root)
        jar().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        web.webViewClient = object : SafeWebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme
                if (scheme == "http" || scheme == "https") return false
                // Google may offer "open in app" links (intent://). Hand them to the system instead of
                // letting the WebView fail on them.
                if (scheme == "intent") {
                    runCatching {
                        Intent.parseUri(request.url.toString(), Intent.URI_INTENT_SCHEME).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                            component = null
                            selector = null
                        }.let(::startActivity)
                    }
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (done || !url.startsWith(Session.ORIGIN)) return
                pollCookies(attemptsLeft = 20)
            }
        }
        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) web.goBack() else { setResult(RESULT_CANCELED); finish() }
        }
        web.loadUrl(LOGIN_URL)
    }

    /** Cookies can land a moment after the page reports finished: check a few times before giving up. */
    private fun pollCookies(attemptsLeft: Int) {
        if (done) return
        val cookies = jar().getCookie(Session.ORIGIN)
        if (cookies != null && Session.sapisid(cookies) != null) {
            done = true
            finishWith(cookies)
            return
        }
        if (attemptsLeft > 0) handler.postDelayed({ pollCookies(attemptsLeft - 1) }, 500)
    }

    private fun finishWith(cookies: String) {
        val session = Session.forSlot(slot)
        session.save(this, cookies, null)
        thread {
            val name = runCatching { YtmClient(applicationContext, slot).accountName() }.getOrNull()
            session.save(applicationContext, cookies, name)
            runOnUiThread {
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        const val ACTION = "com.xlollx.songport.ytmbridge.LOGIN"
        private const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
    }
}
