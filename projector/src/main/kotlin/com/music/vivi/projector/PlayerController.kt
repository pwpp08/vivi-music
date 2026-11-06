package com.music.vivi.projector

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.runBlocking
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.YouTubeClient

/** Single process-wide player + queue. Compose reads the observable fields directly. */
object PlayerController {
    private lateinit var player: ExoPlayer
    private lateinit var settings: Settings
    private lateinit var http: DefaultHttpDataSource.Factory
    private val main = Handler(Looper.getMainLooper())

    var queue by mutableStateOf<List<SongItem>>(emptyList())
        private set
    var index by mutableStateOf(-1)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set

    val current: SongItem? get() = queue.getOrNull(index)

    private var token = 0

    private val ticker = object : Runnable {
        override fun run() {
            positionMs = player.currentPosition
            val d = player.duration
            durationMs = if (d != C.TIME_UNSET && d > 0) d else (current?.duration?.times(1000L) ?: 0L)
            main.postDelayed(this, 500)
        }
    }

    fun init(context: Context, settings: Settings) {
        this.settings = settings
        StreamResolver.init(context)
        http = DefaultHttpDataSource.Factory()
            .setUserAgent(YouTubeClient.USER_AGENT_WEB)
            .setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) next()
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayerError(e: PlaybackException) {
                android.util.Log.e("ViviPlayer", "player error: " + e.errorCodeName + " " + e.cause, e)
                error = "Playback failed, skipping"
                next()
            }
        })
        main.post(ticker)
    }

    fun playQueue(songs: List<SongItem>, start: Int) {
        if (songs.isEmpty()) return
        queue = songs
        playIndex(start.coerceIn(songs.indices))
    }

    fun playIndex(i: Int) {
        if (i !in queue.indices) return
        index = i
        val song = queue[i]
        val my = ++token
        loading = true
        error = null
        positionMs = 0
        durationMs = song.duration?.times(1000L) ?: 0L
        Thread {
            val stream = pickStream(song.id)
            android.util.Log.e("ViviPlayer", "stream for " + song.id + " = " + stream?.url?.take(80))
            main.post {
                if (my != token) return@post
                loading = false
                if (stream == null) {
                    error = "No stream for " + song.title + ", skipping"
                    next()
                    return@post
                }
                stream.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { http.setUserAgent(it.value) }
                http.setDefaultRequestProperties(stream.headers.filterKeys { !it.equals("User-Agent", true) })
                player.setMediaItem(MediaItem.fromUri(stream.url))
                player.prepare()
                player.play()
            }
        }.start()
    }

    fun toggle() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun next() = playIndex(index + 1)

    fun previous() {
        if (player.currentPosition > 4000) player.seekTo(0) else playIndex(index - 1)
    }

    fun seekBy(deltaMs: Long) {
        val max = durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (player.currentPosition + deltaMs).coerceIn(0, max)
        player.seekTo(target)
        positionMs = target
    }

    private fun pickStream(videoId: String): StreamResolver.Stream? =
        runBlocking { StreamResolver.resolve(videoId, settings.audioQuality) }
            .onSuccess { android.util.Log.e("ViviPlayer", "using " + it.client + " headers=" + it.headers.keys) }
            .onFailure { android.util.Log.e("ViviPlayer", "resolve failed: " + it, it) }
            .getOrNull()
}
