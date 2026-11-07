package com.xlollx.songport.providers

import com.xlollx.songport.platform.Env
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.jsonObj
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Matcher
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Jellyfin (https://api.jellyfin.org): server personale, nessuna registrazione, nessuna quota. */
open class JellyfinProvider(override val slot: String = "") : CredentialsProvider() {
    override val serviceId = SERVICE
    override val displayName = "Jellyfin"
    override val routeNoteName: String? get() = "route_note_server"
    override val brandColor = 0xFF00A4DC
    override val noteName = "provider_note_jellyfin"
    // Out of beta (01/11/2026): used against real servers and accounts, and passing LiveServersIT.
    override val beta = false
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    override val supportsAlbums = true
    override val supportsArtists = true
    // A Jellyfin user may have no password (the server allows it, as its public demo does): may stay empty.
    override val loginForm = LoginForm(needsUrl = true, needsUser = true, needsSecret = true, hintName = "login_hint_jellyfin", secretOptional = true)

    // Client and DeviceId stay "Songport" after the rename to Trackhop: they are identifiers, not a
    // name the user reads, and Jellyfin files existing sign-ins under them in its Devices list.
    private fun authHeader(token: String?) =
        "MediaBrowser Client=\"Songport\", Device=\"Android\", DeviceId=\"songport-android\", Version=\"1.0\"" +
            (token?.let { ", Token=\"$it\"" } ?: "")

    /** The headers of every call: Jellyfin's "Authorization: MediaBrowser …" (Emby overrides it). */
    protected open fun headers(token: String?): Map<String, String> =
        mapOf("Authorization" to authHeader(token), "Accept" to "application/json")

    /** Where the API lives under the server's address (Emby: under /emby). */
    protected open fun apiRoot(base: String): String = base

    override suspend fun login(ctx: Env, url: String, user: String, secret: String) {
        val base = normalizeUrl(url)
        val resp = Http.send(
            "POST", "${apiRoot(base)}/Users/AuthenticateByName",
            headers(null),
            Http.jsonBody(jsonObj("Username" to user.trim(), "Pw" to secret).toString()),
        )
        if (!resp.ok) throw ProviderException("$displayName: login failed (HTTP ${resp.code})")
        val j = parseJson(resp.body)
        val token = j["AccessToken"].str ?: throw ProviderException("$displayName: no token")
        save(ctx, Tokens(accessToken = token, userId = j["User"]["Id"].str ?: "", userName = user.trim(), extra = mapOf("url" to base)))
    }

    /**
     * Quick Connect (#45): sign in with a short code approved from a device that is already signed in,
     * with no password. For servers behind single sign-on, where the password is not a Jellyfin one.
     * Emby has no Quick Connect.
     */
    open val supportsQuickConnect: Boolean get() = true

    class QuickConnect(val base: String, val secret: String, val code: String)

    /** Asks the server for a code to approve. Throws with a clear message when Quick Connect is off. */
    suspend fun quickConnectStart(ctx: Env, url: String): QuickConnect {
        val base = normalizeUrl(url)
        val root = apiRoot(base)
        val enabled = Http.send("GET", "$root/QuickConnect/Enabled", headers(null))
        if (enabled.ok && enabled.body.trim().equals("false", ignoreCase = true)) throw ProviderException(ctx.text("jellyfin_qc_off"))
        // POST since Jellyfin 10.9; 10.8 answered GET.
        var resp = Http.send("POST", "$root/QuickConnect/Initiate", headers(null))
        if (resp.code == 404 || resp.code == 405) resp = Http.send("GET", "$root/QuickConnect/Initiate", headers(null))
        if (resp.code == 401 || resp.code == 403 || resp.code == 404) throw ProviderException(ctx.text("jellyfin_qc_off"))
        if (!resp.ok) throw ProviderException("$displayName: Quick Connect failed (HTTP ${resp.code})")
        val j = parseJson(resp.body)
        val secret = j["Secret"].str ?: throw ProviderException("$displayName: Quick Connect failed (no secret)")
        val code = j["Code"].str ?: throw ProviderException("$displayName: Quick Connect failed (no code)")
        return QuickConnect(base, secret, code)
    }

    /** True once the code is approved and the sign-in saved; false while the server still waits. */
    suspend fun quickConnectPoll(ctx: Env, qc: QuickConnect): Boolean {
        val root = apiRoot(qc.base)
        val r = Http.send("GET", "$root/QuickConnect/Connect?Secret=${Http.enc(qc.secret)}", headers(null))
        if (r.code == 404) throw ProviderException(ctx.text("jellyfin_qc_expired"))
        if (!r.ok) throw ProviderException("$displayName: Quick Connect failed (HTTP ${r.code})")
        if (parseJson(r.body)["Authenticated"].bool != true) return false
        val a = Http.send(
            "POST", "$root/Users/AuthenticateWithQuickConnect", headers(null),
            Http.jsonBody(jsonObj("Secret" to qc.secret).toString()),
        )
        if (!a.ok) throw ProviderException("$displayName: login failed (HTTP ${a.code})")
        val j = parseJson(a.body)
        val token = j["AccessToken"].str ?: throw ProviderException("$displayName: no token")
        save(ctx, Tokens(accessToken = token, userId = j["User"]["Id"].str ?: "", userName = j["User"]["Name"].str ?: "", extra = mapOf("url" to qc.base)))
        return true
    }

    protected suspend fun api(ctx: Env, method: String, path: String, body: JsonElement? = null): JsonElement {
        val t = creds(ctx)
        val resp = Http.send(
            method, "${apiRoot(t.extra["url"] ?: "")}$path",
            headers(t.accessToken),
            body?.let { Http.jsonBody(it.toString()) },
        )
        if (!resp.ok) throw ProviderException("$displayName API ${resp.code}: ${resp.body.take(200)}")
        return parseJson(resp.body)
    }

    protected fun uid(ctx: Env) = creds(ctx).userId

    /** The search page of the server's own web client, where the user finds the track and copies its link. */
    override fun webSearchUrl(ctx: Env, query: String): String? =
        ctx.tokens.get(id)?.extra?.get("url")?.let { searchUrl(it, query) }

    private fun toTrack(i: JsonElement?): Track? {
        val id = i["Id"].str ?: return null
        return Track(
            id = id,
            title = i["Name"].str ?: "",
            artists = i["Artists"].arr.mapNotNull { it.str }.ifEmpty { listOfNotNull(i["AlbumArtist"].str) },
            album = i["Album"].str ?: "",
            durationMs = (i["RunTimeTicks"].long ?: 0) / 10_000,
            itemId = i["PlaylistItemId"].str,
            year = i["ProductionYear"].int ?: 0,
            addedAt = com.xlollx.songport.sync.Durations.parseInstant(i["DateCreated"].str),
        )
    }

    override suspend fun playlists(ctx: Env): List<Playlist> =
        api(ctx, "GET", "/Users/${uid(ctx)}/Items?IncludeItemTypes=Playlist&Recursive=true&Fields=ChildCount")["Items"].arr
            .mapNotNull { p -> Playlist(p["Id"].str ?: return@mapNotNull null, p["Name"].str ?: "", p["ChildCount"].int ?: -1) }

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist {
        val p = api(ctx, "GET", "/Users/${uid(ctx)}/Items/$playlistId")
        return Playlist(playlistId, p["Name"].str ?: playlistId, p["ChildCount"].int ?: -1)
    }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        val path = when (playlistId) {
            MusicProvider.LIKED_ID -> "/Users/${uid(ctx)}/Items?Filters=IsFavorite&IncludeItemTypes=Audio&Recursive=true&Limit=5000"
            MusicProvider.ALBUMS_ID -> "/Users/${uid(ctx)}/Items?Filters=IsFavorite&IncludeItemTypes=MusicAlbum&Recursive=true&Limit=5000"
            MusicProvider.ARTISTS_ID -> "/Users/${uid(ctx)}/Items?Filters=IsFavorite&IncludeItemTypes=MusicArtist&Recursive=true&Limit=5000"
            else -> "/Playlists/$playlistId/Items?UserId=${uid(ctx)}"
        }
        val items = api(ctx, "GET", path)["Items"].arr
        return when (playlistId) {
            MusicProvider.ALBUMS_ID -> items.mapNotNull { toAlbum(it) }
            MusicProvider.ARTISTS_ID -> items.mapNotNull { toArtist(it) }
            else -> items.mapNotNull { toTrack(it) }
        }
    }

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        when (track.kind) {
            // Jellyfin's search term matches names: the album title alone finds the album, the artist filters after.
            Track.KIND_ALBUM -> return api(ctx, "GET", "/Items?UserId=${uid(ctx)}&searchTerm=${Http.enc(Matcher.searchTitle(track.title))}&IncludeItemTypes=MusicAlbum&Recursive=true&Limit=8")["Items"].arr
                .mapNotNull { toAlbum(it) }
            Track.KIND_ARTIST -> return api(ctx, "GET", "/Items?UserId=${uid(ctx)}&searchTerm=${Http.enc(track.title)}&IncludeItemTypes=MusicArtist&Recursive=true&Limit=5")["Items"].arr
                .mapNotNull { toArtist(it) }
        }
        // The search term matches names only ("Metallica Battery" finds nothing): the title, then the artist's songs first.
        suspend fun find(term: String) = api(ctx, "GET", "/Items?UserId=${uid(ctx)}&searchTerm=${Http.enc(term)}&IncludeItemTypes=Audio&Recursive=true&Limit=25")["Items"].arr
            .mapNotNull { toTrack(it) }
        // Jellyfin 10.8 does not fold accents: "energie" misses "Énergie". The title as written, then.
        val folded = Matcher.searchTitle(track.title)
        val plain = Matcher.plainTitle(track.title)
        val found = find(folded).ifEmpty { if (plain.equals(folded, ignoreCase = true)) emptyList() else find(plain) }
        return Matcher.byArtist(track, found).take(5)
    }

    private fun toAlbum(i: JsonElement?): Track? {
        val id = i["Id"].str ?: return null
        val artists = i["AlbumArtists"].arr.mapNotNull { it["Name"].str }.ifEmpty { listOfNotNull(i["AlbumArtist"].str) }
        return Track(id = id, title = i["Name"].str ?: "", artists = artists, kind = Track.KIND_ALBUM)
    }

    private fun toArtist(i: JsonElement?): Track? {
        val id = i["Id"].str ?: return null
        val name = i["Name"].str ?: return null
        return Track(id = id, title = name, artists = listOf(name), kind = Track.KIND_ARTIST)
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist {
        val j = api(ctx, "POST", "/Playlists", jsonObj("Name" to name, "Ids" to emptyList<String>(), "UserId" to uid(ctx), "MediaType" to "Audio"))
        return Playlist(j["Id"].str ?: throw ProviderException("$displayName: playlist not created"), name, 0)
    }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) {
        try {
            api(ctx, "POST", "/Playlists/$playlistId", jsonObj("Name" to name))
        } catch (e: ProviderException) {
            // Before 10.9 there is no playlist update: the item itself is written back, renamed, whole.
            if (e.message?.contains(" 404") != true && e.message?.contains(" 405") != true) throw e
            val item = api(ctx, "GET", "/Users/${uid(ctx)}/Items/$playlistId").jsonObject
            api(ctx, "POST", "/Items/$playlistId", JsonObject(item + ("Name" to JsonPrimitive(name))))
        }
    }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) {
        api(ctx, "DELETE", "/Items/$playlistId")
    }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (MusicProvider.isLibrary(playlistId)) {
            // Favourites work the same for songs, albums and artists.
            tracks.forEach { api(ctx, "POST", "/Users/${uid(ctx)}/FavoriteItems/${it.id}") }
            return
        }
        tracks.chunked(50).forEach { chunk ->
            api(ctx, "POST", "/Playlists/$playlistId/Items?Ids=${chunk.joinToString(",") { it.id }}&UserId=${uid(ctx)}")
        }
    }

    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (MusicProvider.isLibrary(playlistId)) {
            tracks.forEach { api(ctx, "DELETE", "/Users/${uid(ctx)}/FavoriteItems/${it.id}") }
            return
        }
        val gone = tracks.mapNotNull { it.itemId }
        // Jellyfin 10.9+ gives every copy of a song the same entry id, and deleting it drops them all:
        // when a copy has to stay, the list is written back whole.
        keptAfterRemoving(tracks(ctx, playlistId), gone)?.let { kept ->
            api(ctx, "POST", "/Playlists/$playlistId", jsonObj("Ids" to kept.map { it.id }))
            return
        }
        gone.chunked(50).forEach { chunk ->
            api(ctx, "DELETE", "/Playlists/$playlistId/Items?EntryIds=${chunk.joinToString(",")}")
        }
    }

    companion object {
        const val SERVICE = "jellyfin"

        /**
         * jellyfin-web's search page with [query] filled in. The web client lives under /web with hash
         * routing; since 10.9 its routes drop ".html" (#/search; "search.html" only redirects, deprecated),
         * and 10.8 is past its end of life.
         */
        internal fun searchUrl(base: String, query: String): String = "$base/web/#/search?query=" + Http.enc(query)

        /**
         * The entries left once [gone] (entry ids, one per copy to remove) are removed, or null when
         * deleting those entry ids removes exactly them. With shared ids the first copies stay.
         */
        internal fun keptAfterRemoving(entries: List<Track>, gone: List<String>): List<Track>? {
            val toDrop = gone.groupingBy { it }.eachCount().toMutableMap()
            if (toDrop.all { (id, n) -> entries.count { it.itemId == id } <= n }) return null
            return entries.asReversed().filter { e ->
                val left = e.itemId?.let { toDrop[it] } ?: 0
                if (left > 0) toDrop[e.itemId!!] = left - 1
                left == 0
            }.asReversed()
        }
    }
}
