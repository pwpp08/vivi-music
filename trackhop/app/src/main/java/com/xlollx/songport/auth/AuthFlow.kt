package com.xlollx.songport.auth

import com.xlollx.songport.providers.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.R
import com.xlollx.songport.providers.OAuthProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.UnknownHostException

/**
 * Login OAuth: apre il browser (Custom Tab) sull'URL di autorizzazione del servizio e
 * conserva verifier/state in attesa del redirect (gestito da AuthCallbackActivity).
 * Lo stato e' su disco perche' il processo puo' essere ucciso mentre il browser e' aperto.
 */
object AuthFlow {
    const val REDIRECT_URI = "songport://callback"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("auth_pending", Context.MODE_PRIVATE)

    fun start(ctx: Context, provider: MusicProvider) = provider.startAuth(ctx)

    /** Avvia un flusso OAuth nel browser (Custom Tab) conservando verifier e state. */
    fun startBrowserFlow(ctx: Context, provider: MusicProvider) {
        val verifier = Pkce.verifier()
        val state = Pkce.state()
        prefs(ctx).edit()
            .putString("provider", provider.id)
            .putString("verifier", verifier)
            .putString("state", state)
            .putLong("at", System.currentTimeMillis())
            .apply()
        val url = (provider as OAuthProvider).authUrl(ctx, state, Pkce.challenge(verifier))
        if (provider.loginInApp) {
            // The service's sign-in page inside the app: "Continue with Facebook" stays on the page
            // instead of being handed to the Facebook app (see WebAuthActivity).
            ctx.startActivity(
                Intent(ctx, WebAuthActivity::class.java)
                    .putExtra(WebAuthActivity.EXTRA_URL, url)
                    .putExtra(WebAuthActivity.EXTRA_SERVICE, provider.displayName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        openInBrowser(ctx, url, provider)
    }

    /**
     * The sign-in page in the phone's browser (a Custom Tab), from any context: started from the
     * application context it needs FLAG_ACTIVITY_NEW_TASK, or Android stops the app (1.5.9, TIDAL).
     * No Custom Tab browser: any browser; no browser at all: the page inside the app.
     */
    private fun openInBrowser(ctx: Context, url: String, provider: MusicProvider) {
        val uri = Uri.parse(url)
        val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
        tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { tab.launchUrl(ctx, uri); return } catch (_: android.content.ActivityNotFoundException) {}
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: android.content.ActivityNotFoundException) {}
        ctx.startActivity(
            Intent(ctx, WebAuthActivity::class.java)
                .putExtra(WebAuthActivity.EXTRA_URL, url)
                .putExtra(WebAuthActivity.EXTRA_SERVICE, provider.displayName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Flusso OAuth con redirect su loopback (client "installed app" di Google): apre una porta
     * su 127.0.0.1, manda il browser li' e attende il codice. Serve perche' un client Desktop
     * non accetta schemi personalizzati, e permette all'utente di usare le proprie credenziali
     * Google senza che il redirect sia cablato nel manifest.
     */
    fun startLoopbackFlow(scope: CoroutineScope, ctx: Context, provider: OAuthProvider) {
        scope.launch {
            val server = try {
                LoopbackServer.open(ctx.getString(R.string.th_app_name))
            } catch (e: Exception) {
                AuthEvents.post(ctx.getString(R.string.th_auth_failed, e.message ?: e.javaClass.simpleName))
                return@launch
            }
            try {
                val verifier = Pkce.verifier()
                val state = Pkce.state()
                val url = provider.authUrl(ctx, state, Pkce.challenge(verifier), server.redirectUri)
                val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
                tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                tab.launchUrl(ctx, Uri.parse(url))

                val params = server.awaitRedirect()
                // Il browser ha ricevuto il redirect: prima di parlare con Google riportiamo l'app in primo
                // piano. In secondo piano, con risparmio dati o batteria attivi, Android nega la rete e il
                // DNS fallisce con "Unable to resolve host".
                if (params.isNotEmpty()) {
                    runCatching {
                        ctx.startActivity(
                            Intent(ctx, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_ACCOUNTS)
                            }
                        )
                    }
                }
                val message = try {
                    when {
                        params.isEmpty() -> ctx.getString(R.string.th_auth_failed, ctx.getString(R.string.th_auth_timeout))
                        params["error"] != null ->
                            ctx.getString(R.string.th_auth_failed, params["error_description"] ?: params["error"]!!)
                        params["state"] != null && params["state"] != state ->
                            ctx.getString(R.string.th_auth_state_mismatch)
                        else -> {
                            retryOnDns {
                                provider.completeAuth(
                                    ctx,
                                    params + mapOf(OAuthProvider.PARAM_REDIRECT_URI to server.redirectUri),
                                    verifier,
                                )
                            }
                            ctx.getString(R.string.th_auth_done, provider.displayName)
                        }
                    }
                } catch (e: Exception) {
                    ctx.getString(R.string.th_auth_failed, friendlyError(ctx, provider, e.message))
                }
                AuthEvents.post(message)
            } finally {
                server.close()
            }
        }
    }

    /**
     * Traduce gli errori criptici dei servizi in una frase che dice cosa fare. Il caso tipico e'
     * Spotify: un'app in modalita' sviluppo accetta 5 utenti, chi non e' nell'elenco riceve un
     * generico access_denied e non capisce perche'.
     */
    /** Il DNS puo' fallire per qualche secondo mentre l'app torna in primo piano: riproviamo. */
    private suspend fun <T> retryOnDns(times: Int = 4, block: suspend () -> T): T {
        var last: Exception? = null
        repeat(times) { i ->
            try {
                return block()
            } catch (e: UnknownHostException) {
                last = e
                delay(1000L * (i + 1))
            }
        }
        throw last!!
    }

    fun friendlyError(ctx: Context, provider: MusicProvider, raw: String?): String {
        val text = raw.orEmpty()
        if (text.contains("Unable to resolve host", true) || text.contains("UnknownHost", true)) {
            return ctx.getString(R.string.th_error_network)
        }
        val notRegistered = text.contains("not registered", true) ||
            text.contains("access_denied", true) ||
            text.contains("User not registered", true)
        if (notRegistered && provider.setupGuide != null && !provider.usesOwnCredentials(ctx)) {
            return ctx.getString(R.string.th_error_dev_mode_limit, provider.displayName)
        }
        return text.ifBlank { ctx.getString(R.string.th_error_generic) }
    }

    /** Completa il login a partire dall'URI di redirect. Ritorna il provider collegato. */
    suspend fun complete(ctx: Context, uri: Uri): MusicProvider {
        val p = prefs(ctx)
        val providerId = p.getString("provider", null) ?: throw AuthException(ctx.getString(com.xlollx.songport.R.string.th_auth_no_pending))
        val verifier = p.getString("verifier", "") ?: ""
        val expectedState = p.getString("state", "") ?: ""
        val provider = Providers.byId(providerId) ?: throw AuthException("unknown provider $providerId")

        // Parametri sia nella query (?code=...) sia nel fragment (#access_token=..., flusso implicito Deezer).
        val params = HashMap<String, String>()
        uri.queryParameterNames.forEach { k -> uri.getQueryParameter(k)?.let { params[k] = it } }
        uri.fragment?.split("&")?.forEach { kv ->
            val i = kv.indexOf('=')
            if (i > 0) params[Uri.decode(kv.substring(0, i))] = Uri.decode(kv.substring(i + 1))
        }

        params["error"]?.let { err ->
            throw AuthException(friendlyError(ctx, provider, params["error_description"] ?: err))
        }
        val gotState = params["state"]
        // Deezer (implicit) non rimanda sempre lo state: lo verifichiamo solo se presente.
        if (gotState != null && gotState != expectedState) {
            throw AuthException(ctx.getString(com.xlollx.songport.R.string.th_auth_state_mismatch))
        }
        provider.completeAuth(ctx, params, verifier)
        p.edit().clear().apply()
        return provider
    }
}

class AuthException(message: String) : Exception(message)
