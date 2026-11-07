package com.xlollx.songport.providers

import com.xlollx.songport.platform.Env
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Matcher
import kotlinx.serialization.json.JsonElement

/**
 * Plex Media Server. Si accede con l'indirizzo del server e il proprio X-Plex-Token (Plex
 * documenta come trovarlo). A new playlist is made empty, pointed at the server's library
 * (checked on Plex 1.43): the engine then adds the tracks as on any other target.
 */
class PlexProvider(override val slot: String = "") : CredentialsProvider() {
    override val serviceId = SERVICE
    override val displayName = "Plex"
    override val routeNoteName: String? get() = "route_note_server"
    override val brandColor = 0xFFE5A00D
    override val noteName = "provider_note_plex"
    // Out of beta (01/11/2026): used against real servers and accounts, and passing LiveServersIT.
    override val beta = false
    override val loginForm = LoginForm(needsUrl = true, needsUser = false, needsSecret = true, secretLabelName = "login_token", hintName = "login_hint_plex")

    override suspend fun login(ctx: Env, url: String, user: String, secret: String) {
        val base = normalizeUrl(url)
        val t = Tokens(accessToken = secret.trim(), extra = mapOf("url" to base))
        val id = api(ctx, "GET", "/identity", t)["MediaContainer"]["machineIdentifier"].str
            ?: throw ProviderException("$displayName: server not recognised")
        save(ctx, t.copy(extra = mapOf("url" to base, "machine" to id), userName = base.removePrefix("https://").removePrefix("http://")))
    }

    private suspend fun api(ctx: Env, method: String, path: String, t: Tokens = creds(ctx)): JsonElement {
        val resp = Http.send(method, "${t.extra["url"]}$path", mapOf("X-Plex-Token" to t.accessToken, "Accept" to "application/json"))
        if (!resp.ok) throw ProviderException("$displayName API ${resp.code}: ${resp.body.take(200)}")
        return parseJson(resp.body)
    }

    private fun toTrack(m: JsonElement?): Track? {
        val id = m["ratingKey"].str ?: return null
        return Track(
            id = id,
            title = m["title"].str ?: "",
            artists = listOfNotNull(m["originalTitle"].str ?: m["grandparentTitle"].str),
            album = m["parentTitle"].str ?: "",
            durationMs = m["duration"].long ?: 0,
            itemId = m["playlistItemID"].str ?: m["playlistItemID"].long?.toString(),
        )
    }

    override suspend fun playlists(ctx: Env): List<Playlist> =
        api(ctx, "GET", "/playlists?playlistType=audio")["MediaContainer"]["Metadata"].arr.mapNotNull { p ->
            Playlist(p["ratingKey"].str ?: return@mapNotNull null, p["title"].str ?: "", p["leafCount"].int ?: -1)
        }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> =
        api(ctx, "GET", "/playlists/$playlistId/items")["MediaContainer"]["Metadata"].arr.mapNotNull { toTrack(it) }

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        // Plex matches the query against one name at a time ("metallica battery" finds nothing): the
        // title alone, then the artist's songs first.
        val q = Matcher.searchTitle(track.title)
        val hubs = api(ctx, "GET", "/hubs/search?query=${Http.enc(q)}&limit=20")["MediaContainer"]["Hub"].arr
        return Matcher.byArtist(track, hubs.filter { it["type"].str == "track" }.flatMap { it["Metadata"].arr }.mapNotNull { toTrack(it) }).take(5)
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist {
        val t = creds(ctx)
        val machine = t.extra["machine"] ?: throw ProviderException("$displayName: reconnect")
        // An empty audio playlist: the uri names the library only, no item (Plex wants a uri even then).
        val uri = "server://$machine/com.plexapp.plugins.library"
        val made = api(ctx, "POST", "/playlists?type=audio&title=${Http.enc(name)}&smart=0&uri=${Http.enc(uri)}", t)
        val id = made["MediaContainer"]["Metadata"][0]["ratingKey"].str
            ?: throw ProviderException("$displayName: playlist not created")
        // The description is Plex's "summary": a nicety, never a reason to fail the new playlist.
        if (description.isNotBlank()) runCatching { api(ctx, "PUT", "/playlists/$id?summary=${Http.enc(description)}", t) }
        return Playlist(id, name, 0)
    }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) {
        api(ctx, "PUT", "/playlists/$playlistId?title=${Http.enc(name)}")
    }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) {
        api(ctx, "DELETE", "/playlists/$playlistId")
    }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        val machine = creds(ctx).extra["machine"] ?: throw ProviderException("$displayName: reconnect")
        tracks.chunked(50).forEach { chunk ->
            val uri = "server://$machine/com.plexapp.plugins.library/library/metadata/${chunk.joinToString(",") { it.id }}"
            api(ctx, "PUT", "/playlists/$playlistId/items?uri=${Http.enc(uri)}")
        }
    }

    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        tracks.mapNotNull { it.itemId }.forEach { api(ctx, "DELETE", "/playlists/$playlistId/items/$it") }
    }

    companion object { const val SERVICE = "plex" }
}
