package com.xlollx.songport.ytmbridge

import android.content.Context

/**
 * The app's side of [YtmClient] (the client itself lives in :ytm, shared with the server): the
 * session from the Keystore, the hidden WebView as the first route, Google's block in the app's
 * preferences, and the connections log on the HTTP client.
 */
class AndroidYtmHost(private val ctx: Context, private val slot: String = Slots.PRIMARY) : YtmHost {
    private val session = Session.forSlot(slot)
    override fun cookies(): String? = session.cookies(ctx)
    override val userAgent: String get() = BridgeUa.desktop(ctx)
    override val interceptor: okhttp3.Interceptor? get() = BridgeNet.interceptor
    override val web: YtmWebTransport = object : YtmWebTransport {
        override fun usable(): Boolean = YtmWeb.usable(slot)
        override fun post(path: String, body: String, headers: Map<String, String>, credentials: Boolean): YtmWebTransport.Answer =
            YtmWeb.post(ctx, path, body, headers, credentials, slot = slot).let { YtmWebTransport.Answer(it.code, it.body) }
    }
    override val verification: YtmVerificationStore get() = verificationStore(ctx)
    override val cacheScope: String get() = slot
    override val channel: String? get() = session.channel(ctx)
    override val signInAgain: String = "sign out of YouTube Music and sign in again"
    // In the app the way out is the "Verify with Google" screen, which the connector's menu offers.
    override val blockedHint: String? = null
    override fun log(message: String) = BridgeNet.log("ytm", message)
}

/** The client as the app has always built it, from a Context; [slot] names an extra account (see [Slots]). */
fun YtmClient(ctx: Context, slot: String = Slots.PRIMARY): YtmClient = YtmClient(AndroidYtmHost(ctx, slot))

/** Google's block in plain preferences (a time and Google's page, nothing secret), as before the client moved. */
private class PrefsVerificationStore(ctx: Context) : YtmVerificationStore {
    private val prefs = ctx.applicationContext.getSharedPreferences("ytm_verification", Context.MODE_PRIVATE)
    override fun until(): Long = prefs.getLong("until", 0)
    override fun seen(): Long = prefs.getLong("seen", 0)
    override fun level(): Int = prefs.getInt("level", 0)
    override fun html(): String? = prefs.getString("html", null)
    override fun save(until: Long, seen: Long, level: Int, html: String) {
        prefs.edit().putLong("until", until).putLong("seen", seen).putInt("level", level).putString("html", html).apply()
    }
    override fun clear() { prefs.edit().clear().apply() }
}

private fun verificationStore(ctx: Context): YtmVerificationStore = PrefsVerificationStore(ctx)

// The block, asked with a Context as the app's screens always did.
fun YtmClient.Verification.remainingSeconds(ctx: Context): Long = remainingSeconds(verificationStore(ctx))
fun YtmClient.Verification.pending(ctx: Context?): Boolean = pending(ctx?.let { verificationStore(it) })
fun YtmClient.Verification.html(ctx: Context): String? = html(verificationStore(ctx))
fun YtmClient.Verification.passed(ctx: Context) = passed(verificationStore(ctx))
