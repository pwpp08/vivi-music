package com.xlollx.songport.providers

import android.content.Context
import androidx.webkit.WebViewFeature
import java.util.concurrent.ConcurrentHashMap

/**
 * A second (third…) account of a web connector (YouTube Music, Spotify, Apple Music, Deezer, Qobuz,
 * Anghami, LiveOne through their web players): each extra account keeps its own session and its
 * own WebView profile in the connectors, and every call and sign-in names it with a `slot`.
 *
 * Offered only where it can work: the WebView must keep profiles apart (MULTI_PROFILE), and the
 * connectors must take a slot, which their status answers say (`slots`). The built-in connectors
 * always do; a connector plugin from before this says nothing, and an extra account is not offered
 * through it rather than signing in to the same session twice. The primary account is never
 * concerned: it is served exactly as it always was.
 */
object WebAccounts {
    enum class State {
        /** Another account can be added. */
        AVAILABLE,
        /** The WebView of this device cannot keep profiles apart. */
        NO_PROFILES,
        /** The connector plugin answers, but knows nothing of slots: it needs an update. */
        PLUGIN_OLD,
        /** No connectors answer for this service (none installed, or not reachable). */
        NOT_SERVED,
    }

    /** A connector whose extra accounts go through the web connectors: which status call says whether they can. */
    interface Connector {
        val statusMethod: String
    }

    /** The application, for the properties that have no context of their own (set at start). */
    @Volatile var app: Context? = null

    /** Whether the WebView keeps profiles apart; asked once, replaced in tests. */
    @Volatile var profilesSupported: () -> Boolean = { runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false) }

    private val cache = ConcurrentHashMap<String, Pair<Long, State>>()
    private const val CACHE_MS = 30_000L

    /**
     * What [profiles] (the WebView keeps profiles apart), [answered] (the status call answered at
     * all) and [slots] (its answer carries `slots` true) add up to.
     */
    fun decide(profiles: Boolean, answered: Boolean, slots: Boolean): State = when {
        !profiles -> State.NO_PROFILES
        !answered -> State.NOT_SERVED
        slots -> State.AVAILABLE
        else -> State.PLUGIN_OLD
    }

    /** The state for the service whose status call is [statusMethod]; asked again after half a minute. */
    fun state(ctx: Context, statusMethod: String): State {
        val now = System.currentTimeMillis()
        cache[statusMethod]?.let { (at, s) -> if (now - at < CACHE_MS) return s }
        val profiles = profilesSupported()
        val answer = if (profiles && BridgePlugin.installed(ctx)) runCatching { BridgePlugin.call(ctx, statusMethod) }.getOrNull() else null
        val s = decide(profiles, answer != null && answer.containsKey("connected"), answer?.getBoolean("slots", false) == true)
        cache[statusMethod] = now to s
        return s
    }

    /** True when another account of the connector answering [statusMethod] can be added now. */
    fun allowed(statusMethod: String): Boolean = app?.let { state(it, statusMethod) == State.AVAILABLE } ?: false

    /** Why [connector] takes no extra account right now, in the person's words; null when it does. */
    fun reason(ctx: Context, connector: MusicProvider): String? {
        val c = connector as? Connector ?: return null
        return when (state(ctx, c.statusMethod)) {
            State.AVAILABLE -> null
            State.NO_PROFILES -> ctx.getString(com.xlollx.songport.R.string.th_web_accounts_need_webview)
            State.PLUGIN_OLD -> ctx.getString(com.xlollx.songport.R.string.th_web_accounts_need_plugin)
            State.NOT_SERVED -> null
        }
    }

    /** Forgets what the connectors answered, so the next question reaches them (after a sign-in, an install). */
    fun forget() { cache.clear() }
}
