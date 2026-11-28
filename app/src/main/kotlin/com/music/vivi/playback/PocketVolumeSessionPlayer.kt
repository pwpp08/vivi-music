/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.playback

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import timber.log.Timber

/**
 * The player the media session sees. Normally it just forwards everything to the real player.
 *
 * With the screen off, many phones never show volume key presses to apps (the accessibility service doesn't get them):
 * the system hands them straight to the audio volume, or to the active media session when that session says it plays
 * on a remote device. So while the screen is off and pocket skip is enabled, this player reports "remote" playback with
 * relative volume control. The system then sends every volume key press, and every key repeat while it's held, here
 * instead of changing the volume. A held key (repeats arriving) skips a track; a short press changes the volume as usual.
 * The audio itself keeps playing on the phone the whole time.
 */
@UnstableApi
class PocketVolumeSessionPlayer(
    player: Player,
    private val context: Context,
    private val onSkipNext: () -> Unit,
    private val onSkipPrevious: () -> Unit,
) : ForwardingPlayer(player) {

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val listeners = mutableListOf<Player.Listener>()

    var remoteVolumeKeys: Boolean = false
        private set

    fun setRemoteVolumeKeys(enabled: Boolean) {
        if (enabled == remoteVolumeKeys) return
        remoteVolumeKeys = enabled
        if (!enabled) flushBurst()
        Timber.tag(TAG).d("Pocket volume keys via media session: $enabled")
        val deviceInfo = getDeviceInfo()
        val commands = getAvailableCommands()
        val events = Player.Events(
            FlagSet.Builder()
                .addAll(Player.EVENT_DEVICE_INFO_CHANGED, Player.EVENT_AVAILABLE_COMMANDS_CHANGED)
                .build(),
        )
        listeners.toList().forEach { listener ->
            listener.onDeviceInfoChanged(deviceInfo)
            listener.onAvailableCommandsChanged(commands)
            listener.onEvents(this, events)
        }
    }

    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        super.removeListener(listener)
    }

    override fun getDeviceInfo(): DeviceInfo =
        if (remoteVolumeKeys) {
            DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                .setMinVolume(0)
                .setMaxVolume(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                .build()
        } else {
            super.getDeviceInfo()
        }

    override fun getAvailableCommands(): Player.Commands =
        if (remoteVolumeKeys) {
            super.getAvailableCommands().buildUpon()
                .addAll(
                    Player.COMMAND_GET_DEVICE_VOLUME,
                    Player.COMMAND_ADJUST_DEVICE_VOLUME,
                    Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
                ).build()
        } else {
            super.getAvailableCommands()
        }

    override fun isCommandAvailable(command: Int): Boolean =
        if (remoteVolumeKeys && command in VOLUME_COMMANDS) true else super.isCommandAvailable(command)

    override fun getDeviceVolume(): Int =
        if (remoteVolumeKeys) audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) else super.getDeviceVolume()

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun increaseDeviceVolume() {
        if (remoteVolumeKeys) onVolumeKey(up = true) else super.increaseDeviceVolume()
    }

    override fun increaseDeviceVolume(flags: Int) {
        if (remoteVolumeKeys) onVolumeKey(up = true) else super.increaseDeviceVolume(flags)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun decreaseDeviceVolume() {
        if (remoteVolumeKeys) onVolumeKey(up = false) else super.decreaseDeviceVolume()
    }

    override fun decreaseDeviceVolume(flags: Int) {
        if (remoteVolumeKeys) onVolumeKey(up = false) else super.decreaseDeviceVolume(flags)
    }

    // --- Telling a held key from a short press ---
    // The session only learns about key presses and repeats, never releases. A held key sends one call, then after
    // ~500 ms a stream of repeats ~50 ms apart. A press that is followed by silence was short.

    private var burstUp = true
    private var burstStart = 0L
    private var lastCall = 0L
    private var burstPresses = 0
    private var burstSkipped = false
    private val burstEnd = Runnable { flushBurst() }

    private fun onVolumeKey(up: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (burstPresses > 0 && up != burstUp) flushBurst()
        handler.removeCallbacks(burstEnd)

        if (burstPresses == 0) {
            burstUp = up
            burstStart = now
            burstSkipped = false
        }
        val gap = now - lastCall
        // Contact chatter (pressing through fabric) shows up as presses a few ms apart: count them as one.
        if (burstPresses == 0 || gap > CHATTER_MS) burstPresses++
        lastCall = now

        if (!burstSkipped && burstPresses >= 1 && now - burstStart >= LONG_PRESS_MS && gap < REPEAT_GAP_MS) {
            burstSkipped = true
            vibrate()
            if (up) onSkipNext() else onSkipPrevious()
        }
        handler.postDelayed(burstEnd, BURST_END_MS)
    }

    private fun flushBurst() {
        handler.removeCallbacks(burstEnd)
        if (burstPresses > 0 && !burstSkipped) {
            val direction = if (burstUp) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            repeat(burstPresses.coerceIn(1, 5)) {
                runCatching { audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0) }
            }
        }
        burstPresses = 0
        burstSkipped = false
    }

    private fun vibrate() {
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            } else {
                vibrator?.vibrate(VibrationEffect.createOneShot(50L, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Pocket skip vibration failed")
        }
    }

    companion object {
        private const val TAG = "PocketVolume"
        private val VOLUME_COMMANDS = setOf(
            Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_ADJUST_DEVICE_VOLUME,
            Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        )

        // A key held this long, with repeats still coming, is a skip.
        private const val LONG_PRESS_MS = 450L

        // Key repeats arrive about every 50 ms.
        private const val REPEAT_GAP_MS = 150L

        // Presses closer than this are contact chatter, not separate presses.
        private const val CHATTER_MS = 80L

        // No call for this long means the key is up again (the first repeat comes ~500 ms after the press).
        private const val BURST_END_MS = 650L
    }
}
