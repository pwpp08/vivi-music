package com.xlollx.songport.auth

import com.xlollx.songport.providers.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Esiti dei login che non passano da un'Activity (flusso loopback di Google): la schermata
 * principale li mostra come messaggio quando l'utente torna nell'app.
 */
object AuthEvents {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> get() = _message

    /** Incrementato a ogni login/logout: le schermate lo usano per rileggere lo stato. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> get() = _version

    fun post(text: String) {
        _message.value = text
        _version.value = _version.value + 1
    }

    fun consume() { _message.value = null }
}

/**
 * Scope legato al processo, non a una schermata: il flusso loopback deve sopravvivere mentre
 * l'utente e' nel browser e l'app e' in secondo piano.
 */
object AuthScope : kotlinx.coroutines.CoroutineScope by kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
)
