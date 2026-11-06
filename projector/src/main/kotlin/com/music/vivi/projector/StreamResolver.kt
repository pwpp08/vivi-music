package com.music.vivi.projector

import android.content.Context
import com.metrolist.innertubex.InnerTubeLogEvent
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.music.innertube.YouTube
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stream URL resolution through the same innertubex extractor the phone app uses,
 * minus the PoToken WebView (the projector is too small to host a BotGuard WebView).
 */
object StreamResolver {
    class Stream(val url: String, val headers: Map<String, String>, val client: String)

    private class Bundle(val generation: Long, val extractor: InnerTubeExtractor)

    @Volatile private var current: Bundle? = null
    private val mutex = Mutex()
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun resolve(videoId: String, quality: String): Result<Stream> = runCatching {
        val hints = ContentHints().withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = false)
        val stream = requireNotNull(
            bundle().extractor.extract(
                videoId = videoId,
                hints = hints,
                excludedClients = emptySet(),
                audioQuality = when (quality) {
                    "HIGH" -> AudioQuality.HIGH
                    "LOW" -> AudioQuality.LOW
                    else -> AudioQuality.AUTO
                },
                clientPlaybackNonce = generateClientPlaybackNonce(),
            ),
        ) { "No playable stream" }
        Stream(stream.audioUrl, stream.headers, stream.clientName)
    }

    private suspend fun bundle(): Bundle {
        val transport = YouTube.extractionTransport()
        current?.takeIf { it.generation == transport.generation }?.let { return it }
        return mutex.withLock {
            val latest = YouTube.extractionTransport()
            current?.takeIf { it.generation == latest.generation }?.let { return@withLock it }
            val store = RemotePlayerConfigStore(latest.httpClient, configRepository, logger)
            val cipher = YouTubeCipherService(latest.httpClient, store, logger)
            val extractor = InnerTubeExtractor(
                configParser = YtConfigParserImpl(latest.httpClient, latest.innerTube, store, logger).withEmbeddedFallback(),
                cipherService = cipher,
                innerTube = latest.innerTube,
                tokenProvider = noTokens,
                logger = logger,
            )
            Bundle(latest.generation, extractor).also { current = it }
        }
    }

    private fun YtConfigParser.withEmbeddedFallback(): YtConfigParser =
        object : YtConfigParser by this {
            override suspend fun fetchConfig(videoId: String, useLoginCookies: Boolean) =
                try {
                    this@withEmbeddedFallback.fetchConfig(videoId, useLoginCookies)
                } catch (_: Exception) {
                    this@withEmbeddedFallback.fetchEmbeddedConfig(videoId, useLoginCookies = false)
                }
        }

    private val noTokens = object : TokenProvider {
        override val capabilities = TokenProviderCapabilities(providers = emptySet(), usesWebView = false)
        override suspend fun getPoToken(videoId: String, visitorData: String, cookie: String?): PoTokenResult? = null
        override suspend fun close() {}
    }

    private val logger = InnerTubeLogger { event: InnerTubeLogEvent ->
        android.util.Log.e("ViviPlayer", "innertubex " + event.level + " " + event.tag + ": " + event.message + " " + event.details)
    }

    private val configRepository: PlayerConfigRepository by lazy { AndroidConfigRepository(appContext) }

    private class AndroidConfigRepository(private val context: Context) : PlayerConfigRepository {
        private val prefs = context.getSharedPreferences("innertubex_player_config", Context.MODE_PRIVATE)

        override val enabled: Boolean = true
        override val sourceUrl: String = URL
        override val defaultSourceUrl: String = URL
        override var cachedJson: String
            get() = prefs.getString("json", null) ?: run {
                val asset = runCatching {
                    context.assets.open("player_configs.json").bufferedReader().use { it.readText() }
                }.getOrDefault("")
                if (asset.isNotBlank()) prefs.edit().putString("json", asset).apply()
                asset
            }
            set(value) = prefs.edit().putString("json", value).apply()
        override var cachedAtMs: Long
            get() = prefs.getLong("cached_at_ms", 0L)
            set(value) = prefs.edit().putLong("cached_at_ms", value).apply()
        override var cachedSourceUrl: String
            get() = prefs.getString("source_url", "").orEmpty()
            set(value) = prefs.edit().putString("source_url", value).apply()
        override var cachedEtag: String
            get() = prefs.getString("etag", "").orEmpty()
            set(value) = prefs.edit().putString("etag", value).apply()

        private companion object {
            const val URL = "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
        }
    }
}
