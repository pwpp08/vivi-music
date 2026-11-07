/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.utils

import android.content.Context
import android.util.LruCache
import com.music.vivi.constants.HideYoutubeShortsKey
import com.music.vivi.constants.PlaylistSortDescendingKey
import com.music.vivi.constants.PlaylistSortType
import com.music.vivi.constants.PlaylistSortTypeKey
import com.music.vivi.db.MusicDatabase
import com.music.vivi.db.entities.Playlist
import com.music.vivi.db.entities.PlaylistSong
import com.music.vivi.db.entities.Song
import com.music.vivi.extensions.filterYoutubeShorts
import com.music.vivi.extensions.toEnum
import kotlinx.coroutines.flow.first

/**
 * Last known library data, kept so screens can draw real content on their first frame while the
 * live Room query catches up. Room stays the source of truth: every entry is replaced as soon as
 * the query emits, so a stale value is only ever visible for a moment.
 */
object LibraryMemoryCache {
    private const val PRELOAD_PLAYLISTS = 30
    private const val PRELOAD_MAX_SONGS = 5000

    @Volatile
    var playlists: List<Playlist>? = null

    private val playlistSongs = LruCache<String, List<PlaylistSong>>(PRELOAD_PLAYLISTS)
    private val autoPlaylistSongs = LruCache<String, List<Song>>(4)

    fun playlist(id: String): Playlist? = playlists?.firstOrNull { it.id == id }

    fun songsOf(playlistId: String): List<PlaylistSong>? = playlistSongs.get(playlistId)

    fun putSongs(playlistId: String, songs: List<PlaylistSong>) {
        playlistSongs.put(playlistId, songs)
    }

    fun autoSongsOf(type: String): List<Song>? = autoPlaylistSongs.get(type)

    fun putAutoSongs(type: String, songs: List<Song>) {
        autoPlaylistSongs.put(type, songs)
    }

    /** Run once at startup, off the main thread, so the first library frame already has data. */
    suspend fun preload(context: Context, database: MusicDatabase) {
        val prefs = context.dataStore.data.first()
        val loaded = database
            .playlists(
                prefs[PlaylistSortTypeKey].toEnum(PlaylistSortType.CREATE_DATE),
                prefs[PlaylistSortDescendingKey] ?: true,
            ).first()
            .filterYoutubeShorts(prefs[HideYoutubeShortsKey] ?: false)
        if (playlists == null) playlists = loaded

        var budget = PRELOAD_MAX_SONGS
        for (playlist in loaded.take(PRELOAD_PLAYLISTS)) {
            if (playlist.songCount > budget) continue
            budget -= playlist.songCount
            if (songsOf(playlist.id) == null) putSongs(playlist.id, database.playlistSongs(playlist.id).first())
        }
    }
}
