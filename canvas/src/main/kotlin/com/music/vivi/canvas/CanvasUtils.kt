package com.music.vivi.canvas

import java.text.Normalizer
import java.util.Locale

fun String.normalizeForComparison(): String {
    val decomposed = Normalizer.normalize(this, Normalizer.Form.NFD)
    val withoutDiacritics = Regex("\\p{InCombiningDiacriticalMarks}+").replace(decomposed, "")
    return withoutDiacritics.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s]"), " ") // keep every script, a-z0-9 would turn non-Latin titles into ""
        .replace(Regex("\\s+"), " ")
        .trim()
}
