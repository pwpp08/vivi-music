package com.xlollx.songport.providers

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env
import com.xlollx.songport.platform.android
import com.xlollx.songport.sync.Durations
import com.xlollx.songport.sync.Matcher
import com.xlollx.songport.sync.SoundCloudPublic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * SoundCloud with the user's account, through the web app's session kept by the companion
 * connectors ("Trackhop Bridge", built into this app on GitHub and F-Droid): the user signs in on
 * soundcloud.com, and their sets and liked tracks become sources and destinations.
 *
 * It is also the "soundcloud" service public links have always used: one service id, so a sync made
 * from a pasted set keeps working and the picker shows one SoundCloud. A playlist id with a slash is
 * the path of a public page ("user/sets/slug", "user/likes"): in the builds that read SoundCloud
 * themselves it goes to [SoundCloudProvider], with no sign-in, exactly as before; in the Google Play
 * build it goes through the plugin, when one serves SoundCloud. Any other id is a set of the account.
 *
 * Tracks arrive as SoundCloud's own track objects and are read by [SoundCloudPublic.parseTrack],
 * the public-link reader's parser: few ISRCs, so the matching uses title, artist and duration.
 * Not an official interface: it can stop working when SoundCloud changes its web app.
 */
class SoundCloudBridgeProvider(override val slot: String = "") : MusicProvider {
    override val serviceId = SoundCloudPublic.SERVICE
    override val displayName = if (BridgePlugin.builtIn) "SoundCloud (web)" else "SoundCloud (plugin)"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.EASY
    override val routeNoteName: String? get() = "route_note_web_only"
    override val familyName: String get() = "SoundCloud"
    override val brandColor = 0xFFFF5500
    override val noteName = "provider_note_soundcloud_account"
    // Tried on a real account (sign-in, renewal, liked songs to a playlist): out of alpha.
    override val beta = true
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    override val supportsMultipleAccounts = false
    override val authDomain = "soundcloud.com"
    override val pluginBased = true
    override val installUrl = BridgePlugin.installUrl
    override val notConfiguredName: String? = "bridge_needed_hint"
    override val searchParallelism = 1
    override val canReorder: Boolean get() = true
    override val canSetVisibility: Boolean get() = true

    /** Reads SoundCloud's public pages itself: the builds that carry the web connectors. */
    private val readsLinksItself: Boolean get() = BridgePlugin.builtIn

    /**
     * Where the service is named: the connector's name once the user added it, otherwise (a sync
     * from a pasted link) what kind of access that is, and that it is unofficial, as before.
     */
    override fun label(ctx: Env): String =
        if (Providers.connectors().any { it.id == id }) super.label(ctx) else ctx.text("soundcloud_label")

