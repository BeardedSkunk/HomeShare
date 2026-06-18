package de.beardedskunk.clipsharing.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
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

sealed interface MdBlock {
    data class Heading(val level: Int, val inline: Inline) : MdBlock
    data class Para(val inline: Inline) : MdBlock
    data class Bullet(val indent: Int, val inline: Inline) : MdBlock
    data class Numbered(val indent: Int, val number: Int, val inline: Inline) : MdBlock
    data class Task(val indent: Int, val checked: Boolean, val inline: Inline, val sourceLine: Int) : MdBlock
    data class Quote(val inline: Inline) : MdBlock
    data class Code(val text: String, val srcStart: Int) : MdBlock
    data object Rule : MdBlock
    data object Blank : MdBlock

    /** Gerenderter (markup-freier) Klartext dieses Blocks – für die Such-Treffersuche. */
    val plain: String
        get() = when (this) {
            is Heading -> inline.spans.text
            is Para -> inline.spans.text
            is Bullet -> inline.spans.text
            is Numbered -> inline.spans.text
            is Task -> inline.spans.text
            is Quote -> inline.spans.text
            is Code -> text
            Rule, Blank -> ""
        }
}

/** Parst den Körper (ab Zeile 2) in Blöcke – öffentlich für die Render-Suche. */
fun parseMarkdownBody(text: String): List<MdBlock> {
    val nl = text.indexOf('\n')
    val bodyStart = if (nl < 0) text.length else nl + 1
    return parseBlocks(postBody(text), lineOffset = 1, bodyStart = bodyStart)
}

/** Alle (case-insensitiven) Treffer-Bereiche von [query] in [text]. */
fun matchRanges(text: String, query: String): List<IntRange> {
    if (query.isBlank()) return emptyList()
    val out = ArrayList<IntRange>()
    var i = text.indexOf(query, 0, ignoreCase = true)
    while (i >= 0) {
        out += i until (i + query.length)
        i = text.indexOf(query, i + query.length, ignoreCase = true)
    }
    return out
}

/**
 * Parst [body] in Blöcke; [lineOffset] = absolute Zeilennummer der ersten Körperzeile im
 * Gesamttext, [bodyStart] = absoluter Zeichen-Offset des Körpers im Gesamttext (für die
 * Tipp-zur-Quellstelle-Karte).
 */
