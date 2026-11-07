package com.xlollx.songport.providers

import com.xlollx.songport.platform.Env
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track
import com.xlollx.songport.sync.CsvCodec
import com.xlollx.songport.sync.PlaylistFiles
import java.io.File

/**
 * "Servizio" locale: playlist come file nella cartella privata dell'app.
 * E' il ponte per i servizi senza API aperta (Amazon Music, Qobuz, SoundCloud…): si importa
 * qui quello che loro sanno esportare (CSV, M3U, XML di Apple Music/iTunes, JSON, elenchi di
 * testo) e lo si sincronizza verso Spotify, Apple Music, YouTube Music, TIDAL o Deezer — e
 * viceversa, esportando qualsiasi playlist in CSV o M3U.
 *
 * L'id resta "csv" per compatibilita' con le sync gia' salvate.
 */
object LocalFilesProvider : MusicProvider {
    override val serviceId = "csv"
    override val supportsMultipleAccounts = false
    override val displayName = "File"
    override val brandColor = 0xFF607D8B
    override val noteName = "provider_note_files"
    override val requiresAuth = false

    fun dir(ctx: Env): File = File(ctx.dataDir, "csv").apply { mkdirs() }
    private fun file(ctx: Env, id: String) = File(dir(ctx), "$id.csv")

    /**
     * [f] with [text], swapped in whole: a sync reading the file meanwhile (a radio sync adds songs
     * to its source while a run reads it) sees the old content or the new, never an empty file.
     */
    private fun write(f: File, text: String) {
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        tmp.writeText(text)
        busyRetry(f) {
            try {
                java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /** [f]'s text. On Windows a file being swapped in by [write] cannot be opened for an instant: tried again. */
    private fun read(f: File): String = busyRetry(f) { f.readText() }

    /**
     * [block], tried again for up to a second while Windows has [f] in use (a reader holds it while
     * [write] replaces it, or the other way round). Elsewhere the first try succeeds. A file that is
     * not there is not waited for. (The error's text is in the system's language: not looked at.)
     */
    private fun <T> busyRetry(f: File, block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: java.io.IOException) {
                val gone = e is java.nio.file.NoSuchFileException || (e is java.io.FileNotFoundException && !f.exists())
                if (gone || ++attempt >= 100) throw e
                Thread.sleep(10)
            }
        }
    }

    override fun isConfigured(ctx: Env) = true
    override fun isConnected(ctx: Env) = true
    override fun accountName(ctx: Env): String? = null
    override fun startAuth(ctx: Env) = Unit
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) = Unit
    override fun disconnect(ctx: Env) = Unit

