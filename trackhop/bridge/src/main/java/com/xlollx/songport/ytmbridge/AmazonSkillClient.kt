package com.xlollx.songport.ytmbridge

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import com.xlollx.songport.ytmbridge.BridgeNet.withHook
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * The older "skyfire" skill API of the Amazon Music web player (`<region>.web.skill.music.a2z.com/api/<method>`),
 * for the accounts whose player still uses it (issue #35).
 *
 * Most players moved to GraphQL in October 2026 ([AmazonClient]); some "Rest of Europe" accounts
 * (a Dutch one, marketplace NL, on music.amazon.com) were left on the skill API, and GraphQL refuses
 * them (AUTH_INVALID_CUSTOMER_ID). [AmazonRoute] decides which of the two an account gets.
 *
 * Restored from the client the connector had before 1.0.49, and checked against a capture of that
 * player: every call is a POST of a JSON object whose fields are all strings, sorted by name, the
 * method's arguments next to `userHash` (`{"level":"HD_MEMBER"}`, the player's own) and `headers`
 * (the `x-amzn-*` set, a JSON string: access token, CSRF, device and session). Those headers come
 * from the player's own calls, mirrored by [AmazonBridge]. Answers are UI templates; the URLs they
 * hand back (next page, "add to this playlist") name another host (zaz.mesk.skill...) and their
 * arguments in the query string: the player sends them to its own skill host with the query as the
 * body, and so does this client.
 */
