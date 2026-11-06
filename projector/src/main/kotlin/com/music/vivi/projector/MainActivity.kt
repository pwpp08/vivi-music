package com.music.vivi.projector

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.YouTubeClient
import kotlinx.coroutines.runBlocking
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.random.Random

/**
 * One screen, plain framework views, no Compose/Hilt/Room: small enough for a 1 GB / 32-bit projector.
 * Everything is driven by D-pad/remote focus.
 */
class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var player: ExoPlayer
    private lateinit var searchBox: EditText
    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var playPause: Button
    private lateinit var syncPanel: FrameLayout
    private lateinit var syncClose: Button
    private lateinit var qrView: ImageView
    private lateinit var syncInfo: TextView

    private val results = ArrayList<SongItem>()
    private lateinit var adapter: ArrayAdapter<String>
    private var current = -1
    private var server: SyncServer? = null

    @Volatile private var playToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this).also { it.apply() }
        buildPlayer()
        buildUi()
        updateStatus(accountStatus())
    }

    private fun accountStatus() =
        if (settings.hasAccount) {
            "Signed in" + (settings.accountName?.let { ": $it" } ?: "")
        } else {
            "Not synced. Press Sync."
        }

    private fun buildPlayer() {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(YouTubeClient.USER_AGENT_WEB)
            .setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) playIndex(current + 1)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playPause.text = if (isPlaying) "Pause" else "Play"
            }

            override fun onPlayerError(error: PlaybackException) {
                toast("Playback failed, skipping")
                playIndex(current + 1)
            }
        })
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#101014")) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(24), dp(32), dp(16))
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        searchBox = EditText(this).apply {
            hint = "Search songs"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setOnEditorActionListener { _, _, _ ->
                search(text.toString())
                true
            }
        }
        top.addView(searchBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(button("Search") { search(searchBox.text.toString()) })
        top.addView(button("Sync") { showSync() })
        column.addView(top)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        list = ListView(this).apply {
            this.adapter = this@MainActivity.adapter
            isFocusable = true
            divider = null
            setOnItemClickListener { _, _, pos, _ -> playIndex(pos) }
        }
        column.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }
        bar.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(button("Prev") { playIndex(current - 1) })
        playPause = button("Play") { if (player.isPlaying) player.pause() else player.play() }
        bar.addView(playPause)
        bar.addView(button("Next") { playIndex(current + 1) })
        column.addView(bar)
        root.addView(column)

        syncPanel = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#F0101014"))
            visibility = View.GONE
            isClickable = true
        }
        val panelCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        qrView = ImageView(this)
        panelCol.addView(qrView, LinearLayout.LayoutParams(dp(260), dp(260)))
        syncInfo = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }
        panelCol.addView(syncInfo)
        syncClose = button("Close") { hideSync() }
        panelCol.addView(syncClose)
        syncPanel.addView(panelCol, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(syncPanel)

        setContentView(root)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun search(query: String) {
        if (query.isBlank()) return
        updateStatus("Searching...")
        Thread {
            val found = runBlocking { YouTube.search(query, YouTube.SearchFilter.FILTER_SONG) }
            runOnUiThread {
                found.onSuccess { page ->
                    results.clear()
                    results.addAll(page.items.filterIsInstance<SongItem>())
                    adapter.clear()
                    adapter.addAll(results.map { "${it.title}  -  ${it.artists.joinToString { a -> a.name }}" })
                    updateStatus("${results.size} results")
                    list.requestFocus()
                }.onFailure { updateStatus("Search failed: ${it.message}") }
            }
        }.start()
    }

    private fun playIndex(index: Int) {
        if (index !in results.indices) return
        current = index
        val song = results[index]
        val token = ++playToken
        updateStatus("Loading: ${song.title}")
        Thread {
            val url = pickStream(song.id)
            runOnUiThread {
                if (token != playToken) return@runOnUiThread
                if (url == null) {
                    toast("No stream, skipping")
                    playIndex(index + 1)
                    return@runOnUiThread
                }
                player.setMediaItem(MediaItem.fromUri(url))
                player.prepare()
                player.play()
                updateStatus("Playing: ${song.title}")
                list.setSelection(index)
            }
        }.start()
    }

    /** itag 140 = AAC 128k, 251 = Opus 160k, 139 = AAC 48k, 249/250 = Opus ~50/70k. */
    private fun pickStream(videoId: String): String? {
        val streams = YouTube.getNewPipeStreamUrls(videoId).toMap()
        val order = when (settings.audioQuality) {
            "HIGH" -> listOf(251, 140, 250, 139, 249)
            "LOW" -> listOf(249, 139, 250, 140, 251)
            else -> listOf(140, 251, 139, 250, 249)
        }
        return order.firstNotNullOfOrNull { streams[it] }
    }

    private fun showSync() {
        val ip = localIp()
        if (ip == null) {
            toast("No Wi-Fi connection")
            return
        }
        val code = Random.nextInt(100000, 1000000).toString()
        server?.stop()
        val newServer = SyncServer(code) { json ->
            settings.save(json)
            runOnUiThread {
                toast("Settings synced from phone")
                updateStatus(accountStatus())
                hideSync()
            }
        }
        newServer.start()
        server = newServer
        val link = "vivimusic://projector?host=$ip&port=${newServer.port}&code=$code"
        qrView.setImageBitmap(qr(link))
        syncInfo.text = "Scan with your phone camera (Vivi must be installed)\n$ip:${newServer.port}   code $code"
        syncPanel.visibility = View.VISIBLE
        syncClose.requestFocus()
    }

    private fun hideSync() {
        server?.stop()
        server = null
        qrView.setImageDrawable(null)
        syncPanel.visibility = View.GONE
        searchBox.requestFocus()
    }

    private fun qr(text: String): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 260, 260)
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    private fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (player.isPlaying) player.pause() else player.play()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                playIndex(current + 1)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                playIndex(current - 1)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (syncPanel.visibility == View.VISIBLE) hideSync() else super.onBackPressed()
    }

    override fun onDestroy() {
        server?.stop()
        player.release()
        super.onDestroy()
    }

    private fun updateStatus(text: String) {
        status.text = text
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
