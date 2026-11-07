package com.xlollx.songport.auth

import com.xlollx.songport.providers.*
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.providers.CredentialsProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.ui.AppTheme
import kotlinx.coroutines.launch

/**
 * Accesso ai servizi "a credenziali": server personali (indirizzo + utente + password o token)
 * e sorgenti che chiedono solo il nome utente. Le credenziali vengono verificate con una
 * chiamata reale prima di essere salvate, cifrate, nel telefono.
 */
class ServerLoginActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.xlollx.songport.ui.AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val provider = intent.getStringExtra(EXTRA_PROVIDER)?.let { Providers.byId(it) as? CredentialsProvider }
        if (provider == null) { finish(); return }
        setContent { AppTheme { LoginForm(provider) { message -> finishWith(message) } } }
    }

    private fun finishWith(message: String) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                // Back without signing in says nothing (an empty message showed as an empty banner).
                if (message.isNotBlank()) putExtra(MainActivity.EXTRA_MESSAGE, message)
                putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_ACCOUNTS)
            }
        )
        finish()
    }

    companion object { const val EXTRA_PROVIDER = "provider" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginForm(provider: CredentialsProvider, onDone: (String) -> Unit) {
    val form = provider.loginForm
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Jellyfin Quick Connect (#45): the code to approve on a signed-in device, while waiting.
    var qcCode by remember { mutableStateOf<String?>(null) }
    var qcJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val jellyfin = (provider as? JellyfinProvider)?.takeIf { it.supportsQuickConnect }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.th_login_title, provider.displayName)) },
                navigationIcon = { IconButton(onClick = { onDone("") }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.th_nav_back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(form.hintRes), style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.th_login_trust_server), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (form.needsUrl) OutlinedTextField(
                value = url, onValueChange = { url = it; error = null }, singleLine = true, enabled = !busy,
                label = { Text(stringResource(R.string.th_login_url)) }, placeholder = { Text("https://…") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
            )
            if (form.needsUser) OutlinedTextField(
                value = user, onValueChange = { user = it; error = null }, singleLine = true, enabled = !busy,
                label = { Text(stringResource(R.string.th_login_user)) }, modifier = Modifier.fillMaxWidth(),
            )
            if (form.needsSecret) OutlinedTextField(
                value = secret, onValueChange = { secret = it; error = null }, singleLine = true, enabled = !busy,
                label = { Text(stringResource(form.secretLabelRes)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(
                enabled = !busy && (!form.needsUrl || url.isNotBlank()) && (!form.needsUser || user.isNotBlank()) && (!form.needsSecret || form.secretOptional || secret.isNotBlank()),
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            provider.login(ctx, url, user, secret)
                            onDone(ctx.getString(R.string.th_auth_done, provider.displayName))
                        } catch (e: Exception) {
                            error = e.message ?: ctx.getString(R.string.th_error_generic)
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) { CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.th_login_checking)) }
                else Text(stringResource(R.string.th_login_submit))
            }
            if (jellyfin != null) {
                androidx.compose.material3.OutlinedButton(
                    enabled = !busy && url.isNotBlank(),
                    onClick = {
                        busy = true; error = null
                        qcJob = scope.launch {
                            try {
                                val qc = jellyfin.quickConnectStart(com.xlollx.songport.platform.AndroidEnv.of(ctx), url)
                                qcCode = qc.code
                                // Up to 5 minutes, every 3 s: the server forgets the code after a while anyway.
                                var ok = false
                                for (i in 0 until 100) {
                                    kotlinx.coroutines.delay(3000)
                                    if (jellyfin.quickConnectPoll(com.xlollx.songport.platform.AndroidEnv.of(ctx), qc)) { ok = true; break }
                                }
                                qcCode = null
                                if (ok) onDone(ctx.getString(R.string.th_auth_done, provider.displayName))
                                else { error = ctx.getString(R.string.th_jellyfin_qc_expired); busy = false }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                qcCode = null
                                error = e.message ?: ctx.getString(R.string.th_error_generic)
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.th_login_qc_button)) }
                Text(stringResource(R.string.th_login_qc_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    qcCode?.let { code ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.th_login_qc_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.th_login_qc_body))
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(code, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.th_login_qc_waiting), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { qcJob?.cancel(); qcCode = null; busy = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
