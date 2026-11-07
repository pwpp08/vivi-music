package com.xlollx.songport.sync

import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When a sync runs by itself. A sync has either an interval (the five old choices, or any number of
 * minutes from an hour to a week) or a time of day ("07:30") with, optionally, the days of the week
 * ("Monday, Wednesday, Friday"). Old syncs carry only [SyncJob.schedule] and keep working as they were.
 */
object Timing {
    /** The steps the slider stops at, in minutes: manual, 1–23 hours, 1–6 days, a week. */
    val STEPS: List<Int> = listOf(0) + (1..23).map { it * 60 } + (1..6).map { it * 1440 } + listOf(10080)

    const val MIN_MINUTES = 60
    const val MAX_MINUTES = 10080

    /** The interval in minutes (0 = manual). */
    fun interval(job: SyncJob): Int =
        job.everyMinutes?.coerceIn(0, MAX_MINUTES)?.let { if (it in 1 until MIN_MINUTES) MIN_MINUTES else it } ?: job.schedule.minutes.toInt()

    /** Runs by itself at all. */
    fun scheduled(job: SyncJob): Boolean = job.enabled && (interval(job) > 0 || job.days.isNotEmpty())

    /** Runs at a time of day (a day or more apart, or on chosen days), so it waits for a clock time. */
    fun timed(job: SyncJob): Boolean = job.days.isNotEmpty() || (job.at != null && interval(job) >= 1440)

    fun parseAt(at: String?): LocalTime? = at?.let { runCatching { LocalTime.parse(it.trim()) }.getOrNull() }

    /**
     * The next run after the last one ([SyncJob.lastRunEpoch]), or null for a manual sync. A sync that
     * never ran is due now when it has an interval, and at its first time of day when it is timed.
     */
    fun nextRun(job: SyncJob, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!scheduled(job)) return null
        val last = job.lastRunEpoch
        if (!timed(job)) return if (last <= 0) now else last + interval(job) * 60_000L
        val time = parseAt(job.at) ?: LocalTime.of(9, 0)
        // From when the next one may come: right away for a sync that never ran; otherwise the interval
        // after the last run, less half a day, so a run that came late does not push the next one a day on.
        val from = if (last <= 0) now else if (job.days.isNotEmpty()) last + 60_000L
            else last + interval(job) * 60_000L - 12 * 3_600_000L
        var day: LocalDate = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        repeat(15) {
            val at: ZonedDateTime = day.atTime(time).atZone(zone)
            val ok = job.days.isEmpty() || day.dayOfWeek.value in job.days
            if (ok && at.toInstant().toEpochMilli() >= from) return at.toInstant().toEpochMilli()
            day = day.plusDays(1)
        }
        return null
    }

    fun isDue(job: SyncJob, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        nextRun(job, now, zone)?.let { now >= it } ?: false

    /** The closest of the old choices, for what still reads [SyncJob.schedule] (the app's old widget, exports). */
    fun legacy(minutes: Int): Schedule = when {
        minutes <= 0 -> Schedule.MANUAL
        minutes < 360 -> Schedule.HOURLY
        minutes < 1440 -> Schedule.EVERY_6H
        minutes < 10080 -> Schedule.DAILY
        else -> Schedule.WEEKLY
    }

    /** [job] with a new timing; [SyncJob.schedule] kept as the closest old choice. */
    fun apply(job: SyncJob, minutes: Int, at: String?, days: List<Int>): SyncJob {
        var m = if (minutes in 1 until MIN_MINUTES) MIN_MINUTES else minutes.coerceIn(0, MAX_MINUTES)
        var d = days.filter { it in 1..7 }.distinct().sorted()
        // Every day of the week is daily.
        if (d.size == 7) { d = emptyList(); m = 1440 }
        return job.copy(
            schedule = if (d.size == 1) Schedule.WEEKLY else if (d.isNotEmpty()) Schedule.DAILY else legacy(m),
            everyMinutes = m,
            at = if (d.isNotEmpty() || m >= 1440) (parseAt(at) ?: LocalTime.of(9, 0)).toString() else null,
            days = d,
        )
    }

    private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** The step's name in English (the web page and the command line): "manual", "every 3 h", "every 2 days", "weekly". */
    fun stepName(minutes: Int): String = when {
        minutes <= 0 -> "manual"
        minutes == 60 -> "hourly"
        minutes < 1440 -> "every ${minutes / 60} h"
        minutes == 1440 -> "daily"
        minutes < 10080 -> "every ${minutes / 1440} days"
        else -> "weekly"
    }

    /** The whole timing in English: "every 3 h", "daily at 07:30", "every Sat at 09:00", "Mon, Wed, Fri at 08:00". */
    fun describe(job: SyncJob): String {
        if (job.days.size == 1) return "every " + DAY_NAMES[(job.days[0] - 1).coerceIn(0, 6)] + " at " + (job.at ?: "09:00")
        if (job.days.isNotEmpty()) return job.days.joinToString(", ") { DAY_NAMES[(it - 1).coerceIn(0, 6)] } + " at " + (job.at ?: "09:00")
        val name = stepName(interval(job))
        return if (timed(job) && job.at != null) "$name at ${job.at}" else name
    }
}
