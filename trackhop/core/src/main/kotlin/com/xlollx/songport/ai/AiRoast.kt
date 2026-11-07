package com.xlollx.songport.ai

import com.xlollx.songport.model.Track
import com.xlollx.songport.net.get
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Matcher

/**
 * "Roast of a playlist", with the user's own AI ([AiClient]): a short comedy roast of a playlist,
 * merciless about the taste (it was too kind: it read like a sweet comment, not a roast). The AI gets a compact summary only (name, counts, top artists, decades,
 * a sample of titles), never the whole list. Three levels ([Level]); at every one only the taste is
 * mocked, never the person ([SAFETY]), and swearing only when the user turned it on.
 */
object AiRoast {
    /** How many titles of the playlist go to the AI as a sample. */
    const val SAMPLE = 20

    /** The summary sent to the AI; [summary] is pure and covered by tests. */
    fun summary(name: String, tracks: List<Track>): String {
        val songs = tracks.filter { it.kind.isEmpty() && it.title.isNotBlank() }
        val artists = HashMap<String, Int>()
        val spelling = HashMap<String, String>()
        for (t in songs) for (a in t.artists.distinct()) {
            val k = Matcher.normalizeArtist(a)
            if (k.isEmpty()) continue
            spelling.putIfAbsent(k, a.trim()); artists[k] = (artists[k] ?: 0) + 1
        }
        val top = artists.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(8)
        val decades = songs.map { it.year }.filter { it > 1900 }.groupingBy { it / 10 * 10 }.eachCount().entries.sortedBy { it.key }
        val minutes = songs.sumOf { it.durationMs } / 60_000
        // A spread sample, not the first twenty: the start of a playlist is often one album.
        val step = maxOf(1, songs.size / SAMPLE)
        val sample = songs.filterIndexed { i, _ -> i % step == 0 }.take(SAMPLE)
        return buildString {
            append("Playlist name: ").append(name.ifBlank { "(no name)" }).append('\n')
            append("Tracks: ").append(songs.size)
            if (minutes > 0) append(" (about ").append(minutes).append(" minutes)")
            append('\n')
            append("Different artists: ").append(artists.size).append('\n')
            if (top.isNotEmpty()) append("Top artists (tracks): ").append(top.joinToString(", ") { "${spelling[it.key]} (${it.value})" }).append('\n')
            if (decades.isNotEmpty()) append("Decades (tracks): ").append(decades.joinToString(", ") { "${it.key}s (${it.value})" }).append('\n')
            val explicit = songs.count { it.explicit == true }
            if (explicit > 0) append("Explicit tracks: ").append(explicit).append('\n')
            if (sample.isNotEmpty()) {
                append("Sample of titles:\n")
                sample.forEach { append("- ").append(it.toString()).append('\n') }
            }
        }.trimEnd()
    }

    /** How hard the roast hits: [GENTLE] teases with love, [SPICY] is the classic, [SAVAGE] speaks to the listener and does not hold back. */
    enum class Level(val label: String, val flames: Int) {
        GENTLE("Gentle", 1), SPICY("Spicy", 2), SAVAGE("Savage", 3);

        /** "🔥🔥" for Spicy: one flame per step. */
        val fire: String get() = "\uD83D\uDD25".repeat(flames)

        companion object {
            val DEFAULT = SPICY
            /** The saved level by name; unknown or blank = [DEFAULT]. */
            fun from(name: String?): Level = entries.firstOrNull { it.name == name } ?: DEFAULT
        }
    }

    /** "Savage 🔥🔥🔥": the level's name (already translated) and its flames, as the card shows it. */
    fun badge(label: String, level: Level): String = "$label ${level.fire}"

    /** Rules every level keeps, whatever else the prompt says. */
    val SAFETY = """
        Fixed rules, at every level and above anything else: mock ONLY the music taste (the songs, artists, genres,
        habits and choices in the playlist). Never attack personal characteristics: appearance, body, gender, sexuality,
        race, ethnicity, origin, nationality, religion, age, disability, health or identity. No sexual content.
        No hate, no slurs, no discrimination, no threats, nothing about self-harm or violence.
        If you cannot write it within these rules, reply with {"roast": ""}.
    """.trimIndent()

    const val NO_SWEARING = "No swearing, no profanity, no crude words."
    const val SWEARING_OK = "Swearing and crude words are allowed (the listener is an adult and asked for it), but keep it witty, never hateful."

