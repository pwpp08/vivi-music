package com.xlollx.songport.auth

import com.xlollx.songport.providers.*
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import kotlinx.coroutines.launch

/** Riceve il redirect OAuth (songport://callback...) e scambia il codice con i token. */
class AuthCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.th_auth_wait))
                }
            }
        }
        val uri = intent?.data
        lifecycleScope.launch {
            val message = if (uri == null) getString(R.string.th_auth_no_pending) else try {
                val provider = AuthFlow.complete(this@AuthCallbackActivity, uri)
                getString(R.string.th_auth_done, provider.displayName)
            } catch (e: Exception) {
                getString(R.string.th_auth_failed, e.message ?: e.javaClass.simpleName)
            }
            val back = Intent(this@AuthCallbackActivity, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(MainActivity.EXTRA_MESSAGE, message)
                putExtra(MainActivity.EXTRA_TAB, 1)
            }
            startActivity(back)
            finish()
        }
    }
}
