package com.xlollx.songport.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.providers.BridgePlugin

// ---------------------------------------------------------------------------------------------
// How to: short guides, written into the app (string resources, so offline and translated). An
// article is a few string resources in a light markup: a line starting with "# " is a heading,
// one starting with "• " a bullet, any other line a paragraph.
// ---------------------------------------------------------------------------------------------

enum class HowTo(@StringRes val title: Int, @StringRes val summary: Int, val icon: ImageVector) {
    FIRST_SYNC(R.string.th_howto_first_sync_title, R.string.th_howto_first_sync_summary, Icons.Filled.PlayCircle),
    MODES(R.string.th_howto_modes_title, R.string.th_howto_modes_summary, Icons.Filled.SyncAlt),
    SCHEDULES(R.string.th_howto_schedules_title, R.string.th_howto_schedules_summary, Icons.Filled.Schedule),
    PREVIEW(R.string.th_howto_preview_title, R.string.th_howto_preview_summary, Icons.Filled.Visibility),
    REVIEW(R.string.th_howto_review_title, R.string.th_howto_review_summary, Icons.Filled.Search),
    BACKUPS(R.string.th_howto_backups_title, R.string.th_howto_backups_summary, Icons.Filled.Backup),
    TOOLS(R.string.th_howto_tools_title, R.string.th_howto_tools_summary, Icons.Filled.Build),
    ACCOUNTS(R.string.th_howto_accounts_title, R.string.th_howto_accounts_summary, Icons.Filled.AccountCircle),
    PLUGINS(R.string.th_howto_plugins_title, R.string.th_howto_plugins_summary, Icons.Filled.Extension);

    /**
     * The article's text, in order. What differs between the builds is its own part: the Play
     * build describes plugins neutrally (no name, no link), the GitHub/F-Droid build says its
     * web connectors are built in.
     */
    val parts: List<Int>
        get() = when (this) {
            FIRST_SYNC -> listOf(R.string.th_howto_first_sync_body)
            MODES -> listOf(R.string.th_howto_modes_body)
            // The battery note is the long text Settings already has for its row.
            SCHEDULES -> listOf(R.string.th_howto_schedules_body, R.string.th_settings_battery_desc, R.string.th_howto_schedules_body2)
            PREVIEW -> listOf(R.string.th_howto_preview_body)
            REVIEW -> listOf(R.string.th_howto_review_body)
            BACKUPS -> listOf(R.string.th_howto_backups_body)
            TOOLS -> listOf(R.string.th_howto_tools_body)
            ACCOUNTS -> listOfNotNull(
                R.string.th_howto_accounts_body,
                R.string.th_howto_accounts_full.takeIf { BridgePlugin.builtIn },
                R.string.th_howto_accounts_body2,
            )
            PLUGINS -> listOf(R.string.th_howto_plugins_body, if (BridgePlugin.builtIn) R.string.th_howto_plugins_full else R.string.th_howto_plugins_play)
        }
}

/** One article's body: headings, paragraphs and bullets, as the markup of its parts says. */
@Composable
fun HowToArticle(article: HowTo, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        article.parts.forEach { res ->
            stringResource(res).lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                when {
                    line.startsWith("# ") -> Text(
                        line.removePrefix("# "), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                    )
                    line.startsWith("• ") -> Row(Modifier.padding(start = 4.dp)) {
                        Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(line.removePrefix("• "), style = MaterialTheme.typography.bodyLarge)
                    }
                    else -> Text(line, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/**
 * Settings › How to: the list of guides and, open, one of them. [initial] opens a guide straight
 * away; back from a guide returns to the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HowToScreen(onClose: () -> Unit, initial: HowTo? = null, header: (@Composable () -> Unit)? = null) {
    var open by rememberSaveable { mutableStateOf(initial?.name) }
    val article = open?.let { name -> HowTo.entries.firstOrNull { it.name == name } }
    ScreenBackHandler { if (article != null) open = null else onClose() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(article?.title ?: R.string.th_howto_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (article != null) open = null else onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.th_nav_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Lines stay a comfortable length on a tablet or a phone in landscape.
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (article != null) {
                    HowToArticle(article, Modifier.padding(horizontal = 4.dp))
                } else {
                    Text(stringResource(R.string.th_howto_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                    header?.invoke()
                    PrefSection(stringResource(R.string.th_howto_guides)) {
                        HowTo.entries.forEach { h ->
                            PrefRow(h.icon, stringResource(h.title), stringResource(h.summary)) { open = h.name }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
