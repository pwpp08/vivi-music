package com.xlollx.songport.sync

import com.xlollx.songport.net.get
import com.xlollx.songport.net.str

/**
 * Riconosce il link di un singolo brano e lo traduce in (servizio, id): nella revisione dei non
 * trovati si puo' incollare l'indirizzo del brano giusto invece di cercarlo a parole.
 * Kotlin puro: coperto dai test.
 */
object TrackLinks {

    data class Ref(val service: String, val trackId: String)

    private val RULES: List<Pair<Regex, String>> = listOf(
        // Spotify: https://open.spotify.com/track/4uLU6… oppure spotify:track:4uLU6…
        Regex("""(?:open\.spotify\.com/(?:intl-[a-z-]+/)?track/|spotify:track:)([A-Za-z0-9]+)""") to "spotify",
        // Apple Music: https://music.apple.com/it/album/nome/123456?i=654321 (il brano e' "i"),
        // oppure https://music.apple.com/it/song/nome/654321
        Regex("""music\.apple\.com/[^\s]*[?&]i=(\d+)""") to "apple",
        // The storefront segment is optional: music.apple.com/song/<id> is sent to the visitor's own.
        Regex("""music\.apple\.com/(?:[a-z]{2}/)?song/(?:[^/?\s]*/)?(\d+)""") to "apple",
        // YouTube / YouTube Music: watch?v=ID, youtu.be/ID
        Regex("""(?:music\.)?youtube\.com/[^\s]*[?&]v=([A-Za-z0-9_-]{11})""") to "youtube",
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})""") to "youtube",
        // Amazon Music: https://music.amazon.it/tracks/B0ABC…, oppure albums/B0…?trackAsin=B0…
        Regex("""music\.amazon\.[a-z.]+/[^\s]*[?&]trackAsin=([A-Z0-9]{10})(?![A-Z0-9])""") to "amazon",
        Regex("""music\.amazon\.[a-z.]+/tracks/([A-Z0-9]{10})(?![A-Z0-9])""") to "amazon",
        // Deezer: https://www.deezer.com/it/track/1234567
        Regex("""deezer\.com/(?:[a-z]{2}/)?track/(\d+)""") to "deezer",
        // TIDAL: https://tidal.com/browse/track/1234567
        Regex("""tidal\.com/(?:browse/)?track/(\d+)""") to "tidal",
        // Shazam: not a service to add to, but a song to find elsewhere (see ShazamShare).
        ShazamShare.LINK to ShazamShare.SERVICE,
        // TikTok: a video whose song is read from its page (see TikTokShare); the id is the video's.
        TikTokShare.LINK to TikTokShare.SERVICE,
    )

    /** @return il riferimento al brano, oppure null se il testo non e' un link riconosciuto. */
    fun parse(text: String): Ref? {
        val s = text.trim()
        if (s.isEmpty()) return null
        for ((regex, service) in RULES) {
            if (service == TikTokShare.SERVICE && !TikTokShare.enabled) continue
            val m = regex.find(s) ?: continue
            val id = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()
            if (id.isNotBlank()) return Ref(service, id)
        }
        return null
    }

    /** True se un link di [service] riguarda il servizio [serviceId] di un connettore (anche via Bridge). */
    /**
     * The same recording on [serviceId], through song.link (Odesli): a public service that maps a
     * track's link across services, no key needed, ten lookups a minute. Null when it does not
     * know the track there. Its free API has an announced retirement, so this is an extra, never
     * a step the review depends on.
     */
    suspend fun viaOdesli(link: String, serviceId: String): Ref? {
        val platform = when (serviceId) {
            "spotify", "spotify_bridge" -> "spotify"
            "apple", "apple_bridge" -> "appleMusic"
            "youtube", "ytm" -> "youtubeMusic"
            "tidal" -> "tidal"
            "deezer", "deezer_bridge" -> "deezer"
            "amazon" -> "amazonMusic"
            else -> return null
        }
        val url = Regex("""https?://\S+""").find(link)?.value ?: return null
        val resp = runCatching {
            com.xlollx.songport.net.Http.send("GET", "https://api.song.link/v1-alpha.1/links?userCountry=US&url=" + com.xlollx.songport.net.Http.enc(url), maxRateLimitRetries = 0)
        }.getOrNull() ?: return null
        if (!resp.ok) return null
        val j = com.xlollx.songport.net.parseJson(resp.body)
        val links = j["linksByPlatform"]
        val target = links[platform]["url"].str ?: (if (platform == "youtubeMusic") links["youtube"]["url"].str else null) ?: return null
        return parse(target)
    }

    /**
     * The public web address of a track on one of the link services, for "open it there": no API
     * call, the service's player resolves the id. Null for a service without a stable track page
     * (personal servers, Last.fm, files).
     */
    fun url(serviceId: String, trackId: String): String? = when (serviceId) {
        "spotify", "spotify_bridge" -> "https://open.spotify.com/track/$trackId"
        // Apple opens a song page from the id alone, whatever the storefront.
        "apple", "apple_bridge" -> "https://music.apple.com/song/$trackId"
        "youtube" -> "https://www.youtube.com/watch?v=$trackId"
        "ytm" -> "https://music.youtube.com/watch?v=$trackId"
        "amazon" -> "https://music.amazon.com/tracks/$trackId"
        "deezer", "deezer_bridge" -> "https://www.deezer.com/track/$trackId"
        "tidal" -> "https://tidal.com/browse/track/$trackId"
        ShazamShare.SERVICE -> "https://www.shazam.com/track/$trackId"
        else -> null
    }

    /** The link services as parse() names them, in the order a sheet lists them. */
    val SERVICES: List<String> = listOf("spotify", "apple", "youtube", "amazon", "deezer", "tidal")

    fun matches(service: String, serviceId: String): Boolean = when (service) {
        "spotify" -> serviceId == "spotify" || serviceId == "spotify_bridge"
        "apple" -> serviceId == "apple" || serviceId == "apple_bridge"
        "youtube" -> serviceId == "youtube" || serviceId == "ytm"
        "deezer" -> serviceId == "deezer" || serviceId == "deezer_bridge"
        else -> serviceId == service
    }
}
