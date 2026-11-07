package com.xlollx.songport.data

import kotlinx.serialization.Serializable

@Serializable
data class Tokens(
    val accessToken: String,
    val refreshToken: String? = null,
    /** Scadenza (epoch ms); 0 = non scade / sconosciuta. */
    val expiresAt: Long = 0,
    val userId: String = "",
    val userName: String = "",
    val extra: Map<String, String> = emptyMap(),
) {
    fun isExpiringSoon(): Boolean = expiresAt > 0 && System.currentTimeMillis() > expiresAt - 60_000
}

/** Where access tokens and credentials are kept, per connector id. */
interface TokenVault {
    fun get(providerId: String): Tokens?
    fun set(providerId: String, tokens: Tokens)
    fun clear(providerId: String)
}
