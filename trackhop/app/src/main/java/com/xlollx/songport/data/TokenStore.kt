package com.xlollx.songport.data

import com.xlollx.songport.providers.*
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.xlollx.songport.net.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString


/**
 * Token OAuth per servizio, cifrati con l'Android Keystore (EncryptedSharedPreferences).
 * Se la cifratura non e' disponibile sul dispositivo si ricade su SharedPreferences normali.
 */
class TokenStore(context: Context) : TokenVault {
    // Aprire le preferenze cifrate costa (chiave nel Keystore, decifratura dell'indice): si fa una
    // volta per processo, altrimenti ogni ridisegno delle schermate che mostrano "collegato" rallenta.
    private val sp: SharedPreferences = shared(context)

    companion object {
        @Volatile private var cached: SharedPreferences? = null

        private fun shared(context: Context): SharedPreferences = cached ?: synchronized(this) {
            cached ?: open(context.applicationContext).also { cached = it }
        }

        private fun open(context: Context): SharedPreferences = try {
            val key = MasterKey.Builder(context, "songport_master")
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                context, "tokens", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            context.getSharedPreferences("tokens_plain", Context.MODE_PRIVATE)
        }
    }

    override fun get(providerId: String): Tokens? =
        sp.getString(providerId, null)?.let { runCatching { json.decodeFromString<Tokens>(it) }.getOrNull() }

    override fun set(providerId: String, tokens: Tokens) {
        sp.edit().putString(providerId, json.encodeToString(tokens)).apply()
    }

    override fun clear(providerId: String) {
        sp.edit().remove(providerId).apply()
    }
}
