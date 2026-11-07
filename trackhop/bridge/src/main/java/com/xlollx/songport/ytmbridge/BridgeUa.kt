package com.xlollx.songport.ytmbridge

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * The desktop browser identity the web connectors use: Chrome on Windows, at the version of this
 * phone's own WebView. A fixed version ages, and the players check it ("Update Chrome" on Amazon,
 * "unsupported browser" on Spotify); the WebView's version is the one Google ships to this phone.
 *
 * The user agent string is not the whole identity: a WebView also sends client hints (the
 * `sec-ch-ua` headers, `navigator.userAgentData`) that say "Android WebView, mobile" whatever the
 * string says, and Amazon reads those. [apply] sets both to the same desktop Chrome.
 */
object BridgeUa {
    const val FALLBACK = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    @Volatile private var cached: String? = null

    fun desktop(ctx: Context): String = cached ?: runCatching {
        val own = WebSettings.getDefaultUserAgent(ctx.applicationContext)
        val ver = Regex("""Chrome/(\d+(?:\.\d+)*)""").find(own)?.groupValues?.get(1) ?: return@runCatching FALLBACK
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$ver Safari/537.36"
    }.getOrDefault(FALLBACK).also { cached = it }

    private fun version(ua: String): Pair<String, String> {
        val full = Regex("""Chrome/([\d.]+)""").find(ua)?.groupValues?.get(1) ?: "140.0.0.0"
        return full.substringBefore('.') to full
    }

    /** The desktop identity on a WebView: the string, and the client hints where the WebView supports setting them. */
    fun apply(ctx: Context, web: WebView) {
        // The WebView's own client hints, kept to give back on Google's sign-in pages ([native]).
        val own = applied[web]?.native ?: if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA))
            runCatching { WebSettingsCompat.getUserAgentMetadata(web.settings) }.getOrNull() else null
        val ua = desktop(ctx)
        web.settings.userAgentString = ua
        val (major, full) = version(ua)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) runCatching {
            val brands = listOf(
                UserAgentMetadata.BrandVersion.Builder().setBrand("Chromium").setMajorVersion(major).setFullVersion(full).build(),
                UserAgentMetadata.BrandVersion.Builder().setBrand("Google Chrome").setMajorVersion(major).setFullVersion(full).build(),
                UserAgentMetadata.BrandVersion.Builder().setBrand("Not:A-Brand").setMajorVersion("99").setFullVersion("99.0.0.0").build(),
            )
            val md = UserAgentMetadata.Builder()
                .setBrandVersionList(brands).setFullVersion(full)
                .setPlatform("Windows").setPlatformVersion("15.0.0")
                .setArchitecture("x86").setBitness(64)
                .setMobile(false).setModel("")
                .build()
            WebSettingsCompat.setUserAgentMetadata(web.settings, md)
        }
        dropAppHeader(web)
        // The page-side view of the same hints, for a WebView that cannot set them natively.
        val script = if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) runCatching {
            WebViewCompat.addDocumentStartJavaScript(web, hintsScript(major, full, "Windows", false, "", "15.0.0", "x86"), setOf("*"))
        }.getOrNull() else null
        applied[web] = Applied(own, script)
    }

    /** What [apply] changed on a WebView, to undo on Google's pages. Main thread only, as WebViews are. */
    private class Applied(val native: UserAgentMetadata?, val script: androidx.webkit.ScriptHandler?, var isNative: Boolean = false)
    private val applied = java.util.WeakHashMap<WebView, Applied>()

    /**
     * Google's own sign-in ("Continue with Google" on Spotify, Deezer…) turns a desktop identity on a
     * phone away: "This browser or app may not be secure". There the WebView is itself again, as in
     * the YouTube Music sign-in that Google accepts: its own user agent and client hints. True when
     * something changed, so the page is loaded again with it. [desktopAgain] undoes it.
     */
    fun native(web: WebView): Boolean {
        val a = applied[web] ?: return false
        if (a.isNative) return false
        web.settings.userAgentString = null
        a.native?.let { md -> runCatching { WebSettingsCompat.setUserAgentMetadata(web.settings, md) } }
        runCatching { a.script?.remove() }
        a.isNative = true
        return true
    }

    /** Back to the desktop identity after Google's pages; true when something changed. */
    fun desktopAgain(ctx: Context, web: WebView): Boolean {
        val a = applied[web] ?: return false
        if (!a.isNative) return false
        apply(ctx, web)
        return true
    }

    /** Google's sign-in hosts, where [native] applies. */
    fun isGoogleSignIn(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "accounts.google.com" || h.endsWith(".google.com") || h == "google.com" || h == "accounts.youtube.com" || h.endsWith(".gstatic.com")
    }

    /**
     * A WebView adds "X-Requested-With: <app package>" to its requests, which no browser sends:
     * the one header saying "embedded in an app". An empty allow-list keeps it off everywhere.
     * Nothing else about the WebView changes: its own user agent, client hints and scripts stay.
     */
    fun dropAppHeader(web: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) runCatching {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(web.settings, emptySet())
        }
    }

    /** What this phone's WebView lets the identity cover, for the diagnostics log. */
    fun describe(): String {
        val hints = WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)
        val xrw = WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)
        return "client hints ${if (hints) "set" else "not settable"}, app header ${if (xrw) "removed" else "not removable"}"
    }

    fun hintsScript(major: String, full: String, platform: String, mobile: Boolean, model: String, platformVersion: String, arch: String): String = """
        (function(){
          try {
            var brands = [{brand:'Chromium',version:'$major'},{brand:'Google Chrome',version:'$major'},{brand:'Not:A-Brand',version:'99'}];
            var fullList = [{brand:'Chromium',version:'$full'},{brand:'Google Chrome',version:'$full'},{brand:'Not:A-Brand',version:'99.0.0.0'}];
            var data = { brands: brands, mobile: $mobile, platform: '$platform',
              getHighEntropyValues: function(){ return Promise.resolve({ brands: brands, mobile: $mobile, platform: '$platform', platformVersion: '$platformVersion', architecture: '$arch', bitness: '${if (mobile) "" else "64"}', model: '${model.replace("'", "")}', uaFullVersion: '$full', fullVersionList: fullList }); },
              toJSON: function(){ return { brands: brands, mobile: $mobile, platform: '$platform' }; } };
            Object.defineProperty(navigator, 'userAgentData', { get: function(){ return data; }, configurable: true });
          } catch (e) {}
        })();
    """.trimIndent()
}