private fun parseBlocks(body: String, lineOffset: Int, bodyStart: Int): List<MdBlock> {
    if (body.isEmpty()) return emptyList()
    val lines = body.split("\n")
    // Zeichen-Offset jeder Zeile im Körper.
    val lineStart = IntArray(lines.size)
    run { var acc = 0; for (k in lines.indices) { lineStart[k] = acc; acc += lines[k].length + 1 } }
    fun col(m: MatchResult, group: Int) = m.groups[group]!!.range.first
    val out = ArrayList<MdBlock>()
    var i = 0
    var numberCounter = 0
    var lastWasNumber = false
    while (i < lines.size) {
        val line = lines[i]
        val abs = bodyStart + lineStart[i]
        if (line.trim().startsWith("```")) {
            // Codeblock bis zum schließenden ``` (oder Ende).
            val sb = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(lines[i]); i++
            }
            if (i < lines.size) i++ // schließendes ``` überspringen
            out += MdBlock.Code(sb.toString(), abs)
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
            head != null -> { out += MdBlock.Heading(head.groupValues[1].length, buildInline(head.groupValues[2], abs + col(head, 2))); lastWasNumber = false }
            task != null -> {
                out += MdBlock.Task(task.groupValues[1].length, task.groupValues[2].lowercase() == "x", buildInline(task.groupValues[3], abs + col(task, 3)), lineOffset + i)
                lastWasNumber = false
            }
            num != null -> {
                numberCounter = if (lastWasNumber) numberCounter + 1 else 1
                out += MdBlock.Numbered(num.groupValues[1].length, numberCounter, buildInline(num.groupValues[3], abs + col(num, 3)))
                lastWasNumber = true
            }
            bullet != null -> { out += MdBlock.Bullet(bullet.groupValues[1].length, buildInline(bullet.groupValues[2], abs + col(bullet, 2))); lastWasNumber = false }
            quote != null -> { out += MdBlock.Quote(buildInline(quote.groupValues[1], abs + col(quote, 1))); lastWasNumber = false }
            else -> { out += MdBlock.Para(buildInline(line, abs)); lastWasNumber = false }
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

/**
 * Aufbereitetes Inline-Stück: die gerenderte [spans] plus eine Karte [map], die jede
 * gerenderte Zeichenposition auf den Quell-Offset (relativ zu [srcStart]) zurückrechnet –
 * damit ein Tipp in der gerenderten Ansicht möglichst zeichengenau die Stelle im Quelltext
 * trifft (die Markdown-Zeichen wie ** oder - [ ] sind herausgerechnet). [srcLen] = Länge des
 * Quell-Inline-Strings (für Tipp ans Zeilenende).
 */
class Inline(val spans: AnnotatedString, val srcStart: Int, val map: IntArray, val srcLen: Int) {
    /** Quell-Offset (absolut) für eine gerenderte Zeichenposition. */
    fun sourceOffset(rendered: Int): Int =
        srcStart + (if (rendered in map.indices) map[rendered] else srcLen)
}

/** Inline-Markup -> AnnotatedString (ohne Karte; für einfache Fälle/Tests). */
fun parseInline(text: String): AnnotatedString = buildInline(text, 0).spans

/** Wie [parseInline], aber mit Offset-Karte; [srcStart] = absoluter Quell-Offset des Strings. */
fun buildInline(text: String, srcStart: Int): Inline {
    val b = AnnotatedString.Builder()
    val map = ArrayList<Int>(text.length)
    appendInline(b, text, 0, SpanStyle(), map)
    return Inline(b.toAnnotatedString(), srcStart, map.toIntArray(), text.length)
}

private fun appendInline(b: AnnotatedString.Builder, text: String, base: Int, style: SpanStyle, map: MutableList<Int>) {
    val run = StringBuilder()
    val runSrc = ArrayList<Int>()
    fun flush() {
        if (run.isNotEmpty()) {
            b.withStyle(style) { append(run.toString()) }
            map.addAll(runSrc)
            run.clear(); runSrc.clear()
        }
    }
    fun appendRun(s: String, srcBase: Int, st: SpanStyle) {
        flush()
        b.withStyle(st) { append(s) }
        for (k in s.indices) map.add(srcBase + k)
    }
    var i = 0
    val n = text.length
    while (i < n) {
        when {
            text.startsWith("`", i) -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) { appendRun(text.substring(i + 1, end), base + i + 1, style.merge(codeStyle)); i = end + 1; continue }
            }
            text.startsWith("***", i) -> {
                // fett + kursiv gleichzeitig (vor ** und * prüfen, sonst gewinnt **).
                val end = text.indexOf("***", i + 3)
                if (end > i + 2) { flush(); appendInline(b, text.substring(i + 3, end), base + i + 3, style.merge(boldStyle).merge(italicStyle), map); i = end + 3; continue }
            }
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 1) { flush(); appendInline(b, text.substring(i + 2, end), base + i + 2, style.merge(boldStyle), map); i = end + 2; continue }
            }
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 1) { flush(); appendInline(b, text.substring(i + 2, end), base + i + 2, style.merge(strikeStyle), map); i = end + 2; continue }
            }
            text.startsWith("*", i) -> {
                val end = text.indexOf("*", i + 1)
                if (end > i) { flush(); appendInline(b, text.substring(i + 1, end), base + i + 1, style.merge(italicStyle), map); i = end + 1; continue }
            }
            text.startsWith("[", i) -> {
                val close = text.indexOf(']', i + 1)
                if (close > i && close + 1 < n && text[close + 1] == '(') {
                    val pend = text.indexOf(')', close + 2)
                    if (pend > close + 1) {
                        appendRun(text.substring(i + 1, close), base + i + 1, style.merge(linkStyle))
                        i = pend + 1; continue
                    }
                }
            }
        }
        run.append(text[i]); runSrc.add(base + i); i++
    }
    flush()
}

/**
 * Rendert den Markdown-Körper. [onToggleTask] (sofern gesetzt) macht Haken antippbar (Parameter =
 * absolute Zeilennummer). [onEditAt] (sofern gesetzt) öffnet bei Tipp auf Text die Edit-Ansicht
 * an der getippten Quellstelle (Parameter = absoluter Zeichen-Offset im Gesamttext, Tbd #2).
 */
@Composable
fun MarkdownBody(
    text: String,
    modifier: Modifier = Modifier,
    onToggleTask: ((sourceLine: Int) -> Unit)? = null,
    onEditAt: ((sourceOffset: Int) -> Unit)? = null,
    highlight: String? = null,
) {
    val body = MaterialTheme.typography.bodyLarge
    Column(modifier) {
        for (b in parseMarkdownBody(text)) MdBlockView(b, body, onToggleTask, onEditAt, highlight)
    }
}

