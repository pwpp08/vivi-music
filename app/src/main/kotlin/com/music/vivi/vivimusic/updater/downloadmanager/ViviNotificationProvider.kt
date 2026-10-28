package com.music.vivi.vivimusic.updater.downloadmanager

import android.app.Notification
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.music.vivi.R
import com.music.vivi.constants.MediaSessionConstants
import java.util.Locale

/**
 * A custom MediaNotification.Provider that delegates to a customized DefaultMediaNotificationProvider
 * and adds Android 16 Status Chips (Live Updates) support.
 *
 * Ensures the Shuffle action is placed at index 0 so that Dynamic Island,
 * notifications, and compact media cards display the Shuffle button on the
 * bottom-left matching the Control Center media player.
 */
@OptIn(UnstableApi::class)
class ViviNotificationProvider(
    private val context: Context,
    notificationIdProvider: DefaultMediaNotificationProvider.NotificationIdProvider,
    channelId: String,
    channelNameResourceId: Int,
) : MediaNotification.Provider {

    private val defaultProvider = InternalNotificationProvider(
        context,
        notificationIdProvider,
        channelId,
        channelNameResourceId,
    )

    fun setSmallIcon(iconResId: Int): ViviNotificationProvider {
        defaultProvider.setSmallIcon(iconResId)
        return this
    }

    private class InternalNotificationProvider(
        context: Context,
        notificationIdProvider: NotificationIdProvider,
        channelId: String,
        channelNameResourceId: Int,
    ) : DefaultMediaNotificationProvider(
        context,
        notificationIdProvider,
        channelId,
        channelNameResourceId,
    ) {
        override fun getMediaButtons(
            session: MediaSession,
            playerCommands: Player.Commands,
            customLayout: ImmutableList<CommandButton>,
            showWhenClosed: Boolean,
        ): ImmutableList<CommandButton> {
            val defaultButtons = super.getMediaButtons(session, playerCommands, customLayout, showWhenClosed)

            val shuffleButton = customLayout.firstOrNull {
                it.sessionCommand?.customAction == MediaSessionConstants.ACTION_TOGGLE_SHUFFLE
            } ?: return defaultButtons

            val reordered = ArrayList<CommandButton>()
            // Place Shuffle first (at index 0) so that notification card / Dynamic Island displays it on the bottom-left
            reordered.add(shuffleButton)

            for (btn in defaultButtons) {
                if (btn.sessionCommand?.customAction != MediaSessionConstants.ACTION_TOGGLE_SHUFFLE) {
                    reordered.add(btn)
                }
            }
            return ImmutableList.copyOf(reordered)
        }

        override fun addNotificationActions(
            session: MediaSession,
            mediaButtons: ImmutableList<CommandButton>,
            builder: NotificationCompat.Builder,
            actionFactory: MediaNotification.ActionFactory,
        ): IntArray {
            super.addNotificationActions(session, mediaButtons, builder, actionFactory)
            // Reordered: [0] = Shuffle, [1] = Previous, [2] = Play/Pause, [3] = Next, [4] = Like
            // For compact view slots, show Shuffle (0), Play/Pause (2), and Next (3)
            return if (mediaButtons.size >= 4) {
                intArrayOf(0, 2, 3)
            } else {
                intArrayOf(0, 1)
            }
        }
    }

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        val mediaNotification = defaultProvider.createNotification(
            mediaSession,
            customLayout,
            actionFactory,
            onNotificationChangedCallback,
        )

        // Android 16 Status Chips implementation (API 36 or "Baklava")
        val isAndroid16 = Build.VERSION.SDK_INT >= 36 || Build.VERSION.CODENAME == "Baklava"

        if (isAndroid16) {
            val player = mediaSession.player
            val isPlaying = player.playWhenReady && player.playbackState == Player.STATE_READY

            val durationMs = player.duration
            val currentPosMs = player.currentPosition

            val formattedTime = if (durationMs != C.TIME_UNSET && durationMs > 0) {
                val totalSeconds = durationMs / 1000
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
            } else null

            val builder = Notification.Builder.recoverBuilder(context, mediaNotification.notification)

            builder.setOngoing(true)
            builder.setCategory(Notification.CATEGORY_TRANSPORT)
            builder.setColorized(false)
            builder.setSmallIcon(R.drawable.icon)

            setRequestPromotedOngoingSafely(builder, true)
            builder.extras.putBoolean("android.requestPromotedOngoing", true)

            if (isPlaying) {
                setShortCriticalTextSafely(builder, formattedTime ?: context.getString(R.string.playing_status))

                if (durationMs != C.TIME_UNSET && durationMs > 0) {
                    val remainingMs = durationMs - currentPosMs
                    val endTime = System.currentTimeMillis() + remainingMs
                    builder.setWhen(endTime)
                    setChronometerCountDownSafely(builder, true)
                    builder.setUsesChronometer(true)
                    builder.setShowWhen(true)
                }
            } else {
                setShortCriticalTextSafely(builder, formattedTime ?: context.getString(R.string.paused_status))
                builder.setShowWhen(false)
                builder.setUsesChronometer(false)
            }

            val updatedNotification = builder.build()

            if (Build.VERSION.SDK_INT >= 33) {
                mediaNotification.notification.extras.getParcelable(
                    Notification.EXTRA_MEDIA_SESSION,
                    android.media.session.MediaSession.Token::class.java,
                )?.let {
                    updatedNotification.extras.putParcelable(Notification.EXTRA_MEDIA_SESSION, it)
                }
            } else {
                @Suppress("DEPRECATION")
                mediaNotification.notification.extras.getParcelable<android.media.session.MediaSession.Token>(
                    Notification.EXTRA_MEDIA_SESSION,
                )?.let {
                    updatedNotification.extras.putParcelable(Notification.EXTRA_MEDIA_SESSION, it)
                }
            }

            return MediaNotification(mediaNotification.notificationId, updatedNotification)
        }

        return mediaNotification
    }

    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean =
        defaultProvider.handleCustomCommand(session, action, extras)

    private fun setShortCriticalTextSafely(builder: Notification.Builder, text: String) {
        try {
            val method = Notification.Builder::class.java.getMethod("setShortCriticalText", CharSequence::class.java)
            method.invoke(builder, text)
        } catch (e: Exception) {
            builder.extras.putCharSequence("android.shortCriticalText", text)
        }
    }

    private fun setChronometerCountDownSafely(builder: Notification.Builder, countDown: Boolean) {
        try {
            val method = Notification.Builder::class.java.getMethod(
                "setChronometerCountDown",
                Boolean::class.javaPrimitiveType,
            )
            method.invoke(builder, countDown)
        } catch (e: Exception) {
            builder.extras.putBoolean("android.chronometerCountDown", countDown)
        }
    }

    private fun setRequestPromotedOngoingSafely(builder: Notification.Builder, promoted: Boolean) {
        try {
            val methodNames = arrayOf("setRequestPromotedOngoing", "setPromotedOngoing", "setOngoingActivity")
            for (name in methodNames) {
                try {
                    val method = Notification.Builder::class.java.getMethod(name, Boolean::class.javaPrimitiveType)
                    method.invoke(builder, promoted)
                    break
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            builder.extras.putBoolean("android.requestPromotedOngoing", promoted)
        }
    }
}
