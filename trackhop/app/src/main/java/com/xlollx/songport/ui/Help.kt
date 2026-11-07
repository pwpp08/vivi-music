package com.xlollx.songport.ui

import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R

// ---------------------------------------------------------------------------------------------
// Contextual help: a small "?" next to a setting or a label opens a sheet with a few sentences
// on what it really does, an example where one helps, and the way to the longer guide.
// ---------------------------------------------------------------------------------------------

/**
 * One help text: a [title] (usually the setting's own label), two to four short sentences in
 * [body] (and [extra] when a second paragraph reads better), an optional [example].
 */
class HelpTopic(
    @StringRes val title: Int,
    @StringRes val body: Int,
    @StringRes val extra: Int? = null,
    @StringRes val example: Int? = null,
    /** The guide that says more, opened in place from the sheet. */
    val more: HowTo? = null,
)

/**
 * The "?" button: 48dp to touch, a quiet 20dp glyph to see, read by TalkBack as "Help: [label]".
 * With several [topics] the sheet shows each under its own title, [label] heading them all.
 */
@Composable
fun HelpButton(
    vararg topics: HelpTopic,
    modifier: Modifier = Modifier,
    label: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val name = label ?: stringResource(topics.first().title)
    IconButton(onClick = { open = true }, modifier = modifier.size(48.dp)) {
        Icon(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(R.string.th_help_cd, name), tint = tint, modifier = Modifier.size(20.dp))
    }
    if (open) HelpSheet(if (topics.size > 1) name else null, topics.toList()) { open = false }
}

/** The sheet behind a "?": the topics one after the other, scrolling when they are long. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSheet(title: String?, topics: List<HelpTopic>, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // "More in How to ›" turns the sheet into the guide; back from there returns to the short text.
    var article by rememberSaveable { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        val open = article?.let { name -> HowTo.entries.firstOrNull { it.name == name } }
        if (open != null) {
            // Back returns to the short text where the sheet lets it (on Android 14 and newer the
            // sheet's own predictive back closes it); the arrow always does.
            BackHandler { article = null }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 24.dp, bottom = 32.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { article = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.th_nav_back)) }
                    Text(stringResource(open.title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                }
                Column(Modifier.padding(start = 12.dp, top = 4.dp)) { HowToArticle(open) }
            }
            return@ModalBottomSheet
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            topics.forEach { t ->
                if (title != null) Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(t.title),
                    style = if (title == null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                    color = if (title == null) Color.Unspecified else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(t.body), style = MaterialTheme.typography.bodyMedium)
                t.extra?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                t.example?.let { HelpExample(stringResource(it)) }
            }
            // The longer guides these topics belong to, once each.
            topics.mapNotNull { it.more }.distinct().forEach { h ->
                TextButton(onClick = { article = h.name }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.th_help_more, stringResource(h.title)))
                }
            }
        }
    }
}

/** "Example:" and the example, on a tinted block so it reads apart from the explanation. */
@Composable
private fun HelpExample(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.th_help_example), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
        }
    }
}

/** Every help text in the app, by screen, so the same topic reads the same wherever it opens. */
object Helps {
    // Syncs
    val status = HelpTopic(R.string.th_help_status_title, R.string.th_help_status_body, example = R.string.th_help_status_example, more = HowTo.REVIEW)
    val route = HelpTopic(R.string.th_help_route_title, R.string.th_help_route_body, more = HowTo.MODES)
    val figures = HelpTopic(R.string.th_help_figures_title, R.string.th_help_figures_body)
    val toFix = HelpTopic(R.string.th_help_to_fix_title, R.string.th_help_to_fix_body, more = HowTo.REVIEW)
    val runNow = HelpTopic(R.string.th_run_now, R.string.th_help_run_now_body, more = HowTo.PREVIEW)
    val syncAll = HelpTopic(R.string.th_sync_all, R.string.th_help_sync_all_body, example = R.string.th_help_sync_all_example, more = HowTo.SCHEDULES)
    val cached = HelpTopic(R.string.th_help_cached_title, R.string.th_help_cached_body, more = HowTo.REVIEW)

    // Editor
    val oneService = HelpTopic(R.string.th_help_one_service_title, R.string.th_help_one_service_body, example = R.string.th_help_one_service_example, more = HowTo.FIRST_SYNC)
    val library = HelpTopic(R.string.th_help_library_title, R.string.th_help_library_body, more = HowTo.FIRST_SYNC)
    val link = HelpTopic(R.string.th_editor_source_link, R.string.th_help_link_body, more = HowTo.FIRST_SYNC)
    val target = HelpTopic(R.string.th_editor_target, R.string.th_help_target_body, more = HowTo.FIRST_SYNC)
    val liked = HelpTopic(R.string.th_liked_songs, R.string.th_help_liked_body, example = R.string.th_help_liked_example, more = HowTo.MODES)
    val newPlaylist = HelpTopic(R.string.th_editor_new_playlist, R.string.th_help_new_playlist_body, more = HowTo.FIRST_SYNC)
    val schedule = HelpTopic(R.string.th_editor_schedule, R.string.th_help_schedule_body, example = R.string.th_help_schedule_example, more = HowTo.SCHEDULES)
    val mirror = HelpTopic(R.string.th_editor_mirror, R.string.th_help_mirror_body, example = R.string.th_help_mirror_example, more = HowTo.MODES)
    val keepOrder = HelpTopic(R.string.th_editor_keep_order, R.string.th_help_keep_order_body)
    val twoWay = HelpTopic(R.string.th_editor_bidirectional, R.string.th_help_two_way_body, more = HowTo.MODES)
    val enabled = HelpTopic(R.string.th_editor_enabled, R.string.th_help_enabled_body, more = HowTo.SCHEDULES)
    val strictness = HelpTopic(R.string.th_policy_strictness, R.string.th_help_strictness_body, example = R.string.th_help_strictness_example, more = HowTo.REVIEW)
    val explicit = HelpTopic(R.string.th_policy_explicit, R.string.th_help_explicit_body, more = HowTo.REVIEW)
    val sameAlbum = HelpTopic(R.string.th_policy_same_album, R.string.th_help_same_album_body, example = R.string.th_help_same_album_example, more = HowTo.REVIEW)

