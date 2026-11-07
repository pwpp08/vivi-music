package com.xlollx.songport.ytmbridge

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The Amazon Music session: cookies of the login completed in the WebView plus the regional domain
 * the account lives on (music.amazon.it, music.amazon.de, ...). Encrypted with the Android Keystore,
 * never handed to Trackhop.
 */
object AmazonSession {
    @Volatile private var cached: SharedPreferences? = null

    private fun prefs(ctx: Context): SharedPreferences = cached ?: synchronized(this) {
        cached ?: open(ctx.applicationContext).also { cached = it }
    }

    private fun open(ctx: Context): SharedPreferences = try {
        val key = MasterKey.Builder(ctx, "amazonbridge_master").setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "amazon_session", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        ctx.getSharedPreferences("amazon_session_plain", Context.MODE_PRIVATE)
    }

    fun cookies(ctx: Context): String? = prefs(ctx).getString("cookies", null)
    fun domain(ctx: Context): String? = prefs(ctx).getString("domain", null)
    fun account(ctx: Context): String? = prefs(ctx).getString("account", null)
    fun isConnected(ctx: Context): Boolean = cookies(ctx) != null && domain(ctx) != null

    fun save(ctx: Context, cookies: String, domain: String, account: String?) {
        prefs(ctx).edit().putString("cookies", cookies).putString("domain", domain).putString("account", account ?: account(ctx)).apply()
    }

    /** Display name of the customer, when the player's configuration revealed it. */
    fun saveAccount(ctx: Context, account: String) {
        prefs(ctx).edit().putString("account", account).apply()
    }

    /**
     * The API headers of the player, captured while it was running, kept encrypted next to the
     * session. They carry an access token, so they live here and are never handed to Trackhop.
     */
    fun saveHeaders(ctx: Context, headers: String, userAgent: String?, host: String?) {
        prefs(ctx).edit()
            .putString("headers", headers)
            .putString("headers_ua", userAgent)
            .putString("headers_host", host?.takeIf { AmazonClient.isMusicDomain(it) })
            .putLong("headers_at", System.currentTimeMillis())
            .apply()
    }

    /**
     * The host the player sends its API calls to, when it is not `gql.<player domain>`: the "Rest of
     * Europe" accounts (Netherlands, Belgium…) use music.amazon.com with an EU-side API host.
     */
    fun saveGqlHost(ctx: Context, host: String) {
        if (!AmazonClient.isGqlHost(host)) return
        if (prefs(ctx).getString("gql_host", null) != host) BridgeNet.log("amazon", "the player's API host is $host")
        prefs(ctx).edit().putString("gql_host", host).apply()
    }
    fun gqlHost(ctx: Context): String? = prefs(ctx).getString("gql_host", null)?.takeIf { AmazonClient.isGqlHost(it) }

    /**
     * What one of the player's skill API calls showed (issue #35): the host its library calls go to,
     * its `x-amzn-*` headers (they carry the access token: kept here, encrypted, never logged) and its
     * userHash. A null leaves the stored value as it is.
     */
    fun saveSkill(ctx: Context, host: String?, headers: String?, userHash: String?, userAgent: String?) {
        val e = prefs(ctx).edit()
        if (host != null && AmazonSkillClient.isSkillHost(host)) e.putString("skill_host", host.lowercase())
        if (headers != null) e.putString("skill_headers", headers).putLong("skill_headers_at", System.currentTimeMillis()).putString("skill_ua", userAgent)
        if (userHash != null) e.putString("skill_user_hash", userHash)
        e.apply()
    }
    fun skillHost(ctx: Context): String? = prefs(ctx).getString("skill_host", null)?.takeIf { AmazonSkillClient.isSkillHost(it) }
    fun skillHeaders(ctx: Context): String? = prefs(ctx).getString("skill_headers", null)
    fun skillHeadersAt(ctx: Context): Long = prefs(ctx).getLong("skill_headers_at", 0)
    fun skillUserHash(ctx: Context): String? = prefs(ctx).getString("skill_user_hash", null)
    fun skillUserAgent(ctx: Context): String? = prefs(ctx).getString("skill_ua", null)
    /** The player was seen calling GraphQL on this install (its host or its headers were kept). */
    fun gqlSeen(ctx: Context): Boolean = prefs(ctx).getString("gql_host", null) != null || headers(ctx) != null
    /** The player's skill host and credentials were seen: the skill API can be tried. */
    fun skillKnown(ctx: Context): Boolean = skillHost(ctx) != null && skillHeaders(ctx) != null

