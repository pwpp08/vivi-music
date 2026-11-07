package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import java.text.Collator
import java.util.Locale
import kotlin.random.Random

/**
 * Trasformazioni di una lista di brani per gli strumenti "nuova playlist da…": copia, unione,
 * divisione, ordinamento, mescolamento. Il risultato va sempre in una playlist NUOVA sullo stesso
 * servizio, quindi gli id restano validi e l'originale non si tocca. Kotlin puro, coperto dai test.
 */
object PlaylistOps {

    enum class SortKey { ARTIST, TITLE, ALBUM, YEAR, ADDED, DURATION, REVERSE }

    /** Unisce piu' liste nell'ordine dato; con [dropDuplicates] tiene solo la prima copia di ogni brano. */
    fun merge(lists: List<List<Track>>, dropDuplicates: Boolean): List<Track> {
        val all = lists.flatten()
        if (!dropDuplicates) return all
        // Per identita': due copie identiche dello stesso brano sono comunque due elementi distinti.
        val extras = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Track, Boolean>())
        Duplicates.groups(all).forEach { extras.addAll(it.drop(1)) }
        return all.filter { it !in extras }
    }

    /** Parti consecutive di al massimo [size] brani (l'ultima puo' essere piu' corta). */
    fun split(tracks: List<Track>, size: Int): List<List<Track>> = tracks.chunked(size.coerceAtLeast(1))

    fun sort(tracks: List<Track>, key: SortKey, locale: Locale = Locale.getDefault()): List<Track> {
        val c = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        fun cmp(a: String, b: String) = c.compare(a, b)
        val byArtist = Comparator<Track> { a, b -> cmp(a.artists.firstOrNull().orEmpty(), b.artists.firstOrNull().orEmpty()) }
        val byAlbum = Comparator<Track> { a, b -> cmp(a.album, b.album) }
        val byTitle = Comparator<Track> { a, b -> cmp(a.title, b.title) }
        return when (key) {
            SortKey.ARTIST -> tracks.sortedWith(byArtist.then(byAlbum).then(byTitle))
            SortKey.TITLE -> tracks.sortedWith(byTitle.then(byArtist))
            SortKey.ALBUM -> tracks.sortedWith(byAlbum.then(byArtist))
            // Unknown values (0) sink to the end rather than pretending to be the oldest or shortest.
            SortKey.YEAR -> tracks.sortedWith(compareBy<Track> { it.year == 0 }.thenBy { it.year }.then(byArtist).then(byAlbum))
            SortKey.ADDED -> tracks.sortedWith(compareBy<Track> { it.addedAt == 0L }.thenBy { it.addedAt })
            SortKey.DURATION -> tracks.sortedWith(compareBy<Track> { it.durationMs == 0L }.thenBy { it.durationMs })
            SortKey.REVERSE -> tracks.asReversed().toList()
        }
    }

    /**
     * Mescola evitando, dove possibile, due brani dello stesso artista uno dopo l'altro: un
     * mescolamento "puro" li avvicina spesso, e a orecchio sembra poco casuale.
     */
    fun shuffle(tracks: List<Track>, random: Random = Random.Default): List<Track> {
        val pool = tracks.shuffled(random).toMutableList()
        val out = ArrayList<Track>(pool.size)
        while (pool.isNotEmpty()) {
            val last = out.lastOrNull()?.artists?.firstOrNull()?.lowercase()
            val i = pool.indexOfFirst { it.artists.firstOrNull()?.lowercase() != last }.takeIf { it >= 0 } ?: 0
            out += pool.removeAt(i)
        }
        return out
    }
}
