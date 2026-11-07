package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * YouTube Music calls made by a real browser engine.
 *
 * Google's abuse detection answers plain HTTP clients with its "Sorry..." page (a 403) after a
 * while, even at a gentle rhythm: it keys on the address and on the TLS and HTTP fingerprint of the
 * client, which OkHttp cannot imitate. A WebView is Chrome, so a `fetch()` issued from a page on
 * music.youtube.com carries Chrome's fingerprint, the session cookies and the browser's own
 * `Origin` and `Sec-Fetch-*` headers, exactly like the player's requests.
 *
 * One hidden WebView per account, created lazily on the main thread, stays on a tiny same-origin
 * document (`/robots.txt`: a few hundred bytes, no scripts of its own) and runs every request as a
 * `fetch`. Responses come back through a JavaScript interface keyed by request id, so several
 * requests can be in flight. Callers block, so this is never used on the main thread. The primary
 * account's engine runs in the WebView's default profile, as it always has; an extra account's in
 * that account's own profile ([Slots]), with its own cookies.
 */
object YtmWeb {
    class Response(val code: Int, val body: String, val url: String)

    private val engines = ConcurrentHashMap<String, Engine>()

    private fun engine(slot: String): Engine = engines.getOrPut(Slots.valid(slot)) { Engine(Slots.valid(slot)) }

    /** False when the engine could not be started in this process: callers use plain HTTP instead. */
    fun usable(slot: String = Slots.PRIMARY): Boolean = engine(slot).usable()

    /**
     * POSTs [body] to [path] (relative to the origin) with [headers]. With [credentials] the browser
     * sends the session cookies; without, nothing at all, an anonymous visitor.
     */
    fun post(ctx: Context, path: String, body: String, headers: Map<String, String>, credentials: Boolean, timeoutMs: Long = 40_000, slot: String = Slots.PRIMARY): Response =
        engine(slot).post(ctx, path, body, headers, credentials, timeoutMs)

    /** Drops the engine, for instance after a sign-out: the next call starts a fresh one. */
    fun reset(slot: String = Slots.PRIMARY) = engine(slot).reset()

    private class Engine(private val slot: String) {
        private val session = Session.forSlot(slot)
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var web: WebView? = null
        @Volatile private var ready: CountDownLatch? = null
        @Volatile private var broken = false
        private val ids = AtomicLong(1)
        private val pending = ConcurrentHashMap<String, CompletableFuture<Response>>()

        fun usable(): Boolean = !broken

        fun post(ctx: Context, path: String, body: String, headers: Map<String, String>, credentials: Boolean, timeoutMs: Long): Response {
            ensureStarted(ctx.applicationContext)
            val id = ids.getAndIncrement().toString()
            val future = CompletableFuture<Response>()
            pending[id] = future
            val script = """
                (function(){
                  fetch(${js(path)}, {method: 'POST', credentials: ${if (credentials) "'include'" else "'omit'"}, headers: ${headersJs(headers)}, body: ${js(body)}})
                    .then(function(r){ return r.text().then(function(t){ YtmWeb.done(${js(id)}, r.status, t, String(r.url || '')); }); })
                    .catch(function(e){ YtmWeb.fail(${js(id)}, String(e)); });
                })();
            """.trimIndent()
            main.post { web?.evaluateJavascript(script, null) ?: future.completeExceptionally(IllegalStateException("no engine")) }
            try {
                return future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                throw BridgeException("YouTube Music did not answer within ${timeoutMs / 1000}s")
            } catch (e: java.util.concurrent.ExecutionException) {
                throw BridgeException("YouTube Music request failed in the browser engine: ${e.cause?.message ?: e.message}")
            } finally {
                pending.remove(id)
            }
        }

        private val sink = object {
            @JavascriptInterface
            fun done(id: String, status: Int, text: String, url: String) { pending[id]?.complete(Response(status, text, url)) }
            @JavascriptInterface
            fun fail(id: String, message: String) { pending[id]?.completeExceptionally(RuntimeException(message)) }
        }

        @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
        private fun ensureStarted(app: Context) {
            if (web != null) { ready?.await(INIT_TIMEOUT_S, TimeUnit.SECONDS); return }
            synchronized(this) {
                if (web != null) { ready?.await(INIT_TIMEOUT_S, TimeUnit.SECONDS); return }
                val latch = CountDownLatch(1)
                ready = latch
                main.post {
                    try {
                        val w = WebView(app)
                        // An extra account's engine lives in that account's profile: set before anything loads.
                        Slots.apply(w, Session.PROFILE, slot)
                        seedCookies(app)
                        w.settings.apply { javaScriptEnabled = true; domStorageEnabled = true }
                        w.addJavascriptInterface(sink, "YtmWeb")
                        w.webViewClient = object : SafeWebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) { latch.countDown() }
                            override fun onReceivedError(view: WebView, req: android.webkit.WebResourceRequest, err: android.webkit.WebResourceError) {
                                if (req.isForMainFrame) { broken = true; latch.countDown() }
                            }
                        }
                        web = w
                        w.loadUrl(PAGE)
                    } catch (e: Exception) {
                        broken = true
                        latch.countDown()
                    }
                }
                if (!latch.await(INIT_TIMEOUT_S, TimeUnit.SECONDS)) broken = true
                if (broken) throw BridgeException("the browser engine for YouTube Music could not be started")
            }
        }

        /**
         * The session was captured by a WebView, so the cookie jar normally still has it; after a jar
         * reset (or a session restored from storage) it is put back from the stored cookie string.
         */
        private fun seedCookies(app: Context) {
            val stored = session.cookies(app) ?: return
            val cm = session.jar()
            val present = cm.getCookie(Session.ORIGIN) ?: ""
            if (present.contains("SAPISID=") || present.contains("__Secure-3PAPISID=")) return
            stored.split(';').map { it.trim() }.filter { it.contains('=') }.forEach { c ->
                val name = c.substringBefore('=')
                val value = c.substringAfter('=')
                cm.setCookie("https://music.youtube.com", "$name=$value; Domain=.youtube.com; Path=/; Secure")
            }
            cm.flush()
        }

        fun reset() {
            main.post { runCatching { web?.destroy() }; web = null; ready = null; broken = false }
        }
    }

    private const val PAGE = "${Session.ORIGIN}/robots.txt"
    private const val INIT_TIMEOUT_S = 20L

    private fun js(s: String): String = JsonPrimitive(s).toString()

    private fun headersJs(h: Map<String, String>): String =
        h.entries.joinToString(",", "{", "}") { (k, v) -> "${js(k)}: ${js(v)}" }
}
