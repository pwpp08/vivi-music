package com.xlollx.songport.auth

import com.xlollx.songport.providers.*
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/** PKCE (RFC 7636): l'app mobile non ha un client secret, usa verifier + challenge S256. */
object Pkce {
    private val rnd = SecureRandom()

    fun verifier(): String = randomUrlSafe(64)
    fun state(): String = randomUrlSafe(24)

    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes); rnd.nextBytes(buf)
        return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
