package com.xlollx.songport.providers

import android.net.Uri
import android.os.Bundle
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env
import com.xlollx.songport.sync.Matcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * LiveOne (formerly Slacker Radio) through liveone.com's web player, built into the GitHub and
 * F-Droid builds only (never Google Play): LiveOne has no public API. The user signs in on LiveOne's
 * own page inside the app; the player's session (its refresh token) is kept encrypted by the web
 * connectors and renewed as the player renews it (LiveOneWebClient).
 *
 * A free account has one list of its own, its presets: shown as the liked songs, read whole and
 * added to. Playlists of one's own need LiveOne's paid plan and are not handled yet; removing from
 * the presets is not either. Same service id as the server's connector, so a sync means the same.
 */
class LiveOneBridgeProvider(override val slot: String = "") : MusicProvider, WebAccounts.Connector {
    override val serviceId = SERVICE
    override val displayName = if (BridgePlugin.builtIn) "LiveOne (web)" else "LiveOne (plugin)"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.EASY
    override val routeNoteName: String? get() = "route_note_web"
    override val familyName: String get() = "LiveOne"
    override val brandColor = 0xFFE4002B
    override val noteName = "provider_note_liveone"
    override val alpha = true
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    /** Another account: where the WebView keeps profiles apart and the connectors take a slot (see [WebAccounts]). */
    override val supportsMultipleAccounts: Boolean get() = WebAccounts.allowed(STATUS)
    override val statusMethod: String get() = STATUS
    override val canCreatePlaylists = false
    override val canRemoveTracks = false
    override val authDomain = "liveone.com"
    override val pluginBased = true
    override val searchParallelism = 2

    /** Built in (GitHub, F-Droid), or through a connector plugin that knows it (Google Play). */
    override fun isConfigured(ctx: Env): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "liveone.status").containsKey("connected") }.getOrDefault(false)
    override fun isConnected(ctx: Env): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "liveone.status").getBoolean("connected", false) }.getOrDefault(false)
    override fun accountName(ctx: Env): String? = runCatching { call(ctx, "liveone.status").getString("account") }.getOrNull()

    override fun startAuth(ctx: Env) = BridgePlugin.startLogin(ctx, LOGIN_ACTION, slot)
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) { /* the sign-in screen keeps the session itself */ }
    override fun disconnect(ctx: Env) { runCatching { call(ctx, "liveone.disconnect") } }

    /** No playlists of one's own on a free account: the presets are the liked songs. */
    override suspend fun playlists(ctx: Env): List<Playlist> = emptyList()

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        if (playlistId != MusicProvider.LIKED_ID) throw ProviderException("$displayName: only the presets (liked songs) can be read for now")
        return io(ctx, "liveone.presets") { j -> j.arr.mapNotNull { toTrack(it) } }
    }

    override fun webSearchUrl(ctx: Env, query: String): String = "https://www.liveone.com/search?q=" + Uri.encode(query)

    /**
     * A search that fails is counted as "not found" by the engine without a word: the first few
     * failures of a process go to the log, so a sync with many not found shows whether LiveOne refused.
     */
    override suspend fun search(ctx: Env, track: Track): List<Track> = try {
        searchOnce(ctx, track)
    } catch (e: Exception) {
        if (e !is kotlinx.coroutines.CancellationException && searchErrors.incrementAndGet() <= 5) ctx.log("liveone", "search failed for \"${track.title}\": ${e.message}")
        throw e
    }

    private suspend fun searchOnce(ctx: Env, track: Track): List<Track> {
        if (track.kind.isNotEmpty()) return emptyList()
        val q = listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }, Matcher.searchTitle(track.title)).joinToString(" ")
        val r = io(ctx, "liveone.search", q) { j -> j.arr.mapNotNull { toTrack(it) } }
        if (r.isNotEmpty()) return r
        return io(ctx, "liveone.search", Matcher.searchTitle(track.title)) { j -> j.arr.mapNotNull { toTrack(it) } }
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist =
        throw ProviderException("$displayName: playlists of your own need LiveOne's paid plan and are not handled yet; sync into the presets (liked songs)")

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (playlistId != MusicProvider.LIKED_ID) throw ProviderException("$displayName: only the presets (liked songs) can be written for now")
        withContext(Dispatchers.IO) {
            tracks.chunked(100).forEach { chunk ->
                call(ctx, "liveone.add", null, Bundle().apply { putStringArray("ids", chunk.map { it.id }.toTypedArray()) })
            }
        }
    }

    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (tracks.isNotEmpty()) throw ProviderException("$displayName: removing from the presets is not possible yet")
    }

    private fun toTrack(j: JsonElement?): Track? {
        val id = j["id"].str ?: return null
        return Track(
            id = id,
            title = j["title"].str ?: return null,
            artists = j["artists"].arr.mapNotNull { it.str },
            album = j["album"].str ?: "",
            durationMs = j["durationMs"].long ?: 0,
        )
    }

    private suspend fun <T> io(ctx: Env, method: String, arg: String? = null, extras: Bundle? = null, map: (JsonElement?) -> T): T =
        withContext(Dispatchers.IO) { map(parseJson(call(ctx, method, arg, extras).getString("json") ?: "")) }

    private fun call(ctx: Env, method: String, arg: String? = null, extras: Bundle? = null): Bundle {
        if (!BridgePlugin.installed(ctx)) throw ProviderException(ctx.text("error_not_connected", displayName))
        val b = try {
            BridgePlugin.call(ctx, method, arg, extras, slot)
        } catch (e: Exception) {
            throw ProviderException("$displayName: ${e.message ?: e.javaClass.simpleName}")
        } ?: throw ProviderException("$displayName: no answer")
        b.getString("error")?.let { throw ProviderException("$displayName: $it") }
        return b
    }

    companion object {
        private val searchErrors = java.util.concurrent.atomic.AtomicInteger()
        const val SERVICE = "liveone_web"
        const val LOGIN_ACTION = "com.xlollx.songport.ytmbridge.LIVEONE_LOGIN"
        /** The status call of this service, which says whether the connectors take extra accounts. */
        const val STATUS = "liveone.status"
    }
}
