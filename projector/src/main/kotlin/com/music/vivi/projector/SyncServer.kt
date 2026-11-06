package com.music.vivi.projector

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Tiny single-thread HTTP listener the phone pushes settings to (POST /sync, JSON body).
 * Plain sockets only: no web-server dependency, one idle thread.
 */
class SyncServer(
    private val code: String,
    private val onSettings: (JSONObject) -> Unit,
) {
    @Volatile private var socket: ServerSocket? = null
    var port: Int = 0
        private set

    fun start() {
        val s = ServerSocket(0).also { socket = it }
        port = s.localPort
        Thread({
            while (!s.isClosed) {
                try {
                    s.accept().use { handle(it) }
                } catch (_: Exception) {
                }
            }
        }, "vivi-sync").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { socket?.close() }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 5000
        val input = client.getInputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val requestLine = lines.firstOrNull().orEmpty()
        val headers = lines.drop(1).mapNotNull {
            val i = it.indexOf(':')
            if (i > 0) it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim() else null
        }.toMap()

        val out = client.getOutputStream()
        if (!requestLine.startsWith("POST /sync")) {
            reply(out, 200, "Vivi Projector")
            return
        }
        if (headers["x-code"] != code) {
            reply(out, 403, "bad code")
            return
        }
        val length = headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..65536 }
        if (length == null) {
            reply(out, 400, "bad length")
            return
        }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        if (read != length) {
            reply(out, 400, "short body")
            return
        }
        try {
            onSettings(JSONObject(String(body, Charsets.UTF_8)))
            reply(out, 200, "ok")
        } catch (_: Exception) {
            reply(out, 400, "bad json")
        }
    }

    /** Reads bytes up to and including the blank line ending the headers. */
    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf(13, 10, 13, 10)
        while (buf.size() < 8192) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
            if (matched == 4) return String(buf.toByteArray(), Charsets.ISO_8859_1).trimEnd()
        }
        return null
    }

    private fun reply(out: java.io.OutputStream, status: Int, text: String) {
        val bytes = text.toByteArray()
        out.write(
            ("HTTP/1.1 $status X\r\nContent-Type: text/plain\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                .toByteArray(),
        )
        out.write(bytes)
        out.flush()
    }
}
