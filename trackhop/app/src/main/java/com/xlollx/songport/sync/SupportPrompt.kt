package com.xlollx.songport.sync

import com.xlollx.songport.providers.*
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.SyncReport

/**
 * La richiesta di contributo, una volta sola nella vita dell'app: subito dopo la prima sync riuscita
 * che ha spostato tanti brani, quando l'utente ha appena visto il valore dell'app. Solo nella build
 * senza pubblicita': quella Play si sostiene col banner e non chiede altro.
 */
object SupportPrompt {
    /** Brani aggiunti da una sola sync perche' valga la pena chiedere. */
    const val MIN_ADDED = 100

    fun available(): Boolean = !BuildConfig.STORE_BUILD && (BuildConfig.KOFI_URL.isNotBlank() || BuildConfig.SPONSORS_URL.isNotBlank())

    /** Chiamato a fine sync: arma la richiesta se e' la prima sync grande e non e' mai stata mostrata. */
    fun offer(store: Store, report: SyncReport) {
        if (!available() || !report.ok || report.partial || report.added < MIN_ADDED) return
        val s = store.data.settings
        if (s.supportPromptDone || s.supportPromptReport != null) return
        store.updateSettings { it.copy(supportPromptReport = report.id) }
    }

    /** La sync da citare nella richiesta, o null se non c'e' niente da chiedere. */
    fun pending(store: Store): SyncReport? {
        if (!available()) return null
        val s = store.data.settings
        if (s.supportPromptDone) return null
        return store.data.reports.firstOrNull { it.id == s.supportPromptReport }
    }

    /** Qualunque risposta chiude la richiesta per sempre. */
    fun close(store: Store) = store.updateSettings { it.copy(supportPromptDone = true, supportPromptReport = null) }

    /** One open in ten shows a small coffee card at the top of the syncs; the next open hides it again. */
    const val NUDGE_EVERY = 10

    /** Counted once per process start, from MainActivity. */
    fun opened(store: Store) = store.updateSettings { it.copy(appOpens = it.appOpens + 1) }

    /** True on the tenth, twentieth... open, until the card is answered or the app is opened again. */
    fun nudge(store: Store): Boolean {
        if (!available()) return false
        val s = store.data.settings
        return s.appOpens >= NUDGE_EVERY && s.appOpens % NUDGE_EVERY == 0 && s.supportNudgeSeen != s.appOpens && s.supportPromptReport == null
    }

    fun closeNudge(store: Store) = store.updateSettings { it.copy(supportNudgeSeen = it.appOpens) }
}
