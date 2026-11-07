package com.xlollx.songport.ytmbridge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Song recognition from a piece of audio, the way the Shazam app does it: an audio signature
 * (frequency peaks, nothing of the sound itself) sent to Shazam, which answers with the song.
 *
 * The signature is a port of SongRec's (https://github.com/marin-m/SongRec, GPL-3.0, by Marin M.),
 * whose author worked out Shazam's format; the request is the one SongRec sends. No account and no
 * key. It is not an official API: used only where unofficial services are (the GitHub and F-Droid
 * builds, Docker and Desktop outside the Microsoft Store, and the connector plugin).
 */
object Shazam {

    const val SAMPLE_RATE = 16_000
    /** How much of the audio is signed: twelve seconds from the middle, as SongRec does. */
    const val SECONDS = 12

    /** One frequency peak: when (the FFT pass), how loud, at which (corrected) bin. */
    class Peak(val pass: Int, val magnitude: Int, val bin: Int)

    /** A signature: the peaks of four frequency bands (250–520, 520–1450, 1450–3500, 3500–5500 Hz). */
    class Signature(val samples: Int, val bands: List<List<Peak>>) {
        val peakCount: Int get() = bands.sumOf { it.size }

        /** The binary format Shazam reads (little-endian header, CRC-32, peaks per band). */
        fun encode(): ByteArray {
            val body = ByteArrayOutputStream()
            fun u32(out: ByteArrayOutputStream, v: Long) { for (i in 0 until 4) out.write(((v shr (8 * i)) and 0xFF).toInt()) }
            fun u16(out: ByteArrayOutputStream, v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
            for ((band, peaks) in bands.withIndex()) {
                if (peaks.isEmpty()) continue
                val p = ByteArrayOutputStream()
                var pass = 0
                for (peak in peaks) {
                    if (peak.pass - pass >= 255) { p.write(0xFF); u32(p, peak.pass.toLong()); pass = peak.pass }
                    p.write(peak.pass - pass)
                    u16(p, peak.magnitude)
                    u16(p, peak.bin)
                    pass = peak.pass
                }
                u32(body, 0x60030040L + band)
                u32(body, p.size().toLong())
                p.writeTo(body)
                repeat((4 - p.size() % 4) % 4) { body.write(0) }
            }
            val size = body.size() + 8 // the two words after the header count too
            val out = ByteBuffer.allocate(48 + size).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(0xCAFE2580.toInt())
            out.putInt(0) // CRC-32, below
            out.putInt(size)
            out.putInt(0x94119C00.toInt())
            out.putInt(0); out.putInt(0); out.putInt(0)
            out.putInt(3 shl 27) // 16 000 Hz
            out.putInt(0); out.putInt(0)
            out.putInt(samples + (SAMPLE_RATE * 0.24).toInt())
            out.putInt((15 shl 19) + 0x40000)
            out.putInt(0x40000000)
            out.putInt(size)
            out.put(body.toByteArray())
            val bytes = out.array()
            val crc = CRC32().apply { update(bytes, 8, bytes.size - 8) }.value
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, crc.toInt())
            return bytes
        }

        fun uri(): String = "data:audio/vnd.shazam.sig;base64," + Base64.getEncoder().encodeToString(encode())
    }

    /**
     * The part of [pcm] (16 kHz mono) that is signed: the middle twelve seconds of a longer piece,
     * a shorter one padded with silence.
     */
    fun excerpt(pcm: ShortArray): ShortArray {
        val n = SECONDS * SAMPLE_RATE
        if (pcm.size <= n) return pcm.copyOf(n)
        val start = pcm.size / 2 - n / 2
        return pcm.copyOfRange(start, start + n)
    }

    /** The signature of [pcm], 16 kHz mono, as SongRec's SignatureGenerator makes it. */
    fun signature(pcm: ShortArray): Signature = Generator(pcm.size).run { feed(pcm); Signature(pcm.size, bands) }

    private class Generator(@Suppress("unused") val total: Int) {
        val ring = ShortArray(2048)
        var ringIndex = 0
        val windowed = DoubleArray(2048)
        val re = DoubleArray(2048)
        val im = DoubleArray(2048)
        val ffts = Array(256) { FloatArray(1025) }
        var fftIndex = 0
        val spread = Array(256) { FloatArray(1025) }
        var spreadIndex = 0
        var spreadDone = 0
        val bands = List(4) { ArrayList<Peak>() }

