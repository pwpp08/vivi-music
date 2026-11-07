package com.xlollx.songport.net

import com.xlollx.songport.providers.*
import android.content.Context
import android.content.SharedPreferences
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Il registro delle connessioni: quali host l'app ha contattato, quante volte, l'ultima quando.
 * Nessun contenuto, solo il nome dell'host. E' la risposta verificabile alla domanda "dove va la mia
 * chiave?": in Opzioni si legge l'elenco, e se compare solo il fornitore scelto e i servizi
 * collegati, la promessa "nessun server Trackhop" e' li' da controllare, non da credere. I connettori
 * web (YouTube Music, Amazon) parlano anche attraverso il motore del browser, che qui non passa: la
 * schermata lo dice.
 */
object HostLog {
    private const val PREFS = "host_log"
    @Volatile private var prefs: SharedPreferences? = null

    data class Entry(val host: String, val count: Long, val lastAt: Long)

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        Http.onRequest = { record(it) }
    }

    /** Da aggiungere a ogni OkHttpClient dell'app: conta la richiesta e passa oltre. */
    val interceptor: Interceptor get() = Http.hostInterceptor

    fun record(host: String) {
        val p = prefs ?: return
        val count = p.getLong("$host|n", 0) + 1
        p.edit().putLong("$host|n", count).putLong("$host|t", System.currentTimeMillis()).apply()
    }

    fun entries(): List<Entry> {
        val p = prefs ?: return emptyList()
        return p.all.keys.filter { it.endsWith("|n") }.map { k ->
            val host = k.removeSuffix("|n")
            Entry(host, p.getLong(k, 0), p.getLong("$host|t", 0))
        }.sortedByDescending { it.lastAt }
    }

    fun clear() { prefs?.edit()?.clear()?.apply() }
}
