package de.beardedskunk.homeshare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.beardedskunk.homeshare.core.PrioBand
import de.beardedskunk.homeshare.core.RRule
import de.beardedskunk.homeshare.data.NodeState
import de.beardedskunk.homeshare.data.TaskRepeat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DUE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy")

private val WEEKDAY_SHORT = mapOf(
    DayOfWeek.MONDAY to "Mo", DayOfWeek.TUESDAY to "Di", DayOfWeek.WEDNESDAY to "Mi",
    DayOfWeek.THURSDAY to "Do", DayOfWeek.FRIDAY to "Fr", DayOfWeek.SATURDAY to "Sa",
    DayOfWeek.SUNDAY to "So",
)

private val WEEKDAY_FULL = mapOf(
    DayOfWeek.MONDAY to "Montag", DayOfWeek.TUESDAY to "Dienstag", DayOfWeek.WEDNESDAY to "Mittwoch",
    DayOfWeek.THURSDAY to "Donnerstag", DayOfWeek.FRIDAY to "Freitag", DayOfWeek.SATURDAY to "Samstag",
    DayOfWeek.SUNDAY to "Sonntag",
)

/**
 * Erinnerungs-Kasten der Aufgaben-Ansicht (zwischen Unterpunkten und Anhängen): Zeile 1 = Termin
 * links (erster Datums-Kindknoten, „Termin: dd.MM.yyyy" bzw. „kein Termin", rot wenn überfällig;
 * Langdruck löscht ihn wirklich), rechtsbündig die vier Prio-Kreise ([PriorityDots] mit „Prio:"-
 * Label). Termin und bunte Hand-Prio schließen sich in der Anzeige aus: eine bunte Prio maskiert
 * den Termin (der Knoten bleibt erhalten, [masked] blendet ihn nur aus — zurück auf weiß zeigt
 * ihn wieder). Zeile 2 = Wiederholungsregel als Kurztext („keine Wiederholung" ohne Regel).
 * Chips öffnen ihre Editoren (readOnly: nur vorhandene Infos, nicht klickbar).
 */
