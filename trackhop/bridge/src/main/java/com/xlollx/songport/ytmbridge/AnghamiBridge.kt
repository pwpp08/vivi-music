package com.xlollx.songport.ytmbridge

import android.content.Context

/**
 * Anghami through play.anghami.com's web player ([AnghamiWebClient]): the user signs in once on
 * Anghami's own page inside a WebView (email, phone, Google, Apple, Facebook); the player then names
 * its session (`sid`) on every request it makes, which the sign-in screen reads as the requests go by
 * and keeps encrypted in [session]. Where the in-app sign-in cannot finish, the sid copied from a
 * desktop browser (any coussa.anghami.com request's address in the developer tools) can be pasted.
 */
object AnghamiBridge {
    val session = WebSession("anghami", listOf("play.anghami.com", "anghami.com", "coussa.anghami.com"))
    /** What the session's "cookies" hold: the session lives in the sid, not in cookies. */
    private const val MARK = "anghami=1"

    /** The session of one account: the primary's, or an extra slot's own (see [Slots]). */
    fun session(slot: String): WebSession = session.forSlot(slot)

    /** The sid and fingerprint of a player request to [url], when it carries a signed-in session. */
    fun fromRequest(url: String): Pair<String, String?>? {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return null
        if (!host.endsWith("anghami.com")) return null
        return AnghamiWebClient.sessionIn(url)?.takeIf { url.contains("sid=") }
    }

    /** Keeps the sid in [pasted] (with the player's fingerprint, when known); what is wrong, or null. */
    fun keep(ctx: Context, pasted: String, fingerprint: String? = null, slot: String = Slots.PRIMARY): String? {
        val sid = AnghamiWebClient.sidIn(pasted) ?: return "no Anghami session in it"
        val sess = session(slot)
        sess.clear(ctx)
        sess.put(ctx, AnghamiWebClient.SID, sid)
        fingerprint?.let { sess.put(ctx, AnghamiWebClient.FP, it) }
        sess.save(ctx, MARK, "Anghami")
        return null
    }

    /** The app's side of [AnghamiWebClient]: the session kept at sign-in. */
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
fun AnghamiWebClient(ctx: Context, slot: String = Slots.PRIMARY): AnghamiWebClient = AnghamiWebClient(AnghamiBridge.host(ctx, slot))
