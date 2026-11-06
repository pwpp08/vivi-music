package com.music.vivi.projector

import android.content.Context
import com.music.innertube.YouTube
import com.music.innertube.models.YouTubeLocale
import org.json.JSONObject

/** Settings pushed from the phone, kept in one SharedPreferences file. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    /** "AUTO", "HIGH" or "LOW" (same names as the phone app). */
    val audioQuality: String get() = prefs.getString("audioQuality", "AUTO") ?: "AUTO"
    val hasAccount: Boolean get() = !prefs.getString("cookie", null).isNullOrBlank()
    val accountName: String? get() = prefs.getString("accountName", null)

    fun save(json: JSONObject) {
        prefs.edit().apply {
            for (key in KEYS) {
                if (json.has(key)) putString(key, json.optString(key).takeIf { it.isNotBlank() })
            }
        }.apply()
        apply()
    }

    /** Pushes the stored values into the shared innertube client. */
    fun apply() {
        YouTube.locale = YouTubeLocale(
            gl = prefs.getString("gl", null) ?: "US",
            hl = prefs.getString("hl", null) ?: "en",
        )
        YouTube.cookie = prefs.getString("cookie", null)
        YouTube.visitorData = prefs.getString("visitorData", null)
        YouTube.dataSyncId = prefs.getString("dataSyncId", null)
    }

    private companion object {
        val KEYS = listOf("cookie", "visitorData", "dataSyncId", "gl", "hl", "audioQuality", "accountName")
    }
}
