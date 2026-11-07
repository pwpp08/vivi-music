package com.xlollx.songport.sync

import com.xlollx.songport.providers.*
import com.xlollx.songport.data.FileStore
import com.xlollx.songport.data.LastWrites
import com.xlollx.songport.platform.Env
import kotlinx.coroutines.sync.withPermit
import com.xlollx.songport.model.MatchPolicy
import com.xlollx.songport.model.MatchReview
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncPlan
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.TargetSearch
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import com.xlollx.songport.providers.Providers
import java.util.UUID

/**
 * Esegue una sincronizzazione origine -> destinazione in due tempi:
 *
 *  **plan()** legge origine e destinazione, riconosce i brani gia' presenti (cache abbinamenti,
 *  ISRC, titolo+artista+durata), cerca i mancanti e decide cosa aggiungere e cosa togliere.
 *  Non scrive nulla: e' quello che mostra l'anteprima.
 *
 *  **apply()** esegue il piano: aggiunte a blocchi, rimozioni, cache, report.
 *
 * Ogni abbinamento riuscito finisce nella cache, cosi' le sync successive non ripetono le ricerche.
 * I brani che l'utente ha messo fra gli "ignorati" del job non vengono ne' cercati ne' segnalati.
 */
/**
 * Attese visibili quando il servizio limita le richieste: brevi e crescenti. Il contatore si azzera
 * dopo un tratto di ricerche riuscite, cosi' una playlist lunga con limiti intermittenti arriva in
 * fondo; solo una serie di rifiuti senza progressi fa fermare con quanto trovato.
 */
private val WAIT_SECONDS = intArrayOf(10, 20, 40, 60, 120)
/** Quante attese dichiarate dal servizio (blocco dell'indirizzo) si accettano prima di fermarsi con quanto fatto. */
private const val MAX_BLOCK_WAITS = 8
/** Longer than this, a declared pause stops the sync with its end time instead of waiting it out. */
private const val LONG_PAUSE_SECONDS = 1800
/** How long to suggest waiting after a long block with no declared end (Apple web: about an hour). */
private const val LONG_BLOCK_SECONDS = 3600L
/** Additions from which a slow, one-at-a-time target (see MusicProvider.addSpacingMs) is worth a note. */
private const val SLOW_ADD_NOTE_FROM = 60
/** How many added tracks a report keeps for its list: enough to read, small enough for a stored report. */
private const val MAX_LISTED = 500
private const val RESET_AFTER_CHUNKS = 15
/** Punteggio fittizio di un "non trovato" preso dalla cache dei fallimenti, mai mostrato. */
private const val MISS_SCORE = -1.0
/**
 * Attese crescenti quando la rete non risponde. Una sync programmata parte spesso mentre il telefono
 * si sta svegliando: WorkManager la lancia appena il sistema dichiara la rete "connessa", ma il DNS
 * puo' arrivare qualche decina di secondi dopo. Quindici secondi di tentativi non bastavano.
 */
private val NETWORK_RETRY_SECONDS = intArrayOf(5, 15, 30, 60, 120)

/** Errori di trasporto che passano da soli: risoluzione del nome, connessione rifiutata o caduta, timeout. */
fun isTransientNetwork(msg: String?): Boolean {
    val m = msg ?: return false
    return m.contains("Unable to resolve host", true) || m.contains("unreachable", true) || m.contains("Failed to connect", true) ||
        m.contains("timeout", true) || m.contains("timed out", true) || m.contains("connection abort", true) || m.contains("reset by peer", true) ||
        // A TLS handshake cut short ("connection closed") or a reply cut off: the line dropped, as above.
        m.contains("connection closed", true) || m.contains("unexpected end of stream", true)
}

/** Below the match threshold but close enough to be worth proposing in the review. */
private const val HINT_THRESHOLD = 0.45
/** A run stopped by the target's size check ([SyncReport.targetShrunkFrom]): [expected] is what the last run left. */
class TargetShrunkException(message: String, val expected: Int) : Exception(message)

/** From this many tracks on the target, a listing under half of what the last run left is held suspect. */
private const val SHRINK_MIN = 20
/** The pause before reading a suspect target again. */
internal var shrinkRereadMs = 3_000L
/** Above this many tracks the order step is skipped: too many single moves for one run. */
private const val MAX_REORDER = 1500
/** Hosts of the short links music apps put in their share text. */
private val SHORT_LINK_HOSTS = setOf("amzn.to", "amzn.eu", "a.co", "spotify.link", "spoti.fi", "apple.co", "deezer.page.link", "link.deezer.com", "tidal.link")

