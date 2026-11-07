package com.xlollx.songport.providers

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.get
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env

/**
 * Emby (https://dev.emby.media): a personal server, like Jellyfin, which started as its fork and still
 * shares most of its API. The differences: the API lives under /emby, the client identifies itself in
 * X-Emby-Authorization with the token in X-Emby-Token, a playlist is created from query parameters,
 * and the web client has its own routes.
 */
class EmbyProvider(slot: String = "") : JellyfinProvider(slot) {
    override val serviceId = SERVICE
    override val displayName = "Emby"
    override val brandColor = 0xFF52B54B
    override val noteName = "provider_note_emby"
    override val beta = true
    override val supportsQuickConnect: Boolean get() = false
    override val loginForm = LoginForm(needsUrl = true, needsUser = true, needsSecret = true, hintName = "login_hint_emby", secretOptional = true)

    override fun headers(token: String?): Map<String, String> = embyHeaders(token)

    override fun apiRoot(base: String): String = embyRoot(base)

    override fun webSearchUrl(ctx: Env, query: String): String? =
        ctx.tokens.get(id)?.extra?.get("url")?.let { embySearchUrl(it, query) }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist {
        val j = api(ctx, "POST", "/Playlists?Name=${Http.enc(name)}&MediaType=Audio&UserId=${uid(ctx)}")
        return Playlist(j["Id"].str ?: throw ProviderException("$displayName: playlist not created"), name, 0)
    }

    companion object {
        const val SERVICE = "emby"

        internal fun embyHeaders(token: String?): Map<String, String> = buildMap {
            put("X-Emby-Authorization", "MediaBrowser Client=\"Vivi\", Device=\"Vivi\", DeviceId=\"vivi\", Version=\"1.0\"" +
                (token?.let { ", Token=\"$it\"" } ?: ""))
            token?.let { put("X-Emby-Token", it) }
            put("Accept", "application/json")
        }

        /** Emby answers its API under /emby; an address already ending there is kept as it is. */
        internal fun embyRoot(base: String): String = base.trimEnd('/').let { if (it.endsWith("/emby", ignoreCase = true)) it else "$it/emby" }

        /** Emby's web client search with [query] filled in. */
        internal fun embySearchUrl(base: String, query: String): String =
            base.trimEnd('/').removeSuffix("/emby") + "/web/index.html#!/search?query=" + Http.enc(query)
    }
}
