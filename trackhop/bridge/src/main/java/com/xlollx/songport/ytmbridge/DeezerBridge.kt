package com.xlollx.songport.ytmbridge

import android.content.Context

/**
 * Deezer through the web player's session, for accounts that cannot use the official API: Deezer
 * stopped registering new developer apps, so the route with a key of one's own is closed to most.
 *
 * The user signs in once on deezer.com's own page inside a WebView; the session cookies (`arl`
 * is the one that names the account) are kept encrypted in [session], never handed to Trackhop.
 * [DeezerWebClient] then talks to the web player's gateway the way deezer.com does.
 */
object DeezerBridge {
    val session = WebSession("deezer", listOf("www.deezer.com", "deezer.com", "account.deezer.com", "connect.deezer.com"))
    const val ORIGIN = DeezerWebClient.ORIGIN
    const val LOGIN_URL = "https://www.deezer.com/login"

    /** The session of one account: the primary's, or an extra slot's own (see [Slots]). */
    fun session(slot: String): WebSession = session.forSlot(slot)

    /** `arl` is the long-lived cookie of a signed-in browser; without it the gateway answers as a visitor. */
    fun signedIn(cookies: String?): Boolean = WebSession.cookieValue(cookies, "arl") != null

    /** The app's side of [DeezerWebClient]: the WebView sign-in in [session]. */
    fun host(ctx: Context, slot: String = Slots.PRIMARY): WebHost = object : WebHost {
        private val sess = session(slot)
        /** The WebView's jar while it still holds the sign-in, the copy saved at sign-in otherwise. */
        override fun cookies(): String? {
            val stored = sess.cookies(ctx) ?: return null
            val live = runCatching { sess.jar().getCookie(ORIGIN) }.getOrNull()
            return if (signedIn(live)) live!! else stored
        }
        override fun account(): String? = sess.account(ctx)
        override fun get(key: String): String? = sess.get(ctx, key)
        override fun put(key: String, value: String?) = sess.put(ctx, key, value)
        override val userAgent: String get() = BridgeUa.desktop(ctx)
        override val slot: String get() = sess.slot
    }

    /** The account's display name, read once after sign-in. Blocking; never on the main thread. */
    fun accountName(ctx: Context, slot: String = Slots.PRIMARY): String? = DeezerWebClient(ctx.applicationContext, slot).user(refresh = true).name
}

/** The client as the app has always built it, from a Context; [slot] names an extra account. */
fun DeezerWebClient(ctx: Context, slot: String = Slots.PRIMARY): DeezerWebClient = DeezerWebClient(DeezerBridge.host(ctx, slot))
