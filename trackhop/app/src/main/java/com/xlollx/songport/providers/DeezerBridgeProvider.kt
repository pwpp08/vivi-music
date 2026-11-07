package com.xlollx.songport.providers

import android.content.Intent
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
import com.xlollx.songport.platform.android
import com.xlollx.songport.sync.Matcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * Deezer through the web player's session, kept by the companion connectors ("Trackhop Bridge",
 * built into this app on GitHub and F-Droid): the user signs in on deezer.com's own page. Deezer no
 * longer registers new developer apps, so for most people this is the only way in; the official
 * route ([DeezerProvider]) stays for those who have an app id.
 *
 * Playlists and favourite tracks go through the web player's gateway (see DeezerWebClient in the
 * Bridge); search goes through Deezer's public catalogue, by ISRC when the track has one. Deezer's
 * track ids are the same on both routes, so matches found through one serve the other.
 *
 * Not an official route: Deezer's terms do not allow it and it can stop working when the player
 * changes. The Bridge shows the notice before sign-in.
 */
class DeezerBridgeProvider(override val slot: String = "") : MusicProvider, WebAccounts.Connector {
    override val serviceId = SERVICE
    override val displayName = if (BridgePlugin.builtIn) "Deezer (web)" else "Deezer (plugin)"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.EASY
    override val routeNoteName: String? get() = "route_note_web"
    override val familyName: String get() = "Deezer"
    override val brandColor = 0xFFA238FF
    override val noteName = "provider_note_deezer_bridge"
    override val alpha = true
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    /** Another account: where the WebView keeps profiles apart and the connectors take a slot (see [WebAccounts]). */
    override val supportsMultipleAccounts: Boolean get() = WebAccounts.allowed(STATUS)
    override val statusMethod: String get() = STATUS
    override val authDomain = "deezer.com"
    override val pluginBased = true
    override val installUrl = BridgePlugin.installUrl
    override val notConfiguredName: String? = "bridge_needed_hint"
    /** The gateway and the public API both dislike bursts. */
    override val searchParallelism = 2

    override fun isConfigured(ctx: Env): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "deezer.status").containsKey("connected") }.getOrDefault(false)
    override fun isConnected(ctx: Env): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "deezer.status").getBoolean("connected", false) }.getOrDefault(false)
    override fun accountName(ctx: Env): String? = runCatching { call(ctx, "deezer.status").getString("account") }.getOrNull()

    override fun startAuth(ctx: Env) {
        if (BridgePlugin.packageName(ctx) == null) {
            installUrl?.let { ctx.android.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        BridgePlugin.startLogin(ctx, LOGIN_ACTION, slot)
    }

    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) { /* the connector completes the login itself */ }
    override fun disconnect(ctx: Env) { runCatching { call(ctx, "deezer.disconnect") } }

    override suspend fun playlists(ctx: Env): List<Playlist> = io(ctx, "deezer.playlists") { j -> j.arr.mapNotNull { toPlaylist(it) } }

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist =
        io(ctx, "deezer.playlistInfo", playlistId) { j -> toPlaylist(j) ?: Playlist(playlistId, playlistId) }

    override suspend fun playlistVersion(ctx: Env, playlistId: String): String? =
        withContext(Dispatchers.IO) { call(ctx, "deezer.version", playlistId).getString("version") }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = withContext(Dispatchers.IO) {
        val out = ArrayList<Track>()
        var offset: Int? = 0
        while (offset != null) {
            val b = call(ctx, "deezer.tracks", playlistId, Bundle().apply { putInt("offset", offset!!) })
            out += parseJson(b.getString("json") ?: "[]").arr.mapNotNull { toTrack(it) }
            offset = if (b.containsKey("next")) b.getInt("next") else null
        }
        out
    }

    override suspend fun track(ctx: Env, trackId: String): Track? = io(ctx, "deezer.track", trackId) { j -> toTrack(j) }

    override fun webSearchUrl(ctx: Env, query: String): String = "https://www.deezer.com/search/" + Uri.encode(query)

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        // Only tracks: the web route does not handle saved albums or followed artists.
        if (track.kind.isNotEmpty()) return emptyList()
        val q = listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }, Matcher.searchTitle(track.title)).joinToString(" ")
        val r = io(ctx, "deezer.search", q, Bundle().apply { putString("isrc", track.isrcNorm) }) { j -> j.arr.mapNotNull { toTrack(it) } }
        if (r.isNotEmpty()) return r
        // The plain title, when artist and title together found nothing (features, remixes).
        return io(ctx, "deezer.search", Matcher.searchTitle(track.title)) { j -> j.arr.mapNotNull { toTrack(it) } }
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist =
        io(ctx, "deezer.create", null, Bundle().apply { putString("name", name); putString("description", description) }) { j ->
            toPlaylist(j) ?: throw ProviderException("Deezer: playlist not created")
        }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) {
        withContext(Dispatchers.IO) { call(ctx, "deezer.rename", playlistId, Bundle().apply { putString("name", name) }) }
    }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) {
        withContext(Dispatchers.IO) { call(ctx, "deezer.delete", playlistId) }
    }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        withContext(Dispatchers.IO) {
            tracks.chunked(100).forEach { chunk ->
                call(ctx, "deezer.add", playlistId, Bundle().apply { putStringArray("ids", chunk.map { it.id }.toTypedArray()) })
            }
        }
    }

    /** By track id, like the official route: every copy of a track leaves the playlist. */
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        withContext(Dispatchers.IO) {
            tracks.chunked(100).forEach { chunk ->
                call(ctx, "deezer.remove", playlistId, Bundle().apply { putStringArray("ids", chunk.map { it.id }.toTypedArray()) })
            }
        }
    }

    private fun toPlaylist(j: JsonElement?): Playlist? {
        val id = j["id"].str ?: return null
        return Playlist(id, j["name"].str ?: "", j["count"]?.long?.toInt() ?: -1, ownedByMe = j["owned"].bool != false, description = j["description"].str.orEmpty())
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
            explicit = j["explicit"].bool,
            addedAt = j["addedAt"].long ?: 0,
            year = j["year"].long?.toInt() ?: 0,
            available = j["available"].bool,
        )
    }

    private suspend fun <T> io(ctx: Env, method: String, arg: String? = null, extras: Bundle? = null, map: (JsonElement?) -> T): T =
        withContext(Dispatchers.IO) { map(parseJson(call(ctx, method, arg, extras).getString("json") ?: "")) }

    private fun call(ctx: Env, method: String, arg: String? = null, extras: Bundle? = null): Bundle {
        if (!BridgePlugin.installed(ctx)) throw ProviderException(ctx.text("ytm_not_installed"))
        val b = try {
            BridgePlugin.call(ctx, method, arg, extras, slot)
        } catch (e: Exception) {
            throw ProviderException("Connector plugin: ${e.message ?: e.javaClass.simpleName}")
        } ?: throw ProviderException("Connector plugin: no answer")
        b.getString("error")?.let { throw ProviderException("$displayName: $it") }
        return b
    }

    companion object {
        const val SERVICE = "deezer_bridge"
        const val LOGIN_ACTION = "com.xlollx.songport.ytmbridge.DEEZER_LOGIN"
        /** The status call of this service, which says whether the connectors take extra accounts. */
        const val STATUS = "deezer.status"
    }
}
