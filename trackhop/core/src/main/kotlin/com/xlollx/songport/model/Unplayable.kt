package com.xlollx.songport.model

import kotlinx.serialization.Serializable

/**
 * An entry a service lists but can no longer play: the track as listed (with what removing it needs:
 * uri, item id), its position in the playlist, why the service refuses it (see
 * [com.xlollx.songport.sync.Unplayable]; "" when it did not say), and the playable version the
 * service itself puts in its place when it has one (Spotify's relinking).
 */
@Serializable
data class UnplayableEntry(val track: Track, val index: Int, val reason: String = "", val relinked: Track? = null) {
    /** Stable within one scan: an entry is its position and its id. */
    val key: String get() = "$index:${track.id}"
}

/** One replacement made by the tool: what was taken out and what went in (null: only removed). */
@Serializable
data class UnplayableSwap(val removed: Track, val added: Track? = null)

/** What the tool changed in one playlist, kept so it can be undone. */
@Serializable
data class UnplayableFix(val provider: String, val playlistId: String, val playlistName: String, val at: Long, val swaps: List<UnplayableSwap>)
