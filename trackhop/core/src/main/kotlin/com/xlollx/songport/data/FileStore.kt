package com.xlollx.songport.data

import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.net.json
import com.xlollx.songport.sync.Wrapped
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Serializable
data class Settings(
    val notifyOnSync: Boolean = true,
    /** Strumenti per chi sviluppa o segnala: log delle connessioni, rapporto tecnico, cattura traffico. */
    val developerOptions: Boolean = false,
    /** Commands from automation apps (Tasker, MacroDroid…) and the end-of-sync event: off until turned on. */
    val automation: Boolean = false,
    /** The traffic captures (a development aid that records what is done on a site): shown only after 7 taps on the version. */
    val captureUnlocked: Boolean = false,
    /** Chiedere al catalogo Deezer l'ISRC dei brani che non lo portano (vedi IsrcOracle). */
    val isrcLookup: Boolean = true,
    /** Client ID inseriti dall'utente, per servizio (vuoto = usa quello della build). */
    val clientIds: Map<String, String> = emptyMap(),
    /**
     * Client secret, solo per i flussi "installed app" che lo richiedono (Google).
     * Non e' un vero segreto: Google stesso dichiara che nelle app installate non e' confidenziale,
     * ed e' l'utente a incollare il proprio. Resta comunque sul dispositivo.
     */
    val clientSecrets: Map<String, String> = emptyMap(),
    val deezerRedirectUrl: String = "",
    /** Account aggiuntivi per servizio: "spotify" -> ["2", "3"]. */
    val extraAccounts: Map<String, List<String>> = emptyMap(),
    /** Connectors shown in Accounts, in order: provider ids ("spotify", "spotify@2", "csv"). */
    val connectors: List<String> = emptyList(),
    /** User-given connector names, by provider id. */
    val connectorNames: Map<String, String> = emptyMap(),
    /** True once existing connections have been turned into connectors (one-time migration). */
    val connectorsInit: Boolean = false,
    /** Richiedi impronta/PIN all'apertura dell'app. */
    val appLock: Boolean = false,
    /** Schermata di benvenuto gia' vista. */
    val onboardingDone: Boolean = false,
    /** Cartella (URI ad albero del selettore di sistema) dove copiare i backup; vuoto = nessuna. */
    val backupFolder: String = "",
    /** Ogni quanto copiare i backup nella cartella; MANUAL = solo a richiesta. */
    val backupSchedule: com.xlollx.songport.model.Schedule = com.xlollx.songport.model.Schedule.MANUAL,
    /** Once a week, look through the playlists for tracks the services no longer play, and say so; never change anything. */
    val unplayableCheck: Boolean = false,
    /** Sync che ha fatto scattare la richiesta di contributo, finche' l'utente non risponde. */
    val supportPromptReport: String? = null,
    /** Richiesta di contributo gia' mostrata e chiusa: non torna piu'. */
    val supportPromptDone: Boolean = false,
    /** Times the app was opened (one per process start): every tenth open shows the coffee card once. */
    val appOpens: Int = 0,
    /** The open count at which the coffee card was last answered, so it is not shown twice in the same open. */
    val supportNudgeSeen: Int = 0,
    /** Where a shared track link went last (provider id): the sheet opens on it next time. */
    val shareTarget: String = "",
    /** Light or dark: "SYSTEM" (follow the phone), "LIGHT" or "DARK". */
    val themeMode: String = "SYSTEM",
    /** Material You: the phone's own colours instead of the brand palette (Android 12+). */
    val dynamicColor: Boolean = false,
    /** The refreshed sync cards (big numbers, run history): on unless turned off in Appearance. */
    val newLook: Boolean = true,
    /** The guided first sync was finished or skipped: it is no longer offered on the empty Syncs tab. */
    val tourDone: Boolean = false,
    /**
     * The newest release whose "What's new" this device has seen ("1.4"), so it opens once per
     * release with one; "" = none yet (an update from before the sheet, or a fresh install still in
     * the welcome, which marks it when it ends). Per device: a configuration file leaves it out.
     */
    val whatsNewSeen: String = "",
    /** Connectors (provider ids) the user left out of the Wrapped: the chips start from this choice next time. */
    val wrappedSkipped: Set<String> = emptySet(),
    /** The "Did you know?" pill on Syncs (widgets, shortcuts, automation) was closed or opened: not shown again. */
    val tipsPillDismissed: Boolean = false,
    /** The Roast's last level ([com.xlollx.songport.ai.AiRoast.Level] by name); blank = the default. */
    val roastLevel: String = "",
    /** The user confirmed once being 18 or older, for the Roast's explicit language. */
    val roastAdultConfirmed: Boolean = false,
)

