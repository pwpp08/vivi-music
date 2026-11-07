package com.xlollx.songport.sync

import com.xlollx.songport.model.Playlist

/**
 * The playlist the Shazam app keeps on the user's music service. Shazam has no public API for a
 * user's library, but in its settings it can be connected to Spotify or Apple Music: from then on
 * it adds every song the user Shazams to a playlist there, "My Shazam Tracks" (a localized name,
 * with "Shazam" in it, in other languages). A Shazam sync reads that playlist through the service's
 * own connector: nothing is asked of Shazam. Kotlin puro: coperto dai test.
 */
object ShazamLibrary {

    /** The playlist's name in English, the one Shazam uses in most languages. */
    const val NAME = "My Shazam Tracks"

    /**
     * The connectors whose service Shazam can fill: Spotify (the official API and the web player)
     * and Apple Music (the official API and the web player).
     */
    val HOSTS: List<String> = listOf("spotify", "spotify_bridge", "apple", "apple_bridge")

    /** True when a connector of [serviceId] can hold Shazam's playlist. */
    fun canHost(serviceId: String): Boolean = serviceId in HOSTS

    /**
     * Shazam's playlist among [playlists], or null. "My Shazam Tracks" first (the user's own copy
     * before a followed one); then one of the user's own playlists named with "Shazam" (the
     * localized names). A followed playlist with "Shazam" in its name and not the exact one is
     * left out: those are Shazam's public charts ("Shazam Top 200"), not the user's Shazams.
     */
    fun find(playlists: List<Playlist>): Playlist? {
        val exact = playlists.filter { it.name.trim().equals(NAME, ignoreCase = true) }
        return exact.firstOrNull { it.ownedByMe } ?: exact.firstOrNull()
            ?: playlists.firstOrNull { it.ownedByMe && it.name.contains("shazam", ignoreCase = true) }
    }
}
