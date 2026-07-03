package de.beardedskunk.homeshare.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownEditingTest {

    @Test fun flipTask_togglesBox() {
        assertEquals("- [x] Eier", flipTaskLine("- [ ] Eier"))
        assertEquals("- [ ] Eier", flipTaskLine("- [x] Eier"))
        assertEquals("  - [x] tief", flipTaskLine("  - [ ] tief"))
        assertEquals("kein task", flipTaskLine("kein task"))
    }

    @Test fun taskCounts_skipsTitleLine_andCounts() {
        val t = "Titel\n- [ ] a\n- [x] b\n- [x] c"
        assertEquals(2 to 3, taskCounts(t))
        // Aufgabe in der Titelzeile zählt nicht.
        assertNull(taskCounts("- [ ] nur Titel"))
        assertNull(taskCounts("Titel\nnormaler Text"))
    }

    @Test fun wrapSelection_emptyPutsCursorInMiddle() {
        val v = TextFieldValue("ab", TextRange(1))
        val r = wrapSelection(v, "**")
        assertEquals("a****b", r.text)
        assertEquals(3, r.selection.start)
        assertEquals(3, r.selection.end)
    }

    @Test fun wrapSelection_wrapsSelection() {
        val v = TextFieldValue("hello", TextRange(0, 5))
        val r = wrapSelection(v, "**")
        assertEquals("**hello**", r.text)
        assertEquals(2, r.selection.start)
        assertEquals(7, r.selection.end)
    }

    @Test fun toggleWrap_wrapsThenUnwraps() {
        val wrapped = toggleWrap(TextFieldValue("hello", TextRange(0, 5)), "**")
        assertEquals("**hello**", wrapped.text)
        // Innenauswahl: Marker stehen außerhalb -> entfernen.
        val unwrapInner = toggleWrap(TextFieldValue("**hello**", TextRange(2, 7)), "**")
        assertEquals("hello", unwrapInner.text)
        assertEquals(0, unwrapInner.selection.start)
        assertEquals(5, unwrapInner.selection.end)
    }

    @Test fun toggleWrap_unwrapsWhenMarkersInSelection() {
        // Auswahl umfasst die Tilden mit -> trotzdem entfernen.
        val r = toggleWrap(TextFieldValue("~~Wort~~", TextRange(0, 8)), "~~")
        assertEquals("Wort", r.text)
        assertEquals(0, r.selection.start)
        assertEquals(4, r.selection.end)
    }

    @Test fun toggleWrap_emptySelectionInsertsPair() {
        val r = toggleWrap(TextFieldValue("ab", TextRange(1)), "*")
        assertEquals("a**b", r.text)
        assertEquals(2, r.selection.start)
    }

    @Test fun applyCode_blockWhenMultiline_inlineOtherwise() {
        val multi = TextFieldValue("x\ny", TextRange(0, 3))
        assertEquals("```\nx\ny\n```", applyCode(multi).text)
        val single = TextFieldValue("word", TextRange(0, 4))
        assertEquals("`word`", applyCode(single).text)
    }

    @Test fun handleEnter_continuesBullet() {
        val old = TextFieldValue("Titel\n- a", TextRange(9))
        val new = TextFieldValue("Titel\n- a\n", TextRange(10))
        val r = handleEnter(old, new)!!
        assertEquals("Titel\n- a\n- ", r.text)
        assertEquals(12, r.selection.start)
    }

    @Test fun handleEnter_incrementsNumbered() {
        val old = TextFieldValue("T\n1. a", TextRange(6))
        val new = TextFieldValue("T\n1. a\n", TextRange(7))
        val r = handleEnter(old, new)!!
        assertEquals("T\n1. a\n2. ", r.text)
    }

    @Test fun handleEnter_keepsCheckedState() {
        val old = TextFieldValue("T\n- [x] a", TextRange(9))
        val new = TextFieldValue("T\n- [x] a\n", TextRange(10))
        val r = handleEnter(old, new)!!
        assertEquals("T\n- [x] a\n- [x] ", r.text)
    }

    @Test fun handleEnter_emptyItemExitsList() {
        val old = TextFieldValue("T\n- ", TextRange(4))
        val new = TextFieldValue("T\n- \n", TextRange(5))
        val r = handleEnter(old, new)!!
        assertEquals("T\n", r.text)
        assertEquals(2, r.selection.start)
    }

    @Test fun handleEnter_inlineCodeBecomesBlock() {
        val old = TextFieldValue("T\n`code`", TextRange(8))
        val new = TextFieldValue("T\n`code`\n", TextRange(9))
        val r = handleEnter(old, new)!!
        assertEquals("T\n```\ncode\n\n```", r.text)
    }

    @Test fun handleEnter_ignoresNonListLine() {
        val old = TextFieldValue("T\nfließtext", TextRange(11))
        val new = TextFieldValue("T\nfließtext\n", TextRange(12))
        assertNull(handleEnter(old, new))
    }

    @Test fun moveLineTo_movesLineDown() {
        assertEquals("Titel\nb\na\nc", moveLineTo("Titel\na\nb\nc", 2, 1))
        assertEquals("Titel\nb\nc\na", moveLineTo("Titel\na\nb\nc", 1, 3))
    }

    @Test fun moveLineTo_protectsTitleLine() {
        // Zeile 0 (Titel) ist weder Quelle noch Ziel.
        assertEquals("Titel\na\nb", moveLineTo("Titel\na\nb", 0, 1))
        assertEquals("Titel\na\nb", moveLineTo("Titel\na\nb", 1, 0))
    }

    @Test fun moveLineTo_ignoresOutOfRangeAndNoop() {
        assertEquals("Titel\na\nb", moveLineTo("Titel\na\nb", 1, 1))
        assertEquals("Titel\na\nb", moveLineTo("Titel\na\nb", 5, 1))
        assertEquals("Titel\na\nb", moveLineTo("Titel\na\nb", 1, 9))
    }

    @Test fun deleteLineWithChildren_removesLineAndDeeperIndented() {
        val t = "Titel\n- [ ] a\n  - [ ] a1\n    - a1x\n- [ ] b"
        assertEquals("Titel\n- [ ] b", deleteLineWithChildren(t, 1))
        // Nur das Kind entfernen: Geschwister gleicher Tiefe bleiben.
        assertEquals("Titel\n- [ ] a\n- [ ] b", deleteLineWithChildren(t, 2))
    }

    @Test fun deleteLineWithChildren_protectsTitleAndRange() {
        assertEquals("Titel\na", deleteLineWithChildren("Titel\na", 0))
        assertEquals("Titel\na", deleteLineWithChildren("Titel\na", 5))
        assertEquals("Titel", deleteLineWithChildren("Titel\na", 1))
    }

    @Test fun applyCode_putsFencesOnOwnLinesForPartialLines() {
        val t = "vorher UND\nmitte\nENDE rest"
        val start = t.indexOf("UND")
        val end = t.indexOf(" rest")
        val v = TextFieldValue(t, TextRange(start, end))
        val r = applyCode(v)
        assertEquals("vorher \n```\nUND\nmitte\nENDE\n```\n rest", r.text)
    }


    /**
     * Struktur-Regressionstest: pinnt die Markdown-Toolbar (Inhalt UND Reihenfolge). Die früheren
     * ↑/↓-Zeilen-Pfeile sind BEWUSST entfernt (2026-07): Zeilen werden jetzt per Drag-Handle in
     * der gerenderten Ansicht umsortiert.
     */
    @Test fun markdownToolbar_hasAllButtonsInOrder() {
        assertEquals(
            listOf(
                MarkdownToolbarItem.TASK,
                MarkdownToolbarItem.BOLD,
                MarkdownToolbarItem.ITALIC,
                MarkdownToolbarItem.STRIKE,
                MarkdownToolbarItem.CODE,
                MarkdownToolbarItem.HELP,
            ),
            MARKDOWN_TOOLBAR,
        )
        // "?" muss ganz rechts stehen.
        assertEquals(MarkdownToolbarItem.HELP, MARKDOWN_TOOLBAR.last())
    }
}
