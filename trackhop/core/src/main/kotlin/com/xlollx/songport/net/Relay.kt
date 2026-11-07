package com.xlollx.songport.net

import com.xlollx.songport.platform.Env
import okhttp3.RequestBody

/**
 * Where the requests of the unofficial read-only sources (public playlist links of NetEase Cloud
 * Music, QQ Music and Anghami; TikTok video pages) are made. The builds that carry unofficial
 * connectors make them here ([via] null). The Google Play build makes none of them itself: when a
 * connector plugin is installed it hands them to the plugin, which makes them and answers with the
 * response, so a Play user with the plugin has what the GitHub build has, and one without it sees
 * none of these sources.
 */
object Relay {
    /** A request as the plugin needs it: method, address, headers, and the body as text with its type. */
    class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String?, val contentType: String?, val followRedirects: Boolean)

    /** The plugin's answer: status, body, the address it ended on (after redirects), headers. */
    class Answer(val code: Int, val body: String, val finalUrl: String, val headers: Map<String, String> = emptyMap())

    /** Set by the Google Play build: makes a call through the plugin. Null elsewhere. */
    @Volatile var via: (suspend (Call) -> Answer)? = null

    /** Whether these sources can be used now: always where they run here; with a capable plugin on Play. */
    @Volatile var available: (Env) -> Boolean = { true }

    /** True where the sources go through a plugin (Google Play): they then show only when it is there. */
    val throughPlugin: Boolean get() = via != null

    suspend fun send(method: String, url: String, headers: Map<String, String> = emptyMap(), body: RequestBody? = null, maxRateLimitRetries: Int = 3, retryServerErrors: Boolean = method != "POST" && method != "PATCH"): HttpResponse {
        val v = via ?: return Http.send(method, url, headers, body, maxRateLimitRetries, retryServerErrors)
        val text = body?.let { b -> okio.Buffer().also { b.writeTo(it) }.readUtf8() }
        val a = v(Call(method, url, headers, text, body?.contentType()?.toString(), followRedirects = true))
        return HttpResponse(a.code, a.body, okhttp3.Headers.Builder().apply { a.headers.forEach { (k, value) -> runCatching { add(k, value) } } }.build())
    }

    suspend fun follow(url: String, userAgent: String = Http.DESKTOP_UA): Http.Landing {
        val v = via ?: return Http.follow(url, userAgent)
        val a = v(Call("GET", url, mapOf("User-Agent" to userAgent, "Accept" to "text/html,application/json,*/*", "Accept-Language" to "en-US,en;q=0.8"), null, null, followRedirects = true))
        return Http.Landing(a.finalUrl, a.code, a.body.take(400_000))
    }
}
