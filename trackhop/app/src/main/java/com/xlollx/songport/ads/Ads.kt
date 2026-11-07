package com.xlollx.songport.ads

import com.xlollx.songport.providers.*
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * No ads in any build, Google Play's included (the user's choice, 05/11/2026: the AdMob banner made
 * the developer a trader under Google Play's rules). No advertising SDK, no consent form: this is
 * the interface the rest of the app still calls, so the screens need no change.
 */
object Ads {
    /** Always false: no note about ads, no ads privacy options. */
    const val enabled = false

    private val none = MutableStateFlow(false)
    val canRequestAds: StateFlow<Boolean> get() = none
    val privacyOptionsRequired: StateFlow<Boolean> get() = none

    @Suppress("UNUSED_PARAMETER")
    fun start(activity: Activity) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun showPrivacyOptions(activity: Activity) = Unit
}

/** Nessun banner: non occupa spazio. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun AdBanner(modifier: Modifier = Modifier) = Unit
