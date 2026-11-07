package com.xlollx.songport.sync

import com.xlollx.songport.model.Track

/**
 * "Vanished songs": tracks that were in one of the user's playlists and are not any more. The app
 * keeps dated versions of each backed-up playlist (LocalFilesProvider.writeBackup, up to five):
 * a track in an older version and missing from the newest one has left the playlist since,
 * taken out by hand or pulled by the service. Pure Kotlin, covered by tests.
 */
object Vanished {

    /** One dated copy of a playlist. */
    data class Version(val at: Long, val tracks: List<Track>)

    /** A track gone from [playlist] (on [service], when known), last seen in the copy of [lastSeen]. */
    data class Gone(val track: Track, val playlist: String, val service: String?, val lastSeen: Long)

    /** What left [playlist] between its older [versions] and the newest one; most recently lost first. */
    fun between(playlist: String, service: String?, versions: List<Version>): List<Gone> {
        if (versions.size < 2) return emptyList()
        val sorted = versions.sortedByDescending { it.at }
        val now = sorted.first().tracks.map { Wrapped.key(it) }.toHashSet()
        val gone = LinkedHashMap<String, Gone>()
        for (v in sorted.drop(1)) for (t in v.tracks) {
            if (t.kind.isNotEmpty() || t.title.isBlank()) continue
            val k = Wrapped.key(t)
            if (k in now) continue
            // The newest older copy that still had it: the first met, as copies come newest first.
            gone.putIfAbsent(k, Gone(t, playlist, service, v.at))
        }
        return gone.values.sortedByDescending { it.lastSeen }
    }
}
