package com.xlollx.songport.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Spaces calls at least [gapMs] apart, across every coroutine that shares it. For services that
 * punish bursts with a long block rather than a short pause: Spotify answers a run of fast searches
 * with "retry in 1500 s" (issue #30), while the same searches spaced out go through without one.
 */
class Pacer(private val gapMs: Long) {
    private val lock = Mutex()
    private var next = 0L

    suspend fun <T> paced(block: suspend () -> T): T {
        lock.withLock {
            val now = System.currentTimeMillis()
            if (next > now) delay(next - now)
            next = maxOf(now, next) + gapMs
        }
        return block()
    }
}
