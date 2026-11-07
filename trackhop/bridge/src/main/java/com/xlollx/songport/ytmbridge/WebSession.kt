package com.xlollx.songport.ytmbridge

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.ConcurrentHashMap

/**
 * A web login kept for one service (Spotify, Apple Music): the cookie jar snapshot plus a few
 * values (account name, cached tokens). Encrypted with the Android Keystore, never handed to
 * Trackhop as such: Trackhop only receives short-lived API tokens or data.
 *
 * One instance per service and account: the primary account (slot "") keeps the storage it has
 * always had (`<name>_session`, the WebView's own cookie jar); an extra account ([forSlot]) has its
 * own file next to it (`<name>_s2_session`) and its own WebView profile ([Slots]), so neither can
 * overwrite the other.
 */
class WebSession private constructor(private val name: String, private val domains: List<String>, val slot: String, private val primary: WebSession?) {
    /** The service's primary account's session, the one the app has always kept. */
    constructor(name: String, domains: List<String>) : this(name, domains, Slots.PRIMARY, null)

    @Volatile private var cached: SharedPreferences? = null
    private val slots = ConcurrentHashMap<String, WebSession>()

    /** The file this account's session lives in; the primary's is the one it has always had. */
    private val fileName: String get() = if (slot.isEmpty()) "${name}_session" else "${name}_s${slot}_session"

    /** This session for [slot]: itself for its own slot, else the service's session of that account. */
    fun forSlot(slot: String): WebSession {
        val s = Slots.valid(slot)
        if (s == this.slot) return this
        primary?.let { return it.forSlot(s) }
        return slots.getOrPut(s) { WebSession(name, domains, s, this) }
    }

    private fun prefs(ctx: Context): SharedPreferences = cached ?: synchronized(this) {
        cached ?: open(ctx.applicationContext).also { cached = it }
    }

    private fun open(ctx: Context): SharedPreferences = try {
        // One master key per service, whatever the account: the files differ, the key is shared.
        val key = MasterKey.Builder(ctx, "${name}_bridge_master").setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, fileName, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        ctx.getSharedPreferences("${fileName}_plain", Context.MODE_PRIVATE)
    }

    fun cookies(ctx: Context): String? = prefs(ctx).getString("cookies", null)
    fun account(ctx: Context): String? = prefs(ctx).getString("account", null)
    fun isConnected(ctx: Context): Boolean = cookies(ctx) != null
    fun get(ctx: Context, key: String): String? = prefs(ctx).getString("x_$key", null)
    fun put(ctx: Context, key: String, value: String?) { prefs(ctx).edit().putString("x_$key", value).apply() }

    fun save(ctx: Context, cookies: String, account: String?) {
        prefs(ctx).edit().putString("cookies", cookies).putString("account", account).apply()
    }

    /** The cookie jar this account's pages use: the WebView's own for the primary, the slot's profile otherwise. */
    fun jar(): CookieManager = Slots.cookies(name, slot)

    /** Forgets the session here and the service's cookies in its WebView jar (other services keep theirs). */
    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        runCatching {
            val cm = jar()
            domains.forEach { d ->
                cm.getCookie("https://$d")?.split(';')?.forEach { c ->
                    val n = c.substringBefore('=').trim()
                    if (n.isNotEmpty()) {
                        cm.setCookie("https://$d", "$n=; Max-Age=0; Path=/")
                        cm.setCookie("https://$d", "$n=; Max-Age=0; Domain=.${d.substringAfter('.')}; Path=/")
                    }
                }
            }
            cm.flush()
        }
    }

    companion object {
        fun cookieValue(cookies: String?, name: String): String? = WebHost.cookieValue(cookies, name)
    }
}