@Composable
fun ReminderBox(
    due: NodeState?,
    ruleSummary: String?,
    overdue: Boolean,
    readOnly: Boolean,
    prio: PrioBand,
    containerColor: Color?,
    onEditDue: () -> Unit,
    onEditRepeat: () -> Unit,
    onRemoveRepeat: (() -> Unit)?,
    onDeleteDue: (() -> Unit)?,
    onPickPrio: (Int) -> Unit,
) {
    if (readOnly && due == null && ruleSummary == null && prio == PrioBand.NONE) return
    // Bunte Hand-Prio maskiert den Termin: der Datumsknoten bleibt bestehen, wird aber ausgeblendet
    // (wieder sichtbar, sobald die Prio auf weiß steht — siehe PrioritySort.dueDriven).
    val masked = prio != PrioBand.NONE
    var confirmDelete by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).tag("box:reminder"),
        colors = containerColor
            ?.let { CardDefaults.cardColors(containerColor = it) }
            ?: CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Erinnerung",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            val dueDay = due?.let { TaskRepeat.dueDate(it) }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DueChip(
                    label = when {
                        masked -> "kein Termin"
                        dueDay != null -> "Termin: " + dueDay.format(DUE_FMT)
                        due != null -> "Termin: " + due.title
                        else -> "kein Termin"
                    },
                    overdue = overdue && !masked,
                    enabled = !readOnly,
                    onClick = onEditDue,
                    // Langdruck = Termin wirklich löschen (nur wenn ein sichtbarer Termin da ist).
                    onLongClick = if (!readOnly && due != null && !masked && onDeleteDue != null) {
                        { confirmDelete = true }
                    } else {
                        null
                    },
                )
                Spacer(Modifier.weight(1f))
                Text("Prio:", style = MaterialTheme.typography.labelLarge)
                PriorityDots(current = prio, enabled = !readOnly, onPick = onPickPrio)
            }
            if (ruleSummary != null || !readOnly) {
                Row(Modifier.fillMaxWidth()) {
                    RepeatChip(
                        summary = ruleSummary,
                        readOnly = readOnly,
                        onClick = onEditRepeat,
                        onLongClick = onRemoveRepeat,
                    )
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Termin löschen?") },
            text = { Text("Der Fälligkeitstermin dieser Aufgabe wird entfernt.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDeleteDue?.invoke() }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}

/**
 * Termin-Chip in der Optik eines AssistChip, aber mit Langdruck: Kurzklick öffnet den Datums-
 * Editor, Langdruck (falls gesetzt) löscht den Termin. Rot, wenn überfällig.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DueChip(
    label: String,
    overdue: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val fg = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.tag("field:due"),
    ) {
        Row(
            Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick, enabled = enabled)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Event, contentDescription = "Termin", tint = fg, modifier = Modifier.size(18.dp))
            Text(label, color = fg, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** Wiederholungs-"Chip": Kurzklick öffnet den Dialog, Langdruck entfernt die Wiederholung direkt. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RepeatChip(summary: String?, readOnly: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val colors = AssistChipDefaults.assistChipColors()
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = colors.containerColor,
        modifier = Modifier.tag("action:repeat"),
    ) {
        Row(
            Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick, enabled = !readOnly)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Repeat, contentDescription = "Wiederholung", modifier = Modifier.size(18.dp))
            Text(
                summary ?: "keine Wiederholung",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** Kompakte Due-Info einer Aufgabe für Listen-Zeilen (Tag + „wiederholt sich" + optionale Uhrzeit). */
data class DueInfo(val day: LocalDate, val repeating: Boolean, val time: java.time.LocalTime? = null)

private val BADGE_FMT = DateTimeFormatter.ofPattern("dd.MM.")

/** Fälligkeits-Badge „(↻) 06.08." auf Aufgaben-Zeilen; rot, wenn überfällig und nicht erledigt. */
@Composable
fun DueBadge(info: DueInfo, done: Boolean) {
    val overdue = !done && TaskRepeat.isOverdue(info.day, LocalDate.now())
    val bg = if (overdue) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (overdue) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(color = bg, shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(end = 8.dp)) {
        Text(
            (if (info.repeating) "↻ " else "") + info.day.format(BADGE_FMT),
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private enum class MonthlyMode { PLAIN, MONTH_DAYS, POSITION }

private val MONTHLY_LABELS = mapOf(
    MonthlyMode.PLAIN to "am gleichen Tag",
    MonthlyMode.MONTH_DAYS to "an Monatstagen",
    MonthlyMode.POSITION to "am X. Wochentag",
)
private enum class EndMode { NEVER, UNTIL, COUNT }
private enum class Trigger { DUE, DONE, NONE }

private val ORDINALS = listOf(1 to "1.", 2 to "2.", 3 to "3.", 4 to "4.", -1 to "letzten")

/**
 * Editor der Wiederholungsregel einer Aufgabe. Verbirgt die RRULE-Komplexität hinter vier
 * Bausteinen (Text-Auswahlen als [PagerTextPicker]): Trigger (Termin/Erledigung/nie),
 * direkt darunter das Ende (für immer / bis Datum / für X Mal), dann Frequenz („alle N
 * Tage/Wochen/Monate/Jahre") und Detail je Frequenz (Wochentags-Chips; Monatstage inkl.
 * „letzter" oder Position wie „am 2. Dienstag"). Die Vorschauzeile zeigt live [RRule.summary].
 * Ohne Due Date fehlt „nach Termin" im Trigger-Picker (kein Disabled-Zustand im Picker
 * möglich); Trigger „nie" entfernt die Wiederholung beim Speichern (kein separater
 * Entfernen-Button mehr nötig).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RepeatDialog(
    initial: RRule?,
    initialMode: String,
    hasDue: Boolean,
    onSave: (RRule?, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var trigger by remember {
        mutableStateOf(
            when {
                initial == null -> Trigger.NONE
                initialMode == TaskRepeat.MODE_DUE && hasDue -> Trigger.DUE
                else -> Trigger.DONE
            },
        )
    }
    var freq by remember { mutableStateOf(initial?.freq ?: RRule.Freq.WEEKLY) }
    var interval by remember { mutableStateOf(initial?.interval?.coerceIn(1, 99) ?: 1) }
    var weekDays by remember {
        mutableStateOf(initial?.takeIf { it.freq == RRule.Freq.WEEKLY }?.byDay?.map { it.day }?.toSet() ?: emptySet())
    }
    var monthlyMode by remember {
        mutableStateOf(
            when {
                initial?.byMonthDay?.isNotEmpty() == true -> MonthlyMode.MONTH_DAYS
                initial?.freq == RRule.Freq.MONTHLY && initial.byDay.isNotEmpty() -> MonthlyMode.POSITION
                else -> MonthlyMode.PLAIN
            },
        )
    }
    var monthDays by remember { mutableStateOf(initial?.byMonthDay?.toSet() ?: emptySet()) }
    val initialPos = initial?.takeIf { it.freq == RRule.Freq.MONTHLY }?.byDay?.firstOrNull()
    var posOrdinal by remember { mutableStateOf(initialPos?.ordinal?.takeIf { it != 0 } ?: 1) }
    var posDay by remember { mutableStateOf(initialPos?.day ?: DayOfWeek.MONDAY) }
    var endMode by remember {
        mutableStateOf(
            when {
                initial?.until != null -> EndMode.UNTIL
                initial?.count != null -> EndMode.COUNT
                else -> EndMode.NEVER
            },
        )
    }
    var untilDate by remember { mutableStateOf(initial?.until ?: LocalDate.now().plusMonths(1)) }
    var count by remember { mutableStateOf(initial?.count?.coerceIn(1, 99) ?: 5) }

    // Aktueller Stand als Regel (Wheel-Picker liefern immer gültige Werte -> nie null).
    fun build(): RRule = RRule(
        freq = freq,
        interval = interval,
        byDay = when {
            freq == RRule.Freq.WEEKLY -> weekDays.sorted().map { RRule.ByDay(0, it) }
            freq == RRule.Freq.MONTHLY && monthlyMode == MonthlyMode.POSITION -> listOf(RRule.ByDay(posOrdinal, posDay))
            else -> emptyList()
        },
        byMonthDay = if (freq == RRule.Freq.MONTHLY && monthlyMode == MonthlyMode.MONTH_DAYS) {
            monthDays.sortedWith(compareBy({ it < 0 }, { it })) // -1 („letzter") ans Ende
        } else {
            emptyList()
        },
        until = if (endMode == EndMode.UNTIL) untilDate else null,
        count = if (endMode == EndMode.COUNT) count else null,
    )
    val preview = if (trigger != Trigger.NONE) build() else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wiederholung") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Trigger: wann die Kopie entsteht (oder „nie" = keine Wiederholung).
                val triggerItems = remember(hasDue) {
                    if (hasDue) listOf(Trigger.DUE, Trigger.DONE, Trigger.NONE) else listOf(Trigger.DONE, Trigger.NONE)
                }
                val triggerLabels = mapOf(Trigger.DUE to "nach Termin", Trigger.DONE to "nach Erledigung", Trigger.NONE to "nie")
                PagerTextPicker(triggerItems, trigger, { trigger = it }) { triggerLabels.getValue(it) }
                if (!hasDue) {
                    Text(
                        "(nach Termin erst mit Termin wählbar)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (trigger == Trigger.NONE) return@Column

                // Ende der Wiederholung — direkt unter dem Trigger.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EndDropdown(endMode) { endMode = it }
                    if (endMode == EndMode.UNTIL) {
                        DateField("", untilDate) { untilDate = it }
                    }
                    if (endMode == EndMode.COUNT) {
                        WheelNumberPicker(count, { count = it }, orientation = WheelOrientation.HORIZONTAL)
                    }
                }

                // Frequenz: alle N Tage/Wochen/Monate/Jahre.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Alle")
                    WheelNumberPicker(interval, { interval = it }, orientation = WheelOrientation.HORIZONTAL)
                    UnitDropdown(freq) { freq = it }
                }

                when (freq) {
                    RRule.Freq.WEEKLY -> {
                        Text("an diesen Wochentagen (leer = gleicher Wochentag):", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (d in DayOfWeek.entries) {
                                FilterChip(
                                    selected = d in weekDays,
                                    onClick = { weekDays = if (d in weekDays) weekDays - d else weekDays + d },
                                    label = { Text(WEEKDAY_SHORT.getValue(d)) },
                                )
                            }
                        }
                    }
                    RRule.Freq.MONTHLY -> {
                        PagerTextPicker(
                            listOf(MonthlyMode.PLAIN, MonthlyMode.MONTH_DAYS, MonthlyMode.POSITION),
                            monthlyMode,
                            { monthlyMode = it },
                        ) { MONTHLY_LABELS.getValue(it) }
                        if (monthlyMode == MonthlyMode.MONTH_DAYS) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (day in (1..31) + listOf(-1)) {
                                    FilterChip(
                                        selected = day in monthDays,
                                        onClick = { monthDays = if (day in monthDays) monthDays - day else monthDays + day },
                                        label = { Text(if (day == -1) "letzter" else "$day") },
                                    )
                                }
                            }
                        }
                        if (monthlyMode == MonthlyMode.POSITION) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                WheelPicker(
                                    ORDINALS.map { it.first },
                                    posOrdinal,
                                    { posOrdinal = it },
                                    orientation = WheelOrientation.HORIZONTAL,
                                    itemExtent = 48.dp,
                                ) { n, distance ->
                                    val alpha = when (distance) { 0 -> 1f; 1 -> 0.5f; else -> 0.25f }
                                    Text(
                                        ORDINALS.first { it.first == n }.second,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                                    )
                                }
                                SmallDropdown(
                                    current = WEEKDAY_FULL.getValue(posDay),
                                    options = DayOfWeek.entries.map { WEEKDAY_FULL.getValue(it) },
                                    modifier = Modifier.width(150.dp),
                                ) { sel -> posDay = DayOfWeek.entries.first { WEEKDAY_FULL.getValue(it) == sel } }
                            }
                        }
                    }
                    else -> {}
                }

                Text(
                    "↻ " + preview!!.summary(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (trigger == Trigger.NONE) {
                        onSave(null, TaskRepeat.MODE_DONE)
                    } else {
                        onSave(preview!!, if (trigger == Trigger.DUE) TaskRepeat.MODE_DUE else TaskRepeat.MODE_DONE)
                    }
                },
            ) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        },
    )
}

@Composable
private fun UnitDropdown(current: RRule.Freq, onSelect: (RRule.Freq) -> Unit) {
    val labels = mapOf(
        RRule.Freq.DAILY to "Tage", RRule.Freq.WEEKLY to "Wochen",
        RRule.Freq.MONTHLY to "Monate", RRule.Freq.YEARLY to "Jahre",
    )
    SmallDropdown(
        current = labels.getValue(current),
        options = RRule.Freq.entries.map { labels.getValue(it) },
        modifier = Modifier.width(120.dp),
    ) { sel -> onSelect(RRule.Freq.entries.first { labels.getValue(it) == sel }) }
}

/** Ende-Combobox: „für immer"/„bis Datum"/„für X Mal". */
@Composable
private fun EndDropdown(mode: EndMode, onSelect: (EndMode) -> Unit) {
    val labels = mapOf(EndMode.NEVER to "für immer", EndMode.UNTIL to "bis Datum", EndMode.COUNT to "für X Mal")
    SmallDropdown(
        current = labels.getValue(mode),
        options = listOf(EndMode.NEVER, EndMode.UNTIL, EndMode.COUNT).map { labels.getValue(it) },
        modifier = Modifier.width(140.dp),
    ) { sel -> onSelect(labels.entries.first { it.value == sel }.key) }
}

@Composable
private fun SmallDropdown(
    current: String,
    options: List<String>,
    modifier: Modifier = Modifier,
    enabledFor: (String) -> Boolean = { true },
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        androidx.compose.material3.OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(current, maxLines = 1)
            Text(" ▾")
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (opt in options) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(opt) },
                    enabled = enabledFor(opt),
                    onClick = { open = false; onSelect(opt) },
                )
            }
        }
    }
}
