package com.xlollx.songport.sync

/**
 * Riconosce i link delle playlist pubbliche e li traduce in (servizio, id), cosi' si puo'
 * sincronizzare anche una playlist che non e' nostra incollandone l'indirizzo.
 * Kotlin puro: coperto dai test.
 */
object PlaylistLinks {

    data class Ref(val providerId: String, val playlistId: String)

    private val RULES: List<Pair<Regex, String>> = listOf(
        // Spotify: https://open.spotify.com/playlist/37i9dQ… oppure spotify:playlist:37i9dQ…
        Regex("""(?:open\.spotify\.com/(?:intl-[a-z-]+/)?playlist/|spotify:playlist:)([A-Za-z0-9]+)""") to "spotify",
        // Apple Music: https://music.apple.com/it/playlist/nome/pl.u-xxxx
        Regex("""music\.apple\.com/[^/]+/playlist/[^/]*/?(pl\.[A-Za-z0-9-]+)""") to "apple",
        Regex("""music\.apple\.com/[^/]+/playlist/(pl\.[A-Za-z0-9-]+)""") to "apple",
        // YouTube / YouTube Music: ?list=PL…
        Regex("""(?:music\.)?youtube\.com/[^\s]*[?&]list=([A-Za-z0-9_-]+)""") to "youtube",
        Regex("""youtu\.be/[^\s]*[?&]list=([A-Za-z0-9_-]+)""") to "youtube",
        // Deezer: https://www.deezer.com/it/playlist/1234567
        Regex("""deezer\.com/(?:[a-z]{2}/)?playlist/(\d+)""") to "deezer",
        Regex("""deezer\.page\.link/playlist/(\d+)""") to "deezer",
        // TIDAL: https://tidal.com/browse/playlist/uuid
        Regex("""tidal\.com/(?:browse/)?playlist/([0-9a-fA-F-]{36})""") to "tidal",
        // SoundCloud: https://soundcloud.com/user/sets/slug (with /s-… when unlisted) or …/user/likes.
        // The id is the page's path ("you/…" is whoever is signed in: no public page); on.soundcloud.com
        // short links are followed first.
        Regex("""(?<![\w-])(?:(?:www|m)\.)?soundcloud\.com/((?!you/)[A-Za-z0-9_-]+/(?:sets/[A-Za-z0-9_-]+(?:/s-[A-Za-z0-9]+)?|likes))(?![\w-])""") to "soundcloud",
        // NetEase Cloud Music: https://music.163.com/#/playlist?id=123, …/playlist/123/…, the mobile
        // y.music.163.com/m/playlist?id=123; the app's 163cn.tv short links are followed first.
        Regex("""music\.163\.com/(?:#/)?(?:m/)?(?:my/m/music/)?playlist\?(?:[^\s#]*&)?id=(\d+)""") to "netease",
        Regex("""music\.163\.com/(?:#/)?(?:m/)?playlist/(\d+)""") to "netease",
        // QQ Music: https://y.qq.com/n/ryqq/playlist/123 (older: /n/yqq/playlist/123.html), the shared
        // i.y.qq.com/n2/m/share/details/taoge.html?…&id=123; c.y.qq.com short links are followed first.
        Regex("""y\.qq\.com/n/(?:ryqq|yqq)/playlist/(\d+)""") to "qqmusic",
        // Anghami: https://play.anghami.com/playlist/123 (also anghami.com/playlist/123).
        Regex("""(?:play\.)?anghami\.com/playlist/(\d+)""") to "anghami",
        Regex("""y\.qq\.com/[^\s]*(?:taoge|playlist)[^\s]*?[?&](?:id|disstid)=(\d+)""") to "qqmusic",
    )

    /** Every playlist link in [text], in order and without repeats: two links shared together are a comparison. */
    fun parseAll(text: String): List<Ref> {
        val out = LinkedHashSet<Ref>()
        for (piece in text.split(Regex("""[\s<>"']+"""))) {
            if (piece.isBlank()) continue
            for ((regex, provider) in RULES) {
                val m = regex.find(piece) ?: continue
                val id = m.groupValues[1]
                if (id.isNotBlank()) { out += Ref(provider, id); break }
            }
        }
        return out.toList()
    }

    /**
     * The playlist's page on its service, for a QR or a link to share; null for library slots and
     * for services without a public address. [base] is the user's own server (Navidrome, Jellyfin,
     * Plex), [region] the Apple storefront or the Amazon site's suffix ("it", "com").
     */
    fun webUrl(providerId: String, playlistId: String, base: String? = null, region: String? = null): String? {
        if (playlistId.startsWith("__")) return null
        val b = base?.trimEnd('/')
        return when (providerId.substringBefore('@')) {
            "spotify", "spotify_bridge" -> "https://open.spotify.com/playlist/$playlistId"
            "apple", "apple_bridge" -> "https://music.apple.com/${region?.lowercase()?.takeIf { it.length == 2 } ?: "us"}/playlist/$playlistId"
            "ytm" -> "https://music.youtube.com/playlist?list=$playlistId"
            "youtube" -> "https://www.youtube.com/playlist?list=$playlistId"
            "deezer", "deezer_bridge" -> "https://www.deezer.com/playlist/$playlistId"
            "tidal" -> "https://tidal.com/browse/playlist/$playlistId"
            "soundcloud" -> "https://soundcloud.com/$playlistId"
            "netease" -> "https://music.163.com/#/playlist?id=$playlistId"
            "anghami" -> "https://play.anghami.com/playlist/$playlistId"
            "qqmusic" -> "https://y.qq.com/n/ryqq/playlist/$playlistId"
            "amazon" -> "https://music.amazon.${region?.lowercase()?.takeIf { it.isNotBlank() } ?: "com"}/user-playlists/$playlistId"
            "jellyfin" -> b?.let { "$it/web/#/details?id=$playlistId" }
            "emby" -> b?.let { it.trimEnd('/').removeSuffix("/emby") + "/web/index.html#!/item?id=$playlistId" }
            "subsonic", "plex" -> b
            else -> null
        }
    }

    /** @return il riferimento alla playlist, oppure null se il testo non e' un link riconosciuto. */
    fun parse(text: String): Ref? {
        val s = text.trim()
        if (s.isEmpty()) return null
        for ((regex, provider) in RULES) {
            val m = regex.find(s) ?: continue
            val id = m.groupValues[1]
            if (id.isNotBlank()) return Ref(provider, id)
        }
        return null
    }
}
