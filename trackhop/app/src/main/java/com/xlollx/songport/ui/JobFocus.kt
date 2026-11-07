package com.xlollx.songport.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A sync to bring into view on the Syncs tab and light up for a moment: asked by a tap on it in a
 * home-screen widget, so it is clear which card that was.
 */
object JobFocus {
    val request = MutableStateFlow<String?>(null)
}
