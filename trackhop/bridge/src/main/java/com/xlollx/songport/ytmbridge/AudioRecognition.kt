package com.xlollx.songport.ytmbridge

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.xlollx.songport.ytmbridge.BridgeNet.withHook
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * The song in an audio file by its address (a TikTok sound): the file is downloaded, about twelve
 * seconds from its middle decoded with the phone's own decoder, and its signature sent to Shazam
 * ([Shazam]). Runs in Trackhop's full build in-process and in the connector plugin for the Play one.
 */
object AudioRecognition {
    /** The largest file read: a sound of several minutes is a few MB. */
    private const val MAX_BYTES = 25L * 1024 * 1024

    private val http = OkHttpClient.Builder().withHook()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    fun recognize(ctx: Context, url: String): Shazam.Match? {
        require(url.startsWith("https://")) { "not an https address" }
        val file = File.createTempFile("sound", ".audio", ctx.cacheDir)
        try {
            val req = Request.Builder().url(url)
                .header("User-Agent", BridgeUa.desktop(ctx))
                .header("Referer", "https://www.tiktok.com/")
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw BridgeException("the sound's file answered HTTP ${r.code}")
                val body = r.body ?: throw BridgeException("the sound's file is empty")
                if (body.contentLength() > MAX_BYTES) throw BridgeException("the sound's file is too large")
                file.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            val pcm = decodeMiddle(file.path, Shazam.SECONDS + 1)
            if (pcm.size < Shazam.SAMPLE_RATE * 3) throw BridgeException("the sound is too short to recognise")
            val sig = Shazam.signature(Shazam.excerpt(pcm))
            val match = Shazam.recognize(sig)
            BridgeNet.log("shazam", "${pcm.size / Shazam.SAMPLE_RATE}s decoded, ${sig.peakCount} peaks: " +
                (match?.let { "recognised" } ?: "no song found"))
            return match
        } finally {
            file.delete()
        }
    }

    /**
     * [seconds] of the file around its middle, decoded and turned into 16 kHz mono samples.
     */
    fun decodeMiddle(path: String, seconds: Int): ShortArray {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw BridgeException("no audio in the sound's file")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (duration > seconds * 1_000_000L) ex.seekTo(duration / 2 - seconds * 500_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mono = ArrayList<Short>(seconds * rate)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            try {
                while (mono.size < seconds * rate) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> codec.outputFormat.let {
                            rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        o >= 0 -> {
                            val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val frame = ShortArray(buf.remaining()).also { buf.get(it) }
                            var k = 0
                            while (k + channels <= frame.size) {
                                var sum = 0
                                for (c in 0 until channels) sum += frame[k + c]
                                mono += (sum / channels).toShort()
                                k += channels
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }; codec.release()
            }
            return Resample.to16k(mono.toShortArray(), rate)
        } finally {
            ex.release()
        }
    }
}
