package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback

/**
 * Google's "Sorry..." verification, shown to the person who can pass it.
 *
 * When Google flags the phone's address as sending unusual traffic, every YouTube Music call gets
 * that page instead of an answer. The page is a captcha: passing it once in a browser sets an
 * exemption cookie for the address, and the calls flow again. This screen opens the very page the
 * browser engine was redirected to, in the same cookie jar the engine uses, so the exemption applies
 * to the sync at once. It closes by itself when Google sends the browser on to music.youtube.com.
 */
class VerifyActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hint = TextView(this).apply { text = getString(R.string.th_bridge_verify_hint); textSize = 12f; setPadding(24, 12, 24, 12) }
        web = WebView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        root.addView(hint); root.addView(web)
        setContentView(root)
        BridgeInsets.fit(root)

        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }
        web.settings.apply { javaScriptEnabled = true; domStorageEnabled = true }
        web.webViewClient = object : SafeWebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                // Past the captcha Google follows the "continue" address: the block is lifted.
                val host = Uri.parse(url).host ?: return
                if (host.endsWith("youtube.com") && !url.contains("/sorry/") && !url.startsWith("data:")) {
                    CookieManager.getInstance().flush()
                    YtmClient.Verification.passed(applicationContext)
                    finish()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this) { if (web.canGoBack()) web.goBack() else finish() }
        // Google's page as it came back to the engine. If it carries a captcha its form posts to the
        // same host; if it only announces the block, the person at least reads Google's own words.
        val html = YtmClient.Verification.html(applicationContext)
        if (html != null) web.loadDataWithBaseURL("${Session.ORIGIN}/sorry/index", html, "text/html", "utf-8", null)
        else web.loadUrl("${Session.ORIGIN}/")
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
