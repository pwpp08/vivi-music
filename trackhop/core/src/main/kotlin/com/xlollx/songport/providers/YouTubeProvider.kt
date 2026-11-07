package com.xlollx.songport.providers

import com.xlollx.songport.platform.AuthMode
import com.xlollx.songport.platform.Env
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.model.UnplayableEntry
import com.xlollx.songport.sync.Unplayable
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.HttpResponse
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.jsonObj
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Durations
import com.xlollx.songport.sync.QuotaMeter
import com.xlollx.songport.sync.TitleParser
import kotlinx.serialization.json.JsonElement

/**
 * YouTube Music non ha un'API pubblica: usiamo YouTube Data API v3, le playlist sono le stesse.
 * Quota: 10.000 unita'/giorno per progetto Google. Una ricerca costa 100, un inserimento 50:
 * ~60 brani nuovi al giorno per TUTTI gli utenti dell'app finche' Google non alza la quota.
 * La cache degli abbinamenti (Store.matchCache) evita di ripetere le ricerche.
 */
class YouTubeProvider(override val slot: String = "") : OAuthProvider() {
    override val serviceId = SERVICE
    override val revokeUrl = "https://myaccount.google.com/permissions"
    override val displayName = "YouTube Music (Google API)"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.OFFICIAL
    override val routeNoteName: String? get() = "route_note_youtube_api"
    override val familyName: String get() = "YouTube Music"
    override val brandColor = 0xFFFF0000
    override val noteName = "provider_note_youtube"
    // Liked songs: the videos the account liked, the music ones (issue #30). Liking or unliking one
    // costs 50 quota units, as a playlist write does.
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true

    override val setupGuide = SetupGuide(
        dashboardUrl = "https://console.cloud.google.com/apis/credentials",
        whyName = "setup_why_youtube",
        stepsArrayName = "setup_steps_youtube",
        fieldLabelName = "setup_field_client_id",
        needsSecret = true,
        // La console ricorda l'ultimo progetto scelto: dopo il primo passo i link si aprono gia' dentro.
        stepUrls = listOf(
            "https://console.cloud.google.com/projectcreate",
            "https://console.cloud.google.com/apis/library/youtube.googleapis.com",
            "https://console.cloud.google.com/auth/overview",
            "https://console.cloud.google.com/auth/audience",
            "https://console.cloud.google.com/auth/clients/create",
        ),
    )

    override val defaultClientId: String get() = BuildDefaults["GOOGLE_CLIENT_ID"]
    override val authorizeEndpoint = "https://accounts.google.com/o/oauth2/v2/auth"
    override val tokenEndpoint = "https://oauth2.googleapis.com/token"
    override val scopes = "https://www.googleapis.com/auth/youtube"
    // prompt=consent garantisce il refresh token anche ai riaccessi successivi.
    override val extraAuthParams = mapOf("access_type" to "offline", "prompt" to "consent")
    // Extra accounts: Google shows the account chooser instead of reusing the signed-in one.
    override val switchAccountParams = mapOf("prompt" to "consent select_account")

    // Client Android della build: Google impone lo schema "client id invertito" (nel manifest).
    override fun redirectUri(ctx: Env) = "${BuildDefaults["GOOGLE_REDIRECT_SCHEME"]}:/oauth2redirect"

    override fun isConfigured(ctx: Env) = clientId(ctx).isNotBlank()

    /**
     * Con le credenziali dell'utente si usa il flusso "installed app": client OAuth di tipo
     * Desktop e redirect su 127.0.0.1, perche' un client Desktop non accetta schemi personalizzati.
     * Cosi' la quota giornaliera YouTube consumata e' quella del progetto Google dell'utente,
     * non quella (condivisa da tutti) della build.
     */
    override fun startAuth(ctx: Env) {
        ctx.startAuth(this, if (ownClientId(ctx) != null) AuthMode.LOOPBACK else AuthMode.BROWSER)
    }



    /** Ogni chiamata ha un costo in unita' di quota: lo registriamo per mostrarlo all'utente. */
    private suspend fun counted(ctx: Env, cost: Long, call: suspend () -> JsonElement): JsonElement {
        val r = call()
        QuotaMeter.add(ctx, serviceId, cost)
        return r
    }

    override suspend fun enrichAccount(ctx: Env, t: Tokens): Tokens {
        val j = api(ctx, "GET", "$API/channels?part=snippet&mine=true")
        val ch = j["items"][0]
        return t.copy(userId = ch["id"].str ?: "", userName = ch["snippet"]["title"].str ?: "")
    }