class SyncEngine(
    private val env: Env,
    /** User-facing texts by their strings.xml name; replaced in tests, which check the names. */
    private val texts: (String, Array<out Any>) -> String = { name, args -> env.text(name, *args) },
) {
    private val store = env.store
    private fun str(name: String, vararg args: Any): String = texts(name, args)

    /** Best candidate under the threshold per source track searched in this run: the review's proposals. */
    private val hints = java.util.concurrent.ConcurrentHashMap<String, Track>()
    /** Source ids whose searches on the target returned nothing at all: not there, nothing to review. */
    private val absent: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    suspend fun run(job: SyncJob, unattended: Boolean = false, onProgress: (Progress) -> Unit = {}): SyncReport {
        val started = System.currentTimeMillis()
        val reportId = UUID.randomUUID().toString()
        // The source's version before anything is read: a change during the run is seen next time.
        val (src0, dst0) = runCatching { providers(job) }.getOrNull() ?: (null to null)
        val sourceVersion = job.source.playlistId?.let { id -> src0?.let { runCatching { it.playlistVersion(env, id) }.getOrNull() } }
        if (unattended && sourceVersion != null && sourceVersion == job.sourceVersion && job.targetVersion != null) {
            val targetVersion = job.target.playlistId?.let { id -> dst0?.let { runCatching { it.playlistVersion(env, id) }.getOrNull() } }
            if (targetVersion == job.targetVersion) {
                env.log("engine", "${job.name}: unchanged on both sides, skipped")
                val report = SyncReport(reportId, job.id, job.name, started, System.currentTimeMillis() - started, notes = listOf(str("note_unchanged")))
                store.addReport(report)
                return report
            }
        }
        // Two syncs writing to the same playlist run one after the other: at once they could both add
        // what is missing, twice. The second waits for the first.
        val lock = JobConflicts.targetKey(job)?.let { targetLocks.getOrPut(it) { kotlinx.coroutines.sync.Mutex() } }
        if (lock?.isLocked == true) env.log("engine", "${job.name}: waiting for another sync writing to the same playlist")
        lock?.lock()
        val report = try {
            // A playlist Spotify makes (daylist, Discover Weekly…) is only ever read: a sync writing to
            // it (one made before the editor knew, or the way back of a two-way) stops here, clearly.
            if (job.target.provider in SPOTIFY_IDS && com.xlollx.songport.providers.SpotifyMade.isSpotifyMade(job.target.playlistId)) {
                throw ProviderException(str("error_read_only", job.target.playlistName.ifBlank { job.target.playlistId.orEmpty() }))
            }
            val plan = plan(job, onProgress, unattended = unattended)
            env.log("engine", "${job.name}: plan ready in ${(System.currentTimeMillis() - started) / 1000}s: source ${plan.sourceCount}, " +
                "not found ${plan.unmatched.size}, to review ${plan.uncertain.size}, removals held back ${plan.heldBackRemovals}" +
                if (plan.searchInterrupted) ", search interrupted" else "")
            // La ricerca e' finita: i brani non trovati e gli abbinamenti incerti si possono gia'
            // sistemare mentre l'aggiunta procede. Un report provvisorio li rende raggiungibili.
            if (plan.unmatched.isNotEmpty() || plan.uncertain.isNotEmpty()) {
                store.addReport(
                    SyncReport(
                        reportId, job.id, job.name, started, System.currentTimeMillis() - started,
                        sourceCount = plan.sourceCount, unmatched = plan.unmatched.map { it.toString() }, unmatchedTracks = plan.unmatched,
                        ignored = plan.ignored, reviewTracks = plan.uncertain, notes = plan.notes, partial = true,
                        heldBack = plan.heldBackRemovals, searchInterrupted = plan.searchInterrupted,
                        suggestions = suggestionsFor(plan.unmatched), absent = plan.unmatched.filter { it.id in absent }.map { it.id },
                    ),
                )
            }
            apply(job, plan, started, reportId, onProgress)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Il sistema ha fermato il worker (cambio di rete, limiti): non e' un esito della sync e
            // WorkManager la fara' ripartire; gli abbinamenti fatti finora sono gia' in cache.
            throw e
        } catch (e: Exception) {
            env.log("engine", "${job.name}: ${describe(e)}")
            SyncReport(reportId, job.id, job.name, started, System.currentTimeMillis() - started,
                error = e.message ?: e.javaClass.simpleName, targetShrunkFrom = (e as? TargetShrunkException)?.expected ?: 0)
        } finally {
            lock?.unlock()
        }
        store.addReport(report)
        if (report.error == null && dst0 != null) {
            // The target's version after our own writes: the next scheduled run compares against it.
            val latest = store.job(job.id) ?: job
            val targetVersion = latest.target.playlistId?.let { id -> runCatching { dst0.playlistVersion(env, id) }.getOrNull() }
            if (sourceVersion != latest.sourceVersion || targetVersion != latest.targetVersion) store.upsertJob(latest.copy(sourceVersion = sourceVersion, targetVersion = targetVersion))
        }
        return report
    }

    /**
     * The playlist is still in the user's library. Library slots (liked, albums…) and files always
     * are; a source that is not listed may be someone else's public playlist, so it passes when the
     * service still describes it. A target must be the user's own: listed, or gone.
     */
    private suspend fun checkStillThere(p: MusicProvider, playlistId: String, name: String, isSource: Boolean) {
        if (MusicProvider.isLibrary(playlistId) || !p.requiresAuth) return
        val listed = runCatching { p.playlists(env).any { it.id == playlistId } }.getOrDefault(true)
        if (listed) return
        if (isSource && runCatching { p.playlistInfo(env, playlistId) }.isSuccess) return
        throw ProviderException(str(if (isSource) "error_source_gone" else "error_target_gone", name.ifBlank { playlistId }, p.label(env)))
    }

    /**
     * An error for the log. A [ProviderException] is said by its message alone: its class name is
     * not ours to show, and in a release build R8 shortens it to a letter ("Liked songs: c: …").
     */
    /**
     * An error as the log says it. Our own exceptions carry a message written for people; a class
     * name helps only for the platform's own (IOException, SocketTimeoutException…), as a release
     * build shrinks the app's class names to letters ("h: Stopped by you").
     */
    private fun describe(e: Exception): String {
        val msg = e.message
        val platform = e.javaClass.name.let { it.startsWith("java.") || it.startsWith("javax.") || it.startsWith("kotlin.") || it.startsWith("android.") }
        return when {
            e is ProviderException -> msg ?: "error"
            platform -> "${e.javaClass.simpleName}: $msg"
            else -> msg ?: "error"
        }
    }

    /** A track as the log names it: artist and title, never an id alone (an untitled entry says so). */
    private fun named(tracks: Collection<Track>, max: Int = 20): String =
        tracks.take(max).joinToString("; ") { it.toString().ifBlank { "an entry listed without a title" } } + if (tracks.size > max) "; …" else ""

    private fun tracksWord(n: Int) = if (n == 1) "track" else "tracks"

    /** Below this many removals a scheduled run proceeds whatever the share of the target they are. */
    private val REMOVAL_CAP_MIN = 10

    /**
     * Source and target are the same connector (the same account): a source track's id is valid on
     * the target as it is, so it is neither searched nor matched by name. Files are left out: a file
     * playlist's ids are its own lines, not a catalogue's.
     */
    private fun sameAccount(job: SyncJob, src: MusicProvider, dst: MusicProvider): Boolean =
        (src.id == dst.id || sameCatalogue(src, dst)) && dst.serviceId != LocalFilesProvider.serviceId &&
            // Liked songs into saved albums asks for albums, not the same ids.
            MusicProvider.libraryKind(job.source.playlistId) == MusicProvider.libraryKind(job.target.playlistId)

    /**
     * Two accounts of one service whose ids are the service's own, the same for every account (a
     * Spotify track id, a YouTube video id): from one account to the other a track is its own match,
     * as on one account. Not a server of one's own (two Jellyfin servers number their tracks each
     * their own way), nor Apple Music, whose library ids belong to the account.
     */
    private fun sameCatalogue(src: MusicProvider, dst: MusicProvider): Boolean =
        src.serviceId == dst.serviceId && src.serviceId in GLOBAL_IDS

    private val GLOBAL_IDS = setOf("spotify", "spotify_bridge", "ytm", "youtube", "deezer", "deezer_bridge", "tidal", "qobuz_web")

    private fun providers(job: SyncJob): Pair<MusicProvider, MusicProvider> {
        val src = Providers.byId(job.source.provider) ?: throw ProviderException("Unknown service: ${job.source.provider}")
        val dst = Providers.byId(job.target.provider) ?: throw ProviderException("Unknown service: ${job.target.provider}")
        return src to dst
    }

    /** Calcola cosa farebbe la sync senza modificare nulla (a parte creare la destinazione se manca). */
    /**
     * [interactive]: the plan is for the preview, where the user sees every removal before applying it.
     * Any other run (scheduled or started with a tap) holds back removals that would take a large
     * part of the target: a source the service listed only partly looks exactly like a source that
     * lost most of its tracks, and only a person can tell the two apart. The person says so in the
     * preview ([FileStore.confirmRemovals]): the next run started by hand then removes them, provided
     * it removes nothing the user did not see. A scheduled run ([unattended]) never does.
     */
    /**
     * A source that renames itself (a Spotify daylist gets a new title every few hours, issue #32):
     * the target takes the new name when it still carries the source's old one, i.e. the user has
     * not given it a name of their own; so does the sync, when it is named after the source. A
     * target renamed by the user keeps its name. A new cover is copied too, where the target takes
     * covers (Spotify); a sync made before covers were followed only notes the current one, so a
     * cover the user chose is not replaced until the source changes its own. Never fails the run.
     */
    private val SYSTEMIC_ADD_FAILURES = 5
    private val SELF_NAMING_WINDOW_MS = 3L * 24 * 3600 * 1000

    private suspend fun followSource(job: SyncJob, src: MusicProvider, srcId: String, dst: MusicProvider, targetId: String) {
        if (MusicProvider.isLibrary(srcId) || MusicProvider.isLibrary(targetId)) return
        val info = runCatching { src.playlistInfo(env, srcId) }.getOrNull() ?: return
        val latest = store.job(job.id) ?: job
        var updated = latest
        val old = latest.source.playlistName.trim()
        val now = info.name.trim().takeIf { it.isNotEmpty() && it != srcId }
        if (old.isNotEmpty() && now != null && now != old) {
            val at = System.currentTimeMillis()
            // Renamed again within days: a playlist that names itself, whose titles would rename the
            // sync several times a day. The sync keeps the name it has; the target still follows.
            val selfNaming = latest.sourceRenamedAt?.let { at - it < SELF_NAMING_WINDOW_MS } == true
            val takesName = latest.name.trim() == old && !selfNaming
            updated = updated.copy(source = updated.source.copy(playlistName = now), sourceRenamedAt = at)
            if (takesName) updated = updated.copy(name = now)
            env.log("engine", "${job.name}: the source playlist is now called \"$now\"" +
                when { takesName -> ", the sync takes its name"; selfNaming -> ", it renames itself often: the sync keeps its name"; else -> "" })
            if (dst.canRenamePlaylists) {
                val current = runCatching { dst.playlistInfo(env, targetId).name.trim() }.getOrNull()
                    ?.takeIf { it.isNotEmpty() && it != targetId } ?: latest.target.playlistName.trim()
                if (current == old) {
                    runCatching { dst.renamePlaylist(env, targetId, now) }
                        .onSuccess { updated = updated.copy(target = updated.target.copy(playlistName = now)); env.log("engine", "${job.name}: the source is now \"$now\", the target follows") }
                        .onFailure { env.log("engine", "${job.name}: target not renamed: ${it.message?.take(120)}") }
                }
            }
        }
        val cover = info.imageUrl?.takeIf { it.isNotBlank() }
        if (cover != null && cover != latest.sourceCover) {
            if (latest.sourceCover == null || !dst.canSetCover) updated = updated.copy(sourceCover = cover)
            else runCatching { Covers.fetchJpeg(env, cover)?.let { dst.setPlaylistCover(env, targetId, it) } ?: error("no image") }
                .onSuccess { updated = updated.copy(sourceCover = cover); env.log("engine", "${job.name}: new cover copied") }
                .onFailure { env.log("engine", "${job.name}: cover not copied: ${it.message?.take(120)}") }
        }
        if (updated != latest) store.upsertJob(updated)
    }

    suspend fun plan(job: SyncJob, onProgress: (Progress) -> Unit = {}, createTarget: Boolean = true, unattended: Boolean = false, interactive: Boolean = false): SyncPlan {
        val (src, dst) = providers(job)
        val srcPlaylistId = job.source.playlistId ?: throw ProviderException("No source playlist selected")
        // L'origine puo' essere una playlist pubblica leggibile senza login (catalogo Apple Music).
        if (!src.canRead(env, srcPlaylistId)) throw ProviderException(str("error_not_connected", src.displayName))
        if (!dst.isConnected(env)) throw ProviderException(str("error_not_connected", dst.displayName))

        // Source and target on the same account are fine (Discover Weekly kept in a playlist of the same
        // Spotify), as long as they are two playlists: one playlist synced onto itself has nothing to do.
        if (src.id == dst.id && job.target.playlistId == srcPlaylistId) throw ProviderException(str("error_same_playlist"))
        val notes = ArrayList<String>()

        onProgress(Progress(Progress.Step.FETCH_SOURCE))
        // A playlist deleted on the service (or unfollowed on Spotify, whose API keeps answering for
        // it) must stop the sync with its name, not feed a ghost. One list call per side, per run.
        checkStillThere(src, srcPlaylistId, job.source.playlistName, isSource = true)
        job.target.playlistId?.let { checkStillThere(dst, it, job.target.playlistName, isSource = false) }
        val allSource = patient(onProgress) { src.tracks(env, srcPlaylistId) }
        // Ignored in this sync, or declared absent from this destination service by any sync.
        val ignoredIds = job.ignoredSourceIds.toHashSet()
        // Verso "album salvati" o "artisti seguiti" (anche da un file) si cercano album o artisti, non brani.
        val kind = MusicProvider.libraryKind(job.target.playlistId)
        val srcTracks = allSource.filter { it.id !in ignoredIds && !store.isIgnored(src.id, it.id, dst.id) }
            .map { if (kind != null && it.kind.isEmpty()) it.copy(kind = kind) else it }
        val ignored = allSource.size - srcTracks.size

        var targetId = job.target.playlistId
        var targetCreated = false
        if (targetId == null) {
            if (!createTarget) {
                // Anteprima senza destinazione: tutto cio' che si abbina verrebbe aggiunto.
                return previewWithoutTarget(job, src, dst, srcTracks, ignored, notes, onProgress)
            }
            onProgress(Progress(Progress.Step.CREATE_TARGET))
            // The source's own name, description and cover travel with the playlist, where the source has them to give.
            val info = if (MusicProvider.isLibrary(srcPlaylistId)) null else runCatching { src.playlistInfo(env, srcPlaylistId) }.getOrNull()
            val srcName = job.source.playlistName.ifBlank { info?.name.orEmpty() }
            // No name given for the new playlist: the shared one's own name, read now (a link pasted
            // without a name resolves here), then whatever the sync is called.
            val name = job.target.playlistName.ifBlank { info?.name?.takeIf { it.isNotBlank() } ?: srcName.ifBlank { job.name } }
            val description = MusicProvider.plainDescription(info?.description)
            val created = patient(onProgress) { dst.createPlaylist(env, name, description.ifBlank { MusicProvider.description(str("app_name")) }) }
            targetId = created.id
            targetCreated = true
            // From the stored job, not the one this run started with: whatever was changed meanwhile
            // (the card's colour, its schedule) stays; only what this step learnt is written.
            val stored = store.job(job.id) ?: job
            store.upsertJob(stored.copy(
                name = stored.name.ifBlank { name },
                source = stored.source.copy(playlistName = srcName),
                target = stored.target.copy(playlistId = created.id, playlistName = created.name),
                sourceCover = info?.imageUrl,
            ))
            val cover = info?.imageUrl ?: (if (dst.canSetCover) runCatching { src.playlists(env).firstOrNull { it.id == srcPlaylistId }?.imageUrl }.getOrNull() else null)
            if (dst.canSetCover && cover != null) runCatching { Covers.fetchJpeg(env, cover)?.let { dst.setPlaylistCover(env, created.id, it) } }
                .onFailure { env.log("engine", "cover not set: ${it.message?.take(120)}") }
        }

        if (!targetCreated && createTarget && !interactive) followSource(job, src, srcPlaylistId, dst, targetId)

        if (dst.serviceId == LocalFilesProvider.serviceId) notes += str("note_file_target")

        onProgress(Progress(Progress.Step.FETCH_TARGET))
        // A playlist created a moment ago is empty: skip the fetch (saves quota, and YouTube's Data API
        // may not even know the playlist yet).
        val dstTracks = if (targetCreated) emptyList() else readTarget(job, dst, targetId, onProgress, unattended, interactive, notes)
        val shrunkFrom = if (interactive && !targetCreated) shrunkFrom(job, targetId, dstTracks.size) else 0
        val dstById = dstTracks.associateBy { it.id }
        val index = Matcher.TrackIndex(dstTracks)

        val matchedDstIds = HashSet<String>()
        val newCache = HashMap<String, String>()
        val toSearch = ArrayList<Track>()
        // The same song twice in the source (common in long playlists) is searched once, and is one
        // entry of what was not found: the review lists each song once (issue #24).
        val queued = HashSet<String>()
        // A match the preview is unsure about is not remembered: the run searches it again and puts
        // it in its own review, unless the user settled it in the preview (which remembers it).
        val unsure = HashSet<String>()

        // A track the service said it holds although its listing leaves it out (Amazon Music's
        // DUPLICATE_TRACK): present, so it is not added, and so not counted as added, on every run.
        val listedId: String = targetId
        fun unlistedPresent(trackId: String) = !targetCreated && store.isPresentUnlisted(dst.id, listedId, trackId)
        // What this sync wrote on this target in its last run: an id it added then that the listing
        // still leaves out is on the target but not shown (YouTube's Liked music leaves out a liked
        // video); one it removed then that is listed again is one the service does not let go.
        val last = if (targetCreated) null else store.lastWrites(job.id)?.takeIf { it.targetId == listedId }
        val unlistedAfterAdd = LinkedHashMap<String, Track>()

        val direct = sameAccount(job, src, dst)
        // 1) brani gia' presenti nella destinazione
        for (s in srcTracks) {
            val key = FileStore.cacheKey(src.id, s.id, dst.id)
            val cachedId = store.cachedMatch(src.id, s.id, dst.id)
            if (cachedId != null && cachedId !in dstById && unlistedPresent(cachedId)) {
                matchedDstIds += cachedId
                newCache[key] = cachedId
                continue
            }
            // On the same account a source track is already a target track: its own id, if the target has it.
            val found = cachedId?.let { dstById[it] } ?: (if (direct) dstById[s.id] else null) ?: index.best(s, policy = job.policy)
            if (found != null) {
                matchedDstIds += found.id
                newCache[key] = found.id
            } else if (queued.add(s.id)) toSearch += s
        }

        // 2) ricerca dei mancanti
        val toAdd = LinkedHashMap<String, Track>()
        val unmatched = ArrayList<Track>()
        val uncertain = ArrayList<MatchReview>()
        var searched = 0
        val interrupted = searchMissing(src, dst, toSearch, job.policy, onProgress, direct) { s, found, score ->
            searched++
            SyncState.item(
                job.id,
                SyncState.LiveItem(
                    source = listOfNotNull(s.artists.firstOrNull(), s.title).joinToString(" – "),
                    result = found?.let { listOfNotNull(it.artists.firstOrNull(), it.title).joinToString(" – ") },
                    outcome = when {
                        found == null -> SyncState.Outcome.NOT_FOUND
                        score == null -> SyncState.Outcome.CACHED
                        else -> SyncState.Outcome.FOUND
                    },
                ),
            )
            if (found != null) {
                // The target may hold the same recording under another id (a remaster, the single
                // edition): by ISRC or by name it counts as present, and nothing is added twice.
                val present = dstById[found.id] ?: if (found.id in matchedDstIds || unlistedPresent(found.id)) found else index.best(found)
                val target = present ?: found
                // Added by the last run and still not listed: the service took it and shows it
                // elsewhere, or not at all. Present, not added again (and not counted) on every run.
                if (present == null) { if (last != null && found.id in last.added) unlistedAfterAdd[found.id] = found else toAdd[found.id] = found }
                matchedDstIds += target.id
                newCache[FileStore.cacheKey(src.id, s.id, dst.id)] = target.id
                if (score != null && score < job.policy.reviewThreshold) {
                    uncertain += MatchReview(s, found, score)
                    if (interactive) unsure += FileStore.cacheKey(src.id, s.id, dst.id)
                }
                // Salvataggio progressivo: un errore a meta' strada non butta via le ricerche fatte.
                if (newCache.size % 100 == 0) store.putMatches(newCache - unsure)
            } else unmatched += s
        }
        // Il servizio ha smesso di rispondere (limiti, sessione): si applica quanto trovato finora e
        // la prossima esecuzione riparte dalla cache, senza rifare le ricerche.
        if (interrupted != null) notes += str("note_search_interrupted", searched, toSearch.size, interrupted)
        if (unlistedAfterAdd.isNotEmpty()) {
            // Remembered as the tracks the service refuses as duplicates are: for a month, then tried
            // once more, so one the user removed on the target in the meantime comes back.
            store.putPresentUnlisted(dst.id, listedId, unlistedAfterAdd.keys)
            env.log("engine", "${job.name}: ${unlistedAfterAdd.size} ${tracksWord(unlistedAfterAdd.size)} added before but not shown by ${dst.displayName}: ${named(unlistedAfterAdd.values)}")
        }

        // 3) rimozioni: solo cio' che nella destinazione non corrisponde a nulla dell'origine
        var toRemove: List<Track> = emptyList()
        var needsConfirmation = false
        var heldBack = 0
        // Half of a two-way pair: the other half copies back what was added on this target, and a
        // removal here could come first. Removals are never made in a pair.
        val paired = job.linkedJobId?.let { store.job(it) } != null
        // Another sync writes to this playlist too: what matches none of this source may be its.
        val sharing = JobConflicts.sharingTarget(store.data.jobs, job)
        // An archive keeps every track it was ever given: nothing goes, whatever the switches say.
        if (job.mirrorRemovals && !job.archive) {
            // An entry the service lists by id alone cannot be told apart from one of the source's
            // under another id: it stays (see MusicProvider.listsUntitledEntries).
            val untitled = if (dst.listsUntitledEntries) dstTracks.count { it.id !in matchedDstIds && it.title.isBlank() } else 0
            if (untitled > 0) env.log("engine", "${job.name}: $untitled entries of the target listed without a title on ${dst.displayName}, matching no source track: not removed")
            val unmatchedOnTarget = dstTracks.filter { it.id !in matchedDstIds && !(dst.listsUntitledEntries && it.title.isBlank()) }
            // Removed by the last run and listed again now: the service answered the removal without
            // taking the entry away (or it came back). Removing it again would only repeat that on every
            // run: it stays, said once, and is left alone for a month, like a track the service keeps
            // after a removal it reported ([MusicProvider.removeTracksReportingKept]).
            val keptBefore = unmatchedOnTarget.filter { store.isKeptOnRemoval(dst.id, listedId, it.id) }.map { it.id }.toHashSet()
            val back = unmatchedOnTarget.filter { last != null && it.id in last.removed && it.id !in keptBefore }
            if (back.isNotEmpty()) {
                store.putKeptOnRemoval(dst.id, listedId, back.map { it.id })
                env.log("engine", "${job.name}: ${dst.displayName} keeps ${back.size} ${tracksWord(back.size)} that removing does not take away: ${named(back)}")
            }
            val stays = keptBefore + back.map { it.id }
            val extra = unmatchedOnTarget.filter { it.id !in stays }
            // Un quarto o piu' della destinazione (playlist svuotata per sbaglio, account cambiato, o un
            // elenco letto a meta' dal servizio): solo una persona puo' dire se va tolto davvero.
            val large = extra.size > REMOVAL_CAP_MIN && extra.size * 4 > dstTracks.size
            when {
                paired -> if (extra.isNotEmpty()) notes += str("note_two_way_no_removals", extra.size)
                sharing.isNotEmpty() -> if (extra.isNotEmpty()) notes += str("note_shared_target_no_removals", extra.size, sharing.joinToString(", ") { it.name })
                // Ricerca non completata: un brano della destinazione puo' corrispondere a uno
                // dell'origine non ancora cercato. Nulla si toglie finche' non si sa.
                interrupted != null -> if (extra.isNotEmpty()) notes += str("note_removals_deferred", extra.size)
                !dst.canRemoveTracks -> if (extra.isNotEmpty()) notes += str("note_no_removals", dst.displayName, extra.size)
                // Origine vuota: quasi certamente un errore, non svuotiamo la destinazione.
                srcTracks.isEmpty() -> notes += str("error_source_empty")
                interactive -> { toRemove = extra; needsConfirmation = large && !job.followsChanges }
                !large || job.followsChanges -> toRemove = extra
                else -> {
                    // A confirmation serves one run started by hand, used or not; a scheduled run leaves it.
                    val confirmed = if (unattended) null else store.takeRemovalConfirmation(job.id)
                    if (confirmed != null && extra.all { it.id in confirmed }) {
                        toRemove = extra
                        env.log("engine", "${job.name}: ${extra.size} removals confirmed in the preview")
                    } else {
                        heldBack = extra.size
                        // Towards Liked songs a removal is an unlike: the note says so, as the preview does.
                        notes += str(if (targetId == MusicProvider.LIKED_ID) "note_unlikes_capped" else "note_removals_capped", extra.size, dstTracks.size)
                        env.log("engine", "${job.name}: removals held back, ${extra.size} of ${dstTracks.size} in the target matched none of the ${srcTracks.size} source tracks" +
                            if (confirmed != null) "; the confirmation did not cover them" else "")
                    }
                }
            }
        }

        // Gli abbinamenti sono fatti, non intenzioni: salvarli ora evita di ripetere le ricerche
        // (e la quota YouTube) se l'utente esegue dopo l'anteprima.
        store.putMatches(newCache - unsure)
        // A service that saves one track at a time to keep the order (Spotify's liked songs): a big
        // first sync takes a while, and the person should know it's expected.
        val spacing = if (job.keepOrder) dst.addSpacingMs(targetId) else 0
        if (spacing > 0 && toAdd.size >= SLOW_ADD_NOTE_FROM) {
            notes += str("note_slow_ordered_adds", dst.displayName, toAdd.size, ((toAdd.size * spacing + 59_999) / 60_000).toInt())
        }
        return SyncPlan(
            targetPlaylistId = targetId,
            sourceCount = srcTracks.size,
            alreadyPresent = srcTracks.size - srcTracks.count { it.id in queued },
            toAdd = toAdd.values.toList(),
            toRemove = toRemove,
            unmatched = unmatched,
            ignored = ignored,
            uncertain = uncertain,
            notes = notes,
            newMatches = newCache - unsure,
            targetCreated = targetCreated,
            sourceOrder = srcTracks.map { it.id },
            targetCount = dstTracks.size,
            removalsNeedConfirmation = needsConfirmation,
            heldBackRemovals = heldBack,
            searchInterrupted = interrupted != null,
            targetShrunkFrom = shrunkFrom,
        )
    }

    /**
     * How many tracks the last good run of [job] left on [targetId], when [now] is far below it (under
     * half, from [SHRINK_MIN] tracks up); 0 otherwise. A listing read in part (a page the service did
     * not send, an answer in a new layout) looks like an emptied playlist: every source track would
     * be added again, thousands of writes the service may hold against the account.
     */
    private fun shrunkFrom(job: SyncJob, targetId: String, now: Int): Int {
        val last = store.data.reports.firstOrNull { it.jobId == job.id && !it.partial && it.error == null && it.targetId == targetId } ?: return 0
        val expected = last.targetCount + last.added - last.removed
        return if (expected >= SHRINK_MIN && now * 2 < expected) expected else 0
    }

    /**
     * The target's tracks, read once more when the listing is far smaller than the last run left it
     * ([shrunkFrom]). Still that small, a run stops before writing anything, unless the user saw it in
     * the preview and ran anyway; the preview itself goes on and says it ([SyncPlan.targetShrunkFrom]).
     */
    private suspend fun readTarget(
        job: SyncJob, dst: MusicProvider, targetId: String, onProgress: (Progress) -> Unit,
        unattended: Boolean, interactive: Boolean, notes: MutableList<String>,
    ): List<Track> {
        val first = patient(onProgress) { dst.tracks(env, targetId) }
        val expected = shrunkFrom(job, targetId, first.size)
        if (expected == 0) return first
        env.log("engine", "${job.name}: ${dst.displayName} lists ${first.size} tracks, the last run left $expected: reading it again")
        kotlinx.coroutines.delay(shrinkRereadMs)
        val second = patient(onProgress) { dst.tracks(env, targetId) }
        val best = if (second.size >= first.size) second else first
        if (shrunkFrom(job, targetId, best.size) == 0) return best
        if (interactive) {
            notes += str("note_target_shrunk", dst.displayName, best.size, expected)
            return best
        }
        if (!unattended && store.takeTargetShrinkConfirmation(job.id)) {
            env.log("engine", "${job.name}: ${best.size} tracks on ${dst.displayName} instead of $expected, run anyway as confirmed in the preview")
            return best
        }
        env.log("engine", "${job.name}: stopped, ${dst.displayName} lists ${best.size} tracks twice, the last run left $expected; nothing written")
        throw TargetShrunkException(str("error_target_shrunk", dst.displayName, best.size, expected), expected)
    }

    /** Anteprima quando la destinazione non esiste ancora: si cerca tutto, non si crea niente. */
    private suspend fun previewWithoutTarget(
        job: SyncJob, src: MusicProvider, dst: MusicProvider, srcTracks: List<Track>,
        ignored: Int, notes: List<String>, onProgress: (Progress) -> Unit,
    ): SyncPlan {
        val toAdd = LinkedHashMap<String, Track>()
        val unmatched = ArrayList<Track>()
        val uncertain = ArrayList<MatchReview>()
        val newCache = HashMap<String, String>()
        searchMissing(src, dst, srcTracks, job.policy, onProgress, sameAccount(job, src, dst)) { s, found, score ->
            if (found != null) {
                toAdd[found.id] = found
                // Unsure matches stay out of the cache: the run reviews them unless the preview settles them.
                if (score != null && score < job.policy.reviewThreshold) uncertain += MatchReview(s, found, score)
                else newCache[FileStore.cacheKey(src.id, s.id, dst.id)] = found.id
            } else unmatched += s
        }
        store.putMatches(newCache)
        return SyncPlan(
            targetPlaylistId = "", sourceCount = srcTracks.size, alreadyPresent = 0,
            toAdd = toAdd.values.toList(), toRemove = emptyList(), unmatched = unmatched,
            ignored = ignored, uncertain = uncertain, notes = notes, newMatches = newCache, targetCreated = false,
        )
    }

    /**
     * Cerca sulla destinazione i brani mancanti, usando la cache quando c'e'. Le ricerche vanno in
     * parallelo dove il servizio lo tollera (vedi [MusicProvider.searchParallelism]); i risultati
     * sono consegnati nell'ordine di origine.
     *
     * @return null se ha finito, altrimenti il motivo per cui si e' fermata prima (errore sistemico:
     * limiti di richieste, sessione scaduta). I brani non cercati non vengono consegnati.
     */
    /** Un semaforo per servizio di destinazione, condiviso da tutte le istanze del motore nel processo. */
    private companion object {
        val SPOTIFY_IDS = setOf("spotify", "spotify_bridge")
        val searchSlots = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Semaphore>()
        /** One run at a time per target playlist ("service|playlist"), across the syncs of this process. */
        val targetLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    }

    private suspend fun searchMissing(
        src: MusicProvider, dst: MusicProvider, toSearch: List<Track>, policy: MatchPolicy,
        onProgress: (Progress) -> Unit,
        /** Source and target on the same account ([sameAccount]): each track is its own match. */
        direct: Boolean = false,
        onResult: (Track, Track?, Double?) -> Unit,
    ): String? {
        val parallel = dst.searchParallelism.coerceIn(1, 8)
        var consecutiveErrors = 0
        var errors = 0
        var searchedHere = 0
        var done = 0
        var waits = 0
        var blockWaits = 0
        var sinceWait = 0
        val chunks = toSearch.chunked(parallel)
        var ci = 0
        val startedAt = System.currentTimeMillis()
        var searched = 0
        var takenAsIs = 0
        // Ricerche finite senza esito in questa esecuzione: ricordate, cosi' una ripresa o il giro di
        // domani non le ripetono (le "non trovate" altrimenti si ricercavano ogni volta).
        val misses = ArrayList<String>()
        fun saveMisses() { store.putMisses(misses); misses.clear() }
        while (ci < chunks.size) {
            val chunk = chunks[ci]
            // Cosa si sta cercando adesso: la scheda e la notifica lo mostrano, cosi' non sembra fermo.
            onProgress(Progress(Progress.Step.MATCHING, done, toSearch.size, chunk.first().let { listOfNotNull(it.artists.firstOrNull(), it.title).joinToString(" – ") }))
            // Esito per brano: (trovato, punteggio); punteggio null = preso dalla cache, mai "incerto".
            val results: List<Result<Pair<Track?, Double?>>> = coroutineScope {
                chunk.map { s ->
                    async {
                        val cached = store.cachedMatch(src.id, s.id, dst.id)
                        if (direct && s.id.isNotBlank()) {
                            // Source and target on the same account: the track is the source's own, nothing to search.
                            Result.success<Pair<Track?, Double?>>(s.copy(itemId = null) to null)
                        } else if (cached != null) {
                            // Abbinato in passato (o confermato a mano): lo aggiungiamo senza ripetere la ricerca.
                            Result.success<Pair<Track?, Double?>>(dst.rehydrate(s.copy(id = cached, uri = null, itemId = null)) to null)
                        } else if (store.cachedMiss(src.id, s.id, dst.id)) {
                            // Cercato da poco senza esito: resta "non trovato" senza interrogare il servizio.
                            Result.success<Pair<Track?, Double?>>(null to MISS_SCORE)
                        } else {
                            // Slot condivisi fra le sync in corso verso lo stesso servizio: due sync insieme
                            // non raddoppiano la pressione, se la dividono.
                            val slots = searchSlots.getOrPut(dst.id) { kotlinx.coroutines.sync.Semaphore(parallel) }
                            slots.withPermit {
                                runCatching { searchScored(dst, s, policy) }.map { it?.track to it?.score }
                                    .also { if (dst.searchSpacingMs > 0) kotlinx.coroutines.delay(dst.searchSpacingMs) }
                            }
                        }
                    }
                }.awaitAll()
            }
            // Il servizio chiede una pausa (403/429 dopo molte ricerche): e' un limite suo, non un
            // guasto. Si aspetta in modo visibile, sempre piu' a lungo, e si riprova lo stesso gruppo.
            val throttled = results.firstNotNullOfOrNull { r -> (r.exceptionOrNull() as? ProviderException)?.takeIf { isThrottle(it.message) } }
            // Un blocco dichiarato dal servizio ("retry in N s") si rispetta alla lettera: e' l'unico
            // modo perche' scada. Le altre pause seguono la scala crescente.
            val asked = declaredWait(throttled?.message)
            // A pause of hours (Spotify on a first big sync): waiting it out in 30-minute rounds looked
            // stuck. The sync stops now and says until when; what was matched so far is kept.
            val askedRaw = declaredSeconds(throttled?.message)
            // Apple's web backend answers a long run of searches with 429 "Request is forbidden" and
            // keeps refusing for an hour or more (#46, #47): no short wait gets past it. Stop at once and
            // say it is the service's limit; what was matched so far is kept.
            if (throttled != null && isLongBlock(throttled.message)) {
                saveMisses()
                val until = java.time.LocalTime.now().plusSeconds(LONG_BLOCK_SECONDS).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                val msg = env.text("service_search_limit", dst.displayName, until)
                env.log("engine", "${dst.displayName} refuses searches for a while (${throttled.message?.take(120)}): sync stopped, try after $until")
                if (done <= 0) throw ProviderException(msg)
                return msg
            }
            if (throttled != null && askedRaw != null && askedRaw > LONG_PAUSE_SECONDS) {
                saveMisses()
                val until = java.time.LocalTime.now().plusSeconds(askedRaw.toLong()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                val msg = env.text("service_pause_until", dst.displayName, until)
                env.log("engine", "${dst.displayName} asks for a pause of ${askedRaw}s (until $until): sync stopped")
                if (done <= 0) throw ProviderException(msg)
                return msg
            }
            if (throttled != null && asked != null && blockWaits < MAX_BLOCK_WAITS) {
                blockWaits++
                env.log("engine", "${dst.displayName} is blocked: ${throttled.message?.take(200)}; waiting ${asked}s")
                waitVisible(asked, onProgress)
                continue
            }
            if (throttled != null && asked == null && waits < WAIT_SECONDS.size) {
                val seconds = WAIT_SECONDS[waits++]
                env.log("engine", "${dst.displayName} is throttling: ${throttled.message?.take(200)}; waiting ${seconds}s")
                sinceWait = 0
                for (s in 1..seconds) {
                    onProgress(Progress(Progress.Step.WAITING, s, seconds))
                    kotlinx.coroutines.delay(1000)
                }
                continue
            }
            if (throttled == null && ++sinceWait >= RESET_AFTER_CHUNKS) { waits = 0; sinceWait = 0 }
            ci++
            chunk.forEachIndexed { i, s ->
                done++
                onProgress(Progress(Progress.Step.MATCHING, done, toSearch.size))
                val r = results[i]
                val e = r.exceptionOrNull()
                if (e != null) {
                    if (e !is ProviderException) throw e
                    consecutiveErrors++
                    errors++
                    val msg = e.message ?: ""
                    // Said in the log, the first few of a run: a search that fails is otherwise only a
                    // "not found", and a service refusing every search looked like a catalogue without the songs.
                    if (errors <= 5) env.log("engine", "${dst.displayName}: search failed for \"${listOfNotNull(s.artists.firstOrNull(), s.title).joinToString(" – ")}\": ${msg.take(200)}")
                    // Errori sistemici (quota, limiti, sessione scaduta): inutile insistere. Se non e'
                    // stato trovato ancora nulla l'errore e' dell'intera sync; altrimenti ci si ferma qui.
                    // Also when failures are a good share of the real searches, even if not in a row:
                    // songs remembered from earlier runs, answered in between, used to hide them.
                    val systemic = consecutiveErrors >= 3 || (errors >= 10 && errors * 3 > searchedHere + errors) ||
                        msg.contains("quota", true) || msg.contains("reconnect", true) ||
                        msg.contains("sign in again", true) || isThrottle(msg)
                    if (systemic) {
                        saveMisses()
                        env.log("engine", "${dst.displayName}: search stopped after $searchedHere searches and $errors failures: ${msg.take(200)}")
                        if (done - chunk.size + i <= 0) throw e
                        return msg
                    }
                    onResult(s, null, null)
                } else {
                    val (found, score) = r.getOrThrow()
                    // Only a search the service answered clears the run of failures: a song remembered
                    // from earlier runs (or a recent miss) asked nothing of it.
                    val remembered = (found != null && score == null) || score == MISS_SCORE
                    if (!remembered) { consecutiveErrors = 0; searchedHere++ }
                    // Same account or same catalogue: taken as it is, not a search (the log said "1328 searches in 0s").
                    if (direct && s.id.isNotBlank()) takenAsIs++
                    else if (score != MISS_SCORE && store.cachedMatch(src.id, s.id, dst.id) == null) searched++
                    if (found == null && score != MISS_SCORE) misses += FileStore.cacheKey(src.id, s.id, dst.id)
                    if (misses.size >= 50) saveMisses()
                    onResult(s, found, if (score == MISS_SCORE) null else score)
                }
            }
        }
        saveMisses()
        if (takenAsIs > 0) env.log("engine", "${dst.displayName}: $takenAsIs ${tracksWord(takenAsIs)} taken as they are, no search")
        if (searched > 0) {
            val secs = (System.currentTimeMillis() - startedAt) / 1000
            env.log("engine", "${dst.displayName}: $searched searches in ${secs}s (${if (searched > 0) secs * 1000 / searched else 0} ms each), $waits waits" + if (errors > 0) ", $errors failed" else "")
        }
        return null
    }

    /**
     * Cerca [s] sulla destinazione e ne tiene il miglior candidato sopra soglia. La prima query e' quella
     * del servizio (artisti e titolo cosi' come sono); se non basta si riprova con il titolo pulito e il
     * primo artista, poi con il solo titolo: alcuni cataloghi rispondono meglio a query corte, e i
     * candidati sono comunque valutati contro l'artista originale, quindi un omonimo non passa.
     */
    /**
     * The target in the source's order: each source track maps to the target track the cache holds
     * for it, in source order; what the target has beyond the source stays at the end. One read of
     * the target, then the service's own moves. A failure is a note, never a failed sync.
     */
    private suspend fun alignOrder(src: MusicProvider, dst: MusicProvider, targetId: String, sourceOrder: List<String>, notes: MutableList<String>, onProgress: (Progress) -> Unit) {
        try {
            onProgress(Progress(Progress.Step.FETCH_TARGET))
            val current = dst.tracks(env, targetId)
            if (current.size > MAX_REORDER) { notes += str("note_order_failed", "${current.size} > $MAX_REORDER"); return }
            val byId = current.groupBy { it.id }
            val seen = HashSet<String>()
            val wanted = ArrayList<Track>()
            for (sid in sourceOrder) {
                val tid = store.cachedMatch(src.id, sid, dst.id) ?: continue
                if (!seen.add(tid)) continue
                byId[tid]?.firstOrNull()?.let { wanted += it }
            }
            wanted += current.filter { it.id !in seen }
            if (wanted.size != current.size) return
            if (wanted.map { it.id } == current.map { it.id }) return
            dst.reorderTracks(env, targetId, current, wanted)
            notes += str("note_order_aligned")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            notes += str("note_order_failed", e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun searchScored(dst: MusicProvider, s: Track, policy: MatchPolicy): Matcher.Scored? {
        var runnerUp: Matcher.Scored? = null
        var sawCandidates = false
        fun consider(found: List<Track>): Matcher.Scored? {
            if (found.isNotEmpty()) sawCandidates = true
            val best = Matcher.bestScored(s, found, 0.0, policy) ?: return null
            if (best.score >= policy.acceptThreshold) return best
            if (best.score > (runnerUp?.score ?: 0.0)) runnerUp = best
            return null
        }
        try {
            // No ISRC on the source (web routes, files) but a target that searches by it: ask the
            // catalogue oracle once, and the first search asks for the exact recording.
            val withIsrc = if (s.isrc == null && s.kind.isEmpty() && dst.searchesByIsrc && dst.serviceId != "deezer" && store.data.settings.isrcLookup)
                IsrcOracle.find(env, s)?.let { s.copy(isrc = it) } ?: s else s
            consider(dst.search(env, withIsrc))?.let { return it }
            val tried = hashSetOf(query(s))
            val title = Matcher.searchTitle(s.title)
            val first = s.artists.firstOrNull()?.let { Matcher.searchArtist(it) }?.takeIf { it.isNotBlank() }
            // "Calvin Harris, Dua Lipa" in one artist field: the first name alone, before the title alone.
            val primary = s.artists.firstOrNull()?.let { Matcher.primaryArtist(it) }?.let { Matcher.searchArtist(it) }?.takeIf { it.isNotBlank() }
            val variants = listOfNotNull(
                s.copy(title = title, artists = listOfNotNull(first), isrc = null),
                primary?.let { s.copy(title = title, artists = listOf(it), isrc = null) },
                s.copy(title = title, artists = emptyList(), isrc = null),
            )
            for (v in variants) {
                if (!tried.add(query(v))) continue
                consider(dst.search(env, v))?.let { return it }
            }
            // Last resort, where the service has one: a wider catalogue (YouTube videos for YouTube Music).
            if (s.kind.isEmpty()) consider(dst.searchWide(env, s))?.let { return it }
            return null
        } finally {
            // Not found, but something came close: the review offers it as a one-tap proposal.
            runnerUp?.takeIf { it.score >= HINT_THRESHOLD }?.let { hints[s.id] = it.track }
            if (!sawCandidates) absent += s.id else absent -= s.id
        }
    }

    private fun suggestionsFor(unmatched: List<Track>): Map<String, Track> =
        unmatched.mapNotNull { t -> hints[t.id]?.let { t.id to it } }.toMap()

    private fun query(t: Track): String = (t.artists.take(2) + t.title).joinToString(" ").lowercase().trim()

    /**
     * Esegue una chiamata al servizio rispettando i blocchi che dichiara ("Retry in N s"): aspetta il
     * tempo detto, visibilmente, e riprova, fino al limite; ogni altro errore passa oltre subito.
     */
    private suspend fun <T> patient(onProgress: (Progress) -> Unit, block: suspend () -> T): T {
        var blockWaits = 0
        var networkRetries = 0
        while (true) {
            try {
                return block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val asked = declaredWait(e.message)
                when {
                    asked != null && blockWaits < MAX_BLOCK_WAITS -> {
                        blockWaits++
                        env.log("engine", "blocked: ${e.message?.take(160)}; waiting ${asked}s")
                        waitVisible(asked, onProgress)
                    }
                    // Rete assente per un attimo (risveglio programmato, cambio Wi-Fi/dati): non e' il
                    // servizio, si riprova con attese crescenti.
                    isTransientNetwork(e.message) && networkRetries < NETWORK_RETRY_SECONDS.size -> {
                        val seconds = NETWORK_RETRY_SECONDS[networkRetries++]
                        env.log("engine", "network hiccup: ${e.message?.take(120)}; retrying in ${seconds}s")
                        waitVisible(seconds, onProgress)
                    }
                    else -> throw e
                }
            }
        }
    }

    /** "Artista – Titolo", come nella vista dal vivo della fase di ricerca. */
    private fun Track.live(): String = listOfNotNull(artists.firstOrNull(), title).joinToString(" – ")

    /** The wait the service declared ("retry in N s"), as it is; null if it says none. */
    private fun declaredSeconds(msg: String?): Int? =
        Regex("retry in (\\d+) s", RegexOption.IGNORE_CASE).find(msg ?: "")?.groupValues?.get(1)?.toIntOrNull()

    /** Secondi di attesa dichiarati dal servizio ("Retry in N s"), entro limiti ragionevoli; null se non li dichiara. */
    private fun declaredWait(msg: String?): Int? =
        Regex("retry in (\\d+) s", RegexOption.IGNORE_CASE).find(msg ?: "")?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(5, 1800)

    /** Attesa mostrata all'utente secondo per secondo; lo stop la interrompe, la pausa no. */
    private suspend fun waitVisible(seconds: Int, onProgress: (Progress) -> Unit) {
        for (s in 1..seconds) {
            onProgress(Progress(Progress.Step.WAITING, s, seconds))
            kotlinx.coroutines.delay(1000)
        }
    }

    /** A refusal that lasts an hour or more (Apple's web backend: 429 "Request is forbidden"). */
    private fun isLongBlock(msg: String?): Boolean = msg?.contains("Request is forbidden", ignoreCase = true) == true

    /** Il servizio sta limitando le richieste (non un errore dell'app). */
    private fun isThrottle(msg: String?): Boolean {
        val m = msg ?: return false
        return m.contains("403") || m.contains("429") || m.contains("too many", true) || m.contains("rate limit", true) ||
            m.contains("refused the request", true)
    }

    /** Applica un piano: aggiunte, rimozioni, cache e report. */
    suspend fun apply(job: SyncJob, plan: SyncPlan, started: Long, reportId: String, onProgress: (Progress) -> Unit = {}): SyncReport {
        val (src, dst) = providers(job)
        val targetId = plan.targetPlaylistId.ifEmpty { throw ProviderException("No target playlist") }
        val unmatched = ArrayList(plan.unmatched)
        val failed = ArrayList<String>()
        val notes = ArrayList(plan.notes)

        var added = 0
        val addedList = ArrayList<Track>()
        // Refused by the service as already in the playlist (its listing left them out): present, not added.
        val alreadyThere = ArrayList<Track>()
        // Every addition refused, one by one, from the start (a path the service closed, a scope the
        // sign-in lacks): the service, not the tracks. Adding stops and the run fails with its answer,
        // instead of listing every track as not found (issue #34).
        var addStopped: String? = null
        var singleFails = 0
        if (plan.toAdd.isNotEmpty()) {
            onProgress(Progress(Progress.Step.ADDING, 0, plan.toAdd.size))
            // Verso una playlist un blocco di 50 e' una chiamata sola. Verso i "brani preferiti" ogni
            // brano e' una chiamata a parte: blocchi piccoli, cosi' l'avanzamento si muove ogni pochi
            // brani invece di restare fermo per minuti.
            val chunkSize = if (MusicProvider.isLibrary(targetId) && job.keepOrder) 10 else 50
            var blockWaits = 0
            // I "brani preferiti" mostrano per primo l'ultimo aggiunto: si aggiunge dall'ultimo al primo,
            // cosi' l'ordine dell'origine (il piu' recente in cima) si ritrova uguale nella destinazione.
            val additions = if (MusicProvider.isLibrary(targetId)) plan.toAdd.asReversed() else plan.toAdd
            // Keeping the order, a service that orders by the second of saving (Spotify's liked songs)
            // saves one track at a time; without it, in batches at full speed (MusicProvider.addsInOrder).
            kotlinx.coroutines.withContext(if (job.keepOrder) MusicProvider.OrderedAdds else kotlin.coroutines.EmptyCoroutineContext) {
            for (chunk in additions.chunked(chunkSize)) {
                if (addStopped != null) break
                // Cosa si sta aggiungendo adesso, in scheda e nella vista dal vivo, come per la ricerca.
                onProgress(Progress(Progress.Step.ADDING, added + alreadyThere.size, plan.toAdd.size, chunk.first().live()))
                while (true) {
                    try {
                        val present = dst.addTracksReportingPresent(env, targetId, chunk)
                        val (there, fresh) = chunk.partition { it.id in present }
                        added += fresh.size
                        addedList += fresh
                        alreadyThere += there
                        fresh.forEach { SyncState.item(job.id, SyncState.LiveItem(it.live(), null, SyncState.Outcome.ADDED)) }
                        there.forEach { SyncState.item(job.id, SyncState.LiveItem(it.live(), it.live(), SyncState.Outcome.CACHED)) }
                        break
                    } catch (e: Exception) {
                        // Il servizio ha bloccato l'indirizzo e dice quanto aspettare: si aspetta, come
                        // nella ricerca, invece di dare per falliti brani che sono solo in attesa.
                        val asked = declaredWait(e.message)
                        if (asked != null && blockWaits < MAX_BLOCK_WAITS) {
                            blockWaits++
                            env.log("engine", "${dst.displayName} is blocked while adding: ${e.message?.take(160)}; waiting ${asked}s")
                            waitVisible(asked, onProgress)
                            continue
                        }
                        // Un blocco fallito (id non piu' valido, ecc.): riprova brano per brano.
                        for (t in chunk) {
                            if (addStopped != null) break
                            try {
                                if (t.id in dst.addTracksReportingPresent(env, targetId, listOf(t))) {
                                    alreadyThere += t
                                    SyncState.item(job.id, SyncState.LiveItem(t.live(), t.live(), SyncState.Outcome.CACHED))
                                } else {
                                    added++; addedList += t
                                    SyncState.item(job.id, SyncState.LiveItem(t.live(), null, SyncState.Outcome.ADDED))
                                }
                                singleFails = 0
                            } catch (e2: Exception) {
                                failed += "$t (${e2.message})"
                                SyncState.item(job.id, SyncState.LiveItem(t.live(), e2.message?.take(80), SyncState.Outcome.ADD_FAILED))
                                singleFails++
                                if (singleFails >= SYSTEMIC_ADD_FAILURES && added == 0 && alreadyThere.isEmpty()) {
                                    addStopped = e2.message ?: e2.javaClass.simpleName
                                    env.log("engine", "${job.name}: adding stopped, ${dst.displayName} refused the first $singleFails tracks one by one: ${addStopped?.take(300)}")
                                }
                            }
                        }
                        break
                    }
                }
                onProgress(Progress(Progress.Step.ADDING, added + alreadyThere.size, plan.toAdd.size))
            }
            }
        }
        if (alreadyThere.isNotEmpty()) {
            store.putPresentUnlisted(dst.id, targetId, alreadyThere.map { it.id })
            env.log("engine", "${job.name}: ${alreadyThere.size} tracks already on ${dst.displayName} but missing from its playlist listing: " +
                alreadyThere.take(20).joinToString("; ") + if (alreadyThere.size > 20) "; …" else "")
        }

        var removed = 0
        var removedList: List<Track> = emptyList()
        if (plan.toRemove.isNotEmpty()) {
            onProgress(Progress(Progress.Step.REMOVING, 0, plan.toRemove.size, plan.toRemove.first().live()))
            var blockWaits = 0
            var kept: Set<String> = emptySet()
            while (true) {
                try {
                    kept = dst.removeTracksReportingKept(env, targetId, plan.toRemove)
                    break
                }
                catch (e: Exception) {
                    val asked = declaredWait(e.message)
                    if (asked != null && blockWaits < MAX_BLOCK_WAITS) { blockWaits++; waitVisible(asked, onProgress); continue }
                    throw e
                }
            }
            val (stayed, gone) = plan.toRemove.partition { it.id in kept }
            gone.forEach { SyncState.item(job.id, SyncState.LiveItem(it.live(), null, SyncState.Outcome.REMOVED)) }
            removedList = gone
            removed = gone.size
            if (gone.isNotEmpty()) env.log("engine", "${job.name}: ${gone.size} ${tracksWord(gone.size)} removed from ${dst.displayName}: ${named(gone)}")
            if (stayed.isNotEmpty()) {
                // Still listed after a removal the service answered: counted as nothing removed, and left alone for a while.
                store.putKeptOnRemoval(dst.id, targetId, stayed.map { it.id })
                env.log("engine", "${job.name}: ${dst.displayName} keeps ${stayed.size} ${tracksWord(stayed.size)} that removing does not take away: ${named(stayed)}")
            }
        }
        // What this run wrote, for the next one to check against the target's listing.
        store.putLastWrites(job.id, LastWrites(targetId, addedList.map { it.id }.toSet(), removedList.map { it.id }.toSet(), System.currentTimeMillis()))

        store.putMatches(plan.newMatches)
        // Quanto l'utente ha gia' sistemato dal report provvisorio non deve ricomparire.
        val provisional = store.report(reportId)?.takeIf { it.partial }
        if (provisional != null) {
            val stillOpen = provisional.unmatchedTracks.map { it.id }.toHashSet()
            unmatched.retainAll { it.id in stillOpen }
        }
        if (job.keepOrder && !job.archive && dst.canReorder && !MusicProvider.isLibrary(targetId) && plan.sourceOrder.isNotEmpty()) {
            alignOrder(src, dst, targetId, plan.sourceOrder, notes, onProgress)
        }
        val reviews = provisional?.reviewTracks ?: plan.uncertain
        return SyncReport(
            id = reportId, jobId = job.id, jobName = job.name, startedEpoch = started,
            durationMs = System.currentTimeMillis() - started,
            sourceCount = plan.sourceCount, targetCount = plan.targetCount, targetId = plan.targetPlaylistId, added = added, removed = removed,
            unmatched = unmatched.map { it.toString() } + failed,
            unmatchedTracks = unmatched,
            ignored = plan.ignored,
            reviewTracks = reviews,
            removedTracks = removedList,
            // Favourites are added last-first: listed back in the source's order.
            addedTracks = (if (MusicProvider.isLibrary(targetId)) addedList.asReversed() else addedList).take(MAX_LISTED),
            notes = notes,
            error = addStopped?.let { "${dst.displayName}: $it" },
            suggestions = suggestionsFor(unmatched),
            absent = unmatched.filter { it.id in absent }.map { it.id },
            heldBack = plan.heldBackRemovals,
            searchInterrupted = plan.searchInterrupted,
            sourceEmpty = plan.sourceCount == 0 && plan.ignored == 0,
        )
    }

    // ------------------------------------------------------------------ revisione abbinamenti

    /** L'abbinamento incerto va bene: resta in cache, sparisce dalla lista da rivedere. */
    fun confirmMatch(reportId: String, review: MatchReview) = confirmMatches(reportId, listOf(review))

    /** [confirmMatch] for several uncertain matches at once. */
    fun confirmMatches(reportId: String, reviews: Collection<MatchReview>) {
        val ids = reviews.map { it.source.id }.toHashSet()
        if (ids.isEmpty()) return
        store.updateReport(reportId) { r -> r.copy(reviewTracks = r.reviewTracks.filter { it.source.id !in ids }) }
    }

    /**
     * Uncertain matches that are wrong, with no replacement wanted: the chosen tracks leave the
     * target (where its API allows removals) and the source tracks stay out of every future sync
     * to that service, as "Ignore" does for a track that was not found. A target track that another
     * source track still matches stays. Returns how many tracks were taken off the target.
     */
    suspend fun discardMatches(job: SyncJob, reportId: String?, reviews: Collection<MatchReview>): Int {
        if (reviews.isEmpty()) return 0
        val (src, dst) = providers(job)
        val targetId = job.target.playlistId ?: throw ProviderException("No target playlist")
        val leaving = reviews.map { it.source.id }.toHashSet()
        var removed = 0
        if (dst.canRemoveTracks) {
            val wrong = reviews.map { it.chosen.id }.filter { id ->
                store.sourcesMatchedTo(src.id, dst.id, id).all { it in leaving }
            }.toHashSet()
            if (wrong.isNotEmpty()) {
                // The tracks read from the search carry no playlist item id: take them from the target.
                val present = dst.tracks(env, targetId).filter { it.id in wrong }
                if (present.isNotEmpty()) {
                    dst.removeTracks(env, targetId, present)
                    removed = present.size
                }
            }
        }
        ignoreAll(job, src.id, dst.id, leaving)
        if (reportId != null) confirmMatches(reportId, reviews)
        return removed
    }

    /**
     * L'abbinamento era sbagliato: toglie il brano errato dalla destinazione (dove l'API lo
     * permette), aggiunge quello scelto e corregge la cache per le sync future.
     */
    suspend fun replaceMatch(job: SyncJob, reportId: String?, review: MatchReview, chosen: Track) {
        val (src, dst) = providers(job)
        val targetId = job.target.playlistId ?: throw ProviderException("No target playlist")
        if (dst.canRemoveTracks) {
            // Il brano errato letto dalla ricerca non ha l'itemId di playlist: lo si ripesca dalla destinazione.
            val present = dst.tracks(env, targetId).firstOrNull { it.id == review.chosen.id }
            if (present != null) dst.removeTracks(env, targetId, listOf(present))
        }
        dst.addTracks(env, targetId, listOf(chosen))
        store.putMatches(mapOf(FileStore.cacheKey(src.id, review.source.id, dst.id) to chosen.id))
        if (reportId != null) confirmMatch(reportId, review)
    }

    /** In anteprima non e' stato ancora scritto nulla: basta correggere la cache. */
    fun rematchInPlan(job: SyncJob, review: MatchReview, chosen: Track) {
        val (src, dst) = providers(job)
        store.putMatches(mapOf(FileStore.cacheKey(src.id, review.source.id, dst.id) to chosen.id))
    }

    /** Rimette nella destinazione i brani tolti da una sync a specchio. */
    suspend fun restoreRemoved(job: SyncJob, report: SyncReport) {
        val (_, dst) = providers(job)
        val targetId = job.target.playlistId ?: throw ProviderException("No target playlist")
        if (report.removedTracks.isEmpty()) return
        dst.addTracks(env, targetId, report.removedTracks)
        // Put back by the user: not a track the service kept after a removal (see plan()).
        val back = report.removedTracks.map { it.id }.toSet()
        store.lastWrites(job.id)?.let { w -> if (w.removed.any { it in back }) store.putLastWrites(job.id, w.copy(removed = w.removed - back)) }
        store.updateReport(report.id) { r ->
            r.copy(removedTracks = emptyList(), notes = r.notes + str("restored_note", report.removedTracks.size))
        }
    }

    // ------------------------------------------------------------------ risoluzione manuale

    /**
     * Ricerca manuale sulla destinazione per una query libera ("Artista - Titolo", solo titolo, o un link).
     * Con il brano d'origine [source] ogni risultato ha un punteggio di somiglianza (titolo, artista,
     * durata) e una provenienza: il catalogo dei brani, l'album del brano d'origine (una seconda
     * ricerca "artista album", quando l'album e' noto), o il catalogo largo (i video di YouTube), che
     * si interroga solo se i brani non hanno dato nulla di convincente. Le edizioni ripetute di uno
     * stesso brano compaiono una volta sola.
     */
    suspend fun searchOnTarget(job: SyncJob, query: String, source: Track? = null): TargetSearch {
        val (_, dst) = providers(job)
        return searchOn(dst, job.target.playlistId, job.policy, query, source)
    }

    /** The same search on any service [dst], for a playlist [playlistId] (its kind decides what is searched) under [policy]. */
    suspend fun searchOn(dst: MusicProvider, playlistId: String?, policy: MatchPolicy, query: String, source: Track? = null): TargetSearch {
        fun hit(t: Track, kind: TargetSearch.Kind) = TargetSearch.Hit(t, if (source != null) Matcher.score(source, t, policy) else 0.0, kind)
        // A Shazam share names the song but lives on no service: search for what it names.
        if (ShazamShare.isShazam(query)) ShazamShare.track(query)?.let { return searchOn(dst, playlistId, policy, ShazamShare.query(it), source) }
        // A TikTok video: the song its page names. The creator's own sound names none.
        if (TikTokShare.isTikTok(query)) {
            return when (val sound = TikTokShare.sound(query)) {
                is TikTokShare.Sound.Song -> searchOn(dst, playlistId, policy, TikTokShare.query(sound.track), source ?: sound.track)
                is TikTokShare.Sound.Original -> throw ProviderException(str("tiktok_original_sound"))
                TikTokShare.Sound.Unknown -> throw ProviderException(str("tiktok_unreadable"))
            }
        }
        // Il link del brano incollato: si aggiunge quello, letto dal servizio quando possibile. I link
        // brevi delle app (amzn.eu, spotify.link...) si seguono fino all'indirizzo vero.
        val kind = source?.kind?.ifEmpty { null } ?: MusicProvider.libraryKind(playlistId).orEmpty()
        (TrackLinks.parse(query) ?: resolveShortLink(query)?.let { TrackLinks.parse(it) })?.let { ref0 ->
            // A song's link on an album or artist row (saved albums, followed artists): the song is
            // not what goes there, its album or its artist is. They are looked up by name on the
            // target; adding the song's own id as an album failed on the service (YouTube Music: 400).
            if (kind == Track.KIND_ALBUM || kind == Track.KIND_ARTIST) {
                val song = (if (TrackLinks.matches(ref0.service, dst.serviceId)) runCatching { dst.track(env, ref0.trackId) }.getOrNull() else null)
                    ?: TrackLookup.describe(ref0, query)
                    ?: throw ProviderException(str("link_song_not_album", dst.label(env)))
                val wanted = if (kind == Track.KIND_ARTIST) Track(id = "", title = song.artists.firstOrNull().orEmpty(), kind = kind)
                    else Track(id = "", title = song.album.ifBlank { song.title }, artists = song.artists.take(1), kind = kind)
                if (wanted.title.isBlank()) throw ProviderException(str("link_song_not_album", dst.label(env)))
                val found = runCatching { dst.search(env, wanted) }.getOrDefault(emptyList())
                if (found.isEmpty()) throw ProviderException(str("link_song_not_album", dst.label(env)))
                return TargetSearch(dedupe(found).map { hit(it, TargetSearch.Kind.SONG) })
            }
            // A link from another service: song.link knows the same recording on the target service.
            val ref = if (TrackLinks.matches(ref0.service, dst.serviceId)) ref0
                else TrackLinks.viaOdesli(query, dst.serviceId)
                    ?: throw ProviderException(str("link_other_service", dst.label(env)))
            val t = runCatching { dst.track(env, ref.trackId) }.getOrNull()
            return TargetSearch(listOf(hit(t ?: dst.rehydrate(Track(id = ref.trackId, title = str("link_track_unknown"), album = ref.trackId)), TargetSearch.Kind.SONG)))
        }
        // An artist is searched by name alone: "Artist - Title" splitting would cut a name with a dash.
        val (artists, title) = if (kind == Track.KIND_ARTIST) emptyList<String>() to query.trim() else PlaylistFiles.splitArtistTitle(query)
        var songs = dst.search(env, Track(id = "", title = title, artists = artists, kind = kind))
        // "Artista - Titolo" senza un candidato convincente: il solo titolo a volte lo trova.
        fun convincing() = source == null || Matcher.bestScored(source, songs, policy = policy) != null
        if (artists.isNotEmpty() && (songs.isEmpty() || !convincing())) {
            songs = (songs + dst.search(env, Track(id = "", title = title, kind = kind))).distinctBy { it.id }
        }
        val hits = ArrayList<TargetSearch.Hit>()
        val seen = HashSet<String>()
        fun add(tracks: List<Track>, kind: TargetSearch.Kind) = dedupe(tracks).filter { seen.add(it.id) }.forEach { hits += hit(it, kind) }
        var album: String? = null
        var albumFound: Boolean? = null
        if (source != null && kind.isEmpty()) {
            source.album.trim().takeIf { it.isNotEmpty() }?.let { name ->
                val byAlbum = runCatching { dst.search(env, Track(id = "", title = name, artists = source.artists.take(1))) }.getOrDefault(emptyList())
                // Un servizio che non riporta l'album nei risultati non permette di dire se il disco c'e'.
                if ((byAlbum + songs).any { it.album.isNotBlank() }) {
                    val wanted = Matcher.normalizeTitle(name)
                    val ofAlbum = (byAlbum + songs).filter {
                        it.album.isNotBlank() && Matcher.similarity(Matcher.normalizeTitle(it.album), wanted) >= 0.8 &&
                            (source.artists.isEmpty() || Matcher.artistScore(source.artists, it.artists) >= 0.7)
                    }
                    album = name
                    albumFound = ofAlbum.isNotEmpty()
                    add(ofAlbum, TargetSearch.Kind.ALBUM)
                }
            }
        }
        add(songs, TargetSearch.Kind.SONG)
        if (source != null && kind.isEmpty() && !convincing()) {
            val wide = runCatching { dst.searchWide(env, source.copy(title = title, artists = artists.ifEmpty { source.artists })) }.getOrDefault(emptyList())
            add(wide, TargetSearch.Kind.VIDEO)
        }
        return TargetSearch(if (source != null) hits.sortedByDescending { it.score } else hits, album, albumFound)
    }

    /** Segue i redirect di un link breve condiviso da un'app musicale; null se non e' uno di quelli. */
    private suspend fun resolveShortLink(text: String): String? {
        val url = Regex("""https?://\S+""").find(text)?.value ?: return null
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return null
        if (host !in SHORT_LINK_HOSTS) return null
        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                // The User-Agent keeps "Songport" after the rename to Trackhop: an identifier the hosts already see.
                val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) Songport").build()
                com.xlollx.songport.net.Http.client.newCall(req).execute().use { it.request.url.toString() }
            }.getOrNull()
        }
    }

    /** Una riga per titolo e artisti: le edizioni ripetute di uno stesso brano non aiutano a scegliere. */
    private fun dedupe(tracks: List<Track>): List<Track> {
        val seen = HashSet<String>()
        return tracks.filter { seen.add(Matcher.normalizeTitle(it.title) + "|" + it.artists.map { a -> Matcher.normalizeArtist(a) }.sorted().joinToString(",")) }
    }

    /**
     * L'utente ha scelto a mano il brano giusto: lo aggiunge alla destinazione, lo mette in cache
     * (cosi' le sync future lo riconoscono) e lo toglie dai non trovati del report.
     */
    suspend fun resolveManually(job: SyncJob, reportId: String, sourceTrack: Track, chosen: Track) {
        val (src, dst) = providers(job)
        val targetId = job.target.playlistId ?: throw ProviderException("No target playlist")
        dst.addTracks(env, targetId, listOf(chosen))
        store.putMatches(mapOf(FileStore.cacheKey(src.id, sourceTrack.id, dst.id) to chosen.id))
        dropUnmatched(reportId, sourceTrack)
    }

    /**
     * L'utente non vuole piu' vedere questo brano: e' assente dalla destinazione, e lo resta per ogni
     * sync verso quel servizio, non solo per questa (come gli abbinamenti scelti a mano).
     */
    fun ignore(job: SyncJob, reportId: String, sourceTrack: Track) = ignore(job, reportId, listOf(sourceTrack))

    /** [ignore] for several tracks that were not found, at once. */
    fun ignore(job: SyncJob, reportId: String, sourceTracks: Collection<Track>) {
        if (sourceTracks.isEmpty()) return
        val ids = sourceTracks.map { it.id }.toHashSet()
        val (src, dst) = runCatching { providers(job) }.getOrNull() ?: (null to null)
        ignoreAll(job, src?.id, dst?.id, ids)
        dropUnmatched(reportId, sourceTracks)
    }

    /** Out of every future sync of [job] (and, with the services known, of every sync between them). */
    private fun ignoreAll(job: SyncJob, srcId: String?, dstId: String?, ids: Set<String>) {
        // The job as stored now: the caller's copy may be older than another change.
        val current = store.job(job.id) ?: job
        store.upsertJob(current.copy(ignoredSourceIds = (current.ignoredSourceIds + ids).distinct()))
        if (srcId != null && dstId != null) store.putIgnored(srcId, ids, dstId)
    }

    private fun dropUnmatched(reportId: String, tracks: Collection<Track>) {
        val ids = tracks.map { it.id }.toHashSet()
        val names = tracks.map { it.toString() }.toHashSet()
        store.updateReport(reportId) { r ->
            r.copy(
                unmatchedTracks = r.unmatchedTracks.filter { it.id !in ids },
                unmatched = r.unmatched.filter { it !in names },
                suggestions = r.suggestions - ids,
            )
        }
    }

    private fun dropUnmatched(reportId: String, track: Track) = dropUnmatched(reportId, listOf(track))
}
