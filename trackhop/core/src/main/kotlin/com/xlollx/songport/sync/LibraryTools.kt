package com.xlollx.songport.sync

import com.xlollx.songport.providers.*
import com.xlollx.songport.model.MatchPolicy
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.model.UnplayableEntry
import com.xlollx.songport.model.UnplayableFix
import com.xlollx.songport.model.UnplayableSwap
import com.xlollx.songport.platform.Env
import kotlinx.serialization.encodeToString
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider

/**
 * Utilita' che i concorrenti tengono dietro un abbonamento o non hanno affatto: backup completo di
 * un servizio in file locali, pulizia dei brani doppi, brani non piu' riproducibili, playlist nuove
 * da una lista. Senza Android: le usano l'app (sync/Tools.kt) e la pagina web del server.
 */
object LibraryTools {

    /** A playlist the backup could not read, and the service's own explanation. */
    data class Failure(val name: String, val reason: String)

    data class BackupResult(val playlists: Int, val tracks: Int, val failures: List<Failure>, val unchanged: Int = 0) {
        val failed: List<String> get() = failures.map { "${it.name} (${it.reason})" }
    }

    /**
     * Salva tutte le playlist (e i preferiti, se il servizio li espone) come file CSV locali,
     * esportabili dalla scheda File. Non tocca nulla sul servizio.
     */
    suspend fun backupAll(ctx: Env, provider: MusicProvider, onProgress: (Progress) -> Unit = {}): BackupResult {
        // Liked songs, saved albums and followed artists come first: they are the library itself.
        val lists = (provider.libraryEntries(ctx, asTarget = false).filter { it.id != MusicProvider.RECENT_ID } + provider.playlists(ctx)).toMutableList()
        var tracks = 0
        var done = 0
        var unchanged = 0
        val failed = ArrayList<Failure>()
        val inUse = LocalFilesProvider.protectedIds(ctx)
        val now = System.currentTimeMillis()
        for (pl in lists) {
            onProgress(Progress(Progress.Step.BACKUP, done, lists.size))
            try {
                val items = provider.tracks(ctx, pl.id)
                // One dated version per run; a playlist that has not changed since the last backup
                // is not written again, so the Files screen holds versions, not copies.
                if (LocalFilesProvider.writeBackup(ctx, provider.displayName, pl.name, items, inUse, now) == LocalFilesProvider.BackupOutcome.UNCHANGED) unchanged++
                tracks += items.size
            } catch (e: Exception) {
                failed += Failure(pl.name, e.message ?: e.javaClass.simpleName)
            }
            done++
        }
        return BackupResult(lists.size - failed.size, tracks, failed, unchanged)
    }

    /** Brani doppi in una playlist, raggruppati (il primo di ogni gruppo e' quello da tenere). */
    suspend fun findDuplicates(ctx: Env, provider: MusicProvider, playlistId: String): List<List<Track>> =
        Duplicates.groups(provider.tracks(ctx, playlistId))

    /**
     * Toglie i doppi lasciando la prima occorrenza. Su servizi che rimuovono per id (Spotify,
     * Deezer) togliere un id elimina TUTTE le sue copie: in quel caso si toglie e si riaggiunge
     * una volta. Dove la rimozione e' per elemento di playlist (YouTube, TIDAL) basta l'itemId.
     */
    suspend fun removeDuplicates(ctx: Env, provider: MusicProvider, playlistId: String, onProgress: (Progress) -> Unit = {}): Int {
        if (!provider.canRemoveTracks) throw ProviderException(ctx.text("error_no_removals", provider.displayName))
        val groups = Duplicates.groups(provider.tracks(ctx, playlistId))
        var removed = 0
        groups.forEachIndexed { i, group ->
            onProgress(Progress(Progress.Step.DEDUPE, i, groups.size))
            val keep = group.first()
            val extras = group.drop(1)
            val byItem = extras.filter { it.itemId != null }
            val byId = extras.filter { it.itemId == null }
            if (byItem.isNotEmpty()) provider.removeTracks(ctx, playlistId, byItem)
            if (byId.isNotEmpty()) {
                // Rimozione per id: le copie con lo stesso id del "da tenere" spariscono tutte, poi se ne rimette una.
                provider.removeTracks(ctx, playlistId, byId)
                if (byId.any { it.id == keep.id }) provider.addTracks(ctx, playlistId, listOf(keep))
            }
            removed += extras.size
        }
        return removed
    }

