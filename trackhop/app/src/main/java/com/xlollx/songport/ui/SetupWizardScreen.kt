package com.xlollx.songport.ui

import com.xlollx.songport.providers.*
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.R
import com.xlollx.songport.auth.AuthFlow
import com.xlollx.songport.data.Store
import com.xlollx.songport.providers.DeezerProvider
import com.xlollx.songport.providers.MusicProvider

/**
 * Procedura guidata "usa le tue credenziali".
 *
 * Serve perche' i servizi contano la quota sul client ID dell'app, non su chi fa la richiesta:
 * su Spotify una sola app copre 5 utenti, su YouTube tutti gli utenti si dividono 10.000 unita'
 * al giorno. Registrare la propria app e' gratis e dura due minuti, ma finora era un campo di
 * testo nudo in fondo alle impostazioni: qui diventa una procedura con i passi, il link al
 * portale e il redirect da copiare con un tocco.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizardScreen(provider: MusicProvider, onClose: () -> Unit) {
    val guide = provider.setupGuide ?: return
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    val clipboard = LocalClipboardManager.current
    ScreenBackHandler(onClose)

    val settings = store.data.settings
    var clientId by remember { mutableStateOf(settings.clientIds[provider.serviceId].orEmpty()) }
    var secret by remember { mutableStateOf(settings.clientSecrets[provider.serviceId].orEmpty()) }
    var redirectUrl by remember { mutableStateOf(settings.deezerRedirectUrl.ifBlank { BuildConfig.DEEZER_REDIRECT_URL }) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    fun save(): Boolean {
        val id = clientId.trim()
        val sec = secret.trim()
        val url = redirectUrl.trim()
        if (id.isEmpty() || (guide.needsSecret && sec.isEmpty()) || (guide.needsRedirectUrl && url.isEmpty())) {
            error = ctx.getString(R.string.th_setup_field_required)
            return false
        }
        store.updateSettings { s ->
            s.copy(
                clientIds = s.clientIds + (provider.serviceId to id),
                clientSecrets = if (guide.needsSecret) s.clientSecrets + (provider.serviceId to sec) else s.clientSecrets,
                deezerRedirectUrl = if (guide.needsRedirectUrl) url else s.deezerRedirectUrl,
            )
        }
        error = null
        return true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.th_setup_title, provider.displayName)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.th_nav_back)) }
                },
                actions = { HelpButton(Helps.fields, Helps.clearKey, label = stringResource(R.string.th_help_keys_title)) },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(guide.whyRes), style = MaterialTheme.typography.bodyMedium)

            OutlinedButton(
                onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(guide.dashboardUrl))) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.OpenInNew, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.th_setup_open_dashboard))
            }

            stringArrayResource(guide.stepsArrayRes).forEachIndexed { i, step ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(24.dp))
                    Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    val link = guide.stepUrls.getOrNull(i)
                    if (link != null) {
                        TextButton(
                            onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) { Text(stringResource(R.string.th_setup_open_step)) }
                    }
                }
            }

            guide.redirectUri?.let { uri ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.th_setup_redirect_label), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                            HelpButton(Helps.redirect)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(uri, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            TextButton(onClick = { clipboard.setText(AnnotatedString(uri)); copied = true }) {
                                Icon(Icons.Filled.ContentCopy, null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(if (copied) R.string.th_setup_copied else R.string.th_setup_copy))
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it; error = null },
                label = { Text(stringResource(guide.fieldLabelRes)) },
                singleLine = !guide.multiline,
                minLines = if (guide.multiline) 3 else 1,
                maxLines = if (guide.multiline) 5 else 1,
                modifier = Modifier.fillMaxWidth(),
            )
            if (guide.needsSecret) {
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it; error = null },
                    label = { Text(stringResource(R.string.th_setup_field_secret)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (guide.needsRedirectUrl) {
                OutlinedTextField(
                    value = redirectUrl,
                    onValueChange = { redirectUrl = it; error = null },
                    label = { Text(stringResource(R.string.th_setup_field_redirect_url)) },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            Button(
                onClick = {
                    if (save()) {
                        AuthFlow.start(ctx, provider)
                        onClose()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.th_setup_save_connect)) }

            OutlinedButton(
                onClick = { if (save()) onClose() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.th_setup_save)) }

            if (provider.usesOwnCredentials(ctx)) {
                TextButton(
                    onClick = {
                        // Cambiando client ID i token esistenti non sono piu' rinnovabili: si scollega.
                        provider.disconnect(ctx)
                        store.updateSettings { s ->
                            s.copy(
                                clientIds = s.clientIds - provider.serviceId,
                                clientSecrets = s.clientSecrets - provider.serviceId,
                                deezerRedirectUrl = if (provider.serviceId == DeezerProvider.SERVICE) "" else s.deezerRedirectUrl,
                            )
                        }
                        onClose()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.th_setup_clear), color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
