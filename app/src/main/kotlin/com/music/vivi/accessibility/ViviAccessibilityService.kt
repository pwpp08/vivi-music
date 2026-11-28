package com.music.vivi.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.TextUtils
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.music.vivi.constants.ScreenOffVolumeSkipKey
import com.music.vivi.playback.MusicService
import com.music.vivi.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Long press volume up / down while the phone is in a pocket (screen off, a covered proximity sensor, or an untouched
 * lock screen) to skip to the next / previous track. A short press still changes the volume.
 */
class ViviAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private var screenOffVolumeSkipEnabled = false

    // The volume key whose press we swallowed on the way down. Its release has to be handled by us as well, even if the
    // screen / player state changed in between (e.g. the player is buffering after the skip).
    private var swallowedVolumeKey = KeyEvent.KEYCODE_UNKNOWN
    private var volumeHoldStart = 0L
    private var volumePressCount = 0
    private var isVolumeLongPressTriggered = false
    private var volumeLongPressRunnable: Runnable? = null
    private var pendingVolumeRelease: Runnable? = null

    // With the screen off the CPU may go back to sleep right after the key event, so the long-press timer would only
    // fire on the next wake-up. A short wake lock keeps it running for the length of a press.
    private var pressWakeLock: PowerManager.WakeLock? = null

    private var lastScreenTouchTime = 0L

    // A covered proximity sensor with the screen on means the phone is in a pocket or a bag.
    private val sensorManager by lazy { getSystemService(Context.SENSOR_SERVICE) as? SensorManager }
    private var proximityCovered = false
    private var proximityRegistered = false
    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val distance = event.values.firstOrNull() ?: return
            proximityCovered = distance < event.sensor.maximumRange && distance < 5f
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        Timber.tag(TAG).d("ViviAccessibilityService connected")

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.apply {
            // Only touch events are needed (to know whether someone is using the lock screen). Receiving every
            // accessibility event of every app would keep this service's main thread busy, which delays the key handling
            // below: the system gives up on a key event after ~500 ms.
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                    AccessibilityEvent.TYPE_TOUCH_INTERACTION_START or
                    AccessibilityEvent.TYPE_GESTURE_DETECTION_START
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = (flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) and
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS.inv()
        }
        serviceInfo = info

        serviceScope.launch {
            dataStore.data.map { preferences ->
                preferences[ScreenOffVolumeSkipKey] ?: false
            }.distinctUntilChanged().collect { enabled ->
                screenOffVolumeSkipEnabled = enabled
                updateProximityListener()
                Timber.tag(TAG).d("screenOffVolumeSkipEnabled: $enabled")
            }
        }
    }

    private fun updateProximityListener() {
        val manager = sensorManager ?: return
        if (screenOffVolumeSkipEnabled && !proximityRegistered) {
            val sensor = manager.getDefaultSensor(Sensor.TYPE_PROXIMITY) ?: return
            proximityRegistered = manager.registerListener(proximityListener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
        } else if (!screenOffVolumeSkipEnabled && proximityRegistered) {
            manager.unregisterListener(proximityListener)
            proximityRegistered = false
            proximityCovered = false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START -> {
                lastScreenTouchTime = SystemClock.uptimeMillis()
            }
        }
    }

    override fun onInterrupt() {
        Timber.tag(TAG).d("ViviAccessibilityService interrupted")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.onKeyEvent(event)
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (swallowedVolumeKey == keyCode) {
                    // Either a key repeat while holding, or the key coming back down right after a bounce. Pressing
                    // through fabric often makes the contact open for a few milliseconds mid-hold; that must not turn
                    // the long press into a volume step.
                    pendingVolumeRelease?.let {
                        handler.removeCallbacks(it)
                        pendingVolumeRelease = null
                    }
                    if (event.repeatCount == 0) volumePressCount++
                    // If the timer was late, the time since the first press is just as good a clock.
                    if (!isVolumeLongPressTriggered && event.eventTime - volumeHoldStart >= LONG_PRESS_TIMEOUT_MS) {
                        triggerVolumeLongPress(keyCode)
                    }
                    return true
                }

                if (screenOffVolumeSkipEnabled && isInPocketState() && MusicService.isPlaybackActive()) {
                    // The other volume key may still be waiting out its release grace period; settle it first.
                    flushPendingRelease()
                    acquirePressWakeLock()
                    swallowedVolumeKey = keyCode
                    volumeHoldStart = event.downTime
                    volumePressCount = 1
                    isVolumeLongPressTriggered = false
                    cancelVolumeLongPress()
                    val runnable = Runnable {
                        // Only skip while the key is actually down, not during a pending release.
                        if (pendingVolumeRelease == null) triggerVolumeLongPress(keyCode)
                    }
                    volumeLongPressRunnable = runnable
                    val elapsed = (SystemClock.uptimeMillis() - event.downTime).coerceAtLeast(0L)
                    handler.postDelayed(runnable, (LONG_PRESS_TIMEOUT_MS - elapsed).coerceAtLeast(0L))
                    return true // Consume to prevent volume change during initial press
                }
            }

            KeyEvent.ACTION_UP -> {
                if (swallowedVolumeKey == keyCode) {
                    if (!isVolumeLongPressTriggered && event.eventTime - volumeHoldStart >= LONG_PRESS_TIMEOUT_MS) {
                        // Held long enough, even if the timer never got to run (the CPU was asleep): it's a skip.
                        triggerVolumeLongPress(keyCode)
                    }
                    if (isVolumeLongPressTriggered) {
                        finishVolumePress()
                    } else {
                        // Wait a moment before treating this as a short click: if the key comes back down within the
                        // grace period it was a bounce and the hold continues.
                        pendingVolumeRelease?.let { handler.removeCallbacks(it) }
                        val release = Runnable { settleShortPress(keyCode) }
                        pendingVolumeRelease = release
                        handler.postDelayed(release, RELEASE_GRACE_MS)
                    }
                    // Always consume the release of a press we swallowed, so the system never sees a lone "up".
                    return true
                }
            }
        }

        return super.onKeyEvent(event)
    }

    private fun settleShortPress(keyCode: Int) {
        pendingVolumeRelease = null
        val steps = volumePressCount.coerceIn(1, 5)
        val longPressHandled = isVolumeLongPressTriggered
        finishVolumePress()
        if (!longPressHandled) repeat(steps) { adjustVolumeOneStep(keyCode) }
    }

    private fun flushPendingRelease() {
        val key = swallowedVolumeKey
        val pending = pendingVolumeRelease ?: return
        if (key == KeyEvent.KEYCODE_UNKNOWN) return
        handler.removeCallbacks(pending)
        settleShortPress(key)
    }

    private fun isScreenOff(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isInteractive == false
    }

    // In a pocket the screen is usually off, but a notification or lift-to-wake can light it up for a few seconds. A
    // covered proximity sensor, or a lock screen nobody is touching, count as "in the pocket" too.
    private fun isInPocketState(): Boolean {
        if (isScreenOff()) return true
        if (proximityCovered) return true
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguardManager?.isKeyguardLocked == true &&
            SystemClock.uptimeMillis() - lastScreenTouchTime > POCKET_TOUCH_GUARD_MS
    }

    private fun acquirePressWakeLock() {
        try {
            val lock = pressWakeLock ?: (getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vivi:volumeSkip")
                ?.apply { setReferenceCounted(false) }
                ?.also { pressWakeLock = it }
            lock?.acquire(WAKE_LOCK_TIMEOUT_MS)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Volume skip wake lock failed")
        }
    }

    private fun releasePressWakeLock() {
        try {
            pressWakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
    }

    private fun finishVolumePress() {
        swallowedVolumeKey = KeyEvent.KEYCODE_UNKNOWN
        cancelVolumeLongPress()
        pendingVolumeRelease?.let { handler.removeCallbacks(it) }
        pendingVolumeRelease = null
        isVolumeLongPressTriggered = false
        volumePressCount = 0
        releasePressWakeLock()
    }

    private fun cancelVolumeLongPress() {
        volumeLongPressRunnable?.let { handler.removeCallbacks(it) }
        volumeLongPressRunnable = null
    }

    private fun triggerVolumeLongPress(keyCode: Int) {
        if (isVolumeLongPressTriggered) return
        isVolumeLongPressTriggered = true
        volumeLongPressRunnable = null
        vibrateVolumeSkip()
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            Timber.tag(TAG).d("Volume UP long-pressed in pocket: skipping to next track")
            MusicService.skipNext()
        } else {
            Timber.tag(TAG).d("Volume DOWN long-pressed in pocket: skipping to previous track")
            MusicService.skipPrevious()
        }
    }

    private fun adjustVolumeOneStep(keyCode: Int) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                AudioManager.ADJUST_RAISE
            } else {
                AudioManager.ADJUST_LOWER
            }
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error adjusting volume one step")
        }
    }

    private fun vibrateVolumeSkip() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(50L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50L)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Volume skip vibration failed")
        }
    }

    override fun onDestroy() {
        isServiceRunning = false
        finishVolumePress()
        if (proximityRegistered) {
            sensorManager?.unregisterListener(proximityListener)
            proximityRegistered = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ViviAccessibility"
        private const val LONG_PRESS_TIMEOUT_MS = 450L
        private const val RELEASE_GRACE_MS = 150L
        private const val POCKET_TOUCH_GUARD_MS = 4000L
        private const val WAKE_LOCK_TIMEOUT_MS = 3000L

        @Volatile
        var isServiceRunning = false
            private set

        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val expectedComponentName = ComponentName(context, ViviAccessibilityService::class.java)
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)
            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val enabledComponent = ComponentName.unflattenFromString(componentNameString)
                if (enabledComponent != null && enabledComponent == expectedComponentName) {
                    return true
                }
            }
            return false
        }
    }
}
