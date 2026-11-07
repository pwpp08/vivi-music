package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import java.security.MessageDigest

/**
 * CSV di playlist. Scrive `title,artist,album,duration_ms,isrc`; in lettura riconosce anche le
 * intestazioni degli export piu' comuni (Exportify, TuneMyMusic, Soundiiz, Apple Music via terze parti).
 */
object CsvCodec {
    private val HEADER = listOf("title", "artist", "album", "duration_ms", "isrc")

    private val TITLE_KEYS = setOf("title", "trackname", "name", "track", "song", "songname", "tracktitle", "titolo", "brano")
    private val ARTIST_KEYS = setOf("artist", "artists", "artistname", "artistnames", "artista", "artisti", "performer")
    private val ALBUM_KEYS = setOf("album", "albumname", "albumtitle")
    private val DURATION_KEYS = setOf("durationms", "duration", "length", "time", "durata")
    private val ISRC_KEYS = setOf("isrc")

    fun encode(tracks: List<Track>): String = buildString {
        append(HEADER.joinToString(",")).append("\r\n")
        for (t in tracks) {
            append(listOf(t.title, t.artistLine, t.album, if (t.durationMs > 0) t.durationMs.toString() else "", t.isrc ?: "")
                .joinToString(",") { quote(it) }).append("\r\n")
        }
    }

    fun parse(text: String): List<Track> {
        var rows = parseRows(text.removePrefix("﻿"))
        if (rows.isEmpty()) return emptyList()
        // Shazam's export opens with a one-cell line ("Shazam Library") above the real header.
        if (rows.size > 1 && rows.first().size == 1 && rows[1].size > 1 && rows[1].map { norm(it) }.any { it in TITLE_KEYS }) rows = rows.drop(1)
        val header = rows.first().map { norm(it) }
        fun col(keys: Set<String>) = header.indexOfFirst { it in keys }.takeIf { it >= 0 }
        val iTitle = col(TITLE_KEYS)
        val iArtist = col(ARTIST_KEYS)
        val iAlbum = col(ALBUM_KEYS)
        val iDur = col(DURATION_KEYS)
        val iIsrc = col(ISRC_KEYS)
        // "duration_ms" (ours, Exportify's "Duration (ms)") is milliseconds whatever the size: an 8-second
        // interlude is 8000 ms, not 8000 seconds.
        val inMs = iDur != null && header[iDur].endsWith("ms")
        // Senza intestazione riconoscibile: assumiamo title,artist,album,duration,isrc.
        val hasHeader = iTitle != null
        val body = if (hasHeader) rows.drop(1) else rows
        val out = ArrayList<Track>()
        for (r in body) {
            fun at(i: Int?) = i?.let { r.getOrNull(it) }?.trim() ?: ""
            val title = if (hasHeader) at(iTitle) else r.getOrNull(0)?.trim() ?: ""
            if (title.isEmpty()) continue
            val artist = if (hasHeader) at(iArtist) else r.getOrNull(1)?.trim() ?: ""
            val album = if (hasHeader) at(iAlbum) else r.getOrNull(2)?.trim() ?: ""
            val rawDur = if (hasHeader) at(iDur) else r.getOrNull(3)?.trim() ?: ""
            val dur = if (inMs) rawDur.toDoubleOrNull()?.toLong() ?: parseDuration(rawDur) else parseDuration(rawDur)
            val isrc = (if (hasHeader) at(iIsrc) else r.getOrNull(4)?.trim() ?: "").ifEmpty { null }
            out += withStableId(Track(id = "", title = title, artists = splitArtists(artist), album = album, durationMs = dur, isrc = isrc))
        }
        return out
    }

    fun splitArtists(s: String): List<String> =
        s.split(Regex("""\s*[,;/]\s*|\s+&\s+""")).map { it.trim() }.filter { it.isNotEmpty() }

    fun parseDuration(s: String): Long {
        if (s.isBlank()) return 0
        if (':' in s) {
            val p = s.split(':').map { it.trim().toDoubleOrNull() ?: 0.0 }
            return when (p.size) {
                2 -> ((p[0] * 60 + p[1]) * 1000).toLong()
                3 -> (((p[0] * 60 + p[1]) * 60 + p[2]) * 1000).toLong()
                else -> 0
            }
        }
        if (s.startsWith("P")) return Durations.parseIso8601(s)
        val n = s.toDoubleOrNull() ?: return 0
        return if (n > 10_000) n.toLong() else (n * 1000).toLong() // ms vs secondi
    }

    /** Id stabile derivato da titolo+artista, cosi' le sync ripetute riconoscono lo stesso brano. */
    fun withStableId(t: Track): Track {
        val key = Matcher.normalizeTitle(t.title) + "|" + t.artists.map { Matcher.normalizeArtist(it) }.sorted().joinToString(",")
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
        val id = digest.take(8).joinToString("") { "%02x".format(it) }
        return t.copy(id = id, uri = null, itemId = null)
    }

    fun safeName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().trim('.').take(80).ifBlank { "playlist" }

    private fun norm(h: String) = h.lowercase().replace(Regex("""[^a-z]"""), "")

    private fun quote(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' || it == ';' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /**
     * Parser RFC 4180 (virgolette, campi multilinea). Il separatore (`,`, `;` o TAB) e' dedotto
     * dalla prima riga: il TAB serve per gli export "Esporta playlist" di Apple Music/iTunes.
     */
    fun parseRows(text: String): List<List<String>> {
        // The separator is read off the first lines that have one (a lone title line may come first).
        val firstLine = text.lineSequence().filter { it.isNotBlank() }.take(3).joinToString("\n").ifEmpty { return emptyList() }
        val sep = listOf(',', ';', '\t')
            .map { c -> c to firstLine.count { it == c } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first ?: ','
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            } else when (c) {
                '"' -> inQuotes = true
                sep -> { row.add(field.toString()); field.setLength(0) }
                '\r' -> { }
                '\n' -> { row.add(field.toString()); field.setLength(0); if (row.any { it.isNotBlank() }) rows.add(row); row = ArrayList() }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); if (row.any { it.isNotBlank() }) rows.add(row) }
        return rows
    }
}
