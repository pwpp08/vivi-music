package com.xlollx.songport.providers

import com.xlollx.songport.platform.AuthMode
import com.xlollx.songport.platform.Env
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.HttpResponse
import com.xlollx.songport.net.get
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import kotlinx.serialization.json.JsonElement

/**
 * Base per i servizi con OAuth 2.0 Authorization Code + PKCE (Spotify, Google, TIDAL).
 * Gestisce: URL di autorizzazione, scambio codice, refresh, chiamate API con Bearer e retry su 401.
 */
abstract class OAuthProvider : MusicProvider {
    protected abstract val defaultClientId: String
    protected abstract val authorizeEndpoint: String
    protected abstract val tokenEndpoint: String
    protected abstract val scopes: String
    protected open val extraAuthParams: Map<String, String> = emptyMap()
    /**
     * Parametri aggiunti solo per gli account oltre il primo: il browser e' quasi sempre gia' loggato
     * con il primo account, e senza questi il servizio lo riuserebbe in silenzio.
     */
    protected open val switchAccountParams: Map<String, String> = emptyMap()
    protected open val apiAccept: String = "application/json"
    protected open val apiContentType: String = "application/json"

    protected open fun redirectUri(ctx: Env): String = ctx.redirectUri

    fun clientId(ctx: Env): String =
        ownClientId(ctx) ?: defaultClientId

    /** Client ID inserito dall'utente, se c'e' (per servizio, vale per tutti gli account). */
    fun ownClientId(ctx: Env): String? =
        ctx.store.data.settings.clientIds[serviceId]?.trim()?.takeIf { it.isNotEmpty() }

    /** Client secret inserito dall'utente (solo flussi installed-app, es. Google). */
    fun ownClientSecret(ctx: Env): String? =
        ctx.store.data.settings.clientSecrets[serviceId]?.trim()?.takeIf { it.isNotEmpty() }

    override fun usesOwnCredentials(ctx: Env): Boolean = ownClientId(ctx) != null

    override fun isConfigured(ctx: Env): Boolean = clientId(ctx).isNotBlank()
    override fun isConnected(ctx: Env): Boolean = ctx.tokens.get(id) != null
    override fun accountName(ctx: Env): String? = ctx.tokens.get(id)?.userName?.takeIf { it.isNotBlank() }

    override fun startAuth(ctx: Env) = ctx.startAuth(this, AuthMode.BROWSER)

    /**
     * True where the service's sign-in page may run inside the app (WebAuthActivity): Spotify,
     * Deezer, TIDAL. False for Google, whose sign-in refuses embedded views.
     */
    open val loginInApp: Boolean get() = false

    override val authDomain: String? get() = runCatching { java.net.URI(authorizeEndpoint).host }.getOrNull()

    open fun authUrl(ctx: Env, state: String, codeChallenge: String): String =
        authUrl(ctx, state, codeChallenge, redirectUri(ctx))

