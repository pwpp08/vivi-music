package com.xlollx.songport.sync

import com.xlollx.songport.providers.*
import android.content.Context
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.MusicProvider
import java.util.IdentityHashMap

/**
 * Uno sguardo a tutta la libreria di un servizio: quali brani stanno in piu' di una playlist (anche
 * come edizioni diverse dello stesso brano, con la stessa logica dei duplicati) e quali preferiti non
 * stanno in nessuna playlist. Solo lettura: una richiesta per playlist, quindi su YouTube pesa sulla
 * quota quanto un backup completo.
 */
object LibraryScan {

    data class Repeat(val track: Track, val playlists: List<String>)
    data class Result(val playlists: Int, val repeats: List<Repeat>, val orphanLiked: List<Track>, val failed: List<String>)

    suspend fun run(ctx: Context, provider: MusicProvider, onProgress: (String) -> Unit = {}): Result {
        val lists = provider.playlists(ctx).filter { it.ownedByMe }
        val owner = IdentityHashMap<Track, String>()
        val all = ArrayList<Track>()
        val failed = ArrayList<String>()
        val likedName = ctx.getString(com.xlollx.songport.R.string.th_liked_songs)
        var liked: List<Track> = emptyList()
        if (provider.supportsLikedSongs) {
            onProgress(likedName)
            liked = try { provider.tracks(ctx, MusicProvider.LIKED_ID) } catch (e: Exception) { failed += "$likedName (${e.message})"; emptyList() }
            liked.forEach { owner[it] = likedName; all += it }
        }
        for (pl in lists) {
            onProgress(pl.name)
            val tracks = try { provider.tracks(ctx, pl.id) } catch (e: Exception) { failed += "${pl.name} (${e.message})"; continue }
            tracks.forEach { owner[it] = pl.name; all += it }
        }
        val groups = Duplicates.groups(all)
        val repeats = ArrayList<Repeat>()
        val likedCovered = java.util.Collections.newSetFromMap(IdentityHashMap<Track, Boolean>())
        for (g in groups) {
            val names = g.mapNotNull { owner[it] }
            val inPlaylists = names.filter { it != likedName }.distinct()
            // The same track twice in one playlist is a duplicate, not a repeat across playlists.
            if (inPlaylists.size >= 2) repeats += Repeat(g.first { owner[it] != likedName }, inPlaylists)
            if (inPlaylists.isNotEmpty()) g.filter { owner[it] == likedName }.forEach { likedCovered.add(it) }
        }
        val orphan = liked.filter { it !in likedCovered }
        return Result(lists.size, repeats.sortedByDescending { it.playlists.size }, orphan, failed)
    }
}