    override fun errorMessage(resp: HttpResponse): String {
        val e = runCatching { parseJson(resp.body)["error"] }.getOrNull()
        val reason = e["errors"][0]["reason"].str
        if (reason == "quotaExceeded") return "the daily YouTube API quota (a limit set by Google, not by Vivi) is used up; it resets at midnight Pacific time"
        return e["message"].str ?: super.errorMessage(resp)
    }

    override suspend fun playlists(ctx: Env): List<Playlist> {
        val out = ArrayList<Playlist>()
        var page: String? = null
        do {
            val j = counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/playlists?part=snippet,contentDetails,status&mine=true&maxResults=50" +
                (page?.let { "&pageToken=$it" } ?: "")) }
            for (p in j["items"].arr) {
                out += Playlist(
                    id = p["id"].str ?: continue,
                    name = p["snippet"]["title"].str ?: "",
                    trackCount = p["contentDetails"]["itemCount"].int ?: -1,
                    isPublic = p["status"]["privacyStatus"].str?.let { it == "public" },
                )
            }
            page = j["nextPageToken"].str
        } while (page != null)
        return out
    }

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist {
        val p = counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/playlists?part=snippet,contentDetails&id=$playlistId") }["items"][0]
        return Playlist(playlistId, p["snippet"]["title"].str ?: playlistId, p["contentDetails"]["itemCount"].int ?: -1, ownedByMe = false, description = p["snippet"]["description"].str.orEmpty())
    }

    private suspend fun isEmptyPlaylist(ctx: Env, playlistId: String): Boolean {
        val j = runCatching { counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/playlists?part=contentDetails&id=$playlistId") } }.getOrNull()
        val p = j["items"].arr.firstOrNull() ?: return false
        return (p["contentDetails"]["itemCount"].int ?: -1) == 0
    }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        if (playlistId == MusicProvider.LIKED_ID) return likedTracks(ctx)
        data class Raw(val itemId: String, val videoId: String, val title: String, val channel: String?)
        val raws = ArrayList<Raw>()
        var page: String? = null
        do {
            val j = try {
                counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/playlistItems?part=snippet,contentDetails&maxResults=50&playlistId=$playlistId" +
                    (page?.let { "&pageToken=$it" } ?: "")) }
            } catch (e: ProviderException) {
                // The Data API answers 404 "playlist cannot be found" for a playlist that exists but is
                // empty (typically one just created). Check the playlist itself before giving up.
                if (page == null && e.message?.contains("cannot be found") == true && isEmptyPlaylist(ctx, playlistId)) {
                    ctx.log(id, "playlist $playlistId is empty; YouTube reports it as not found")
                    return emptyList()
                }
                throw e
            }
            for (item in j["items"].arr) {
                val vid = item["contentDetails"]["videoId"].str ?: continue
                val title = item["snippet"]["title"].str ?: continue
                if (title == "Private video" || title == "Deleted video") continue
                raws += Raw(item["id"].str ?: continue, vid, title, item["snippet"]["videoOwnerChannelTitle"].str)
            }
            page = j["nextPageToken"].str
        } while (page != null)

        // Durate (1 unita' di quota ogni 50 video): migliorano molto l'abbinamento.
        val durations = HashMap<String, Long>()
        raws.map { it.videoId }.distinct().chunked(50).forEach { ids ->
            val j = counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/videos?part=contentDetails&id=${ids.joinToString(",")}") }
            for (v in j["items"].arr) {
                val id = v["id"].str ?: continue
                durations[id] = Durations.parseIso8601(v["contentDetails"]["duration"].str)
            }
        }
        return raws.map { r ->
            val (title, artists) = TitleParser.parseYouTube(r.title, r.channel)
            Track(id = r.videoId, title = title, artists = artists, durationMs = durations[r.videoId] ?: 0, itemId = r.itemId)
        }
    }

    override val detectsUnplayable: Boolean get() = true

    /**
     * Entries YouTube shows as "Private video" or "Deleted video", and videos the API no longer
     * lists at all. The position counts every entry of the playlist, placeholders included. One
     * quota unit per page and one per fifty videos.
     */
    override suspend fun unplayable(ctx: Env, playlistId: String): List<UnplayableEntry> {
        if (MusicProvider.isLibrary(playlistId)) return emptyList()
        data class Raw(val index: Int, val itemId: String, val videoId: String, val title: String, val channel: String?)
        val raws = ArrayList<Raw>()
        var page: String? = null
        var index = 0
        do {
            val j = counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/playlistItems?part=snippet,contentDetails&maxResults=50&playlistId=$playlistId" +
                (page?.let { "&pageToken=$it" } ?: "")) }
            for (item in j["items"].arr) {
                val vid = item["contentDetails"]["videoId"].str ?: continue
                raws += Raw(index++, item["id"].str ?: continue, vid, item["snippet"]["title"].str ?: "", item["snippet"]["videoOwnerChannelTitle"].str)
            }
            page = j["nextPageToken"].str
        } while (page != null)
        // Which of the videos the API still knows: a deleted one is simply missing from the answer.
        val known = HashSet<String>()
        raws.map { it.videoId }.distinct().chunked(50).forEach { ids ->
            val j = counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/videos?part=id&id=${ids.joinToString(",")}") }
            j["items"].arr.mapNotNullTo(known) { it["id"].str }
        }
        return raws.mapNotNull { r ->
            val reason = when {
                r.title == "Private video" -> Unplayable.REASON_PRIVATE
                r.title == "Deleted video" || r.videoId !in known -> Unplayable.REASON_DELETED
                else -> return@mapNotNull null
            }
            val (title, artists) = TitleParser.parseYouTube(r.title, r.channel)
            UnplayableEntry(Track(id = r.videoId, title = title, artists = artists, itemId = r.itemId, available = false), r.index, reason)
        }
    }

    override suspend fun track(ctx: Env, trackId: String): Track? {
        val v = counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/videos?part=snippet,contentDetails&id=${Http.enc(trackId)}") }
        val item = v["items"].arr.firstOrNull() ?: return null
        val (title, artists) = TitleParser.parseYouTube(item["snippet"]["title"].str ?: "", item["snippet"]["channelTitle"].str)
        return Track(id = trackId, title = title, artists = artists, durationMs = Durations.parseIso8601(item["contentDetails"]["duration"].str))
    }

    override fun webSearchUrl(ctx: Env, query: String): String = "https://www.youtube.com/results?search_query=" + Http.enc(query)

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        val q = (track.artists.take(2) + track.title).joinToString(" ")
        val s = counted(ctx, QuotaMeter.COST_SEARCH) { api(ctx, "GET", "$API/search?part=snippet&type=video&videoCategoryId=10&maxResults=5&q=${Http.enc(q)}") }
        val ids = s["items"].arr.mapNotNull { it["id"]["videoId"].str }
        if (ids.isEmpty()) return emptyList()
        val v = counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/videos?part=snippet,contentDetails&id=${ids.joinToString(",")}") }
        return v["items"].arr.mapNotNull { item ->
            val id = item["id"].str ?: return@mapNotNull null
            val (title, artists) = TitleParser.parseYouTube(item["snippet"]["title"].str ?: "", item["snippet"]["channelTitle"].str)
            Track(id = id, title = title, artists = artists, durationMs = Durations.parseIso8601(item["contentDetails"]["duration"].str))
        }
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist {
        val j = counted(ctx, QuotaMeter.COST_WRITE) { api(ctx, "POST", "$API/playlists?part=snippet,status",
            jsonObj(
                "snippet" to mapOf("title" to name, "description" to description),
                "status" to mapOf("privacyStatus" to "private"),
            )) }
        return Playlist(j["id"].str ?: throw ProviderException("YouTube: playlist not created"), name, 0)
    }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (playlistId == MusicProvider.LIKED_ID) return rate(ctx, tracks, "like")
        for (t in tracks) {
            var attempt = 0
            while (true) {
                try {
                    counted(ctx, QuotaMeter.COST_WRITE) { api(ctx, "POST", "$API/playlistItems?part=snippet",
                        jsonObj("snippet" to mapOf(
                            "playlistId" to playlistId,
                            "resourceId" to mapOf("kind" to "youtube#video", "videoId" to t.id),
                        ))) }
                    break
                } catch (e: ProviderException) {
                    // A 409 "The operation was aborted" (or a 503) is YouTube refusing an insert that
                    // came too close to the one before on the same playlist: the same insert a moment
                    // later goes through.
                    if ((e.code != 409 && e.code != 503) || ++attempt > INSERT_RETRIES) throw e
                    ctx.log(id, "YouTube aborted adding a video (${e.code}): trying again, ${attempt} of $INSERT_RETRIES")
                    kotlinx.coroutines.delay(2_000L * attempt)
                }
            }
        }
    }

    private val INSERT_RETRIES = 3

    /**
     * The playlist's snippet as it is, with [title] in place of its own when given. An update
     * replaces the whole part it names: what it leaves out (the description) would be erased.
     */
    private suspend fun snippet(ctx: Env, playlistId: String, title: String? = null): Map<String, Any?> {
        val s = counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/playlists?part=snippet&id=$playlistId") }["items"][0]["snippet"]
        val current = s["title"].str ?: throw ProviderException("$displayName: playlist $playlistId not found")
        return mapOf("title" to (title ?: current), "description" to s["description"].str, "defaultLanguage" to s["defaultLanguage"].str, "tags" to s["tags"])
            .filterValues { !it.isNullish() }
    }

    private fun Any?.isNullish(): Boolean = this == null || this is kotlinx.serialization.json.JsonNull

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) {
        val snippet = snippet(ctx, playlistId, title = name)
        counted(ctx, QuotaMeter.COST_WRITE) {
            api(ctx, "PUT", "$API/playlists?part=snippet", jsonObj("id" to playlistId, "snippet" to snippet))
        }
    }

    /** An update must carry the title as well, so the current snippet is read first. */
    override val canSetVisibility: Boolean get() = true
    override suspend fun setPlaylistVisibility(ctx: Env, playlistId: String, public: Boolean) {
        val snippet = snippet(ctx, playlistId)
        counted(ctx, QuotaMeter.COST_WRITE) {
            api(ctx, "PUT", "$API/playlists?part=snippet,status", jsonObj(
                "id" to playlistId, "snippet" to snippet, "status" to mapOf("privacyStatus" to if (public) "public" else "private"),
            ))
        }
    }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) {
        counted(ctx, QuotaMeter.COST_WRITE) { api(ctx, "DELETE", "$API/playlists?id=$playlistId") }
    }

    /**
     * The account's liked videos (videos.list myRating=like, 1 unit a page of 50), keeping the music
     * ones: those in YouTube's Music category, which is where YouTube Music's own likes land.
     */
    private suspend fun likedTracks(ctx: Env): List<Track> {
        val out = ArrayList<Track>()
        var page: String? = null
        do {
            val j = counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/videos?part=snippet,contentDetails&myRating=like&maxResults=50" +
                (page?.let { "&pageToken=$it" } ?: "")) }
            out += likedFrom(j)
            page = j["nextPageToken"].str
        } while (page != null)
        return out
    }

    private suspend fun rate(ctx: Env, tracks: List<Track>, rating: String) {
        for (t in tracks) counted(ctx, QuotaMeter.COST_WRITE) { api(ctx, "POST", "$API/videos/rate?id=${Http.enc(t.id)}&rating=$rating") }
    }

    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (playlistId == MusicProvider.LIKED_ID) return rate(ctx, tracks, "none")
        for (t in tracks) {
            val itemId = t.itemId ?: continue
            counted(ctx, QuotaMeter.COST_WRITE) { api(ctx, "DELETE", "$API/playlistItems?id=$itemId") }
        }
    }

    companion object {
        const val SERVICE = "youtube"
        private const val API = "https://www.googleapis.com/youtube/v3"

        /** The music videos of a page of videos.list (category 10, "Music"), as tracks. */
        internal fun likedFrom(page: JsonElement): List<Track> = page["items"].arr.mapNotNull { v ->
            val id = v["id"].str ?: return@mapNotNull null
            if (v["snippet"]["categoryId"].str != "10") return@mapNotNull null
            val raw = v["snippet"]["title"].str ?: return@mapNotNull null
            val ms = Durations.parseIso8601(v["contentDetails"]["duration"].str)
            if (!looksLikeASong(raw, ms)) return@mapNotNull null
            val (title, artists) = TitleParser.parseYouTube(raw, v["snippet"]["channelTitle"].str)
            Track(id = id, title = title, artists = artists, durationMs = ms)
        }

        /** Words in a Music video's title that say it is about a song, not the song (an interview, a trailer). */
        private val NOT_A_SONG = Regex(
            """\b(interview|trailer|teaser|behind the scenes|making of|track by track|track inspiration|documentary|reaction|reacts?|podcast|livestream|live stream|unboxing|vlog|tutorial|lesson|how to play|guitar cover lesson|full album|full concert|album review|q&a)\b""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * A liked Music video that is a song: not one whose title says it is something else, and between
         * 30 seconds and 15 minutes (a short is a clip; a long one a concert, an album or a mix).
         */
        internal fun looksLikeASong(title: String, durationMs: Long): Boolean {
            if (NOT_A_SONG.containsMatchIn(title)) return false
            if (durationMs in 1 until 30_000) return false
            if (durationMs > 15 * 60_000L) return false
            return true
        }
    }
}
