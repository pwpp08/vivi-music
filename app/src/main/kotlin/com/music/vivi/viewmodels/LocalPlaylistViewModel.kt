/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.viewmodels

import kotlinx.coroutines.flow.onEach
import com.music.vivi.utils.ViviPrefCache
import com.music.vivi.utils.LibraryMemoryCache
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.vivi.constants.HideVideoSongsKey
import com.music.vivi.constants.PlaylistSongSortDescendingKey
import com.music.vivi.constants.PlaylistSongSortType
import com.music.vivi.constants.PlaylistSongSortTypeKey
import com.music.vivi.db.MusicDatabase
import com.music.vivi.db.entities.PlaylistSong
import com.music.vivi.extensions.reversed
import com.music.vivi.extensions.toEnum
import com.music.vivi.utils.SyncUtils
import com.music.vivi.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale
import javax.inject.Inject

import androidx.datastore.preferences.core.stringPreferencesKey

private data class OrderConfig(
    val sortType: PlaylistSongSortType,
    val sortDescending: Boolean,
    val hideVideoSongs: Boolean,
    val orderOverrideStr: String?,
)

@HiltViewModel
class LocalPlaylistViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    private val syncUtils: SyncUtils,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val playlistId = savedStateHandle.get<String>("playlistId")!!
    val playlist =
        database
            .playlist(playlistId)
            .stateIn(viewModelScope, SharingStarted.Lazily, LibraryMemoryCache.playlist(playlistId))
    val playlistSongs: StateFlow<List<PlaylistSong>> =
        combine(
            database.playlistSongs(playlistId).onEach { LibraryMemoryCache.putSongs(playlistId, it) },
            context.dataStore.data
                .map { prefs ->
                    val sortType = prefs[PlaylistSongSortTypeKey].toEnum(PlaylistSongSortType.CUSTOM)
                    val sortDescending = prefs[PlaylistSongSortDescendingKey] ?: true
                    val hideVideoSongs = prefs[HideVideoSongsKey] ?: false
                    val orderOverrideStr = prefs[orderOverrideKey(sortType)]
                    OrderConfig(sortType, sortDescending, hideVideoSongs, orderOverrideStr)
                }.distinctUntilChanged(),
        ) { songs, config -> applyOrder(songs, config) }
            .stateIn(viewModelScope, SharingStarted.Lazily, cachedSongs())

    private fun orderOverrideKey(sortType: PlaylistSongSortType) =
        stringPreferencesKey("playlist_order_${playlistId}_${sortType.name}")

    /** First-frame content: last known songs in the user's current order, or empty before prefs load. */
    private fun cachedSongs(): List<PlaylistSong> {
        val cached = LibraryMemoryCache.songsOf(playlistId)
        if (cached == null || !ViviPrefCache.isInitialized()) return emptyList()
        val sortType = ViviPrefCache.get(PlaylistSongSortTypeKey).toEnum(PlaylistSongSortType.CUSTOM)
        return applyOrder(
            cached,
            OrderConfig(
                sortType = sortType,
                sortDescending = ViviPrefCache.get(PlaylistSongSortDescendingKey) ?: true,
                hideVideoSongs = ViviPrefCache.get(HideVideoSongsKey) ?: false,
                orderOverrideStr = ViviPrefCache.get(orderOverrideKey(sortType)),
            ),
        )
    }

    private fun applyOrder(songs: List<PlaylistSong>, config: OrderConfig): List<PlaylistSong> {
        val (sortType, sortDescending, hideVideoSongs, orderOverrideStr) = config
        val filteredSongs = if (hideVideoSongs) {
            songs.filter { !it.song.song.isVideo }
        } else {
            songs
        }
        val overrideOrderIds = orderOverrideStr?.split(",")?.mapNotNull { it.toIntOrNull() }
        return if (overrideOrderIds != null && overrideOrderIds.isNotEmpty()) {
            val orderMap = overrideOrderIds.withIndex().associate { it.value to it.index }
            filteredSongs.sortedBy { orderMap[it.map.id] ?: Int.MAX_VALUE }
        } else {
            when (sortType) {
                PlaylistSongSortType.CUSTOM -> filteredSongs
                PlaylistSongSortType.CREATE_DATE -> filteredSongs.sortedBy { it.map.id }
                PlaylistSongSortType.NAME -> {
                    val collator = Collator.getInstance(Locale.getDefault())
                    collator.strength = Collator.PRIMARY
                    filteredSongs.sortedWith(compareBy(collator) { it.song.song.title })
                }
                PlaylistSongSortType.ARTIST -> {
                    val collator = Collator.getInstance(Locale.getDefault())
                    collator.strength = Collator.PRIMARY
                    filteredSongs
                        .sortedWith(compareBy(collator) { song -> song.song.artists.joinToString("") { it.name } })
                        .groupBy { it.song.album?.title }
                        .flatMap { (_, songsByAlbum) ->
                            songsByAlbum.sortedBy {
                                it.song.artists.joinToString(
                                    ""
                                ) { it.name }
                            }
                        }
                }

                PlaylistSongSortType.PLAY_TIME -> filteredSongs.sortedBy { it.song.song.totalPlayTime }
            }.reversed(sortDescending && sortType != PlaylistSongSortType.CUSTOM)
        }
    }

    init {
        viewModelScope.launch {
            // Trigger sync in background if it's a YouTube synced playlist
            playlist.first { it != null }?.playlist?.browseId?.let { browseId ->
                syncUtils.syncPlaylist(browseId, playlistId)
            }
        }
    }
}
