package com.xlollx.songport.providers

/**
 * Playlists Spotify itself makes and keeps (daylist, Discover Weekly, Release Radar, the Daily
 * Mixes, the "This is…" and editorial ones): they can be read, never written, whatever a listing
 * says about their owner. Their ids start with "37i9dQZ"; their owner is the "spotify" user.
 */
object SpotifyMade {
    private const val PREFIX = "37i9dQZ"

    fun isSpotifyMade(playlistId: String?, owner: String? = null): Boolean =
        playlistId?.startsWith(PREFIX) == true || owner.equals("spotify", ignoreCase = true)
}
