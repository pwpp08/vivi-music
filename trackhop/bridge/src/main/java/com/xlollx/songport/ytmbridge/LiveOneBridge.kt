package com.xlollx.songport.ytmbridge

import android.content.Context

/**
 * LiveOne through liveone.com's web player ([LiveOneWebClient]): the user signs in once on LiveOne's
 * own page inside a WebView (email, Facebook or Google, popups allowed); the player then keeps its
 * session in the page's local storage (`slackerUser`, with the refresh token), which the sign-in
 * screen reads and keeps encrypted in [session]. Where the in-app sign-in cannot finish (Google may
 * refuse an embedded browser), the same entry can be pasted from a desktop browser instead.
 */
object LiveOneBridge {
    val session = WebSession("liveone", listOf("www.liveone.com", "liveone.com", "account.liveone.com", "slacker.liveone.com"))
    const val ORIGIN = "https://www.liveone.com"
    const val LOGIN_URL = "https://account.liveone.com/login?done=" + "https%3A%2F%2Fwww.liveone.com%2F"
    /** What the session's "cookies" hold: the session lives in the refresh token, not in cookies. */
    private const val MARK = "liveone=1"

    /** Reads the player's session entry: run on www.liveone.com once signed in. */
    const val STORAGE_SCRIPT = "(function(){try{return localStorage.getItem('slackerUser')||localStorage.getItem('refresh_token')||''}catch(e){return ''}})()"

    /** The session of one account: the primary's, or an extra slot's own (see [Slots]). */
    fun session(slot: String): WebSession = session.forSlot(slot)

    /** True when [pasted] (the page's `slackerUser`, or a refresh token) is a session to keep. */
    fun usable(pasted: String?): Boolean {
        val token = pasted?.let { LiveOneWebClient.tokenIn(it) } ?: return false
        return LiveOneWebClient.problemWith(token) == null && LiveOneWebClient.accountOf(token) != null
    }

    /** Keeps the refresh token in [pasted]; null when there is none, or what is wrong with it. */
    fun keep(ctx: Context, pasted: String, slot: String = Slots.PRIMARY): String? {
        val token = LiveOneWebClient.tokenIn(pasted) ?: return "no LiveOne session in it"
        LiveOneWebClient.problemWith(token)?.let { return it }
        val account = LiveOneWebClient.accountOf(token) ?: return "no LiveOne account in it"
        val sess = session(slot)
        sess.clear(ctx)
        sess.put(ctx, LiveOneWebClient.REFRESH, token)
        sess.put(ctx, LiveOneWebClient.ACCOUNT, account)
        sess.save(ctx, MARK, "LiveOne $account")
        return null
    }

    /** The app's side of [LiveOneWebClient]: the session kept at sign-in. */
    fun host(ctx: Context, slot: String = Slots.PRIMARY): WebHost = object : WebHost {
        private val sess = session(slot)
        override fun cookies(): String? = sess.cookies(ctx)
        override fun account(): String? = sess.account(ctx)
        override fun get(key: String): String? = sess.get(ctx, key)
        override fun put(key: String, value: String?) = sess.put(ctx, key, value)
        override val userAgent: String get() = BridgeUa.desktop(ctx)
        override val slot: String get() = sess.slot
    }
}

/** The client as the app builds it, from a Context; [slot] names an extra account. */
fun LiveOneWebClient(ctx: Context, slot: String = Slots.PRIMARY): LiveOneWebClient = LiveOneWebClient(LiveOneBridge.host(ctx, slot))
