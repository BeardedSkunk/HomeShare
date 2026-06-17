package de.beardedskunk.clipsharing.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Sehr leichter Markdown-Renderer für ClipSharing – bewusst kein schweres Fremd-Lib,
 * damit (a) die erste Zeile als markup-freier Titel ausgenommen werden kann und
 * (b) Aufgaben-Haken antippbar sind und beim Umschalten eine neue Version erzeugen.
 *
 * Unterstützt: ## Überschrift, - / * Aufzählung, 1. nummeriert, - [ ]/- [x] Aufgaben,
 * eingerückte Verschachtelung, > Zitat, ``` Codeblock, `inline code`, **fett**,
 * *kursiv*, ~~durchgestrichen~~, --- Trennlinie, [Text](url).
 */

/** Erste Zeile = Titel (markup-frei). */
fun postTitle(text: String): String = text.substringBefore('\n').trim()

/** Alles ab Zeile 2 = Markdown-Körper. */
fun postBody(text: String): String = text.substringAfter('\n', "")

private val TASK_RE = Regex("""^(\s*)- \[([ xX])]\s?(.*)$""")
private val BULLET_RE = Regex("""^(\s*)[-*]\s+(.*)$""")
private val NUMBER_RE = Regex("""^(\s*)(\d+)\.\s+(.*)$""")
private val HEAD_RE = Regex("""^(#{1,6})\s+(.*)$""")
private val QUOTE_RE = Regex("""^>\s?(.*)$""")
private val RULE_RE = Regex("""^\s*(-{3,}|\*{3,}|_{3,})\s*$""")

/**
 * Aufgaben-Zählung im Körper (Titelzeile übersprungen): (erledigt, gesamt) oder null,
 * wenn der Körper keine Aufgabenliste enthält. Für das ☑-Badge in der Feed-Liste.
 */
fun taskCounts(text: String): Pair<Int, Int>? {
    val lines = text.split("\n")
    var total = 0
    var done = 0
    for (i in 1 until lines.size) {
        val m = TASK_RE.matchEntire(lines[i]) ?: continue
        total++
        if (m.groupValues[2].lowercase() == "x") done++
    }
    return if (total == 0) null else done to total
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val spans: AnnotatedString) : MdBlock
    data class Para(val spans: AnnotatedString) : MdBlock
    data class Bullet(val indent: Int, val spans: AnnotatedString) : MdBlock
    data class Numbered(val indent: Int, val number: Int, val spans: AnnotatedString) : MdBlock
    data class Task(val indent: Int, val checked: Boolean, val spans: AnnotatedString, val sourceLine: Int) : MdBlock
    data class Quote(val spans: AnnotatedString) : MdBlock
    data class Code(val text: String) : MdBlock
    data object Rule : MdBlock
    data object Blank : MdBlock
}

/** Parst [body] in Blöcke; [lineOffset] = absolute Zeilennummer der ersten Körperzeile im Gesamttext. */
private fun parseBlocks(body: String, lineOffset: Int): List<MdBlock> {
    if (body.isEmpty()) return emptyList()
    val lines = body.split("\n")
    val out = ArrayList<MdBlock>()
    var i = 0
    var numberCounter = 0
    var lastWasNumber = false
    while (i < lines.size) {
        val line = lines[i]
        if (line.trim().startsWith("```")) {
            // Codeblock bis zum schließenden ``` (oder Ende).
            val sb = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(lines[i]); i++
            }
            if (i < lines.size) i++ // schließendes ``` überspringen
            out += MdBlock.Code(sb.toString())
            lastWasNumber = false
            continue
        }
        val task = TASK_RE.matchEntire(line)
        val head = HEAD_RE.matchEntire(line)
        val num = NUMBER_RE.matchEntire(line)
        val bullet = BULLET_RE.matchEntire(line)
        val quote = QUOTE_RE.matchEntire(line)
        when {
            line.isBlank() -> { out += MdBlock.Blank; lastWasNumber = false }
            RULE_RE.matchEntire(line) != null -> { out += MdBlock.Rule; lastWasNumber = false }
            head != null -> { out += MdBlock.Heading(head.groupValues[1].length, parseInline(head.groupValues[2])); lastWasNumber = false }
            task != null -> {
                out += MdBlock.Task(task.groupValues[1].length, task.groupValues[2].lowercase() == "x", parseInline(task.groupValues[3]), lineOffset + i)
                lastWasNumber = false
            }
            num != null -> {
                numberCounter = if (lastWasNumber) numberCounter + 1 else 1
                out += MdBlock.Numbered(num.groupValues[1].length, numberCounter, parseInline(num.groupValues[3]))
                lastWasNumber = true
            }
            bullet != null -> { out += MdBlock.Bullet(bullet.groupValues[1].length, parseInline(bullet.groupValues[2])); lastWasNumber = false }
            quote != null -> { out += MdBlock.Quote(parseInline(quote.groupValues[1])); lastWasNumber = false }
            else -> { out += MdBlock.Para(parseInline(line)); lastWasNumber = false }
        }
        i++
    }
    return out
}

