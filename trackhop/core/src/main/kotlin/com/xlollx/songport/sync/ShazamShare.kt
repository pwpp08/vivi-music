package com.xlollx.songport.sync

import com.xlollx.songport.model.Track

/**
 * A song shared from the Shazam app: its text names the title and the artist ("I used Shazam to
 * discover Get Lucky by Daft Punk. https://www.shazam.com/track/…"), so the track is read from the
 * text alone, without asking Shazam anything. When the sentence is not one of the known wordings,
 * the title in the link's address ("…/track/66932935/get-lucky") is enough for a search.
 * Kotlin puro: coperto dai test.
 */
object ShazamShare {

    const val SERVICE = "shazam"

    /** shazam.com/track/<id>/<slug> or /song/<id>/<slug>, with an optional language segment. */
    val LINK = Regex("""https?://(?:www\.)?shazam\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?(?:track|song)/(\d+)(?:/([^\s?#/]+))?""", RegexOption.IGNORE_CASE)

    // The share sentence in the app's languages: title, then the word for "by", then the artist.
    // Greedy titles: the last "by" splits, so "Stand by Me by Ben E. King" keeps its title whole.
    private val SENTENCES = listOf(
        """(?:I used Shazam to discover|I just used Shazam to discover|Shazam(?:ed)?:?)\s+(.+)\s+by\s+(.+)""",
        """Ho usato Shazam per scoprire\s+(.+)\s+di\s+(.+)""",
        """(?:Usé|He usado) Shazam para descubrir\s+(.+)\s+de\s+(.+)""",
        """J'ai utilisé Shazam pour découvrir\s+(.+)\s+(?:de|par)\s+(.+)""",
        """Usei o Shazam para descobrir\s+(.+)\s+de\s+(.+)""",
        """Ich habe (?:gerade )?(?:mit )?Shazam\s+(?:benutzt,? um\s+)?(.+)\s+von\s+(.+?)\s+(?:zu )?entdeck(?:t|en)""",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    fun isShazam(text: String): Boolean = LINK.containsMatchIn(text)

    /** The track the shared text is about, or null when it carries no Shazam link. */
    fun track(text: String): Track? {
        val m = LINK.find(text) ?: return null
        val id = m.groupValues[1]
        val sentence = text.replace(Regex("""https?://\S+"""), " ").replace(Regex("""\s+"""), " ").trim().trimEnd('.', '!', ' ')
        for (r in SENTENCES) {
            val s = r.find(sentence) ?: continue
            val title = s.groupValues[1].trim().trim('"', '“', '”', '«', '»')
            val artist = s.groupValues[2].trim().trimEnd('.', '!').trim('"', '“', '”', '«', '»')
            if (title.isNotEmpty() && artist.isNotEmpty()) return Track(id = id, title = title, artists = listOf(artist))
        }
        // A wording not listed above: the address's own words, which carry the title.
        val slug = m.groupValues.getOrNull(2).orEmpty()
        val fromSlug = java.net.URLDecoder.decode(slug, "UTF-8").replace('-', ' ').replace(Regex("""\s+"""), " ").trim()
        return Track(id = id, title = fromSlug)
    }

    /** What to search for on another service. */
    fun query(t: Track): String = if (t.artists.isEmpty()) t.title else "${t.artistLine} - ${t.title}"
}