    open fun authUrl(ctx: Env, state: String, codeChallenge: String, redirect: String): String {
        val params = mapOf(
            "response_type" to "code",
            "client_id" to clientId(ctx),
            "redirect_uri" to redirect,
            "scope" to scopes,
            "state" to state,
            "code_challenge_method" to "S256",
            "code_challenge" to codeChallenge,
        ) + extraAuthParams + (if (slot.isNotEmpty()) switchAccountParams else emptyMap())
        return authorizeEndpoint + "?" + Http.query(params)
    }

    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) {
        val code = params["code"] ?: throw ProviderException("missing authorization code")
        val resp = Http.send(
            "POST", tokenEndpoint, mapOf("Accept" to "application/json"),
            Http.form(
                buildMap {
                    put("grant_type", "authorization_code")
                    put("code", code)
                    put("redirect_uri", params[PARAM_REDIRECT_URI] ?: redirectUri(ctx))
                    put("client_id", clientId(ctx))
                    put("code_verifier", verifier)
                    ownClientSecret(ctx)?.let { put("client_secret", it) }
                }
            ),
        )
        if (!resp.ok) throw ProviderException("token exchange failed (${resp.code}): ${resp.body.take(200)}")
        val j = parseJson(resp.body)
        var t = Tokens(
            accessToken = j["access_token"].str ?: throw ProviderException("no access_token in response"),
            refreshToken = j["refresh_token"].str,
            expiresAt = expiry(j["expires_in"].long),
        )
        val store = ctx.tokens
        store.set(id, t)
        // A token the service then refuses (Spotify without Premium) must not show as "connected".
        try { t = enrichAccount(ctx, t) } catch (e: Exception) { store.clear(id); throw e }
        store.set(id, t)
    }

    /** Dopo il login: recupera id/nome utente (e altro) da salvare nei token. */
    protected open suspend fun enrichAccount(ctx: Env, t: Tokens): Tokens = t

    override fun disconnect(ctx: Env) = ctx.tokens.clear(id)

    protected open suspend fun tokens(ctx: Env): Tokens {
        val store = ctx.tokens
        var t = store.get(id) ?: throw ProviderException(ctx.text("error_not_connected", displayName))
        if (t.isExpiringSoon() && t.refreshToken != null) {
            t = refresh(ctx, t)
            store.set(id, t)
        }
        return t
    }

    protected open suspend fun refresh(ctx: Env, t: Tokens): Tokens {
        val resp = Http.send(
            "POST", tokenEndpoint, mapOf("Accept" to "application/json"),
            Http.form(
                buildMap {
                    put("grant_type", "refresh_token")
                    put("refresh_token", t.refreshToken ?: "")
                    put("client_id", clientId(ctx))
                    ownClientSecret(ctx)?.let { put("client_secret", it) }
                }
            ),
        )
        if (!resp.ok) {
            // Refresh token revocato: l'utente dovra' ricollegare l'account.
            if (resp.code == 400 || resp.code == 401) ctx.tokens.clear(id)
            throw ProviderException("$displayName: session expired, please reconnect (${resp.code})")
        }
        val j = parseJson(resp.body)
        return t.copy(
            accessToken = j["access_token"].str ?: t.accessToken,
            refreshToken = j["refresh_token"].str ?: t.refreshToken,
            expiresAt = expiry(j["expires_in"].long),
        )
    }

    /** Chiamata API autenticata; su 401 prova un refresh e ripete una volta. */
    protected suspend fun api(
        ctx: Env,
        method: String,
        url: String,
        body: JsonElement? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonElement {
        var t = tokens(ctx)
        var resp = raw(t, method, url, body, extraHeaders)
        if (resp.code == 401 && t.refreshToken != null) {
            t = refresh(ctx, t)
            ctx.tokens.set(id, t)
            resp = raw(t, method, url, body, extraHeaders)
        }
        if (!resp.ok) {
            ctx.log(id, "HTTP ${resp.code} $method ${url.substringBefore('?').take(120)}: ${errorMessage(resp).take(160)}")
            throw ProviderException(friendlyApiError(ctx, resp) ?: "$displayName API ${resp.code}: ${errorMessage(resp)}", code = resp.code)
        }
        return parseJson(resp.body)
    }

    /** Un servizio puo' spiegare un errore ricorrente con parole sue (es. i 403 di Spotify). */
    protected open fun friendlyApiError(ctx: Env, resp: HttpResponse): String? = null

    private suspend fun raw(t: Tokens, method: String, url: String, body: JsonElement?, extra: Map<String, String>): HttpResponse =
        Http.send(
            method, url,
            mapOf("Authorization" to "Bearer ${t.accessToken}", "Accept" to apiAccept) + extra,
            body?.let { Http.jsonBody(it.toString(), apiContentType) },
        )

    protected open fun errorMessage(resp: HttpResponse): String = resp.body.take(300)

    companion object {
        /** Chiave interna: il redirect effettivo usato, quando non e' quello di default (loopback). */
        const val PARAM_REDIRECT_URI = "__redirect_uri"
    }

    protected fun expiry(expiresIn: Long?): Long =
        if (expiresIn == null || expiresIn <= 0) 0 else System.currentTimeMillis() + expiresIn * 1000
}
