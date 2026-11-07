package com.xlollx.songport.sync

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Public SoundCloud sets and likes, read the way soundcloud.com's own web player reads them: no
 * account and no key of ours, only the client id the site ships in its scripts. Not an official
 * interface: when SoundCloud changes its player this stops working until Trackhop is updated, and
 * it says so ([PlayerChanged]) instead of failing vaguely.
 *
 * A playlist id here is the path of the page on soundcloud.com ("user/sets/slug", with the secret
 * "/s-…" of an unlisted set, or "user/likes"): the link the user pasted, minus the host.
 */
object SoundCloudPublic {
    const val SITE = "https://soundcloud.com"
    const val API = "https://api-v2.soundcloud.com"
    private const val BATCH = 50
    private const val MAX_TRACKS = 10_000

    /** The set or the likes page is private, deleted, or never existed. */
    class NotPublic : Exception("not public")
    /** The site no longer works the way this reads it (no client id, or the id is refused). */
    class PlayerChanged(detail: String) : Exception(detail)

    private val SCRIPT = Regex("""<script[^>]+src="(https://a-v2\.sndcdn\.com/assets/[^"]+\.js)"""")
    private val CLIENT_ID = Regex("""client_id\s*:\s*"([A-Za-z0-9]{32})"""")
    private val SPLIT = Regex("""\s+[-–—]\s+""")

    /** The scripts of the site's page, last first: the client id sits in one of the last bundles. */
    fun scriptUrls(html: String): List<String> = SCRIPT.findAll(html).map { it.groupValues[1] }.distinct().toList().asReversed()

    fun clientIdIn(js: String): String? = CLIENT_ID.find(js)?.groupValues?.get(1)

    /** The page path of a soundcloud.com address ("user/sets/slug", "user/likes"), or null. */
    fun pathOf(url: String): String? = PlaylistLinks.parse(url)?.takeIf { it.providerId == SERVICE }?.playlistId

    const val SERVICE = "soundcloud"

    fun isLikes(path: String) = path.endsWith("/likes")

    /**
     * A track as the API lists it, or null for a stub (only the id: sets list the first tracks in
     * full and the rest by id) and for anything that is not a track.
     */
    fun parseTrack(j: JsonElement?, addedAt: Long = 0): Track? {
        if (j == null || (j["kind"].str ?: "track") != "track") return null
        val id = j["id"].long ?: return null
        val raw = j["title"].str ?: return null
        val meta = j["publisher_metadata"]
        val metaArtist = meta["artist"].str?.trim()?.takeIf { it.isNotEmpty() }
        val uploader = j["user"]["username"].str?.trim().orEmpty()
        // Without the label's metadata the uploader is often a channel, not the artist, and the
        // title carries "Artist - Title" as on YouTube.
        val parts = if (metaArtist == null) raw.split(SPLIT, limit = 2) else emptyList()
        val (title, artists) = when {
            metaArtist != null -> raw.trim() to listOf(metaArtist)
            parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank() ->
                TitleParser.clean(parts[1]) to TitleParser.splitArtists(parts[0])
            else -> TitleParser.clean(raw) to listOfNotNull(uploader.takeIf { it.isNotEmpty() })
        }
        val policy = j["policy"].str
        return Track(
            id = id.toString(),
            title = title.ifBlank { raw.trim() },
            artists = artists,
            album = meta["album_title"].str?.trim().orEmpty(),
            durationMs = j["full_duration"].long?.takeIf { it > 0 } ?: j["duration"].long ?: 0,
            isrc = meta["isrc"].str?.trim()?.takeIf { it.isNotEmpty() },
            uri = j["permalink_url"].str,
            explicit = meta["explicit"].bool,
            available = when (policy) { "BLOCK" -> false; null -> null; else -> true },
            year = Durations.year(j["release_date"].str ?: j["display_date"].str ?: j["created_at"].str),
            addedAt = addedAt,
        )
    }

    /** The ids a set lists, in order (stubs and full tracks alike). */
    fun trackIds(set: JsonElement): List<Long> = set["tracks"].arr.mapNotNull { it["id"].long }

    /** A set's tracks in its order: the ones it listed in full plus the stubs filled in from [fetched]. */
    fun assemble(set: JsonElement, fetched: Map<Long, Track>): List<Track> = set["tracks"].arr.mapNotNull { t ->
        parseTrack(t) ?: t["id"].long?.let { fetched[it] }
    }

    fun playlistOf(set: JsonElement, path: String): Playlist = Playlist(
        id = path,
        name = set["title"].str ?: path,
        trackCount = set["track_count"].int ?: set["tracks"].arr.size,
        ownedByMe = false,
        description = set["description"].str.orEmpty(),
        imageUrl = (set["artwork_url"].str ?: set["tracks"].arr.firstNotNullOfOrNull { it["artwork_url"].str })?.replace("-large.", "-t500x500."),
        isPublic = set["sharing"].str?.let { it == "public" },
    )

    /** One page of a user's likes: the liked tracks (sets and other likes skipped) and the next page's address. */
    fun likesPage(j: JsonElement): Pair<List<Track>, String?> {
        val tracks = j["collection"].arr.mapNotNull { like -> parseTrack(like["track"], Durations.parseInstant(like["created_at"].str)) }
        return tracks to j["next_href"].str?.takeIf { it.isNotBlank() }
    }

    // ---- Network ---------------------------------------------------------------------------

    @Volatile private var clientId: String? = null

    private suspend fun clientId(refresh: Boolean): String {
        if (!refresh) clientId?.let { return it }
        val home = Http.send("GET", "$SITE/", mapOf("User-Agent" to Http.DESKTOP_UA, "Accept" to "text/html"))
        if (!home.ok) throw PlayerChanged("soundcloud.com HTTP ${home.code}")
        for (url in scriptUrls(home.body)) {
            val js = Http.send("GET", url, mapOf("User-Agent" to Http.DESKTOP_UA))
            if (!js.ok) continue
            clientIdIn(js.body)?.let { clientId = it; return it }
        }
        throw PlayerChanged("no client id in the web player's scripts")
    }

    /** [url] without its client_id parameter, wherever it stands in the query. */
    fun withoutClientId(url: String): String =
        url.toHttpUrlOrNull()?.newBuilder()?.removeAllQueryParameters("client_id")?.build()?.toString() ?: url

    private fun withClientId(url: String, id: String) = url + (if ('?' in url) "&" else "?") + "client_id=$id"

    /** GET on the API with the client id; a refused id is fetched again once before giving up. */
    private suspend fun api(url: String): JsonElement {
        var refreshed = false
        while (true) {
            val id = clientId(refresh = false)
            val resp = Http.send("GET", withClientId(url, id), mapOf("Accept" to "application/json", "User-Agent" to Http.DESKTOP_UA))
            when {
                resp.ok -> return parseJson(resp.body)
                resp.code == 404 -> throw NotPublic()
                (resp.code == 401 || resp.code == 403) && !refreshed -> { clientId(refresh = true); refreshed = true }
                resp.code == 401 || resp.code == 403 -> throw PlayerChanged("api-v2 HTTP ${resp.code}")
                else -> throw com.xlollx.songport.model.ProviderException("SoundCloud HTTP ${resp.code}", code = resp.code)
            }
        }
    }

    private suspend fun resolve(pageUrl: String): JsonElement = api("$API/resolve?url=${Http.enc(pageUrl)}")

    /** The set behind [path], as resolve returns it (a playlist, an album, an EP: all "playlist"). */
    private suspend fun set(path: String): JsonElement {
        val j = resolve("$SITE/$path")
        if (j["kind"].str != "playlist") throw NotPublic()
        return j
    }

    private suspend fun user(path: String): JsonElement {
        val j = resolve("$SITE/${path.substringBefore('/')}")
        if (j["kind"].str != "user") throw NotPublic()
        return j
    }

    suspend fun info(path: String): Playlist {
        if (isLikes(path)) {
            val u = user(path)
            val name = u["username"].str ?: path.substringBefore('/')
            return Playlist(path, "$name · Likes", u["likes_count"].int ?: -1, ownedByMe = false, imageUrl = u["avatar_url"].str, isPublic = true)
        }
        return playlistOf(set(path), path)
    }

    suspend fun tracks(path: String): List<Track> {
        if (isLikes(path)) {
            val uid = user(path)["id"].long ?: throw NotPublic()
            val out = ArrayList<Track>()
            var next: String? = "$API/users/$uid/likes?limit=200&linked_partitioning=1"
            while (next != null && out.size < MAX_TRACKS) {
                val (page, more) = likesPage(api(withoutClientId(next)))
                out += page
                next = more
            }
            return out.distinctBy { it.id }
        }
        val s = set(path)
        val missing = s["tracks"].arr.filter { parseTrack(it) == null }.mapNotNull { it["id"].long }
        val fetched = HashMap<Long, Track>()
        for (chunk in missing.chunked(BATCH)) {
            api("$API/tracks?ids=${chunk.joinToString(",")}").arr.forEach { t -> parseTrack(t)?.let { fetched[it.id.toLong()] = it } }
        }
        return assemble(s, fetched)
    }

    /** What changes when the set changes: its modification date and size. Likes have none. */
    suspend fun version(path: String): String? {
        if (isLikes(path)) return null
        val s = set(path)
        return listOfNotNull(s["last_modified"].str, s["track_count"].int?.toString()).joinToString("|").ifEmpty { null }
    }

    /**
     * The path of a pasted SoundCloud link, short share links (on.soundcloud.com, snd.sc) followed
     * to where they land; null when the text is no link to a set or a likes page.
     */
    suspend fun pathFromLink(text: String): String? {
        pathOf(text)?.let { return it }
        val short = Regex("""https?://(?:on\.soundcloud\.com|snd\.sc)/[^\s<>"']+""").find(text)?.value ?: return null
        val landing = runCatching { Http.follow(short) }.getOrNull() ?: return null
        return pathOf(landing.url)
    }
}