/** Unita' di quota consumate in un giorno (chiave = data nel fuso del servizio). */
@Serializable
data class QuotaDay(val day: String, val units: Long)

@Serializable
data class StoreData(
    val jobs: List<SyncJob> = emptyList(),
    /** Ultimi report, dal piu' recente. */
    val reports: List<SyncReport> = emptyList(),
    /** Cache abbinamenti: "srcProvider:trackId>dstProvider" -> id brano sulla destinazione. */
    val matchCache: Map<String, String> = emptyMap(),
    /** Ricerche senza esito, stessa chiave -> quando (epoch ms): non si ripetono per una settimana. */
    val missCache: Map<String, Long> = emptyMap(),
    /**
     * Tracks a service said a playlist holds although its listing leaves them out (Amazon Music
     * answering DUPLICATE_TRACK), key [FileStore.presentKey] -> when (epoch ms): present, not to be
     * added again on every run.
     */
    val presentUnlisted: Map<String, Long> = emptyMap(),
    /**
     * Target entries a Mirror sync removed and found again on its next run (Amazon Music answering
     * the removal without taking the entry away), same keys as [presentUnlisted] -> when (epoch ms):
     * left alone instead of removed again on every run, until the memory expires.
     */
    val keptOnRemoval: Map<String, Long> = emptyMap(),
    /** What each sync added to and removed from its target in its last run, by job id: the next run checks it. */
    val lastWrites: Map<String, LastWrites> = emptyMap(),
    /** Source tracks the user declared absent from a destination service (same keys as the caches). */
    val ignoredTracks: Set<String> = emptySet(),
    val settings: Settings = Settings(),
    /** Quota API stimata per servizio (oggi YouTube). */
    val quota: Map<String, QuotaDay> = emptyMap(),
    /** Large removals the user confirmed in a preview, by job id: one use, by the next run started by hand. */
    val removalConfirmations: Map<String, RemovalConfirmation> = emptyMap(),
    /** Syncs whose much smaller target the user agreed, in the preview, to sync anyway, and when. */
    val shrinkConfirmations: Map<String, Long> = emptyMap(),
    /** The Wrapped made, the newest first (figures only, no tracks): at most [Wrapped.MAX_HISTORY]. */
    val wrapped: List<Wrapped.Saved> = emptyList(),
)

/**
 * The target ids one run of a sync added and removed on [targetId]. The next run compares them with
 * the target's listing: an id added a moment ago that the listing still leaves out is on the target
 * but unlisted (YouTube's Liked music keeps out a liked video), an id removed a moment ago that is
 * still listed is one the service does not let go.
 */
@Serializable
data class LastWrites(val targetId: String, val added: Set<String> = emptySet(), val removed: Set<String> = emptySet(), val at: Long = 0)

/** The target track ids the user saw in the preview and agreed to remove, and when. */
@Serializable
data class RemovalConfirmation(val trackIds: Set<String>, val at: Long)

/**
 * Stato persistente dell'app: un unico file JSON (piccolo), esposto come StateFlow alla UI.
 * Le scritture sono serializzate su un thread dedicato e atomiche (tmp + rename).
 */
open class FileStore(dir: File) {
    private val file = File(dir, "store.json")
    private val tmp = File(dir, "store.json.tmp")
    private val writer = Executors.newSingleThreadScheduledExecutor()
    private var pendingWrite: java.util.concurrent.ScheduledFuture<*>? = null
    private val lock = Any()

    private val _state = MutableStateFlow(load().also { Providers.configure(it.settings.extraAccounts, it.settings.connectorNames, it.settings.connectors) })
    val state: StateFlow<StoreData> get() = _state
    val data: StoreData get() = _state.value

    private fun load(): StoreData = try {
        if (file.exists()) json.decodeFromString<StoreData>(file.readText()) else StoreData()
    } catch (e: Exception) {
        StoreData()
    }

