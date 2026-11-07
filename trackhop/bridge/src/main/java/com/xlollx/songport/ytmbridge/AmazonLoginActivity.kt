package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import kotlin.concurrent.thread

/**
 * Amazon sign-in inside a WebView. Amazon redirects music.amazon.com to the account's regional site;
 * as soon as the cookie jar of that site holds an `at-*` token the session is stored encrypted and
 * the screen closes. The account name is fetched afterwards and is not required: a web player that
 * answers something unexpected must not leave the user trapped in this screen, so there is also a
 * "Done" button that closes it with whatever session is there.
 */
class AmazonLoginActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }
    private lateinit var web: WebView
    private lateinit var hintView: TextView
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var left = false
    /** When the player's page first showed a signed-in cookie jar; 0 while not on the player. */
    private var signedSince = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Disclaimer.require(this) { setup() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setup() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24, 12, 24, 12) }
        val hint = TextView(this).apply {
            text = getString(R.string.th_bridge_login_hint)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        hintView = hint
        val doneButton = Button(this).apply {
            text = getString(R.string.th_bridge_login_done)
            setOnClickListener { capture(manual = true) }
        }
        bar.addView(hint); bar.addView(doneButton)
        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(bar); root.addView(web)
        setContentView(root)
        BridgeInsets.fit(root)

        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        // The desktop player: its own calls are accepted on this session (traffic capture, 1.0.57).
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        BridgeUa.apply(this, web)
        // The player runs here, in front of the user: the surest place to see the headers it builds
        // for its own API calls, which are the only thing carrying an access token.
        val early = AmazonBridge.install(this, web)
        web.webViewClient = object : SafeWebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!early) view.evaluateJavascript(AmazonBridge.HOOK, null)
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (!early) view.evaluateJavascript(AmazonBridge.HOOK, null)
                if (AmazonClient.isMusicDomain(Uri.parse(url).host)) view.evaluateJavascript(AmazonBridge.CONFIG, null)
                capture()
            }
        }
        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) web.goBack() else { setResult(RESULT_CANCELED); finish() }
        }
        // A new sign-in starts from an empty jar: old Amazon cookies would ride along with the new ones.
        val wiped = AmazonSession.wipeCookies()
        BridgeNet.log("amazon", "sign-in started ($wiped old Amazon cookies removed first; ${BridgeUa.describe()})")
        web.loadUrl(LOGIN_URL)
        // The player is a single page app: navigation does not always report a finished page.
        handler.postDelayed(object : Runnable {
            override fun run() { if (!left && !isFinishing) { capture(); handler.postDelayed(this, 1500) } }
        }, 1500)
    }

    /**
     * Closes the screen with the session once the player has really started for this account.
     *
     * Cookies alone are not that signal. Amazon signs you in on amazon.com first, then sends an
     * Italian account to music.amazon.it, whose own sign-in hop still has to complete: closing on the
     * first `at-*` cookie kept music.amazon.com, and every later request was sent back to the sign-in
     * page. What proves the player runs is the set of headers it builds for its first call, seen by
     * the hook installed in this WebView. So the automatic close waits for those, and only after a
     * long patience on the player's page, or when the user taps Done, falls back to the cookies.
     */
    private fun capture(manual: Boolean = false) {
        if (left) return
        val cm = CookieManager.getInstance()
        val app = applicationContext
        val current = Uri.parse(web.url ?: "").host?.takeIf { AmazonClient.isMusicDomain(it) }
        val ready = AmazonBridge.ready(app)

        if (!manual && !ready) {
            // On the player's page with a signed-in jar: tell the user why the screen stays, and start
            // the patience clock. Off the player (a sign-in page again): reset it, the user is typing.
            val onPlayer = current != null && AmazonSession.looksSignedIn(cm.getCookie("https://$current"))
            if (!onPlayer) { signedSince = 0L; hintView.text = getString(R.string.th_bridge_login_hint); return }
            if (signedSince == 0L) { signedSince = System.currentTimeMillis(); hintView.text = getString(R.string.th_bridge_login_finishing) }
            if (System.currentTimeMillis() - signedSince < PATIENCE_MS) return
        }

        // The site to keep: where the player's headers were seen, else the player page on screen;
        // any signed-in Amazon domain only as a last resort (Done, or patience over).
        val candidates = listOfNotNull(AmazonSession.headersHost(app).takeIf { ready }, current) + AmazonClient.DOMAINS.keys
        val hit = candidates.distinct()
            .map { it to cm.getCookie("https://$it") }
            .firstOrNull { (_, ck) -> AmazonSession.looksSignedIn(ck) }
        if (hit == null) {
            if (manual) Toast.makeText(this, R.string.th_bridge_login_not_signed_in, Toast.LENGTH_LONG).show()
            return
        }
        val host = hit.first
        val cookies = hit.second ?: return
        AmazonSession.save(app, cookies, host, null)
        AmazonClient.forgetConfig()
        BridgeNet.log("amazon", "connected on $host (${if (ready) "player started" else if (manual) "Done tapped" else "after waiting"}, " +
            "${cookies.split(';').size} cookies)")
        // The display name comes from the player configuration; a failure here is not a failed login.
        thread {
            val name = runCatching { AmazonClient(app).config(host, cookies).customerName }.getOrNull()
            if (name != null) AmazonSession.save(app, cookies, host, name)
        }
        leave()
    }

    private fun leave() {
        if (left) return
        left = true
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        const val ACTION = "com.xlollx.songport.ytmbridge.AMAZON_LOGIN"
        private const val LOGIN_URL = "https://music.amazon.com/"
        /** How long to stay on the player's page waiting for its first call before settling for the cookies. */
        private const val PATIENCE_MS = 60_000L
    }
}
