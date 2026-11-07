package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.content.FileProvider
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File

/**
 * Development aid for the Amazon Music connector. The web player runs in this WebView with a small
 * script that mirrors every API call it makes (URL, request body, response) into a file on this
 * phone. The user browses their library, creates a playlist, adds and removes a song, then shares
 * the file so the connector can be completed against the real protocol.
 *
 * What is written: request bodies with the `headers` field removed (it carries the access token and
 * CSRF values), the request's own HTTP headers with every credential-looking value replaced by its
 * length, responses cut at 30 kB, and no cookies at all. Access tokens, bearer tokens and JWTs left
 * anywhere are blanked before writing.
 */
class AmazonCaptureActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var frame: android.widget.FrameLayout
    private val popups = ArrayList<WebView>()
    private lateinit var counter: TextView
    private var count = 0
    /** One writer for the page script and the WebView's own requests (several threads). */
    private val sink by lazy { Sink() }

    /**
     * Another site's web player to capture instead of Amazon's (a service being added, Qobuz…):
     * every call to its domain is written, not only Amazon's API paths.
     */
    private val startUrl: String? by lazy { intent.getStringExtra(EXTRA_URL)?.takeIf { it.startsWith("https://") } }
    /** The site's domain ("qobuz.com"): the calls to it and to its subdomains are written. */
    private val site: String? by lazy { startUrl?.let { android.net.Uri.parse(it).host }?.split('.')?.takeLast(2)?.joinToString(".") }

    private val file: File get() = File(filesDir, site?.let { "capture-$it.jsonl" } ?: "amazon-capture.jsonl")

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forgetOld(this)
        count = if (file.exists()) file.readLines().size else 0

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(16, 8, 16, 8) }
        counter = TextView(this).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val share = Button(this).apply { text = getString(R.string.th_bridge_amazon_capture_share); setOnClickListener { share() } }
        val clear = Button(this).apply { text = getString(R.string.th_bridge_amazon_capture_clear); setOnClickListener { file.delete(); count = 0; refresh() } }
        bar.addView(counter); bar.addView(clear); bar.addView(share)
        val hint = TextView(this).apply { text = if (site != null) getString(R.string.th_bridge_capture_site_intro, site) else getString(R.string.th_bridge_amazon_capture_intro); setPadding(16, 0, 16, 8); textSize = 12f }
        web = WebView(this)
        // The page and, above it, the popups a sign-in opens ("Continue with Google").
        frame = android.widget.FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(web) }
        root.addView(bar); root.addView(hint); root.addView(frame)
        setContentView(root)
        BridgeInsets.fit(root)
        refresh()

        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // The phone-sized player hides playlist editing (rename, delete) behind "open the app";
            // the desktop player shows them in the playlist's menu. Identify as a desktop browser.
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        BridgeUa.apply(this, web)
        web.addJavascriptInterface(sink, "SpCapture")
        val hook = (site?.let { "window.__capSite = '${it.replace("'", "")}';\n" } ?: "") + HOOK
        val early = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (early) WebViewCompat.addDocumentStartJavaScript(web, hook, setOf("*"))
        // Watching the real Amazon player here also refreshes the connector's own credentials.
        val amazon = site == null
        if (amazon) AmazonBridge.install(this, web)
        web.webViewClient = object : SafeWebViewClient() {
            // Every request to the site as the WebView sees it (also those a page script cannot:
            // workers, beacons, a reply it could not read): address and method, no body. A safety
            // net under the script's fuller lines.
            override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                val s = site ?: return null
                val u = request.url
                val host = u.host ?: return null
                if ((host == s || host.endsWith(".$s")) && !STATIC.containsMatchIn(u.path.orEmpty())) {
                    val line = org.json.JSONObject()
                        .put("t", System.currentTimeMillis()).put("seen", "webview")
                        .put("method", request.method).put("url", cleanUrl(u.toString()))
                        .put("headers", org.json.JSONObject(request.requestHeaders.mapValues { (k, v) -> if (SECRET_HEADER.containsMatchIn(k)) "[${v.length} chars]" else v.take(200) }))
                    sink.log(line.toString())
                }
                return null
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!early) { view.evaluateJavascript(hook, null); if (amazon) view.evaluateJavascript(AmazonBridge.HOOK, null) }
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (!early) { view.evaluateJavascript(hook, null); if (amazon) view.evaluateJavascript(AmazonBridge.HOOK, null) }
            }
        }
        // A sign-in with Google, Apple or Facebook opens a popup and hands its result back to the
        // page that opened it: a real second view, sharing the cookies, keeps that working.
        web.settings.apply { setSupportMultipleWindows(true); javaScriptCanOpenWindowsAutomatically = true }
        web.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                val popup = WebView(this@AmazonCaptureActivity).apply {
                    settings.apply { javaScriptEnabled = true; domStorageEnabled = true; setSupportMultipleWindows(true); javaScriptCanOpenWindowsAutomatically = true }
                    BridgeUa.apply(this@AmazonCaptureActivity, this)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = SafeWebViewClient()
                    webChromeClient = object : android.webkit.WebChromeClient() {
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
                else -> leave()
            }
        }
        // What this screen does, every time: it records, and the file is for the developer only.
        if (savedInstanceState == null) android.app.AlertDialog.Builder(this)
            .setTitle(R.string.th_bridge_capture_warning_title)
            .setMessage(R.string.th_bridge_capture_warning_body)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setCancelable(false)
            .show()
        web.loadUrl(startUrl ?: ("https://" + (AmazonSession.domain(this) ?: "music.amazon.com") + "/"))
    }

    /** Leaving with a capture on the phone: delete it now, or keep it (gone by itself after a day). */
    private fun leave() {
        if (!file.exists() || count == 0) { finish(); return }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.th_bridge_capture_delete_title)
            .setMessage(R.string.th_bridge_capture_delete_body)
            .setPositiveButton(R.string.th_bridge_capture_delete) { _, _ -> file.delete(); finish() }
            .setNegativeButton(R.string.th_bridge_capture_keep) { _, _ -> finish() }
            .show()
    }

    private fun closePopup(popup: WebView) {
        popups -= popup
        frame.removeView(popup)
        popup.destroy()
    }

    private fun refresh() { counter.text = getString(R.string.th_bridge_amazon_capture_count, count) }

    private fun share() {
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Vivi: ${site ?: "Amazon Music"} capture")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.th_bridge_amazon_capture_share)))
    }

    private inner class Sink {
        @JavascriptInterface
        fun log(line: String) {
            if (file.exists() && file.length() > MAX_BYTES) return
            val safe = SECRETS.fold(TOKEN.replace(line, "\"accessToken\":\"[redacted]\"")) { s, r -> r.replace(s, "[redacted]") }
            synchronized(this) { file.appendText(safe.replace('\n', ' ') + "\n") }
            count++
            runOnUiThread { refresh() }
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private val STATIC = Regex("""\.(?:png|jpe?g|webp|gif|svg|css|woff2?|ico|js|mp3|flac|m4a)$""", RegexOption.IGNORE_CASE)
        private val SECRET_HEADER = Regex("authori|token|cookie|csrf|session|signature|credential", RegexOption.IGNORE_CASE)
        /** Personal fields of a form or a query, by their decoded name (as the page script does). */
        private val PERSONAL = Regex("pass|secret|token|mail|user_?name|login|authori[sz]|authenticat|birth|captcha|phone|address|gender|iban", RegexOption.IGNORE_CASE)

        /** [url] with the values of its personal query fields blanked. */
        fun cleanUrl(url: String): String {
            val q = url.indexOf('?').takeIf { it >= 0 } ?: return url
            val query = url.substring(q + 1).split('&').joinToString("&") { kv ->
                val i = kv.indexOf('=')
                if (i < 0) kv else {
                    val name = runCatching { java.net.URLDecoder.decode(kv.substring(0, i), "UTF-8") }.getOrDefault(kv.substring(0, i))
                    if (PERSONAL.containsMatchIn(name)) kv.substring(0, i) + "=[redacted]" else kv
                }
            }
            return url.substring(0, q + 1) + query
        }

        /** A capture left on the phone is deleted a day after it was last written. */
        fun forgetOld(ctx: android.content.Context) = runCatching {
            ctx.filesDir.listFiles { f -> f.name.endsWith("capture.jsonl") || (f.name.startsWith("capture-") && f.name.endsWith(".jsonl")) }
                ?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3_600_000L }?.forEach { it.delete() }
        }

        /** The address to open for a capture of another site (https only). */
        const val EXTRA_URL = "url"
        private const val MAX_BYTES = 6L * 1024 * 1024
        private val TOKEN = Regex("\"accessToken\"\\s*:\\s*\"[^\"]*\"")
        /** Bearer tokens, JWTs and Amazon access tokens (Atna|…, Atza|…) wherever they appear. */
        private val SECRETS = listOf(
            Regex("Bearer\\s+[A-Za-z0-9._~+/=|-]+"),
            Regex("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*"),
            Regex("Atn[a-z]\\|[A-Za-z0-9._~+/=|-]+"),
            Regex("Atz[a-z]\\|[A-Za-z0-9._~+/=|-]+"),
        )

        // Mirrors fetch() and XMLHttpRequest calls towards the Amazon Music API into SpCapture.log.
        private val HOOK = """
            (function(){
              if (window.__spCap) return; window.__spCap = 1;
              // Another site's desktop player (Qobuz asks for at least 1024 px): see DesktopPage.
              if (window.__capSite) { ${DesktopPage.SCRIPT} }
              // The "update your browser" page: what the player's own code checks, taken from its
              // scripts, so the check can be met instead of guessed at.
              var probe = function(){
                try {
                  var t = String(document.body && document.body.innerText || '');
                  if (!/Aggiornare Chrome|Update Chrome|browser non è più supportat|no longer supported|non è più supportata/i.test(t)) return;
                  var facts = { userAgent: navigator.userAgent, uaData: navigator.userAgentData ? JSON.stringify(navigator.userAgentData) : null,
                    chrome: !!window.chrome, plugins: navigator.plugins ? navigator.plugins.length : -1, vendor: navigator.vendor, platform: navigator.platform,
                    mediaSource: !!window.MediaSource, eme: !!navigator.requestMediaKeySystemAccess, wasm: !!window.WebAssembly, subtle: !!(window.crypto && crypto.subtle),
                    sw: !!navigator.serviceWorker, storage: !!navigator.storage, share: !!navigator.share, bt: !!navigator.bluetooth, usb: !!navigator.usb, hid: !!navigator.hid, pip: !!document.pictureInPictureEnabled,
                    gpu: !!navigator.gpu, wakeLock: !!navigator.wakeLock, credentials: !!navigator.credentials, cookies: !!window.cookieStore, scheduler: !!window.scheduler, url: location.href };
                  SpCapture.log(JSON.stringify({ t: Date.now(), url: 'diag://browser-check', method: 'facts', body: facts, status: 0, response: '' }));
                  var scripts = Array.prototype.slice.call(document.scripts).map(function(s){ return s.src; }).filter(Boolean);
                  scripts.forEach(function(src){
                    fetch(src).then(function(r){ return r.text(); }).then(function(js){
                      var out = [];
                      var re = /(unsupported|Unsupported|UNSUPPORTED|isSupportedBrowser|browserSupport|supportedBrowser|minimumVersion|minVersion|updateChrome|update-chrome|browserNotSupported|outdated)/g;
                      var m, n = 0;
                      while ((m = re.exec(js)) && n < 12) { out.push(js.substring(Math.max(0, m.index - 700), m.index + 700)); n++; re.lastIndex = m.index + 1; }
                      if (out.length) SpCapture.log(JSON.stringify({ t: Date.now(), url: 'diag://browser-check', method: 'script', body: src, status: js.length, response: out.join('\n----\n') }));
                    }).catch(function(){});
                  });
                } catch(e){}
              };
              setTimeout(probe, 4000); setTimeout(probe, 12000);
              // The old skill API, the music.amazon.* api paths, and since October 2026 the GraphQL
              // endpoint (gql.music.amazon.<tld>) the desktop player moved to.
              var abs = function(u){ try { return new URL(String(u||''), location.href).href; } catch(e) { return String(u||''); } };
              var match = function(u){
                var a = abs(u);
                // Another site: every call to its domain, pages and pictures aside.
                if (window.__capSite) { try { var h = new URL(a).hostname; return (h === window.__capSite || h.endsWith('.' + window.__capSite)) && !/\.(?:png|jpe?g|webp|gif|svg|css|woff2?|ico)(?:\?|$)/i.test(a); } catch(e) { return false; } }
                return /skill\.music\.a2z\.com|gql\.music\.amazon\.|music\.amazon\.[a-z.]+\/(?:[A-Z]{2}\/)?api\//.test(a);
              };
              // Passwords and secrets in addresses and form bodies (a sign-in posts them): blanked.
              // Personal fields, whatever their form's naming: register[_password], user%5Bemail%5D…
              var KEY = /pass|secret|token|mail|user_?name|login|authori[sz]|authenticat|birth|captcha|phone|address|gender|iban/i;
              var pairs = function(s){
                return s.split('&').map(function(kv){
                  var i = kv.indexOf('='); if (i < 0) return kv;
                  var k = kv.slice(0, i), name = k;
                  try { name = decodeURIComponent(k.replace(/\+/g, ' ')); } catch(e){}
                  return KEY.test(name) ? k + '=[redacted]' : kv;
                }).join('&');
              };
              var clean = function(s){
                s = String(s);
                var q = s.indexOf('?');
                if (/^https?:/i.test(s)) return q < 0 ? s : s.slice(0, q + 1) + pairs(s.slice(q + 1));
                return pairs(s);
              };
              var scrub = function(o){ if (o && typeof o === 'object') Object.keys(o).forEach(function(k){ if (KEY.test(k) && typeof o[k] !== 'object') o[k] = '[redacted]'; else scrub(o[k]); }); return o; };
              // Request headers as sent: names and values, credentials replaced by their length.
              var SECRET = /authori|token|cookie|csrf|session|signature|credential|x-amz-security/i;
              var hdrs = function(h){
                var out = {};
                try {
                  var each = function(v, k){ out[k] = SECRET.test(k) ? '[' + String(v).length + ' chars]' : String(v).slice(0, 300); };
                  if (!h) return out;
                  if (typeof h.forEach === 'function' && !(h instanceof Array)) h.forEach(each);
                  else if (h instanceof Array) h.forEach(function(p){ each(p[1], p[0]); });
                  else Object.keys(h).forEach(function(k){ each(h[k], k); });
                } catch(e){}
                return out;
              };
              var send = function(o){ try { SpCapture.log(JSON.stringify(o)); } catch(e){} };
              // An answer can name the account too (e-mail, birthday): the same fields blanked there.
              var answer = function(t){ try { var j = JSON.parse(t); if (j && typeof j === 'object') return JSON.stringify(scrub(j)).slice(0, 30000); } catch(e){} return String(t).slice(0, 30000); };
              var redact = function(b){
                if (b == null) return null;
                if (typeof b !== 'string') { try { b = String(b); } catch(e) { return '[binary]'; } }
                try { var j = JSON.parse(b); if (j && typeof j === 'object') { delete j.headers; scrub(j); } return JSON.stringify(j).slice(0, 20000); }
                catch(e) { return clean(b).slice(0, 4000); }
              };
              var of = window.fetch;
              // A body as text, whatever the page passed: a string, form fields, a form, a Request.
              var bodyText = function(b){
                if (b == null) return Promise.resolve(null);
                if (typeof b === 'string') return Promise.resolve(b);
                try { if (b instanceof URLSearchParams) return Promise.resolve(b.toString()); } catch(e){}
                try { if (b instanceof FormData) { var o = []; b.forEach(function(v, k){ o.push(encodeURIComponent(k) + '=' + encodeURIComponent(typeof v === 'string' ? v : '[file]')); }); return Promise.resolve(o.join('&')); } } catch(e){}
                try { if (b instanceof Blob) return b.text(); } catch(e){}
                try { if (b instanceof ArrayBuffer || ArrayBuffer.isView(b)) return Promise.resolve(new TextDecoder().decode(b)); } catch(e){}
                return Promise.resolve(String(b));
              };
              if (of) window.fetch = function(input, init){
                var url = (typeof input === 'string') ? input : (input && input.url) || '';
                var method = (init && init.method) || (input && input.method) || 'GET';
                var reqHeaders = hdrs((init && init.headers) || (input && input.headers));
                var watched = match(url);
                var bodyP = !watched ? null : (init && init.body != null) ? bodyText(init.body)
                  : (input && typeof input !== 'string' && input.clone && method !== 'GET') ? input.clone().text().catch(function(){ return null; }) : Promise.resolve(null);
                // Another site's player that cancels each request once it has its answer (Qobuz) would
                // cancel this copy too: its signal is left out, here only.
                var args = arguments;
                if (watched && window.__capSite && init && init.signal) { var i2 = Object.assign({}, init); delete i2.signal; args = [input, i2]; }
                var p = of.apply(this, args);
                if (watched) p.then(function(r){
                  var done = function(text, err){
                    bodyP.then(function(bt){ send({t: Date.now(), url: clean(abs(url)), method: method, headers: reqHeaders, body: redact(bt), status: r.status, response: text == null ? null : answer(text), error: err}); });
                  };
                  try { r.clone().text().then(function(t){ done(t); }).catch(function(e){ done(null, String(e)); }); } catch(e){ done(null, String(e)); }
                }).catch(function(e){ bodyP.then(function(bt){ send({t: Date.now(), url: clean(abs(url)), method: method, body: redact(bt), error: String(e)}); }); });
                return p;
              };
              var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
              var osh = XMLHttpRequest.prototype.setRequestHeader;
              XMLHttpRequest.prototype.setRequestHeader = function(k, v){ (this.__h = this.__h || []).push([k, v]); return osh.apply(this, arguments); };
              XMLHttpRequest.prototype.open = function(m, u){ this.__m = m; this.__u = u; return oo.apply(this, arguments); };
              XMLHttpRequest.prototype.send = function(b){
                var x = this;
                if (match(x.__u)) x.addEventListener('loadend', function(){
                  // responseText throws for a "json", "blob" or "arraybuffer" request: read what there is.
                  var text = '';
                  try { text = (x.responseType === '' || x.responseType === 'text') ? String(x.responseText || '') : (x.responseType === 'json' ? JSON.stringify(x.response) : '[' + x.responseType + ']'); } catch(e) { text = '[unreadable: ' + e + ']'; }
                  try { send({t: Date.now(), url: clean(abs(x.__u)), method: x.__m, headers: hdrs(x.__h), body: redact(b), status: x.status, response: answer(text)}); }
                  catch(e) { send({t: Date.now(), url: clean(abs(x.__u)), method: x.__m, error: String(e)}); }
                });
                return os.apply(this, arguments);
              };
            })();
        """.trimIndent()
    }
}
