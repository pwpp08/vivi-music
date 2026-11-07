package com.xlollx.songport.platform

import com.xlollx.songport.providers.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.auth.AppleAuthActivity
import com.xlollx.songport.auth.AuthFlow
import com.xlollx.songport.auth.AuthScope
import com.xlollx.songport.auth.ServerLoginActivity
import com.xlollx.songport.data.Diagnostics
import com.xlollx.songport.data.FileStore
import com.xlollx.songport.data.Store
import com.xlollx.songport.data.TokenStore
import com.xlollx.songport.data.TokenVault
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.OAuthProvider
import com.xlollx.songport.ui.AppLocale
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** The engine's platform on Android: the app's files, its encrypted token store, its strings. */
class AndroidEnv private constructor(val context: Context) : Env {
    override val dataDir: File get() = context.filesDir
    override val store: FileStore get() = Store.get(context)
    override val tokens: TokenVault get() = TokenStore(context)
    override val redirectUri: String get() = AuthFlow.REDIRECT_URI

    /** Strings in the language chosen in the app (on Android 12 and older the app context alone would not follow it). */
    private val localized: Context get() = runCatching { AppLocale.wrap(context) }.getOrDefault(context)

    override fun text(name: String, vararg args: Any): String {
        val id = Res.id(context, name, "string")
        if (id == 0) return name
        val res = localized.resources
        return if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
    }

    override fun log(tag: String, message: String) = Diagnostics.log(context, tag, message)

    override fun startAuth(provider: MusicProvider, mode: AuthMode) {
        when (mode) {
            AuthMode.BROWSER -> AuthFlow.startBrowserFlow(context, provider)
            AuthMode.LOOPBACK -> AuthFlow.startLoopbackFlow(AuthScope, context, provider as OAuthProvider)
            AuthMode.CREDENTIALS -> context.startActivity(
                Intent(context, ServerLoginActivity::class.java)
                    .putExtra(ServerLoginActivity.EXTRA_PROVIDER, provider.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            AuthMode.MUSICKIT -> context.startActivity(
                Intent(context, AppleAuthActivity::class.java)
                    .putExtra(AppleAuthActivity.EXTRA_PROVIDER, provider.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    override fun keyValues(name: String): KeyValues {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        return object : KeyValues {
            override fun get(key: String): String? = prefs.getString(key, null)
            override fun set(key: String, value: String) = prefs.edit().putString(key, value).apply()
            override val size: Int get() = prefs.all.size
            override fun clear() = prefs.edit().clear().apply()
        }
    }

    override fun shrinkJpeg(raw: ByteArray, maxSide: Int, maxBytes: Int): ByteArray? {
        val src = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        // Quality steps down until the bytes fit; a cover that never fits is not sent.
        for (q in intArrayOf(85, 70, 55, 40)) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
            if (out.size() <= maxBytes) return out.toByteArray()
        }
        return null
    }

    companion object {
        @Volatile private var instance: AndroidEnv? = null

        fun of(ctx: Context): AndroidEnv = instance ?: synchronized(this) {
            instance ?: AndroidEnv(runCatching { ctx.applicationContext }.getOrNull() ?: ctx).also { instance = it }
        }

        /** Build-time values the engine reads through [BuildDefaults]. */
        fun buildDefaults(): Map<String, String> = mapOf(
            "SPOTIFY_CLIENT_ID" to BuildConfig.SPOTIFY_CLIENT_ID,
            "TIDAL_CLIENT_ID" to BuildConfig.TIDAL_CLIENT_ID,
            "DEEZER_APP_ID" to BuildConfig.DEEZER_APP_ID,
            "DEEZER_REDIRECT_URL" to BuildConfig.DEEZER_REDIRECT_URL,
            "GOOGLE_CLIENT_ID" to BuildConfig.GOOGLE_CLIENT_ID,
            "GOOGLE_REDIRECT_SCHEME" to BuildConfig.GOOGLE_REDIRECT_SCHEME,
            "APPLE_DEVELOPER_TOKEN" to BuildConfig.APPLE_DEVELOPER_TOKEN,
            "LASTFM_API_KEY" to BuildConfig.LASTFM_API_KEY,
        )
    }
}

/** The engine's Env for this Context: what to hand to a connector where it used to take the Context. */
val Context.env: AndroidEnv get() = AndroidEnv.of(this)

/** The Android Context behind an Env, for the app's own connectors (they run only on Android). */
val Env.android: Context get() = (this as AndroidEnv).context

/** Resource ids by name, for the names the engine uses (connector notes, setup guides, login forms). */
object Res {
    private val cache = ConcurrentHashMap<String, Int>()
    @Volatile private var app: Context? = null

    fun init(ctx: Context) { app = ctx.applicationContext }

    fun id(ctx: Context, name: String, type: String = "string"): Int =
        cache.getOrPut("$type/$name") { ctx.resources.getIdentifier("th_$name", type, ctx.packageName) }

    /** Without a Context at hand: the application's, set at start. 0 when unknown. */
    fun id(name: String, type: String = "string"): Int = app?.let { id(it, name, type) } ?: 0
}
