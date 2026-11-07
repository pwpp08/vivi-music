package com.xlollx.songport.ytmbridge

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * DataDome, the bot protection in front of soundcloud.com. When it distrusts a visitor it does not
 * answer with the page but with its own, which loads `geo.captcha-delivery.com/captcha/?…&t=…` in
 * a frame: `t=fe` is a puzzle the person can solve, `t=bv` is "You have been blocked", with nothing
 * to solve. An API call it refuses gets a 403 whose body carries the same address.
 *
 * Pure functions only: the sign-in screen watches the addresses its page loads, without changing
 * any of them, and the API client reads the bodies it receives.
 */
object DataDome {
    enum class Kind {
        /** "You have been blocked": nothing to do on the page but wait. */
        BLOCKED,
        /** A puzzle in the page: the person solves it there, as in any browser. */
        CAPTCHA,
        /** The "checking your browser" page, which goes on by itself. */
        CHECK,
    }

    private fun isDataDomeHost(host: String) = host == "captcha-delivery.com" || host.endsWith(".captcha-delivery.com")

    /** What a request to DataDome's own pages means; null for any other address (its scripts and images included). */
    fun kindOf(url: String): Kind? {
        val u = url.toHttpUrlOrNull() ?: return null
        if (!isDataDomeHost(u.host)) return null
        val path = u.encodedPath
        return when {
            path.startsWith("/captcha") -> when (u.queryParameter("t")) {
                "bv" -> Kind.BLOCKED
                "fe" -> Kind.CAPTCHA
                else -> null
            }
            path.startsWith("/interstitial") -> Kind.CHECK
            else -> null
        }
    }

    private val BLOCKED_PARAM = Regex("""(?:[?&]|\\u0026)t=bv\b|['"]t['"]\s*:\s*['"]bv['"]""")

    /**
     * What a refused response is, from its body: DataDome's JSON (`{"url":"https://geo.captcha-delivery.com/captcha/?…"}`)
     * or its HTML page (`var dd={…'t':'bv',…}`); null when the body is not DataDome's, an ordinary 403 for instance.
     */
    fun kindOfBody(body: String): Kind? {
        if (!body.contains("captcha-delivery.com")) return null
        return if (BLOCKED_PARAM.containsMatchIn(body)) Kind.BLOCKED else Kind.CAPTCHA
    }
}