        fun feed(pcm: ShortArray) {
            var at = 0
            while (at + 128 <= pcm.size) {
                fft(pcm, at)
                spreadPeaks()
                spreadDone++
                if (spreadDone >= 46) recognize()
                at += 128
            }
        }

        fun fft(pcm: ShortArray, from: Int) {
            System.arraycopy(pcm, from, ring, ringIndex, 128)
            ringIndex = (ringIndex + 128) and 2047
            for (i in 0 until 2048) windowed[i] = ring[(i + ringIndex) and 2047] * HANNING[i]
            for (i in 0 until 2048) { re[i] = windowed[i]; im[i] = 0.0 }
            Fft.forward(re, im)
            val out = ffts[fftIndex]
            for (i in 0 until 1025) out[i] = max((re[i] * re[i] + im[i] * im[i]) / (1 shl 17), 0.0000000001).toFloat()
            fftIndex = (fftIndex + 1) and 255
        }

        fun spreadPeaks() {
            val last = ffts[(fftIndex - 1) and 255]
            val s = spread[spreadIndex]
            System.arraycopy(last, 0, s, 0, 1025)
            for (p in 0..1022) s[p] = max(max(s[p], s[p + 1]), s[p + 2])
            val copy = s.copyOf()
            for (p in 0..1024) for (former in FORMER) {
                val f = spread[(spreadIndex - former) and 255]
                f[p] = max(f[p], copy[p])
            }
            spreadIndex = (spreadIndex + 1) and 255
        }

        fun recognize() {
            val f46 = ffts[(fftIndex - 46) and 255]
            val f49 = spread[(spreadIndex - 49) and 255]
            for (bin in 10..1014) {
                val v = f46[bin]
                if (v < 1f / 64f || v < f49[bin - 1]) continue
                var around = 0f
                for (o in NEIGHBOURS) around = max(around, f49[bin + o])
                if (v <= around) continue
                var other = around
                for (o in OTHERS) other = max(other, spread[(spreadIndex + o) and 255][bin - 1])
                if (v <= other) continue
                val pass = spreadDone - 46
                fun mag(x: Float) = max(ln(x), 1f / 64f) * 1477.3f + 6144f
                val m = mag(v)
                val before = mag(f46[bin - 1])
                val after = mag(f46[bin + 1])
                val v1 = m * 2f - before - after
                val v2 = (after - before) * 32f / v1
                val corrected = ((bin * 64) + v2.toInt()) and 0xFFFF
                val hz = corrected * (16000f / 2f / 1024f / 64f)
                val band = when (hz.toInt()) {
                    in 250..519 -> 0
                    in 520..1449 -> 1
                    in 1450..3499 -> 2
                    in 3500..5500 -> 3
                    else -> continue
                }
                bands[band] += Peak(pass, m.toInt().coerceIn(0, 0xFFFF), corrected)
            }
        }
    }

