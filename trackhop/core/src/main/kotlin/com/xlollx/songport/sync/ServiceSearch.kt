package com.xlollx.songport.sync

import java.net.URLEncoder

/**
 * "Search it on…": the public search page of the big services for a track, by artist and title.
 * No API, no account: the service's own site (or app, where it claims the address) does the
 * search. The universal link's page (docs/open.html) builds the same addresses in JavaScript.
 */
object ServiceSearch {
    /** [serviceId] as the logos name it ([com.xlollx.songport.ui.ServiceLogos]). */
    class Site(val serviceId: String, val name: String, private val pattern: String) {
        fun url(query: String): String = pattern.replace("{q}", encode(query))
    }

    private fun encode(q: String) = URLEncoder.encode(q.replace(Regex("""[/\\]+"""), " ").replace(Regex("""\s+"""), " ").trim(), "UTF-8").replace("+", "%20")

    val SITES: List<Site> = listOf(
        Site("spotify", "Spotify", "https://open.spotify.com/search/{q}"),
        Site("apple", "Apple Music", "https://music.apple.com/search?term={q}"),
        Site("ytm", "YouTube Music", "https://music.youtube.com/search?q={q}"),
        Site("deezer", "Deezer", "https://www.deezer.com/search/{q}"),
        Site("tidal", "TIDAL", "https://listen.tidal.com/search?q={q}"),
        Site("amazon", "Amazon Music", "https://music.amazon.com/search/{q}"),
    )

    /** What to search for a track: its first artist and its title. */
    fun query(title: String, artist: String): String = (artist.split(", ").firstOrNull().orEmpty() + " " + title).trim()
}
