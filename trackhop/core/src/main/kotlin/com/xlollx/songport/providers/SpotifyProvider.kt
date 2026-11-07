package com.xlollx.songport.providers

import com.xlollx.songport.platform.Env
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.model.UnplayableEntry
import com.xlollx.songport.sync.Unplayable
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.HttpResponse
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.isNullish
import com.xlollx.songport.net.jsonObj
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Durations
import com.xlollx.songport.sync.Matcher
import kotlinx.serialization.json.JsonElement

/**
 * Spotify Web API (https://developer.spotify.com/documentation/web-api).
 * Nota: un'app in "Development mode" accetta al massimo 25 utenti registrati nella dashboard;
 * per un'app pubblica l'utente puo' inserire il PROPRIO client ID in Impostazioni > Avanzate.
 */
open class SpotifyProvider(override val slot: String = "") : OAuthProvider() {
    // A developer app in development mode gets blocked for up to 24 h after a burst of searches
    // (first big syncs, #34): one search at a time, about 40 a minute, stays under it.
    override val searchParallelism: Int get() = 1
    override val searchSpacingMs: Long get() = 1500
    override val serviceId = SERVICE
    override val loginInApp: Boolean get() = true
    override val revokeUrl = "https://www.spotify.com/account/apps/"
    override val displayName = "Spotify"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.OFFICIAL
    override val routeNoteName: String? get() = "route_note_spotify_api"
    override val familyName: String get() = "Spotify"
    override val brandColor = 0xFF1DB954
    override val noteName = "provider_note_spotify"
    override val supportsLikedSongs = true
    override val searchesByIsrc: Boolean get() = true
    override val supportsLikedTarget = true
    override val supportsAlbums = true
    override val supportsArtists = true
    override val supportsPodcasts = true
    override val supportsRecent = true

    override val setupGuide: SetupGuide? = SetupGuide(
        dashboardUrl = "https://developer.spotify.com/dashboard",
        redirectUri = Env.APP_REDIRECT_URI,
        stepUrls = listOf("https://developer.spotify.com/dashboard", "https://developer.spotify.com/dashboard/create", null, null, "https://developer.spotify.com/dashboard", "https://developer.spotify.com/dashboard"),
        whyName = "setup_why_spotify",
        stepsArrayName = "setup_steps_spotify",
        fieldLabelName = "setup_field_client_id",
    )

    override val defaultClientId: String get() = BuildDefaults["SPOTIFY_CLIENT_ID"]
    override val authorizeEndpoint = "https://accounts.spotify.com/authorize"
    override val tokenEndpoint = "https://accounts.spotify.com/api/token"
    override val scopes =
        "playlist-read-private playlist-read-collaborative playlist-modify-private playlist-modify-public " +
            "user-library-read user-library-modify user-follow-read user-follow-modify user-read-recently-played ugc-image-upload"
    // Forces the consent page, which names the signed-in account and offers "Not you?" to switch.
    override val switchAccountParams = mapOf("show_dialog" to "true")


    override suspend fun enrichAccount(ctx: Env, t: Tokens): Tokens {
        val me = api(ctx, "GET", "$API/me")
        val uid = me["id"].str ?: ""
        return t.copy(userId = uid, userName = me["display_name"].str ?: uid)
    }

    // Spotify answers some refusals in plain text ("Active premium subscription required..."): not JSON.
    override fun errorMessage(resp: HttpResponse): String =
        runCatching { parseJson(resp.body)["error"]["message"].str }.getOrNull() ?: super.errorMessage(resp).trim()

