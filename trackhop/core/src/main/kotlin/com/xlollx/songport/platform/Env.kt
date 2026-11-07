package com.xlollx.songport.platform

import com.xlollx.songport.data.FileStore
import com.xlollx.songport.data.TokenVault
import com.xlollx.songport.providers.MusicProvider
import java.io.File

/**
 * What the engine needs from the platform it runs on: the Android app implements it with its
 * Context, the server build with its data folder. Connectors and the sync engine receive it where
 * they used to receive an Android Context.
 */
interface Env {
    /** The app's private folder: the store, playlist files, logs. */
    val dataDir: File

    /** Jobs, reports, caches and settings. */
    val store: FileStore

    /** Access tokens and server credentials, kept encrypted where the platform can. */
    val tokens: TokenVault

    /** The OAuth redirect this platform receives sign-ins on. */
    val redirectUri: String

    /** A user-facing text, by its name in strings.xml, in the user's language. */
    fun text(name: String, vararg args: Any): String

    /** One line in the diagnostic log. */
    fun log(tag: String, message: String)

    /** Starts the sign-in of [provider] the way [mode] says (browser, credentials form...). */
    fun startAuth(provider: MusicProvider, mode: AuthMode)

    /** A small persistent string map, by name: caches that outlive the process (the ISRC answers...). */
    fun keyValues(name: String): KeyValues

    /**
     * [raw] image bytes as a JPEG of at most [maxSide] px and [maxBytes] bytes, or null when the
     * image can't be decoded or never fits.
     */
    fun shrinkJpeg(raw: ByteArray, maxSide: Int, maxBytes: Int): ByteArray?

    companion object {
        /** The redirect of the Android app, also the one shown in the setup guides. */
        const val APP_REDIRECT_URI = "songport://callback"
    }
}

/** How a connector signs in. */
enum class AuthMode {
    /** OAuth in the browser (or the in-app sign-in page), back on [Env.redirectUri]. */
    BROWSER,
    /** OAuth with a loopback redirect, for a Google "installed app" client of the user's own. */
    LOOPBACK,
    /** A form for server address, user and password (Subsonic, Jellyfin, Plex). */
    CREDENTIALS,
    /** Apple's MusicKit sign-in, which hands over a music user token. */
    MUSICKIT,
}

/**
 * Credentials and endpoints fixed at build time (client ids, the Deezer redirect page...), set by
 * the platform at start. A missing key reads as "".
 */
object BuildDefaults {
    @Volatile var values: Map<String, String> = emptyMap()
    operator fun get(key: String): String = values[key].orEmpty()
}

/** A persistent string map; writes may be applied in the background. */
interface KeyValues {
    operator fun get(key: String): String?
    operator fun set(key: String, value: String)
    val size: Int
    fun clear()
}
