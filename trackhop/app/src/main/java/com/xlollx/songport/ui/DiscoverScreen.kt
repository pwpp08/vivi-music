package com.xlollx.songport.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xlollx.songport.R
import com.xlollx.songport.ai.AiClient
import com.xlollx.songport.ai.config
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.importTracks

/**
 * The cards of the Discover tab, each opening its own page. The names are kept stable: a page
 * open survives a rotation by name. [emoji] and [tint] draw the tile's icon; [ai] puts the "AI"
 * tag on the card (it then needs the user's own AI key, see [AiClient]).
 */
internal enum class DiscoverCard(val emoji: String, val tint: Long, val titleRes: Int, val descRes: Int, val ai: Boolean = false) {
    WRAPPED("✨", 0xFF6B4BD8, R.string.th_discover_wrapped_title, R.string.th_discover_wrapped_desc),
    LINK("🔗", 0xFF5B3FD9, R.string.th_discover_link_title, R.string.th_discover_link_desc),
    COMPAT("💞", 0xFFC2185B, R.string.th_tools_compat_title, R.string.th_discover_compat_desc),
    VANISHED("👻", 0xFF00838F, R.string.th_discover_vanished_title, R.string.th_discover_vanished_desc),
    SUBS("💸", 0xFF2E7D32, R.string.th_discover_subs_title, R.string.th_discover_subs_desc),
    POSTER("🖼️", 0xFFF9A825, R.string.th_tools_poster_title, R.string.th_discover_poster_desc),
}

/**
 * Discover ("Scopri"): playful things to do with one's own music and to share. Everything is
 * computed on the phone from what the app reads from the user's services or already keeps
 * (backups, sync history); nothing leaves it unless the user shares a card or a link. One hero
 * card (the Wrapped) and a two-column grid; each card opens its page, with its own top bar, like
 * a tool in Tools.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    snackbar: SnackbarHostState,
    compareLinks: Pair<String, String>? = null,
    onCompareClosed: () -> Unit = {},
    onSubpage: (Boolean) -> Unit = {},
    onGoToAccounts: () -> Unit = {},
    onBackup: () -> Unit = {},
    onNewSyncFromFile: (name: String, fileId: String) -> Unit = { _, _ -> },
    /** Grows when the Discover tab is tapped again while it is open: back to the list of cards. */
    homeRequests: Int = 0,
    /** Asked from elsewhere (an idea on the Syncs tab): the Wrapped opens, once. */
    openWrapped: Boolean = false,
    onWrappedOpened: () -> Unit = {},
) {
    val ctx = LocalContext.current
    var open by rememberSaveable { mutableStateOf<DiscoverCard?>(null) }
    LaunchedEffect(openWrapped) { if (openWrapped) { open = DiscoverCard.WRAPPED; onWrappedOpened() } }
    val listScroll = rememberScrollState()
    // Two playlist links shared together open the comparison straight away.
    LaunchedEffect(compareLinks) { if (compareLinks != null) open = DiscoverCard.COMPAT }
    val current = open
    fun close() { if (open == DiscoverCard.COMPAT) onCompareClosed(); open = null }
    LaunchedEffect(homeRequests) { if (homeRequests > 0 && open != null) { close(); listScroll.scrollTo(0) } }
    DisposableEffect(current != null) {
        onSubpage(current != null)
        onDispose { onSubpage(false) }
    }
    // The user's AI, read again whenever the list shows (it may have been set up in Tools meanwhile);
    // without a key, Gemini Nano when the phone has it.
    val saved = remember(current) { AiClient.config(ctx)?.takeIf { it.complete } }
    val ai = AiClient.pick(saved, com.xlollx.songport.ai.NanoAi.state, com.xlollx.songport.ai.NanoAi.NAME)

    if (current != null) {
        val local = remember { SnackbarHostState() }
        BackHandler { close() }
        Scaffold(
            // Above the glass bottom bar, which the page runs under: a snackbar there would be hidden by it.
            snackbarHost = { SnackbarHost(local, Modifier.padding(bottom = LocalBottomBarInset.current)) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(current.titleRes)) },
                    navigationIcon = { IconButton(onClick = { close() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.th_nav_back)) } },
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )
            },
        ) { padding ->
            Column(
                Modifier.padding(padding).verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + LocalBottomBarInset.current),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (current) {
                    DiscoverCard.WRAPPED -> WrappedPage(local, onGoToAccounts)
                    DiscoverCard.LINK -> UniversalLinkPage(local)
                    DiscoverCard.COMPAT -> CompatibilityCard(local, compareLinks) { name, tracks ->
                        // The songs in common become a file playlist, the source of a new sync to any service.
                        val id = LocalFilesProvider.importTracks(ctx, name, tracks)
                        onNewSyncFromFile(name, id)
                    }
                    DiscoverCard.VANISHED -> VanishedPage(onBackup)
                    DiscoverCard.SUBS -> SubscriptionsPage(local, onGoToAccounts)
                    DiscoverCard.POSTER -> PosterCard(local)
                }
            }
        }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(listScroll)
            .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 24.dp + LocalBottomBarInset.current),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.th_discover_subtitle), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
        )
        WrappedHero { open = DiscoverCard.WRAPPED }
        val cards = DiscoverCard.entries.filter { it != DiscoverCard.WRAPPED }
        for (row in cards.chunked(2)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (card in row) {
                    // No AI at all: the card still opens, on Oli's verdict and the way to an AI (OliRoast).
                    val noAi = card.ai && ai == null
                    val disabledWhy = if (noAi) stringResource(R.string.th_discover_roast_no_ai_tap) else null
                    DiscoverTile(card, disabledWhy, Modifier.weight(1f).fillMaxHeight()) { open = card }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** The Wrapped, on top: a gradient card with its button. */
@Composable
private fun WrappedHero(onOpen: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)))
            // The whole card opens the Wrapped, not only its button.
            .clickable(onClick = onOpen)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.th_discover_wrapped_title), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.th_discover_wrapped_desc), color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.92f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onPrimary, contentColor = MaterialTheme.colorScheme.primary),
        ) { Text(stringResource(R.string.th_discover_wrapped_button), fontWeight = FontWeight.SemiBold) }
    }
}

/** One card of the grid: its emoji on a tinted tile, name, one line of what it does; dimmed with the reason when it cannot be used yet (still tappable). */
@Composable
private fun DiscoverTile(card: DiscoverCard, disabledWhy: String?, modifier: Modifier, onClick: () -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val enabled = disabledWhy == null
    Card(
        onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer, disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp).fillMaxWidth()) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.45f else 0.16f)),
                    contentAlignment = Alignment.Center,
                ) { Text(card.emoji, fontSize = 20.sp) }
                Spacer(Modifier.height(8.dp))
                val alpha = if (enabled) 1f else 0.6f
                Text(stringResource(card.titleRes), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                Spacer(Modifier.height(3.dp))
                Text(disabledWhy ?: stringResource(card.descRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            }
            if (card.ai) StatusPill(stringResource(R.string.th_discover_ai_tag), Tone.Accent, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp))
        }
    }
}

/** The system share sheet for a piece of text (a link, a roast). */
internal fun shareText(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
