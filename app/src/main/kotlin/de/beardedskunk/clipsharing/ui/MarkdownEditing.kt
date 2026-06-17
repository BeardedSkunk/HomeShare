package de.beardedskunk.clipsharing.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.math.max
import kotlin.math.min

/**
 * Reine Texttransformationen für den Markdown-Quelltext-Editor (Toolbar-Knöpfe,
 * Listen-Autovervollständigung, Zeilen verschieben). Bewusst ohne Compose-Abhängigkeit,
 * damit sie sich unit-testen lassen.
 */

private val EDIT_TASK_RE = Regex("""^(\s*)- \[([ xX])]\s?(.*)$""")
private val EDIT_BULLET_RE = Regex("""^(\s*)([-*])\s+(.*)$""")
private val EDIT_NUMBER_RE = Regex("""^(\s*)(\d+)\.\s+(.*)$""")

/** Kippt `- [ ]` <-> `- [x]` auf einer Zeile (Einrückung bleibt erhalten). */
fun flipTaskLine(line: String): String {
    val m = EDIT_TASK_RE.matchEntire(line) ?: return line
    val indent = m.groupValues[1]
    val checked = m.groupValues[2].lowercase() == "x"
    val rest = m.groupValues[3]
    val box = if (checked) "[ ]" else "[x]"
    return "$indent- $box $rest"
}

/** Umschließt die Auswahl mit [marker]; bei leerer Auswahl wird das Paar eingefügt und der Cursor in die Mitte gesetzt. */
fun wrapSelection(v: TextFieldValue, marker: String): TextFieldValue {
    val start = min(v.selection.start, v.selection.end)
    val end = max(v.selection.start, v.selection.end)
    val t = v.text
    return if (start == end) {
        val nt = t.substring(0, start) + marker + marker + t.substring(start)
        TextFieldValue(nt, TextRange(start + marker.length))
    } else {
        val sel = t.substring(start, end)
        val nt = t.substring(0, start) + marker + sel + marker + t.substring(end)
        TextFieldValue(nt, TextRange(start + marker.length, end + marker.length))
    }
}

/**
 * Code-Knopf: Auswahl über mehrere Zeilen -> umschließender ```-Block; sonst inline `…`.
 * Leere Auswahl -> inline-Paar mit Cursor in der Mitte.
 */
fun applyCode(v: TextFieldValue): TextFieldValue {
    val start = min(v.selection.start, v.selection.end)
    val end = max(v.selection.start, v.selection.end)
    val t = v.text
    val sel = t.substring(start, end)
    return if (start != end && sel.contains('\n')) {
        // ```-Zäune müssen auf eigenen Zeilen stehen. Angebrochene Zeilen vor/nach der
        // Auswahl bleiben außerhalb des Blocks; ggf. Zeilenumbruch ergänzen.
        val prefix = t.substring(0, start)
        val suffix = t.substring(end)
        val open = (if (prefix.isNotEmpty() && !prefix.endsWith("\n")) "\n" else "") + "```\n"
        val close = "\n```" + (if (suffix.isNotEmpty() && !suffix.startsWith("\n")) "\n" else "")
        val nt = prefix + open + sel + close + suffix
        val selStart = prefix.length + open.length
        TextFieldValue(nt, TextRange(selStart, selStart + sel.length))
    } else {
        wrapSelection(v, "`")
    }
}

/** Fügt eine neue, nicht erledigte Aufgabe ein (auf eigener Zeile). */
fun insertTask(v: TextFieldValue): TextFieldValue {
    val pos = v.selection.end
    val t = v.text
    val lineStart = t.lastIndexOf('\n', max(pos - 1, 0)).let { if (it < 0) 0 else it + 1 }
    val atLineStart = pos == lineStart || t.substring(lineStart, pos).isBlank()
    val ins = if (atLineStart) "- [ ] " else "\n- [ ] "
    val nt = t.substring(0, pos) + ins + t.substring(pos)
    return TextFieldValue(nt, TextRange(pos + ins.length))
}

private data class ListItem(val prefix: String, val emptyContent: Boolean, val nextPrefix: String)

