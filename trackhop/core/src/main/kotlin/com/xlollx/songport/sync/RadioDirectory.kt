package com.xlollx.songport.sync

import com.xlollx.songport.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress

/**
 * Finding a station by name in radio-browser.info, the free, community-run directory of some
 * 50,000 internet radios with their stream addresses (no key, no account). Its servers come and
 * go: `all.api` holds the live ones, the named ones are tried after it, and a server already tried
 * under another name (they often share an address) is not asked twice. The same search as the server's.
 */
object RadioDirectory {
    data class Station(val name: String, val stream: String, val homepage: String, val country: String, val codec: String, val bitrate: Int)

    /** No server of the directory answered: [detail] says how the last one failed, for the log. */
    class Unavailable(val detail: String) : Exception("radio-browser.info is not answering right now ($detail)")

    val HOSTS = listOf("all", "de1", "de2", "fi1", "nl1", "at1").map { "$it.api.radio-browser.info" }

    /** The hosts worth asking, in order: those that do not resolve are left out, and so is one at an address already listed. */
    fun servers(resolve: (String) -> List<String>): List<String> {
        val seen = HashSet<String>()
        return HOSTS.filter { host ->
            val addresses = runCatching { resolve(host) }.getOrDefault(emptyList())
            addresses.map { seen.add(it) }.contains(true)
        }
    }

    /** The stations whose name holds [name], the most voted first. Throws [Unavailable] when no server answers. */
    suspend fun search(name: String, limit: Int = 20): List<Station> {
        val q = Http.enc(name.trim())
        val hosts = withContext(Dispatchers.IO) { servers { h -> InetAddress.getAllByName(h).map { it.hostAddress.orEmpty() } } }
        if (hosts.isEmpty()) throw Unavailable("its servers cannot be found")
        var last = ""
        for (host in hosts) {
            try {
                val r = Http.send(
                    "GET", "https://$host/json/stations/search?name=$q&limit=$limit&hidebroken=true&order=votes&reverse=true",
                    headers = mapOf("User-Agent" to "ViviMusic"), maxRateLimitRetries = 1,
                )
                if (!r.ok) { last = "HTTP ${r.code}"; continue }
                return parse(r.body)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e.message ?: e.javaClass.simpleName
            }
        }
        throw Unavailable(last)
    }

    /** The directory's answer as stations: those without a stream address are left out. */
    fun parse(body: String): List<Station> =
        (com.xlollx.songport.net.json.parseToJsonElement(body) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = runCatching { e.jsonObject }.getOrNull() ?: return@mapNotNull null
            fun s(k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty().trim()
            val stream = s("url_resolved").ifEmpty { s("url") }
            if (stream.isEmpty() || !(stream.startsWith("http://") || stream.startsWith("https://"))) null
            else Station(s("name"), stream, s("homepage"), s("country"), s("codec"), s("bitrate").toIntOrNull() ?: 0)
        }
}
