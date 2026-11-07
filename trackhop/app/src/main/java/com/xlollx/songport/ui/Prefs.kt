package com.xlollx.songport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R

// ---------------------------------------------------------------------------------------------
// Grouped rows, shared by Settings and Tools: a section title, one card holding its rows, an
// optional note under it. Every row has the same shape (icon, title, one line saying the current
// value or what it does, then a switch or a chevron) and is one touch target of 56dp or more.
// ---------------------------------------------------------------------------------------------

/**
 * A group of rows under a title. [description] is a short line under the title (what the group
 * is for); [footer] a note under the card, for the longer explanation a row has no room for.
 */
@Composable
fun PrefSection(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    footer: String? = null,
    help: (@Composable () -> Unit)? = null,
    accent: JobColor? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = LocalAppDark.current
    val color = accent?.let { MaterialTheme.colorScheme.primary }
    // The heading in the section's colour, a little deeper in light mode so small text still reads.
    val headingColor = color?.let { if (dark) it else lerp(it, MaterialTheme.colorScheme.onSurface, 0.25f) } ?: MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxWidth()) {
        val heading = @Composable { m: Modifier ->
            Row(m, verticalAlignment = Alignment.CenterVertically) {
                if (color != null) {
                    Box(Modifier.size(width = 4.dp, height = 18.dp).clip(RoundedCornerShape(2.dp)).background(color))
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = headingColor, modifier = Modifier.semantics { heading() })
            }
        }
        if (help == null) heading(Modifier.padding(start = 16.dp, end = 16.dp, bottom = if (description == null) 8.dp else 2.dp))
        else Row(verticalAlignment = Alignment.CenterVertically) {
            // The "?" keeps its 48dp target at the end of the heading.
            heading(Modifier.weight(1f).padding(start = 16.dp))
            Box(Modifier.padding(end = 4.dp)) { help() }
        }
        if (description != null) Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        // The card stands off the page: white with a soft shadow in light mode, a raised surface in
        // dark mode (where shadows do not show), tinted with the section's colour when it has one.
        val base = if (dark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest
        Card(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = color?.let { lerp(base, it, if (dark) 0.10f else 0.05f) } ?: base),
            elevation = CardDefaults.cardElevation(defaultElevation = if (dark) 0.dp else 1.dp),
        ) {
            CompositionLocalProvider(LocalPrefAccent provides color) {
                val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                Column(
                    Modifier.drawBehind {
                        // In light mode the colour also runs as a band along the leading edge, as on the sync cards.
                        if (color != null && !dark) {
                            val w = 6.dp.toPx()
                            drawRect(color, topLeft = Offset(if (rtl) size.width - w else 0f, 0f), size = Size(w, size.height))
                        }
                    }.padding(vertical = 6.dp),
                    content = content,
                )
            }
        }
        if (footer != null) Text(
            footer,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
    }
}

/** The colour of the section a row is in (null for a plain one): its icons wear it. */
val LocalPrefAccent = staticCompositionLocalOf<Color?> { null }

/** The row's icon: the glyph in a soft tonal circle, so the eye finds the row before reading it. */
@Composable
fun PrefIcon(icon: ImageVector, danger: Boolean = false, enabled: Boolean = true) {
    val accent = LocalPrefAccent.current
    Box(
        Modifier.alpha(if (enabled) 1f else 0.5f).size(40.dp).clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    danger -> MaterialTheme.colorScheme.errorContainer
                    accent != null -> lerp(MaterialTheme.colorScheme.surfaceContainerHighest, accent, if (LocalAppDark.current) 0.28f else 0.16f)
                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, null,
            tint = if (danger) MaterialTheme.colorScheme.onErrorContainer else accent ?: MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * A row that opens something (a page, a choice, a system screen) or only shows a value. With
 * [onClick] the whole row is the target and ends in a chevron unless [trailing] says otherwise.
 */
@Composable
fun PrefRow(
    icon: ImageVector?,
    title: String,
    summary: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    chevron: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** A second, hidden action on a long press (the About box's easter egg). */
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(
                when {
                    onLongClick != null -> Modifier.combinedClickable(enabled = enabled, onClick = onClick ?: {}, onLongClick = onLongClick)
                    onClick != null -> Modifier.clickable(enabled = enabled, onClick = onClick)
                    else -> Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(16.dp))
        } else if (icon != null) {
            PrefIcon(icon, danger, enabled)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else 0.6f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) { Spacer(Modifier.width(8.dp)); badge() }
            }
            if (!summary.isNullOrBlank()) Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        } else if (onClick != null && chevron && enabled) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A row that is one switch: read out by TalkBack as a switch with its title and summary. */
@Composable
fun PrefSwitch(
    icon: ImageVector?,
    title: String,
    summary: String?,
    checked: Boolean,
    enabled: Boolean = true,
    help: (@Composable () -> Unit)? = null,
    onChange: (Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch) { v ->
                haptics.performHapticFeedback(if (v) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onChange(v)
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            PrefIcon(icon, enabled = enabled)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else 0.6f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!summary.isNullOrBlank()) Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (help != null) help() else Spacer(Modifier.width(12.dp))
        // The row owns the toggle; the switch only shows the state.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Free content inside a section (a list, a status line, buttons), lined up with the rows' text. */
@Composable
fun PrefBlock(indent: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = if (indent) 72.dp else 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** One choice out of a few (theme, language, schedule): radio rows, 48dp each, chosen at a tap. */
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                            .selectable(selected = value == selected, role = Role.RadioButton) { onSelect(value) }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.th_cancel)) } },
    )
}
