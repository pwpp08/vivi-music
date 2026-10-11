package com.music.vivi.viewmodels

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.spotify.Spotify
import com.music.spotify.SpotifyMapper
import com.music.spotify.models.SpotifyTrack
import com.music.vivi.R
import com.music.vivi.db.MusicDatabase
import com.music.vivi.db.entities.ArtistEntity
import com.music.vivi.db.entities.PlaylistEntity
import com.music.vivi.db.entities.PlaylistSongMap
import com.music.vivi.db.entities.Song
import com.music.vivi.db.entities.SongEntity
import com.music.vivi.models.MediaMetadata
import com.music.vivi.models.toMediaMetadata
import com.music.vivi.playback.SpotifySyncService
import com.music.vivi.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

data class SpotifyImportProgress(
    val playlistName: String,
    val currentSongIndex: Int,
    val totalSongs: Int,
    val percent: Float,
    val isFinished: Boolean = false
)

private data class PlaylistImportData(
    val title: String,
    val songs: List<Song>,
    val localPlaylistId: String,
    val thumbnailUrl: String?
)

@HiltViewModel
class SpotifyImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
) : ViewModel() {

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val importProgress: StateFlow<SpotifyImportProgress?> = _importProgress.asStateFlow()
    val isImportMinimized: StateFlow<Boolean> = _isImportMinimized.asStateFlow()

    companion object {
        private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val _importProgress = MutableStateFlow<SpotifyImportProgress?>(null)
        private val _isImportMinimized = MutableStateFlow(false)
        private var importJob: Job? = null

        val importProgressFlow: StateFlow<SpotifyImportProgress?> = _importProgress.asStateFlow()

        fun cancelActiveJobs() {
            importJob?.cancel()
            importJob = null
            _importProgress.value = null
            _isImportMinimized.value = false
        }
    }

    fun minimizeImport() {
        _isImportMinimized.value = true
    }

    fun restoreImportDialog() {
        _isImportMinimized.value = false
    }

    private suspend fun <T> spotifyCallWithTokenRetry(block: suspend () -> T): T =
        runCatching { block() }
            .getOrElse { error ->
                if ((error as? Spotify.SpotifyException)?.statusCode != 401) {
                    throw error
                }
                Spotify.accessToken = null
                Spotify.ensureToken()
                block()
            }

    suspend fun fetchPlaylistTracks(playlistId: String): List<Song> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<SpotifyTrack>()
        var offset = 0
        val limit = 100
        while (true) {
            val page = spotifyCallWithTokenRetry {
                Spotify.playlistTracks(playlistId, limit = limit, offset = offset).getOrThrow()
            }
            tracks.addAll(page.items.mapNotNull { it.track })
            offset += limit
            if (page.items.isEmpty() || offset >= page.total) break
        }

        return@withContext tracks.map { track ->
            Song(
                song = SongEntity(
                    id = "",
                    title = track.name,
                    duration = track.durationMs / 1000,
                    thumbnailUrl = SpotifyMapper.getTrackThumbnail(track),
                ),
                artists = track.artists.map { ArtistEntity(id = "", name = it.name) },
            )
        }
    }

    fun extractPlaylistId(input: String): String? {
        val trimmed = input.trim()
        val match = Regex("""(?:playlist[/:])([a-zA-Z0-9]{22})""").find(trimmed)
        if (match != null) {
            return match.groupValues[1]
        }
        if (trimmed.length == 22 && trimmed.all { it.isLetterOrDigit() }) {
            return trimmed
        }
        return null
    }

    private suspend fun importSinglePlaylist(importData: PlaylistImportData) {
        val totalSongs = importData.songs.size
        if (totalSongs == 0) {
            database.withTransaction {
                val existing = playlist(importData.localPlaylistId).first()
                val now = LocalDateTime.now()
                val entity = existing?.playlist?.copy(
                    name = importData.title,
                    bookmarkedAt = existing.playlist.bookmarkedAt ?: now,
                    lastUpdateTime = now,
                    thumbnailUrl = importData.thumbnailUrl,
                    isEditable = true,
                ) ?: PlaylistEntity(
                    id = importData.localPlaylistId,
                    name = importData.title,
                    bookmarkedAt = now,
                    lastUpdateTime = now,
                    thumbnailUrl = importData.thumbnailUrl,
                    isEditable = true,
                )
                if (existing == null) insert(entity) else update(entity)
                clearPlaylist(importData.localPlaylistId)
            }

            _importProgress.update {
                SpotifyImportProgress(
                    playlistName = importData.title,
                    currentSongIndex = 0,
                    totalSongs = 0,
                    percent = 1.0f
                )
            }
            return
        }

        _importProgress.update {
            SpotifyImportProgress(
                playlistName = importData.title,
                currentSongIndex = 0,
                totalSongs = totalSongs,
                percent = 0.0f
            )
        }

        val completedCount = AtomicInteger(0)
        val semaphore = Semaphore(2)

        coroutineScope {
            val matchedMedia = importData.songs.mapIndexed { _, song ->
                async {
                    semaphore.withPermit {
                        var resultMedia: MediaMetadata? = null
                        try {
                            val artist = song.artists.firstOrNull()?.name.orEmpty()
                            val cleanTitle = Spotify.cleanTrackSearchQuery(song.title)
                            val query = if (artist.isEmpty()) cleanTitle else "$artist $cleanTitle"

                            // 1. Check local DB first to avoid unnecessary network calls
                            val existingLocal = database.searchSongs(query, 1).first().firstOrNull()
                            if (existingLocal != null) {
                                resultMedia = existingLocal.toMediaMetadata()
                            } else {
                                delay(100)
                                val searchResult = YouTube.search(
                                    query = query,
                                    filter = YouTube.SearchFilter.FILTER_SONG,
                                ).getOrNull()

                                var candidates = searchResult?.items
                                    ?.filterIsInstance<SongItem>()
                                    ?.distinctBy { it.id }
                                    .orEmpty()

                                if (candidates.isEmpty()) {
                                    val fallbackResult = YouTube.search(query, filter = YouTube.SearchFilter.FILTER_VIDEO).getOrNull()
                                    candidates = fallbackResult?.items
                                        ?.filterIsInstance<SongItem>()
                                        ?.distinctBy { it.id }
                                        .orEmpty()
                                }

                                val best = candidates.maxByOrNull { candidate ->
                                    SpotifyMapper.matchScore(
                                        spotifyTitle = song.title,
                                        spotifyArtist = song.artists.joinToString(" ") { it.name },
                                        spotifyDurationMs = song.song.duration * 1000,
                                        candidateTitle = candidate.title,
                                        candidateArtist = candidate.artists.joinToString(" ") { it.name },
                                        candidateDurationSec = candidate.duration,
                                    )
                                }

                                if (best != null) {
                                    resultMedia = best.toMediaMetadata()
                                }
                            }
                        } catch (e: Exception) {
                            reportException(e)
                        } finally {
                            val done = completedCount.incrementAndGet()
                            _importProgress.update {
                                SpotifyImportProgress(
                                    playlistName = importData.title,
                                    currentSongIndex = done,
                                    totalSongs = totalSongs,
                                    percent = done.toFloat() / totalSongs
                                )
                            }
                        }
                        resultMedia
                    }
                }
            }.awaitAll().filterNotNull()

            database.withTransaction {
                val existing = playlist(importData.localPlaylistId).first()
                val now = LocalDateTime.now()
                val entity = existing?.playlist?.copy(
                    name = importData.title,
                    bookmarkedAt = existing.playlist.bookmarkedAt ?: now,
                    lastUpdateTime = now,
                    thumbnailUrl = importData.thumbnailUrl,
                    isEditable = true,
                ) ?: PlaylistEntity(
                    id = importData.localPlaylistId,
                    name = importData.title,
                    bookmarkedAt = now,
                    lastUpdateTime = now,
                    thumbnailUrl = importData.thumbnailUrl,
                    isEditable = true,
                )

                if (existing == null) {
                    insert(entity)
                } else {
                    update(entity)
                }

                matchedMedia.forEach { metadata ->
                    insert(metadata)
                }

                clearPlaylist(importData.localPlaylistId)
                matchedMedia.forEachIndexed { index, metadata ->
                    insert(
                        PlaylistSongMap(
                            playlistId = importData.localPlaylistId,
                            songId = metadata.id,
                            position = index,
                            setVideoId = metadata.setVideoId,
                        )
                    )
                }
                update(entity.copy(lastUpdateTime = now))
            }
        }
    }

    fun importPlaylistByUrl(url: String) {
        val playlistId = extractPlaylistId(url)
        if (playlistId == null) {
            _errorMessage.value = context.getString(R.string.spotify_paste_link_invalid)
            return
        }

        _isImporting.value = true
        _isImportMinimized.value = false
        _errorMessage.value = null
        importJob?.cancel()
        SpotifySyncService.start(context)
        importJob = backgroundScope.launch(Dispatchers.IO) {
            _importProgress.update { null }
            try {
                Spotify.ensureToken()
                val playlist = Spotify.playlist(playlistId).getOrNull()
                val title = playlist?.name?.takeIf { it.isNotBlank() } ?: "Imported Spotify Playlist"
                val thumbnail = playlist?.let { SpotifyMapper.getPlaylistThumbnail(it) }
                val songs = fetchPlaylistTracks(playlistId)

                val importData = PlaylistImportData(
                    title = title,
                    songs = songs,
                    localPlaylistId = "SPOTIFY_PLAYLIST_$playlistId",
                    thumbnailUrl = thumbnail
                )

                importSinglePlaylist(importData)

                _importProgress.update { old ->
                    old?.copy(isFinished = true) ?: SpotifyImportProgress(
                        playlistName = title,
                        currentSongIndex = songs.size,
                        totalSongs = songs.size,
                        percent = 1.0f,
                        isFinished = true
                    )
                }
                withContext(Dispatchers.Main) {
                    _isImporting.value = false
                    Toast.makeText(context, "Spotify import completed!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    reportException(e)
                    _errorMessage.value = e.message ?: "Failed to import playlist"
                    withContext(Dispatchers.Main) {
                        _isImporting.value = false
                    }
                }
            } finally {
                if (_importProgress.value?.isFinished != true) {
                    _importProgress.update { null }
                }
                withContext(Dispatchers.Main) {
                    _isImporting.value = false
                }
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        _importProgress.update { null }
        _isImportMinimized.value = false
        _isImporting.value = false
        SpotifySyncService.stop(context)
    }

    fun dismissImportProgress() {
        _importProgress.update { null }
        _isImportMinimized.value = false
        SpotifySyncService.stop(context)
    }

    fun dismissError() {
        _errorMessage.value = null
    }
}
