package com.xlollx.songport.ytmbridge

import kotlinx.serialization.Serializable

/** What crosses the process boundary to Trackhop. Field names are the contract: do not rename. */
/**
 * A channel the signed-in YouTube account can act as ([YtmClient.channels]): [id] is the brand
 * account's page id, "" for the account's own channel; [selected] as the player's switcher marks it.
 */
@Serializable
data class ChannelDto(val id: String, val name: String, val byline: String? = null, val selected: Boolean = false, val hasChannel: Boolean = true)

@Serializable
data class PlaylistDto(
    val id: String, val name: String, val count: Int = -1,
    /** False for a playlist followed from someone else: readable, not writable. */
    val owned: Boolean = true,
    val description: String = "",
)

@Serializable
data class TrackDto(
    val id: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String = "",
    val durationMs: Long = 0,
    /** Id of the entry inside the playlist: needed to remove it. */
    val setVideoId: String? = null,
    /** Native uri (Spotify), when the id alone is not what the service wants back. */
    val uri: String? = null,
    val explicit: Boolean? = null,
    val addedAt: Long? = null,
    /** "album" or "artist" for library objects; null for a track. */
    val kind: String? = null,
    val year: Int? = null,
    /** False when the service lists the entry greyed out (it no longer plays); null when it did not say. */
    val available: Boolean? = null,
    /** The recording's ISRC where the service gives it (Deezer): the matching uses it first. */
    val isrc: String? = null,
)

@Serializable
data class RemoveItem(val videoId: String, val setVideoId: String? = null)