private fun listItem(line: String): ListItem? {
    EDIT_TASK_RE.matchEntire(line)?.let { m ->
        val indent = m.groupValues[1]
        val mark = if (m.groupValues[2].lowercase() == "x") "[x]" else "[ ]"
        return ListItem("$indent- $mark ", m.groupValues[3].isBlank(), "$indent- $mark ")
    }
    EDIT_NUMBER_RE.matchEntire(line)?.let { m ->
        val indent = m.groupValues[1]
        val n = m.groupValues[2].toIntOrNull() ?: 1
        return ListItem("$indent${n}. ", m.groupValues[3].isBlank(), "$indent${n + 1}. ")
    }
    EDIT_BULLET_RE.matchEntire(line)?.let { m ->
        val indent = m.groupValues[1]
        val glyph = m.groupValues[2]
        return ListItem("$indent$glyph ", m.groupValues[3].isBlank(), "$indent$glyph ")
    }
    return null
}

/**
 * Reagiert auf ein soeben getipptes Enter. Liefert den korrigierten Wert oder null
 * (dann gilt [new] unverändert):
 *  - nach einem Listeneintrag mit Inhalt: nächster Eintrag gleicher Sorte (nummeriert zählt hoch);
 *  - nach einem leeren Listeneintrag: Präfix entfernen (Liste verlassen);
 *  - Enter in einem einzeiligen `code`: wird zum ```-Block umgeformt.
 */
fun handleEnter(old: TextFieldValue, new: TextFieldValue): TextFieldValue? {
    if (new.text.length != old.text.length + 1) return null
    if (!new.selection.collapsed) return null
    val pos = new.selection.start
    if (pos <= 0 || new.text[pos - 1] != '\n') return null

    val before = new.text.substring(0, pos - 1)
    val prevLineStart = before.lastIndexOf('\n') + 1
    val prevLine = before.substring(prevLineStart)

    // Inline-`code` -> Codeblock, wenn die ganze Zeile genau ein Inline-Code ist.
    val trimmed = prevLine.trim()
    if (trimmed.length >= 2 && trimmed.startsWith("`") && trimmed.endsWith("`") && trimmed.count { it == '`' } == 2) {
        val code = trimmed.substring(1, trimmed.length - 1)
        val replacement = "```\n" + code + "\n"
        val nt = new.text.substring(0, prevLineStart) + replacement + "\n```" + new.text.substring(pos)
        val cursor = prevLineStart + replacement.length
        return TextFieldValue(nt, TextRange(cursor))
    }

    val item = listItem(prevLine) ?: return null
    return if (item.emptyContent) {
        // Leeren Eintrag + Enter: Präfix samt Zeilenumbruch entfernen (Liste verlassen).
        val nt = new.text.substring(0, prevLineStart) + new.text.substring(pos)
        TextFieldValue(nt, TextRange(prevLineStart))
    } else {
        val nt = new.text.substring(0, pos) + item.nextPrefix + new.text.substring(pos)
        TextFieldValue(nt, TextRange(pos + item.nextPrefix.length))
    }
}

/** Verschiebt die von der Auswahl berührten Zeilen um eine Position nach oben/unten. */
fun moveLines(v: TextFieldValue, up: Boolean): TextFieldValue {
    val lines = v.text.split("\n").toMutableList()
    val selStart = min(v.selection.start, v.selection.end)
    val selEnd = max(v.selection.start, v.selection.end)
    // Zeilenindizes aus Zeichenoffsets bestimmen.
    fun lineOf(offset: Int): Int {
        var acc = 0
        for (idx in lines.indices) {
            val len = lines[idx].length
            if (offset <= acc + len) return idx
            acc += len + 1
        }
        return lines.lastIndex
    }
    val first = lineOf(selStart)
    val last = lineOf(selEnd)
    // Zeile 0 ist der Titel und bleibt oben: nicht über sie hinaus nach oben schieben.
    if (up && first <= 1) return v
    if (!up && last == lines.lastIndex) return v
    val newFirst: Int
    val newLast: Int
    if (up) {
        val moved = lines.removeAt(first - 1); lines.add(last, moved)
        newFirst = first - 1; newLast = last - 1
    } else {
        val moved = lines.removeAt(last + 1); lines.add(first, moved)
        newFirst = first + 1; newLast = last + 1
    }
    val nt = lines.joinToString("\n")
    // Markierung erhalten: den verschobenen Zeilenblock weiter selektieren,
    // damit man ihn ohne Neu-Markieren um mehrere Zeilen bewegen kann.
    var startOff = 0
    for (idx in 0 until newFirst) startOff += lines[idx].length + 1
    var endOff = startOff
    for (idx in newFirst..newLast) {
        endOff += lines[idx].length
        if (idx < newLast) endOff += 1
    }
    return TextFieldValue(nt, TextRange(startOff, endOff))
}
