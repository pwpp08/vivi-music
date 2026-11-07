package com.xlollx.songport.providers

import android.content.Context
import com.xlollx.songport.platform.Env
import com.xlollx.songport.platform.android
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import com.xlollx.songport.R
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * YouTube Music through the optional companion app "Songport YTM Bridge".
 *
 * Trackhop itself never talks to YouTube here: it asks the Bridge, installed separately (not from
 * Google Play), through a ContentProvider. The Bridge holds the login and answers with playlists and
 * tracks. No Google Cloud project, no quota. The official Data API route stays available as
 * [YouTubeProvider] for whoever prefers it.
 */
class YouTubeBridgeProvider(override val slot: String = "") : MusicProvider, WebAccounts.Connector {
    override val canRenamePlaylists: Boolean get() = BridgePlugin.full
    override val canDeletePlaylists: Boolean get() = BridgePlugin.full
    override val serviceId = SERVICE
    override val displayName = "YouTube Music"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.EASY
    override val routeNoteName: String? get() = "route_note_web"
    override val familyName: String get() = "YouTube Music"
    override val brandColor = 0xFFFF0000
    override val noteName = "provider_note_ytm"
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    /** Saved albums and subscribed artists: the built-in connectors know them, an old plugin may not. */
    override val supportsAlbums: Boolean get() = BridgePlugin.full
    override val supportsArtists: Boolean get() = BridgePlugin.full
    override val supportsRecent: Boolean get() = BridgePlugin.full
    /** Another account: where the WebView keeps profiles apart and the connectors take a slot (see [WebAccounts]). */
    override val supportsMultipleAccounts: Boolean get() = WebAccounts.allowed(STATUS)
    override val statusMethod: String get() = STATUS
    /** An extra account of a web connector is new: said so, as the other new connectors are. */
    override val beta: Boolean get() = slot.isNotEmpty()
    override val authDomain = "accounts.google.com"
    override val revokeUrl = "https://myaccount.google.com/device-activity"
    override val installUrl = BridgePlugin.installUrl
    override val notConfiguredName: String? = "ytm_not_installed_hint"
    override val pluginBased = true
    // The web interface answers 403 to bursts of searches: one at a time, paced by the Bridge.
    // Due alla volta: Google segna l'indirizzo quando il ritmo non e' quello di una persona, e il
    // Bridge impone comunque una cadenza minima; oltre due non si guadagna nulla e si rischia il blocco.
    override val searchParallelism = 2

    /** "Configured" here means the Bridge app is installed. */
    override fun isConfigured(ctx: Env): Boolean = installed(ctx)

    override fun isConnected(ctx: Env): Boolean =
        installed(ctx) && runCatching { call(ctx, "status").getBoolean("connected", false) }.getOrDefault(false)

    override fun accountName(ctx: Env): String? =
        runCatching { call(ctx, "status").getString("account") }.getOrNull()

