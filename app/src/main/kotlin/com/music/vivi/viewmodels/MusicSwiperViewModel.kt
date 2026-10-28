/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.WatchEndpoint
import com.music.vivi.db.MusicDatabase
import com.music.vivi.db.entities.Song
import com.music.vivi.extensions.toMediaItem
import com.music.vivi.models.toMediaMetadata
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.ArrayDeque
import javax.inject.Inject

@HiltViewModel
class MusicSwiperViewModel @Inject constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
) : ViewModel() {

    private val _cards = MutableStateFlow<List<SongItem>>(emptyList())
    val cards = _cards.asStateFlow()

    private val _likedCount = MutableStateFlow(0)
    val likedCount = _likedCount.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val swipedHistory = ArrayDeque<SongItem>()
    private val seenSongIds = mutableSetOf<String>()

    init {
        loadRecommendations()
    }

    fun loadRecommendations() {
        if (_isLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                // 1. Gather seed candidate songs from user's liked and played tracks
                val likedSongs = database.likedSongsByCreateDateAsc().firstOrNull().orEmpty()
                val recentSongs = database.mostPlayedSongs(20).firstOrNull().orEmpty()
                val allTasteSeeds = (likedSongs + recentSongs).distinctBy { it.id }.shuffled()

                val newRecommendations = mutableListOf<SongItem>()

                if (allTasteSeeds.isNotEmpty()) {
                    // Query related/radio recommendations for up to 3 random favorite seeds
                    for (seed in allTasteSeeds.take(3)) {
                        val result = YouTube.next(WatchEndpoint(videoId = seed.id)).getOrNull()
                        val items = result?.items.orEmpty()
                        for (item in items) {
                            if (!seenSongIds.contains(item.id) &&
                                likedSongs.none { it.id == item.id } &&
                                newRecommendations.none { it.id == item.id }
                            ) {
                                newRecommendations.add(item)
                            }
                        }
                        if (newRecommendations.size >= 25) break
                    }
                }

                // If user has no local history or insufficient seeds, discover top trending new tracks
                if (newRecommendations.size < 10) {
                    val fallbackSearch = YouTube.search("trending hits", YouTube.SearchFilter.FILTER_SONG).getOrNull()
                    val fallbackItems = fallbackSearch?.items?.filterIsInstance<SongItem>().orEmpty()
                    for (item in fallbackItems) {
                        if (!seenSongIds.contains(item.id) && newRecommendations.none { it.id == item.id }) {
                            newRecommendations.add(item)
                        }
                    }
                }

                seenSongIds.addAll(newRecommendations.map { it.id })
                _cards.value = (_cards.value + newRecommendations).distinctBy { it.id }
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Error loading music swiper taste recommendations")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun swipeRight(song: SongItem) {
        swipedHistory.push(song)
        _cards.value = _cards.value.filter { it.id != song.id }
        _likedCount.value += 1

        viewModelScope.launch(Dispatchers.IO) {
            val metadata = song.toMediaMetadata().copy(
                liked = true,
                likedDate = LocalDateTime.now(),
            )
            database.query {
                insert(metadata)
            }
        }

        if (_cards.value.size < 5) {
            loadRecommendations()
        }
    }

    fun swipeLeft(song: SongItem) {
        swipedHistory.push(song)
        _cards.value = _cards.value.filter { it.id != song.id }

        if (_cards.value.size < 5) {
            loadRecommendations()
        }
    }

    fun undo() {
        if (swipedHistory.isNotEmpty()) {
            val last = swipedHistory.pop()
            _cards.value = listOf(last) + _cards.value
        }
    }
}
