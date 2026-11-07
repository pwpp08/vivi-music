package com.xlollx.songport.providers

import android.content.Context
import com.xlollx.songport.platform.Env
import com.xlollx.songport.platform.android
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.R
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.get
import com.xlollx.songport.net.str

/**
 * Apple Music through the companion app "Trackhop Bridge": the user signs in with their Apple ID on
 * music.apple.com inside the Bridge, no Apple Developer Program needed. The Bridge hands over the
 * web player's developer token and the music user token, and everything else is the normal
 * [AppleMusicProvider] code.
 *
 * Not an official route: those tokens belong to the web player. The Bridge shows the notice before
 * sign-in. The official path with your own developer token stays available as [AppleMusicProvider].
 */
class AppleBridgeProvider(slot: String = "") : AppleMusicProvider(slot), WebAccounts.Connector {
    override val serviceId = SERVICE
    override val displayName = if (BridgePlugin.builtIn) "Apple Music (web)" else "Apple Music (plugin)"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.EASY
    override val routeNoteName: String? get() = "route_note_web"
    override val familyName: String get() = "Apple Music"
    override val noteName = "provider_note_apple_bridge"
    /** Another account: where the WebView keeps profiles apart and the connectors take a slot (see [WebAccounts]). */
    override val supportsMultipleAccounts: Boolean get() = WebAccounts.allowed(STATUS)
    override val statusMethod: String get() = STATUS
    /** An extra account of a web connector is new: said so, as the other new connectors are. */
    override val beta: Boolean get() = slot.isNotEmpty()
    override val pluginBased = true
    override val setupGuide: SetupGuide? = null
    override val installUrl = BridgePlugin.installUrl
    override val notConfiguredName: String? = "bridge_needed_hint"

    // Tokens come from the Bridge; cached briefly so a playlist listing does not call it per request.
    @Volatile private var cache: Pair<Long, Bundle>? = null

    private fun tokens(ctx: Env): Bundle {
        val now = System.currentTimeMillis()
        cache?.let { (at, b) -> if (now - at < 5 * 60_000) return b }
        // Built in, the Bridge may fetch the token from the player, even through a WebView on the
        // main thread: the callers read tokens off the main thread (see api and amp).
        if (android.os.Looper.getMainLooper().isCurrentThread) throw ProviderException("$displayName: tokens are read off the main thread")
        val b = call(ctx, "apple.tokens")
        cache = now to b
        return b
    }

    override fun usesOwnCredentials(ctx: Env) = false
    // The Bridge's own reason (not signed in, token not found) is more useful than "no developer token".
    override fun developerToken(ctx: Env): String =
        runCatching { tokens(ctx) }.getOrElse { throw ProviderException(it.message ?: "$displayName: no tokens") }.getString("developerToken") ?: ""
    override fun userToken(ctx: Env): String? = runCatching { tokens(ctx).getString("userToken") }.getOrNull()
    // The storefront goes into URLs built on the caller's thread: the cache a token read filled
    // serves it there; failing that, the default one, rather than a network call on the main thread.
    override fun storefront(ctx: Env): String =
        cache?.second?.getString("storefront") ?: runCatching { tokens(ctx).getString("storefront") }.getOrNull() ?: super.storefront(ctx)

