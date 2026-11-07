package com.xlollx.songport.ai

import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.HttpResponse
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.ListenBrainzProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.CancellationException
import kotlin.random.Random

/**
 * Where [ArtistMix] gets its data: official public APIs, MetaBrainz's first and Deezer's catalogue
 * as a fallback, none with a key, so the keyless generator works on every build (full, Play,
 * F-Droid, server, Desktop). Checked in November 2026:
 *
 * - MusicBrainz artist search, `https://musicbrainz.org/ws/2/artist?query=…&fmt=json`: a typed name
 *   to the artist's MBID. MusicBrainz asks for at most one request per second per client and a
 *   User-Agent that names the application and a contact ([userAgent]); calls here are paced 1.1 s apart.
 * - ListenBrainz Labs similar artists, `https://labs.api.listenbrainz.org/similar-artists/json`
 *   with `artist_mbids` (one MBID: several answer 400) and [ALGORITHM], the one ListenBrainz's own
 *   artist pages use: up to 100 artists that listeners play in the same sessions, with a score. No
 *   published limit and no rate headers; paced one call a second, like MusicBrainz.
 * - ListenBrainz popularity, `https://api.listenbrainz.org/1/popularity/top-recordings-for-artist/<mbid>`:
 *   an artist's recordings by listen count. Paced by window: X-RateLimit-Limit (30 calls) and
 *   X-RateLimit-Reset-In (seconds); the last call of a window waits for the next one. Since 2026 an
 *   anonymous call gets only what ListenBrainz's cache already holds (X-Cache-Status: HIT); a miss
 *   is refused with 401 ("you need to provide an Auth token"). The token of a connected
 *   ListenBrainz account is sent when there is one, and then every artist answers; without, a 401
 *   is tried once more (another cache node may have it) and then the fallback below is used.
 * - Deezer's public catalogue, the fallback for an artist ListenBrainz will not tell about:
 *   `https://api.deezer.com/search/artist?q=…` and `https://api.deezer.com/artist/<id>/top?limit=50`,
 *   the same keyless API the charts, cover and ISRC lookups already read. Deezer allows 50 calls per
 *   5 seconds; paced 150 ms apart.
 *
 * ListenBrainz's own radio (`/1/explore/lb-radio`) would do the whole job but answers 401 without
 * a token, so it is not used.
 */
object ArtistMixSource {
    /** "Trackhop/<version> ( https://trackhop.org )": set at start by the app and the server, which know the version. */
    @Volatile var userAgent: String = "ViviMusic ( https://github.com/pwpp08/vivi-music )"

    const val ALGORITHM = "session_based_days_7500_session_300_contribution_5_threshold_10_limit_100_filter_True_skip_30"
    private const val MUSICBRAINZ = "https://musicbrainz.org/ws/2"
    private const val LABS = "https://labs.api.listenbrainz.org"
    private const val LISTENBRAINZ = "https://api.listenbrainz.org"
    private const val DEEZER = "https://api.deezer.com"

    private val musicBrainzPace = Pacer(1100)
    private val labsPace = Pacer(1000)
    private val listenBrainzPace = Pacer(250)
    private val deezerPace = Pacer(150)

    /** What the generator is doing, for a progress line: [done] of [total] in the current step. */
    enum class Step { ARTISTS, SIMILAR, TRACKS, SERVICE }

    data class Request(
        /** Artist names as typed, or the top artists of a playlist. */
        val seeds: List<String>,
        val count: Int,
        val leaning: ArtistMix.Leaning = ArtistMix.Leaning.BALANCED,
        /** Tracks the result must not repeat: the playlist the seeds came from, or the one being extended. */
        val exclude: List<Track> = emptyList(),
    )

    /** The mix, with the seed names MusicBrainz did not know. */
    data class Result(val tracks: List<Track>, val unknown: List<String>, val seeds: List<ArtistMix.Artist>)

    private fun headers(token: String? = null) = buildMap {
        put("User-Agent", userAgent)
        put("Accept", "application/json")
        token?.let { put("Authorization", "Token $it") }
    }

