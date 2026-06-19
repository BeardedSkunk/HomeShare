package de.beardedskunk.homeshare.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.beardedskunk.homeshare.data.EventCodec
import de.beardedskunk.homeshare.data.EventData
import de.beardedskunk.homeshare.data.Feed
import de.beardedskunk.homeshare.data.FeedRepository
import de.beardedskunk.homeshare.data.PostState
import de.beardedskunk.homeshare.data.Recurrence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val DATE_UI = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val HM = DateTimeFormatter.ofPattern("HH:mm")

private val reminderOptions: List<Pair<String, Int?>> = listOf(
    "Keine" to null,
    "Zur Startzeit" to 0,
    "10 Minuten vorher" to 10,
    "30 Minuten vorher" to 30,
    "1 Stunde vorher" to 60,
    "1 Tag vorher" to 1440,
)

/**
 * Editor für einen Kalendereintrag mit FESTEN Feldern (Hintergrund: Markdown via [EventCodec],
 * Zeiten als ISO-8601 inkl. Zeitzone). Datum/Zeit über grafische Picker; Anzeige dd.MM.yyyy / HH:mm.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarEntryEditor(
    repo: FeedRepository,
    feed: Feed,
    post: PostState?,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val existing = remember(post?.headVersionId) { post?.text?.let { EventCodec.parse(it) } }
    val now = remember { LocalDateTime.now() }
    val defStart = remember { now.toLocalTime().plusHours(1).withMinute(0).withSecond(0).withNano(0) }

    var title by remember { mutableStateOf(existing?.title ?: "") }
    var allDay by remember { mutableStateOf(existing?.allDay ?: false) }
    var startDate by remember { mutableStateOf(existing?.let { dateOf(it.start) } ?: now.toLocalDate()) }
    var startTime by remember { mutableStateOf(existing?.takeIf { !it.allDay }?.let { timeOf(it.start) } ?: defStart) }
    var endDate by remember { mutableStateOf(existing?.let { dateOf(it.end) } ?: now.toLocalDate()) }
    var endTime by remember { mutableStateOf(existing?.takeIf { !it.allDay }?.let { timeOf(it.end) } ?: defStart.plusHours(1)) }
    var location by remember { mutableStateOf(existing?.location ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var reminder by remember { mutableStateOf(existing?.reminderMinutes) }
    var recurrence by remember { mutableStateOf(existing?.recurrence ?: Recurrence.NONE) }
    var busy by remember { mutableStateOf(existing?.busy ?: true) }

    fun save() {
        if (title.isBlank()) { toast(context, "Titel fehlt"); return }
        val zone = ZoneId.systemDefault()
        val start: String
        val end: String
        if (allDay) {
            start = startDate.toString()
            end = (if (endDate.isBefore(startDate)) startDate else endDate).toString()
        } else {
            start = ZonedDateTime.of(startDate, startTime, zone).toString()
            val endZdt = ZonedDateTime.of(endDate, endTime, zone)
            val startZdt = ZonedDateTime.of(startDate, startTime, zone)
            end = (if (endZdt.isBefore(startZdt)) startZdt else endZdt).toString()
        }
        val ev = EventData(
            title = title.trim(),
            start = start,
            end = end,
            allDay = allDay,
            location = location.trim(),
            description = description.trim(),
            reminderMinutes = reminder,
            recurrence = recurrence,
            busy = busy,
        )
        val text = EventCodec.encode(ev)
        scope.launch {
            withContext(Dispatchers.IO) {
                if (post == null) repo.createPost(feed.id, text) else repo.editPost(feed.id, post.postId, text)
            }
            onClose()
        }
    }

    fun delete() {
        val p = post ?: return
        scope.launch {
            withContext(Dispatchers.IO) { repo.deletePost(feed.id, p.postId) }
            onClose()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (post == null) "Neuer Termin" else "Termin bearbeiten") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    if (post != null) {
                        IconButton(onClick = { delete() }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Löschen")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(title, { title = it }, label = { Text("Titel") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ganztägig", modifier = Modifier.weight(1f))
                Switch(checked = allDay, onCheckedChange = { allDay = it })
            }

            Text("Start", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Beim Verschieben des Starts die Dauer beibehalten (Ende wandert mit).
                DateField("Datum", startDate, Modifier.weight(1f)) { picked ->
                    if (allDay) {
                        val days = ChronoUnit.DAYS.between(startDate, endDate).coerceAtLeast(0)
                        startDate = picked
                        endDate = picked.plusDays(days)
                    } else {
                        val dur = Duration.between(LocalDateTime.of(startDate, startTime), LocalDateTime.of(endDate, endTime))
                        startDate = picked
                        val ne = LocalDateTime.of(picked, startTime).plus(if (dur.isNegative) Duration.ZERO else dur)
                        endDate = ne.toLocalDate(); endTime = ne.toLocalTime()
                    }
                }
                if (!allDay) TimeField("Zeit", startTime, Modifier.weight(1f)) { picked ->
                    val dur = Duration.between(LocalDateTime.of(startDate, startTime), LocalDateTime.of(endDate, endTime))
                    startTime = picked
                    val ne = LocalDateTime.of(startDate, picked).plus(if (dur.isNegative) Duration.ZERO else dur)
                    endDate = ne.toLocalDate(); endTime = ne.toLocalTime()
                }
            }

            Text("Ende", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField("Datum", endDate, Modifier.weight(1f)) { endDate = it }
                if (!allDay) TimeField("Zeit", endTime, Modifier.weight(1f)) { endTime = it }
            }

            OutlinedTextField(location, { location = it }, label = { Text("Ort") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            LabeledDropdown(
                label = "Erinnerung",
                current = reminderOptions.firstOrNull { it.second == reminder }?.first ?: "Keine",
                options = reminderOptions.map { it.first },
                onSelect = { sel -> reminder = reminderOptions.first { it.first == sel }.second },
            )
            LabeledDropdown(
                label = "Wiederholung",
                current = recurrence.label,
                options = Recurrence.entries.map { it.label },
                onSelect = { sel -> recurrence = Recurrence.entries.first { it.label == sel } },
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (busy) "Als gebucht anzeigen" else "Als frei anzeigen", modifier = Modifier.weight(1f))
                Switch(checked = busy, onCheckedChange = { busy = it })
            }

            OutlinedTextField(
                description, { description = it },
                label = { Text("Beschreibung / Notizen (Markdown)") },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Speichern") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, date: LocalDate, modifier: Modifier = Modifier, onPick: (LocalDate) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }, modifier = modifier) {
        Text("$label: ${date.format(DATE_UI)}")
    }
    if (show) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    show = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Abbrechen") } },
        ) { DatePicker(state = state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(label: String, time: LocalTime, modifier: Modifier = Modifier, onPick: (LocalTime) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }, modifier = modifier) {
        Text("$label: ${time.format(HM)}")
    }
    if (show) {
        val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)); show = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Abbrechen") } },
            text = { TimePicker(state = state) },
        )
    }
}

/** Aufklappbares Auswahlfeld. */
@Composable
private fun LabeledDropdown(label: String, current: String, options: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(current, modifier = Modifier.weight(1f))
                Text("▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (opt in options) {
                    DropdownMenuItem(text = { Text(opt) }, onClick = { open = false; onSelect(opt) })
                }
            }
        }
    }
}