private val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
private val boldStyle = SpanStyle(fontWeight = FontWeight.Bold)
private val italicStyle = SpanStyle(fontStyle = FontStyle.Italic)
private val strikeStyle = SpanStyle(textDecoration = TextDecoration.LineThrough)
private val linkStyle = SpanStyle(color = Color(0xFF185FA5), textDecoration = TextDecoration.Underline)

/** Inline-Markup -> AnnotatedString. Rekursiv für Verschachtelung (z. B. **fett *kursiv***). */
fun parseInline(text: String): AnnotatedString = buildAnnotatedString {
    appendInline(text, SpanStyle())
}

private fun AnnotatedString.Builder.appendInline(text: String, base: SpanStyle) {
    val plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) { withStyle(base) { append(plain.toString()) }; plain.clear() }
    }
    var i = 0
    val n = text.length
    while (i < n) {
        when {
            text.startsWith("`", i) -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) { flush(); withStyle(base.merge(codeStyle)) { append(text.substring(i + 1, end)) }; i = end + 1; continue }
            }
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 1) { flush(); appendInline(text.substring(i + 2, end), base.merge(boldStyle)); i = end + 2; continue }
            }
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 1) { flush(); appendInline(text.substring(i + 2, end), base.merge(strikeStyle)); i = end + 2; continue }
            }
            text.startsWith("*", i) -> {
                val end = text.indexOf("*", i + 1)
                if (end > i) { flush(); appendInline(text.substring(i + 1, end), base.merge(italicStyle)); i = end + 1; continue }
            }
            text.startsWith("[", i) -> {
                val close = text.indexOf(']', i + 1)
                if (close > i && close + 1 < n && text[close + 1] == '(') {
                    val pend = text.indexOf(')', close + 2)
                    if (pend > close + 1) {
                        flush()
                        withStyle(base.merge(linkStyle)) { append(text.substring(i + 1, close)) }
                        i = pend + 1; continue
                    }
                }
            }
        }
        plain.append(text[i]); i++
    }
    flush()
}

/**
 * Rendert den Markdown-Körper. [onToggleTask] (sofern gesetzt) macht Haken antippbar;
 * der Parameter ist die absolute Zeilennummer im Gesamttext.
 */
@Composable
fun MarkdownBody(
    text: String,
    modifier: Modifier = Modifier,
    onToggleTask: ((sourceLine: Int) -> Unit)? = null,
) {
    val blocks = parseBlocks(postBody(text), lineOffset = 1)
    Column(modifier) {
        for (b in blocks) {
            when (b) {
                is MdBlock.Blank -> Spacer(Modifier.height(6.dp))
                is MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 6.dp))
                is MdBlock.Heading -> Text(
                    b.spans,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                )
                is MdBlock.Para -> Text(b.spans, style = MaterialTheme.typography.bodyLarge)
                is MdBlock.Bullet -> ListRow(b.indent, bulletGlyph(b.indent)) { Text(b.spans, style = MaterialTheme.typography.bodyLarge) }
                is MdBlock.Numbered -> ListRow(b.indent, "${b.number}.") { Text(b.spans, style = MaterialTheme.typography.bodyLarge) }
                is MdBlock.Quote -> Row(Modifier.padding(vertical = 2.dp)) {
                    Surface(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.width(3.dp).height(20.dp)) {}
                    Text(b.spans, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                }
                is MdBlock.Code -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                ) {
                    Text(b.text, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(10.dp))
                }
                is MdBlock.Task -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 1.dp).padding(start = (b.indent * 8).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = b.checked,
                        onCheckedChange = if (onToggleTask != null) { _ -> onToggleTask(b.sourceLine) } else null,
                        enabled = onToggleTask != null,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    val spans = if (b.checked) {
                        buildAnnotatedString { withStyle(strikeStyle) { append(b.spans) } }
                    } else b.spans
                    Text(
                        spans,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (b.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun ListRow(indent: Int, marker: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp, horizontal = 0.dp).padding(start = (indent * 8).dp)) {
        Text(marker, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(22.dp).padding(top = 0.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

private fun bulletGlyph(indent: Int): String = if (indent >= 2) "◦" else "•"
