package com.xlollx.songport.auth

import com.xlollx.songport.providers.*
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.xlollx.songport.R

/**
 * Sblocco con impronta, volto o PIN del telefono. Vive in una FragmentActivity separata perche'
 * BiometricPrompt la richiede, mentre il resto dell'app usa ComponentActivity + Compose.
 */
class LockActivity : FragmentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.xlollx.songport.ui.AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                setResult(RESULT_OK); finish()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                setResult(RESULT_CANCELED); finish()
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.th_lock_title))
            .setSubtitle(getString(R.string.th_lock_subtitle))
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }

    companion object {
        const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

        fun available(ctx: android.content.Context): Boolean =
            BiometricManager.from(ctx).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    }
}
