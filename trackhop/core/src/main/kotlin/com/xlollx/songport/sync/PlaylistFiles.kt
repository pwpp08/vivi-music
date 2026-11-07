package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Import/export di playlist come file, in tutti i formati che i servizi musicali (o i loro
 * esportatori) producono davvero. E' il ponte per i servizi senza API aperta — Amazon Music,
 * Qobuz, SoundCloud — e per i backup: qualunque cosa si riesca a esportare da li' entra qui.
 *
 * Formati riconosciuti in lettura:
 *  - **CSV / TSV / CSV con `;`** (Exportify, TuneMyMusic, Soundiiz, "Esporta playlist" di Apple Music)
 *  - **M3U / M3U8** (la maggior parte dei player desktop e Android)
 *  - **XML plist** (Apple Music / iTunes → File › Libreria › Esporta libreria)
 *  - **JSON** (export di Amazon "Request My Data", Soundiiz, backup vari)
 *  - **testo semplice**, una riga per brano: "Artista - Titolo"
 *
 * In scrittura: CSV (completo, con ISRC) e M3U (per i player).
 * Kotlin puro, niente Android: coperto dai test.
 */
object PlaylistFiles {

    enum class Format { CSV, M3U, PLS, ITUNES_XML, XSPF, OPML, JSON, TEXT }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val TITLE_KEYS = listOf("title", "trackname", "name", "track", "song", "songname", "tracktitle", "titolo", "brano")
    private val ARTIST_KEYS = listOf("artist", "artists", "artistname", "artistnames", "albumartist", "artista", "artisti", "performer", "creator")
    private val ALBUM_KEYS = listOf("album", "albumname", "albumtitle")
    private val DURATION_KEYS = listOf("durationms", "duration", "length", "time", "totaltime", "durata")
    private val ISRC_KEYS = listOf("isrc")

    fun detect(fileName: String, text: String): Format {
        val name = fileName.lowercase()
        val head = text.take(4000).trimStart().removePrefix("﻿")
        return when {
            head.startsWith("#EXTM3U") || name.endsWith(".m3u") || name.endsWith(".m3u8") -> Format.M3U
            name.endsWith(".pls") || head.startsWith("[playlist]", ignoreCase = true) -> Format.PLS
            name.endsWith(".xspf") || (head.startsWith("<") && head.contains("xspf.org")) -> Format.XSPF
            name.endsWith(".opml") || (head.startsWith("<") && head.contains("<opml", ignoreCase = true)) -> Format.OPML
            head.contains("<plist") || (name.endsWith(".xml") && head.startsWith("<")) -> Format.ITUNES_XML
            head.startsWith("{") || head.startsWith("[") || name.endsWith(".json") -> Format.JSON
            // Shazam's export opens with a title line above the header: look at the first few lines.
            name.endsWith(".csv") || name.endsWith(".tsv") ||
                text.lineSequence().filter { it.isNotBlank() }.take(3).any { l -> l.any { it == ',' || it == ';' || it == '\t' } } -> Format.CSV
            else -> Format.TEXT
        }
    }

