package com.xlollx.songport.auth

import com.xlollx.songport.providers.*
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.providers.AppleMusicProvider
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.launch

/**
 * Login Apple Music. Apple non ha un OAuth "da app": il *music user token* si ottiene solo con
 * MusicKit JS, che qui gira in una WebView su una pagina servita dagli asset dell'app
 * (https://appassets.androidplatform.net/, un'origine https vera: MusicKit richiede https e
 * localStorage). La schermata di Apple ID si apre in una finestra popup, gestita in onCreateWindow.
 */
class AppleAuthActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.xlollx.songport.ui.AppLocale.wrap(newBase))
    }

    private lateinit var container: FrameLayout
    private var popup: WebView? = null
    private var finished = false
    private lateinit var provider: AppleMusicProvider

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this)
        setContentView(container)

        val provider = intent.getStringExtra(EXTRA_PROVIDER)?.let { Providers.byId(it) as? AppleMusicProvider } ?: AppleMusicProvider()
        this.provider = provider
        val developerToken = provider.developerToken(this)
        if (developerToken.isBlank()) {
            finishWith(getString(R.string.th_apple_token_missing))
            return
        }

        val web = newWebView()
        // L'interfaccia JS sta solo sulla pagina nostra: il popup di Apple non la riceve.
        web.addJavascriptInterface(Bridge(), "Bridge")
        container.addView(web, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.loadUrl(
            "https://appassets.androidplatform.net/assets/applemusic/auth.html" +
                "?dt=" + Uri.encode(developerToken)
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        CookieManager.getInstance().setAcceptCookie(true)
        webViewClient = object : SafeWebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)
        }
        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                // MusicKit apre l'accesso Apple ID in una nuova finestra: la mostriamo sopra.
                val child = newWebView()
                popup = child
                container.addView(child, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                CookieManager.getInstance().setAcceptThirdPartyCookies(child, true)
                (resultMsg.obj as WebView.WebViewTransport).webView = child
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                if (window === popup) {
                    container.removeView(window)
                    window.destroy()
                    popup = null
                }
            }
        }
    }

    override fun onBackPressed() {
        val p = popup
        if (p != null) {
            container.removeView(p)
            p.destroy()
            popup = null
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun finishWith(message: String) {
        if (finished) return
        finished = true
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(MainActivity.EXTRA_MESSAGE, message)
                putExtra(MainActivity.EXTRA_TAB, 1)
            }
        )
        finish()
    }

    /** Ponte JS -> Kotlin: riceve il music user token (o l'errore) da MusicKit. */
    private inner class Bridge {
        @JavascriptInterface
        fun onToken(token: String) {
            runOnUiThread {
                if (finished || token.isBlank()) return@runOnUiThread
                lifecycleScope.launch {
                    val msg = try {
                        provider.completeAuth(this@AppleAuthActivity, mapOf("music_user_token" to token), "")
                        getString(R.string.th_auth_done, provider.displayName)
                    } catch (e: Exception) {
                        getString(R.string.th_auth_failed, e.message ?: e.javaClass.simpleName)
                    }
                    finishWith(msg)
                }
            }
        }

        @JavascriptInterface
        fun onError(message: String) {
            // Non chiudiamo: la pagina mostra l'errore e il pulsante per riprovare.
        }
    }

    companion object {
        const val EXTRA_PROVIDER = "provider"
    }
}
