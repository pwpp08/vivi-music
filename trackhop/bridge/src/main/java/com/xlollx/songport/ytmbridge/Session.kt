package com.xlollx.songport.ytmbridge

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.ConcurrentHashMap

/**
 * The YouTube Music session of one account: the cookies of the login the user completed in the
 * WebView, encrypted with the Android Keystore. They are as sensitive as the Google password: they
 * never leave this device and are never handed to Trackhop, which only receives playlists and tracks.
 *
 * [Session] is the primary account, in the file it has always had (`ytm_session`) and the WebView's
 * own cookie jar; an extra account ([Session.forSlot]) has its own file (`ytm_session_s2`) and its
 * own WebView profile ([Slots]), so the two never mix.
 */
open class YtmSessionStore(val slot: String) {
    @Volatile private var cached: SharedPreferences? = null

    private val fileName: String get() = if (slot.isEmpty()) "ytm_session" else "ytm_session_s$slot"

    private fun prefs(ctx: Context): SharedPreferences = cached ?: synchronized(this) {
        cached ?: open(ctx.applicationContext).also { cached = it }
    }

    private fun open(ctx: Context): SharedPreferences = try {
        val key = MasterKey.Builder(ctx, "ytmbridge_master").setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, fileName, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        ctx.getSharedPreferences("${fileName}_plain", Context.MODE_PRIVATE)
    }

    fun cookies(ctx: Context): String? = prefs(ctx).getString("cookies", null)?.takeIf { Session.sapisid(it) != null }
    fun account(ctx: Context): String? = prefs(ctx).getString("account", null)
    fun isConnected(ctx: Context): Boolean = cookies(ctx) != null

    fun save(ctx: Context, cookies: String, account: String?) {
        prefs(ctx).edit().putString("cookies", cookies).putString("account", account).apply()
    }

    /**
     * The channel this account's calls are made as: a brand account's page id, chosen by the
     * person ([YtmClient.channels]); null (every session so far) is the account's own channel.
     */
    fun channel(ctx: Context): String? = prefs(ctx).getString("channel", null)?.takeIf { it.isNotBlank() }
    /** The chosen channel's name, for the screens; null when none was chosen. */
    fun channelName(ctx: Context): String? = if (channel(ctx) == null) null else prefs(ctx).getString("channelName", null)

    /** Keeps the chosen channel; a blank [pageId] is the account's own channel, as before any choice. */
    fun setChannel(ctx: Context, pageId: String?, name: String?) {
        val e = prefs(ctx).edit()
        if (pageId.isNullOrBlank()) e.remove("channel").remove("channelName") else e.putString("channel", pageId).putString("channelName", name)
        e.apply()
    }

    /** The cookie jar this account's pages use: the WebView's own for the primary, the slot's profile otherwise. */
    fun jar() = Slots.cookies(Session.PROFILE, slot)

    /** Forgets the session here and in the WebView, so the next login can pick another account. */
    fun clear(ctx: Context) {
        YtmWeb.reset(slot)
        prefs(ctx).edit().clear().apply()
        // Only Google's and YouTube's cookies: inside Trackhop the WebView also holds the other web
        // sessions (Amazon, Spotify, Apple), which signing out of YouTube Music must not touch.
        runCatching {
            val cm = jar()
            COOKIE_DOMAINS.forEach { d ->
                val root = d.split('.').takeLast(2).joinToString(".")
                cm.getCookie("https://$d")?.split(';')?.forEach { c ->
                    val n = c.substringBefore('=').trim()
                    if (n.isNotEmpty()) {
                        cm.setCookie("https://$d", "$n=; Max-Age=0; Path=/; Secure")
                        cm.setCookie("https://$d", "$n=; Max-Age=0; Domain=.$root; Path=/; Secure")
                    }
                }
            }
            cm.flush()
        }
    }

    private companion object {
        val COOKIE_DOMAINS = listOf("music.youtube.com", "www.youtube.com", "youtube.com", "accounts.google.com", "www.google.com", "google.com")
    }
}

/** The primary YouTube Music account's session, as the app has always kept it. */
object Session : YtmSessionStore(Slots.PRIMARY) {
    const val ORIGIN = YtmAuth.ORIGIN
    /** The service's name in the WebView profiles of its extra accounts. */
    const val PROFILE = "ytm"

    private val slots = ConcurrentHashMap<String, YtmSessionStore>()

    /** The session of [slot]: this one for the primary, the account's own otherwise. */
    fun forSlot(slot: String): YtmSessionStore {
        val s = Slots.valid(slot)
        if (s.isEmpty()) return this
        return slots.getOrPut(s) { YtmSessionStore(s) }
    }

    /** The cookie that signs requests: SAPISID, or its __Secure-3PAPISID twin. */
    fun sapisid(cookies: String): String? = YtmAuth.sapisid(cookies)

    /** Authorization header in the form the web player itself sends: SAPISIDHASH ts_sha1("ts sapisid origin"). */
    fun authorization(cookies: String): String = YtmAuth.authorization(cookies)
}