    /**
     * A picked file's bytes as text: UTF-8 (a BOM is dropped), UTF-16 with a BOM, and otherwise
     * Windows-1252, what Windows players write in a plain .m3u (where "Beyoncé" is not valid UTF-8).
     */
    fun decode(bytes: ByteArray): String {
        fun starts(vararg b: Int) = bytes.size >= b.size && b.indices.all { bytes[it] == b[it].toByte() }
        return when {
            starts(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            starts(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            starts(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> try {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                String(bytes, charset("windows-1252"))
            }
        }
    }

    fun parse(fileName: String, text: String): List<Track> = parseClean(fileName, text.removePrefix("\uFEFF"))

    private fun parseClean(fileName: String, text: String): List<Track> = when (detect(fileName, text)) {
        Format.M3U -> parseM3u(text)
        Format.PLS -> parsePls(text)
        Format.ITUNES_XML -> parseItunesXml(text)
        Format.XSPF -> parseXspf(text)
        Format.OPML -> parseOpml(text)
        Format.JSON -> parseJson(text)
        Format.CSV -> CsvCodec.parse(text)
        Format.TEXT -> parseText(text)
    }

    // ---------------------------------------------------------------- M3U

    private val EXTINF = Regex("""^#EXTINF:\s*(-?\d+(?:\.\d+)?)?\s*(?:[^,]*)?,\s*(.*)$""")

    fun parseM3u(text: String): List<Track> {
        val out = ArrayList<Track>()
        var pendingSeconds = 0L
        var pendingLabel: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                val m = EXTINF.find(line) ?: continue
                pendingSeconds = m.groupValues[1].toDoubleOrNull()?.takeIf { it > 0 }?.toLong() ?: 0
                pendingLabel = m.groupValues[2].trim().ifBlank { null }
                continue
            }
            val t = pendingLabel?.let { label -> splitArtistTitle(label).let { (artist, title) -> Track(id = "", title = title, artists = artist) } }
                ?: fromPath(line)
            if (t.title.isNotBlank()) out += CsvCodec.withStableId(t.copy(durationMs = pendingSeconds * 1000))
            pendingSeconds = 0
            pendingLabel = null
        }
        return out
    }

    // ---------------------------------------------------------------- PLS

    private val PLS_ENTRY = Regex("""^(file|title|length)(\d+)\s*=\s*(.*)$""", RegexOption.IGNORE_CASE)

    /** PLS (Winamp, VLC, Clementine): File1=…, Title1=Artist - Title, Length1=seconds. */
    fun parsePls(text: String): List<Track> {
        val entries = java.util.TreeMap<Int, MutableMap<String, String>>()
        for (raw in text.lineSequence()) {
            val m = PLS_ENTRY.find(raw.trim()) ?: continue
            entries.getOrPut(m.groupValues[2].toInt()) { HashMap() }[m.groupValues[1].lowercase()] = m.groupValues[3].trim()
        }
        return entries.values.mapNotNull { e ->
            val t = e["title"]?.takeIf { it.isNotBlank() }?.let { label -> splitArtistTitle(label).let { (artist, title) -> Track(id = "", title = title, artists = artist) } }
                ?: e["file"]?.let { fromPath(it) } ?: return@mapNotNull null
            val secs = e["length"]?.toLongOrNull()?.takeIf { it > 0 } ?: 0
            t.takeIf { it.title.isNotBlank() }?.let { CsvCodec.withStableId(it.copy(durationMs = secs * 1000)) }
        }
    }

    /** Folder names that are a music root, not an artist or an album. */
    private val ROOT_FOLDERS = setOf(
        "music", "my music", "musik", "musique", "musica", "música", "muziek", "mp3", "audio", "songs", "download", "downloads",
        "storage", "emulated", "sdcard", "media", "mnt", "home", "users", "volumes", "various artists", "various", "va", "compilations",
    )
    /** What comes before the folders: "http://host", "file://", "file:", "C:". */
    private val URL_OR_DRIVE = Regex("""^(?:[A-Za-z][\w+.-]*://[^/]*|file:|[A-Za-z]:(?=/))""")
    // The track number a file name starts with: "01 - ", "01. ", "1-01-" always; "01 " before
    // "Artist - Title" only when zero-padded ("50 Cent - …" is an artist); "12 " before a bare title.
    private val NUMBER_DASH = Regex("""^(?:\d{1,2}[-.])?\d{1,3}\s*(?:[-.)]\s+|-(?!\d))(?=.*\p{L})""")
    private val PADDED_NUMBER = Regex("""^(?:\d{1,2}[-.])?0\d\s+(?=.*\p{L})""")
    private val NUMBER = Regex("""^(?:\d{1,2}[-.])?\d{1,3}\s+(?=.*\p{L})""")

    private fun withoutTrackNumber(name: String): String = when {
        NUMBER_DASH.containsMatchIn(name) -> NUMBER_DASH.replace(name, "")
        DASH.containsMatchIn(name) -> PADDED_NUMBER.replace(name, "")
        else -> NUMBER.replace(name, "")
    }.trim()

    /**
     * A track from a path without #EXTINF: "Artist - Title.mp3" as named, or the usual library layout
     * Artist/Album/01 Title.mp3, relative or absolute, a file: URI or percent-encoded.
     */
    fun fromPath(path: String): Track {
        val p = (if (path.contains("://") || path.startsWith("file:")) runCatching { java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path) else path)
            .replace('\\', '/').replace(URL_OR_DRIVE, "")
        val parts = p.split('/').filter { it.isNotBlank() }
        val name = withoutTrackNumber(parts.lastOrNull().orEmpty().substringBeforeLast('.').replace('_', ' ').trim())
        val (artists, title) = splitArtistTitle(name)
        if (artists.isNotEmpty() || parts.size < 3) return Track(id = "", title = title, artists = artists)
        val album = parts[parts.size - 2]
        val artist = parts[parts.size - 3]
        val generic = { f: String -> f.lowercase() in ROOT_FOLDERS || f.all { it.isDigit() || it == ':' } }
        if (generic(album) || generic(artist)) return Track(id = "", title = title)
        return Track(id = "", title = title, artists = listOf(artist), album = album)
    }

    fun toM3u(tracks: List<Track>): String = buildString {
        append("#EXTM3U\r\n")
        for (t in tracks) {
            val secs = if (t.durationMs > 0) t.durationMs / 1000 else -1
            append("#EXTINF:").append(secs).append(',')
            if (t.artists.isNotEmpty()) append(t.artistLine).append(" - ")
            append(t.title).append("\r\n")
            // Nessun file locale: lasciamo una riga di ricerca, i player la ignorano o la mostrano.
            append(t.artistLine).append(if (t.artists.isEmpty()) "" else " - ").append(t.title).append("\r\n")
        }
    }

    /** Testo semplice, una riga "Artista - Titolo" per brano: si incolla ovunque, anche in una chat. */
    fun toText(tracks: List<Track>): String = tracks.joinToString("\n", postfix = "\n") { t ->
        if (t.artists.isEmpty()) t.title else t.artistLine + " - " + t.title
    }

    /** JSPF (XSPF in JSON), il formato di ListenBrainz: creator/title/album/duration e l'ISRC fra gli identificatori. */
    fun toJspf(name: String, tracks: List<Track>): String {
        val list = tracks.map { t ->
            buildJsonObject {
                put("title", t.title)
                if (t.artists.isNotEmpty()) put("creator", t.artistLine)
                if (t.album.isNotBlank()) put("album", t.album)
                if (t.durationMs > 0) put("duration", t.durationMs)
                t.isrcNorm?.let { put("identifier", JsonArray(listOf(JsonPrimitive("isrc:$it")))) }
            }
        }
        val root = buildJsonObject { put("playlist", buildJsonObject { put("title", name); put("track", JsonArray(list)) }) }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root) + "\n"
    }

