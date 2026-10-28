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
 * 1. Trims query (handles trailing/leading spaces from mobile autocomplete)
 * 2. Normalizes non-breaking spaces (\u00A0) and whitespace
 * 3. Removes diacritics / accents (accent-insensitive)
 * 4. Case-insensitively checks direct matches and word tokens
 */
fun matchesSongSearch(
    query: String,
    title: String,
    artists: List<String>,
): Boolean {
    val cleanQuery = query.trim()
    if (cleanQuery.isEmpty()) return true

    fun normalize(s: String): String {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace('\u00A0', ' ')
            .lowercase()
    }

    val normQuery = normalize(cleanQuery)
    val normTitle = normalize(title)
    val normArtists = artists.map { normalize(it) }

    // Direct match against title or any artist
    if (normTitle.contains(normQuery) || normArtists.any { it.contains(normQuery) }) {
        return true
    }

    // Tokenized word-by-word match
    val tokens = normQuery.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return true

    return tokens.all { token ->
        normTitle.contains(token) || normArtists.any { it.contains(token) }
    }
}