    override fun isConfigured(ctx: Env): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "soundcloud.status").containsKey("connected") }.getOrDefault(false)
    override fun isConnected(ctx: Env): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "soundcloud.status").getBoolean("connected", false) }.getOrDefault(false)
    override fun accountName(ctx: Env): String? = runCatching { call(ctx, "soundcloud.status").getString("account") }.getOrNull()

    /** A public page needs no sign-in; the account's sets and likes do. */
    override fun canRead(ctx: Env, playlistId: String): Boolean = when {
        isPage(playlistId) -> readsLinksItself || isConfigured(ctx)
        else -> isConnected(ctx)
    }

    override fun startAuth(ctx: Env) {
        if (BridgePlugin.packageName(ctx) == null) {
            installUrl?.let { ctx.android.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        BridgePlugin.startLogin(ctx, LOGIN_ACTION)
    }

    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) { /* the connector completes the login itself */ }
    override fun disconnect(ctx: Env) { runCatching { call(ctx, "soundcloud.disconnect") } }

    override suspend fun playlists(ctx: Env): List<Playlist> = io(ctx, "soundcloud.playlists") { j -> j.arr.mapNotNull { toPlaylist(it) } }

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist =
        if (isPage(playlistId) && readsLinksItself) SoundCloudProvider.playlistInfo(ctx, playlistId)
        else io(ctx, "soundcloud.playlistInfo", playlistId) { j -> toPlaylist(j) ?: Playlist(playlistId, playlistId) }

    override suspend fun playlistVersion(ctx: Env, playlistId: String): String? =
        if (isPage(playlistId) && readsLinksItself) SoundCloudProvider.playlistVersion(ctx, playlistId)
        else withContext(Dispatchers.IO) { call(ctx, "soundcloud.version", playlistId).getString("version") }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        if (isPage(playlistId) && readsLinksItself) return SoundCloudProvider.tracks(ctx, playlistId)
        return withContext(Dispatchers.IO) {
            val out = ArrayList<Track>()
            var offset: Int? = 0
            while (offset != null) {
                val b = call(ctx, "soundcloud.tracks", playlistId, Bundle().apply { putInt("offset", offset!!) })
                out += parseJson(b.getString("json") ?: "[]").arr.mapNotNull { toTrack(it) }
                offset = if (b.containsKey("next")) b.getInt("next") else null
            }
            out
        }
    }

    override fun webSearchUrl(ctx: Env, query: String): String = "${SoundCloudPublic.SITE}/search/sounds?q=${Http.enc(query)}"

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        if (track.kind.isNotEmpty()) return emptyList()
        val q = listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }, Matcher.searchTitle(track.title)).joinToString(" ")
        val r = io(ctx, "soundcloud.search", q) { j -> j.arr.mapNotNull { toTrack(it) } }
        if (r.isNotEmpty()) return r
        return io(ctx, "soundcloud.search", Matcher.searchTitle(track.title)) { j -> j.arr.mapNotNull { toTrack(it) } }
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist =
        io(ctx, "soundcloud.create", null, Bundle().apply { putString("name", name); putString("description", description) }) { j ->
            toPlaylist(j) ?: throw ProviderException("SoundCloud: playlist not created")
        }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) {
        withContext(Dispatchers.IO) { call(ctx, "soundcloud.rename", playlistId, Bundle().apply { putString("name", name) }) }
    }

    override suspend fun setPlaylistVisibility(ctx: Env, playlistId: String, public: Boolean) {
        withContext(Dispatchers.IO) { call(ctx, "soundcloud.visibility", playlistId, Bundle().apply { putBoolean("public", public) }) }
    }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) {
        withContext(Dispatchers.IO) { call(ctx, "soundcloud.delete", playlistId) }
    }

    /** A set is written whole: one call with every id, so a sync adds its tracks in one go. */
    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        withContext(Dispatchers.IO) {
            call(ctx, "soundcloud.add", playlistId, Bundle().apply { putStringArray("ids", tracks.map { it.id }.toTypedArray()) })
        }
    }

    /** By track id: every copy of a track leaves the set (as removal by id does on Spotify and Deezer). */
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        withContext(Dispatchers.IO) {
            call(ctx, "soundcloud.remove", playlistId, Bundle().apply { putStringArray("ids", tracks.map { it.id }.distinct().toTypedArray()) })
        }
    }

    /** The whole order in one call: SoundCloud takes a set's full track list. */
    override suspend fun reorderTracks(ctx: Env, playlistId: String, current: List<Track>, ordered: List<Track>) {
        if (ordered.map { it.id } == current.map { it.id }) return
        withContext(Dispatchers.IO) {
            call(ctx, "soundcloud.reorder", playlistId, Bundle().apply { putStringArray("ids", ordered.map { it.id }.toTypedArray()) })
        }
    }

    private fun toPlaylist(j: JsonElement?): Playlist? {
        val id = j["id"].str ?: return null
        return Playlist(id, j["name"].str ?: "", j["count"]?.long?.toInt() ?: -1, ownedByMe = j["owned"].bool != false, description = j["description"].str.orEmpty())
    }

    /** SoundCloud's track object, read exactly as the public-link reader reads it; a like's date when there is one. */
    private fun toTrack(j: JsonElement?): Track? = SoundCloudPublic.parseTrack(j, Durations.parseInstant(j["liked_at"].str))

    private suspend fun <T> io(ctx: Env, method: String, arg: String? = null, extras: Bundle? = null, map: (JsonElement?) -> T): T =
        withContext(Dispatchers.IO) { map(parseJson(call(ctx, method, arg, extras).getString("json") ?: "")) }

    private fun call(ctx: Env, method: String, arg: String? = null, extras: Bundle? = null): Bundle {
        if (!BridgePlugin.installed(ctx)) throw ProviderException(ctx.text("ytm_not_installed"))
        val b = try {
            BridgePlugin.call(ctx, method, arg, extras)
        } catch (e: Exception) {
            throw ProviderException("Connector plugin: ${e.message ?: e.javaClass.simpleName}")
        } ?: throw ProviderException("Connector plugin: no answer")
        b.getString("error")?.let { throw ProviderException("$displayName: $it") }
        return b
    }

    companion object {
        const val LOGIN_ACTION = "com.xlollx.songport.ytmbridge.SOUNDCLOUD_LOGIN"

        /** The path of a public page, as a pasted link gives it, rather than a set of the account. */
        fun isPage(playlistId: String): Boolean = '/' in playlistId
    }
}