    fun update(fn: (StoreData) -> StoreData) {
        val next: StoreData
        synchronized(lock) {
            next = fn(_state.value)
            _state.value = next
        }
        Providers.configure(next.settings.extraAccounts, next.settings.connectorNames, next.settings.connectors)
        scheduleWrite()
    }

    /**
     * Le scritture si accumulano per mezzo secondo e poi si scrive l'ultimo stato: digitare nelle
     * impostazioni o aggiornare la cache brano per brano non riscrive il file a ogni tocco.
     */
    private fun scheduleWrite() {
        synchronized(lock) {
            pendingWrite?.cancel(false)
            pendingWrite = writer.schedule({ writeNow() }, WRITE_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun writeNow() {
        val snapshot = _state.value
        try {
            tmp.writeText(json.encodeToString(snapshot))
            if (!tmp.renameTo(file)) { file.writeText(json.encodeToString(snapshot)); tmp.delete() }
        } catch (_: Exception) { }
    }

    /** Scrive subito (es. prima che il processo venga fermato). */
    fun flush() {
        synchronized(lock) { pendingWrite?.cancel(false); pendingWrite = null }
        writeNow()
    }

    // --- helper di comodo ---

    fun job(id: String): SyncJob? = data.jobs.firstOrNull { it.id == id }

    fun upsertJob(job: SyncJob) = update { d ->
        if (d.jobs.any { it.id == job.id }) d.copy(jobs = d.jobs.map { if (it.id == job.id) job else it })
        else d.copy(jobs = d.jobs + job)
    }

    /**
     * Puts a sync at [toIndex] in the list (clamped): the order of the Syncs list, Sync all and the
     * widgets. The other syncs keep their order.
     */
    fun moveJob(id: String, toIndex: Int) = update { d ->
        val job = d.jobs.firstOrNull { it.id == id } ?: return@update d
        val rest = d.jobs.filter { it.id != id }
        val at = toIndex.coerceIn(0, rest.size)
        d.copy(jobs = rest.take(at) + job + rest.drop(at))
    }

    fun deleteJob(id: String) = update { d -> d.copy(jobs = d.jobs.filter { it.id != id }, removalConfirmations = d.removalConfirmations - id, shrinkConfirmations = d.shrinkConfirmations - id) }

    /** The user saw, in the preview, that the target of [jobId] lists far fewer tracks than before, and runs it anyway. */
    fun confirmTargetShrink(jobId: String) = update { d -> d.copy(shrinkConfirmations = d.shrinkConfirmations + (jobId to System.currentTimeMillis())) }

    /** True once after [confirmTargetShrink] (within the confirmation's lifetime): a confirmation serves one run. */
    fun takeTargetShrinkConfirmation(jobId: String): Boolean {
        val at = data.shrinkConfirmations[jobId] ?: return false
        update { d -> d.copy(shrinkConfirmations = d.shrinkConfirmations - jobId) }
        return System.currentTimeMillis() - at < CONFIRMATION_TTL_MS
    }

    /** The user confirmed, in the preview, the removal of these target tracks by the next run of [jobId]. */
    fun confirmRemovals(jobId: String, trackIds: Collection<String>) = update { d ->
        d.copy(removalConfirmations = d.removalConfirmations + (jobId to RemovalConfirmation(trackIds.toSet(), System.currentTimeMillis())))
    }

    /** The confirmed track ids of [jobId], removed from the store: a confirmation serves one run. Null when none, or too old. */
    fun takeRemovalConfirmation(jobId: String): Set<String>? {
        if (jobId !in data.removalConfirmations) return null
        var taken: RemovalConfirmation? = null
        update { d -> taken = d.removalConfirmations[jobId]; d.copy(removalConfirmations = d.removalConfirmations - jobId) }
        return taken?.takeIf { System.currentTimeMillis() - it.at < CONFIRMATION_TTL_MS }?.trackIds
    }

    /** Aggiunge il report o, se un provvisorio con lo stesso id esiste gia', lo sostituisce al suo posto. */
    fun addReport(report: SyncReport) = update { d ->
        val reports = if (d.reports.any { it.id == report.id }) d.reports.map { if (it.id == report.id) report else it }
            else (listOf(report) + d.reports).take(MAX_REPORTS)
        d.copy(
            reports = reports,
            jobs = d.jobs.map { if (it.id == report.jobId) it.copy(lastRunEpoch = report.startedEpoch, lastReportId = report.id) else it },
        )
    }

    fun report(id: String): SyncReport? = data.reports.firstOrNull { it.id == id }

    fun updateReport(id: String, fn: (SyncReport) -> SyncReport) = update { d ->
        d.copy(reports = d.reports.map { if (it.id == id) fn(it) else it })
    }

    fun updateSettings(fn: (Settings) -> Settings) = update { d -> d.copy(settings = fn(d.settings)) }

    fun cachedMatch(srcProvider: String, srcTrackId: String, dstProvider: String): String? =
        data.matchCache[cacheKey(srcProvider, srcTrackId, dstProvider)]
            // Entries written before the key was per family, under the exact connector id.
            ?: data.matchCache["$srcProvider:$srcTrackId>$dstProvider"]

    /** "Ignore" in the review: this source track is not on that service, whichever sync meets it. */
    fun isIgnored(srcProvider: String, srcTrackId: String, dstProvider: String): Boolean =
        cacheKey(srcProvider, srcTrackId, dstProvider) in data.ignoredTracks || "$srcProvider:$srcTrackId>$dstProvider" in data.ignoredTracks

    fun putIgnored(srcProvider: String, srcTrackId: String, dstProvider: String) =
        update { d -> d.copy(ignoredTracks = d.ignoredTracks + cacheKey(srcProvider, srcTrackId, dstProvider)) }

    /** [putIgnored] for several tracks in one write. */
    fun putIgnored(srcProvider: String, srcTrackIds: Collection<String>, dstProvider: String) {
        if (srcTrackIds.isEmpty()) return
        update { d -> d.copy(ignoredTracks = d.ignoredTracks + srcTrackIds.map { cacheKey(srcProvider, it, dstProvider) }) }
    }

    /** The source tracks (of [srcProvider]) whose match on [dstProvider] is [dstTrackId]. */
    fun sourcesMatchedTo(srcProvider: String, dstProvider: String, dstTrackId: String): List<String> {
        val prefix = family(srcProvider) + ":"
        val suffix = ">" + family(dstProvider)
        return data.matchCache.entries
            .filter { (k, v) -> v == dstTrackId && k.startsWith(prefix) && k.endsWith(suffix) }
            .map { it.key.substring(prefix.length, it.key.length - suffix.length) }
    }

    fun putMatches(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        update { d ->
            var cache = d.matchCache + entries
            if (cache.size > MAX_CACHE) cache = cache.entries.drop(cache.size - MAX_CACHE).associate { it.key to it.value }
            // Un abbinamento trovato (o scelto a mano) cancella il ricordo della ricerca fallita.
            d.copy(matchCache = cache, missCache = d.missCache - entries.keys)
        }
    }

    /** True se questa ricerca e' fallita da poco: inutile ripeterla a ogni esecuzione o ripresa. */
    fun cachedMiss(srcProvider: String, srcTrackId: String, dstProvider: String): Boolean {
        val at = data.missCache[cacheKey(srcProvider, srcTrackId, dstProvider)] ?: data.missCache["$srcProvider:$srcTrackId>$dstProvider"] ?: return false
        return System.currentTimeMillis() - at < MISS_TTL_MS
    }

    fun putMisses(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val now = System.currentTimeMillis()
        update { d ->
            var cache = d.missCache.filterValues { now - it < MISS_TTL_MS } + keys.associateWith { now }
            if (cache.size > MAX_CACHE) cache = cache.entries.drop(cache.size - MAX_CACHE).associate { it.key to it.value }
            d.copy(missCache = cache)
        }
    }

    /**
     * The service said [playlistId] holds [trackId] although its listing does not show it. After a
     * month the track is tried again: a service that still has it refuses it again, and the answer
     * is remembered anew; one where the user removed it since gets it back.
     */
    fun isPresentUnlisted(dstProvider: String, playlistId: String, trackId: String): Boolean {
        val at = data.presentUnlisted[presentKey(dstProvider, playlistId, trackId)] ?: return false
        return System.currentTimeMillis() - at < PRESENT_TTL_MS
    }

    fun putPresentUnlisted(dstProvider: String, playlistId: String, trackIds: Collection<String>) {
        if (trackIds.isEmpty()) return
        val now = System.currentTimeMillis()
        update { d ->
            var m = d.presentUnlisted.filterValues { now - it < PRESENT_TTL_MS } + trackIds.associate { presentKey(dstProvider, playlistId, it) to now }
            if (m.size > MAX_CACHE) m = m.entries.drop(m.size - MAX_CACHE).associate { it.key to it.value }
            d.copy(presentUnlisted = m)
        }
    }

    /**
     * The service did not take [trackId] out of [playlistId] when asked: Mirror leaves it there for a
     * month instead of removing it again on every run, then tries once more.
     */
    fun isKeptOnRemoval(dstProvider: String, playlistId: String, trackId: String): Boolean {
        val at = data.keptOnRemoval[presentKey(dstProvider, playlistId, trackId)] ?: return false
        return System.currentTimeMillis() - at < PRESENT_TTL_MS
    }

    fun putKeptOnRemoval(dstProvider: String, playlistId: String, trackIds: Collection<String>) {
        if (trackIds.isEmpty()) return
        val now = System.currentTimeMillis()
        update { d ->
            var m = d.keptOnRemoval.filterValues { now - it < PRESENT_TTL_MS } + trackIds.associate { presentKey(dstProvider, playlistId, it) to now }
            if (m.size > MAX_CACHE) m = m.entries.drop(m.size - MAX_CACHE).associate { it.key to it.value }
            d.copy(keptOnRemoval = m)
        }
    }

    /** What the last run of [jobId] wrote on its target; null before the first run that recorded it. */
    fun lastWrites(jobId: String): LastWrites? = data.lastWrites[jobId]

    /** Replaces what [jobId] wrote last; entries of syncs that no longer exist go. */
    fun putLastWrites(jobId: String, writes: LastWrites) {
        if (writes.added.isEmpty() && writes.removed.isEmpty() && jobId !in data.lastWrites) return
        update { d ->
            val jobs = d.jobs.map { it.id }.toHashSet()
            d.copy(lastWrites = d.lastWrites.filterKeys { it in jobs } + (jobId to writes))
        }
    }

    companion object {
        const val MAX_REPORTS = 200
        /** How long a track a service said it holds, though unlisted, counts as present without asking again. */
        private const val PRESENT_TTL_MS = 30L * 24 * 3_600_000
        private const val WRITE_DELAY_MS = 500L
        private const val MAX_CACHE = 20_000
        /** Una ricerca fallita si ripete dopo una settimana: i cataloghi cambiano, ma non ogni giorno. */
        private const val MISS_TTL_MS = 7L * 24 * 3_600_000
        /** A removal confirmation not used within this time is dropped: it was meant for the run started then. */
        const val CONFIRMATION_TTL_MS = 12L * 3_600_000

        fun cacheKey(srcProvider: String, srcTrackId: String, dstProvider: String) = "${family(srcProvider)}:$srcTrackId>${family(dstProvider)}"

        /** Key of [StoreData.presentUnlisted]: the service, the playlist and the track there. */
        fun presentKey(dstProvider: String, playlistId: String, trackId: String) = "${family(dstProvider)}:$playlistId>$trackId"

        /**
         * Track ids are the service's, not the route's or the account's: a match found through the
         * official Spotify route serves the web route and a second account alike. Files keep their id.
         */
        fun family(providerId: String): String = when (val base = providerId.substringBefore('@')) {
            "spotify_bridge" -> "spotify"
            "apple_bridge" -> "apple"
            "deezer_bridge" -> "deezer"
            "ytm" -> "youtube"
            else -> base
        }

        @Volatile private var instance: FileStore? = null

        /** The one store of this process, on [dir]; the first call decides the folder. */
        fun get(dir: File): FileStore = instance ?: synchronized(this) {
            instance ?: FileStore(dir).also { instance = it }
        }

        /** Installs a store built elsewhere (the Android app's subclass), so both names reach it. */
        fun install(store: FileStore) { synchronized(this) { instance = store } }

        fun current(): FileStore? = instance
    }
}
