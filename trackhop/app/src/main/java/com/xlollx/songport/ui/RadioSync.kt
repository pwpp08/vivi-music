package com.xlollx.songport.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.xlollx.songport.R
import com.xlollx.songport.data.Diagnostics
import com.xlollx.songport.model.RadioSource
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.sync.RadioDirectory
import com.xlollx.songport.sync.RadioPlan
import com.xlollx.songport.sync.RadioState
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/*
 * Radio syncs in the app: the station (found by name in the radio directory, or a pasted stream),
 * the days and hours of the window, and on the card what the radio is playing.
 */

/** A new radio sync's window: every day, 08:00 to 10:00, as on the server. */
internal const val RADIO_DEFAULT_START = 8 * 60
internal const val RADIO_DEFAULT_END = 10 * 60

private fun locale(ctx: Context) = ctx.resources.configuration.locales[0]

/** A minute of the day as the phone writes times: "08:00" or "8:00 AM". */
internal fun radioTime(ctx: Context, minute: Int): String {
    val m = minute.coerceIn(0, 24 * 60 - 1)
    val pattern = if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm a"
    return LocalTime.of(m / 60, m % 60).format(DateTimeFormatter.ofPattern(pattern, locale(ctx)))
}

/** A day (1 = Monday … 7 = Sunday) as a short name in the app's language. */
internal fun radioDay(ctx: Context, day: Int): String = DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, locale(ctx))

/** The window, for the card: "Mon–Fri 08:00–10:00", "Every day, all day"… in the app's language. */
internal fun radioWindowLabel(ctx: Context, r: RadioSource): String {
    val d = r.days.sorted()
    val days = when {
        d.size == 7 -> ctx.getString(R.string.th_radio_every_day)
        d == listOf(1, 2, 3, 4, 5) -> ctx.getString(R.string.th_radio_weekdays)
        d == listOf(6, 7) -> ctx.getString(R.string.th_radio_weekends)
        else -> d.joinToString(", ") { radioDay(ctx, it) }
    }
    return if (r.startMinute == r.endMinute) ctx.getString(R.string.th_radio_all_day, days)
    else "$days ${radioTime(ctx, r.startMinute)}–${radioTime(ctx, r.endMinute)}"
}