    // Da novembre 2024 le app in Development Mode non possono leggere le playlist create da Spotify
    // (Discover Weekly, Release Radar, mix editoriali): la risposta e' un 403 senza spiegazioni.
    override fun friendlyApiError(ctx: Env, resp: HttpResponse): String? {
        if (resp.code == 429) return rateLimited(ctx, resp)
        if (resp.code != 403) return null
        val msg = errorMessage(resp).ifBlank { "403" }
        return when {
            // The key's owner has no Premium: since February 2026 Spotify switches the app off for that.
            msg.contains("premium", ignoreCase = true) -> ctx.text("spotify_premium_required")
            // A token granted before a scope was added lacks it: only a new sign-in can widen it.
            msg.contains("scope", ignoreCase = true) -> ctx.text("spotify_scope_reconnect", msg)
            else -> ctx.text("spotify_forbidden", msg)
        }
    }

    /**
     * 429 after the shared client's retries: Spotify wants a pause, and says how long in Retry-After
     * (seconds, sometimes hours). The tail "(429, retry in N s)" is what the sync engine reads to wait
     * exactly that long instead of giving up.
     */
    protected fun rateLimited(ctx: Env, resp: HttpResponse): String {
        val secs = resp.header("Retry-After")?.trim()?.toLongOrNull()
        val human = when {
            secs == null -> ctx.text("in_a_few_minutes")
            secs < 90 -> "$secs s"
            secs < 5400 -> "${(secs + 59) / 60} min"
            else -> "${(secs + 3599) / 3600} h"
        }
        return ctx.text("spotify_rate_limited", human) + (secs?.let { " (429, retry in $it s)" } ?: " (429)")
    }

    override suspend fun playlists(ctx: Env): List<Playlist> {
        val me = tokens(ctx).userId
        val out = ArrayList<Playlist>()
        var url: String? = "$API/me/playlists?limit=50"
        while (url != null) {
            val j = api(ctx, "GET", url)
            for (p in j["items"].arr) {
                val pid = p["id"].str ?: continue
                out += Playlist(
                    id = pid,
                    name = p["name"].str ?: "",
                    trackCount = p["items"]["total"].int ?: p["tracks"]["total"].int ?: -1,
                    ownedByMe = (p["owner"]["id"].str == me || p["collaborative"].bool == true) && !SpotifyMade.isSpotifyMade(pid, p["owner"]["id"].str),
                    imageUrl = p["images"][0]["url"].str,
                    isPublic = p["public"].bool,
                )
            }
            url = j["next"].str
        }
        return out
    }

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist {
        val j = api(ctx, "GET", "$API/playlists/$playlistId?fields=name,description,images,items(total),tracks(total)")
        return Playlist(playlistId, j["name"].str ?: playlistId, j["items"]["total"].int ?: j["tracks"]["total"].int ?: -1, ownedByMe = false, description = j["description"].str.orEmpty(), imageUrl = j["images"][0]["url"].str)
    }

    override val canSetCover: Boolean get() = true

    /** The JPEG goes up base64-encoded as the body; Spotify wants it under 256 KB (scope ugc-image-upload). */
    override suspend fun setPlaylistCover(ctx: Env, playlistId: String, jpeg: ByteArray) {
        val t = tokens(ctx)
        val body = java.util.Base64.getEncoder().encodeToString(jpeg)
        val resp = Http.send("PUT", "$API/playlists/$playlistId/images", mapOf("Authorization" to "Bearer ${t.accessToken}"), Http.jsonBody(body, "image/jpeg"))
        if (!resp.ok) throw ProviderException(friendlyApiError(ctx, resp) ?: "$displayName API ${resp.code}: ${errorMessage(resp)}")
    }

    override suspend fun playlistVersion(ctx: Env, playlistId: String): String? = when (playlistId) {
        MusicProvider.LIKED_ID -> api(ctx, "GET", "$API/me/tracks?limit=1").let { "${it["total"].int}|${it["items"][0]["added_at"].str}" }
        MusicProvider.ALBUMS_ID -> api(ctx, "GET", "$API/me/albums?limit=1").let { "${it["total"].int}|${it["items"][0]["added_at"].str}" }
        MusicProvider.ARTISTS_ID -> api(ctx, "GET", "$API/me/following?type=artist&limit=1")["artists"].let { "${it["total"].int}|${it["items"][0]["id"].str}" }
        MusicProvider.PODCASTS_ID -> api(ctx, "GET", "$API/me/shows?limit=1").let { "${it["total"].int}|${it["items"][0]["added_at"].str}" }
        MusicProvider.RECENT_ID -> null
        else -> api(ctx, "GET", "$API/playlists/$playlistId?fields=snapshot_id")["snapshot_id"].str
    }

