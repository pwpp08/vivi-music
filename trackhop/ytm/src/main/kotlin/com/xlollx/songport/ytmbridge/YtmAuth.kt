package com.xlollx.songport.ytmbridge

import java.security.MessageDigest

/** How a YouTube Music session signs its requests: the cookies of a signed-in browser, and a hash of one of them. */
object YtmAuth {
    const val ORIGIN = "https://music.youtube.com"

    /** The cookie that signs requests: SAPISID, or its __Secure-3PAPISID twin. */
    fun sapisid(cookies: String): String? {
        val map = cookies.split(';').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }.toMap()
        return map["SAPISID"] ?: map["__Secure-3PAPISID"]
    }

    /** Authorization header in the form the web player itself sends: SAPISIDHASH ts_sha1("ts sapisid origin"). */
    fun authorization(cookies: String, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
        val sid = sapisid(cookies) ?: throw BridgeException("session has no SAPISID cookie")
        val digest = MessageDigest.getInstance("SHA-1").digest("$nowSeconds $sid $ORIGIN".toByteArray())
        return "SAPISIDHASH ${nowSeconds}_${digest.joinToString("") { "%02x".format(it) }}"
    }
}
