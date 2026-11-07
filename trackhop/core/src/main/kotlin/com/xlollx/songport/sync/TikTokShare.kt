package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.Relay
import com.xlollx.songport.net.get
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A TikTok video shared to Trackhop: the song it uses, read from the video's public page (no
 * account, no key), then searched for on a music service like any shared track.
 *
 * The page carries the video's sound as "music": a released song names its title, its artist and
 * usually its album ("Espresso", "Sabrina Carpenter"); a sound the creator recorded is "original
 * sound" under the creator's name, and nothing on the page says which song plays in it. Only the
 * full builds read TikTok's page ([enabled]), the Google Play and Microsoft Store ones do not.
 */
object TikTokShare {

    const val SERVICE = "tiktok"

    /**
     * Off in the builds limited to official services (Microsoft Store); on Google Play, on while a
     * connector plugin that reads the video pages is installed (see [com.xlollx.songport.net.Relay]).
     */
    @Volatile var gate: () -> Boolean = { true }
    var enabled: Boolean
        get() = gate()
        set(value) { gate = { value } }

    /**
     * A video's address: tiktok.com/@user/video/<id>, m.tiktok.com/v/<id>.html, or a short link of
     * the share sheet (vm.tiktok.com/<code>, vt.tiktok.com/<code>, tiktok.com/t/<code>).
     */
    val LINK = Regex(
        """https?://(?:(?:www|m)\.)?tiktok\.com/(?:@[^/\s?#]+/(?:video|photo)/(\d+)|v/(\d+)(?:\.html)?|t/([A-Za-z0-9]+))|https?://(?:vm|vt)\.tiktok\.com/([A-Za-z0-9]+)""",
        RegexOption.IGNORE_CASE,
    )

    fun isTikTok(text: String): Boolean = enabled && LINK.containsMatchIn(text)

    /**
     * Recognises the song in an audio file by its address (a TikTok sound's mp3), or null when none
     * is found: set where song recognition is available (the full builds in-process, the Play
     * build through the connector plugin, Docker and Desktop with their own decoder); null where
     * it is not, and an "original sound" stays unknown.
     */
    @Volatile var recognizer: (suspend (String) -> Track?)? = null

    /** The video id, or the short link's code: what tells two shares apart. */
    fun id(text: String): String? = LINK.find(text)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }

    /** What a TikTok page says about the video's sound. */
    sealed class Sound {
        /** A released song: search for it. [recognized] when the page did not name it and its audio did. */
        data class Song(val track: Track, val recognized: Boolean = false) : Sound()
        /**
         * The creator's own sound ("original sound"): the page does not say which song it is.
         * [audio] is the sound's own file, where the page gives it: what [recognizer] listens to.
         */
        data class Original(val creator: String, val audio: String? = null) : Sound()
        /** The page could not be read (the video is private or gone, or TikTok answered something else). */
        object Unknown : Sound()
    }

    /** Reads the video's page and tells its sound. */
    suspend fun sound(text: String): Sound {
        val url = LINK.find(text)?.value ?: return Sound.Unknown
        val page = runCatching { Relay.follow(url, Http.MOBILE_UA) }.getOrNull() ?: return Sound.Unknown
        if (page.code !in 200..299) return Sound.Unknown
        return hear(parse(page.page, id(page.url) ?: id(url) ?: ""))
    }

    /** An "original sound" is often a released song uploaded again: its audio may tell which. */
    suspend fun hear(sound: Sound): Sound {
        if (sound !is Sound.Original || sound.audio == null) return sound
        val heard = recognizer?.let { r -> runCatching { r(sound.audio) }.getOrNull() } ?: return sound
        return Sound.Song(heard, recognized = true)
    }

    /** The sound named by a video page's HTML. */
    fun parse(html: String, videoId: String = ""): Sound {
        val music = music(html) ?: return Sound.Unknown
        val title = music["title"].str.orEmpty().trim()
        val artist = music["authorName"].str.orEmpty().trim()
        val album = music["album"].str.orEmpty().trim()
        // A song TikTok knows has an album; without one, a title like "original sound" is the creator's.
        if (title.isEmpty() || (album.isEmpty() && isOriginal(title))) return Sound.Original(artist, music["playUrl"].str?.takeIf { it.startsWith("https://") })
        val id = music["id"].str ?: videoId
        return Sound.Song(Track(id = id, title = title, artists = listOfNotNull(artist.ifEmpty { null }), album = album))
    }

    /** What to search for on another service. */
    fun query(t: Track): String = if (t.artists.isEmpty()) t.title else "${t.artistLine} - ${t.title}"

    // The page's data: one JSON script ("__UNIVERSAL_DATA_FOR_REHYDRATION__", "SIGI_STATE" before 2024).
    private fun music(html: String): JsonElement? {
        for (idName in listOf("__UNIVERSAL_DATA_FOR_REHYDRATION__", "SIGI_STATE")) {
            val m = Regex("""<script[^>]*id="$idName"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html) ?: continue
            val root = runCatching { parseJson(m.groupValues[1]) }.getOrNull() ?: continue
            val scope = root["__DEFAULT_SCOPE__"]
            for (key in listOf("webapp.reflow.video.detail", "webapp.video-detail")) {
                scope[key]["itemInfo"]["itemStruct"]["music"]?.let { return it }
            }
            // SIGI_STATE: ItemModule.<id>.music
            (root["ItemModule"] as? JsonObject)?.values?.firstOrNull()?.get("music")?.let { return it }
        }
        return null
    }

    private val ORIGINAL = Regex(
        """^(?:original sound|son original|sonido original|suono originale|som original|originalton|originalsound|dźwięk oryginalny|оригинальный звук|orijinal ses|原声|オリジナル楽曲|원본 사운드)\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun isOriginal(title: String) = ORIGINAL.containsMatchIn(title.trim())

}
