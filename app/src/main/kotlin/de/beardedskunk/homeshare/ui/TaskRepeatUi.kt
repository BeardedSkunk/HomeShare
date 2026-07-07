package de.beardedskunk.homeshare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

/**
 * Fällig-Zeile der Aufgaben-Ansicht: links das Due Date (erster Datums-Kindknoten, rot wenn
 * überfällig), rechts die Wiederholungsregel als Kurztext. Beide Chips öffnen ihre Editoren;
 * ohne Daten dienen sie als Anlege-Knöpfe (readOnly: nur vorhandene Infos, nicht klickbar).
 */
@Composable
fun DueRow(
    due: NodeState?,
    ruleSummary: String?,
    overdue: Boolean,
    readOnly: Boolean,
    onEditDue: () -> Unit,
    onEditRepeat: () -> Unit,
) {
    if (readOnly && due == null && ruleSummary == null) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val dueDay = due?.let { TaskRepeat.dueDate(it) }
        if (due != null || !readOnly) {
            val error = MaterialTheme.colorScheme.error
            AssistChip(
                onClick = onEditDue,
                enabled = !readOnly,
                label = { Text(dueDay?.format(DUE_FMT) ?: (due?.title ?: "Fällig…")) },
                leadingIcon = { Icon(Icons.Filled.Event, contentDescription = "Fälligkeitsdatum") },
                colors = if (overdue) {
                    AssistChipDefaults.assistChipColors(labelColor = error, leadingIconContentColor = error)
                } else {
                    AssistChipDefaults.assistChipColors()
                },
                modifier = Modifier.tag("field:due"),
            )
        }
        if (ruleSummary != null || !readOnly) {
            AssistChip(
                onClick = onEditRepeat,
                enabled = !readOnly,
                label = { Text(ruleSummary ?: "Wiederholung…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Filled.Repeat, contentDescription = "Wiederholung") },
                modifier = Modifier.tag("action:repeat"),
            )
        }
    }
}

/** Kompakte Due-Info einer Aufgabe für Listen-Zeilen (Tag + „wiederholt sich"). */
data class DueInfo(val day: LocalDate, val repeating: Boolean)

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
private enum class EndMode { NEVER, UNTIL, COUNT }

private val ORDINALS = listOf(1 to "1.", 2 to "2.", 3 to "3.", 4 to "4.", -1 to "letzten")

/**
 * Editor der Wiederholungsregel einer Aufgabe. Verbirgt die RRULE-Komplexität hinter vier
 * Bausteinen: Trigger (Fälligkeit/Erledigung), Frequenz („alle N Tage/Wochen/Monate/Jahre"),
 * Detail je Frequenz (Wochentags-Chips; Monatstage inkl. „letzter" oder Position wie
 * „am 2. Dienstag"), Ende (nie / an Datum / nach N Wiederholungen). Die Vorschauzeile zeigt
 * live [RRule.summary]. Ohne Due Date ist nur der Erledigt-Trigger wählbar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RepeatDialog(
    initial: RRule?,
    initialMode: String,
    hasDue: Boolean,
    onSave: (RRule, String) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(if (hasDue) initialMode else TaskRepeat.MODE_DONE) }
    var freq by remember { mutableStateOf(initial?.freq ?: RRule.Freq.WEEKLY) }
    var intervalText by remember { mutableStateOf((initial?.interval ?: 1).toString()) }
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
    var countText by remember { mutableStateOf((initial?.count ?: 5).toString()) }

    // Aktueller Stand als Regel; null solange Zahlenfelder unbrauchbar sind (deaktiviert Speichern).
    fun build(): RRule? {
        val interval = intervalText.toIntOrNull()?.takeIf { it >= 1 } ?: return null
        val count = if (endMode == EndMode.COUNT) countText.toIntOrNull()?.takeIf { it >= 1 } ?: return null else null
        return RRule(
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
            count = count,
        )
    }
    val preview = build()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wiederholung") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Trigger: wann die Kopie entsteht.
                Text("Wiederholen…", style = MaterialTheme.typography.labelLarge)
                RadioRow("nach Fälligkeit", selected = mode == TaskRepeat.MODE_DUE, enabled = hasDue) {
                    mode = TaskRepeat.MODE_DUE
                }
                if (!hasDue) {
                    Text(
                        "(erst mit Fälligkeitsdatum wählbar)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RadioRow("nach Erledigung", selected = mode == TaskRepeat.MODE_DONE) { mode = TaskRepeat.MODE_DONE }

                // Frequenz: alle N Tage/Wochen/Monate/Jahre.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Alle")
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = { intervalText = it.filter(Char::isDigit).take(3) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(72.dp),
                    )
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
                        RadioRow("am gleichen Tag", selected = monthlyMode == MonthlyMode.PLAIN) {
                            monthlyMode = MonthlyMode.PLAIN
                        }
                        RadioRow("an Monatstagen", selected = monthlyMode == MonthlyMode.MONTH_DAYS) {
                            monthlyMode = MonthlyMode.MONTH_DAYS
                        }
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
                        RadioRow("am X. Wochentag", selected = monthlyMode == MonthlyMode.POSITION) {
                            monthlyMode = MonthlyMode.POSITION
                        }
                        if (monthlyMode == MonthlyMode.POSITION) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SmallDropdown(
                                    current = ORDINALS.first { it.first == posOrdinal }.second,
                                    options = ORDINALS.map { it.second },
                                    modifier = Modifier.width(110.dp),
                                ) { sel -> posOrdinal = ORDINALS.first { it.second == sel }.first }
                                SmallDropdown(
                                    current = WEEKDAY_SHORT.getValue(posDay),
                                    options = DayOfWeek.entries.map { WEEKDAY_SHORT.getValue(it) },
                                    modifier = Modifier.width(90.dp),
                                ) { sel -> posDay = DayOfWeek.entries.first { WEEKDAY_SHORT.getValue(it) == sel } }
                            }
                        }
                    }
                    else -> {}
                }

                // Ende der Wiederholung.
                Text("Ende", style = MaterialTheme.typography.labelLarge)
                RadioRow("nie", selected = endMode == EndMode.NEVER) { endMode = EndMode.NEVER }
                RadioRow("an Datum", selected = endMode == EndMode.UNTIL) { endMode = EndMode.UNTIL }
                if (endMode == EndMode.UNTIL) {
                    DateField("Bis", untilDate) { untilDate = it }
                }
                RadioRow("nach Anzahl", selected = endMode == EndMode.COUNT) { endMode = EndMode.COUNT }
                if (endMode == EndMode.COUNT) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = countText,
                            onValueChange = { countText = it.filter(Char::isDigit).take(3) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(72.dp),
                        )
                        Text("Wiederholungen")
                    }
                }

                Text(
                    preview?.let { "↻ " + it.summary() } ?: "Bitte gültige Zahlen eingeben",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (preview != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = preview != null,
                onClick = { preview?.let { onSave(it, mode) } },
            ) { Text("Speichern") }
        },
        dismissButton = {
            Row {
                if (onRemove != null) TextButton(onClick = onRemove) { Text("Entfernen") }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
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

@Composable
private fun SmallDropdown(current: String, options: List<String>, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box(modifier) {
        androidx.compose.material3.OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(current, maxLines = 1)
            Text(" ▾")
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (opt in options) {
                androidx.compose.material3.DropdownMenuItem(text = { Text(opt) }, onClick = { open = false; onSelect(opt) })
            }
        }
    }
}