    override fun startAuth(ctx: Env) {
        val pkg = BridgePlugin.packageName(ctx)
        if (pkg == null) {
            installUrl?.let { ctx.android.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        BridgePlugin.startLogin(ctx, LOGIN_ACTION, slot)
    }

    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) { /* the Bridge completes the login itself */ }

    override fun disconnect(ctx: Env) { runCatching { call(ctx, "disconnect") } }

    /** A channel the account can act as: its own ([id] "") or a brand account (its page id). */
    class Channel(val id: String, val name: String, val byline: String?, val selected: Boolean)

    /**
     * True when the connectors offer the choice of the channel: the built-in ones do, a plugin says
     * so with `channels` in its status answer (an older Bridge says nothing and offers nothing).
     */
    fun channelsSupported(ctx: Env): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "status").containsKey("channels") }.getOrDefault(false)

    /** The chosen channel's name, for the card; null for the account's own channel (the default). */
    fun channelName(ctx: Env): String? = runCatching { call(ctx, "status").getString("channelName") }.getOrNull()

    /** The channels the signed-in account can act as, the chosen one marked. Blocking: off the main thread. */
    fun channels(ctx: Env): List<Channel> = parseJson(call(ctx, "channels").getString("json") ?: "[]").arr.mapNotNull { j ->
        Channel(j["id"].str ?: return@mapNotNull null, j["name"].str ?: return@mapNotNull null, j["byline"].str, j["selected"].bool == true)
    }

    /** Makes the account's calls as [channel] ("" for its own channel). Blocking: off the main thread. */
    fun setChannel(ctx: Env, channel: Channel) {
        call(ctx, "channel", channel.id, Bundle().apply { putString("name", channel.name) })
    }

    override suspend fun playlists(ctx: Env): List<Playlist> = io(ctx, "playlists") { j ->
        j.arr.mapNotNull { p ->
            val id = p["id"].str ?: return@mapNotNull null
            Playlist(id, p["name"].str ?: id, p["count"].int ?: -1)
        }
    }

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist = io(ctx, "playlistInfo", playlistId) { j ->
        Playlist(playlistId, j["name"].str ?: playlistId, j["count"].int ?: -1, ownedByMe = false)
    }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = withContext(Dispatchers.IO) {
        val out = ArrayList<Track>()
        var offset: Int? = 0
        while (offset != null) {
            val b = call(ctx, "tracks", playlistId, Bundle().apply { putInt("offset", offset!!) })
            out += parseJson(b.getString("json") ?: "[]").arr.mapNotNull { toTrack(it) }
            offset = if (b.containsKey("next")) b.getInt("next") else null
        }
        out
    }

    override fun webSearchUrl(ctx: Env, query: String): String = "https://music.youtube.com/search?q=" + android.net.Uri.encode(query)

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        val kind = when (track.kind) { Track.KIND_ALBUM -> "album"; Track.KIND_ARTIST -> "artist"; Track.KIND_PODCAST -> return emptyList(); else -> null }
        val q = if (kind == "artist") track.title else (track.artists.take(2) + track.title).joinToString(" ")
        return io(ctx, "search", q, Bundle().apply { kind?.let { putString("kind", it) } }) { j -> j.arr.mapNotNull { toTrack(it) } }
    }

    /** YouTube videos, for a song the music catalogue does not have (an old Bridge ignores the flag and repeats the songs). */
    override suspend fun searchWide(ctx: Env, track: Track): List<Track> {
        val q = (track.artists.take(2) + track.title).joinToString(" ")
        return io(ctx, "search", q, Bundle().apply { putBoolean("videos", true) }) { j -> j.arr.mapNotNull { toTrack(it) } }
    }

    /** Titolo e canale da oEmbed di YouTube: pubblico, senza quota e senza passare dal Bridge. */
    override suspend fun track(ctx: Env, trackId: String): Track? {
        val r = com.xlollx.songport.net.Http.send("GET", "https://www.youtube.com/oembed?format=json&url=" +
            com.xlollx.songport.net.Http.enc("https://www.youtube.com/watch?v=$trackId"))
        if (!r.ok) return null
        val j = parseJson(r.body)
        val (title, artists) = com.xlollx.songport.sync.TitleParser.parseYouTube(j["title"].str ?: return null, j["author_name"].str)
        return Track(id = trackId, title = title, artists = artists)
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist =
        io(ctx, "create", null, Bundle().apply { putString("name", name); putString("description", description) }) { j ->
            Playlist(j["id"].str ?: throw ProviderException("YouTube Music: playlist not created"), name, 0)
        }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) {
        withContext(Dispatchers.IO) { call(ctx, "rename", playlistId, Bundle().apply { putString("name", name) }) }
    }

    override val canReorder: Boolean get() = BridgePlugin.full
    override suspend fun reorderTracks(ctx: Env, playlistId: String, current: List<Track>, ordered: List<Track>) {
        // Entries move by their set id; a listing without them cannot be reordered.
        if (current.any { it.itemId.isNullOrEmpty() }) return
        val steps = com.xlollx.songport.sync.Reorder.before(current.map { it.itemId!! }, ordered.map { it.itemId!! })
        if (steps.isEmpty()) return
        withContext(Dispatchers.IO) {
            call(ctx, "reorder", playlistId, Bundle().apply {
                putStringArray("items", steps.map { it.first }.toTypedArray())
                putStringArray("before", steps.map { it.second }.toTypedArray())
            })
        }
    }

    override val canSetVisibility: Boolean get() = BridgePlugin.full
    override suspend fun setPlaylistVisibility(ctx: Env, playlistId: String, public: Boolean) {
        withContext(Dispatchers.IO) { call(ctx, "visibility", playlistId, Bundle().apply { putBoolean("public", public) }) }
    }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) {
        withContext(Dispatchers.IO) { call(ctx, "delete", playlistId) }
    }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        addTracksReportingPresent(ctx, playlistId, tracks)
    }

    /**
     * Towards Liked songs, the likes YouTube answered as going to Liked videos (the Bridge's
     * "present"): liked, but Liked music will not list them, so the sync counts them as present.
     * An older Bridge app, and every playlist, report none.
     */
    override suspend fun addTracksReportingPresent(ctx: Env, playlistId: String, tracks: List<Track>): Set<String> {
        if (tracks.isEmpty()) return emptySet()
        return withContext(Dispatchers.IO) {
            val b = call(ctx, "add", playlistId, Bundle().apply {
                putStringArray("ids", tracks.map { it.id }.toTypedArray())
                // Albums are liked through their playlist id, which the row carried as uri when it had one.
                if (playlistId == MusicProvider.ALBUMS_ID) putStringArray("uris", tracks.map { it.uri ?: "" }.toTypedArray())
            })
            b.getStringArray("present")?.toSet() ?: emptySet()
        }
    }

    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (playlistId == MusicProvider.ALBUMS_ID || playlistId == MusicProvider.ARTISTS_ID) {
            withContext(Dispatchers.IO) {
                call(ctx, "remove", playlistId, Bundle().apply {
                    putStringArray("ids", tracks.map { it.id }.toTypedArray())
                    putStringArray("uris", tracks.map { it.uri ?: "" }.toTypedArray())
                })
            }
            return
        }
        val items = tracks.joinToString(",", "[", "]") { t ->
            """{"videoId":${quote(t.id)}${t.itemId?.let { ",\"setVideoId\":${quote(it)}" } ?: ""}}"""
        }
        withContext(Dispatchers.IO) { call(ctx, "remove", playlistId, Bundle().apply { putString("json", items) }) }
    }

    // ------------------------------------------------------------------ plumbing

    private fun toTrack(j: JsonElement?): Track? {
        val id = j["id"].str ?: return null
        return Track(
            id = id,
            title = j["title"].str ?: return null,
            artists = j["artists"].arr.mapNotNull { it.str },
            album = j["album"].str ?: "",
            durationMs = j["durationMs"].long ?: 0,
            itemId = j["setVideoId"].str,
            uri = j["uri"].str,
            kind = when (j["kind"].str) { "album" -> Track.KIND_ALBUM; "artist" -> Track.KIND_ARTIST; else -> "" },
            year = j["year"].long?.toInt() ?: 0,
            available = j["available"].bool,
        )
    }

    /** The built-in connector marks greyed-out entries; an older Bridge app does not. */
    override val detectsUnplayable: Boolean get() = BridgePlugin.full

    private suspend fun <T> io(ctx: Env, method: String, arg: String? = null, extras: Bundle? = null, map: (JsonElement?) -> T): T =
        withContext(Dispatchers.IO) { map(parseJson(call(ctx, method, arg, extras).getString("json") ?: "")) }

    private fun call(ctx: Env, method: String, arg: String? = null, extras: Bundle? = null): Bundle {
        if (!installed(ctx)) throw ProviderException(ctx.text("ytm_not_installed"))
        val b = try {
            BridgePlugin.call(ctx, method, arg, extras, slot)
        } catch (e: Exception) {
            throw ProviderException("Connector plugin: ${e.message ?: e.javaClass.simpleName}")
        } ?: throw ProviderException("Connector plugin: no answer")
        b.getString("error")?.let { throw ProviderException("YouTube Music: $it") }
        return b
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        const val SERVICE = "ytm"
        const val PACKAGE = "com.xlollx.songport.ytmbridge"
        const val LOGIN_ACTION = "com.xlollx.songport.ytmbridge.LOGIN"
        /** The status call of this service, which says whether the connectors take extra accounts. */
        const val STATUS = "status"
        fun installed(ctx: Env): Boolean = BridgePlugin.installed(ctx)
    }
}
