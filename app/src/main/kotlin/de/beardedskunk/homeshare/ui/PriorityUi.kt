package de.beardedskunk.homeshare.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import de.beardedskunk.homeshare.core.PrioBand

/** Feste Bandfarben (themenunabhängig — als Volltonfläche im Picker, als Alpha-Tönung auf Zeilen). */
fun PrioBand.color(): Color? = when (this) {
    PrioBand.YELLOW -> Color(0xFFFFC107)
    PrioBand.ORANGE -> Color(0xFFFF9800)
    PrioBand.RED -> Color(0xFFE53935)
    PrioBand.OVERDUE -> Color(0xFFFF1744)
    PrioBand.NONE -> null
}

/** Zeilen-Hintergrund: leichte Alpha-Variante der Bandfarbe. */
fun PrioBand.rowTint(): Color? = color()?.copy(alpha = 0.15f)

/** Kasten-Tönung (Anhänge/Unterpunkte) im TodoDetail. */
fun PrioBand.boxTint(): Color? = color()?.copy(alpha = 0.12f)

private val PICK_BANDS = listOf(PrioBand.NONE, PrioBand.YELLOW, PrioBand.ORANGE, PrioBand.RED)
private val PICK_LABELS = mapOf(
    PrioBand.NONE to "keine", PrioBand.YELLOW to "gelb", PrioBand.ORANGE to "orange", PrioBand.RED to "rot",
)
private val PICK_TAGS = mapOf(
    PrioBand.NONE to "prio:none", PrioBand.YELLOW to "prio:yellow",
    PrioBand.ORANGE to "prio:orange", PrioBand.RED to "prio:red",
)

/**
 * Farb-Picker für die Hand-Priorität einer Aufgabe (nur ohne Due-Date). Vier tappbare Kreise
 * keine/gelb/orange/rot; der aktuelle Wert bekommt einen Rahmen. [onPick] liefert den Level 0..3.
 */
@Composable
fun PriorityPickerDialog(current: PrioBand, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Priorität") },
        text = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                for (band in PICK_BANDS) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val fill = band.color() ?: MaterialTheme.colorScheme.surfaceVariant
                        val selected = band == current
                        Surface(
                            onClick = { onPick(band.level) },
                            shape = CircleShape,
                            color = fill,
                            border = if (selected) {
                                BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface)
                            } else if (band == PrioBand.NONE) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            } else {
                                null
                            },
                            modifier = Modifier.size(40.dp).tag(PICK_TAGS.getValue(band)),
                        ) {}
                        Text(
                            PICK_LABELS.getValue(band),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
