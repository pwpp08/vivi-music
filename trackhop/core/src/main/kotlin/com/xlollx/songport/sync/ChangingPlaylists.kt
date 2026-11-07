package com.xlollx.songport.sync

import com.xlollx.songport.providers.ChartsProvider

/**
 * Playlists a service rewrites by itself: a Spotify Discover Weekly, Release Radar or daylist, an
 * Apple Music New Music Mix, a YouTube Music Discover Mix, the charts. A sync from one of them can
 * copy the latest version (replace) or keep every track it ever had (SyncJob.archive): the editor
 * asks which. Known by name, as the services list them (Spotify's names in the languages it
 * translates them into, where known); a playlist not recognised still has the choice among the
 * sync's options. Kotlin puro.
 */
object ChangingPlaylists {
    /** Whole names, lower case. */
    private val NAMES = setOf(
        // Spotify, and its translations of Discover Weekly and Release Radar.
        "discover weekly", "release radar", "on repeat", "repeat rewind",
        "descubrimiento semanal", "radar de novedades", "découvertes de la semaine", "radar des sorties",
        "dein mix der woche", "descobertas da semana", "radar de novidades", "odkryj w tym tygodniu", "radar premier",
        "haftalık keşif",
        // Apple Music.
        "new music mix", "favorites mix", "favourites mix", "chill mix", "get up! mix", "friends mix",
        // YouTube Music.
        "my supermix", "discover mix", "new release mix", "replay mix",
        // TIDAL.
        "my daily discovery", "my new arrivals",
    )

    /** Starts of names: numbered or titled series ("Daily Mix 3", "daylist • chill tuesday", "My Mix 2"). */
    private val PREFIXES = listOf("daily mix", "daylist", "my mix")

    /** The playlist [name] on [serviceId] changes by itself. */
    fun isSelfChanging(serviceId: String?, name: String?): Boolean {
        if (serviceId == ChartsProvider.SERVICE) return true
        val n = name?.trim()?.lowercase() ?: return false
        if (n.isEmpty()) return false
        return n in NAMES || PREFIXES.any { n.startsWith(it) }
    }

    /** Spotify's own, made for the listener: what its Web API no longer lets a developer key read (since February 2026). */
    private val SPOTIFY_MADE = setOf(
        "discover weekly", "release radar", "on repeat", "repeat rewind",
        "descubrimiento semanal", "radar de novedades", "découvertes de la semaine", "radar des sorties",
        "dein mix der woche", "descobertas da semana", "radar de novidades", "odkryj w tym tygodniu", "radar premier",
        "haftalık keşif",
    )

    /**
     * [name] is a playlist Spotify makes itself (Discover Weekly, Release Radar, a daylist, a Daily
     * Mix): the official connection (serviceId "spotify") sees it empty or not at all; the quick
     * route ("spotify_bridge") reads it.
     */
    fun madeBySpotify(name: String?): Boolean {
        val n = name?.trim()?.lowercase() ?: return false
        return n in SPOTIFY_MADE || n.startsWith("daylist") || n.startsWith("daily mix")
    }

    /**
     * The weekly playlist worth keeping whole on [serviceId], as the service names it, where Trackhop
     * can read it: Spotify's through the web route only (Spotify's Web API refuses its own playlists
     * to apps in Development Mode), YouTube Music's through its web route, Apple Music's once the
     * user has added it to the library. Null where there is none.
     */
    fun weekly(serviceId: String): String? = when (serviceId) {
        "spotify_bridge" -> "Discover Weekly"
        "ytm" -> "Discover Mix"
        "apple", "apple_bridge" -> "New Music Mix"
        else -> null
    }
}