/** Kompakte Listenzeile für einen Kalendereintrag: Titel + Datum/Zeit + Ort. */
@Composable
fun CalendarRow(post: PostState, onClick: () -> Unit) {
    val ev = remember(post.headVersionId) { EventCodec.parse(post.text) }
    androidx.compose.material3.Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable(onClick = onClick),
        colors = if (post.conflicted) {
            androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            androidx.compose.material3.CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                (if (post.conflicted) "⚠ " else "") +
                    (ev?.title?.ifBlank { "(ohne Titel)" } ?: post.text.lineSequence().firstOrNull().orEmpty()),
                style = MaterialTheme.typography.titleMedium,
            )
            if (ev != null) {
                Text(formatWhen(ev), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                if (ev.location.isNotBlank()) Text("📍 ${ev.location}", style = MaterialTheme.typography.bodySmall)
                if (ev.recurrence != Recurrence.NONE) Text("🔁 ${ev.recurrence.label}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun formatWhen(ev: EventData): String = try {
    if (ev.allDay) {
        val d = LocalDate.parse(ev.start.substringBefore('T'))
        val e = LocalDate.parse(ev.end.substringBefore('T'))
        if (e.isAfter(d)) "${d.format(DATE_UI)} – ${e.format(DATE_UI)} · ganztägig"
        else "${d.format(DATE_UI)} · ganztägig"
    } else {
        val s = parseZoned(ev.start)
        val e = parseZoned(ev.end)
        val sameDay = s.toLocalDate() == e.toLocalDate()
        if (sameDay) "${s.format(DATE_UI)} ${s.format(HM)} – ${e.format(HM)}"
        else "${s.format(DATE_UI)} ${s.format(HM)} – ${e.format(DATE_UI)} ${e.format(HM)}"
    }
} catch (_: Exception) {
    ev.start
}

private fun parseZoned(s: String): ZonedDateTime {
    val t = s.trim()
    runCatching { return ZonedDateTime.parse(t) }
    runCatching { return OffsetDateTime.parse(t).toZonedDateTime() }
    return LocalDateTime.parse(t).atZone(ZoneId.systemDefault())
}

private fun dateOf(s: String): LocalDate = runCatching { parseZoned(s).toLocalDate() }
    .getOrElse { runCatching { LocalDate.parse(s.substringBefore('T')) }.getOrDefault(LocalDate.now()) }

private fun timeOf(s: String): LocalTime = runCatching { parseZoned(s).toLocalTime() }
    .getOrDefault(LocalTime.of(9, 0))

private fun toast(ctx: android.content.Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
