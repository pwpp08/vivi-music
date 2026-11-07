package com.xlollx.songport.ai

import android.content.Context
import com.xlollx.songport.ai.AiClient.LocalState
import com.xlollx.songport.model.ProviderException

/**
 * The F-Droid build (Gradle property noMlkit): no Gemini Nano, because Google's ML Kit is not free
 * software and F-Droid does not allow it. Same interface as src/mlkit, so the screens need no change:
 * the state stays NONE, and the Roast and the generator ask for the user's own key, as before 1.14.
 */
object NanoAi : AiClient.LocalModel {
    const val NAME = "Gemini Nano"

    val state: LocalState get() = LocalState.NONE
    val downloaded: Long get() = 0L
    val total: Long get() = 0L
    val error: String? get() = null

    /** Nothing to plug in: AiClient keeps no local model. */
    fun init() = Unit

    fun pick(ctx: Context): AiClient.Config? = AiClient.pick(AiClient.config(ctx), state, NAME)

    fun prepare() = Unit
    suspend fun poll() = Unit
    fun retry() = Unit

    override suspend fun complete(system: String, user: String): String =
        throw ProviderException("$NAME is not part of this build")
}
