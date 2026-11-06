/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.utils

import android.content.Context
import android.widget.Toast
import androidx.datastore.preferences.core.Preferences
import com.music.vivi.constants.AccountNameKey
import com.music.vivi.constants.AudioQualityKey
import com.music.vivi.constants.ContentCountryKey
import com.music.vivi.constants.ContentLanguageKey
import com.music.vivi.constants.DataSyncIdKey
import com.music.vivi.constants.InnerTubeCookieKey
import com.music.vivi.constants.SYSTEM_DEFAULT
import com.music.vivi.constants.VisitorDataKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Pushes account and playback settings to the Vivi Projector app on the local network. */
object ProjectorSyncUtils {
    suspend fun pushToProjector(context: Context, host: String, port: Int, code: String): Boolean =
        withContext(Dispatchers.IO) {
            val error: String? = try {
                val prefs: Preferences = context.dataStore.data.first()
                val payload = JSONObject().apply {
                    put("cookie", prefs[InnerTubeCookieKey].orEmpty())
                    put("visitorData", prefs[VisitorDataKey].orEmpty())
                    put("dataSyncId", prefs[DataSyncIdKey].orEmpty())
                    put("accountName", prefs[AccountNameKey].orEmpty())
                    put("audioQuality", prefs[AudioQualityKey] ?: "AUTO")
                    prefs[ContentCountryKey]?.takeIf { it != SYSTEM_DEFAULT }?.let { put("gl", it) }
                    prefs[ContentLanguageKey]?.takeIf { it != SYSTEM_DEFAULT }?.let { put("hl", it) }
                }.toString()

                val conn = URL("http://$host:$port/sync").openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 6000
                    conn.readTimeout = 10000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.setRequestProperty("X-Code", code)
                    conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    when (val code = conn.responseCode) {
                        200 -> null
                        403 -> "Wrong or expired code. Reopen the Account tab on the projector and scan again."
                        else -> "Projector answered HTTP $code"
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                "Could not reach projector (${e.javaClass.simpleName}: ${e.message}). Same Wi-Fi?"
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    error ?: "Settings sent to projector",
                    Toast.LENGTH_LONG,
                ).show()
            }
            error == null
        }
}
