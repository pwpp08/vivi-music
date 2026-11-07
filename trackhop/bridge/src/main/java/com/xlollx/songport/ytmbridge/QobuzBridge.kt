package com.xlollx.songport.ytmbridge

import android.content.Context

/**
 * Qobuz through play.qobuz.com's web player ([QobuzWebClient]): the user signs in once on Qobuz's
 * own page inside a WebView (email, Google, Apple, Facebook); the player then sends its session
 * (`X-User-Auth-Token`) and its app id (`X-App-Id`) as headers of every API request, which the
 * sign-in screen reads as the requests go by and keeps encrypted in [session]. Where the in-app
 * sign-in cannot finish, a request copied from a desktop browser's developer tools (or the token
 * alone) can be pasted.
 */
object QobuzBridge {
    val session = WebSession("qobuz", listOf("play.qobuz.com", "qobuz.com", "www.qobuz.com"))
    /** What the session's "cookies" hold: the session lives in the token, not in cookies. */
    private const val MARK = "qobuz=1"

    /** The session of one account: the primary's, or an extra slot's own (see [Slots]). */
    fun session(slot: String): WebSession = session.forSlot(slot)

    /** The token and app id of a player request to [url] with [headers], when it carries a signed-in session. */
    fun fromRequest(url: String, headers: Map<String, String>?): Pair<String, String?>? = QobuzWebClient.sessionOf(url, headers)

    /** Keeps the token in [pasted] (with the player's app id, when known); what is wrong, or null. */
    fun keep(ctx: Context, pasted: String, appId: String? = null, account: String? = null, slot: String = Slots.PRIMARY): String? {
        val (token, seenApp) = QobuzWebClient.sessionIn(pasted) ?: return "no Qobuz session in it"
        val sess = session(slot)
        sess.clear(ctx)
        sess.put(ctx, QobuzWebClient.TOKEN, token)
        (appId ?: seenApp)?.let { sess.put(ctx, QobuzWebClient.APP_ID, it) }
        sess.save(ctx, MARK, account ?: "Qobuz")
        return null
    }

    /** Names the account once the session has been tried. */
    fun name(ctx: Context, account: String, slot: String = Slots.PRIMARY) { val sess = session(slot); if (sess.isConnected(ctx)) sess.save(ctx, MARK, account) }

    /** The app's side of [QobuzWebClient]: the session kept at sign-in. */
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
fun QobuzWebClient(ctx: Context, slot: String = Slots.PRIMARY): QobuzWebClient = QobuzWebClient(QobuzBridge.host(ctx, slot))
