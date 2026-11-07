package com.xlollx.songport.providers

import com.xlollx.songport.platform.Env
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import kotlinx.serialization.json.JsonElement

/**
 * Last.fm: sorgente in sola lettura dei brani "amati" di un utente (profilo pubblico, basta il
 * nome utente). Serve una API key gratuita e immediata (https://www.last.fm/api/account/create).
 */
class LastFmProvider(override val slot: String = "") : CredentialsProvider() {
    override val serviceId = SERVICE
    override val displayName = "Last.fm"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.OFFICIAL
    override val routeNoteName: String? get() = "route_note_api_readonly"
    override val brandColor = 0xFFD51007
    override val noteName = "provider_note_lastfm"
    // Out of beta (01/11/2026): used against real servers and accounts, and passing LiveServersIT.
    override val beta = false
    override val canWrite = false
    override val canRemoveTracks = false
    override val supportsLikedSongs = true
    override val supportsRecent = true
    override val loginForm = LoginForm(needsUrl = false, needsUser = true, needsSecret = false, hintName = "login_hint_lastfm")
    override val setupGuide = SetupGuide(
        dashboardUrl = "https://www.last.fm/api/account/create",
        stepUrls = listOf("https://www.last.fm/api/account/create", null, "https://www.last.fm/api/accounts"),
        whyName = "setup_why_lastfm",
        stepsArrayName = "setup_steps_lastfm",
        fieldLabelName = "setup_field_api_key",
    )

    private fun apiKey(ctx: Env): String =
        ctx.store.data.settings.clientIds[serviceId]?.trim()?.takeIf { it.isNotEmpty() } ?: BuildDefaults["LASTFM_API_KEY"]

    override fun isConfigured(ctx: Env) = apiKey(ctx).isNotBlank()
    override fun usesOwnCredentials(ctx: Env) = !ctx.store.data.settings.clientIds[serviceId].isNullOrBlank()

    private suspend fun call(ctx: Env, params: Map<String, String>): JsonElement {
        val key = apiKey(ctx)
        if (key.isBlank()) throw ProviderException(ctx.text("not_configured"))
        val resp = Http.send("GET", "https://ws.audioscrobbler.com/2.0/?" + Http.query(params + mapOf("api_key" to key, "format" to "json")))
        val j = runCatching { parseJson(resp.body) }.getOrElse { if (resp.ok) throw it else kotlinx.serialization.json.JsonNull }
        j["error"].int?.let { throw ProviderException("$displayName: ${j["message"].str ?: "error $it"}") }
        if (!resp.ok) throw ProviderException("$displayName: HTTP ${resp.code}")
        return j
    }

    override suspend fun login(ctx: Env, url: String, user: String, secret: String) {
        val name = call(ctx, mapOf("method" to "user.getinfo", "user" to user.trim()))["user"]["name"].str
            ?: throw ProviderException("$displayName: user not found")
        save(ctx, Tokens(accessToken = name, userName = name))
    }

    /** Last.fm has no playlists: what it keeps per user, named and explained as Last.fm calls it. */
    override fun libraryEntries(ctx: Env, asTarget: Boolean): List<Playlist> = if (asTarget) emptyList() else listOf(
        Playlist(MusicProvider.LIKED_ID, ctx.text("lastfm_loved"), description = ctx.text("lastfm_loved_desc")),
        Playlist(MusicProvider.RECENT_ID, ctx.text("recently_played"), description = ctx.text("lastfm_recent_desc")),
    )

    /** The one "playlist": the user's most played tracks of the last month. */
    override suspend fun playlists(ctx: Env): List<Playlist> =
        listOf(Playlist(TOP_MONTH_ID, ctx.text("lastfm_top_month"), description = ctx.text("lastfm_top_month_desc")))

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        val user = creds(ctx).userName
        if (playlistId == MusicProvider.RECENT_ID) {
            val j = call(ctx, mapOf("method" to "user.getrecenttracks", "user" to user, "limit" to "200"))
            return list(j["recenttracks"]["track"]).mapNotNull { t ->
                if (t["@attr"]["nowplaying"].str == "true") return@mapNotNull null
                val title = t["name"].str ?: return@mapNotNull null
                val artist = t["artist"]["#text"].str ?: t["artist"]["name"].str
                Track(
                    id = t["mbid"].str?.takeIf { it.isNotBlank() } ?: t["url"].str ?: "$title|$artist",
                    title = title, artists = listOfNotNull(artist), album = t["album"]["#text"].str ?: "",
                    addedAt = (t["date"]["uts"].str?.toLongOrNull() ?: 0) * 1000,
                )
            }.distinctBy { it.id }
        }
        if (playlistId == TOP_MONTH_ID) {
            val j = call(ctx, mapOf("method" to "user.gettoptracks", "user" to user, "period" to "1month", "limit" to "100"))
            return list(j["toptracks"]["track"]).mapNotNull { t ->
                val title = t["name"].str ?: return@mapNotNull null
                val artist = t["artist"]["name"].str
                Track(id = t["mbid"].str?.takeIf { it.isNotBlank() } ?: t["url"].str ?: "$title|$artist", title = title, artists = listOfNotNull(artist))
            }.distinctBy { it.id }
        }
        if (playlistId != MusicProvider.LIKED_ID) return emptyList()
        val out = ArrayList<Track>()
        var page = 1
        var pages = 1
        do {
            val j = call(ctx, mapOf("method" to "user.getlovedtracks", "user" to user, "limit" to "200", "page" to page.toString()))
            val root = j["lovedtracks"]
            pages = root["@attr"]["totalPages"].str?.toIntOrNull() ?: 1
            for (t in list(root["track"])) {
                val title = t["name"].str ?: continue
                out += Track(
                    id = t["mbid"].str?.takeIf { it.isNotBlank() } ?: t["url"].str ?: "$title|${t["artist"]["name"].str}",
                    title = title,
                    artists = listOfNotNull(t["artist"]["name"].str),
                )
            }
            page++
        } while (page <= pages && page <= 50)
        return out
    }

    /** Last.fm's JSON comes from its XML: a list of one arrives as the object itself, not an array of one. */
    private fun list(e: JsonElement?): List<JsonElement> = if (e is kotlinx.serialization.json.JsonObject) listOf(e) else e.arr

    override suspend fun search(ctx: Env, track: Track): List<Track> = unsupported(ctx)
    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist = unsupported(ctx)
    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) = unsupported(ctx)
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) = unsupported(ctx)

    companion object {
        const val SERVICE = "lastfm"
        /** The most played tracks of the last month (user.gettoptracks, period 1month). */
        const val TOP_MONTH_ID = "lastfm:top:1month"
    }
}
