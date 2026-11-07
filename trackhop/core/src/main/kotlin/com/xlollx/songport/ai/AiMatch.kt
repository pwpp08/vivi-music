package com.xlollx.songport.ai

import com.xlollx.songport.providers.*
import com.xlollx.songport.platform.Env
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.sync.Matcher

/**
 * Dai nomi proposti dall'AI ai brani veri di un servizio: la stessa ricerca e lo stesso punteggio
 * della sync, uno per uno. Cio' che il servizio non ha resta fuori, e viene detto.
 */
object AiMatch {
    /** [alreadyThere]: proposals the playlist turned out to have already, once matched to real tracks. */
    data class Result(val found: List<Track>, val missing: List<Track>, val alreadyThere: Int = 0)

    /** The same recording through spelling, case, accents, "feat." tails and bracketed versions. */
    fun key(t: Track): String = Matcher.normalizeArtist(t.artists.firstOrNull().orEmpty()) + "|" + Matcher.normalizeTitle(t.title)

    /** Proposals without repeats among themselves and without anything [existing] already holds, by name. */
    fun fresh(proposals: List<Track>, existing: List<Track>): List<Track> {
        val taken = existing.mapTo(HashSet()) { key(it) }
        return proposals.filter { taken.add(key(it)) }
    }

    /**
     * The AI's proposals filtered by what [provider] actually has: each round asks the model for
     * what is still missing, tells it what the service did not have, and searches the answers on the
     * service. Stops when [prompt].count tracks are found or after three rounds. The result's tracks
     * are the service's own, ready to add; [Result.missing] is what the service never had.
     */
    suspend fun available(
        ctx: Env, c: AiClient.Config, provider: MusicProvider, prompt: AiClient.Prompt, existing: List<Track>,
        onProgress: (found: Int, wanted: Int) -> Unit = { _, _ -> },
    ): Result {
        val want = prompt.count
        val found = ArrayList<Track>()
        val missing = ArrayList<Track>()
        var alreadyThere = 0
        val size = AiClient.roundFor(c)
        for (round in 0 until 3 + want / size) {
            val need = want - found.size
            if (need <= 0) break
            onProgress(found.size, want)
            val ask = prompt.copy(
                count = minOf(need, size),
                existing = (prompt.existing + found.map { it.toString() }).distinct().take(300),
                unavailable = missing.map { it.toString() }.distinct().take(100),
            )
            val proposals = fresh(AiClient.generate(c, ask), existing + found + missing)
            if (proposals.isEmpty()) break
            val r = match(ctx, provider, proposals, existing + found) { done, _ -> onProgress(found.size + done.coerceAtMost(need), want) }
            found += r.found.take(need)
            missing += r.missing
            alreadyThere += r.alreadyThere
            if (r.found.isEmpty()) break
        }
        onProgress(found.size, want)
        return Result(found, missing, alreadyThere)
    }

    suspend fun match(ctx: Env, provider: MusicProvider, proposals: List<Track>, existing: List<Track> = emptyList(), onProgress: (Int, Int) -> Unit = { _, _ -> }): Result {
        val found = ArrayList<Track>()
        val missing = ArrayList<Track>()
        var alreadyThere = 0
        // What the playlist holds, by id and by name: a proposal that resolves to one of those is dropped.
        val seen = existing.mapTo(HashSet()) { it.id }
        val seenKeys = existing.mapTo(HashSet()) { key(it) }
        proposals.forEachIndexed { i, p ->
            onProgress(i, proposals.size)
            val best = runCatching { Matcher.best(p, provider.search(ctx, p)) }.getOrNull()
            when {
                best == null -> missing += p
                seen.add(best.id) && seenKeys.add(key(best)) -> found += best
                else -> alreadyThere++
            }
        }
        onProgress(proposals.size, proposals.size)
        return Result(found, missing, alreadyThere)
    }
}
