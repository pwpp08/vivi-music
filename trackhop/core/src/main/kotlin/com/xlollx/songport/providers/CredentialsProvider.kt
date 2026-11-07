package com.xlollx.songport.providers

import com.xlollx.songport.platform.AuthMode
import com.xlollx.songport.platform.Env
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.ProviderException

/** Campi richiesti dalla schermata di accesso di un servizio "a credenziali" (server personali, username). */
data class LoginForm(
    val needsUrl: Boolean,
    val needsUser: Boolean,
    val needsSecret: Boolean,
    /** Names of strings in strings.xml. */
    val secretLabelName: String = "login_password",
    val hintName: String,
    /** The secret field is shown but may stay empty (a token that only some features need). */
    val secretOptional: Boolean = false,
)

/**
 * Servizi senza OAuth: server personali (Subsonic/Navidrome, Jellyfin, Plex) e sorgenti che si
 * leggono con il solo nome utente (Last.fm, ListenBrainz). Le credenziali restano cifrate sul
 * telefono come i token OAuth e l'app parla soltanto con il server indicato dall'utente.
 */
abstract class CredentialsProvider : MusicProvider {
    abstract val loginForm: LoginForm

    /** Verifica le credenziali contro il servizio e, se valide, le salva. Lancia se non valide. */
    abstract suspend fun login(ctx: Env, url: String, user: String, secret: String)

    override fun isConfigured(ctx: Env) = true
    override fun isConnected(ctx: Env) = ctx.tokens.get(id) != null
    override fun accountName(ctx: Env): String? =
        ctx.tokens.get(id)?.let { t -> t.userName.ifBlank { null } ?: t.extra["url"]?.removePrefix("https://")?.removePrefix("http://") }

    override fun startAuth(ctx: Env) {
        ctx.startAuth(this, AuthMode.CREDENTIALS)
    }

    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) = Unit
    override fun disconnect(ctx: Env) = ctx.tokens.clear(id)

    protected fun creds(ctx: Env): Tokens =
        ctx.tokens.get(id) ?: throw ProviderException(ctx.text("error_not_connected", displayName))

    protected fun save(ctx: Env, t: Tokens) = ctx.tokens.set(id, t)

    /** "navidrome.casa.lan:4533" -> "https://navidrome.casa.lan:4533", senza barra finale. */
    protected fun normalizeUrl(url: String): String {
        val u = url.trim().trimEnd('/')
        return if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
    }

    protected fun unsupported(ctx: Env): Nothing =
        throw ProviderException(ctx.text("error_read_only", displayName))
}
