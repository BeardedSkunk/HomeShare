package de.beardedskunk.homeshare.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Gemeinsame Kleinteile der UI-Screens.

/** Zurück-/Abbrechen-Pfeil für TopAppBar-navigationIcon. */
@Composable
fun BackIconButton(onClick: () -> Unit, contentDescription: String = "Zurück") {
    IconButton(onClick = onClick, modifier = Modifier.tag("topbar:back")) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = contentDescription)
    }
}

fun toast(context: Context, msg: String, long: Boolean = false) {
    Toast.makeText(context, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

/** Alle Start-Indizes von [needle] in [haystack] (case-insensitive, ueberlappungsfrei). */
internal fun findAllMatches(haystack: String, needle: String): List<Int> {
    if (needle.isEmpty()) return emptyList()
    val out = ArrayList<Int>()
    var from = 0
    while (true) {
        val idx = haystack.indexOf(needle, from, ignoreCase = true)
        if (idx < 0) break
        out += idx
        from = idx + needle.length
    }
    return out
}

/**
 * Ausklapp-/Einklapp-Chevron (▲/▼) für Kopfzeilen mit optionalem Markdown-Body.
 * Gemeinsam genutzt von der Listen-Kopfzeile (ListHeader) und der Anhang-Detailansicht.
 */
@Composable
fun ExpandChevron(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "header:expand",
) {
    IconButton(onClick = onToggle, modifier = modifier.tag(tag)) {
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "Einklappen" else "Ausklappen",
        )
    }
}
