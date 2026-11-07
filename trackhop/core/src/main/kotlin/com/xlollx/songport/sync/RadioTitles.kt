package com.xlollx.songport.sync

import com.xlollx.songport.model.RadioSource
import com.xlollx.songport.model.Track
import java.time.LocalDateTime

/**
 * What a radio says is on air, as a track: the "StreamTitle" Icecast and Shoutcast send between
 * blocks of audio ("Daft Punk - Digital Love"), with the station's own announcements, adverts and
 * jingles left out. And whether a moment falls in a radio sync's window. Kotlin puro: coperto dai test.
 */
object RadioTitles {

    private val STREAM_TITLE = Regex("""StreamTitle='(.*?)';""", RegexOption.DOT_MATCHES_ALL)

    /** Titles that are the station talking, not a song: adverts, news, jingles, the station's own name. */
    private val NOT_A_SONG = Regex(
        """\b(advert|advertising|commercial|adbreak|ad break|jingle|news|notiziario|giornale radio|pubblicit[aà]|spot|werbung|nachrichten|publicit[eé]|anuncio|traffic|weather|meteo|promo|station id|live show|on air|in onda)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** The StreamTitle inside an ICY metadata block, or null when there is none. */
    fun streamTitle(metadata: String): String? = STREAM_TITLE.find(metadata)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * [title] as a track ("Artist - Title"), or null when it is not a song: no separator, the
     * station's name, a URL, an advert or the news.
     */
    fun track(title: String, station: String = ""): Track? {
        tildeRecord(title)?.let { (song, artist) -> return track("$artist - $song", station) }
        val t = title.trim().replace(Regex("""\s+"""), " ")
        if (t.length < 3 || t.contains("://") || t.contains("www.", ignoreCase = true)) return null
        if (station.isNotBlank() && t.contains(station.trim(), ignoreCase = true) && !t.contains(" - ")) return null
        if (NOT_A_SONG.containsMatchIn(t)) return null
        val sep = listOf(" - ", " – ", " — ", " ~ ").firstOrNull { t.contains(it) } ?: return null
        val artist = t.substringBefore(sep).trim()
        val song = t.substringAfter(sep).trim()
            // "(Radio Edit)" and the like stay; a trailing "[2024]" or "| Station" does not.
            .replace(Regex("""\s*\|.*$"""), "").replace(Regex("""\s*\[\d{4}]$"""), "").trim()
        if (artist.isEmpty() || song.isEmpty() || artist.equals(song, ignoreCase = true)) return null
        if (station.isNotBlank() && (artist.equals(station.trim(), ignoreCase = true) || song.equals(station.trim(), ignoreCase = true))) return null
        return Track(id = "", title = song, artists = artist.split(Regex("""\s*(?:,|&| feat\.? | ft\.? | x )\s*""", RegexOption.IGNORE_CASE)).filter { it.isNotBlank() })
    }

    /**
     * The record some stations send instead of "Artist - Title" (Mediaset's: Virgin Radio, R101,
     * Radio 105): fields joined by "~", title first, then artist, then year, length, times, the
     * station's name and an id ("Sweet Home Alabama~Lynyrd Skynyrd~~1974~~211~…~Virgin Radio Classic Rock~…").
     * Title and artist, or null when [title] is not such a record or names no artist (the station talking).
     */
    private fun tildeRecord(title: String): Pair<String, String>? {
        if (title.count { it == '~' } < 3 || title.contains(" ~ ")) return null
        val f = title.split('~').map { it.trim() }
        val song = f[0]
        val artist = f.getOrElse(1) { "" }
        return (song to artist).takeIf { song.isNotEmpty() && artist.isNotEmpty() }
    }

    /** What is on air, for the card: "Artist - Title" when it is a song, else the title as sent (a record cut to its first field). */
    fun display(title: String, station: String = ""): String =
        track(title, station)?.let { t -> t.artists.joinToString(", ") + " - " + t.title }
            ?: if (title.count { it == '~' } >= 3) title.substringBefore('~').trim().ifEmpty { title } else title

    /** The same song, whatever the case or spacing: to note each one once. */
    fun key(t: Track): String = (t.artists.joinToString(",") + "|" + t.title).lowercase().replace(Regex("""[^\p{L}\p{N}|,]"""), "")

    /** True when [at] falls on one of [r]'s days and inside its daily window. */
    fun inWindow(r: RadioSource, at: LocalDateTime): Boolean {
        val minute = at.hour * 60 + at.minute
        val day = at.dayOfWeek.value
        if (r.startMinute == r.endMinute) return day in r.days
        return if (r.startMinute < r.endMinute) day in r.days && minute >= r.startMinute && minute < r.endMinute
        // Past midnight: the evening part belongs to its day, the small hours to the day before.
        else if (minute >= r.startMinute) day in r.days
        else minute < r.endMinute && ((day + 5) % 7 + 1) in r.days
    }

    /**
     * The first moment after [after] when [r]'s window opens: its start time on one of its days (an
     * all-day window opens at midnight). Null when it has no day. A window already open does not
     * count: this is when the next one begins.
     */
    fun nextStart(r: RadioSource, after: LocalDateTime): LocalDateTime? {
        if (r.days.isEmpty()) return null
        val base = after.toLocalDate()
        for (d in 0..8) {
            val day = base.plusDays(d.toLong())
            val m = r.startMinute.coerceIn(0, 24 * 60 - 1)
            val at = day.atTime(m / 60, m % 60)
            if (at.isAfter(after) && day.dayOfWeek.value in r.days) return at
        }
        return null
    }

    /** "Mon–Fri 08:00–10:00", "Every day, all day"… for the card. */
    fun describe(r: RadioSource): String {
        val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val d = r.days.sorted()
        val days = when {
            d.size == 7 -> "Every day"
            d == listOf(1, 2, 3, 4, 5) -> "Mon–Fri"
            d == listOf(6, 7) -> "Weekends"
            d.isEmpty() -> "No day"
            else -> d.joinToString(", ") { names[it - 1] }
        }
        fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
        return if (r.startMinute == r.endMinute) "$days, all day" else "$days ${hm(r.startMinute)}–${hm(r.endMinute)}"
    }
}
