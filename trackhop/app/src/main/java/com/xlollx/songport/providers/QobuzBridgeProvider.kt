package com.xlollx.songport.providers

import android.net.Uri
import android.os.Bundle
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.bool
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
 * Qobuz with an account, through play.qobuz.com's web player, built into the GitHub and F-Droid
 * builds only (never Google Play): Qobuz opens its API to partners only. The user signs in on
 * Qobuz's own page inside the app; the player's session is kept encrypted by the web connectors
 * (QobuzWebClient).
 *
 * Playlists of one's own and the favourite tracks (as liked songs), read and written; playlists
 * renamed, made public or private, deleted; search through the catalogue, by ISRC first. Same
 * service id as the server's connector, so a sync means the same on both.
 *
 * Not an official route: Qobuz's terms do not allow it and it can stop working when the player
 * changes. The sign-in screen shows the notice first.
 */
class QobuzBridgeProvider(override val slot: String = "") : MusicProvider, WebAccounts.Connector {
    override val serviceId = SERVICE
    override val displayName = if (BridgePlugin.builtIn) "Qobuz (web)" else "Qobuz (plugin)"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.EASY
    override val routeNoteName: String? get() = "route_note_web"
    override val familyName: String get() = "Qobuz"
    override val brandColor = BRAND
    override val noteName = "provider_note_qobuz_web"
    override val alpha = true
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    /** Another account: where the WebView keeps profiles apart and the connectors take a slot (see [WebAccounts]). */
    override val supportsMultipleAccounts: Boolean get() = WebAccounts.allowed(STATUS)
    override val statusMethod: String get() = STATUS
    override val canSetVisibility = true
    override val searchesByIsrc = true
    override val authDomain = "qobuz.com"
    override val pluginBased = true
    override val searchParallelism = 2

    /** Built in (GitHub, F-Droid), or through a connector plugin that knows it (Google Play). */
    override fun isConfigured(ctx: Env): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "qobuz.status").containsKey("connected") }.getOrDefault(false)
    override fun isConnected(ctx: Env): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "qobuz.status").getBoolean("connected", false) }.getOrDefault(false)
    override fun accountName(ctx: Env): String? = runCatching { call(ctx, "qobuz.status").getString("account") }.getOrNull()

    override fun startAuth(ctx: Env) = BridgePlugin.startLogin(ctx, LOGIN_ACTION, slot)
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) { /* the sign-in screen keeps the session itself */ }
    override fun disconnect(ctx: Env) { runCatching { call(ctx, "qobuz.disconnect") } }

    override suspend fun playlists(ctx: Env): List<Playlist> = io(ctx, "qobuz.playlists") { j ->
        j.arr.mapNotNull { p ->
            p["id"].str?.let { Playlist(it, p["name"].str ?: "", p["count"]?.long?.toInt() ?: -1, ownedByMe = p["owned"].bool != false, description = p["description"].str.orEmpty()) }
        }
    }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = withContext(Dispatchers.IO) {
        val id = if (playlistId == MusicProvider.LIKED_ID) LIKED else playlistId
        val out = ArrayList<Track>()
        var offset: Int? = 0
        while (offset != null) {
            val b = call(ctx, "qobuz.tracks", id, Bundle().apply { putInt("offset", offset!!) })
            out += parseJson(b.getString("json") ?: "[]").arr.mapNotNull { toTrack(it) }
            offset = if (b.containsKey("next")) b.getInt("next") else null
        }
        out
    }

    override fun webSearchUrl(ctx: Env, query: String): String = "https://play.qobuz.com/search?q=" + Uri.encode(query)

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        if (track.kind.isNotEmpty()) return emptyList()
        val q = listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }, Matcher.searchTitle(track.title)).joinToString(" ")
        val r = io(ctx, "qobuz.search", q, Bundle().apply { putString("isrc", track.isrcNorm) }) { j -> j.arr.mapNotNull { toTrack(it) } }
        if (r.isNotEmpty()) return r
        return io(ctx, "qobuz.search", Matcher.searchTitle(track.title)) { j -> j.arr.mapNotNull { toTrack(it) } }
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist =
        io(ctx, "qobuz.create", null, Bundle().apply { putString("name", name); putString("description", description) }) { j ->
            j["id"].str?.let { Playlist(it, name, 0, description = description) } ?: throw ProviderException("Qobuz: playlist not created")
        }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) {
        withContext(Dispatchers.IO) { call(ctx, "qobuz.rename", playlistId, Bundle().apply { putString("name", name) }) }
    }

    override suspend fun setPlaylistVisibility(ctx: Env, playlistId: String, public: Boolean) {
        withContext(Dispatchers.IO) { call(ctx, "qobuz.visibility", playlistId, Bundle().apply { putBoolean("public", public) }) }
    }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) {
        withContext(Dispatchers.IO) { call(ctx, "qobuz.delete", playlistId) }
    }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) = write(ctx, "qobuz.add", playlistId, tracks)

    /** By entry where the listing gave one (Qobuz removes a playlist's entries, not tracks). */
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) = write(ctx, "qobuz.remove", playlistId, tracks)

    /** In chunks, so a long list shows its progress in the log. */
    private suspend fun write(ctx: Env, method: String, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val id = if (playlistId == MusicProvider.LIKED_ID) LIKED else playlistId
        withContext(Dispatchers.IO) {
            tracks.chunked(50).forEach { chunk ->
                call(ctx, method, id, Bundle().apply {
                    putStringArray("ids", chunk.map { it.id }.toTypedArray())
                    putStringArray("items", chunk.map { it.itemId.orEmpty() }.toTypedArray())
                })
            }
        }
    }

    private fun toTrack(j: JsonElement?): Track? {
        val id = j["id"].str ?: return null
        return Track(
            id = id,
            title = j["title"].str ?: return null,
            artists = j["artists"].arr.mapNotNull { it.str },
            album = j["album"].str ?: "",
            durationMs = j["durationMs"].long ?: 0,
            isrc = j["isrc"].str,
            itemId = j["setVideoId"].str,
            explicit = j["explicit"].bool,
            year = j["year"].long?.toInt() ?: 0,
            available = j["available"].bool,
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
        const val SERVICE = "qobuz_web"
        const val LOGIN_ACTION = "com.xlollx.songport.ytmbridge.QOBUZ_LOGIN"
        /** The status call of this service, which says whether the connectors take extra accounts. */
        const val STATUS = "qobuz.status"
        /** Qobuz's mark is black: near-black here, which the logo circle edges on a dark theme. */
        const val BRAND = 0xFF141414
        /** The liked songs as the connectors name them. */
        private const val LIKED = "__liked__"
    }
}
