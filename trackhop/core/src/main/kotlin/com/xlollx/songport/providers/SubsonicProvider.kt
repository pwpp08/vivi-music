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
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Matcher
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * API Subsonic (http://www.subsonic.org/pages/api.jsp): copre Navidrome, Airsonic, Gonic, LMS,
 * Funkwhale e gli altri server compatibili. Nessuna registrazione, nessuna quota: solo il tuo server.
 * Autenticazione con token = md5(password + salt) per richiesta, mai la password in chiaro.
 */
class SubsonicProvider(override val slot: String = "") : CredentialsProvider() {
    override val serviceId = SERVICE
    override val displayName = "Subsonic / Navidrome"
    override val routeNoteName: String? get() = "route_note_server"
    override val brandColor = 0xFF2E7D32
    override val noteName = "provider_note_subsonic"
    // Out of beta (01/11/2026): used against real servers and accounts, and passing LiveServersIT.
    override val beta = false
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    override val supportsAlbums = true
    override val supportsArtists = true
    override val loginForm = LoginForm(needsUrl = true, needsUser = true, needsSecret = true, hintName = "login_hint_subsonic")

    override suspend fun login(ctx: Env, url: String, user: String, secret: String) {
        val base = normalizeUrl(url)
        var t = Tokens(accessToken = secret, userName = user.trim(), extra = mapOf("url" to base))
        // The version this server speaks, when it refuses ours: every later call is made with it.
        // Its kind too ("navidrome", ...), which says whether it has a web search to open.
        call(ctx, "ping", emptyList(), t, onType = { ty -> t = t.copy(extra = t.extra + (TYPE to ty)) }) { v -> t = t.copy(extra = t.extra + ("v" to v)) }
        save(ctx, t)
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Chiamata REST Subsonic; i parametri ripetibili (songIdToAdd…) passano come lista di coppie. */
    private suspend fun call(
        ctx: Env, method: String, params: List<Pair<String, String>>, t: Tokens = creds(ctx),
        // Connections made before 1.3.2 never stored the server's kind: the first answer records it.
        onType: (String) -> Unit = { ty -> ctx.tokens.get(id)?.let { cur -> save(ctx, cur.copy(extra = cur.extra + (TYPE to ty))) } },
        onVersion: (String) -> Unit = { v -> save(ctx, t.copy(extra = t.extra + ("v" to v))) },
    ): JsonElement {
        var version = t.extra["v"] ?: API_VERSION
        while (true) {
            val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            // "c" stays "Songport" after the rename to Trackhop: Navidrome and others register a player
            // per client name, with the admin's settings (transcoding, ...) attached to it.
            val common = listOf(
                "u" to t.userName, "t" to md5(t.accessToken + salt), "s" to salt,
                "v" to version, "c" to "Songport", "f" to "json",
            )
            val query = (common + params).joinToString("&") { (k, v) -> "${Http.enc(k)}=${Http.enc(v)}" }
            val resp = Http.send("GET", "${t.extra["url"]}/rest/$method?$query", mapOf("Accept" to "application/json"))
            if (!resp.ok) throw ProviderException("$displayName: HTTP ${resp.code}")
            val r = parseJson(resp.body)["subsonic-response"] ?: throw ProviderException("$displayName: unexpected response")
            if (r["status"].str == "ok") {
                // OpenSubsonic servers name themselves in every answer; plain Subsonic ones do not.
                r["type"].str?.lowercase()?.takeIf { it.isNotBlank() && it != t.extra[TYPE] }?.let(onType)
                return r
            }
            // Error 30, "server must upgrade": an older server (Airsonic-Advanced speaks 1.15.0) refuses a
            // newer client version. Its own version is in the answer; once per call, then remembered.
            val older = olderVersion(r, version)
            if (older != null) { version = older; onVersion(older); continue }
            if (isAmpacheWrongPassword(r)) throw ProviderException(ctx.text("error_ampache_subsonic_password"))
            throw ProviderException("$displayName: ${r["error"]["message"].str ?: "error ${r["error"]["code"].int}"}")
        }
    }

    internal fun toTrack(s: JsonElement?, index: Int? = null): Track? {
        val id = s["id"].str ?: return null
        return Track(
            id = id,
            title = s["title"].str ?: "",
            artists = listOfNotNull(s["artist"].str),
            album = s["album"].str ?: "",
            durationMs = (s["duration"].long ?: 0) * 1000,
            itemId = index?.toString(),
            year = s["year"].int ?: 0,
            // OpenSubsonic servers (Navidrome) list the file's ISRCs; older ones a single string or nothing.
            isrc = s["isrc"].arr.firstNotNullOfOrNull { it.str } ?: s["isrc"].str,
            addedAt = com.xlollx.songport.sync.Durations.parseInstant(s["created"].str),
        )
    }

    /** Navidrome's own web player searches songs; other Subsonic servers have no search page to link. */
    override fun webSearchUrl(ctx: Env, query: String): String? {
        val t = ctx.tokens.get(id) ?: return null
        val base = t.extra["url"] ?: return null
        return if (t.extra[TYPE] == NAVIDROME) navidromeSearchUrl(base, query) else null
    }

    override suspend fun playlists(ctx: Env): List<Playlist> {
        val me = creds(ctx).userName
        return call(ctx, "getPlaylists", emptyList())["playlists"]["playlist"].arr.mapNotNull { p ->
            Playlist(p["id"].str ?: return@mapNotNull null, p["name"].str ?: "", p["songCount"].int ?: -1, ownedByMe = (p["owner"].str ?: me) == me, isPublic = p["public"].bool)
        }
    }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> =
        if (playlistId == MusicProvider.LIKED_ID) {
            call(ctx, "getStarred2", emptyList())["starred2"]["song"].arr.mapNotNull { toTrack(it) }
        } else if (playlistId == MusicProvider.ALBUMS_ID) {
            call(ctx, "getStarred2", emptyList())["starred2"]["album"].arr.mapNotNull { toAlbum(it) }
        } else if (playlistId == MusicProvider.ARTISTS_ID) {
            call(ctx, "getStarred2", emptyList())["starred2"]["artist"].arr.mapNotNull { toArtist(it) }
        } else {
            call(ctx, "getPlaylist", listOf("id" to playlistId))["playlist"]["entry"].arr
                .mapIndexedNotNull { i, s -> toTrack(s, i) }
        }

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        when (track.kind) {
            Track.KIND_ALBUM -> {
                val q = (listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }) + Matcher.searchTitle(track.title)).joinToString(" ")
                val r = call(ctx, "search3", listOf("query" to q, "songCount" to "0", "albumCount" to "5", "artistCount" to "0"))["searchResult3"]["album"].arr.mapNotNull { toAlbum(it) }
                // Servers index album titles alone too: the artist in the query can hide a match.
                return r.ifEmpty { call(ctx, "search3", listOf("query" to Matcher.searchTitle(track.title), "songCount" to "0", "albumCount" to "5", "artistCount" to "0"))["searchResult3"]["album"].arr.mapNotNull { toAlbum(it) } }
            }
            Track.KIND_ARTIST -> return call(ctx, "search3", listOf("query" to track.title, "songCount" to "0", "albumCount" to "0", "artistCount" to "5"))["searchResult3"]["artist"].arr.mapNotNull { toArtist(it) }
        }
        val title = Matcher.searchTitle(track.title)
        val q = (listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }) + title).joinToString(" ")
        suspend fun songs(query: String, count: Int) = call(ctx, "search3", listOf("query" to query, "songCount" to "$count", "albumCount" to "0", "artistCount" to "0"))["searchResult3"]["song"].arr
            .mapNotNull { toTrack(it) }
        // Gonic and Ampache match the query against the title alone ("metallica battery" finds nothing):
        // then the title, with the artist's songs first.
        return songs(q, 5).ifEmpty { if (q == title) emptyList() else Matcher.byArtist(track, songs(title, 20)).take(5) }
    }

    private fun toAlbum(a: JsonElement?): Track? {
        val id = a["id"].str ?: return null
        return Track(id = id, title = a["name"].str ?: a["album"].str ?: "", artists = listOfNotNull(a["artist"].str), kind = Track.KIND_ALBUM)
    }

    private fun toArtist(a: JsonElement?): Track? {
        val id = a["id"].str ?: return null
        val name = a["name"].str ?: return null
        return Track(id = id, title = name, artists = listOf(name), kind = Track.KIND_ARTIST)
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist {
        val r = call(ctx, "createPlaylist", listOf("name" to name))
        val id = r["playlist"]["id"].str
            ?: call(ctx, "getPlaylists", emptyList())["playlists"]["playlist"].arr.lastOrNull { it["name"].str == name }?.get("id").str
            ?: throw ProviderException("$displayName: playlist not created")
        return Playlist(id, name, 0)
    }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (playlistId == MusicProvider.LIKED_ID) {
            tracks.chunked(50).forEach { chunk -> call(ctx, "star", chunk.map { "id" to it.id }) }
            return
        }
        if (playlistId == MusicProvider.ALBUMS_ID) {
            tracks.chunked(50).forEach { chunk -> call(ctx, "star", chunk.map { "albumId" to it.id }) }
            return
        }
        if (playlistId == MusicProvider.ARTISTS_ID) {
            tracks.chunked(50).forEach { chunk -> call(ctx, "star", chunk.map { "artistId" to it.id }) }
            return
        }
        tracks.chunked(100).forEach { chunk ->
            call(ctx, "updatePlaylist", listOf("playlistId" to playlistId) + chunk.map { "songIdToAdd" to it.id })
        }
    }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) {
        call(ctx, "updatePlaylist", listOf("playlistId" to playlistId, "name" to name))
    }

    override val canSetVisibility: Boolean get() = true
    override suspend fun setPlaylistVisibility(ctx: Env, playlistId: String, public: Boolean) {
        call(ctx, "updatePlaylist", listOf("playlistId" to playlistId, "public" to public.toString()))
    }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) {
        call(ctx, "deletePlaylist", listOf("id" to playlistId))
    }

    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (playlistId == MusicProvider.LIKED_ID) {
            tracks.chunked(50).forEach { chunk -> call(ctx, "unstar", chunk.map { "id" to it.id }) }
            return
        }
        if (playlistId == MusicProvider.ALBUMS_ID) {
            tracks.chunked(50).forEach { chunk -> call(ctx, "unstar", chunk.map { "albumId" to it.id }) }
            return
        }
        if (playlistId == MusicProvider.ARTISTS_ID) {
            tracks.chunked(50).forEach { chunk -> call(ctx, "unstar", chunk.map { "artistId" to it.id }) }
            return
        }
        // Rimozione per posizione: gli indici sono quelli letti da getPlaylist, tutti in una chiamata.
        val indexes = tracks.mapNotNull { it.itemId }
        if (indexes.isEmpty()) return
        call(ctx, "updatePlaylist", listOf("playlistId" to playlistId) + indexes.map { "songIndexToRemove" to removalIndex(it) })
    }

    companion object {
        const val SERVICE = "subsonic"
        const val API_VERSION = "1.16.1"
        /** Where the server's kind is kept in the account's extras, as its answers give it ("navidrome"). */
        internal const val TYPE = "type"
        internal const val NAVIDROME = "navidrome"

        /**
         * Navidrome's web UI (react-admin, hash routing, served under /app) on its songs list filtered by
         * [query]: the "title" filter is the list's search box, a full-text search over title, artist and album.
         */
        internal fun navidromeSearchUrl(base: String, query: String): String =
            "$base/app/#/song?filter=" + Http.enc(JsonObject(mapOf("title" to JsonPrimitive(query))).toString())

        /**
         * Ampache drops every query parameter whose value is "0" (PHP truthiness), so the first entry
         * could not be removed there; "00" reads as 0 everywhere (Go, Java, Python, PHP).
         */
        internal fun removalIndex(index: String): String = if (index == "0") "00" else index

        /**
         * Error 40 from a server that says it is Ampache: Ampache checks the md5+salt token against the
         * user's separate "Subsonic password" (or API key), never the account password, so the account
         * password is refused even when it is right.
         */
        internal fun isAmpacheWrongPassword(r: JsonElement?): Boolean =
            r["error"]["code"].int == 40 && r["type"].str.equals("ampache", ignoreCase = true)

        /** The version to retry with when [r] is the "server must upgrade" refusal of a call made with [sent]. */
        internal fun olderVersion(r: JsonElement?, sent: String): String? =
            r["version"].str?.takeIf { r["error"]["code"].int == 30 && it.isNotBlank() && it != sent }
    }
}
