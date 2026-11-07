package com.xlollx.songport.providers

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.Relay
import com.xlollx.songport.platform.Env
import com.xlollx.songport.sync.AnghamiPublic
import com.xlollx.songport.sync.NeteasePublic
import com.xlollx.songport.sync.QqMusicPublic

/**
 * The read-only, link-only sources: a public playlist of a service Trackhop has no account route
 * for, read from a pasted link as [SoundCloudProvider] reads a set. No login and no writing, so
 * nothing of the user's is ever at stake there. Installed by the builds that carry unofficial
 * connectors (not Google Play's, not the Microsoft Store's).
 */
abstract class LinkSource : MusicProvider {
    override val requiresAuth = false
    override val supportsMultipleAccounts = false
    override val canWrite = false
    override val canCreatePlaylists = false
    override val canRemoveTracks = false
    override val linkOnly = true
    override val searchParallelism = 1

    override fun label(ctx: Env): String = ctx.text("${serviceId}_label")
    /** Always where these sources run here; on Google Play, while a plugin makes their requests ([Relay]). */
    override fun isConfigured(ctx: Env) = Relay.available(ctx)
    override fun isConnected(ctx: Env) = Relay.available(ctx)
    override val pluginBased: Boolean get() = Relay.throughPlugin
    override fun accountName(ctx: Env): String? = null
    override fun startAuth(ctx: Env) = Unit
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) = Unit
    override fun disconnect(ctx: Env) = Unit

    /** Nothing to list: the source is always a pasted link. */
    override suspend fun playlists(ctx: Env): List<Playlist> = emptyList()
    override fun canRead(ctx: Env, playlistId: String) = !MusicProvider.isLibrary(playlistId) && Relay.available(ctx)

    /** Never a destination, so never searched or written. */
    override suspend fun search(ctx: Env, track: Track): List<Track> = emptyList()
    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist = readOnly(ctx)
    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) = readOnly(ctx)
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) = readOnly(ctx)
    private fun readOnly(ctx: Env): Nothing = throw ProviderException(ctx.text("error_read_only", displayName))
}

/** NetEase Cloud Music (网易云音乐): public playlists from a music.163.com link. */
object NeteaseProvider : LinkSource() {
    override val serviceId = NeteasePublic.SERVICE
    override val displayName = "NetEase Cloud Music"
    override val brandColor = 0xFFC20C0C
    override val noteName = "provider_note_netease"

    private suspend fun <T> reading(ctx: Env, block: suspend () -> T): T = try {
        block()
    } catch (e: NeteasePublic.NotPublic) {
        throw ProviderException(ctx.text("netease_not_public"), e, 404)
    } catch (e: NeteasePublic.Changed) {
        throw ProviderException(ctx.text("netease_changed"), e)
    }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = reading(ctx) { NeteasePublic.tracks(playlistId) }
    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist = reading(ctx) { NeteasePublic.info(playlistId) }
    override suspend fun playlistVersion(ctx: Env, playlistId: String): String? = runCatching { NeteasePublic.version(playlistId) }.getOrNull()
    override fun webSearchUrl(ctx: Env, query: String): String = "${NeteasePublic.SITE}/#/search/m/?s=${Http.enc(query)}&type=1"
}

/** QQ Music: public playlists (歌单) from a y.qq.com link. */
object QqMusicProvider : LinkSource() {
    override val serviceId = QqMusicPublic.SERVICE
    override val displayName = "QQ Music"
    override val brandColor = 0xFF31C27C
    override val noteName = "provider_note_qqmusic"

    private suspend fun <T> reading(ctx: Env, block: suspend () -> T): T = try {
        block()
    } catch (e: QqMusicPublic.NotPublic) {
        throw ProviderException(ctx.text("qqmusic_not_public"), e, 404)
    } catch (e: QqMusicPublic.Changed) {
        throw ProviderException(ctx.text("qqmusic_changed"), e)
    }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = reading(ctx) { QqMusicPublic.tracks(playlistId) }
    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist = reading(ctx) { QqMusicPublic.info(playlistId) }
    override suspend fun playlistVersion(ctx: Env, playlistId: String): String? = runCatching { QqMusicPublic.version(playlistId) }.getOrNull()
    override fun webSearchUrl(ctx: Env, query: String): String = "${QqMusicPublic.SITE}/n/ryqq/search?w=${Http.enc(query)}&t=song"
}

/** Anghami: public playlists from a link, read-only, no account (sync/AnghamiPublic). */
object AnghamiProvider : LinkSource() {
    override val serviceId = AnghamiPublic.SERVICE
    override val displayName = "Anghami"
    override val brandColor = 0xFF8D3DEB
    override val noteName = "provider_note_anghami"

    private suspend fun <T> reading(ctx: Env, block: suspend () -> T): T = try {
        block()
    } catch (e: AnghamiPublic.NotPublic) {
        throw ProviderException(ctx.text("anghami_not_public"), e, 404)
    } catch (e: AnghamiPublic.Changed) {
        throw ProviderException(ctx.text("anghami_changed"), e)
    }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = reading(ctx) { AnghamiPublic.tracks(playlistId) }
    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist = reading(ctx) { AnghamiPublic.info(playlistId) }
    override suspend fun playlistVersion(ctx: Env, playlistId: String): String? = runCatching { AnghamiPublic.version(playlistId) }.getOrNull()
    override fun webSearchUrl(ctx: Env, query: String): String = "${AnghamiPublic.SITE}/search/${Http.enc(query)}"
}
