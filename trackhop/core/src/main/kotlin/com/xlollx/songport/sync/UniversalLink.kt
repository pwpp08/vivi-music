package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The "universal link": a playlist or a track as an address of a static page on trackhop.org
 * (docs/open.html) that lists it and opens or searches each track on the receiver's own service.
 * The tracks travel in the address's fragment (after "#"), which browsers never send to the
 * server: no server keeps anything, the page only decodes what it was handed.
 *
 * Fragment: base64url (no padding) of raw DEFLATE of UTF-8 text, one record per line, fields
 * separated by tabs:
 *   line 0: "1" (format version) · "P" (playlist) or "T" (one track) · total tracks · name
 *   then:   title · artists (", ") · ISRC (may be empty)
 * A long playlist travels in several links ([buildParts]): each one's type is "P:part:parts:first"
 * ("P:2:3:301" is the second of three links, starting at the playlist's 301st song). Readers that
 * predate parts see a playlist that holds fewer songs than its total, which is still right.
 * Tabs and line breaks inside a field become spaces. The page (docs/open.html) reads the same
 * format with DecompressionStream("deflate-raw"); keep the two in step.
 */
object UniversalLink {
    const val BASE = "https://trackhop.org/open"
    private const val VERSION = "1"

    /** At most this many tracks travel in a link, and the fragment stays under [MAX_FRAGMENT] characters. */
    const val MAX_TRACKS = 300

    /** Long addresses get cut by some chat apps; about 6 KB goes through the common ones. */
    const val MAX_FRAGMENT = 6000

    /**
     * Each link of a playlist split in parts stays under this many characters, so that the whole
     * message ("name (1/3): link") fits in one Telegram message (4096 characters at most).
     */
    const val PART_FRAGMENT = 3900

    data class Item(val title: String, val artist: String, val isrc: String? = null)

    /** What a link carries: [total] is how many tracks the playlist had, [items] the ones that fit. */
    data class Shared(
        val name: String, val items: List<Item>, val total: Int, val single: Boolean = false,
        /** Which of the playlist's links this is (1-based), out of [parts]; [first] is its first song's number. */
        val part: Int = 1, val parts: Int = 1, val first: Int = 1,
    ) {
        val capped: Boolean get() = items.size < total
    }

    /** A link ready to share, and how many of the playlist's tracks it holds. */
    data class Built(val url: String, val included: Int, val total: Int, val part: Int = 1, val parts: Int = 1, val first: Int = 1) {
        val capped: Boolean get() = included < total
        /** The number of this link's last song in the playlist. */
        val last: Int get() = first + included - 1
    }

    private fun clean(s: String) = s.replace(Regex("[\\t\\r\\n]+"), " ").trim()

    private fun item(t: Track) = Item(clean(t.title), clean(t.artistLine), t.isrcNorm)

    /** The playlist [name] with its [tracks] (only real tracks: no albums, artists or podcasts) as a link. */
    fun build(name: String, tracks: List<Track>, maxChars: Int = MAX_FRAGMENT): Built {
        val items = tracks.filter { it.kind.isEmpty() && it.title.isNotBlank() }.map(::item)
        var count = minOf(items.size, MAX_TRACKS)
        while (true) {
            val fragment = encode(Shared(clean(name), items.take(count), items.size))
            if (fragment.length <= maxChars || count <= 1) return Built("$BASE#$fragment", count, items.size)
            // Shrink in proportion to the overshoot, a little more to land under the limit in a step or two.
            count = minOf(count - 1, (count * maxChars.toDouble() / fragment.length * 0.97).toInt()).coerceAtLeast(1)
        }
    }

    /**
     * The whole playlist as links, as many as it takes: each holds at most [MAX_TRACKS] songs and
     * stays under [maxChars] characters. A playlist that fits in one link gives one link, as [build].
     */
    fun buildParts(name: String, tracks: List<Track>, maxChars: Int = PART_FRAGMENT): List<Built> {
        val items = tracks.filter { it.kind.isEmpty() && it.title.isNotBlank() }.map(::item)
        val title = clean(name)
        if (items.isEmpty()) return listOf(Built("$BASE#" + encode(Shared(title, emptyList(), 0)), 0, 0))
        // First the cuts, with a part count as wide as it could ever be (the real one is never longer).
        val wide = items.size
        val cuts = mutableListOf<Pair<Int, Int>>()
        var from = 0
        while (from < items.size) {
            var count = minOf(items.size - from, MAX_TRACKS)
            while (true) {
                val probe = Shared(title, items.subList(from, from + count), items.size, part = wide, parts = wide, first = from + 1)
                val length = encode(probe).length
                if (length <= maxChars || count <= 1) break
                count = minOf(count - 1, (count * maxChars.toDouble() / length * 0.97).toInt()).coerceAtLeast(1)
            }
            cuts += from to count
            from += count
        }
        return cuts.mapIndexed { i, (start, count) ->
            val shared = Shared(title, items.subList(start, start + count), items.size, part = i + 1, parts = cuts.size, first = start + 1)
            Built("$BASE#" + encode(shared), count, items.size, i + 1, cuts.size, start + 1)
        }
    }

    /** One track as a link. */
    fun buildTrack(t: Track): Built {
        val fragment = encode(Shared(clean(t.title), listOf(item(t)), 1, single = true))
        return Built("$BASE#$fragment", 1, 1)
    }

    fun encode(s: Shared): String {
        val text = buildString {
            append(VERSION).append('\t').append(if (s.single) "T" else if (s.parts > 1) "P:${s.part}:${s.parts}:${s.first}" else "P").append('\t').append(s.total).append('\t').append(clean(s.name))
            for (i in s.items) append('\n').append(clean(i.title)).append('\t').append(clean(i.artist)).append('\t').append(i.isrc.orEmpty())
        }
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(text.toByteArray(Charsets.UTF_8)); deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    /** A link (or its fragment alone) back to what it carries; null when it is not one of ours. */
    fun parse(link: String): Shared? = runCatching {
        val fragment = link.substringAfter('#', link).trim()
        val bytes = Base64.getUrlDecoder().decode(fragment)
        val inflater = Inflater(true)
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inflater.end()
        val lines = out.toString(Charsets.UTF_8.name()).split('\n')
        val head = lines.first().split('\t', limit = 4)
        if (head.size < 4 || head[0] != VERSION) return null
        val items = lines.drop(1).map { l ->
            val f = l.split('\t')
            Item(f.getOrElse(0) { "" }, f.getOrElse(1) { "" }, f.getOrNull(2)?.takeIf { it.isNotBlank() })
        }
        val kind = head[1].split(':')
        val parts = kind.getOrNull(2)?.toIntOrNull()?.takeIf { it > 1 }
        Shared(
            head[3], items, head[2].toIntOrNull() ?: items.size, single = kind[0] == "T",
            part = if (parts != null) kind[1].toIntOrNull() ?: 1 else 1,
            parts = parts ?: 1,
            first = if (parts != null) kind.getOrNull(3)?.toIntOrNull() ?: 1 else 1,
        )
    }.getOrNull()
}
