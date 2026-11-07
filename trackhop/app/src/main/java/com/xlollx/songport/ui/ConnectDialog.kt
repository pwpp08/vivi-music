package com.xlollx.songport.ui

import com.xlollx.songport.providers.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.providers.AppleMusicProvider
import com.xlollx.songport.providers.CredentialsProvider
import com.xlollx.songport.providers.MusicProvider

/**
 * Cosa succede quando premi "Collega", detto prima di premerlo. L'utente deve sapere dove sta
 * mettendo la password (sul sito del servizio, non qui), cosa riceviamo (un permesso revocabile),
 * dove finisce (cifrato nel telefono, nessun server) e come tornare indietro.
 */
@Composable
fun ConnectDialog(provider: MusicProvider, onDismiss: () -> Unit, onContinue: () -> Unit) {
    val isServer = provider is CredentialsProvider
    val isApple = provider.serviceId == AppleMusicProvider.SERVICE
    // Web routes host the service's own sign-in page in the app, not in the browser: said as it is.
    val isWeb = provider.routeNoteRes == R.string.th_route_note_web || provider.routeNoteRes == R.string.th_route_note_web_only
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.th_connect_title, provider.displayName)) },
        text = {
            Column {
                Fact(
                    Icons.Filled.Public,
                    when {
                        isServer -> stringResource(R.string.th_connect_where_server)
                        isApple -> stringResource(R.string.th_connect_where_apple)
                        isWeb -> stringResource(R.string.th_connect_where_web, provider.authDomain ?: provider.displayName)
                        else -> stringResource(R.string.th_connect_where_oauth, provider.authDomain ?: provider.displayName)
                    },
                )
                if (provider.slot.isNotEmpty()) Fact(Icons.Filled.SwitchAccount, stringResource(R.string.th_connect_switch_account, provider.displayName))
                Fact(Icons.Filled.Lock, stringResource(R.string.th_connect_token))
                Fact(Icons.Filled.PhoneAndroid, stringResource(R.string.th_connect_noserver, provider.displayName))
                Fact(Icons.Filled.Undo, stringResource(R.string.th_connect_revoke, provider.displayName))
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(if (isServer || isApple) R.string.th_connect_continue else R.string.th_connect_continue_browser))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.th_cancel)) } },
    )
}

@Composable
private fun Fact(icon: ImageVector, text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
