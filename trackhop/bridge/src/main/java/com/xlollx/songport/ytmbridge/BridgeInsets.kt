package com.xlollx.songport.ytmbridge

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * The screens of this module draw edge to edge (Android 15 and later impose it on an app that
 * targets them): without this their top bar sat under the status bar, its text under the clock and
 * its button under the icons, and the keyboard covered the page's fields. The root keeps clear of
 * the system bars, the camera cutout and the keyboard, as Trackhop's own screens do.
 */
object BridgeInsets {
    fun fit(root: View) {
        val pad = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(pad[0] + bars.left, pad[1] + bars.top, pad[2] + bars.right, pad[3] + maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }
}
