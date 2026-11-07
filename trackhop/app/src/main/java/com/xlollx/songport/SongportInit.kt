package com.xlollx.songport

import android.app.Application
import com.xlollx.songport.sync.Scheduler
import kotlin.concurrent.thread

/**
 * What upstream's Application class does, called from the host app's own Application. Only what
 * the sync engine needs before anything else runs stays on the main thread, so the host's cold
 * start does not pay for the rest.
 */
object SongportInit {
    fun init(app: Application) {
        com.xlollx.songport.platform.BuildDefaults.values = com.xlollx.songport.platform.AndroidEnv.buildDefaults()
        com.xlollx.songport.platform.Res.init(app)
        // MusicBrainz asks every client to name itself, with its version and a contact.
        com.xlollx.songport.ai.ArtistMixSource.userAgent = "ViviMusic/${BuildConfig.VERSION_NAME} ( https://github.com/pwpp08/vivi-music )"
        com.xlollx.songport.providers.installConnectors()
        // The connections log (hosts only) starts before anything talks to the network.
        com.xlollx.songport.net.HostLog.init(app)
        // The web connectors' extra accounts ask the connectors whether they can be added: from anywhere, with the app.
        com.xlollx.songport.providers.WebAccounts.app = app
        com.xlollx.songport.providers.BuiltIn.onAppStart(app, com.xlollx.songport.net.HostLog.interceptor)
        // A failure here must not take the host app down with it.
        thread(name = "playlist-sync-init", isDaemon = true) {
            // The sync cards' last "up to date" checks, so a card opens with them (FreshnessChecks).
            runCatching { com.xlollx.songport.ui.FreshnessChecks.init(app) }
            runCatching { Notifications.ensureChannel(app) }
            // Brings the periodic work in line with the saved configuration (idempotent).
            runCatching { Scheduler.applyAll(app) }
        }
    }
}
