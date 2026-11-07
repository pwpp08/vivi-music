package com.xlollx.songport.ytmbridge

import android.os.Bundle
import com.xlollx.songport.ytmbridge.BridgeNet.withHook
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The requests Trackhop's Google Play build hands to the plugin: the public pages and APIs its
 * read-only link sources and TikTok lookups read (NetEase Cloud Music, QQ Music, Anghami, TikTok),
 * which that build does not reach by itself. Only those services' hosts, only what Trackhop asks.
 * A long answer goes back in pieces: a call through the plugin carries well under a megabyte.
 */
object NetRelay {
    /** The services whose public pages the relay reaches, and nothing else. */
    private val HOSTS = listOf("music.163.com", "qq.com", "anghami.com", "tiktok.com")
    const val CHUNK = 200_000

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).withHook().build()
    private val pending = ConcurrentHashMap<String, String>()

    fun allowed(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        if (!url.startsWith("https://")) return false
        return HOSTS.any { host == it || host.endsWith(".$it") }
    }

    fun fetch(url: String, extras: Bundle?): Bundle {
        if (!allowed(url)) return Bundle().apply { putString("error", "not a host this relay reaches") }
        val method = extras?.getString("method") ?: "GET"
        val body = extras?.getString("body")?.toRequestBody(extras.getString("contentType")?.toMediaTypeOrNull())
        val req = Request.Builder().url(url).apply {
            extras?.getStringArray("headers")?.forEach { h -> h.split('\n', limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> header(k, v) } }
            method(method, body ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody(null) else null)
        }.build()
        return http.newCall(req).execute().use { r ->
            // A redirect that leaves the allowed services brings back only where it went, not the page.
            val text = if (r.priorResponse == null || allowed(r.request.url.toString())) r.body?.string().orEmpty() else ""
            Bundle().apply {
                putInt("code", r.code)
                putString("finalUrl", r.request.url.toString())
                putStringArray("headers", r.headers.map { (k, v) -> "$k\n$v" }.toTypedArray())
                putInt("length", text.length)
                putString("chunk", text.take(CHUNK))
                if (text.length > CHUNK) {
                    val id = UUID.randomUUID().toString()
                    pending[id] = text
                    putString("id", id)
                }
            }
        }
    }

    /** The piece of a long answer from [offset]; the answer is forgotten once its last piece is read. */
    fun chunk(id: String, offset: Int): Bundle {
        val text = pending[id] ?: return Bundle().apply { putString("error", "no such answer") }
        val end = minOf(text.length, offset + CHUNK)
        if (end >= text.length) pending.remove(id)
        return Bundle().apply { putString("chunk", text.substring(offset.coerceIn(0, text.length), end)) }
    }
}
