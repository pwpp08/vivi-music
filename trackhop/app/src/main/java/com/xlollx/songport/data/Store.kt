package com.xlollx.songport.data

import com.xlollx.songport.providers.*
import android.content.Context
import com.xlollx.songport.providers.Providers

/**
 * The app's store: the engine's [FileStore] in the app's private folder, plus the one-time
 * migration that needs Android (which connectors are signed in).
 */
class Store private constructor(context: Context) : FileStore(context.filesDir) {
    init { migrateConnectors(context) }

    /** First run of the connector model: whatever is connected or used by a sync becomes a connector. */
    private fun migrateConnectors(ctx: Context) {
        val d = data
        if (d.settings.connectorsInit) return
        val ids = Providers.all().filter { p ->
            p.slot.isNotEmpty() ||
                d.jobs.any { it.source.provider == p.id || it.target.provider == p.id } ||
                (p.requiresAuth && runCatching { p.isConnected(ctx) }.getOrDefault(false))
        }.map { it.id }
        update { s -> s.copy(settings = s.settings.copy(connectors = ids, connectorsInit = true)) }
    }


    companion object {
        fun cacheKey(srcProvider: String, srcTrackId: String, dstProvider: String) = FileStore.cacheKey(srcProvider, srcTrackId, dstProvider)
        fun family(providerId: String): String = FileStore.family(providerId)

        @Volatile private var instance: Store? = null
        fun get(context: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(context.applicationContext).also { instance = it; FileStore.install(it) }
        }
    }
}
