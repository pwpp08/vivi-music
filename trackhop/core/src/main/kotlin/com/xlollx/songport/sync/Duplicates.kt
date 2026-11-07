package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import kotlin.math.abs
import kotlin.math.max

/** Trova i brani doppi in una playlist. Kotlin puro: coperto dai test. */
object Duplicates {

    /**
     * Gruppi di brani che sono lo stesso pezzo: stesso id, stesso ISRC, oppure stesso titolo
     * normalizzato + stessa versione (live, acustica, remix...) + stessi artisti normalizzati, e
     * durata compatibile quando e' nota. "Throne" e "Throne - Live at Royal Albert Hall" sono due
     * brani, non un doppione. Ogni gruppo mantiene l'ordine di playlist; il primo elemento e' quello
     * da tenere. Restituisce solo i gruppi con almeno 2 elementi.
     */
    fun groups(tracks: List<Track>): List<List<Track>> {
        val byKey = LinkedHashMap<String, MutableList<Track>>()
        val keyOfId = HashMap<String, String>()
        val keyOfIsrc = HashMap<String, String>()
        for (t in tracks) {
            val version = (Matcher.versionMarkers(t.title) - NEUTRAL).sorted().joinToString(",")
            val textKey = Matcher.normalizeTitle(t.title) + "|" + version + "|" +
                t.artists.map { Matcher.normalizeArtist(it) }.filter { it.isNotEmpty() }.sorted().joinToString(",")
            // Riusa la chiave di un brano gia' visto con stesso id o stesso ISRC, altrimenti quella testuale.
            val key = keyOfId[t.id] ?: t.isrcNorm?.let { keyOfIsrc[it] } ?: textKey
            byKey.getOrPut(key) { ArrayList() }.add(t)
            keyOfId.putIfAbsent(t.id, key)
            t.isrcNorm?.let { keyOfIsrc.putIfAbsent(it, key) }
        }
        return byKey.values.flatMap { splitByDuration(it) }.filter { it.size > 1 }
    }

    /**
     * Stesso testo ma durate lontane: versioni diverse (una live di 7 minuti contro i 4 dello
     * studio). Brani con durata sconosciuta restano con il primo gruppo. Stesso id o stesso ISRC
     * restano insieme comunque.
     */
    private fun splitByDuration(group: List<Track>): List<List<Track>> {
        if (group.size < 2) return listOf(group)
        val clusters = ArrayList<MutableList<Track>>()
        for (t in group) {
            val home = clusters.firstOrNull { c ->
                c.any { it.id == t.id || (it.isrcNorm != null && it.isrcNorm == t.isrcNorm) } ||
                    c.any { sameDuration(it.durationMs, t.durationMs) }
            }
            if (home != null) home.add(t) else clusters.add(mutableListOf(t))
        }
        return clusters
    }

    private fun sameDuration(a: Long, b: Long): Boolean {
        if (a <= 0 || b <= 0) return true
        return abs(a - b) <= max(TOLERANCE_MS, (max(a, b) * TOLERANCE_RATIO).toLong())
    }

    /** I brani da togliere: tutti tranne il primo di ogni gruppo. */
    fun extras(tracks: List<Track>): List<Track> = groups(tracks).flatMap { it.drop(1) }

    private val NEUTRAL = setOf("remaster", "mono", "stereo")
    private const val TOLERANCE_MS = 8_000L
    private const val TOLERANCE_RATIO = 0.05
}
