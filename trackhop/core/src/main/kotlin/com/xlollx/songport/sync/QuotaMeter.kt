package com.xlollx.songport.sync

import com.xlollx.songport.platform.Env
import com.xlollx.songport.data.QuotaDay
import com.xlollx.songport.data.StoreData
import java.time.LocalDate
import java.time.ZoneId

/**
 * Contatore della quota API di YouTube. E' un limite di Google (10.000 unita' al giorno per
 * progetto, azzerato a mezzanotte ora del Pacifico), non di Trackhop: qui lo stimiamo contando
 * il costo di ogni chiamata fatta da questo telefono, cosi' l'utente vede quanto resta.
 * Con le credenziali integrate la quota e' condivisa con gli altri utenti della build: la stima
 * e' quindi per difetto e va presentata come tale.
 */
object QuotaMeter {
    const val YOUTUBE_DAILY = 10_000L
    const val COST_LIST = 1L
    const val COST_SEARCH = 100L
    const val COST_WRITE = 50L

    fun dayKey(): String = LocalDate.now(ZoneId.of("America/Los_Angeles")).toString()

    fun add(ctx: Env, serviceId: String, units: Long) {
        if (units <= 0) return
        ctx.store.update { d ->
            val today = dayKey()
            val base = d.quota[serviceId]?.takeIf { it.day == today }?.units ?: 0
            d.copy(quota = d.quota + (serviceId to QuotaDay(today, base + units)))
        }
    }

    fun used(data: StoreData, serviceId: String): Long =
        data.quota[serviceId]?.takeIf { it.day == dayKey() }?.units ?: 0

    /** Costo stimato per aggiungere N brani gia' abbinati (un inserimento ciascuno). */
    fun estimateAdd(count: Int): Long = count * COST_WRITE
}