    // Preview
    val preview = HelpTopic(R.string.th_preview, R.string.th_help_preview_body, more = HowTo.PREVIEW)
    val previewRun = HelpTopic(R.string.th_run_now, R.string.th_help_preview_run_body, more = HowTo.PREVIEW)
    val removals = HelpTopic(R.string.th_help_removals_title, R.string.th_help_removals_body, more = HowTo.PREVIEW)
    val uncertain = HelpTopic(R.string.th_help_uncertain_title, R.string.th_help_uncertain_body, more = HowTo.PREVIEW)
    val quota = HelpTopic(R.string.th_help_quota_title, R.string.th_help_quota_body, more = HowTo.ACCOUNTS)

    // Review
    val change = HelpTopic(R.string.th_review_change, R.string.th_help_change_body, more = HowTo.REVIEW)
    val proposal = HelpTopic(R.string.th_search_suggested, R.string.th_help_proposal_body, example = R.string.th_help_proposal_example, more = HowTo.REVIEW)
    val ignore = HelpTopic(R.string.th_unmatched_ignore, R.string.th_help_ignore_body, more = HowTo.REVIEW)
    val results = HelpTopic(R.string.th_help_results_title, R.string.th_help_results_body, more = HowTo.REVIEW)
    val askAi = HelpTopic(R.string.th_search_ask_ai, R.string.th_help_ask_ai_body, more = HowTo.REVIEW)

    // Sync log
    val notes = HelpTopic(R.string.th_log_notes_title, R.string.th_help_notes_body, more = HowTo.PREVIEW)
    val restore = HelpTopic(R.string.th_help_restore_title, R.string.th_help_restore_body, more = HowTo.BACKUPS)

    // Accounts
    val connectors = HelpTopic(R.string.th_help_connectors_title, R.string.th_help_connectors_body, more = HowTo.ACCOUNTS)
    val tags = HelpTopic(R.string.th_help_tags_title, R.string.th_help_tags_body, more = HowTo.ACCOUNTS)
    val disconnect = HelpTopic(R.string.th_disconnect, R.string.th_help_disconnect_body, more = HowTo.ACCOUNTS)
    val removeAccount = HelpTopic(R.string.th_account_remove, R.string.th_help_remove_account_body, more = HowTo.ACCOUNTS)
    val test = HelpTopic(R.string.th_account_test, R.string.th_help_test_body, more = HowTo.ACCOUNTS)
    val extraActions = HelpTopic(R.string.th_help_extra_actions_title, R.string.th_help_extra_actions_body)
    val routes = HelpTopic(R.string.th_help_routes_title, R.string.th_help_routes_body, more = HowTo.ACCOUNTS)
    val unavailable = HelpTopic(R.string.th_services_group_unavailable, R.string.th_help_unavailable_body, more = HowTo.PLUGINS)

    // Setup wizard
    val redirect = HelpTopic(R.string.th_help_redirect_title, R.string.th_help_redirect_body, more = HowTo.ACCOUNTS)
    val fields = HelpTopic(R.string.th_help_fields_title, R.string.th_help_fields_body, more = HowTo.ACCOUNTS)
    val clearKey = HelpTopic(R.string.th_setup_clear, R.string.th_help_clear_key_body, more = HowTo.ACCOUNTS)

    // Tools
    val files = HelpTopic(R.string.th_files_title, R.string.th_help_files_body, more = HowTo.BACKUPS)
    val export = HelpTopic(R.string.th_help_export_title, R.string.th_help_export_body, more = HowTo.BACKUPS)
    val make = HelpTopic(R.string.th_tools_make_title, R.string.th_help_make_body, example = R.string.th_help_make_example, more = HowTo.TOOLS)
    val transfer = HelpTopic(R.string.th_tools_transfer_title, R.string.th_tools_transfer_desc, extra = R.string.th_help_transfer_body, example = R.string.th_help_transfer_example, more = HowTo.TOOLS)
    val dedupe = HelpTopic(R.string.th_tools_dedupe_title, R.string.th_help_dedupe_body, more = HowTo.TOOLS)
    val unplayable = HelpTopic(R.string.th_tools_unplayable_title, R.string.th_help_unplayable_body, more = HowTo.TOOLS)

    // Settings
    val newLook = HelpTopic(R.string.th_settings_new_look, R.string.th_settings_new_look_desc)
    val isrc = HelpTopic(R.string.th_settings_isrc, R.string.th_settings_isrc_desc, more = HowTo.REVIEW)
    val musicLinks = HelpTopic(R.string.th_settings_music_links, R.string.th_settings_music_links_desc)
    val notifications = HelpTopic(R.string.th_settings_notifications, R.string.th_settings_notifications_desc, more = HowTo.SCHEDULES)
    val backups = HelpTopic(R.string.th_backup_folder_title, R.string.th_help_backup_body, example = R.string.th_help_backup_example, more = HowTo.BACKUPS)
    val plugins = HelpTopic(R.string.th_plugins_title, R.string.th_help_plugins_body, more = HowTo.PLUGINS)
    val ai = HelpTopic(R.string.th_ai_setup_title, R.string.th_help_ai_body)
}
