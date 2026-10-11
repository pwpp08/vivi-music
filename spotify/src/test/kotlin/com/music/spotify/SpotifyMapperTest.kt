package com.music.spotify

import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyMapperTest {

    @Test
    fun testNonLatinTitlesDoNotBecomeEmptyOrMatchEverything() {
        // Japanese: distinct tracks from same artist must not match with 1.0 (empty string issue)
        val scoreDiff = SpotifyMapper.matchScore(
            spotifyTitle = "夜に駆ける",
            spotifyArtist = "YOASOBI",
            spotifyDurationMs = 261000,
            candidateTitle = "アイドル",
            candidateArtist = "YOASOBI",
            candidateDurationSec = 261
        )
        assertTrue("Different Japanese titles should have low match score, got: $scoreDiff", scoreDiff < 0.8)

        // Matching Japanese tracks
        val scoreSame = SpotifyMapper.matchScore(
            spotifyTitle = "夜に駆ける (feat. Ayase) [2020]",
            spotifyArtist = "YOASOBI",
            spotifyDurationMs = 261000,
            candidateTitle = "夜に駆ける",
            candidateArtist = "YOASOBI",
            candidateDurationSec = 261
        )
        assertTrue("Same Japanese titles should match with high score, got: $scoreSame", scoreSame > 0.9)

        // Cyrillic tracks
        val scoreCyrillic = SpotifyMapper.matchScore(
            spotifyTitle = "Группа крови",
            spotifyArtist = "Кино",
            spotifyDurationMs = 240000,
            candidateTitle = "Группа крови",
            candidateArtist = "Кино",
            candidateDurationSec = 240
        )
        assertTrue("Same Cyrillic titles should match with high score, got: $scoreCyrillic", scoreCyrillic > 0.9)

        // Korean tracks
        val scoreKorean = SpotifyMapper.matchScore(
            spotifyTitle = "봄날",
            spotifyArtist = "방탄소년단",
            spotifyDurationMs = 274000,
            candidateTitle = "봄날",
            candidateArtist = "방탄소년단",
            candidateDurationSec = 274
        )
        assertTrue("Same Korean titles should match with high score, got: $scoreKorean", scoreKorean > 0.9)
    }
}