/** Rendert EINEN Markdown-Block (für die block-weise Render-Suche per LazyColumn). */
@Composable
fun MdBlockView(
    b: MdBlock,
    body: TextStyle,
    onToggleTask: ((sourceLine: Int) -> Unit)?,
    onEditAt: ((sourceOffset: Int) -> Unit)?,
    highlight: String? = null,
    currentRange: IntRange? = null,
) {
    when (b) {
        is MdBlock.Blank -> Spacer(Modifier.height(6.dp))
        is MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 6.dp))
        is MdBlock.Heading -> MdText(
            b.inline, MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
            onEditAt, Modifier.padding(top = 4.dp, bottom = 2.dp), highlight = highlight, current = currentRange,
        )
        is MdBlock.Para -> MdText(b.inline, body, onEditAt, highlight = highlight, current = currentRange)
        is MdBlock.Bullet -> ListRow(b.indent, bulletGlyph(b.indent)) { MdText(b.inline, body, onEditAt, highlight = highlight, current = currentRange) }
        is MdBlock.Numbered -> ListRow(b.indent, "${b.number}.") { MdText(b.inline, body, onEditAt, highlight = highlight, current = currentRange) }
        is MdBlock.Quote -> Row(Modifier.padding(vertical = 2.dp)) {
            Surface(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.width(3.dp).height(20.dp)) {}
            MdText(b.inline, body, onEditAt, Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, highlight = highlight, current = currentRange)
        }
        is MdBlock.Code -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        ) {
            val codeMod = Modifier.padding(10.dp).let { if (onEditAt != null) it.clickable { onEditAt(b.srcStart) } else it }
            Text(highlighted(AnnotatedString(b.text), highlight, currentRange), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, modifier = codeMod)
        }
        is MdBlock.Task -> Row(
            Modifier.fillMaxWidth().padding(vertical = 1.dp).padding(start = (b.indent * 8).dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = b.checked,
                onCheckedChange = if (onToggleTask != null) { _ -> onToggleTask(b.sourceLine) } else null,
                enabled = onToggleTask != null,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(8.dp))
            MdText(
                b.inline, body, onEditAt,
                color = if (b.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                strike = b.checked, highlight = highlight, current = currentRange,
            )
        }
    }
}

private val MATCH_BG = Color(0x55FFEB3B)        // alle Treffer: gelb, transparent
private val MATCH_BG_CURRENT = Color(0xFFFFB300) // aktueller Treffer: kräftiges Orange

/** Such-Hervorhebung über einem reinen String (z. B. dem Titel). */
fun highlightedText(text: String, highlight: String?, current: IntRange?): AnnotatedString =
    highlighted(AnnotatedString(text), highlight, current)

/** Legt Treffer-Hintergründe über eine AnnotatedString (Such-Hervorhebung). */
private fun highlighted(s: AnnotatedString, highlight: String?, current: IntRange?): AnnotatedString {
    if (highlight.isNullOrBlank()) return s
    val ranges = matchRanges(s.text, highlight)
    if (ranges.isEmpty()) return s
    return buildAnnotatedString {
        append(s)
        for (r in ranges) {
            val isCur = current != null && r.first == current.first
            addStyle(SpanStyle(background = if (isCur) MATCH_BG_CURRENT else MATCH_BG), r.first, r.last + 1)
        }
    }
}

/**
 * Text eines Inline-Blocks. [onEditAt] = Tipp öffnet die Edit-Ansicht an der Quellstelle.
 * [highlight] hebt Such-Treffer hervor, [current] markiert den aktuellen Treffer stärker.
 */
@Composable
private fun MdText(
    inline: Inline,
    style: TextStyle,
    onEditAt: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    strike: Boolean = false,
    highlight: String? = null,
    current: IntRange? = null,
) {
    var layout by remember(inline) { mutableStateOf<TextLayoutResult?>(null) }
    val tapMod = if (onEditAt != null) {
        modifier.pointerInput(inline, onEditAt) {
            detectTapGestures { pos ->
                val rendered = layout?.getOffsetForPosition(pos) ?: 0
                onEditAt(inline.sourceOffset(rendered))
            }
        }
    } else {
        modifier
    }
    Text(
        highlighted(inline.spans, highlight, current),
        style = if (strike) style.copy(textDecoration = TextDecoration.LineThrough) else style,
        color = color,
        modifier = tapMod,
        onTextLayout = { layout = it },
    )
}

@Composable
private fun ListRow(indent: Int, marker: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp, horizontal = 0.dp).padding(start = (indent * 8).dp)) {
        Text(marker, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(22.dp).padding(top = 0.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

private fun bulletGlyph(indent: Int): String = if (indent >= 2) "◦" else "•"
