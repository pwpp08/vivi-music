package com.xlollx.songport.providers

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.platform.Env
import com.xlollx.songport.sync.SoundCloudPublic

/**
 * SoundCloud, read-only and through public links only: a pasted set (playlist, album, EP) or a
 * user's likes page becomes the source of a sync. No login and no writing; the reading goes
 * through the site's own web player interface ([SoundCloudPublic]), which is not official.
 * Installed by the builds that carry unofficial connectors (not the Google Play one).
 */
object SoundCloudProvider : MusicProvider {
    override val serviceId = SoundCloudPublic.SERVICE
    override val displayName = "SoundCloud"
    override val brandColor = 0xFFFF5500
    override val noteName = "provider_note_soundcloud"
    override val requiresAuth = false
    override val supportsMultipleAccounts = false
    override val canWrite = false
    override val canCreatePlaylists = false
    override val canRemoveTracks = false
    override val linkOnly = true
    override val searchParallelism = 1

    /** Where the service is named next to a playlist: what kind of access this is, and that it is unofficial. */
    override fun label(ctx: Env): String = ctx.text("soundcloud_label")

    override fun isConfigured(ctx: Env) = true
    override fun isConnected(ctx: Env) = true
    override fun accountName(ctx: Env): String? = null
    override fun startAuth(ctx: Env) = Unit
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) = Unit
    override fun disconnect(ctx: Env) = Unit

    /** The reader's errors in the user's words: a private set, or a site that changed under us. */
    private suspend fun <T> reading(ctx: Env, block: suspend () -> T): T = try {
        block()
    } catch (e: SoundCloudPublic.NotPublic) {
        throw ProviderException(ctx.text("soundcloud_not_public"), e, 404)
    } catch (e: SoundCloudPublic.PlayerChanged) {
        throw ProviderException(ctx.text("soundcloud_player_changed"), e)
    }

    /** Nothing to list: a SoundCloud source is always a pasted link. */
    override suspend fun playlists(ctx: Env): List<Playlist> = emptyList()
    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = reading(ctx) { SoundCloudPublic.tracks(playlistId) }
    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist = reading(ctx) { SoundCloudPublic.info(playlistId) }
    override suspend fun playlistVersion(ctx: Env, playlistId: String): String? =
        runCatching { SoundCloudPublic.version(playlistId) }.getOrNull()
    override fun canRead(ctx: Env, playlistId: String) = !MusicProvider.isLibrary(playlistId)

    /** Never a destination, so never searched. */
    override suspend fun search(ctx: Env, track: Track): List<Track> = emptyList()
    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist = readOnly(ctx)
    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) = readOnly(ctx)
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) = readOnly(ctx)
    private fun readOnly(ctx: Env): Nothing = throw ProviderException(ctx.text("error_read_only", displayName))

    override fun webSearchUrl(ctx: Env, query: String): String = "${SoundCloudPublic.SITE}/search/sounds?q=${Http.enc(query)}"
}
