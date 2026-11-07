package com.xlollx.songport.sync

import com.xlollx.songport.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * What a radio is playing, by a short visit rather than by listening for hours: open the stream
 * asking for its metadata (`Icy-MetaData: 1`), read the audio up to the first metadata block (the
 * `icy-metaint` bytes the station names, usually 8 to 32 KB, a few seconds), take its StreamTitle
 * and close. Songs last three minutes and more, so a visit every two minutes catches nearly all of
 * them for a few megabytes an hour instead of the whole stream's sixty. Pure JVM: the app samples
 * this way, and the server can too.
 */
object RadioSampler {

    /** The stream answers but carries no titles: no `icy-metaint` header (an HLS or plain audio stream). */
    class NoTitles : IOException("this stream sends no song titles")

    /** The station refused the stream (404, 403…). */
    class Refused(val code: Int) : IOException("the station answered $code")

    /** What one visit read: the title on air (null when the station sent none this time) and the bytes it cost. */
    data class Sample(val title: String?, val bytes: Long)

    /** Longest audio block between two metadata blocks believed: past this the header is nonsense. */
    private const val MAX_METAINT = 1 shl 20

    /** A playlist (.pls, .m3u) handed instead of the stream is read up to this many bytes. */
    private const val MAX_PLAYLIST = 64 * 1024

    /** The `icy-metaint` header as a block size, or [NoTitles] when the stream has none. */
    fun metaInt(header: String?): Int {
        val n = header?.trim()?.toIntOrNull() ?: throw NoTitles()
        if (n <= 0 || n > MAX_METAINT) throw NoTitles()
        return n
    }

    /**
     * Reads [input], a stream sending a metadata block after every [metaInt] bytes of audio, up to
     * the first block that names a title, looking at no more than [maxBlocks] blocks (some stations
     * send an empty block first). The audio is read and dropped, never kept.
     */
    fun readTitle(input: InputStream, metaInt: Int, maxBlocks: Int = 3): Sample {
        val discard = ByteArray(8 * 1024)
        var bytes = 0L
        repeat(maxBlocks) {
            var left = metaInt
            while (left > 0) {
                val n = input.read(discard, 0, minOf(left, discard.size))
                if (n < 0) throw IOException("the stream ended")
                left -= n
                bytes += n
            }
            val len = input.read()
            if (len < 0) throw IOException("the stream ended")
            bytes++
            if (len == 0) return@repeat
            val block = ByteArray(len * 16)
            var off = 0
            while (off < block.size) {
                val n = input.read(block, off, block.size - off)
                if (n < 0) throw IOException("the stream ended")
                off += n
            }
            bytes += block.size
            // Most stations write UTF-8; a block that is not valid UTF-8 is Latin-1, as older Shoutcast sends.
            val text = utf8OrLatin1(block)
            return Sample(RadioTitles.streamTitle(text), bytes)
        }
        return Sample(null, bytes)
    }

    private fun utf8OrLatin1(b: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(b)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            String(b, Charsets.ISO_8859_1)
        }
    }

    private val client: OkHttpClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** One visit to [stream]: the title on air now. Throws [NoTitles], [Refused] or an [IOException]. */
    suspend fun sample(stream: String): Sample = withContext(Dispatchers.IO) { visit(stream, hops = 0) }

    private fun visit(stream: String, hops: Int): Sample {
        if (hops > 5) throw IOException("too many redirects")
        val uri = URI(stream.trim())
        val opened = when (uri.scheme?.lowercase()) {
            "https" -> openTls(uri.toString())
            "http" -> openPlain(uri)
            else -> throw IOException("not a stream address")
        }
        opened.use { o ->
            if (o.code in 300..399) {
                val to = o.header("location") ?: throw Refused(o.code)
                return visit(uri.resolve(to).toString(), hops + 1)
            }
            if (o.code !in 200..299) throw Refused(o.code)
            val metaInt = o.header("icy-metaint")
            if (metaInt == null && hops < 5 && looksLikePlaylist(uri, o.header("content-type"))) {
                // A .pls or .m3u names the stream rather than being it: the first address in it.
                // Up to its length, when the server says it: a connection kept open would otherwise wait.
                val limit = o.header("content-length")?.trim()?.toIntOrNull()?.coerceIn(0, MAX_PLAYLIST) ?: MAX_PLAYLIST
                val text = String(readUpTo(o.body, limit), Charsets.UTF_8)
                val next = Regex("""https?://\S+""").find(text)?.value ?: throw NoTitles()
                return visit(next, hops + 1)
            }
            return readTitle(o.body, metaInt(metaInt))
        }
    }

    private fun looksLikePlaylist(uri: URI, type: String?): Boolean {
        val t = type.orEmpty().lowercase()
        val path = uri.path.orEmpty().lowercase()
        return "mpegurl" in t || "scpls" in t || "x-pls" in t || path.endsWith(".pls") || path.endsWith(".m3u")
    }

    private fun readUpTo(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** An open stream: its status, headers (lower case) and body, closed together. */
    private class Opened(val code: Int, private val headers: Map<String, String>, val body: InputStream, private val closer: () -> Unit) : Closeable {
        fun header(name: String): String? = headers[name.lowercase()]
        override fun close() { runCatching { closer() } }
    }

    private fun openTls(url: String): Opened {
        val req = Request.Builder().url(url)
            .header("Icy-MetaData", "1")
            .header("User-Agent", "ViviMusic")
            // The audio as sent: no transparent gzip in between.
            .header("Accept-Encoding", "identity")
            .build()
        val resp = client.newCall(req).execute()
        val headers = resp.headers.associate { (k, v) -> k.lowercase() to v }
        val body = resp.body?.byteStream() ?: java.io.ByteArrayInputStream(ByteArray(0))
        return Opened(resp.code, headers, body) { resp.close() }
    }

    /**
     * A plain-HTTP stream, over a socket of its own: most internet radios still stream over http,
     * older Shoutcast servers answer "ICY 200 OK" instead of "HTTP/1.0 200 OK", and only the song's
     * title, public, is read. HTTP/1.0, so the body comes as it is, never chunked.
     */
    private fun openPlain(uri: URI): Opened {
        val host = uri.host ?: throw IOException("not a stream address")
        val port = if (uri.port > 0) uri.port else 80
        Http.onRequest(host)
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), 15_000)
            socket.soTimeout = 15_000
            val path = (uri.rawPath?.ifEmpty { null } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            val hostHeader = if (uri.port > 0 && uri.port != 80) "$host:${uri.port}" else host
            val request = "GET $path HTTP/1.0\r\nHost: $hostHeader\r\nIcy-MetaData: 1\r\nUser-Agent: ViviMusic\r\nAccept: */*\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(request.toByteArray(Charsets.ISO_8859_1)); flush() }
            val input = socket.getInputStream().buffered()
            val status = readLine(input) ?: throw IOException("the station did not answer")
            // "HTTP/1.1 200 OK", "HTTP/1.0 302 Found", "ICY 200 OK".
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("not a stream: $status")
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) throw IOException("this stream is sent in chunks")
            return Opened(code, headers, input) { socket.close() }
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    /** One header line, without its CRLF; null at the end of the stream. Lines past 8 KB are an error. */
    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > 8 * 1024) throw IOException("not a stream")
        }
        return out.toString(Charsets.ISO_8859_1.name())
    }
}
