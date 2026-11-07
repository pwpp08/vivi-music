package com.xlollx.songport.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class HttpResponse(val code: Int, val body: String, val headers: Headers) {
    val ok: Boolean get() = code in 200..299
    fun header(name: String): String? = headers[name]
}

/** Client HTTP condiviso. Gestisce da solo i 429 (rate limit) con attesa e ripetizione. */
object Http {
    /**
     * Called with the host of every request this client makes. The Android app records it in its
     * connections log (HostLog); elsewhere it stays a no-op.
     */
    @Volatile var onRequest: (host: String) -> Unit = {}

    /**
     * Tests only: rewrites each request just before it leaves (the contract tests send every host to
     * a local mock server). Null in the app and on the server, where requests go where addressed.
     */
    @Volatile internal var rewrite: ((Request) -> Request)? = null

    /** How [send] waits before a retry. Tests record the waits instead of sleeping through them. */
    @Volatile internal var pause: suspend (Long) -> Unit = { delay(it) }

    /** The interceptor that reports each request to [onRequest]: for every OkHttp client of the app. */
    val hostInterceptor = okhttp3.Interceptor { chain ->
        onRequest(chain.request().url.host)
        chain.proceed(rewrite?.invoke(chain.request()) ?: chain.request())
    }

    /** Longest Retry-After (seconds) honoured silently inside [send]; anything longer is returned as is. */
    const val MAX_SILENT_WAIT_S = 15L
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(hostInterceptor)
        .build()

    class Landing(val url: String, val code: Int, val page: String)

    const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    /** Where [url] lands after redirects, with the status and the page there (short share links open this way). */
    suspend fun follow(url: String, userAgent: String = DESKTOP_UA): Landing = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/json,*/*")
            .header("Accept-Language", "en-US,en;q=0.8").build()
        client.newCall(req).execute().use { r -> Landing(r.request.url.toString(), r.code, r.body?.string()?.take(400_000) ?: "") }
    }

    suspend fun send(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        maxRateLimitRetries: Int = 3,
        // A 502/503/504 is tried once more only where repeating cannot do harm. A POST (adding tracks
        // to a playlist, creating one) may have been applied before the error: sent twice it can add
        // the same tracks twice (#42, idea of BjoernHa). Reads sent as POST opt in.
        retryServerErrors: Boolean = method != "POST" && method != "PATCH",
    ): HttpResponse = withContext(Dispatchers.IO) {
        var last: HttpResponse? = null
        for (attempt in 0..maxRateLimitRetries) {
            val req = Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
                val b = body ?: if (method == "POST" || method == "PUT" || method == "PATCH") ByteArray(0).toRequestBody(null) else null
                method(method, b)
            }.build()
            val resp = client.newCall(req).execute()
            val text = resp.body?.string() ?: ""
            val out = HttpResponse(resp.code, text, resp.headers)
            last = out
            if (out.code == 429 && attempt < maxRateLimitRetries) {
                // A short pause is absorbed here. A long one (Spotify can ask for hours) is not: the
                // caller gets the 429 at once, with Retry-After, and says so instead of showing a
                // spinner for minutes; the sync engine waits on its own terms, visibly.
                // Retry-After, or the window's reset of services that pace by window (ListenBrainz).
                val asked = (out.header("Retry-After") ?: out.header("X-RateLimit-Reset-In"))?.trim()?.toLongOrNull()
                if (asked != null && asked > MAX_SILENT_WAIT_S) return@withContext out
                pause((asked?.coerceAtLeast(1) ?: (2L * (attempt + 1))) * 1000)
                continue
            }
            // 502/503/504: quasi sempre un singhiozzo del servizio, un secondo tentativo basta.
            if (retryServerErrors && out.code in listOf(502, 503, 504) && attempt < 1) {
                pause(1500)
                continue
            }
            return@withContext out
        }
        last!!
    }

    fun form(params: Map<String, String>): RequestBody =
        FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()

    fun jsonBody(text: String, mediaType: String = "application/json"): RequestBody =
        text.toRequestBody(mediaType.toMediaType())

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun query(params: Map<String, String?>): String =
        params.filterValues { it != null }.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v!!)}" }
}