    // ---------------------------------------------------------------- unplayable tracks

    /** One playlist's unplayable entries, as the scan found them. */
    data class Unplayables(val playlist: Playlist, val entries: List<UnplayableEntry>)
    data class UnplayableScan(val playlists: Int, val found: List<Unplayables>, val failed: List<Failure>) {
        val count: Int get() = found.sumOf { it.entries.size }
    }

    /**
     * Asks the service which entries of [playlists] it can no longer play. Nothing is changed. A
     * playlist it could not read is reported, not fatal.
     */
    suspend fun scanUnplayable(ctx: Env, provider: MusicProvider, playlists: List<Playlist>, onProgress: (String) -> Unit = {}): UnplayableScan {
        val found = ArrayList<Unplayables>()
        val failed = ArrayList<Failure>()
        for (pl in playlists) {
            onProgress(pl.name)
            try {
                val entries = provider.unplayable(ctx, pl.id)
                if (entries.isNotEmpty()) found += Unplayables(pl, entries)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                failed += Failure(pl.name, e.message ?: e.javaClass.simpleName)
            }
        }
        return UnplayableScan(playlists.size, found, failed)
    }

    /** The playlists the tool looks at: the user's own, and the liked songs where the service has them. */
    suspend fun unplayableTargets(ctx: Env, provider: MusicProvider): List<Playlist> =
        provider.libraryEntries(ctx, asTarget = true).filter { it.id == MusicProvider.LIKED_ID } + provider.playlists(ctx).filter { it.ownedByMe }

    /** What the service would play in place of [entry]: its own relink, else the best match found by searching it. */
    suspend fun proposeReplacement(ctx: Env, provider: MusicProvider, entry: UnplayableEntry, policy: MatchPolicy = MatchPolicy()): Matcher.Scored? {
        entry.relinked?.let { return Matcher.Scored(it, 1.0) }
        val candidates = runCatching { provider.search(ctx, entry.track) }.getOrDefault(emptyList())
        return Unplayable.proposal(entry, candidates, policy)
    }

    data class UnplayableResult(val replaced: Int, val removed: Int, val fix: UnplayableFix?)

    /**
     * Applies the decisions: each replacement is added, each dead entry removed (where the service
     * lets apps remove; otherwise the replacement is only added), and the replacements are moved to
     * the spots the dead entries had where the service can move tracks. What was done is kept, so
     * [undoUnplayable] can put it back.
     */
    suspend fun applyUnplayable(
        ctx: Env, provider: MusicProvider, playlist: Playlist,
        replace: List<Pair<UnplayableEntry, Track>>, removeOnly: List<UnplayableEntry>,
        onProgress: (Progress) -> Unit = {},
    ): UnplayableResult {
        val total = replace.size + removeOnly.size
        if (total == 0) return UnplayableResult(0, 0, null)
        val swaps = ArrayList<UnplayableSwap>()
        var done = 0
        // Adds first, in playlist order, so a failure never leaves the playlist shorter than before.
        for ((entry, replacement) in replace.sortedBy { it.first.index }) {
            onProgress(Progress(Progress.Step.ADDING, done++, total))
            provider.addTracks(ctx, playlist.id, listOf(replacement))
            swaps += UnplayableSwap(entry.track, replacement)
        }
        val dead = (replace.map { it.first } + removeOnly).map { it.track }
        var removed = 0
        if (provider.canRemoveTracks && dead.isNotEmpty()) {
            onProgress(Progress(Progress.Step.REMOVING, done, total))
            provider.removeTracks(ctx, playlist.id, dead)
            removed = removeOnly.size
            removeOnly.forEach { swaps += UnplayableSwap(it.track, null) }
        }
        if (provider.canReorder && replace.isNotEmpty() && playlist.id != MusicProvider.LIKED_ID) {
            runCatching {
                val current = provider.tracks(ctx, playlist.id)
                val ordered = Unplayable.orderAfter(current, replace)
                if (ordered.map { it.id } != current.map { it.id }) provider.reorderTracks(ctx, playlist.id, current, ordered)
            }.onFailure { ctx.log("unplayable", "${playlist.name}: order not restored: ${it.message}") }
        }
        val fix = UnplayableFix(provider.id, playlist.id, playlist.name, System.currentTimeMillis(), swaps)
        fixes(ctx)[fixKey(provider.id, playlist.id)] = com.xlollx.songport.net.json.encodeToString(fix)
        ctx.log("unplayable", "${provider.displayName} / ${playlist.name}: ${replace.size} replaced, $removed removed")
        return UnplayableResult(replace.size, removed, fix)
    }

