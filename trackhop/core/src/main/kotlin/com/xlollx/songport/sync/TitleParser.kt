package com.xlollx.songport.sync

/** Estrae titolo e artisti dai titoli dei video YouTube ("Artista - Titolo (Official Video)"). */
object TitleParser {
    private val JUNK = Regex(
        """\s*[(\[{][^)\]}]*\b(official|video|audio|lyric|lyrics|visuali[sz]er|hd|hq|4k|music video|videoclip|clip|explicit|clean|out now|free download|premiere|live)\b[^)\]}]*[)\]}]""",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING = Regex("""\s*[|/]\s*[^|/]*\b(official|video|audio|lyrics?)\b[^|/]*$""", RegexOption.IGNORE_CASE)
    private val SEP = Regex("""\s+[-–—:]\s+""")
    private val ARTIST_SPLIT = Regex("""\s*(,|&|\+|\s+x\s+|\bvs\.?\s|\bfeat\.?\s|\bft\.?\s|\bfeaturing\s)\s*""", RegexOption.IGNORE_CASE)
    private val QUOTED = Regex("""^["“](.*)["”]$""")
    private val SPACES = Regex("""\s+""")

    fun clean(title: String): String {
        var t = JUNK.replace(title, " ")
        t = TRAILING.replace(t, " ")
        return SPACES.replace(t, " ").trim()
    }

    fun channelArtist(channel: String?): String {
        val c = channel?.trim() ?: return ""
        return c.removeSuffix(" - Topic").replace(Regex("""VEVO$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*Topic$"""), "").replace(Regex("""\s*Official$""", RegexOption.IGNORE_CASE), "").trim()
    }

    /** @return titolo pulito e lista artisti (puo' essere vuota se non deducibile). */
    fun parseYouTube(rawTitle: String, channel: String?): Pair<String, List<String>> {
        val t = clean(rawTitle)
        val isTopic = channel?.trim()?.endsWith(" - Topic") == true
        val chArtist = channelArtist(channel)
        val parts = t.split(SEP, limit = 2)
        return when {
            // Canali auto-generati "Artista - Topic": il titolo e' gia' il solo nome del brano.
            isTopic -> t to listOfNotNull(chArtist.takeIf { it.isNotBlank() })
            parts.size == 2 -> {
                val title = QUOTED.replace(parts[1].trim(), "$1")
                title to splitArtists(parts[0])
            }
            else -> t to listOfNotNull(chArtist.takeIf { it.isNotBlank() && !it.contains("Various", ignoreCase = true) })
        }
    }

    fun splitArtists(s: String): List<String> =
        s.split(ARTIST_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
}
