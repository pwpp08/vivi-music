package com.xlollx.songport.providers

import com.xlollx.songport.platform.Env
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.isNullish
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Matcher
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Deezer API (https://developers.deezer.com). Usa il flusso OAuth "implicit" (response_type=token):
 * il token arriva nel fragment del redirect, non serve un client secret. Deezer accetta solo
 * redirect https: la pagina statica docs/deezer-redirect.html rimanda a songport://callback.
 * BETA: la registrazione di nuove app sul portale Deezer potrebbe non essere disponibile.
 */
class DeezerProvider(override val slot: String = "") : OAuthProvider() {
    override val serviceId = SERVICE
    override val loginInApp: Boolean get() = true
    override val revokeUrl = "https://www.deezer.com/account/apps"
    override val displayName = "Deezer"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.OFFICIAL
    override val routeNoteName: String? get() = "route_note_api"
    override val brandColor = 0xFFA238FF
    override val noteName = "provider_note_deezer"
    override val beta = true
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    override val supportsAlbums = true
    override val supportsArtists = true
    override val supportsPodcasts = true

    override val setupGuide = SetupGuide(
        dashboardUrl = "https://developers.deezer.com/myapps",
        stepUrls = listOf("https://developers.deezer.com/myapps", "https://developers.deezer.com/myapps/create", null, "https://developers.deezer.com/myapps"),
        whyName = "setup_why_deezer",
        stepsArrayName = "setup_steps_deezer",
        fieldLabelName = "setup_field_app_id",
        needsRedirectUrl = true,
    )

    override val defaultClientId: String get() = BuildDefaults["DEEZER_APP_ID"]
    override val authorizeEndpoint = "https://connect.deezer.com/oauth/auth.php"
    override val tokenEndpoint = "" // non usato: flusso implicito
    override val scopes = "basic_access,manage_library,delete_library,offline_access"


    override fun redirectUri(ctx: Env): String =
        ctx.store.data.settings.deezerRedirectUrl.trim().ifEmpty { BuildDefaults["DEEZER_REDIRECT_URL"] }

    override fun isConfigured(ctx: Env) = clientId(ctx).isNotBlank() && redirectUri(ctx).startsWith("https://")

    override fun authUrl(ctx: Env, state: String, codeChallenge: String): String =
        authorizeEndpoint + "?" + Http.query(
            mapOf(
                "app_id" to clientId(ctx),
                "redirect_uri" to redirectUri(ctx),
                "perms" to scopes,
                "response_type" to "token",
                "state" to state,
            )
        )

    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) {
        val token = params["access_token"] ?: throw ProviderException("Deezer: no access_token in redirect")
        val expires = params["expires"]?.toLongOrNull() ?: 0L
        var t = Tokens(accessToken = token, expiresAt = if (expires > 0) System.currentTimeMillis() + expires * 1000 else 0)
        val store = ctx.tokens
        store.set(id, t)
        val me = dz(ctx, "GET", "/user/me")
        t = t.copy(userId = me["id"].long?.toString() ?: "", userName = me["name"].str ?: "")
        store.set(id, t)
    }

    override suspend fun refresh(ctx: Env, t: Tokens): Tokens {
        ctx.tokens.clear(id)
        throw ProviderException("Deezer: session expired, please reconnect")
    }

    /** Chiamata Deezer: token come parametro di query; gli errori arrivano come JSON `error` con HTTP 200. */
    private suspend fun dz(ctx: Env, method: String, path: String, params: Map<String, String?> = emptyMap(), allowNoData: Boolean = false): JsonElement {
        // Public playlists are readable without an account (the same requests deezer.com makes for
        // a visitor): a GET goes out without a token when none is stored. Anything else needs it.
        val t = if (method == "GET" && ctx.tokens.get(id) == null) null else tokens(ctx)
        val url = if (path.startsWith("http")) path + (t?.let { (if ('?' in path) "&" else "?") + "access_token=${Http.enc(it.accessToken)}" } ?: "")
        else "$API$path?" + Http.query(if (t == null) params else params + ("access_token" to t.accessToken))
        var attempt = 0
        while (true) {
            val resp = Http.send(method, url, mapOf("Accept" to "application/json"))
            if (!resp.ok) throw ProviderException("Deezer API ${resp.code}: ${resp.body.take(200)}")
            val j = parseJson(resp.body)
            val err = j["error"]
            if (err.isNullish) return j
            val code = err["code"].int ?: 0
            if (allowNoData && code == 800) return JsonNull
            // Error 4, "Quota limit exceeded": Deezer allows 50 requests every 5 seconds. Waiting out
            // the window is enough; a few windows in a row mean something else is wrong.
            if (code == QUOTA && attempt < QUOTA_RETRIES) { attempt++; Http.pause(QUOTA_WINDOW_MS); continue }
            if (code == 300 || code == 200) ctx.tokens.clear(id) // token non valido / permesso mancante
            throw ProviderException("Deezer: ${err["message"].str ?: err.toString()}")
        }
    }

    override suspend fun playlists(ctx: Env): List<Playlist> {
        val me = tokens(ctx).userId
        val out = ArrayList<Playlist>()
        var url: String? = "$API/user/me/playlists?limit=100"
        while (url != null) {
            val j = dz(ctx, "GET", url)
            for (p in j["data"].arr) {
                if (p["is_loved_track"].bool == true) continue // gia' esposta come "Brani preferiti"
                out += Playlist(
                    id = p["id"].long?.toString() ?: continue,
                    name = p["title"].str ?: "",
                    trackCount = p["nb_tracks"].int ?: -1,
                    ownedByMe = p["creator"]["id"].long?.toString() == me,
                    imageUrl = p["picture_xl"].str ?: p["picture_big"].str,
                    isPublic = p["public"].bool,
                )
            }
            url = j["next"].str
        }
        return out
    }

    private fun toTrack(t: JsonElement?): Track? {
        val id = t["id"].long?.toString() ?: return null
        return Track(
            id = id,
            title = t["title"].str ?: "",
            artists = listOfNotNull(t["artist"]["name"].str),
            album = t["album"]["title"].str ?: "",
            durationMs = (t["duration"].long ?: 0) * 1000,
            isrc = t["isrc"].str,
            explicit = t["explicit_lyrics"].bool,
            // Deezer says of every listed track whether the account can play it.
            available = t["readable"].bool,
        )
    }

    /** A public playlist is readable from its link with no account; the library slots need one. */
    override fun canRead(ctx: Env, playlistId: String): Boolean = isConnected(ctx) || !MusicProvider.isLibrary(playlistId)
    override val detectsUnplayable: Boolean get() = true

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist {
        val j = dz(ctx, "GET", "/playlist/$playlistId")
        return Playlist(playlistId, j["title"].str ?: playlistId, j["nb_tracks"].int ?: -1, ownedByMe = false, description = j["description"].str.orEmpty(), imageUrl = j["picture_xl"].str ?: j["picture_big"].str)
    }

    override suspend fun playlistVersion(ctx: Env, playlistId: String): String? = when (playlistId) {
        MusicProvider.LIKED_ID -> dz(ctx, "GET", "/user/me/tracks", mapOf("limit" to "1")).let { "${it["total"].int}|${it["data"][0]["id"].long}" }
        MusicProvider.ALBUMS_ID, MusicProvider.ARTISTS_ID, MusicProvider.PODCASTS_ID, MusicProvider.RECENT_ID -> null
        else -> dz(ctx, "GET", "/playlist/$playlistId")["checksum"].str
    }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        val out = ArrayList<Track>()
        var url: String? = when (playlistId) {
            MusicProvider.LIKED_ID -> "$API/user/me/tracks?limit=100"
            MusicProvider.ALBUMS_ID -> "$API/user/me/albums?limit=100"
            MusicProvider.ARTISTS_ID -> "$API/user/me/artists?limit=100"
            MusicProvider.PODCASTS_ID -> "$API/user/me/podcasts?limit=100"
            else -> "$API/playlist/$playlistId/tracks?limit=100"
        }
        while (url != null) {
            val j = dz(ctx, "GET", url)
            j["data"].arr.forEach { t ->
                when (playlistId) {
                    MusicProvider.ALBUMS_ID -> toAlbum(t)
                    MusicProvider.ARTISTS_ID -> toArtist(t)
                    MusicProvider.PODCASTS_ID -> toPodcast(t)
                    else -> toTrack(t)?.copy(addedAt = (t["time_add"].long ?: 0) * 1000)
                }?.let { out += it }
            }
            url = j["next"].str
        }
        return out
    }

    private fun toAlbum(a: JsonElement?): Track? {
        val id = a["id"].long?.toString() ?: return null
        return Track(id = id, title = a["title"].str ?: "", artists = listOfNotNull(a["artist"]["name"].str), isrc = a["upc"].str, kind = Track.KIND_ALBUM)
    }

    private fun toPodcast(p: JsonElement?): Track? {
        val id = p["id"].long?.toString() ?: return null
        return Track(id = id, title = p["title"].str ?: return null, kind = Track.KIND_PODCAST)
    }

    private fun toArtist(a: JsonElement?): Track? {
        val id = a["id"].long?.toString() ?: return null
        val name = a["name"].str ?: return null
        return Track(id = id, title = name, artists = listOf(name), kind = Track.KIND_ARTIST)
    }

    override suspend fun track(ctx: Env, trackId: String): Track? = toTrack(dz(ctx, "GET", "/track/$trackId", allowNoData = true))

    override fun webSearchUrl(ctx: Env, query: String): String = "https://www.deezer.com/search/" + Http.enc(query)

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        when (track.kind) {
            Track.KIND_ALBUM -> {
                track.isrcNorm?.let { upc ->
                    val j = dz(ctx, "GET", "/album/upc:$upc", allowNoData = true)
                    toAlbum(j)?.let { return listOf(it) }
                }
                val artist = track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }
                var j = dz(ctx, "GET", "/search/album", mapOf("q" to "album:\"${Matcher.searchTitle(track.title)}\"" + (artist?.let { " $it" } ?: ""), "limit" to "5"))
                var res = j["data"].arr.mapNotNull { toAlbum(it) }
                if (res.isEmpty()) {
                    j = dz(ctx, "GET", "/search/album", mapOf("q" to listOfNotNull(artist, Matcher.searchTitle(track.title)).joinToString(" "), "limit" to "5"))
                    res = j["data"].arr.mapNotNull { toAlbum(it) }
                }
                return res
            }
            Track.KIND_ARTIST -> {
                val j = dz(ctx, "GET", "/search/artist", mapOf("q" to track.title, "limit" to "5"))
                return j["data"].arr.mapNotNull { toArtist(it) }
            }
            Track.KIND_PODCAST -> {
                val j = dz(ctx, "GET", "/search/podcast", mapOf("q" to track.title, "limit" to "5"))
                return j["data"].arr.mapNotNull { toPodcast(it) }
            }
        }
        track.isrcNorm?.let { isrc ->
            val j = dz(ctx, "GET", "/track/isrc:$isrc", allowNoData = true)
            toTrack(j)?.let { return listOf(it) }
        }
        val title = Matcher.searchTitle(track.title)
        val artist = track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }
        // Deezer's artist:"…" filter answers nothing (#46): the title filtered, the artist as plain words.
        val fielded = buildString {
            append("track:\"").append(title).append('"')
            if (!artist.isNullOrBlank()) append(' ').append(artist)
        }
        var j = dz(ctx, "GET", "/search/track", mapOf("q" to fielded, "limit" to "5"))
        var res = j["data"].arr.mapNotNull { toTrack(it) }
        if (res.isEmpty()) {
            j = dz(ctx, "GET", "/search/track", mapOf("q" to listOfNotNull(artist, title).joinToString(" "), "limit" to "5"))
            res = j["data"].arr.mapNotNull { toTrack(it) }
        }
        return res
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist {
        val j = dz(ctx, "POST", "/user/me/playlists", mapOf("title" to name))
        return Playlist(j["id"].long?.toString() ?: throw ProviderException("Deezer: playlist not created"), name, 0)
    }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) {
        dz(ctx, "POST", "/playlist/$playlistId", mapOf("title" to name))
    }

    /** Deezer takes the whole order in one call. */
    override val canReorder: Boolean get() = true
    override suspend fun reorderTracks(ctx: Env, playlistId: String, current: List<Track>, ordered: List<Track>) {
        dz(ctx, "POST", "/playlist/$playlistId/tracks", mapOf("order" to ordered.joinToString(",") { it.id }))
    }

    override val canSetVisibility: Boolean get() = true
    override suspend fun setPlaylistVisibility(ctx: Env, playlistId: String, public: Boolean) {
        dz(ctx, "POST", "/playlist/$playlistId", mapOf("public" to public.toString()))
    }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) {
        dz(ctx, "DELETE", "/playlist/$playlistId")
    }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (playlistId == MusicProvider.LIKED_ID) {
            tracks.forEach { dz(ctx, "POST", "/user/me/tracks", mapOf("track_id" to it.id)) }
            return
        }
        if (playlistId == MusicProvider.ALBUMS_ID) {
            tracks.forEach { dz(ctx, "POST", "/user/me/albums", mapOf("album_id" to it.id)) }
            return
        }
        if (playlistId == MusicProvider.ARTISTS_ID) {
            tracks.forEach { dz(ctx, "POST", "/user/me/artists", mapOf("artist_id" to it.id)) }
            return
        }
        if (playlistId == MusicProvider.PODCASTS_ID) {
            tracks.forEach { dz(ctx, "POST", "/user/me/podcasts", mapOf("podcast_id" to it.id)) }
            return
        }
        tracks.chunked(50).forEach { chunk ->
            dz(ctx, "POST", "/playlist/$playlistId/tracks", mapOf("songs" to chunk.joinToString(",") { it.id }))
        }
    }

    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (playlistId == MusicProvider.LIKED_ID) {
            tracks.forEach { dz(ctx, "DELETE", "/user/me/tracks", mapOf("track_id" to it.id)) }
            return
        }
        if (playlistId == MusicProvider.ALBUMS_ID) {
            tracks.forEach { dz(ctx, "DELETE", "/user/me/albums", mapOf("album_id" to it.id)) }
            return
        }
        if (playlistId == MusicProvider.ARTISTS_ID) {
            tracks.forEach { dz(ctx, "DELETE", "/user/me/artists", mapOf("artist_id" to it.id)) }
            return
        }
        if (playlistId == MusicProvider.PODCASTS_ID) {
            tracks.forEach { dz(ctx, "DELETE", "/user/me/podcasts", mapOf("podcast_id" to it.id)) }
            return
        }
        tracks.chunked(50).forEach { chunk ->
            dz(ctx, "DELETE", "/playlist/$playlistId/tracks", mapOf("songs" to chunk.joinToString(",") { it.id }))
        }
    }

    companion object {
        const val SERVICE = "deezer"
        private const val API = "https://api.deezer.com"
        private const val QUOTA = 4
        private const val QUOTA_RETRIES = 3
        private const val QUOTA_WINDOW_MS = 5_000L
    }
}