    override suspend fun playlists(ctx: Env): List<Playlist> =
        dir(ctx).listFiles { f -> f.extension == "csv" }.orEmpty().sortedBy { it.name.lowercase() }.map { f ->
            Playlist(id = f.nameWithoutExtension, name = f.nameWithoutExtension, trackCount = CsvCodec.parse(read(f)).size)
        }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        val f = file(ctx, playlistId)
        return if (f.exists()) CsvCodec.parse(read(f)) else emptyList()
    }

    /** Un file puo' "contenere" qualunque brano: il candidato e' il brano stesso con id stabile. */
    override suspend fun search(ctx: Env, track: Track): List<Track> = listOf(CsvCodec.withStableId(track))

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist {
        val base = CsvCodec.safeName(name)
        var candidate = base
        var n = 2
        while (file(ctx, candidate).exists()) { candidate = "$base ($n)"; n++ }
        write(file(ctx, candidate), CsvCodec.encode(emptyList()))
        return Playlist(candidate, candidate, 0)
    }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        val f = file(ctx, playlistId)
        val current = if (f.exists()) CsvCodec.parse(read(f)) else emptyList()
        val ids = current.map { it.id }.toHashSet()
        val merged = current + tracks.map { CsvCodec.withStableId(it) }.filter { ids.add(it.id) }
        write(f, CsvCodec.encode(merged))
    }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) {
        val f = file(ctx, playlistId)
        if (!f.exists()) return
        val base = CsvCodec.safeName(name)
        var candidate = base
        var n = 2
        while (candidate != playlistId && file(ctx, candidate).exists()) { candidate = "$base ($n)"; n++ }
        if (candidate == playlistId) return
        if (!f.renameTo(file(ctx, candidate))) throw com.xlollx.songport.model.ProviderException("$displayName: rename failed")
        // Syncs point at the file by name: follow it.
        val store = ctx.store
        store.data.jobs.filter { j -> (j.source.provider == id && j.source.playlistId == playlistId) || (j.target.provider == id && j.target.playlistId == playlistId) }
            .forEach { j ->
                store.upsertJob(j.copy(
                    source = if (j.source.provider == id && j.source.playlistId == playlistId) j.source.copy(playlistId = candidate, playlistName = candidate) else j.source,
                    target = if (j.target.provider == id && j.target.playlistId == playlistId) j.target.copy(playlistId = candidate, playlistName = candidate) else j.target,
                ))
            }
    }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) { file(ctx, playlistId).delete() }

    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        val f = file(ctx, playlistId)
        if (!f.exists()) return
        val remove = tracks.map { it.id }.toHashSet()
        write(f, CsvCodec.encode(CsvCodec.parse(read(f)).filter { it.id !in remove }))
    }

    // --- import/export per la UI (Storage Access Framework) ---

    /** Importa un file in qualsiasi formato riconosciuto. @return numero di brani importati. */
    fun importFile(ctx: Env, fileName: String, text: String): Int {
        val tracks = PlaylistFiles.parse(fileName, text)
        val base = CsvCodec.safeName(fileName.substringBeforeLast('.').ifBlank { "playlist" })
        var candidate = base
        var n = 2
        while (file(ctx, candidate).exists()) { candidate = "$base ($n)"; n++ }
        write(file(ctx, candidate), CsvCodec.encode(tracks))
        return tracks.size
    }

    /** Salva una lista di brani come nuovo file (nome reso unico). @return id della playlist creata. */
    fun importTracks(ctx: Env, name: String, tracks: List<Track>): String {
        val base = CsvCodec.safeName(name.ifBlank { "playlist" })
        var candidate = base
        var n = 2
        while (file(ctx, candidate).exists()) { candidate = "$base ($n)"; n++ }
        write(file(ctx, candidate), CsvCodec.encode(tracks))
        return candidate
    }

    enum class Export(val mime: String, val extension: String) {
        CSV("text/csv", "csv"),
        M3U("audio/x-mpegurl", "m3u8"),
        JSPF("application/json", "jspf"),
        XSPF("application/xspf+xml", "xspf"),
        TXT("text/plain", "txt"),
        ;

        /** Il testo del file in questo formato; [name] serve ai formati che hanno un titolo. */
        fun encode(name: String, tracks: List<Track>): String = when (this) {
            CSV -> CsvCodec.encode(tracks)
            M3U -> PlaylistFiles.toM3u(tracks)
            JSPF -> PlaylistFiles.toJspf(name, tracks)
            XSPF -> PlaylistFiles.toXspf(name, tracks)
            TXT -> PlaylistFiles.toText(tracks)
        }
    }

    fun exportText(ctx: Env, playlistId: String, format: Export): String {
        val tracks = file(ctx, playlistId).takeIf { it.exists() }?.let { read(it) }?.let { CsvCodec.parse(it) } ?: emptyList()
        return format.encode(playlistId, tracks)
    }

    fun delete(ctx: Env, playlistId: String) { file(ctx, playlistId).delete() }

    // --- backups: one version per run, dated, with retention ---

    /** How many versions of one playlist's backup are kept; older ones are removed at the next backup. */
    const val KEEP_VERSIONS = 5

    /**
     * A file playlist as the Files screen shows it. Backups are "Service - Playlist (yyyy-MM-dd HH.mm)";
     * [service] and [base] come from the name, [writtenAt] from the stamp, or from the file when the
     * name has none (imports, and backups made before versions existed).
     */
    data class Entry(
        val id: String, val name: String, val trackCount: Int,
        val service: String?, val base: String, val stamped: Boolean, val writtenAt: Long,
    ) {
        /** The playlist's own name, without the service prefix and the stamp. */
        val title: String get() = if (service == null) base else base.substringAfter(" - ").trim()
    }

    /** A fresh formatter per use: SimpleDateFormat is not thread-safe, and backups and the Files screen may overlap. */
    private fun stamp() = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.ROOT)
    private val STAMP_TAIL = Regex(""" \((\d{4}-\d{2}-\d{2} \d{2}\.\d{2})\)$""")
    private val COPY_TAIL = Regex(""" \(\d+\)$""")

    fun entries(ctx: Env): List<Entry> =
        dir(ctx).listFiles { f -> f.extension == "csv" }.orEmpty().map { f ->
            val name = f.nameWithoutExtension
            val tail = STAMP_TAIL.find(name)
            val stampedAt = tail?.let { runCatching { stamp().parse(it.groupValues[1])?.time }.getOrNull() }
            val base = COPY_TAIL.replace(STAMP_TAIL.replace(name, ""), "")
            Entry(
                id = name, name = name, trackCount = CsvCodec.parse(read(f)).size,
                service = base.substringBefore(" - ", "").trim().takeIf { it.isNotEmpty() },
                base = base, stamped = stampedAt != null, writtenAt = stampedAt ?: f.lastModified(),
            )
        }

    enum class BackupOutcome { WRITTEN, UNCHANGED }

    /**
     * Writes one dated version of a service playlist's backup, unless the newest version already has
     * exactly these tracks, then trims that playlist's versions to [KEEP_VERSIONS]. Files a sync reads
     * or writes ([protectedIds]) are never removed, whatever their age.
     */
    fun writeBackup(ctx: Env, service: String, playlistName: String, tracks: List<Track>, protectedIds: Set<String>, now: Long = System.currentTimeMillis()): BackupOutcome {
        val base = CsvCodec.safeName("$service - $playlistName")
        val versions = entries(ctx).filter { it.base == base && it.service != null }.sortedByDescending { it.writtenAt }
        val text = CsvCodec.encode(tracks)
        if (versions.firstOrNull()?.let { read(file(ctx, it.id)) } == text) return BackupOutcome.UNCHANGED
        val when_ = stamp().format(java.util.Date(now))
        var id = "$base ($when_)"
        var n = 2
        while (file(ctx, id).exists()) { id = "$base ($when_) ($n)"; n++ }
        write(file(ctx, id), text)
        versions.drop(KEEP_VERSIONS - 1).filter { it.id !in protectedIds }.forEach { file(ctx, it.id).delete() }
        return BackupOutcome.WRITTEN
    }

    /** Ids of the file playlists some sync reads or writes: retention leaves them alone. */
    fun protectedIds(ctx: Env): Set<String> =
        ctx.store.data.jobs.flatMap { j ->
            listOfNotNull(j.source.takeIf { it.provider == serviceId }?.playlistId, j.target.takeIf { it.provider == serviceId }?.playlistId)
        }.toSet()
}