    private suspend fun get(url: String, token: String? = null): HttpResponse = Http.send("GET", url, headers(token), maxRateLimitRetries = 4)

    /** The artist MusicBrainz means by [name]; a second, forgiving try ("radiohed" finds Radiohead) when the exact one finds nothing. */
    suspend fun findArtist(name: String): ArtistMix.Artist? {
        for (query in listOf(escape(name), fuzzy(name)).distinct()) {
            if (query.isBlank()) continue
            val r = musicBrainzPace.paced { get("$MUSICBRAINZ/artist?" + Http.query(mapOf("query" to query, "fmt" to "json", "limit" to "3"))) }
            if (!r.ok) throw ProviderException("MusicBrainz ${r.code}")
            ArtistMix.parseArtistSearch(parseJson(r.body))?.let { return it }
        }
        return null
    }

    private val LUCENE = Regex("""([+\-!(){}\[\]^"~*?:\\/]|&&|\|\|)""")
    internal fun escape(s: String) = LUCENE.replace(s.trim()) { "\\" + it.value }
    internal fun fuzzy(s: String) = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { escape(it) + "~" }

    suspend fun similar(artist: ArtistMix.Artist): List<ArtistMix.Artist> {
        val url = "$LABS/similar-artists/json?" + Http.query(mapOf("artist_mbids" to artist.mbid, "algorithm" to ALGORITHM))
        val r = labsPace.paced { get(url) }
        if (!r.ok) throw ProviderException("ListenBrainz Labs ${r.code}")
        return ArtistMix.parseSimilar(parseJson(r.body), artist.mbid)
    }

    /** The artist's most played recordings; empty when ListenBrainz will not tell (see the 401 above). */
    suspend fun topRecordings(artist: ArtistMix.Artist, token: String?): List<Track> {
        val url = "$LISTENBRAINZ/1/popularity/top-recordings-for-artist/${artist.mbid}"
        repeat(2) { attempt ->
            val r = listenBrainzPace.paced { get(url, token) }
            if (r.header("X-RateLimit-Remaining") == "0") {
                Http.pause((r.header("X-RateLimit-Reset-In")?.toLongOrNull()?.coerceIn(1, 60) ?: 5) * 1000 + 250)
            }
            when {
                r.ok -> return ArtistMix.parseTopRecordings(parseJson(r.body), artist.mbid)
                r.code == 401 && token == null && attempt == 0 -> Http.pause(400)
                r.code == 401 || r.code == 404 -> return emptyList()
                else -> throw ProviderException("ListenBrainz ${r.code}")
            }
        }
        return emptyList()
    }

    /** The artist's top tracks on Deezer, found by the very same name; empty when Deezer has no such artist. */
    suspend fun deezerTop(artist: ArtistMix.Artist): List<Track> {
        val s = deezerPace.paced { get("$DEEZER/search/artist?" + Http.query(mapOf("q" to artist.name, "limit" to "10"))) }
        if (!s.ok) return emptyList()
        val id = ArtistMix.parseDeezerArtist(parseJson(s.body), artist.name) ?: return emptyList()
        val r = deezerPace.paced { get("$DEEZER/artist/$id/top?limit=50") }
        if (!r.ok) return emptyList()
        return ArtistMix.parseDeezerTop(parseJson(r.body), artist.name)
    }

    /** ListenBrainz's most played, or Deezer's top tracks when ListenBrainz has nothing to give. */
    suspend fun popular(artist: ArtistMix.Artist, token: String?): List<Track> =
        topRecordings(artist, token).ifEmpty { deezerTop(artist) }

    /** The token of a connected ListenBrainz account, if the user gave one: popularity then always answers. */
    fun listenBrainzToken(ctx: Env): String? = Providers.connectors().filterIsInstance<ListenBrainzProvider>()
        .firstNotNullOfOrNull { runCatching { it.userToken(ctx) }.getOrNull() }

