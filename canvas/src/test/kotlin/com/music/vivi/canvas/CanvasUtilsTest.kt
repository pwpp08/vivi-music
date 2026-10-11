package com.music.vivi.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasUtilsTest {

    @Test
    fun testNormalizeForComparison() {
        // Casing and diacritics
        assertEquals("mexico", "México".normalizeForComparison())
        assertEquals("edicion", "Edición".normalizeForComparison())
        assertEquals("cancion", "canción".normalizeForComparison())

        // Punctuation and spacing
        assertEquals("mexico en la piel edicion diamante", "México en la Piel (Edición Diamante)".normalizeForComparison())
        assertEquals("mexico en la piel edicion diamante", "Mexico en la Piel (edicion diamante)".normalizeForComparison())

        // Full match check
        val normalized1 = "México en la Piel (Edición Diamante)".normalizeForComparison()
        val normalized2 = "Mexico en la Piel (edicion diamante)".normalizeForComparison()
        assertEquals(normalized1, normalized2)
        assertTrue(normalized1.isNotBlank())
    }

    @Test
    fun testNormalizeForComparisonNonLatin() {
        // Non-Latin scripts (Cyrillic, Japanese, Korean, Persian) must not become empty
        val cyrillic = "Группа крови".normalizeForComparison()
        assertEquals("группа крови", cyrillic)

        val japanese = "夜に駆ける".normalizeForComparison()
        assertEquals("夜に駆ける", japanese)

        val persian = "گل سنگم".normalizeForComparison()
        assertEquals("گل سنگم", persian)

        val korean1 = "봄날 (Spring Day)".normalizeForComparison()
        val korean2 = "봄날".normalizeForComparison()
        assertTrue(korean2.isNotBlank())
        assertTrue(korean1.contains(korean2))

        // Two different non-Latin titles must not normalize to the same empty string
        val diff1 = "夜に駆ける".normalizeForComparison()
        val diff2 = "アイドル".normalizeForComparison()
        assertTrue(diff1.isNotBlank())
        assertTrue(diff2.isNotBlank())
        org.junit.Assert.assertNotEquals(diff1, diff2)
    }
}
