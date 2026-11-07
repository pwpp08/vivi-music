package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env

/**
 * L'ISRC di un brano che non ce l'ha, chiesto al catalogo pubblico di Deezer (nessuna chiave,
 * nessun account: la stessa ricerca che chiunque fa su deezer.com).
 *
 * Le strade web (Spotify web, YouTube Music, Amazon) e i file locali non portano l'ISRC, e senza
 * ISRC l'abbinamento va per titolo, artista e durata: bene quasi sempre, meno con remix, versioni
 * live e riedizioni. Con l'ISRC in mano, Spotify, Apple Music, TIDAL e Deezer rispondono con la
 * registrazione esatta al primo colpo. Si chiede una volta per brano e si ricorda l'esito, anche
 * negativo, per una settimana.
 */
object IsrcOracle {
    private const val PREFS = "isrc_oracle"
    private const val TTL_MS = 7L * 24 * 3_600_000
    private const val MAX = 5_000

    private val memory = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 2_000
    }

    private fun key(t: Track): String = (Matcher.searchArtist(t.artists.firstOrNull() ?: "") + "|" + Matcher.searchTitle(t.title)).lowercase()

    /** L'ISRC, o null quando il catalogo non ha nulla di abbastanza vicino. Mai un'eccezione: e' un aiuto, non un passaggio. */
    suspend fun find(env: Env, t: Track): String? {
        if (t.title.isBlank() || t.kind.isNotEmpty()) return null
        val k = key(t)
        synchronized(memory) { memory[k] }?.let { return it.ifEmpty { null } }
        val prefs = env.keyValues(PREFS)
        prefs[k]?.let { saved ->
            val (isrc, at) = saved.substringBefore('|') to (saved.substringAfter('|', "0").toLongOrNull() ?: 0)
            if (System.currentTimeMillis() - at < TTL_MS) { synchronized(memory) { memory[k] = isrc }; return isrc.ifEmpty { null } }
        }
        val isrc = runCatching { lookup(t) }.getOrElse { e ->
            env.log("isrc", "lookup failed for ${t.title.take(40)}: ${e.message?.take(80)}")
            return null // Not remembered: a network hiccup is not "no ISRC".
        } ?: ""
        synchronized(memory) { memory[k] = isrc }
        if (prefs.size >= MAX) prefs.clear()
        prefs[k] = "$isrc|${System.currentTimeMillis()}"
        return isrc.ifEmpty { null }
    }

    private suspend fun lookup(t: Track): String? {
        val artist = t.artists.firstOrNull()?.let { Matcher.searchArtist(it) }.orEmpty()
        val title = Matcher.searchTitle(t.title)
        // Deezer's artist:"…" filter answers nothing (#46): the title filtered, the artist as plain words.
        val q = if (artist.isBlank()) title else "track:\"$title\" $artist"
        val resp = Http.send("GET", "https://api.deezer.com/search?limit=8&q=" + Http.enc(q))
        if (!resp.ok) return null
        val candidates = parseJson(resp.body)["data"].arr.mapNotNull { d ->
            val id = d["id"].long ?: return@mapNotNull null
            Track(
                id = id.toString(), title = d["title"].str ?: return@mapNotNull null,
                artists = listOfNotNull(d["artist"]["name"].str), album = d["album"]["title"].str ?: "",
                durationMs = (d["duration"].long ?: 0) * 1000,
            )
        }
        // The same bar as an accepted match: a loose neighbour's ISRC would point the search at the wrong recording.
        val best = Matcher.bestScored(t, candidates, 0.0)?.takeIf { it.score >= 0.80 } ?: return null
        val track = Http.send("GET", "https://api.deezer.com/track/${best.track.id}")
        if (!track.ok) return null
        return parseJson(track.body)["isrc"].str?.trim()?.takeIf { it.length == 12 }
    }
}