    override fun isConfigured(ctx: Env): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "apple.status").containsKey("connected") }.getOrDefault(false)
    override fun isConnected(ctx: Env): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "apple.status").getBoolean("connected", false) }.getOrDefault(false)
    override fun accountName(ctx: Env): String? = runCatching { call(ctx, "apple.status").getString("account") }.getOrNull()
    override fun canRead(ctx: Env, playlistId: String): Boolean = isConnected(ctx)

    // The player's token is bound to its origin: api.music.apple.com answers it with a bare 401. Every
    // call goes to the player's own backend instead, with the headers the player sends. The user's
    // token only on library calls: the catalogue answers a search that carries it with 429 42900
    // "Request is forbidden", which no waiting gets past (#46).
    override fun apiUrl(url: String): String = url.replace("https://api.music.apple.com", AMP)

    // One search at a time, about a second apart: a fast run of hundreds of searches makes Apple
    // refuse them all for an hour or more (#46, #47). Slower on a first big sync, but it gets through.
    override val searchParallelism: Int get() = 1
    override val searchSpacingMs: Long get() = 1000
    override fun apiHeaders(ctx: Env, user: Boolean): Map<String, String> = mapOf(
        "Origin" to "https://music.apple.com", "Referer" to "https://music.apple.com/",
        "media-user-token" to (if (user) runCatching { userToken(ctx) }.getOrNull() ?: "" else ""),
    ).filterValues { it.isNotEmpty() }

    // The web player's backend (amp-api.music.apple.com) takes the same two tokens and, unlike the
    // public API, removes tracks, renames and deletes library playlists: what music.apple.com does.
    override val canRemoveTracks: Boolean get() = true
    override val canRenamePlaylists: Boolean get() = true
    override val canDeletePlaylists: Boolean get() = true

    private suspend fun amp(ctx: Env, method: String, path: String, body: kotlinx.serialization.json.JsonElement? = null) {
        val (dev, user) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { developerToken(ctx) to userToken(ctx) }
        if (dev.isBlank() || user == null) throw ProviderException(ctx.text("error_not_connected", displayName))
        val headers = mapOf(
            "Authorization" to "Bearer $dev", "Music-User-Token" to user, "media-user-token" to user, "Accept" to "application/json",
            "Origin" to "https://music.apple.com", "Referer" to "https://music.apple.com/",
        )
        val resp = com.xlollx.songport.net.Http.send(method, "$AMP$path", headers, body?.let { com.xlollx.songport.net.Http.jsonBody(it.toString()) })
        if (!resp.ok) {
            val detail = com.xlollx.songport.net.parseJson(resp.body)["errors"][0]["detail"].str ?: resp.body.take(200)
            if (resp.code == 403 || detail.contains("subscription tier", ignoreCase = true) || detail.contains("CloudLibrary"))
                throw ProviderException(ctx.text("apple_subscription_needed", displayName, detail))
            throw ProviderException("$displayName ${resp.code}: $detail")
        }
    }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) {
        amp(ctx, "PATCH", "/v1/me/library/playlists/$playlistId", com.xlollx.songport.net.jsonObj("attributes" to mapOf("name" to name)))
    }

    override val canSetVisibility: Boolean get() = true
    override suspend fun setPlaylistVisibility(ctx: Env, playlistId: String, public: Boolean) {
        amp(ctx, "PATCH", "/v1/me/library/playlists/$playlistId", com.xlollx.songport.net.jsonObj("attributes" to mapOf("isPublic" to public)))
    }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) {
        amp(ctx, "DELETE", "/v1/me/library/playlists/$playlistId")
    }

    /** Entries go by their library id (i.xxx), which the playlist listing carries as itemId. */
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val missing = tracks.filter { it.itemId == null }.map { it.id }.toSet()
        val byCatalog = if (missing.isNotEmpty()) tracks(ctx, playlistId).filter { it.id in missing }.groupBy { it.id } else emptyMap()
        val libIds = tracks.flatMap { t -> t.itemId?.let { listOf(it) } ?: byCatalog[t.id].orEmpty().mapNotNull { it.itemId } }.distinct()
        for (lib in libIds) amp(ctx, "DELETE", "/v1/me/library/playlists/$playlistId/tracks?ids[library-songs]=${Http.enc(lib)}&mode=all")
    }

    override fun startAuth(ctx: Env) {
        val pkg = BridgePlugin.packageName(ctx)
        if (pkg == null) {
            installUrl?.let { ctx.android.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        BridgePlugin.startLogin(ctx, LOGIN_ACTION, slot)
    }

    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) { /* the Bridge completes the login itself */ }
    override fun disconnect(ctx: Env) { cache = null; runCatching { call(ctx, "apple.disconnect") } }

    private fun call(ctx: Env, method: String, arg: String? = null, extras: Bundle? = null): Bundle {
        if (!BridgePlugin.installed(ctx)) throw ProviderException(ctx.text("ytm_not_installed"))
        val b = try {
            BridgePlugin.call(ctx, method, arg, extras, slot)
        } catch (e: Exception) {
            throw ProviderException("Connector plugin: ${e.message ?: e.javaClass.simpleName}")
        } ?: throw ProviderException("Connector plugin: no answer")
        b.getString("error")?.let { throw ProviderException("$displayName: $it") }
        return b
    }

    companion object {
        const val SERVICE = "apple_bridge"
        const val LOGIN_ACTION = "com.xlollx.songport.ytmbridge.APPLE_LOGIN"
        /** The status call of this service, which says whether the connectors take extra accounts. */
        const val STATUS = "apple.status"
        private const val AMP = "https://amp-api.music.apple.com"
    }
}