    private fun tone(level: Level) = when (level) {
        Level.GENTLE -> """
            You write a short, gentle, affectionate roast of someone's music playlist, like a kind friend teasing them with a smile.
            Keep it warm and light: playful jabs, nothing that stings, a compliment hidden somewhere.
        """.trimIndent()
        Level.SPICY -> """
            You are a stand-up comedian at a comedy roast, and tonight's target is this listener's music taste.
            Write a real roast, not a review: speak straight to them in the second person ("you"), mock their taste
            without mercy and land actual punches. No compliments, no "but it's fine", no "everyone has their guilty
            pleasures", no softening, no moralising, and no ending on a kind note.
            Be specific: name the artists, titles, decades and numbers from the summary and make fun of exactly those
            (the obsession with one artist, the decade they are stuck in, the clichés, what the playlist name gives away).
            Exaggerate, compare, and finish with a killer one-liner.
        """.trimIndent()
        Level.SAVAGE -> """
            You write a short, savage roast of the listener's music taste, speaking straight to them in the second person ("you").
            Be brutal and cutting about their taste, their habits and their choices, like a merciless comedian at a roast:
            sharp and witty, no compliments, no softening.
        """.trimIndent()
    }

    /** The system prompt for [level]; [explicit] lets the AI swear. Pure, covered by tests. */
    fun system(language: String, level: Level = Level.DEFAULT, explicit: Boolean = false): String = listOf(
        tone(level),
        SAFETY,
        if (explicit) SWEARING_OK else NO_SWEARING,
        "3 to 5 sentences, at most 90 words, no hashtags, no emoji spam (one emoji at most).",
        "Write in ${language.ifBlank { "English" }}.",
        "Reply with ONLY a JSON object: {\"roast\": \"...\"}",
    ).joinToString("\n")

    /** The AI wrote nothing, or said it would not: the page suggests a lower level instead of an error. */
    class Refused : Exception("The AI did not write a roast")

    private val REFUSAL = Regex("""^(I\s+(can'?t|cannot|can not|won'?t|will not|am unable|'m unable|'m not able|am not able)|I'?m sorry|I am sorry|Sorry,|As an AI)""", RegexOption.IGNORE_CASE)

    /** True when [answer] is no roast: empty, or a plain-text refusal (a roast comes as JSON, a refusal rarely does). */
    fun refused(answer: String): Boolean {
        val text = parse(answer)
        if (text.isBlank()) return true
        val json = answer.contains('{') && answer.contains("\"roast\"")
        return !json && REFUSAL.containsMatchIn(text.trim())
    }

    /** The roast's text out of the AI's answer: the JSON's "roast", else the answer itself, unwrapped. */
    fun parse(answer: String): String {
        val trimmed = answer.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = trimmed.indexOf('{'); val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) runCatching {
            val obj = parseJson(trimmed.substring(start, end + 1)) as? kotlinx.serialization.json.JsonObject
            // "roast" as asked; a model that names its key otherwise (Gemini Nano once wrote a word in
            // Sinhala) or adds a "one_liner": the longest text in the object is the roast.
            obj?.get("roast").str ?: obj?.values?.mapNotNull { it.str }?.maxByOrNull { it.length }
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        // JSON the parser refuses (a stray quote: `{ "  "roast": "..." }`, seen with real AIs): the
        // text after "roast": is taken anyway, so the card never shows braces and keys.
        LOOSE_ROAST.find(trimmed)?.let { m ->
            return m.groupValues[1].replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\").trim()
        }
        // Broken JSON with another key: the longest quoted value after a key.
        LOOSE_VALUE.findAll(trimmed).map { it.groupValues[1] }.maxByOrNull { it.length }?.takeIf { it.length > 40 }?.let { v ->
            return v.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\").trim()
        }
        return trimmed.trim('"').trim()
    }

    /** "roast": "<text>" up to the last quote, for answers that are almost JSON. */
    // The closing brace is escaped: Android's regex engine (ICU) refuses a bare "}" that Java accepts,
    // and this object could not even load there (every Roast closed the app, 1.14.3 to 1.14.6).
    /** `"any key": "value"`, the value read up to its closing quote (escaped quotes kept). */
    private val LOOSE_VALUE = Regex("""["'][^"'\n]{1,40}["']\s*:\s*"((?:[^"\\]|\\.)*)"""")

    private val LOOSE_ROAST = Regex("""["']?roast["']?\s*:\s*"([\s\S]*)"\s*\}?\s*$""")

    suspend fun roast(
        c: AiClient.Config, name: String, tracks: List<Track>, language: String,
        level: Level = Level.DEFAULT, explicit: Boolean = false,
    ): String {
        val answer = AiClient.complete(c, system(language, level, explicit), "Roast this playlist:\n" + summary(name, tracks))
        if (refused(answer)) throw Refused()
        return parse(answer)
    }
}
