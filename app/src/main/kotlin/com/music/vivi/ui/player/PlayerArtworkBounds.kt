/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.ui.player

import androidx.compose.ui.geometry.Rect

/**
 * Where the expanded player draws the current song's artwork, in window coordinates, so other screens can animate a
 * cover straight onto it (the spin wheel morphs its result into the now playing view this way).
 */
object PlayerArtworkBounds {
    @Volatile
    var rect: Rect? = null

    @Volatile
    var cornerRadiusPx: Float = 0f
}
