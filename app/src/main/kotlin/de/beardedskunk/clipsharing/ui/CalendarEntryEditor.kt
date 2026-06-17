package de.beardedskunk.clipsharing.ui

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
import androidx.compose.material3.Button
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
import androidx.compose.material3.TopAppBar
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
import de.beardedskunk.clipsharing.data.EventCodec
import de.beardedskunk.clipsharing.data.EventData
import de.beardedskunk.clipsharing.data.Feed
import de.beardedskunk.clipsharing.data.FeedRepository
import de.beardedskunk.clipsharing.data.PostState
import de.beardedskunk.clipsharing.data.Recurrence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val reminderOptions: List<Pair<String, Int?>> = listOf(
    "Keine" to null,
    "Zur Startzeit" to 0,
    "10 Minuten vorher" to 10,
    "30 Minuten vorher" to 30,
    "1 Stunde vorher" to 60,
    "1 Tag vorher" to 1440,
)

/**
 * Editor für einen Kalendereintrag mit FESTEN Feldern (im Hintergrund Markdown via
 * [EventCodec]). Deckt alle Felder ab, die der Android-Kalender annehmen kann:
 * Titel, ganztägig, Start/Ende (Datum+Zeit), Ort, Erinnerung, Wiederholung,
 * Verfügbarkeit (gebucht/frei) und freie Beschreibung.
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
    val nowDate = remember { LocalDate.now() }
    val nextHour = remember { LocalTime.now().plusHours(1).withMinute(0) }

    var title by remember { mutableStateOf(existing?.title ?: "") }
    var allDay by remember { mutableStateOf(existing?.allDay ?: false) }
    var startDate by remember { mutableStateOf(existing?.start?.substringBefore('T')?.takeIf { it.isNotBlank() } ?: nowDate.toString()) }
    var startTime by remember { mutableStateOf(existing?.start?.substringAfter('T', "")?.takeIf { it.isNotBlank() } ?: nextHour.format(HM)) }
    var endDate by remember { mutableStateOf(existing?.end?.substringBefore('T')?.takeIf { it.isNotBlank() } ?: nowDate.toString()) }
    var endTime by remember { mutableStateOf(existing?.end?.substringAfter('T', "")?.takeIf { it.isNotBlank() } ?: nextHour.plusHours(1).format(HM)) }
    var location by remember { mutableStateOf(existing?.location ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var reminder by remember { mutableStateOf(existing?.reminderMinutes) }
    var recurrence by remember { mutableStateOf(existing?.recurrence ?: Recurrence.NONE) }
    var busy by remember { mutableStateOf(existing?.busy ?: true) }

    fun save() {
        if (title.isBlank()) { toast(context, "Titel fehlt"); return }
        if (!validDate(startDate) || (!allDay && !validTime(startTime))) { toast(context, "Start ungültig (Datum JJJJ-MM-TT, Zeit HH:MM)"); return }
        if (!validDate(endDate) || (!allDay && !validTime(endTime))) { toast(context, "Ende ungültig"); return }
        val start = if (allDay) startDate else "${startDate}T$startTime"
        val end = if (allDay) endDate else "${endDate}T$endTime"
        val ev = EventData(
            title = title.trim(),
            start = start,
            end = end,
            allDay = allDay,
            tz = ZoneId.systemDefault().id,
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
                OutlinedTextField(startDate, { startDate = it }, label = { Text("Datum (JJJJ-MM-TT)") }, singleLine = true, modifier = Modifier.weight(1f))
                if (!allDay) OutlinedTextField(startTime, { startTime = it }, label = { Text("Zeit (HH:MM)") }, singleLine = true, modifier = Modifier.weight(1f))
            }

            Text("Ende", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(endDate, { endDate = it }, label = { Text("Datum (JJJJ-MM-TT)") }, singleLine = true, modifier = Modifier.weight(1f))
                if (!allDay) OutlinedTextField(endTime, { endTime = it }, label = { Text("Zeit (HH:MM)") }, singleLine = true, modifier = Modifier.weight(1f))
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

/** Aufklappbares Auswahlfeld (einfacher als ExposedDropdownMenuBox, reicht hier). */
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

/** Kompakte Listenzeile für einen Kalendereintrag: Datum/Zeit + Titel + Ort. */
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
                (if (post.conflicted) "⚠ " else "") + (ev?.title?.ifBlank { "(ohne Titel)" } ?: post.text.lineSequence().firstOrNull().orEmpty()),
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

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_DE = DateTimeFormatter.ofPattern("EEE dd.MM.yyyy")

private fun formatWhen(ev: EventData): String {
    return try {
        if (ev.allDay) {
            val d = LocalDate.parse(ev.start.substringBefore('T'))
            "${d.format(DATE_DE)} · ganztägig"
        } else {
            val s = LocalDateTime.parse(ev.start)
            val e = runCatching { LocalDateTime.parse(ev.end) }.getOrNull()
            val base = "${s.format(DATE_DE)} ${s.format(HM)}"
            if (e != null) "$base – ${e.format(HM)}" else base
        }
    } catch (_: Exception) {
        ev.start
    }
}

private fun validDate(s: String): Boolean = runCatching { LocalDate.parse(s.trim()) }.isSuccess
private fun validTime(s: String): Boolean = runCatching { LocalTime.parse(s.trim()) }.isSuccess
private fun toast(ctx: android.content.Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
