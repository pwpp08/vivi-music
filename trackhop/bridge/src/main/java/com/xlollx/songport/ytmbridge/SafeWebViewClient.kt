package com.xlollx.songport.ytmbridge

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * A [WebViewClient] that survives its page's renderer: when the WebView's renderer process crashes
 * (a heavy web player, low memory) or is stopped by the system, Android kills the whole app unless
 * the client says it handled it. Here the dead WebView is taken off the screen and destroyed; a
 * screen showing it closes with a short message, a page working out of sight just goes away (what
 * waited on it times out with its own error).
 */
open class SafeWebViewClient : WebViewClient() {
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        val host = runCatching { android.net.Uri.parse(view.url.orEmpty()).host }.getOrNull().orEmpty()
        runCatching { BridgeNet.log("web", "the page's renderer " + (if (detail.didCrash()) "crashed" else "was stopped by the system") + (if (host.isNotEmpty()) " ($host)" else "") + ": page closed, the app goes on") }
        val activity = activityOf(view.context)
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        runCatching { view.destroy() }
        if (activity != null && !activity.isFinishing) {
            runCatching { Toast.makeText(activity.applicationContext, activity.getString(R.string.th_bridge_page_crashed), Toast.LENGTH_LONG).show() }
            activity.finish()
        }
        return true
    }

    private fun activityOf(ctx: Context?): Activity? {
        var c = ctx
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }
}
