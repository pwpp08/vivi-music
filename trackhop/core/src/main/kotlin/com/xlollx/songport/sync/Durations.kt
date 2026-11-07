package com.xlollx.songport.sync

/** Durate ISO 8601 ("PT3M25S", "PT1H2M") in millisecondi. */
object Durations {
    /** Istante ISO-8601 ("2021-03-04T12:00:00Z", anche con frazioni) o data sola, in epoch ms; 0 se non leggibile. */
    fun parseInstant(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        return runCatching { java.time.Instant.parse(s.trim()).toEpochMilli() }
            .recoverCatching { java.time.LocalDate.parse(s.trim().take(10)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }
            .getOrDefault(0)
    }

    /** L'anno da una data "yyyy…" (0 se manca). */
    fun year(s: String?): Int = s?.trim()?.take(4)?.toIntOrNull()?.takeIf { it in 1000..2999 } ?: 0

    private val ISO = Regex("""^P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$""")

    fun parseIso8601(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        val m = ISO.matchEntire(s.trim()) ?: return 0
        val d = m.groupValues[1].toLongOrNull() ?: 0
        val h = m.groupValues[2].toLongOrNull() ?: 0
        val min = m.groupValues[3].toLongOrNull() ?: 0
        val sec = m.groupValues[4].toDoubleOrNull() ?: 0.0
        return (((d * 24 + h) * 60 + min) * 60 * 1000) + (sec * 1000).toLong()
    }

    fun format(ms: Long): String {
        if (ms <= 0) return ""
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }
}
