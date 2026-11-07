package com.xlollx.songport.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The "open music links with Trackhop" switch: an activity alias in the manifest, off by default,
 * that the package manager turns on or off. On, Android lists Trackhop among the apps for a track
 * or playlist link; the user still picks, in the chooser or in the system's link settings.
 */
object MusicLinks {
    private fun component(ctx: Context) = ComponentName(ctx.packageName, "com.xlollx.songport.MusicLinkActivity")

    fun enabled(ctx: Context): Boolean =
        ctx.packageManager.getComponentEnabledSetting(component(ctx)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun set(ctx: Context, on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching { ctx.packageManager.setComponentEnabledSetting(component(ctx), state, PackageManager.DONT_KILL_APP) }
    }
}
