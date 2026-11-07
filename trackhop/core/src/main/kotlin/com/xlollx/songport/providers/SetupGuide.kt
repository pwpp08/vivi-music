package com.xlollx.songport.providers

/**
 * Dati della procedura guidata "usa le tue credenziali".
 *
 * Non e' un vezzo per smanettoni: su Spotify, dal febbraio 2026, un'app in modalita' sviluppo
 * serve al massimo 5 utenti, e la quota e' legata al client ID indipendentemente da dove partono
 * le chiamate. L'unico modo perche' l'app funzioni per tutti e' che ognuno registri la propria
 * app sul portale del servizio: gratis, immediato, e la quota diventa sua.
 */
data class SetupGuide(
    /** Portale sviluppatori dove creare l'app. */
    val dashboardUrl: String,
    /** Redirect da incollare nella dashboard (null se il servizio non lo richiede). */
    val redirectUri: String? = null,
    /** Perche' conviene farlo, in una riga (nome della stringa). */
    val whyName: String,
    /** Array di stringhe con i passi da seguire (nome dello string-array). */
    val stepsArrayName: String,
    /** Etichetta del campo principale (client ID, developer token…), nome della stringa. */
    val fieldLabelName: String,
    /** Google: serve anche il client secret dell'app "Desktop". */
    val needsSecret: Boolean = false,
    /** Deezer: l'utente deve fornire anche il proprio URL di redirect https. */
    val needsRedirectUrl: Boolean = false,
    /** Campo lungo su piu' righe (il JWT di Apple Music). */
    val multiline: Boolean = false,
    /** Link diretto per ogni passo (stesso ordine di stepsArrayRes); null dove non serve. */
    val stepUrls: List<String?> = emptyList(),
)