    /** XSPF, la playlist XML aperta che leggono VLC, Strawberry, Clementine e altri. */
    fun toXspf(name: String, tracks: List<Track>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<playlist version=\"1\" xmlns=\"http://xspf.org/ns/0/\">\n")
        append("  <title>").append(escapeXml(name)).append("</title>\n  <trackList>\n")
        for (t in tracks) {
            append("    <track>")
            append("<title>").append(escapeXml(t.title)).append("</title>")
            if (t.artists.isNotEmpty()) append("<creator>").append(escapeXml(t.artistLine)).append("</creator>")
            if (t.album.isNotBlank()) append("<album>").append(escapeXml(t.album)).append("</album>")
            if (t.durationMs > 0) append("<duration>").append(t.durationMs).append("</duration>")
            t.isrcNorm?.let { append("<identifier>isrc:").append(escapeXml(it)).append("</identifier>") }
            append("</track>\n")
        }
        append("  </trackList>\n</playlist>\n")
    }

    private val OPML_OUTLINE = Regex("""<outline\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val OPML_ATTR = Regex("""(\w+)\s*=\s*"([^"]*)"""")

    /** OPML delle iscrizioni ai podcast (Pocket Casts, AntennaPod, Apple Podcasts…): un show per outline con feed. */
    fun parseOpml(text: String): List<Track> = OPML_OUTLINE.findAll(text).mapNotNull { m ->
        val attrs = OPML_ATTR.findAll(m.groupValues[1]).associate { it.groupValues[1].lowercase() to unescapeXml(it.groupValues[2]) }
        // Group headers are outlines too; only the ones pointing at a feed are shows.
        if (attrs["xmlurl"].isNullOrBlank()) return@mapNotNull null
        val title = (attrs["text"] ?: attrs["title"])?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        CsvCodec.withStableId(Track(id = "", title = title, kind = Track.KIND_PODCAST))
    }.toList()

    private val XSPF_TRACK = Regex("""<track>([\s\S]*?)</track>""")
    private fun xspfField(body: String, tag: String): String? =
        Regex("""<$tag>([\s\S]*?)</$tag>""").find(body)?.groupValues?.get(1)?.let { unescapeXml(it).trim() }?.takeIf { it.isNotEmpty() }

    fun parseXspf(text: String): List<Track> = XSPF_TRACK.findAll(text).mapNotNull { m ->
        val body = m.groupValues[1]
        val title = xspfField(body, "title") ?: return@mapNotNull null
        val isrc = Regex("""<identifier>\s*isrc:([A-Za-z0-9]+)\s*</identifier>""").find(body)?.groupValues?.get(1)
        CsvCodec.withStableId(
            Track(
                id = "",
                title = title,
                artists = CsvCodec.splitArtists(xspfField(body, "creator").orEmpty()),
                album = xspfField(body, "album").orEmpty(),
                durationMs = xspfField(body, "duration")?.toLongOrNull() ?: 0,
                isrc = isrc,
            )
        )
    }.toList()

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // ---------------------------------------------------------------- iTunes / Apple Music XML

    private val DICT_BLOCK = Regex("""<dict>((?:(?!<dict>|</dict>)[\s\S])*)</dict>""")
    private val STRING_ENTRY = Regex("""<key>([^<]+)</key>\s*<(string|integer|date|real)>([\s\S]*?)</\2>""")

    fun parseItunesXml(text: String): List<Track> {
        val out = ArrayList<Track>()
        for (block in DICT_BLOCK.findAll(text)) {
            val body = block.groupValues[1]
            val fields = HashMap<String, String>()
            for (e in STRING_ENTRY.findAll(body)) {
                fields[e.groupValues[1].lowercase().replace(" ", "")] = unescapeXml(e.groupValues[3])
            }
            // Solo i dizionari dei brani (hanno "Track ID"); gli altri sono playlist o metadati.
            if (!fields.containsKey("trackid")) continue
            val title = fields["name"]?.trim().orEmpty()
            if (title.isEmpty()) continue
            out += CsvCodec.withStableId(
                Track(
                    id = "",
                    title = title,
                    artists = CsvCodec.splitArtists(fields["artist"] ?: fields["albumartist"] ?: ""),
                    album = fields["album"].orEmpty(),
                    durationMs = fields["totaltime"]?.toLongOrNull() ?: 0,
                )
            )
        }
        return out
    }

    private fun unescapeXml(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&#38;", "&").replace("&amp;", "&")

    // ---------------------------------------------------------------- JSON

    fun parseJson(text: String): List<Track> {
        val root = try { json.parseToJsonElement(text) } catch (e: Exception) { return salvageJson(text) }
        val best = bestTrackArray(root) ?: return emptyList()
        return tracksOf(best)
    }

    /**
     * A JSON array that does not parse as a whole (cut off by a token limit, a trailing comma, a stray
     * comment): every object in it that parses on its own still counts.
     */
    private fun salvageJson(text: String): List<Track> {
        val objects = Regex("""\{[^{}]*\}""").findAll(text).mapNotNull { m ->
            try { json.parseToJsonElement(m.value) as? JsonObject } catch (e: Exception) { null }
        }.toList()
        return tracksOf(objects)
    }

    private fun tracksOf(best: Iterable<JsonElement>): List<Track> {
        val out = ArrayList<Track>()
        for (el in best) {
            val o = el as? JsonObject ?: continue
            val title = pick(o, TITLE_KEYS)?.trim().orEmpty()
            if (title.isEmpty()) continue
            out += CsvCodec.withStableId(
                Track(
                    id = "",
                    title = title,
                    artists = CsvCodec.splitArtists(pick(o, ARTIST_KEYS).orEmpty()),
                    album = pick(o, ALBUM_KEYS).orEmpty(),
                    durationMs = CsvCodec.parseDuration(pick(o, DURATION_KEYS).orEmpty()),
                    isrc = pick(o, ISRC_KEYS)?.takeIf { it.isNotBlank() },
                )
            )
        }
        return out
    }

    /** L'array di oggetti piu' lungo che sembra una lista di brani (ha una chiave "titolo"). */
    private fun bestTrackArray(root: JsonElement): JsonArray? {
        var best: JsonArray? = null
        fun visit(e: JsonElement) {
            when (e) {
                is JsonArray -> {
                    val objects = e.filterIsInstance<JsonObject>()
                    if (objects.isNotEmpty() && objects.any { pick(it, TITLE_KEYS) != null } &&
                        (best == null || objects.size > best!!.size)
                    ) best = e
                    e.forEach { visit(it) }
                }
                is JsonObject -> e.values.forEach { visit(it) }
                else -> {}
            }
        }
        visit(root)
        return best
    }

    /** Valore della prima chiave riconosciuta (confronto senza maiuscole/spazi/underscore). */
    private fun pick(o: JsonObject, keys: List<String>): String? {
        val normalized = o.entries.associate { (k, v) -> k.lowercase().replace(Regex("""[^a-z]"""), "") to v }
        for (k in keys) {
            val v = normalized[k] ?: continue
            val s = flatten(v)
            if (!s.isNullOrBlank()) return s
        }
        return null
    }

    /** Un campo puo' essere stringa, numero, lista di stringhe o lista di oggetti {name: …}. */
    private fun flatten(v: JsonElement): String? = when (v) {
        is JsonPrimitive -> v.content.takeIf { it != "null" }
        is JsonArray -> v.mapNotNull { flatten(it) }.filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
        is JsonObject -> (v["name"] ?: v["title"] ?: v["artistName"])?.let { flatten(it) }
    }

    // ---------------------------------------------------------------- testo semplice

    private val LIST_PREFIX = Regex("""^\s*\d+\s*[.)\-]\s+""")

    fun parseText(text: String): List<Track> {
        val out = ArrayList<Track>()
        for (raw in text.lineSequence()) {
            val line = LIST_PREFIX.replace(raw.trim(), "").trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            // JSON punctuation or a JSON object is not a track name.
            if (line.first() in "[]{}" || line == "```" || line.startsWith("```")) continue
            val (artists, title) = splitArtistTitle(line)
            if (title.isNotBlank()) out += CsvCodec.withStableId(Track(id = "", title = title, artists = artists))
        }
        return out
    }

    private val DASH = Regex("""\s+[-–—]\s+""")

    /** "Artista - Titolo" (il formato di gran lunga piu' diffuso negli export testuali). */
    fun splitArtistTitle(label: String): Pair<List<String>, String> {
        val parts = DASH.split(label.trim(), 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            CsvCodec.splitArtists(parts[0]) to parts[1].trim()
        } else {
            emptyList<String>() to label.trim()
        }
    }

    private fun firstLine(text: String): String? = text.lineSequence().firstOrNull { it.isNotBlank() }
}
