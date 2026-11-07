package com.xlollx.songport.ytmbridge

import android.app.Activity
import android.app.AlertDialog
import android.content.Context

/**
 * The liability notice. Every sign-in (any web connector, started here or by Trackhop)
 * requires the user to have accepted it once; the acceptance is stored on this device only.
 */
object Disclaimer {
    /**
     * The notice's current version. 2 names Deezer and SoundCloud: their sign-ins ask for this
     * version, so an acceptance given to the older text (version 1) does not cover them.
     */
    const val VERSION = 2

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("bridge_consent", Context.MODE_PRIVATE)

    /** The version accepted on this device: 0 for none, 1 for an acceptance stored before versions existed. */
    fun acceptedVersion(ctx: Context): Int = prefs(ctx).getInt("version", if (prefs(ctx).getBoolean("accepted", false)) 1 else 0)

    fun accepted(ctx: Context, version: Int = 1): Boolean = acceptedVersion(ctx) >= version

    fun accept(ctx: Context) { prefs(ctx).edit().putBoolean("accepted", true).putInt("version", VERSION).apply() }

    /** Runs [onAccepted] at once if [version] (or a later one) was accepted, otherwise after the user accepts; cancelling finishes the activity. */
    fun require(activity: Activity, version: Int = 1, onAccepted: () -> Unit) {
        if (accepted(activity, version)) { onAccepted(); return }
        AlertDialog.Builder(activity)
            .setTitle(R.string.th_bridge_disclaimer_title)
            .setMessage(R.string.th_bridge_disclaimer_body)
            .setCancelable(false)
            .setPositiveButton(R.string.th_bridge_disclaimer_accept) { _, _ -> accept(activity); onAccepted() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> activity.setResult(Activity.RESULT_CANCELED); activity.finish() }
            .show()
    }
}
