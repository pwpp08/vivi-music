package com.xlollx.songport.ytmbridge

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A second (third…) account of a web connector: the "slot" Trackhop names in its calls (`slot` in
 * the extras: "2", "3"…; absent or "" for the account there has always been, the primary).
 *
 * An extra slot keeps its own session ([WebSession.forSlot], [Session.forSlot]) and signs in and
 * runs in its own WebView profile (androidx.webkit's MULTI_PROFILE, one profile per service and
 * slot), so two accounts of one service never share a cookie jar. The primary account keeps the
 * default profile and the storage it has always had: nothing about it changes, and a Trackhop that
 * never names a slot is served exactly as before.
 *
 * A device whose WebView cannot keep profiles apart has no extra slots: Trackhop does not offer
 * them, and a sign-in or a call that would need one fails with [UNSUPPORTED].
 */
object Slots {
    const val EXTRA = "slot"
    const val PRIMARY = ""
    const val UNSUPPORTED = "another account of a web connector needs a newer Android System WebView"
    private val SHAPE = Regex("[0-9]{1,4}")

    /** The slot named in [extras]; the primary when absent or malformed. */
    fun of(extras: Bundle?): String = valid(extras?.getString(EXTRA))

    /** [slot] when it is one ("2", "3"…), the primary otherwise. */
    fun valid(slot: String?): String = slot?.takeIf { SHAPE.matches(it) } ?: PRIMARY

    /** Whether the WebView keeps profiles apart; replaced in tests, where there is no WebView. */
    @Volatile var multiProfile: () -> Boolean = { runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false) }

    /** True when this device's WebView can hold extra slots. */
    fun supported(): Boolean = multiProfile()

    /** The profile [service]'s [slot] lives in: one per service and slot, so a sign-out wipes only its own. */
    fun profileName(service: String, slot: String): String = "trackhop-$service-$slot"

    private val managers = ConcurrentHashMap<String, CookieManager>()

    /**
     * The cookie jar of [service]'s [slot]: the WebView's own for the primary (as always), the
     * slot's profile otherwise. The profile is opened on the main thread, once; from any other
     * thread this waits for it (a few seconds at most).
     */
    @android.annotation.SuppressLint("RequiresFeature") // guarded by supported(), which lint does not follow
    fun cookies(service: String, slot: String): CookieManager {
        if (slot.isEmpty()) return CookieManager.getInstance()
        val name = profileName(service, slot)
        managers[name]?.let { return it }
        if (!supported()) throw BridgeException(UNSUPPORTED)
        val cm = onMain { ProfileStore.getInstance().getOrCreateProfile(name).cookieManager }
        managers[name] = cm
        return cm
    }

    /**
     * Puts [web] in the slot's profile: called right after the WebView is made, before it loads
     * anything (a WebView in use cannot change profile). The primary stays in the default one.
     */
    @android.annotation.SuppressLint("RequiresFeature") // guarded by supported(), which lint does not follow
    fun apply(web: WebView, service: String, slot: String) {
        if (slot.isEmpty()) return
        if (!supported()) throw BridgeException(UNSUPPORTED)
        val name = profileName(service, slot)
        // Opening the profile first keeps its cookie jar at hand for the threads that read it.
        managers.getOrPut(name) { ProfileStore.getInstance().getOrCreateProfile(name).cookieManager }
        WebViewCompat.setProfile(web, name)
    }

    /** Runs [block] on the main thread and waits for its result (at once when already there). */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.getMainLooper().isCurrentThread) return block()
        val latch = CountDownLatch(1)
        var out: T? = null
        var failure: Throwable? = null
        Handler(Looper.getMainLooper()).post {
            try { out = block() } catch (e: Throwable) { failure = e } finally { latch.countDown() }
        }
        if (!latch.await(10, TimeUnit.SECONDS)) throw BridgeException("the browser engine's profile did not open in time")
        failure?.let { throw BridgeException("the browser engine's profile could not be opened: ${it.message ?: it.javaClass.simpleName}") }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }
}