    /**
     * The list for [request]: more tracks than asked for (a service will not have them all), in the
     * order to keep. Throws, with a text for the user, when no seed is known or nothing came back.
     */
    suspend fun generate(ctx: Env, request: Request, random: Random = Random.Default, onProgress: (Step, Int, Int) -> Unit = { _, _, _ -> }): Result {
        val names = request.seeds.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(ArtistMix.MAX_SEEDS)
        if (names.isEmpty()) throw ProviderException(ctx.text("lb_seed_needed"))
        val leaning = request.leaning
        val seeds = ArrayList<ArtistMix.Artist>()
        val unknown = ArrayList<String>()
        names.forEachIndexed { i, n ->
            onProgress(Step.ARTISTS, i, names.size)
            val a = findArtist(n)
            if (a == null) unknown += n else if (seeds.none { it.mbid == a.mbid }) seeds += a
        }
        if (seeds.isEmpty()) throw ProviderException(ctx.text("lb_artist_not_found", unknown.joinToString(", ")))
        ctx.log("mix", "seeds: " + seeds.joinToString { "${it.name} (${it.mbid})" } + if (unknown.isEmpty()) "" else "; unknown: $unknown")

        val lists = seeds.mapIndexed { i, s ->
            onProgress(Step.SIMILAR, i, seeds.size)
            runCatching { similar(s) }.getOrElse { if (it is CancellationException) throw it; ctx.log("mix", "similar ${s.name}: ${it.message}"); emptyList() }
        }
        val ranked = ArtistMix.rankSimilar(lists, seeds.map { it.mbid }.toSet())
        // The candidate list is longer than asked: some tracks will not be on the service.
        val want = (request.count * 3 + 1) / 2
        val chosen = ArtistMix.pickArtists(ranked, ArtistMix.similarNeeded(want, seeds.size, leaning), leaning, random)

        val token = listenBrainzToken(ctx)
        val everyone = seeds + chosen
        var skipped = 0
        val pools = everyone.mapIndexed { i, a ->
            onProgress(Step.TRACKS, i, everyone.size)
            val tracks = runCatching { popular(a, token) }.getOrElse { if (it is CancellationException) throw it; ctx.log("mix", "top ${a.name}: ${it.message}"); emptyList() }
            if (tracks.isEmpty()) skipped++
            ArtistMix.Pool(a, tracks)
        }
        onProgress(Step.TRACKS, everyone.size, everyone.size)
        if (skipped > 0) ctx.log("mix", "no popular recordings for $skipped of ${everyone.size} artists${if (token == null) " (anonymous)" else ""}")
        val seedPools = pools.take(seeds.size).filter { it.tracks.isNotEmpty() }
        val similarPools = pools.drop(seeds.size).filter { it.tracks.isNotEmpty() }
        val tracks = ArtistMix.mix(seedPools, similarPools, want, leaning, random, request.exclude)
        if (tracks.isEmpty()) throw ProviderException(ctx.text("lb_nothing"))
        return Result(tracks, unknown, seeds)
    }

    /**
     * The first [count] of [candidates] that [provider] has, as its own tracks, through the same
     * search and scoring as a sync ([AiMatch.match]), in the candidates' order. Searched a little
     * more than needed at a time, so a long list is not searched to the end for nothing.
     */
    suspend fun onService(
        ctx: Env, provider: MusicProvider, candidates: List<Track>, count: Int, existing: List<Track> = emptyList(),
        onProgress: (found: Int, wanted: Int) -> Unit = { _, _ -> },
    ): AiMatch.Result {
        val found = ArrayList<Track>()
        val missing = ArrayList<Track>()
        var alreadyThere = 0
        var next = 0
        while (found.size < count && next < candidates.size) {
            val need = count - found.size
            val chunk = candidates.subList(next, minOf(candidates.size, next + need + maxOf(2, need / 5)))
            next += chunk.size
            val r = AiMatch.match(ctx, provider, chunk, existing + found) { done, _ -> onProgress(found.size + done.coerceAtMost(need), count) }
            found += r.found.take(need)
            missing += r.missing
            alreadyThere += r.alreadyThere
        }
        onProgress(found.size, count)
        return AiMatch.Result(found, missing, alreadyThere)
    }
}
