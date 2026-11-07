package com.xlollx.songport.data

/**
 * Takes secrets out of a technical report before it leaves the device (Export log, Share technical
 * details, Report on GitHub): tokens, cookies, passwords, sign-in parameters in addresses, e-mail
 * addresses and the addresses of the user's own servers. The log is written not to hold them, but a
 * service's raw answer or a URL can carry one: this is the net under it.
 */
object Redact {
    private const val HIDDEN = "[hidden]"

    private val bearer = Regex("""(?i)\b(Bearer|Basic|MediaBrowser[^"\n]*?Token=|Token)\s*[:=]?\s*"?[A-Za-z0-9._~+/=-]{12,}"?""")
    private val jwt = Regex("""eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{4,}""")
    // A URL's sign-in parameters (Subsonic's u/t/s/p, Plex's token, keys, codes, signatures).
    private val query = Regex("""(?i)([?&](?:access_token|refresh_token|id_token|token|api_key|apikey|key|client_secret|secret|code|password|pw|sig|signature|auth|session|sid|x-plex-token|t|s|p|u)=)[^&\s"'<>]+""")
    // JSON fields and header-like lines that hold a secret.
    private val field = Regex("""(?i)("?(?:access_?token|refresh_?token|id_?token|token|password|pw|secret|client_?secret|api_?key|cookie|authorization|session(?:_?token)?|sp_dc|arl|media-user-token|x-plex-token)"?\s*[:=]\s*)("[^"]*"|[^\s,;}&]+)""")
    // Cookie pairs of the services' sign-ins.
    private val cookie = Regex("""(?i)\b(sp_dc|sp_key|sp_t|media-user-token|myacinfo|SAPISID|SID|HSID|SSID|APISID|__Secure-[\w-]+|LOGIN_INFO|at-main|sess-at-main|ubid-main|session-id|session-token|x-main|arl|sid|oauth_token|PHPSESSID)=([^;\s"']+)""")
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    // Addresses on a home network: private IPs and local names.
    private val localHost = Regex("""(?i)\b(?:https?://)?(?:(?:10|127)\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}|[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:local|lan|home|internal|localdomain|home\.arpa))(?::\d{2,5})?""")

    /** [text] without its secrets; [servers] are the user's own server addresses, hidden wherever they appear. */
    fun text(text: String, servers: Collection<String> = emptyList()): String {
        var t = text
        servers.map { it.trim().trimEnd('/') }.filter { it.length > 6 }.sortedByDescending { it.length }.forEach { s ->
            t = t.replace(s, "[server]", ignoreCase = true)
            val bare = s.substringAfter("://")
            if (bare.length > 6) t = t.replace(bare, "[server]", ignoreCase = true)
        }
        t = jwt.replace(t, HIDDEN)
        t = bearer.replace(t) { m -> m.groupValues[1] + " " + HIDDEN }
        t = query.replace(t) { m -> m.groupValues[1] + HIDDEN }
        t = cookie.replace(t) { m -> m.groupValues[1] + "=" + HIDDEN }
        t = field.replace(t) { m -> m.groupValues[1] + if (m.groupValues[2].startsWith("\"")) "\"$HIDDEN\"" else HIDDEN }
        t = email.replace(t, "[email]")
        t = localHost.replace(t, "[server]")
        return t
    }
}