/** Under a listening radio sync's name: what is on air, that it waits for Wi-Fi, or what went wrong. */
@Composable
internal fun RadioCardStatus(job: SyncJob) {
    val all by RadioState.status.collectAsState()
    val timedOut by RadioState.timedOut.collectAsState()
    val s = all[job.id]
    val ctx = LocalContext.current
    val (text, problem) = when {
        job.id in timedOut.keys -> stringResource(R.string.th_radio_timed_out) to true
        s == null -> return
        s.phase == RadioState.Phase.WAITING_WIFI -> stringResource(R.string.th_radio_waiting_wifi) to false
        s.phase == RadioState.Phase.ERROR && s.noTitles -> stringResource(R.string.th_radio_no_titles) to true
        s.phase == RadioState.Phase.ERROR -> stringResource(R.string.th_radio_error, s.error.orEmpty()) to true
        s.title != null -> stringResource(R.string.th_radio_on_air, s.title) to false
        else -> stringResource(R.string.th_radio_listening) to false
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Icon(Icons.Filled.Radio, null, Modifier.size(16.dp), tint = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.width(6.dp))
        Text(
            text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        // Stopped by Android's daily limit: the app on screen may start it again, when the user asks.
        if (job.id in timedOut.keys) TextButton(onClick = { RadioPlan.refresh(ctx, start = true, listenAgain = true) }) {
            Text(stringResource(R.string.th_radio_listen_again))
        }
    }
}

/** The editor's radio source: what a radio sync does, then the station to listen to. */
@Composable
internal fun RadioStationPicker(onPicked: (RadioDirectory.Station) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RadioDirectory.Station>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var stream by rememberSaveable { mutableStateOf("") }
    var station by rememberSaveable { mutableStateOf("") }
    var streamError by remember { mutableStateOf(false) }

    fun search() {
        if (query.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            try {
                results = RadioDirectory.search(query)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                results = null
                Diagnostics.log(ctx, "radio", "station search failed: ${(e as? RadioDirectory.Unavailable)?.detail ?: e.message}")
                error = ctx.getString(R.string.th_radio_search_failed)
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.th_radio_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = query, onValueChange = { query = it; error = null },
            label = { Text(stringResource(R.string.th_radio_search_label)) },
            placeholder = { Text(stringResource(R.string.th_radio_search_hint)) },
            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
        )
        Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.th_radio_searching), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
            }
            TextButton(enabled = !busy && query.isNotBlank(), onClick = { search() }) { Text(stringResource(R.string.th_radio_search)) }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        results?.let { list ->
            if (list.isEmpty()) Text(stringResource(R.string.th_radio_search_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            list.forEach { s ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onPicked(s) }.padding(vertical = 8.dp),
                ) {
                    Icon(Icons.Filled.Radio, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val details = listOf(s.country, s.codec, if (s.bitrate > 0) "${s.bitrate} kbps" else "").filter { it.isNotBlank() }.joinToString(" · ")
                        if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text(stringResource(R.string.th_radio_stream_label), style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = stream, onValueChange = { stream = it; streamError = false },
            label = { Text(stringResource(R.string.th_radio_stream_address)) },
            placeholder = { Text("https://…/stream.mp3") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = station, onValueChange = { station = it; streamError = false },
            label = { Text(stringResource(R.string.th_radio_station_name)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        if (streamError) Text(stringResource(R.string.th_radio_stream_invalid), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(
                enabled = stream.isNotBlank(),
                onClick = {
                    val url = stream.trim()
                    val name = station.trim()
                    if (name.isEmpty() || !(url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))) streamError = true
                    else onPicked(RadioDirectory.Station(name, url, "", "", "", 0))
                },
            ) { Text(stringResource(R.string.th_radio_use_stream)) }
        }
    }
}

/** The station chosen, with the way to pick another while the sync is new. */
@Composable
internal fun RadioStationCard(station: String, stream: String, onChange: (() -> Unit)?) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Radio, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(station, style = MaterialTheme.typography.titleSmall)
                Text(stream, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onChange != null) TextButton(onClick = onChange) { Text(stringResource(R.string.th_radio_another)) }
        }
    }
}

/** The days (seven chips) and the daily window (from, to: the Material time picker). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RadioWindowPicker(days: List<Int>, start: Int, end: Int, onDays: (List<Int>) -> Unit, onStart: (Int) -> Unit, onEnd: (Int) -> Unit) {
    val ctx = LocalContext.current
    var picking by remember { mutableStateOf<Boolean?>(null) }
    Text(stringResource(R.string.th_radio_days), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        (1..7).forEach { d ->
            FilterChip(
                selected = d in days,
                onClick = { onDays(if (d in days) days - d else (days + d).sorted()) },
                label = { Text(radioDay(ctx, d)) },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { picking = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.th_radio_from, radioTime(ctx, start))) }
        OutlinedButton(onClick = { picking = false }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.th_radio_to, radioTime(ctx, end))) }
    }
    Text(stringResource(R.string.th_radio_window_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    picking?.let { isStart ->
        RadioTimeDialog(if (isStart) start else end, onDismiss = { picking = null }) { m ->
            if (isStart) onStart(m) else onEnd(m)
            picking = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RadioTimeDialog(minute: Int, onDismiss: () -> Unit, onPicked: (Int) -> Unit) {
    val ctx = LocalContext.current
    val state = rememberTimePickerState(initialHour = minute / 60, initialMinute = minute % 60, is24Hour = DateFormat.is24HourFormat(ctx))
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPicked(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.th_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.th_cancel)) } },
        text = { TimePicker(state) },
    )
}

/**
 * Android 12 and later start the listening in the background only from an exact alarm, which
 * Android 14 and later leave to the user ("Alarms & reminders"): said, with the way there, until allowed.
 */
@Composable
internal fun RadioAlarmHint() {
    if (Build.VERSION.SDK_INT < 31) return
    val ctx = LocalContext.current
    var allowed by remember { mutableStateOf(RadioPlan.canExact(ctx)) }
    LifecycleResumeEffect(Unit) {
        allowed = RadioPlan.canExact(ctx)
        onPauseOrDispose {}
    }
    if (allowed) return
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.th_radio_exact_alarm), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            TextButton(
                modifier = Modifier.align(Alignment.End),
                onClick = {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
            ) { Text(stringResource(R.string.th_radio_exact_alarm_allow)) }
        }
    }
}
