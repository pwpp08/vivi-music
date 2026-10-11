/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.ui.screens.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.music.vivi.LocalPlayerAwareWindowInsets
import com.music.vivi.R
import com.music.vivi.ui.component.DefaultDialog
import com.music.vivi.ui.component.IconButton
import com.music.vivi.ui.utils.backToMain
import com.music.vivi.viewmodels.SpotifyImportViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: SpotifyImportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var linkInput by remember { mutableStateOf("") }
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val isImportMinimized by viewModel.isImportMinimized.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Feature description card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.spotify),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.import_from_spotify),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.spotify_import_by_link_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Link input section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.spotify_paste_link_dialog_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )

                OutlinedTextField(
                    value = linkInput,
                    onValueChange = {
                        linkInput = it
                        if (errorMessage != null) viewModel.dismissError()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.spotify_paste_link_hint),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.spotify),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingIcon = {
                        if (linkInput.isNotBlank()) {
                            IconButton(onClick = { linkInput = "" }) {
                                Icon(
                                    painter = painterResource(R.drawable.close),
                                    contentDescription = stringResource(android.R.string.cancel),
                                )
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                                    if (!clip.isNullOrBlank()) {
                                        linkInput = clip
                                    }
                                }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.link),
                                    contentDescription = "Paste",
                                )
                            }
                        }
                    },
                    singleLine = true,
                    enabled = !isImporting,
                    isError = errorMessage != null,
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Button(
                    onClick = {
                        val input = linkInput.trim()
                        if (input.isNotBlank()) {
                            viewModel.importPlaylistByUrl(input)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = linkInput.isNotBlank() && !isImporting,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.spotify_import_in_progress))
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.playlist_add),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.import_from_spotify))
                    }
                }
            }
        }

        // Inline import progress card
        AnimatedVisibility(
            visible = importProgress != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            val progress = importProgress
            if (progress != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (progress.isFinished) {
                                    stringResource(R.string.spotify_import_complete)
                                } else {
                                    stringResource(R.string.spotify_import_in_progress)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (progress.isFinished) {
                                Icon(
                                    painter = painterResource(R.drawable.check),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }

                        if (progress.playlistName.isNotBlank()) {
                            Text(
                                text = progress.playlistName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        LinearProgressIndicator(
                            progress = { progress.percent },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                        )

                        Text(
                            text = if (progress.isFinished) {
                                stringResource(
                                    R.string.spotify_imported_songs_count,
                                    progress.currentSongIndex,
                                    progress.totalSongs,
                                )
                            } else {
                                stringResource(
                                    R.string.spotify_importing_songs_count,
                                    progress.currentSongIndex,
                                    progress.totalSongs,
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            if (progress.isFinished) {
                                Button(
                                    onClick = { viewModel.dismissImportProgress() },
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text(stringResource(android.R.string.ok))
                                }
                            } else {
                                TextButton(onClick = { viewModel.cancelImport() }) {
                                    Text(stringResource(android.R.string.cancel))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal progress dialog if active and not minimized
    if (importProgress != null && !isImportMinimized) {
        val progress = importProgress!!
        DefaultDialog(
            onDismiss = {
                if (progress.isFinished) {
                    viewModel.dismissImportProgress()
                } else {
                    viewModel.minimizeImport()
                }
            },
            title = {
                Text(
                    text = if (progress.isFinished) {
                        stringResource(R.string.spotify_import_complete)
                    } else {
                        stringResource(R.string.spotify_import_in_progress)
                    }
                )
            },
            buttons = {
                if (progress.isFinished) {
                    Button(
                        onClick = { viewModel.dismissImportProgress() },
                        shape = CircleShape,
                    ) {
                        Text(stringResource(android.R.string.ok))
                    }
                } else {
                    TextButton(onClick = { viewModel.minimizeImport() }) {
                        Text(stringResource(R.string.run_in_background))
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { viewModel.cancelImport() }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (progress.playlistName.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.playlists) + ": " + progress.playlistName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = if (progress.isFinished) {
                        stringResource(
                            R.string.spotify_imported_songs_count,
                            progress.currentSongIndex,
                            progress.totalSongs,
                        )
                    } else {
                        stringResource(
                            R.string.spotify_importing_songs_count,
                            progress.currentSongIndex,
                            progress.totalSongs,
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { progress.percent },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.import_from_spotify)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
        scrollBehavior = scrollBehavior,
    )
}
