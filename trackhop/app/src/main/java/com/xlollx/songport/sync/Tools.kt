package com.xlollx.songport.sync

import android.content.Context
import com.xlollx.songport.model.MatchPolicy
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.Track
import com.xlollx.songport.model.UnplayableEntry
import com.xlollx.songport.model.UnplayableFix
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.MusicProvider

/**
 * The app's door to [LibraryTools] (core), which the server's web page shares: the same calls,
 * taking the app's Context.
 */
object Tools {
    suspend fun backupAll(ctx: Context, provider: MusicProvider, onProgress: (Progress) -> Unit = {}) =
        LibraryTools.backupAll(ctx.env, provider, onProgress)

    suspend fun findDuplicates(ctx: Context, provider: MusicProvider, playlistId: String): List<List<Track>> =
        LibraryTools.findDuplicates(ctx.env, provider, playlistId)

    suspend fun removeDuplicates(ctx: Context, provider: MusicProvider, playlistId: String, onProgress: (Progress) -> Unit = {}): Int =
        LibraryTools.removeDuplicates(ctx.env, provider, playlistId, onProgress)

    suspend fun scanUnplayable(ctx: Context, provider: MusicProvider, playlists: List<Playlist>, onProgress: (String) -> Unit = {}) =
        LibraryTools.scanUnplayable(ctx.env, provider, playlists, onProgress)

    suspend fun unplayableTargets(ctx: Context, provider: MusicProvider): List<Playlist> =
        LibraryTools.unplayableTargets(ctx.env, provider)

    suspend fun proposeReplacement(ctx: Context, provider: MusicProvider, entry: UnplayableEntry, policy: MatchPolicy = MatchPolicy()) =
        LibraryTools.proposeReplacement(ctx.env, provider, entry, policy)

    suspend fun applyUnplayable(
        ctx: Context, provider: MusicProvider, playlist: Playlist,
        replace: List<Pair<UnplayableEntry, Track>>, removeOnly: List<UnplayableEntry>,
        onProgress: (Progress) -> Unit = {},
    ) = LibraryTools.applyUnplayable(ctx.env, provider, playlist, replace, removeOnly, onProgress)

    fun lastUnplayableFix(ctx: Context, provider: MusicProvider, playlistId: String): UnplayableFix? =
        LibraryTools.lastUnplayableFix(ctx.env, provider, playlistId)

    suspend fun undoUnplayable(ctx: Context, provider: MusicProvider, fix: UnplayableFix): Int =
        LibraryTools.undoUnplayable(ctx.env, provider, fix)

    suspend fun createWith(
        ctx: Context, provider: MusicProvider, name: String, description: String, tracks: List<Track>,
        onProgress: (Progress) -> Unit = {},
    ) = LibraryTools.createWith(ctx.env, provider, name, description, tracks, onProgress)
}
