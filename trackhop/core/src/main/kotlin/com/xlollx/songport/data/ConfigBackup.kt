package com.xlollx.songport.data

import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.sync.CsvCodec
import com.xlollx.songport.sync.Wrapped
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Trackhop's whole set-up in one file (`.trackhop`), to move it to another phone or between the
 * app, Docker and Trackhop Desktop, which keep the same data: the syncs, the services added (not
 * signed in), the matches chosen by hand and the tracks ignored, the settings, the File playlists
 * and, if asked, the run history.
 *
 * Never in it: sign-ins and passwords (the token vault), client secrets, the app lock, and what only
 * means something on the device that wrote it (the Android backup folder, caches of failed
 * searches). After an import the services are signed in again, and the syncs carry on where they were.
 */
object ConfigBackup {
    const val FORMAT = "trackhop-config"
    const val VERSION = 1
    const val EXTENSION = "trackhop"

    @Serializable
    data class Snapshot(
        val format: String = FORMAT,
        val version: Int = VERSION,
        /** Who wrote it: "Trackhop 1.3.9 (Android)", "Trackhop server 0.4.0"… for the import's message. */
        val writtenBy: String = "",
        val writtenAt: Long = 0,
        val jobs: List<SyncJob> = emptyList(),
        val reports: List<SyncReport> = emptyList(),
        val matchCache: Map<String, String> = emptyMap(),
        val ignoredTracks: Set<String> = emptySet(),
        val settings: Settings = Settings(),
        /** File playlists: name (without .csv) -> the CSV's text. */
        val files: Map<String, String> = emptyMap(),
        /** The Wrapped history, with the run history only (it is the user's own past, like the runs). */
        val wrapped: List<Wrapped.Saved> = emptyList(),
    )

    data class Summary(val syncs: Int, val matches: Int, val files: Int, val runs: Int, val writtenBy: String, val writtenAt: Long)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    /** The set-up as the file's text. */
    fun export(env: Env, writtenBy: String, history: Boolean = false, now: Long = System.currentTimeMillis()): String {
        val d = env.store.data
        val files = LocalFilesProvider.dir(env).listFiles { f -> f.isFile && f.extension == "csv" }.orEmpty()
            .associate { it.nameWithoutExtension to it.readText() }
        val snapshot = Snapshot(
            writtenBy = writtenBy, writtenAt = now,
            jobs = if (history) d.jobs else d.jobs.map { it.copy(lastReportId = null) },
            reports = if (history) d.reports else emptyList(),
            matchCache = d.matchCache, ignoredTracks = d.ignoredTracks,
            settings = portable(d.settings), files = files,
            wrapped = if (history) d.wrapped else emptyList(),
        )
        return json.encodeToString(Snapshot.serializer(), snapshot)
    }

    /** What a file holds, without changing anything; throws with a readable message if it is not one. */
    fun read(text: String): Snapshot {
        val s = runCatching { json.decodeFromString(Snapshot.serializer(), text.removePrefix("﻿")) }
            .getOrElse { throw IllegalArgumentException("Not a playlist sync configuration file.") }
        require(s.format == FORMAT) { "Not a playlist sync configuration file." }
        require(s.version <= VERSION) { "This file comes from a newer version: update this one first." }
        return s
    }

    fun summary(s: Snapshot) = Summary(s.jobs.size, s.matchCache.size, s.files.size, s.reports.size, s.writtenBy, s.writtenAt)

    /**
     * Brings [s] in: its syncs replace those with the same id and join the others; matches, ignored
     * tracks and services added are merged; its settings replace these except what belongs to this
     * device; its File playlists are added, a name already here kept as it is.
     */
    fun import(env: Env, s: Snapshot): Summary {
        val dir = LocalFilesProvider.dir(env)
        var files = 0
        for ((name, text) in s.files) {
            val safe = CsvCodec.safeName(name)
            val f = File(dir, "$safe.csv")
            if (!f.exists()) { f.writeText(text); files++ }
        }
        env.store.update { d ->
            val ids = s.jobs.map { it.id }.toSet()
            val reportIds = d.reports.map { it.id }.toSet()
            d.copy(
                jobs = d.jobs.filter { it.id !in ids } + s.jobs,
                reports = (d.reports + s.reports.filter { it.id !in reportIds }).sortedByDescending { it.startedEpoch }.take(FileStore.MAX_REPORTS),
                matchCache = d.matchCache + s.matchCache,
                ignoredTracks = d.ignoredTracks + s.ignoredTracks,
                settings = merged(d.settings, s.settings),
                wrapped = s.wrapped.fold(d.wrapped) { h, w -> Wrapped.remember(h, w) },
            )
        }
        // The services added come with it: the connector list follows the new settings at once.
        env.store.data.settings.let { st -> com.xlollx.songport.providers.Providers.configure(st.extraAccounts, st.connectorNames, st.connectors) }
        env.store.flush()
        return Summary(s.jobs.size, s.matchCache.size, files, s.reports.size, s.writtenBy, s.writtenAt)
    }

    /** The settings without what is secret or only means something on this device. */
    private fun portable(st: Settings) = st.copy(
        clientSecrets = emptyMap(), appLock = false, backupFolder = "",
        supportPromptReport = null, appOpens = 0, supportNudgeSeen = 0,
        // Which "What's new" this phone has shown: the phone that imports the file keeps its own.
        whatsNewSeen = "",
    )

    /** [incoming]'s settings over [current], keeping this device's own. */
    private fun merged(current: Settings, incoming: Settings) = incoming.copy(
        clientSecrets = current.clientSecrets,
        appLock = current.appLock,
        backupFolder = current.backupFolder,
        backupSchedule = current.backupSchedule,
        connectors = (current.connectors + incoming.connectors).distinct(),
        connectorNames = current.connectorNames + incoming.connectorNames,
        extraAccounts = (current.extraAccounts.keys + incoming.extraAccounts.keys).associateWith { k ->
            ((current.extraAccounts[k] ?: emptyList()) + (incoming.extraAccounts[k] ?: emptyList())).distinct()
        },
        clientIds = current.clientIds + incoming.clientIds,
        connectorsInit = true,
        onboardingDone = current.onboardingDone || incoming.onboardingDone,
        tourDone = current.tourDone || incoming.tourDone,
        tipsPillDismissed = current.tipsPillDismissed || incoming.tipsPillDismissed,
        supportPromptReport = current.supportPromptReport,
        supportPromptDone = current.supportPromptDone,
        appOpens = current.appOpens,
        supportNudgeSeen = current.supportNudgeSeen,
        whatsNewSeen = current.whatsNewSeen,
    )

    /** The file's name: trackhop-2026-10-30.trackhop. */
    fun fileName(now: Long = System.currentTimeMillis()): String =
        "vivi-sync-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(now)) + ".$EXTENSION"
}