    /** The API this account's player uses ([AmazonRoute.SKILL] or [AmazonRoute.GQL]); null until it was seen. */
    fun api(ctx: Context): String? = prefs(ctx).getString("api", null)

    /** Keeps the account's API; a change is logged once, when it happens. */
    @Synchronized
    fun setApi(ctx: Context, api: String) {
        val was = api(ctx)
        if (api == was) return
        prefs(ctx).edit().putString("api", api).commit()
        if (api == AmazonRoute.SKILL) {
            val host = skillHost(ctx) ?: "its skill host"
            BridgeNet.log("amazon", "Amazon Music: this account uses the ${if (host.startsWith("eu.")) "European " else ""}skill API ($host)")
        } else if (was == AmazonRoute.SKILL) {
            BridgeNet.log("amazon", "Amazon Music: this account's player uses GraphQL again")
        }
    }

    fun headers(ctx: Context): String? = prefs(ctx).getString("headers", null)
    fun headersUserAgent(ctx: Context): String? = prefs(ctx).getString("headers_ua", null)
    /** The player page the headers were seen on: the account's real regional site. */
    fun headersHost(ctx: Context): String? = prefs(ctx).getString("headers_host", null)
    fun headersAt(ctx: Context): Long = prefs(ctx).getLong("headers_at", 0)

    /** Forgets the session here and the Amazon cookies in the WebView. */
    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        AmazonClient.forgetConfig()
        AmazonBridge.forget()
        val n = wipeCookies()
        BridgeNet.log("amazon", "disconnected: session forgotten, $n Amazon cookies removed")
    }

    /**
     * Removes every Amazon cookie from the WebView jar, and only those (the YouTube Music and
     * Spotify sessions must survive). A cookie lives on a host or on a domain, so each name seen is
     * expired in every form it can have: host-only on the player, its API host (gql.), the store and
     * the bare domain, and set on the domain or the music subdomain. A copy left behind is sent next
     * to the new one after signing in again, and Amazon then refuses both. Returns how many it saw.
     */
    fun wipeCookies(): Int = runCatching {
        val cm = CookieManager.getInstance()
        var seen = 0
        val bases = (AmazonClient.DOMAINS.keys.map { it.removePrefix("music.") } + "amazon.com").distinct()
        for (base in bases) {
            val hosts = listOf("music.$base", "gql.music.$base", "www.$base", base)
            val names = hosts.flatMap { h -> cm.getCookie("https://$h").orEmpty().split(';').map { it.substringBefore('=').trim() } }
                .filter { it.isNotEmpty() }.distinct()
            seen += names.size
            for (h in hosts) for (name in names) {
                cm.setCookie("https://$h", "$name=; Max-Age=0; Path=/")
                cm.setCookie("https://$h", "$name=; Max-Age=0; Domain=.$base; Path=/")
                cm.setCookie("https://$h", "$name=; Max-Age=0; Domain=.music.$base; Path=/")
            }
        }
        cm.flush()
        seen
    }.getOrDefault(0)

    /** Cookie names the jar would send twice to [url] (stale copies next to fresh ones), for diagnostics. */
    fun duplicateCookies(url: String): List<String> = runCatching {
        CookieManager.getInstance().getCookie(url).orEmpty().split(';').map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
    }.getOrDefault(emptyList())

    /** True when the cookie jar holds an Amazon authentication token (at-main, at-acbit, sess-at-*, ...). */
    fun looksSignedIn(cookies: String?): Boolean = cookies != null && cookies.split(';').any {
        val c = it.trim()
        (c.startsWith("at-") || c.startsWith("sess-at-")) && c.substringAfter('=', "").trim().length > 20
    }
}
