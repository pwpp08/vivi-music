/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.playback.queues

import androidx.media3.common.MediaItem
import com.music.vivi.extensions.metadata
import com.music.vivi.models.MediaMetadata

interface Queue {
    val preloadItem: MediaMetadata?

    suspend fun getInitialStatus(): Status

    fun hasNextPage(): Boolean

    suspend fun nextPage(): List<MediaItem>

    data class Status(
        val title: String?,
        val items: List<MediaItem>,
        val mediaItemIndex: Int,
        val position: Long = 0L,
    ) {
        fun filterExplicit(enabled: Boolean = true) =
            if (enabled) {
                withItemsFiltered { it.metadata?.explicit != true }
            } else {
                this
            }

        fun filterVideoSongs(disableVideos: Boolean = false) =
            if (disableVideos) {
                withItemsFiltered { it.metadata?.isVideoSong != true }
            } else {
                this
            }

        /**
         * Drops the items rejected by [keep] and moves [mediaItemIndex] along with the list, so it still points at the
         * song the user picked (or the next kept one when that song itself was removed) instead of at a shifted item
         * or past the end of the shortened list.
         */
        private fun withItemsFiltered(keep: (MediaItem) -> Boolean): Status {
            val kept = items.filter(keep)
            if (kept.size == items.size) return this

            val startIndex = mediaItemIndex.coerceAtLeast(0)
            val removedBeforeStart = items.take(startIndex).count { !keep(it) }
            val startItemRemoved = items.getOrNull(startIndex)?.let { !keep(it) } == true
            return copy(
                items = kept,
                mediaItemIndex = (startIndex - removedBeforeStart).coerceIn(0, (kept.size - 1).coerceAtLeast(0)),
                position = if (startItemRemoved) 0L else position,
            )
        }
    }
}

fun List<MediaItem>.filterExplicit(enabled: Boolean = true) =
    if (enabled) {
        filterNot {
            it.metadata?.explicit == true
        }
    } else {
        this
    }

fun List<MediaItem>.filterVideoSongs(disableVideos: Boolean = false) =
    if (disableVideos) {
        filterNot { it.metadata?.isVideoSong == true }
    } else {
        this
    }