    /** The last fix made in this playlist, if it is still there to undo. */
    fun lastUnplayableFix(ctx: Env, provider: MusicProvider, playlistId: String): UnplayableFix? =
        fixes(ctx)[fixKey(provider.id, playlistId)]?.let { runCatching { com.xlollx.songport.net.json.decodeFromString<UnplayableFix>(it) }.getOrNull() }

    /** Puts the dead entries back and takes the replacements out again. Returns how many entries came back. */
    suspend fun undoUnplayable(ctx: Env, provider: MusicProvider, fix: UnplayableFix): Int {
        val back = fix.swaps.map { it.removed }
        val added = fix.swaps.mapNotNull { it.added }
        if (back.isNotEmpty() && provider.canRemoveTracks) provider.addTracks(ctx, fix.playlistId, back)
        if (added.isNotEmpty() && provider.canRemoveTracks) provider.removeTracks(ctx, fix.playlistId, added)
        fixes(ctx)[fixKey(provider.id, fix.playlistId)] = ""
        ctx.log("unplayable", "${provider.displayName} / ${fix.playlistName}: undone, ${back.size} entries back")
        return back.size
    }

    private fun fixes(ctx: Env) = ctx.keyValues("unplayable_fixes")
    private fun fixKey(providerId: String, playlistId: String) = "$providerId|$playlistId"

    data class Created(val playlist: com.xlollx.songport.model.Playlist, val added: Int, val failed: List<String>)

    /**
     * Crea una playlist nuova sul servizio e ci mette [tracks] nell'ordine dato. Blocchi da 50 come
     * nella sync; un blocco rifiutato si ritenta brano per brano, cosi' un id non piu' valido non
     * fa perdere gli altri 49.
     */
    suspend fun createWith(
        ctx: Env, provider: MusicProvider, name: String, description: String, tracks: List<Track>,
        onProgress: (Progress) -> Unit = {},
    ): Created {
        if (!provider.canWrite || !provider.canCreatePlaylists) {
            throw ProviderException(ctx.text("tools_make_cannot_create", provider.displayName))
        }
        val pl = provider.createPlaylist(ctx, name, description)
        var added = 0
        val failed = ArrayList<String>()
        for (chunk in tracks.chunked(50)) {
            onProgress(Progress(Progress.Step.ADDING, added, tracks.size))
            try {
                provider.addTracks(ctx, pl.id, chunk); added += chunk.size
            } catch (e: Exception) {
                for (t in chunk) {
                    try { provider.addTracks(ctx, pl.id, listOf(t)); added++ } catch (e2: Exception) { failed += "$t (${e2.message})" }
                }
            }
        }
        onProgress(Progress(Progress.Step.ADDING, added, tracks.size))
        return Created(pl, added, failed)
    }
}
