package com.xlollx.songport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.label
import com.xlollx.songport.sync.SyncFilter

/** The find bar shows from this many syncs on: with fewer, the whole list fits on a screen or two. */
const val FIND_MIN_SYNCS = 5

/** Keeps the filter across rotation and process death: only strings and lists of strings. */
val SyncFilterSaver: Saver<SyncFilter, Any> = listSaver<SyncFilter, Any>(
    save = { f -> listOf(f.query, ArrayList(f.statuses.map { it.name }), ArrayList(f.services), ArrayList(f.colors)) },
    restore = { l ->
        SyncFilter(
            query = l[0] as String,
            statuses = (l[1] as List<*>).mapNotNull { n -> SyncFilter.Status.entries.firstOrNull { it.name == n } }.toSet(),
            services = (l[2] as List<*>).filterIsInstance<String>().toSet(),
            colors = (l[3] as List<*>).filterIsInstance<String>().toSet(),
        )
    },
)

/**
 * Find and filter, under the hero when there are many syncs: a compact search field, then one row
 * of chips (status, the services in use, the colours in use) that scrolls sideways. With a filter
 * on, a line says how many cards are shown, with a way to clear it all.
 */
@Composable
fun SyncFinder(jobs: List<SyncJob>, filter: SyncFilter, shown: Int, counts: Map<SyncFilter.Status, Int> = emptyMap(), onChange: (SyncFilter) -> Unit) {
    val ctx = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = filter.query,
            onValueChange = { onChange(filter.copy(query = it)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            singleLine = true,
            shape = RoundedCornerShape(50),
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = { Text(stringResource(R.string.th_syncs_find_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = if (filter.query.isNotEmpty()) ({
                IconButton(onClick = { onChange(filter.copy(query = "")) }) {
                    Icon(Icons.Filled.Close, stringResource(R.string.th_syncs_find_clear))
                }
            }) else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )
        // One row that scrolls sideways: it never grows into a wall of chips above the cards.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // By status, each with how many syncs it holds; a status no sync is in shows only while chosen.
            SyncFilter.Status.entries.forEach { s ->
                val n = counts[s] ?: 0
                if (n > 0 || s in filter.statuses || counts.isEmpty()) {
                    val (label, icon) = when (s) {
                        SyncFilter.Status.UP_TO_DATE -> R.string.th_filter_up_to_date to Icons.Filled.CheckCircle
                        SyncFilter.Status.ERROR -> R.string.th_filter_errors to Icons.Filled.Warning
                        SyncFilter.Status.NEEDS_FIX -> R.string.th_filter_needs_fix to Icons.Filled.Build
                        SyncFilter.Status.RUNNING -> R.string.th_filter_running to Icons.Filled.Sync
                        SyncFilter.Status.NEVER_RUN -> R.string.th_filter_never_run to Icons.Filled.Schedule
                        SyncFilter.Status.DISABLED -> R.string.th_filter_disabled to Icons.Filled.Block
                    }
                    val text = stringResource(label)
                    StatusChip(if (counts.isEmpty()) text else "$text · $n", icon, s in filter.statuses) { onChange(filter.toggle(s)) }
                }
            }
            val services = SyncFilter.servicesInUse(jobs)
            if (services.size > 1) {
                services.forEach { id ->
                    val p = Providers.byId(id)
                    FilterChip(
                        selected = id in filter.services,
                        onClick = { onChange(filter.toggleService(id)) },
                        label = { Text(p?.label(ctx) ?: id, maxLines = 1) },
                        // The mark says which service, the label says it in words: the mark itself stays silent.
                        leadingIcon = { Box(Modifier.clearAndSetSemantics {}) { ServiceLogo(p, FilterChipDefaults.IconSize) } },
                    )
                }
            }
            val inUse = SyncFilter.colorsInUse(jobs)
            JobColor.entries.filter { it.key in inUse }.forEach { c ->
                ColorDot(c, c.key in filter.colors) { onChange(filter.toggleColor(c.key)) }
            }
        }
        if (filter.isActive) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(R.plurals.th_filter_shown, jobs.size, shown, jobs.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
                TextButton(onClick = { onChange(SyncFilter()) }) { Text(stringResource(R.string.th_filter_clear)) }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = { Icon(if (selected) Icons.Filled.Check else icon, null, Modifier.size(FilterChipDefaults.IconSize)) },
    )
}

/** A colour to filter by: a dot in a 48dp target, ticked when on; TalkBack reads its name. */
@Composable
private fun ColorDot(c: JobColor, selected: Boolean, onToggle: () -> Unit) {
    val color = if (LocalAppDark.current) c.dark else c.light
    val name = stringResource(R.string.th_filter_color, stringResource(c.label))
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(color)
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = if (color.luminance() < 0.45f) Color.White else Color.Black)
        }
    }
}

/** Nothing left with this filter: a friendly line and a way back to every sync. */
@Composable
fun SyncFinderEmpty(onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.SearchOff, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.th_filter_none), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(12.dp))
        OutlinedButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Filled.Close, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.th_filter_clear))
        }
    }
}
