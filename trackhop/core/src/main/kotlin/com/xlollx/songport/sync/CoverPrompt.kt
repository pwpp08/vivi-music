package com.xlollx.songport.sync

import com.xlollx.songport.model.Track

/**
 * The words sent to the user's image AI for a playlist cover: the playlist's title, its most
 * frequent artists and its description, and nothing else (no account, no service, no track list).
 * The art is asked to be square, without lettering, logos, brand marks or real people's faces.
 */
object CoverPrompt {

    /** The [n] artists that appear most often, in order of frequency, then of first appearance. */
    fun topArtists(tracks: List<Track>, n: Int = 5): List<String> {
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        for (t in tracks) {
            val name = t.artists.firstOrNull()?.trim().orEmpty()
            if (name.isEmpty()) continue
            val key = name.lowercase()
            val (shown, c) = counts[key] ?: (name to 0)
            counts[key] = shown to c + 1
        }
        return counts.values.withIndex()
            .sortedWith(compareByDescending<IndexedValue<Pair<String, Int>>> { it.value.second }.thenBy { it.index })
            .take(n).map { it.value.first }
    }

    fun build(title: String, artists: List<String>, description: String = ""): String = buildString {
        append("Square album cover art for a music playlist titled \"").append(title.trim().take(120)).append("\".")
        if (artists.isNotEmpty()) append(" The music sounds like ").append(artists.take(5).joinToString(", ") { it.take(60) }).append('.')
        val d = description.trim().replace(Regex("\\s+"), " ").take(300)
        if (d.isNotEmpty()) append(" Mood and theme: ").append(d).append('.')
        append(" Style: bold, modern, abstract, music-poster.")
        append(" No text, no letters, no words, no logos, no brand marks, no real people's faces.")
    }
}