    /** The last fifty plays, newest first, a track once. */
    private suspend fun recentlyPlayed(ctx: Env): List<Track> {
        val j = api(ctx, "GET", "$API/me/player/recently-played?limit=50")
        return j["items"].arr.mapNotNull { item ->
            val t = item["track"]
            if (t.isNullish || t["id"].str == null) null else toTrack(t).copy(addedAt = Durations.parseInstant(item["played_at"].str))
        }.distinctBy { it.id }
    }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        if (playlistId == MusicProvider.RECENT_ID) return recentlyPlayed(ctx)
        if (playlistId == MusicProvider.ALBUMS_ID) return savedAlbums(ctx)
        if (playlistId == MusicProvider.ARTISTS_ID) return followedArtists(ctx)
        if (playlistId == MusicProvider.PODCASTS_ID) return savedShows(ctx)
        val out = ArrayList<Track>()
        var url: String? = if (playlistId == MusicProvider.LIKED_ID) {
            "$API/me/tracks?limit=50"
        } else {
            // Migrazione Spotify di febbraio/marzo 2026: /tracks e' stato tolto alle app in Development Mode
            // (risponde 403) e sostituito da /items, con "track" rinominato in "item" nella risposta.
            "$API/playlists/$playlistId/items?limit=100&fields=" +
                Http.enc("next,items(item(id,name,uri,duration_ms,is_local,external_ids(isrc),artists(name),album(name))," +
                    "track(id,name,uri,duration_ms,is_local,external_ids(isrc),artists(name),album(name)))")
        }
        while (url != null) {
            val j = try {
                api(ctx, "GET", url)
            } catch (e: ProviderException) {
                // Which of the two paths an app may read has changed twice: try the other before giving up.
                if (e.code != 403 || !url.contains("/items?")) throw e
                api(ctx, "GET", url.replace("/items?", "/tracks?"))
            }
            for (item in j["items"].arr) {
                val t = item["item"].takeUnless { it.isNullish } ?: item["track"]
                if (t.isNullish || t["is_local"].bool == true) continue
                if (t["id"].str == null) continue
                out += toTrack(t).copy(addedAt = Durations.parseInstant(item["added_at"].str))
            }
            url = j["next"].str
        }
        return out
    }

    override val detectsUnplayable: Boolean get() = true

    /**
     * Read with the user's market: Spotify then says of each entry whether it plays (`is_playable`,
     * with the restriction's reason) and, for a track it relinks, hands the playable edition over
     * with the listed one in `linked_from`. The plain [tracks] never asks for a market, because a
     * relinked listing would show other ids to a mirror sync and make it rewrite the playlist.
     */
    override suspend fun unplayable(ctx: Env, playlistId: String): List<UnplayableEntry> {
        if (MusicProvider.isLibrary(playlistId) && playlistId != MusicProvider.LIKED_ID) return emptyList()
        val fieldsOf = "id,name,uri,duration_ms,is_local,is_playable,restrictions(reason),linked_from(id,uri),external_ids(isrc),artists(name),album(name,release_date)"
        val out = ArrayList<UnplayableEntry>()
        var index = 0
        var url: String? = if (playlistId == MusicProvider.LIKED_ID) "$API/me/tracks?limit=50&market=from_token"
        else "$API/playlists/$playlistId/items?limit=100&market=from_token&fields=" + Http.enc("next,items(item($fieldsOf),track($fieldsOf))")
        while (url != null) {
            val j = try {
                api(ctx, "GET", url)
            } catch (e: ProviderException) {
                if (e.code != 403 || !url.contains("/items?")) throw e
                api(ctx, "GET", url.replace("/items?", "/tracks?"))
            }
            for (item in j["items"].arr) {
                val t = item["item"].takeUnless { it.isNullish } ?: item["track"]
                // The same entries [tracks] skips, so the positions agree with its listing.
                if (t.isNullish || t["is_local"].bool == true || t["id"].str == null) continue
                val linked = t["linked_from"]
                if (t["is_playable"].bool == false) {
                    out += UnplayableEntry(toTrack(t).copy(available = false), index, reason = t["restrictions"]["reason"].str.orEmpty())
                } else if (linked["id"].str != null) {
                    // The playlist holds linked_from; what came back is the edition Spotify plays instead.
                    val deadId = linked["id"].str!!
                    val dead = toTrack(t).copy(id = deadId, uri = linked["uri"].str ?: "spotify:track:$deadId", available = false)
                    out += UnplayableEntry(dead, index, reason = Unplayable.REASON_MARKET, relinked = toTrack(t).copy(available = true))
                }
                index++
            }
            url = j["next"].str
        }
        return out
    }

    private fun toTrack(t: JsonElement?): Track {
        val tid = t["id"].str ?: ""
        return Track(
            id = tid,
            title = t["name"].str ?: "",
            artists = t["artists"].arr.mapNotNull { it["name"].str },
            album = t["album"]["name"].str ?: "",
            durationMs = t["duration_ms"].long ?: 0,
            isrc = t["external_ids"]["isrc"].str,
            uri = t["uri"].str ?: "spotify:track:$tid",
            explicit = t["explicit"].bool,
            year = Durations.year(t["album"]["release_date"].str),
        )
    }

    override suspend fun track(ctx: Env, trackId: String): Track? =
        api(ctx, "GET", "$API/tracks/${Http.enc(trackId)}").takeIf { it["id"].str != null }?.let { toTrack(it) }

    // ---- library: saved albums and followed artists, as Track items of their kind

    private suspend fun savedAlbums(ctx: Env): List<Track> {
        val out = ArrayList<Track>()
        var url: String? = "$API/me/albums?limit=50"
        while (url != null) {
            val j = api(ctx, "GET", url)
            j["items"].arr.forEach { item -> albumItem(item["album"])?.let { out += it } }
            url = j["next"].str
        }
        return out
    }

    private suspend fun followedArtists(ctx: Env): List<Track> {
        val out = ArrayList<Track>()
        var url: String? = "$API/me/following?type=artist&limit=50"
        while (url != null) {
            val j = api(ctx, "GET", url)["artists"]
            j["items"].arr.forEach { a -> artistItem(a)?.let { out += it } }
            url = j["next"].str
        }
        return out
    }

    private suspend fun savedShows(ctx: Env): List<Track> {
        val out = ArrayList<Track>()
        var url: String? = "$API/me/shows?limit=50"
        while (url != null) {
            val j = api(ctx, "GET", url)
            j["items"].arr.forEach { item -> showItem(item["show"])?.let { out += it } }
            url = j["next"].str
        }
        return out
    }

    private fun showItem(s: JsonElement?): Track? {
        val id = s["id"].str ?: return null
        return Track(id = id, title = s["name"].str ?: "", artists = listOfNotNull(s["publisher"].str), uri = s["uri"].str ?: "spotify:show:$id", kind = Track.KIND_PODCAST)
    }

    private suspend fun searchAlbums(ctx: Env, q: String): List<Track> {
        val j = searchPace.paced { api(ctx, "GET", "$API/search?type=album&limit=5&q=${Http.enc(q)}") }
        return j["albums"]["items"].arr.mapNotNull { albumItem(it) }
    }

    private fun albumItem(a: JsonElement?): Track? {
        val id = a["id"].str ?: return null
        return Track(
            id = id, title = a["name"].str ?: "", artists = a["artists"].arr.mapNotNull { it["name"].str },
            isrc = a["external_ids"]["upc"].str, uri = a["uri"].str ?: "spotify:album:$id", kind = Track.KIND_ALBUM,
        )
    }

    private fun artistItem(a: JsonElement?): Track? {
        val id = a["id"].str ?: return null
        val name = a["name"].str ?: return null
        return Track(id = id, title = name, artists = listOf(name), uri = a["uri"].str ?: "spotify:artist:$id", kind = Track.KIND_ARTIST)
    }

    private suspend fun searchQuery(ctx: Env, q: String): List<Track> {
        val j = searchPace.paced { api(ctx, "GET", "$API/search?type=track&limit=5&q=${Http.enc(q)}") }
        return j["tracks"]["items"].arr.filter { it["id"].str != null }.map { toTrack(it) }
    }

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        when (track.kind) {
            Track.KIND_ALBUM -> {
                track.isrcNorm?.let { upc ->
                    val r = searchAlbums(ctx, "upc:$upc")
                    if (r.isNotEmpty()) return r
                }
                val artist = track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }
                val r = searchAlbums(ctx, "album:${Matcher.searchTitle(track.title)}" + (artist?.let { " artist:$it" } ?: ""))
                return r.ifEmpty { searchAlbums(ctx, listOfNotNull(artist, Matcher.searchTitle(track.title)).joinToString(" ")) }
            }
            Track.KIND_ARTIST -> {
                val j = searchPace.paced { api(ctx, "GET", "$API/search?type=artist&limit=5&q=${Http.enc(track.title)}") }
                return j["artists"]["items"].arr.mapNotNull { artistItem(it) }
            }
            Track.KIND_PODCAST -> {
                val j = searchPace.paced { api(ctx, "GET", "$API/search?type=show&limit=5&q=${Http.enc(track.title)}") }
                return j["shows"]["items"].arr.mapNotNull { showItem(it) }
            }
        }
        track.isrcNorm?.let { isrc ->
            val r = searchQuery(ctx, "isrc:$isrc")
            if (r.isNotEmpty()) return r
        }
        val title = Matcher.searchTitle(track.title)
        val artist = track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }
        val fielded = buildString {
            append("track:").append(title)
            if (!artist.isNullOrBlank()) append(" artist:").append(artist)
        }
        val r = searchQuery(ctx, fielded)
        if (r.isNotEmpty()) return r
        return searchQuery(ctx, listOfNotNull(title, artist).joinToString(" "))
    }

    override fun webSearchUrl(ctx: Env, query: String): String = "https://open.spotify.com/search/" + Http.enc(query)

    override fun rehydrate(track: Track): Track = track.copy(uri = "spotify:track:${track.id}")

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist {
        val body = jsonObj("name" to name, "public" to false, "description" to description)
        // Creation moved to /me/playlists; the older /users/{id}/playlists answers Development Mode
        // apps with a 403. The old path stays as the fallback should the new one be missing.
        val j = try {
            api(ctx, "POST", "$API/me/playlists", body)
        } catch (e: ProviderException) {
            if (e.code != 404 && e.code != 405) throw e
            api(ctx, "POST", "$API/users/${Http.enc(tokens(ctx).userId)}/playlists", body)
        }
        return Playlist(j["id"].str ?: throw ProviderException("Spotify: playlist not created"), name, 0)
    }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) {
        api(ctx, "PUT", "$API/playlists/$playlistId", jsonObj("name" to name))
    }

    /**
     * Every entry of the playlist in its position: the id of the ones [tracks] lists, null for the
     * ones it skips (local files, entries the catalogue lost). Spotify's positions count them all.
     */
    private suspend fun positions(ctx: Env, playlistId: String): MutableList<String?> {
        val out = ArrayList<String?>()
        var url: String? = "$API/playlists/$playlistId/items?limit=100&fields=" + Http.enc("next,items(item(id,is_local),track(id,is_local))")
        while (url != null) {
            val j = try {
                api(ctx, "GET", url)
            } catch (e: ProviderException) {
                if (e.code != 403 || !url.contains("/items?")) throw e
                api(ctx, "GET", url.replace("/items?", "/tracks?"))
            }
            for (item in j["items"].arr) {
                val t = item["item"].takeUnless { it.isNullish } ?: item["track"]
                out += if (t.isNullish || t["is_local"].bool == true) null else t["id"].str
            }
            url = j["next"].str
        }
        return out
    }

    override val canReorder: Boolean get() = canWrite
    override suspend fun reorderTracks(ctx: Env, playlistId: String, current: List<Track>, ordered: List<Track>) {
        val moves = com.xlollx.songport.sync.Reorder.moves(current.map { it.id }, ordered.map { it.id })
        if (moves.isEmpty()) return
        // [current] leaves out what [tracks] skips, so its indices are not the playlist's positions
        // once a local file sits before them: the moves are worked out on the whole playlist.
        val all = positions(ctx, playlistId)
        if (all.filterNotNull() != current.map { it.id }) throw ProviderException("$displayName: the playlist changed while it was being reordered")
        var path = "items"
        for ((listedFrom, listedTo) in moves) {
            val from = all.indices.filter { all[it] != null }[listedFrom]
            val moved = all.removeAt(from)
            val listed = all.indices.filter { all[it] != null }
            val to = if (listedTo < listed.size) listed[listedTo] else (listed.lastOrNull()?.plus(1) ?: 0)
            all.add(to, moved)
            val body = jsonObj("range_start" to from, "insert_before" to com.xlollx.songport.sync.Reorder.insertBefore(from, to), "range_length" to 1)
            try {
                api(ctx, "PUT", "$API/playlists/$playlistId/$path", body)
            } catch (e: ProviderException) {
                if (e.code != 403 || path != "items") throw e
                path = "tracks"
                api(ctx, "PUT", "$API/playlists/$playlistId/$path", body)
            }
        }
    }

    override val canSetVisibility: Boolean get() = canWrite
    override suspend fun setPlaylistVisibility(ctx: Env, playlistId: String, public: Boolean) {
        api(ctx, "PUT", "$API/playlists/$playlistId", jsonObj("public" to public))
    }

    /** Spotify has no delete: a playlist you stop following disappears from your library. */
    override suspend fun deletePlaylist(ctx: Env, playlistId: String) {
        library(ctx, "DELETE", "playlist", listOf(playlistId)) { api(ctx, "DELETE", "$API/playlists/$playlistId/followers") }
    }

    /**
     * Saves to or removes from the library through /me/library, which takes URIs, 40 at a time: since
     * February 2026 the per-type paths (/me/tracks, /me/albums, /me/following, /me/shows, the
     * playlist followers) answer Development Mode apps with a 403 (issue #34). An app still served by
     * them (Extended Quota) gets a 404 or 405 from the new path for a type it does not take: the old
     * path, [legacy], is called then, once for all the ids.
     */
    @Volatile private var lastLibrarySave = 0L
    override fun addSpacingMs(playlistId: String): Long = if (playlistId == MusicProvider.LIKED_ID) librarySpacingMs else 0
    /** The gap between two saved tracks: a little over a second, so each gets its own second. */
    @Volatile var librarySpacingMs = 1_050L

    private suspend fun library(ctx: Env, method: String, type: String, ids: List<String>, legacy: suspend () -> Unit) {
        try {
            // Saved tracks are listed by the second they were saved, and Spotify orders the ones saved
            // in the same second as it likes (issue #34: liked songs came out shuffled). Tracks are
            // saved one per request, a second apart, in the order given (the engine sends the oldest
            // first): slower, but the order comes out as in the source.
            // Only when the job keeps the order: otherwise 40 a request, at full speed.
            val perRequest = if (method == "PUT" && type == "track" && MusicProvider.addsInOrder()) 1 else 40
            ids.chunked(perRequest).forEach { chunk ->
                if (perRequest == 1) {
                    val wait = lastLibrarySave + librarySpacingMs - System.currentTimeMillis()
                    if (wait > 0) kotlinx.coroutines.delay(wait)
                }
                api(ctx, method, "$API/me/library?uris=" + chunk.joinToString(",") { java.net.URLEncoder.encode("spotify:$type:$it", "UTF-8") })
                lastLibrarySave = System.currentTimeMillis()
            }
        } catch (e: ProviderException) {
            if (e.code != 404 && e.code != 405 && !(e.code == 400 && type == "artist")) throw e
            ctx.log("spotify", "/me/library refused $type (${e.code}): the older path")
            legacy()
        }
    }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        val ids = tracks.map { it.id }
        when (playlistId) {
            MusicProvider.LIKED_ID -> return library(ctx, "PUT", "track", ids) { ids.chunked(50).forEach { api(ctx, "PUT", "$API/me/tracks", jsonObj("ids" to it)) } }
            MusicProvider.ALBUMS_ID -> return library(ctx, "PUT", "album", ids) { ids.chunked(20).forEach { api(ctx, "PUT", "$API/me/albums", jsonObj("ids" to it)) } }
            MusicProvider.ARTISTS_ID -> return library(ctx, "PUT", "artist", ids) { ids.chunked(50).forEach { api(ctx, "PUT", "$API/me/following?type=artist", jsonObj("ids" to it)) } }
            MusicProvider.PODCASTS_ID -> return library(ctx, "PUT", "show", ids) { ids.chunked(50).forEach { api(ctx, "PUT", "$API/me/shows?ids=${it.joinToString(",")}") } }
        }
        tracks.mapNotNull { it.uri }.chunked(100).forEach { chunk ->
            api(ctx, "POST", "$API/playlists/$playlistId/items", jsonObj("uris" to chunk))
        }
    }

    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        val ids = tracks.map { it.id }
        when (playlistId) {
            MusicProvider.LIKED_ID -> return library(ctx, "DELETE", "track", ids) { ids.chunked(50).forEach { api(ctx, "DELETE", "$API/me/tracks", jsonObj("ids" to it)) } }
            MusicProvider.ALBUMS_ID -> return library(ctx, "DELETE", "album", ids) { ids.chunked(20).forEach { api(ctx, "DELETE", "$API/me/albums", jsonObj("ids" to it)) } }
            MusicProvider.ARTISTS_ID -> return library(ctx, "DELETE", "artist", ids) { ids.chunked(50).forEach { api(ctx, "DELETE", "$API/me/following?type=artist", jsonObj("ids" to it)) } }
            MusicProvider.PODCASTS_ID -> return library(ctx, "DELETE", "show", ids) { ids.chunked(50).forEach { api(ctx, "DELETE", "$API/me/shows?ids=${it.joinToString(",")}") } }
        }
        tracks.mapNotNull { it.uri }.chunked(100).forEach { chunk ->
            api(ctx, "DELETE", "$API/playlists/$playlistId/items",
                jsonObj("items" to chunk.map { mapOf("uri" to it) }))
        }
    }

    companion object {
        /**
         * Searches spaced out, about three a second across every sync of the process: Spotify blocks
         * an app for half an hour after a burst of them (issue #30), and a steady pace never gets
         * there, so a first sync of a thousand songs ends sooner than with the blocks.
         */
        val searchPace = com.xlollx.songport.net.Pacer(350)
        const val SERVICE = "spotify"
        private const val API = "https://api.spotify.com/v1"
    }
}