class AmazonSkillClient(private val ctx: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .withHook().build()

    private fun domain(): String = AmazonSession.domain(ctx) ?: throw BridgeException("not connected")

    /** The host the player sends its skill calls to (eu. for the Dutch account), else the domain's region. */
    private fun host(domain: String): String = AmazonSession.skillHost(ctx)
        ?: "${(AmazonClient.DOMAINS[domain]?.region ?: "EU").lowercase()}.web.skill.music.a2z.com"

    private fun userHash(): String = AmazonSession.skillUserHash(ctx) ?: DEFAULT_USER_HASH

    /**
     * The `x-amzn-*` set for one call: the player's own, mirrored from its traffic, with the values
     * that change on every request refreshed. When the stored set is too old (the access token in it
     * lasts about an hour) the player is run again offscreen; a set built from the page configuration
     * is the last resort, as before 1.0.49.
     */
    private fun apiHeaders(domain: String, pageUrl: String, renew: Boolean): Map<String, String> {
        val own = if (renew) null else storedHeaders()
        val headers = own ?: run {
            runCatching { AmazonBridge.skillPlayer(ctx, domain) }
                .onFailure { BridgeNet.log("amazon", "skill API: the player did not run (${it.message?.take(120)})") }
            storedHeaders() ?: fromConfig(domain, pageUrl)
        }
        return headers + mapOf(
            "x-amzn-timestamp" to System.currentTimeMillis().toString(),
            "x-amzn-request-id" to java.util.UUID.randomUUID().toString().replace("-", "").take(13),
            "x-amzn-page-url" to pageUrl,
        ) + listOf("x-amzn-music-domain", "x-amzn-referer").filter { headers[it].isNullOrBlank() }.associateWith { domain }
    }

    private fun storedHeaders(): Map<String, String>? {
        if (System.currentTimeMillis() - AmazonSession.skillHeadersAt(ctx) > TTL_MS) return null
        return headersOf(AmazonSession.skillHeaders(ctx) ?: return null)
    }

    /** The set the player builds, from its configuration: only when the configuration carries a token. */
    private fun fromConfig(domain: String, pageUrl: String): Map<String, String> {
        val c = AmazonClient(ctx).config(domain)
        if (c.accessToken.isBlank()) throw BridgeException(
            "Amazon Music: the player's credentials were not seen (its skill API calls carry them). " +
                "Accounts, Amazon Music: connect again and let the player load, then try again.",
        )
        val lang = AmazonClient.DOMAINS[domain]?.language ?: "en_US"
        return linkedMapOf(
            "x-amzn-authentication" to jsonObj("interface" to "ClientAuthenticationInterface.v1_0.ClientTokenElement", "accessToken" to c.accessToken).toString(),
            "x-amzn-device-model" to "WEBPLAYER",
            "x-amzn-device-width" to "1920",
            "x-amzn-device-family" to "WebPlayer",
            "x-amzn-device-id" to c.deviceId,
            "x-amzn-user-agent" to userAgent(),
            "x-amzn-session-id" to c.sessionId,
            "x-amzn-device-height" to "1080",
            "x-amzn-device-language" to lang,
            "x-amzn-currency-of-preference" to (CURRENCIES[c.musicTerritory ?: lang.substringAfter('_')] ?: "EUR"),
            "x-amzn-os-version" to "1.0",
            "x-amzn-application-version" to (c.raw["version"].str ?: c.raw["clientVersion"].str ?: c.raw["serverInfo"]["version"].str).orEmpty(),
            "x-amzn-device-time-zone" to TimeZone.getDefault().id,
            "x-amzn-csrf" to jsonObj("interface" to "CSRFInterface.v1_0.CSRFHeaderElement", "token" to c.csrfToken, "timestamp" to c.csrfTs, "rndNonce" to c.csrfRnd).toString(),
            "x-amzn-music-domain" to domain,
            "x-amzn-referer" to domain,
            "x-amzn-page-url" to pageUrl,
        )
    }

    private fun userAgent(): String = AmazonSession.skillUserAgent(ctx) ?: AmazonBridge.storedUserAgent(ctx) ?: BridgeUa.desktop(ctx)

    /** One call to `/api/<method>` on the player's skill host; [params] are the method's arguments. */
    fun call(method: String, params: Map<String, String>, pageUrl: String = ""): JsonElement {
        val domain = domain()
        return send("https://${host(domain)}/api/$method", params, domain, pageUrl)
    }

    /** A URL an answer handed back, sent the way the player sends it: to its own host, the query as the body. */
    private fun follow(url: String, pageUrl: String): JsonElement {
        val domain = domain()
        val (target, params) = request(url, host(domain)) ?: throw BridgeException("Amazon Music: unexpected link in an answer")
        return send(target, params, domain, pageUrl)
    }

    /**
     * Sends one call; a refusal (401/403) runs the player again once for fresh credentials, as the
     * stored ones can belong to a session Amazon has since renewed.
     */
    private fun send(url: String, params: Map<String, String>, domain: String, pageUrl: String): JsonElement {
        val method = url.substringAfterLast('/')
        var renew = false
        while (true) {
            val headers = apiHeaders(domain, pageUrl, renew)
            val req = Request.Builder()
                .url(url)
                .post(body(params, params["userHash"] ?: userHash(), headers).toRequestBody(TEXT_TYPE))
                .header("Origin", "https://$domain")
                .header("Referer", "https://$domain/")
                // The same user agent the player had when it built those headers.
                .header("User-Agent", userAgent())
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            val (code, text) = exchange(req)
            if (code == 401 || code == 403) {
                BridgeNet.log("amazon", "skill API: $code on $method (sent ${headers.keys.sorted().joinToString(",")}); " +
                    if (renew) "again with the player's fresh credentials" else "running the player for fresh credentials")
                if (!renew) { renew = true; continue }
                throw BridgeException("Amazon Music refused the session ($code on $method). Accounts, Amazon Music: disconnect and connect again.")
            }
            if (code !in 200..299) throw BridgeException("Amazon Music $code on $method: ${text.take(160)}")
            return runCatching { parseJson(text) }.getOrNull() ?: throw BridgeException("Amazon Music $code on $method: ${text.take(160)}")
        }
    }

    /** One HTTP exchange, retried on transport errors (a phone switching networks). */
    private fun exchange(req: Request): Pair<Int, String> {
        var attempt = 0
        while (true) {
            try {
                http.newCall(req).execute().use { resp -> return resp.code to (resp.body?.string() ?: "") }
            } catch (e: java.io.IOException) {
                if (attempt >= 2) throw BridgeException("Amazon Music unreachable: ${e.message ?: e.javaClass.simpleName}")
                Thread.sleep(if (attempt == 0) 2_000 else 5_000)
                attempt++
            }
        }
    }

    // ------------------------------------------------------------------ library

    /** The user's own playlists (library), following the "more" links of a long list. */
    fun libraryPlaylists(): List<PlaylistDto> {
        val page = "https://${domain()}/my/playlists"
        var j = call("showLibraryPlaylists", emptyMap(), page)
        val out = LinkedHashMap<String, PlaylistDto>()
        var pages = 0
        while (true) {
            val before = out.size
            parsePlaylists(j).forEach { out.putIfAbsent(it.id, it) }
            val next = nextPages(j).firstOrNull() ?: break
            if (out.size == before && pages > 0 || ++pages > 50) break
            j = follow(next, page)
        }
        titles = out.values.associate { it.id to it.name }
        return out.values.toList()
    }

    /** Tracks of a library playlist; an entry's `setVideoId` is its trackEntryId, what removing takes. */
    fun playlistTracks(id: String): List<TrackDto> {
        val page = "https://${domain()}/my/playlists/$id"
        var j = call("showLibraryPlaylist", mapOf("id" to id), page)
        val out = LinkedHashMap<String, TrackDto>()
        var pages = 0
        while (true) {
            val before = out.size
            for (t in parsePlaylistTracks(j)) out.putIfAbsent(t.setVideoId ?: t.id, t)
            val next = nextPages(j).firstOrNull() ?: break
            if (out.size == before && pages > 0 || ++pages > 200) break
            j = follow(next, page)
        }
        return out.values.toList()
    }

    /**
     * Catalogue search, songs only, as the player searches: showSearch with the query as the
     * suggested keyword. searchCatalogTracks, the call of earlier players, only when that finds none.
     */
    fun searchTracks(query: String): List<TrackDto> {
        val page = "https://${domain()}/search/${java.net.URLEncoder.encode(query, "UTF-8")}"
        var found = parseTracks(call("showSearch", searchParams(query), page))
        if (found.isEmpty()) found = runCatching { parseTracks(call("searchCatalogTracks", mapOf("keyword" to query), "$page/songs")) }.getOrDefault(emptyList())
        // Amazon lists the same recording once per edition: one row per title and artist.
        val seen = HashSet<String>()
        return found.filter { seen.add(it.title.lowercase() + "\u0000" + it.artists.joinToString(",").lowercase()) }.take(25)
    }

    /**
     * The "Create Playlist" dialog's Save: the playlist's client information with the name. The
     * answer is a chain of UI methods naming the new playlist; when it names none, the library is
     * read again and the new playlist is the one of that name that was not there before.
     */
    fun createPlaylist(name: String): PlaylistDto {
        val existing = runCatching { libraryPlaylists().map { it.id }.toSet() }.getOrNull()
        val j = call("createPlaylist", mapOf("playlistInfo" to playlistInfo(name, "/my/playlists")), "https://${domain()}/my/playlists")
        topLevelError(j)?.let { throw BridgeException("Amazon Music did not create the playlist: $it") }
        createdId(j)?.let { return PlaylistDto(it, name, 0) }
        val again = libraryPlaylists().firstOrNull { it.name == name && (existing == null || it.id !in existing) }
            ?: throw BridgeException("Amazon Music: playlist not created")
        return PlaylistDto(again.id, name, 0)
    }

    /**
     * Adds one track the way the player does: its "Add to playlist" dialog first, whose entry for the
     * playlist carries the exact call (with the playlist's current `version`), then that call. True
     * when added, false when Amazon says the playlist already holds the track.
     */
    fun addTrack(playlistId: String, trackId: String, trackTitle: String): Boolean {
        val page = "https://${domain()}/my/playlists/$playlistId"
        val dialog = runCatching { call("showAddTrackToPlaylistDialog", mapOf("trackId" to trackId, "trackTitle" to trackTitle), page) }.getOrNull()
        val link = dialog?.let { addLink(it, playlistId) }
        val j = if (link != null) follow(link, page) else {
            // Not in the dialog (a long library): the call built here, with the version last seen
            // for this playlist, else 1 as the players before 1.0.49 sent it.
            logOnce("add-built", "skill API: the add dialog does not list playlist ${playlistId.takeLast(8)}; adding with a built call")
            call("addTrackToPlaylist", addParams(playlistId, titles[playlistId] ?: "", trackId, trackTitle, versions[playlistId] ?: "1"), page)
        }
        link?.toHttpUrlOrNull()?.queryParameter("version")?.let { versions[playlistId] = it }
        return when (val r = addResult(j)) {
            ADDED -> true
            DUPLICATE -> false
            null -> {
                logOnce("add-unknown", "skill API: addTrackToPlaylist answered ${methodNames(j).joinToString(",").take(200)}: counted as added")
                true
            }
            else -> throw BridgeException("Amazon Music did not add $trackId: $r")
        }
    }

    /**
     * Removes entries by trackEntryId and says which of them the playlist still lists afterwards.
     * An entry without one (a GraphQL cursor "index:uuid", an old cache) is looked up in the playlist.
     */
    fun removeEntries(playlistId: String, ids: List<String>, entryIds: List<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val usable = { e: String? -> e?.takeIf { it.length > 20 && !it.contains(':') } }
        val listed = if (ids.indices.any { usable(entryIds.getOrNull(it)) == null }) playlistTracks(playlistId) else emptyList()
        val free = listed.groupBy { it.id }.mapValues { (_, v) -> v.mapNotNull { it.setVideoId }.toMutableList() }
        val entries = ids.mapIndexed { i, id -> id to (usable(entryIds.getOrNull(i)) ?: free[id]?.removeFirstOrNull()) }
        for ((id, entry) in entries) {
            if (entry == null) continue
            val j = call("removeTrackFromPlaylist", mapOf("playlistId" to playlistId, "trackEntryId" to entry, "trackId" to id), "https://${domain()}/my/playlists/$playlistId")
            topLevelError(j)?.let { BridgeNet.log("amazon", "skill API: removing an entry of ${playlistId.takeLast(8)} answered: $it") }
            Thread.sleep(removePauseMs)
        }
        val after = playlistTracks(playlistId)
        val listedEntries = after.mapNotNull { it.setVideoId }.toSet()
        // An entry removed and still listed, or one never found that the playlist still holds.
        val kept = entries.filter { (id, e) -> if (e == null) after.any { it.id == id } else e in listedEntries }.map { it.first }.toSet()
        if (kept.isNotEmpty()) BridgeNet.log("amazon", "skill API: playlist ${playlistId.takeLast(8)}: ${kept.size} of ${ids.size} entries still listed after removing them")
        return kept
    }

    /** The rename dialog's Save: the playlist's client information with the new name. */
    fun renamePlaylist(playlistId: String, name: String) {
        val j = call("renamePlaylist", mapOf("id" to playlistId, "playlistInfo" to playlistInfo(name, "/my/playlists/$playlistId")), "https://${domain()}/my/playlists/$playlistId")
        topLevelError(j)?.let { throw BridgeException("Amazon Music did not rename the playlist: $it") }
    }

    /** "Make public" / "Make private" (the capture's updatePlaylistVisibility link). */
    fun setVisibility(playlistId: String, public: Boolean) {
        val j = call(
            "updatePlaylistVisibility",
            mapOf("playlistId" to playlistId, "newVisibility" to if (public) "PUBLIC" else "PRIVATE", "refresh" to "true"),
            "https://${domain()}/my/playlists/$playlistId",
        )
        topLevelError(j)?.let { throw BridgeException("Amazon Music did not change the playlist's visibility: $it") }
    }

    /** The delete dialog's confirmation. */
    fun removePlaylist(playlistId: String) {
        val j = call("removePlaylist", mapOf("id" to playlistId), "https://${domain()}/my/playlists/$playlistId")
        topLevelError(j)?.let { throw BridgeException("Amazon Music did not delete the playlist: $it") }
    }

    private fun logOnce(key: String, message: String) {
        val now = System.currentTimeMillis()
        if (now - (logged[key] ?: 0L) < 600_000) return
        logged[key] = now
        BridgeNet.log("amazon", message)
    }

    companion object {
        /** The player's skill hosts: one per Amazon region (the Dutch account's player uses eu.). */
        val SKILL_HOST = Regex("""^(na|eu|fe)\.web\.skill\.music\.a2z\.com$""")
        fun isSkillHost(host: String?): Boolean = host != null && SKILL_HOST.matches(host.lowercase())

        /** Any skill host, also the ones answers name in their links (zaz.mesk..., eu.mesk...). */
        private fun isAnySkillHost(host: String): Boolean = host.lowercase().endsWith(".skill.music.a2z.com")

        /** Sent when the player's own was not seen: the level of the capture's account. */
        const val DEFAULT_USER_HASH = """{"level":"HD_MEMBER"}"""
        private const val SEARCH_KEYWORD = "Web.TemplatesInterface.v1_0.Touch.SearchTemplateInterface.SearchKeywordClientInformation"
        private const val PLAYLIST_INFO = "Web.TemplatesInterface.v1_0.Touch.PlaylistTemplateInterface.PlaylistClientInformation"
        private val TEXT_TYPE = "text/plain;charset=UTF-8".toMediaType()
        /** The access token in the player's headers lasts about an hour; well before that it is taken again. */
        private const val TTL_MS = 30 * 60_000L
        private const val ADDED = "added"
        private const val DUPLICATE = "duplicate"
        @Volatile var removePauseMs = 150L
        /** Names of the library's playlists from the last listing, for an add the dialog does not cover. */
        @Volatile private var titles: Map<String, String> = emptyMap()
        /** The `version` the add dialog last gave each playlist. */
        private val versions = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val logged = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private val CURRENCIES = mapOf("US" to "USD", "GB" to "GBP", "SE" to "SEK", "PL" to "PLN", "CA" to "CAD", "MX" to "MXN", "BR" to "BRL",
            "IN" to "INR", "JP" to "JPY", "AU" to "AUD", "SA" to "SAR", "AE" to "AED", "TR" to "TRY")

        /**
         * Calls of the player that show it runs on the skill API: library, catalogue and playlist
         * calls. Not showHome (the Dutch player sends that one to na. while everything else goes to
         * eu.), nor metrics and playback reports, which say nothing about where the library lives.
         */
        private val EVIDENCE = Regex("""^(show(Library\w*|Search|Find|Catalog\w*|HomeBrowse|AddTrackToPlaylistDialog)|addTrackToPlaylist|removeTrackFromPlaylist|createPlaylist|renamePlaylist|removePlaylist|searchCatalog\w*|play(Library|Catalog)\w*|restorePlayback)$""")
        fun isEvidence(method: String): Boolean = EVIDENCE.matches(method)

        /** A mirrored `headers` set: the player's x-amzn-* values, kept only when they carry its credentials. */
        fun headersOf(json: String): Map<String, String>? {
            val j = runCatching { parseJson(json) }.getOrNull() as? JsonObject ?: return null
            val map = j.mapNotNull { (k, v) -> v.str?.let { k.lowercase() to it } }.toMap()
            val token = runCatching { parseJson(map["x-amzn-authentication"] ?: "{}")["accessToken"].str }.getOrNull()
            return if (token.isNullOrBlank() && map["x-amzn-csrf"].isNullOrBlank()) null else map
        }

        /** The body of one call: every field a string, sorted by name as the player sends them. */
        fun body(params: Map<String, String>, userHash: String, headers: Map<String, String>?): String {
            val all = java.util.TreeMap<String, String>(params)
            all["userHash"] = userHash
            if (headers != null) all["headers"] = JsonObject(headers.mapValues { JsonPrimitive(it.value) }).toString()
            return JsonObject(all.mapValues { JsonPrimitive(it.value) }).toString()
        }

        /**
         * A link an answer handed back, as the player sends it: to [host] (the player's own skill host,
         * whatever host the link names) at the same path, its query string as the call's arguments.
         * Null when it is not a skill API link.
         */
        fun request(url: String, host: String): Pair<String, Map<String, String>>? {
            val u = url.toHttpUrlOrNull() ?: return null
            if (!isAnySkillHost(u.host) || !u.encodedPath.startsWith("/api/")) return null
            val params = LinkedHashMap<String, String>()
            for (name in u.queryParameterNames) params[name] = u.queryParameter(name) ?: ""
            return "https://$host${u.encodedPath}" to params
        }

        /** The arguments of showSearch, as the player sent them for a query. */
        fun searchParams(query: String): Map<String, String> = mapOf(
            "keyword" to jsonObj("interface" to SEARCH_KEYWORD, "keyword" to "").toString(),
            "suggestedKeyword" to query,
        )

        /** addTrackToPlaylist's arguments, as the player sends them (the capture's exact set). */
        fun addParams(playlistId: String, playlistTitle: String, trackId: String, trackTitle: String, version: String): Map<String, String> = mapOf(
            "isTrackInLibrary" to "false",
            "playlistId" to playlistId,
            "playlistTitle" to playlistTitle,
            "rejectDuplicate" to "true",
            "shouldReplaceAddedTrack" to "false",
            "trackId" to trackId,
            "trackTitle" to trackTitle,
            "version" to version,
        )

        private fun playlistInfo(name: String, path: String): String =
            jsonObj("interface" to PLAYLIST_INFO, "name" to name, "path" to path).toString()

        // -------------------------------------------------------------- parsing

        /**
         * Actions, menus and states of an element: the templates inside them are what the player
         * would open on a tap (an album page, a dialog), not this answer's content.
         */
        private val ELSEWHERE = setOf("onItemSelected", "contextMenu", "onError", "onSuccess", "onCreated", "onBound", "onViewed",
            "onFirstViewed", "onInteraction", "onLeave", "onBackPressed", "onRefresh", "states", "before", "after")

        /** Every value under [key] in the answer's own content, leaving out [ELSEWHERE]. */
        private fun content(j: JsonElement?, key: String): List<JsonElement> {
            val out = ArrayList<JsonElement>()
            fun walk(e: JsonElement?) {
                when (e) {
                    is JsonObject -> e.forEach { (k, v) -> if (k == key) out += v; if (k !in ELSEWHERE) walk(v) }
                    is JsonArray -> e.forEach { walk(it) }
                    else -> {}
                }
            }
            walk(j)
            return out
        }

        private fun items(j: JsonElement?): List<JsonElement> = content(j, "items").flatMap { (it as? JsonArray) ?: emptyList() }

        /** Every URL in an answer, in document order. */
        private fun urls(j: JsonElement?): List<String> = j.findAll("url").mapNotNull { it.str }

        /** The "more" links of the answer's long lists (onEndOfWidget), skill API calls only. */
        fun nextPages(j: JsonElement?): List<String> =
            content(j, "onEndOfWidget").flatMap { urls(it) }.filter { it.contains("/api/") }.distinct()

        /** The library's own playlists: links to /my/playlists/<id>; their name may sit in the storage default. */
        fun parsePlaylists(j: JsonElement?): List<PlaylistDto> {
            val out = LinkedHashMap<String, PlaylistDto>()
            for (item in items(j)) {
                val link = item["primaryLink"]["deeplink"].str ?: continue
                val id = link.substringAfter("/my/playlists/", "").substringBefore('?').substringBefore('/')
                if (id.isEmpty()) continue
                val name = item["primaryText"]["text"].str?.takeIf { it.isNotBlank() }
                    ?: item["primaryText"]["observer"]["defaultValue"]["text"].str?.takeIf { it.isNotBlank() }
                    ?: item["primaryText"].str?.takeIf { it.isNotBlank() } ?: id
                val count = (item["secondaryText"].str ?: item["secondaryText"]["text"].str)
                    ?.let { Regex("""^\s*(\d+)\s""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: -1
                out.putIfAbsent(id, PlaylistDto(id, name, count))
            }
            return out.values.toList()
        }

        /**
         * A playlist's rows. Each entry carries its trackEntryId as `id`: only the rows that do are the
         * playlist's own (not a recommendation shelf below it), unless none does at all.
         */
        fun parsePlaylistTracks(j: JsonElement?): List<TrackDto> {
            val all = parseTracks(j)
            return all.filter { it.setVideoId != null }.ifEmpty { all }
        }

        /**
         * Track rows of any template: playlist rows carry `primaryText` as a string, `secondaryText1..3`
         * (artist, album, mm:ss), the entry `id` and a deeplink with `trackAsin=`; search rows carry
         * `primaryText.text`, `secondaryText` and a storage key "album:track". Artists, albums and
         * playlists (no track in their link or key) are left out.
         */
        fun parseTracks(j: JsonElement?): List<TrackDto> {
            val out = ArrayList<TrackDto>()
            for (item in items(j)) {
                val link = item["primaryLink"]["deeplink"].str ?: ""
                val fromLink = Regex("""[?&]trackAsin=([A-Z0-9]{10})""").find(link)?.groupValues?.get(1)
                    ?: link.substringAfter("/tracks/", "").substringBefore('/').substringBefore('?').takeIf { it.isNotEmpty() }
                val storageKey = item["iconButton"]["observer"]["storageKey"].str
                val fromKey = storageKey?.substringAfter(':', "")?.takeIf { it.length == 10 }
                val id = fromLink ?: fromKey ?: continue
                val title = item["primaryText"].str ?: item["primaryText"]["text"].str ?: continue
                val artist = item["secondaryText1"].str ?: item["secondaryText"].str ?: item["secondaryText"]["text"].str ?: ""
                val album = item["secondaryText2"].str ?: item["imageAltText"].str ?: ""
                val entry = item["id"].str?.takeIf { it.length > 20 }
                out += TrackDto(id, title, if (artist.isBlank()) emptyList() else listOf(artist), album, parseDuration(item["secondaryText3"].str), entry)
            }
            return out
        }

        private fun parseDuration(text: String?): Long {
            if (text == null) return 0
            val parts = text.trim().split(':').map { it.toLongOrNull() ?: return 0 }
            if (parts.isEmpty() || parts.size > 3) return 0
            return parts.fold(0L) { acc, p -> acc * 60 + p } * 1000
        }

        /** The interfaces of the answer's top-level methods (names only), for the diagnostics log. */
        private fun methodNames(j: JsonElement?): List<String> = j["methods"].arr.mapNotNull { it["interface"].str?.substringAfterLast('.') }

        /**
         * The message of an error the answer shows at top level ("We're Sorry" template), or null.
         * Error templates nested in a method's onError are only what the player would show if a later
         * call failed, and do not count.
         */
        fun topLevelError(j: JsonElement?): String? = j["methods"].arr.firstNotNullOfOrNull { m ->
            val t = m["template"]
            if (m["interface"].str?.endsWith("CreateAndBindTemplateMethod") == true && t["interface"].str?.endsWith("MessageTemplate") == true) {
                listOfNotNull(t["header"].str, t["message"].str).joinToString(": ").ifBlank { "error" }
            } else null
        }

        /** The new playlist's id in createPlaylist's answer: a playlistId argument or a /my/playlists/ link. */
        fun createdId(j: JsonElement?): String? {
            val text = j.toString()
            return Regex("""playlistId=([0-9a-fA-F-]{20,})""").find(text)?.groupValues?.get(1)
                ?: Regex("""/my/playlists/([0-9a-fA-F-]{20,})""").find(text)?.groupValues?.get(1)
        }

        /** In the "Add to playlist" dialog, the link that adds the track to [playlistId]. */
        fun addLink(j: JsonElement?, playlistId: String): String? = urls(j).firstOrNull { u ->
            u.contains("/api/addTrackToPlaylist") && u.toHttpUrlOrNull()?.queryParameter("playlistId") == playlistId
        }

        /**
         * How addTrackToPlaylist went: [ADDED] (the "added" ribbon), [DUPLICATE] (the answer offers to
         * add it anyway, rejectDuplicate=false, or names a duplicate), an error message, or null when
         * the answer says none of these.
         */
        fun addResult(j: JsonElement?): String? {
            val text = j.toString()
            if (text.contains("TrackAddedToPlaylist")) return ADDED
            if (text.contains("rejectDuplicate=false") || text.contains("duplicate", ignoreCase = true)) return DUPLICATE
            return topLevelError(j)
        }
    }
}

/**
 * Which API an account's player uses: GraphQL (most accounts, [AmazonClient]) or the skill API
 * ([AmazonSkillClient], some "Rest of Europe" accounts). The choice is kept in [AmazonSession].
 *
 * The skill API is chosen when the player itself was seen using it for its library or catalogue and
 * no GraphQL call came from the same page, or when GraphQL refused the account as AUTH_INVALID_CUSTOMER_ID
 * and the player's skill credentials are at hand (then kept only once the skill API has answered). The
 * player seen on GraphQL again brings the account back. An account never seen on the skill API is
 * on GraphQL exactly as before.
 */
object AmazonRoute {
    const val SKILL = "skill"
    const val GQL = "gql"

    /** The route after one of the player's own calls ([skill]: to a skill host; else GraphQL). */
    fun afterPlayerCall(current: String?, skill: Boolean, method: String, pageSawGql: Boolean): String? = when {
        !skill -> if (current == SKILL) GQL else current
        !pageSawGql && AmazonSkillClient.isEvidence(method) -> SKILL
        else -> current
    }

    /** After GraphQL failed: try the skill API only for AUTH_INVALID_CUSTOMER_ID, with the player's skill credentials known. */
    fun fallbackToSkill(invalidCustomer: Boolean, skillKnown: Boolean): Boolean = invalidCustomer && skillKnown

    /** Runs one operation on the account's API (see the object's comment). */
    fun <T> run(ctx: Context, gql: (AmazonClient) -> T, skill: (AmazonSkillClient) -> T): T {
        if (AmazonSession.api(ctx) == SKILL) {
            try {
                return skill(AmazonSkillClient(ctx))
            } catch (e: BridgeException) {
                // Running the player again showed it on GraphQL: the account moved, so does the call.
                if (AmazonSession.api(ctx) != SKILL) return gql(AmazonClient(ctx))
                // A player once seen on GraphQL too: GraphQL is tried, and kept if it answers.
                if (!AmazonSession.gqlSeen(ctx)) throw e
                val r = runCatching { gql(AmazonClient(ctx)) }
                r.onSuccess {
                    AmazonSession.setApi(ctx, GQL)
                    return it
                }
                throw e
            }
        }
        val since = System.currentTimeMillis()
        try {
            return gql(AmazonClient(ctx))
        } catch (e: BridgeException) {
            // Renewing the session ran the player, which may have shown it on the skill API only.
            val seen = AmazonSession.api(ctx) == SKILL
            if (!seen && !fallbackToSkill(AmazonClient.invalidCustomerSince(since), AmazonSession.skillKnown(ctx))) throw e
            val r = runCatching { skill(AmazonSkillClient(ctx)) }
            r.onSuccess {
                if (!seen) AmazonSession.setApi(ctx, SKILL)
                return it
            }
            BridgeNet.log("amazon", "GraphQL refused the account and the skill API failed too: ${r.exceptionOrNull()?.message?.take(160)}")
            throw e
        }
    }
}
