package com.xlollx.songport.auth

import com.xlollx.songport.providers.*
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import com.xlollx.songport.R
import com.xlollx.songport.ui.AppLocale

/**
 * The official OAuth sign-in of a service, inside the app instead of a browser tab.
 *
 * A browser tab hands "Continue with Facebook" to the Facebook app when it is installed, and the
 * app's dialog often fails ("problems loading"), leaving the user with no way back to the service.
 * Here the service's own sign-in page runs in a WebView that keeps every link on the page (an app
 * scheme is ignored, a popup opens above the page) and only watches for the one address that ends
 * the flow, the redirect back to Trackhop, which it hands to [AuthCallbackActivity] as the browser
 * would. Nothing typed on the page is read: the page is the service's, the WebView only shows it.
 *
 * Google's sign-in refuses embedded views, so YouTube keeps the browser tab.
 */
class WebAuthActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }

    private lateinit var web: WebView
    private lateinit var frame: FrameLayout
    private val popups = ArrayList<WebView>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val service = intent.getStringExtra(EXTRA_SERVICE) ?: ""
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24, 12, 24, 12) }
        bar.addView(TextView(this).apply {
            text = getString(R.string.th_web_auth_hint, service)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(Button(this).apply { text = getString(R.string.th_cancel); setOnClickListener { finish() } })
        frame = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        web = WebView(this).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        frame.addView(web)
        root.addView(bar); root.addView(frame)
        setContentView(root)
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }
        setup(web)
        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val popup = WebView(this@WebAuthActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    setup(this)
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
                else -> finish()
            }
        }
        web.loadUrl(url)
    }

    private fun setup(w: WebView) {
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            // Sign-in pages of third parties inside the service's page (Google's) refuse an embedded
            // view by its user agent: a desktop browser identity, at this phone's WebView version.
            userAgentString = desktopUserAgent()
        }
        // The client hints too (sec-ch-ua, navigator.userAgentData): a WebView says "Android WebView,
        // mobile" through them whatever the string says, and sign-in pages read them.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) runCatching {
            val full = Regex("""Chrome/([\d.]+)""").find(w.settings.userAgentString)?.groupValues?.get(1) ?: "140.0.0.0"
            val major = full.substringBefore('.')
            val brands = listOf(
                UserAgentMetadata.BrandVersion.Builder().setBrand("Chromium").setMajorVersion(major).setFullVersion(full).build(),
                UserAgentMetadata.BrandVersion.Builder().setBrand("Google Chrome").setMajorVersion(major).setFullVersion(full).build(),
                UserAgentMetadata.BrandVersion.Builder().setBrand("Not:A-Brand").setMajorVersion("99").setFullVersion("99.0.0.0").build(),
            )
            WebSettingsCompat.setUserAgentMetadata(w.settings, UserAgentMetadata.Builder()
                .setBrandVersionList(brands).setFullVersion(full).setPlatform("Windows").setPlatformVersion("15.0.0")
                .setArchitecture("x86").setBitness(64).setMobile(false).setModel("").build())
        }
        w.webViewClient = object : SafeWebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = handle(request.url)
        }
    }

    /** The redirect ends the flow; web links stay on the page; app schemes (Facebook's, intents) are ignored. */
    private fun handle(uri: Uri): Boolean {
        val s = uri.toString()
        if (s.startsWith(AuthFlow.REDIRECT_URI)) {
            startActivity(Intent(this, AuthCallbackActivity::class.java).setData(uri))
            finish()
            return true
        }
        return uri.scheme != "http" && uri.scheme != "https"
    }

    private fun closePopup(w: WebView) {
        popups.remove(w)
        frame.removeView(w)
        runCatching { w.destroy() }
    }

    private fun desktopUserAgent(): String = runCatching {
        val ver = Regex("""Chrome/(\d+(?:\.\d+)*)""").find(WebSettings.getDefaultUserAgent(this))?.groupValues?.get(1)
        if (ver == null) FALLBACK_UA else "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$ver Safari/537.36"
    }.getOrDefault(FALLBACK_UA)

    override fun onDestroy() {
        popups.forEach { runCatching { it.destroy() } }
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_SERVICE = "service"
        private const val FALLBACK_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    }
}
