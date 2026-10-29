/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.utils

import java.math.BigInteger
import java.security.MessageDigest

fun makeTimeString(duration: Long?): String {
    if (duration == null || duration < 0) return ""
    var sec = duration / 1000
    val day = sec / 86400
    sec %= 86400
    val hour = sec / 3600
    sec %= 3600
    val minute = sec / 60
    sec %= 60
    return when {
        day > 0 -> "%d:%02d:%02d:%02d".format(day, hour, minute, sec)
        hour > 0 -> "%d:%02d:%02d".format(hour, minute, sec)
        else -> "%d:%02d".format(minute, sec)
    }
}

fun md5(str: String): String {
    val md = MessageDigest.getInstance("MD5")
    return BigInteger(1, md.digest(str.toByteArray())).toString(16).padStart(32, '0')
}

fun joinByBullet(vararg str: String?) =
    str
        .filterNot {
            it.isNullOrEmpty()
        }.joinToString(separator = " • ")

/**
 * Robust playlist song search matcher that:
 * 1. Trims query and strips invisible zero-width formatting characters
 * 2. Normalizes non-breaking spaces, unicode spacing, and whitespace
 * 3. Removes diacritics / accents (accent-insensitive)
 * 4. Checks direct matches across title, artists, and album
 * 5. Checks collapsed alphanumeric match (ignoring punctuation differences)
 * 6. Checks word token matching
 * 7. Tolerates stop-word discrepancies (e.g. "all of the love" matches "ALL FOR LOVE" or "ALL OF MY LOVE")
 */
fun matchesSongSearch(
    query: String,
    title: String,
    artists: List<String> = emptyList(),
    album: String? = null,
): Boolean {
    val cleanQuery = query.trim()
    if (cleanQuery.isEmpty()) return true

    val invisibleCharsRegex = Regex("[\\u200B-\\u200D\\uFEFF\\u00AD\\u200E\\u200F]")

    fun normalize(s: String): String {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(invisibleCharsRegex, "")
            .replace(Regex("\\p{M}"), "")
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .lowercase()
    }

    val normQuery = normalize(cleanQuery)
    val normTitle = normalize(title)
    val normArtists = artists.map { normalize(it) }
    val normAlbum = album?.let { normalize(it) }

    // Direct match against title, artists, or album
    if (normTitle.contains(normQuery) ||
        normArtists.any { it.contains(normQuery) } ||
        (normAlbum != null && normAlbum.contains(normQuery))
    ) {
        return true
    }

    // Combined searchable target text
    val fullTarget = buildString {
        append(normTitle)
        normArtists.forEach { append(" ").append(it) }
        normAlbum?.let { append(" ").append(it) }
    }

    if (fullTarget.contains(normQuery)) {
        return true
    }

    // Collapsed alphanumeric matching (handles hyphens, quotes, brackets differences)
    val collapsedQuery = normQuery.replace(Regex("[^\\p{L}\\p{N}]"), "")
    val collapsedTarget = fullTarget.replace(Regex("[^\\p{L}\\p{N}]"), "")
    if (collapsedQuery.isNotEmpty() && collapsedTarget.contains(collapsedQuery)) {
        return true
    }

    // Word tokens matching (punctuation replaced with spaces)
    val cleanTargetWords = fullTarget.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
    val cleanQueryWords = normQuery.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }

    if (cleanQueryWords.isEmpty()) return true

    // Check if all query words exist in target
    val allWordsMatch = cleanQueryWords.all { word ->
        cleanTargetWords.contains(word)
    }
    if (allWordsMatch) return true

    // Common stop-word tolerant match (e.g. user typed "all of the love" for "all of my love" or "all for love")
    val stopWords = setOf("the", "of", "a", "an", "and", "in", "on", "at", "to", "for", "with", "is", "it", "de", "la", "el", "es", "az", "a", "my")
    val coreWords = cleanQueryWords.filterNot { it in stopWords }
    if (coreWords.isNotEmpty() && coreWords.size < cleanQueryWords.size) {
        val allCoreWordsMatch = coreWords.all { word ->
            cleanTargetWords.contains(word)
        }
        if (allCoreWordsMatch) return true
    }

    return false
}
