package com.xlollx.songport.sync

import com.xlollx.songport.net.Http
import com.xlollx.songport.platform.Env
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** A playlist cover fetched and shrunk to what a service accepts: a JPEG under 256 KB, at most 640 px. */
object Covers {
    private const val MAX_BYTES = 256_000
    private const val MAX_SIDE = 640

    suspend fun fetchJpeg(env: Env, url: String): ByteArray? = withContext(Dispatchers.IO) {
        val raw = Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return@withContext null
            r.body?.bytes() ?: return@withContext null
        }
        env.shrinkJpeg(raw, MAX_SIDE, MAX_BYTES)
    }
}
