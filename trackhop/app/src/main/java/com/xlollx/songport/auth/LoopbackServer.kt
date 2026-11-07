package com.xlollx.songport.auth

import com.xlollx.songport.providers.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder

/**
 * Minuscolo server HTTP su 127.0.0.1 che intercetta il redirect OAuth.
 *
 * Serve per il flusso "installed app" di Google: un client OAuth di tipo *Desktop* non accetta
 * schemi personalizzati, accetta il loopback. Cosi' l'utente puo' usare le proprie credenziali
 * Google (e quindi la propria quota YouTube) senza che il redirect sia cablato nel manifest.
 *
 * Ascolta solo su loopback: nessun'altra app o dispositivo in rete puo' raggiungerlo, e resta
 * aperto un solo giro di autorizzazione.
 */
class LoopbackServer private constructor(private val socket: ServerSocket, private val appName: String) {

    val port: Int get() = socket.localPort
    val redirectUri: String get() = "http://127.0.0.1:$port"

    /**
     * Attende il redirect del browser e restituisce i parametri della query (code/state/error).
     * Ritorna una mappa vuota se il tempo scade o la connessione non porta nulla di utile.
     */
    suspend fun awaitRedirect(timeoutMs: Int = 5 * 60 * 1000): Map<String, String> = withContext(Dispatchers.IO) {
        socket.soTimeout = timeoutMs
        socket.use { server ->
            val client = server.accept()
            client.use { conn ->
                conn.soTimeout = 15_000
                val reader = conn.getInputStream().bufferedReader()
                // Ci basta la request line: "GET /?code=...&state=... HTTP/1.1"
                val requestLine = reader.readLine() ?: return@withContext emptyMap()
                val params = parseQuery(requestLine.split(' ').getOrNull(1) ?: "")
                val body = if (params.containsKey("code")) page("Tutto fatto", "Puoi chiudere questa scheda e tornare a $appName.")
                    else page("Accesso non riuscito", "Torna a $appName e riprova.")
                conn.getOutputStream().apply {
                    write(
                        ("HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${body.toByteArray().size}\r\n" +
                            "Connection: close\r\n\r\n" + body).toByteArray()
                    )
                    flush()
                }
                params
            }
        }
    }

    fun close() = runCatching { socket.close() }.let { }

    private fun parseQuery(target: String): Map<String, String> {
        val query = target.substringAfter('?', "")
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null
            else runCatching {
                URLDecoder.decode(pair.substring(0, i), "UTF-8") to URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            }.getOrNull()
        }.toMap()
    }

    companion object {
        /** Apre una porta libera sul loopback. */
        suspend fun open(appName: String): LoopbackServer = withContext(Dispatchers.IO) {
            LoopbackServer(ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")), appName)
        }

        private fun page(title: String, text: String) = """
            <!doctype html><html lang="it"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title>
            <style>body{font-family:system-ui,sans-serif;margin:0;min-height:100vh;display:flex;
            flex-direction:column;align-items:center;justify-content:center;gap:12px;text-align:center;
            padding:24px;color:#222;background:#fafafa}h1{font-size:1.2rem;margin:0}p{margin:0;opacity:.75}</style>
            </head><body><h1>$title</h1><p>$text</p></body></html>
        """.trimIndent()
    }
}