    private val FORMER = intArrayOf(1, 3, 6)
    private val NEIGHBOURS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
    private val OTHERS = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)

    /** SongRec's window: a 2049-point Hanning without its leading and trailing zeroes. */
    private val HANNING = DoubleArray(2048) { 0.5 * (1 - cos(2 * PI * (it + 1) / 2049)) }

    /** An in-place radix-2 FFT, enough for one 2048-point frame at a time. */
    private object Fft {
        fun forward(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                val wr = cos(ang); val wi = sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0; var ci = 0.0
                    for (k in 0 until len / 2) {
                        val a = i + k; val b = a + len / 2
                        val xr = re[b] * cr - im[b] * ci
                        val xi = re[b] * ci + im[b] * cr
                        re[b] = re[a] - xr; im[b] = im[a] - xi
                        re[a] += xr; im[a] += xi
                        val ncr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr; cr = ncr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }

    // ------------------------------------------------------------------ the request

    /** What Shazam recognised. */
    data class Match(val title: String, val artist: String, val album: String?, val isrc: String?)

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val JSON = "application/json".toMediaType()

    /** Shazam's endpoint; replaced in tests. */
    @Volatile var endpoint = "https://amp.shazam.com"

    /**
     * Asks Shazam which song [signature] is: null when it knows none (a voice, a song it lacks).
     * Throws when the request itself fails (no network, 429 when an address asks too often).
     */
    fun recognize(signature: Signature, client: OkHttpClient = http): Match? {
        val now = System.currentTimeMillis()
        val body = JsonObject(mapOf(
            "geolocation" to JsonObject(mapOf("altitude" to JsonPrimitive(300), "latitude" to JsonPrimitive(45), "longitude" to JsonPrimitive(2))),
            "signature" to JsonObject(mapOf(
                "samplems" to JsonPrimitive((signature.samples * 1000L / SAMPLE_RATE)),
                "timestamp" to JsonPrimitive(now and 0xFFFFFFFFL),
                "uri" to JsonPrimitive(signature.uri()),
            )),
            "timestamp" to JsonPrimitive(now and 0xFFFFFFFFL),
            "timezone" to JsonPrimitive("Europe/Paris"),
        ))
        val url = "$endpoint/discovery/v5/en/US/android/-/tag/${UUID.randomUUID().toString().uppercase()}/${UUID.randomUUID()}" +
            "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3"
        val req = Request.Builder().url(url).post(body.toString().toRequestBody(JSON))
            .header("Content-Language", "en_US")
            .header("User-Agent", USER_AGENTS.random())
            .build()
        val (code, text) = client.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        if (code == 429) throw BridgeException("Shazam asks to slow down (429)")
        if (code !in 200..299) throw BridgeException("Shazam answered HTTP $code")
        return parse(parseJson(text))
    }

    /** The song in Shazam's answer, or null when it matched nothing. */
    fun parse(j: JsonElement): Match? {
        val track = j["track"].takeUnless { it == null || it is JsonNull } ?: return null
        val title = track["title"].str?.trim().orEmpty()
        val artist = track["subtitle"].str?.trim().orEmpty()
        if (title.isEmpty()) return null
        val album = track["sections"].arr.firstNotNullOfOrNull { s ->
            s["metadata"].arr.firstOrNull { it["title"].str.equals("Album", ignoreCase = true) }?.get("text").str
        }
        return Match(title, artist, album?.trim()?.ifEmpty { null }, track["isrc"].str)
    }

    /** Android device names, as SongRec sends (a few of its list): Shazam's own app is on phones. */
    private val USER_AGENTS = listOf(
        "Dalvik/2.1.0 (Linux; U; Android 5.0.2; VS980 4G Build/LRX22G)",
        "Dalvik/2.1.0 (Linux; U; Android 5.1.1; SM-P905V Build/LMY47X)",
        "Dalvik/2.1.0 (Linux; U; Android 6.0.1; SM-G920F Build/MMB29K)",
        "Dalvik/2.1.0 (Linux; U; Android 5.0; SM-G900F Build/LRX21T)",
        "Dalvik/2.1.0 (Linux; U; Android 6.0.1; SM-G928F Build/MMB29K)",
        "Dalvik/2.1.0 (Linux; U; Android 5.1.1; SM-J500FN Build/LMY48B)",
    )
}

/** Samples of any rate brought to the 16 kHz [Shazam] signs: a windowed-sinc low-pass, then resampling. */
object Resample {
    private const val TAPS = 16

    fun to16k(pcm: ShortArray, rate: Int): ShortArray {
        if (rate == Shazam.SAMPLE_RATE || pcm.isEmpty()) return pcm
        val ratio = rate.toDouble() / Shazam.SAMPLE_RATE
        // Below the new Nyquist frequency (8 kHz), with a margin: Shazam's bands stop at 5.5 kHz.
        val cutoff = minOf(1.0, 1.0 / ratio) * 0.9
        val out = ShortArray((pcm.size / ratio).toInt())
        for (n in out.indices) {
            val t = n * ratio
            val c = t.toInt()
            var acc = 0.0
            var norm = 0.0
            for (k in c - TAPS..c + TAPS) {
                if (k < 0 || k >= pcm.size) continue
                val x = (t - k) * cutoff
                val sinc = if (x == 0.0) 1.0 else kotlin.math.sin(kotlin.math.PI * x) / (kotlin.math.PI * x)
                val w = 0.5 * (1 + kotlin.math.cos(kotlin.math.PI * (t - k) / (TAPS + 1)))
                acc += pcm[k] * sinc * w
                norm += sinc * w
            }
            out[n] = (if (norm != 0.0) acc / norm else 0.0).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}
