package de.beardedskunk.homeshare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * Gemeinsames Markdown-Quelltextfeld: Textbox + Markdown-Toolbar + Hilfe-Dialog +
 * Enter-Listenfortführung. Von Notiz-, Anhang-, Termin- und Listen-Kopf-Editor benutzt —
 * Änderungen am Editier-Verhalten bitte NUR hier.
 */
@Composable
fun MarkdownEditField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    /** Modifier fürs Textfeld selbst (testTag, heightIn, padding — vom Aufrufer). */
    fieldModifier: Modifier = Modifier,
    placeholder: String = "Titel (1. Zeile), dann Markdown…",
    minLines: Int = 1,
    /** Nach Toolbar-Aktionen den Fokus zurück ins Feld holen (falls gesetzt). */
    focusRequester: FocusRequester? = null,
) {
    var helpOpen by remember { mutableStateOf(false) }
    if (helpOpen) MarkdownHelpDialog(onDismiss = { helpOpen = false })
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = { nv -> onValueChange(handleEnter(value, nv) ?: nv) },
            placeholder = { Text(placeholder) },
            minLines = minLines,
            modifier = Modifier.fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .then(fieldModifier),
        )
        MarkdownToolbar(
            value = value,
            apply = { transform -> onValueChange(transform(value)); focusRequester?.requestFocus() },
            onHelp = { helpOpen = true },
        )
    }
}

/**
 * Gemeinsamer Render-Kopf: Titel (headlineSmall) + Ausklapp-Chevron + gerenderter Markdown-Body.
 * [text] = voller Knotentext (Zeile 1 = Titel). [emptyTitle] = Fallback bei leerem Titel;
 * null = Titelzeile bei leerem Titel weglassen (AttachmentDetailScreen-Verhalten).
 */
@Composable
fun MarkdownRenderHeader(
    text: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    emptyTitle: String? = "(ohne Titel)",
    onToggleTask: ((sourceLine: Int) -> Unit)? = null,
    onEditAt: ((sourceOffset: Int) -> Unit)? = null,
    highlight: String? = null,
) {
    val title = postTitle(text)
    val hasBody = postBody(text).isNotBlank()
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (title.isNotBlank() || emptyTitle != null) {
                Text(
                    title.ifBlank { emptyTitle.orEmpty() },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).tag("header:title"),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (hasBody) ExpandChevron(expanded = expanded, onToggle = { onExpandedChange(!expanded) })
        }
        if (expanded && hasBody) {
            MarkdownBody(
                text = text,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                onToggleTask = onToggleTask, onEditAt = onEditAt, highlight = highlight,
            )
        }
    }
}

/**
 * Markdown-Toolbar (Aufgabe/Fett/Kursiv/Durchgestrichen/Code/Hilfe).
 * [value] dient der Aktiv/Inaktiv-Logik (Titelzeile), [apply] wendet eine
 * Transformation auf das zugehörige Feld an.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarkdownToolbar(
    value: TextFieldValue,
    apply: ((TextFieldValue) -> TextFieldValue) -> Unit,
    onHelp: () -> Unit,
) {
    // Auf der Titelzeile (1. Zeile) sind die Format-Knöpfe inaktiv.
    val firstNl = value.text.indexOf('\n').let { if (it < 0) value.text.length else it }
    val onTitleLine = value.selection.start <= firstNl
    // Rendert genau MARKDOWN_TOOLBAR (datengetrieben + exhaustives when => kein Knopf fällt unbemerkt weg).
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (item in MARKDOWN_TOOLBAR) when (item) {
            MarkdownToolbarItem.TASK -> TbButton("☐ Aufgabe", enabled = !onTitleLine, tag = "toolbar:task") { apply(::insertTask) }
            MarkdownToolbarItem.BOLD -> TbButton("B", enabled = !onTitleLine, bold = true, tag = "toolbar:bold") { apply { toggleWrap(it, "**") } }
            MarkdownToolbarItem.ITALIC -> TbButton("I", enabled = !onTitleLine, italic = true, tag = "toolbar:italic") { apply { toggleWrap(it, "*") } }
            MarkdownToolbarItem.STRIKE -> TbButton("S", enabled = !onTitleLine, strike = true, tag = "toolbar:strike") { apply { toggleWrap(it, "~~") } }
            MarkdownToolbarItem.CODE -> TbButton("</>", enabled = !onTitleLine, tag = "toolbar:code") { apply { applyCode(it) } }
            MarkdownToolbarItem.HELP -> TbButton("?", enabled = true, tag = "toolbar:help") { onHelp() }
        }
    }
}

/** Kleiner Toolbar-Knopf. defaultMinSize hebt den 58-dp-Mindestbreiten-Boden von TextButton auf,
 *  sonst passen die Knöpfe auf schmalen Geräten nicht nebeneinander. */
@Composable
private fun TbButton(label: String, enabled: Boolean, bold: Boolean = false, italic: Boolean = false, strike: Boolean = false, tag: String = "", onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 36.dp).then(if (tag.isEmpty()) Modifier else Modifier.tag(tag)),
    ) {
        Text(
            label,
            color = color,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
            textDecoration = if (strike) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
        )
    }
}

/** Kurzhilfe zu Markdown. */
@Composable
private fun MarkdownHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
        title = { Text("Markdown – Kurzhilfe") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                HelpRow("# Titel ist die 1. Zeile", "schmucklos, kein Markdown")
                HelpRow("## Überschrift", "Abschnitts-Überschrift")
                HelpRow("- Eintrag", "Aufzählung")
                HelpRow("1. Eintrag", "nummerierte Liste")
                HelpRow("- [ ] offen", "offene Aufgabe")
                HelpRow("- [x] erledigt", "erledigte Aufgabe")
                HelpRow("  - eingerückt", "Unterpunkt (2 Leerzeichen)")
                HelpRow("> Zitat", "Zitat")
                HelpRow("--- ", "Trennlinie")
                HelpRow("[Text](https://…)", "Link")
                Spacer(Modifier.size(8.dp))
                Text(
                    "Enter setzt Listen automatisch fort; leerer Eintrag + Enter beendet die Liste. Fett, kursiv, durchgestrichen und Code haben eigene Knöpfe.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun HelpRow(syntax: String, meaning: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(syntax, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(170.dp))
        Text(meaning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
