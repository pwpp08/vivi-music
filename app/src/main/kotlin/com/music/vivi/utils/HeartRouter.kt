/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.utils

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.music.innertube.YouTube
import com.music.vivi.constants.AutoDownloadOnLikeKey
import com.music.vivi.constants.HeartTargetPlaylistKey
import com.music.vivi.db.MusicDatabase
import com.music.vivi.db.entities.Playlist
import com.music.vivi.models.MediaMetadata
import com.music.vivi.playback.DownloadUtil
import com.music.vivi.playback.ExoDownloadService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides where the heart button sends a song. With no target set the callers keep their normal
 * "like" behaviour; with a target the song goes into that playlist and is not liked at all.
 * A target playlist that has since been deleted counts as no target.
 */
@Singleton
class HeartRouter
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val syncUtils: SyncUtils,
    private val downloadUtil: DownloadUtil,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val targetId: Flow<String?> =
        context.dataStore.data
            .map { prefs -> prefs[HeartTargetPlaylistKey]?.takeIf { it.isNotEmpty() } }
            .distinctUntilChanged()
            .flatMapLatest { id ->
                if (id == null) flowOf(null) else database.playlist(id).map { it?.id }
            }.distinctUntilChanged()

    private suspend fun target(): Playlist? {
        val id = targetId.first() ?: return null
        return database.playlist(id).first()
    }

    /** Whether the heart for [songId] should be drawn filled. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun isHearted(songId: String): Flow<Boolean> =
        targetId.flatMapLatest { id ->
            if (id == null) {
                database.song(songId).map { it?.song?.liked == true }
            } else {
                database.isSongInPlaylist(id, songId)
            }
        }.distinctUntilChanged()

    /** Null when the heart is not rerouted, otherwise whether every song is in the target. */
    suspend fun allHeartedOrNull(songIds: List<String>): Boolean? =
        withContext(Dispatchers.IO) {
            val playlist = target() ?: return@withContext null
            songIds.isNotEmpty() && database.playlistDuplicates(playlist.id, songIds).toSet().containsAll(songIds)
        }

    /** Returns false when the heart is not rerouted, so the caller should do its normal like. */
    suspend fun toggleIfRouted(metadata: MediaMetadata): Boolean =
        withContext(Dispatchers.IO) {
            val playlist = target() ?: return@withContext false
            if (database.checkInPlaylist(playlist.id, metadata.id) > 0) {
                remove(playlist, metadata.id)
            } else {
                add(playlist, listOf(metadata))
            }
            true
        }

    /** Returns false when the heart is not rerouted. Songs already in the target are left alone. */
    suspend fun addIfRouted(metadata: MediaMetadata): Boolean =
        withContext(Dispatchers.IO) {
            val playlist = target() ?: return@withContext false
            if (database.checkInPlaylist(playlist.id, metadata.id) == 0) add(playlist, listOf(metadata))
            true
        }

    /** Fire-and-forget toggle for UI callers; [defaultAction] runs when the heart is not rerouted. */
    fun toggle(metadata: MediaMetadata, defaultAction: () -> Unit) {
        scope.launch {
            if (!toggleIfRouted(metadata)) defaultAction()
        }
    }

    /** Bulk version: removes everything if all songs are already in the target, else adds the rest. */
    fun toggleAll(songs: List<MediaMetadata>, defaultAction: () -> Unit) {
        scope.launch {
            val playlist = target()
            if (playlist == null) {
                defaultAction()
                return@launch
            }
            val present = database.playlistDuplicates(playlist.id, songs.map { it.id }).toSet()
            if (songs.isNotEmpty() && songs.all { it.id in present }) {
                songs.forEach { remove(playlist, it.id) }
            } else {
                add(playlist, songs.filter { it.id !in present })
            }
        }
    }

    private suspend fun add(playlist: Playlist, songs: List<MediaMetadata>) {
        if (songs.isEmpty()) return
        val ids = songs.map { it.id }
        database.transaction {
            songs.forEach { insert(it) }
            addSongToPlaylist(playlist, ids)
        }
        ids.forEach { syncUtils.clearSongTombstone(playlist.id, it) }
        downloadUtil.autoDownloadIfPlaylistDownloaded(playlist.id, ids)
        if (context.dataStore.data.first()[AutoDownloadOnLikeKey] == true) {
            songs.forEach { song ->
                val request = DownloadRequest.Builder(song.id, song.id.toUri())
                    .setCustomCacheKey(song.id)
                    .setData(song.title.toByteArray())
                    .build()
                DownloadService.sendAddDownload(context, ExoDownloadService::class.java, request, false)
            }
        }
        playlist.playlist.browseId?.let { browseId ->
            ids.forEach { id ->
                runCatching { YouTube.addToPlaylist(browseId, id) }
                    .onFailure { Timber.e(it, "HeartRouter: could not add %s to YouTube playlist", id) }
            }
        }
    }

    private fun remove(playlist: Playlist, songId: String) {
        // Last position first, so removing one row doesn't shift the rows still to be removed.
        database.playlistSongMapsOf(playlist.id, songId).sortedByDescending { it.position }.forEach { map ->
            syncUtils.markSongRemovedFromPlaylist(
                playlistId = map.playlistId,
                browseId = playlist.playlist.browseId,
                songId = map.songId,
                setVideoId = map.setVideoId,
            )
            database.transaction {
                move(map.playlistId, map.position, Int.MAX_VALUE)
                delete(map.copy(position = Int.MAX_VALUE))
            }
        }
        downloadUtil.checkAndRemoveOrphanedDownload(songId, playlist.id)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface HeartRouterEntryPoint {
    fun heartRouter(): HeartRouter
}

@Composable
fun rememberHeartRouter(): HeartRouter {
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) {
        EntryPointAccessors.fromApplication(appContext, HeartRouterEntryPoint::class.java).heartRouter()
    }
}

/** The filled state of a heart: [liked] normally, playlist membership when the heart is rerouted. */
@Composable
fun rememberHearted(songId: String?, liked: Boolean): Boolean {
    val (target, _) = rememberPreference(HeartTargetPlaylistKey, defaultValue = "")
    if (target.isEmpty() || songId == null) return liked
    val router = rememberHeartRouter()
    val hearted by remember(songId, target) { router.isHearted(songId) }.collectAsState(initial = false)
    return hearted
}

/** Same as [rememberHearted] for a multi-selection. */
@Composable
fun rememberAllHearted(songIds: List<String>, allLiked: Boolean): Boolean {
    val router = rememberHeartRouter()
    val routed by produceState<Boolean?>(null, songIds) { value = router.allHeartedOrNull(songIds) }
    return routed ?: allLiked
}
