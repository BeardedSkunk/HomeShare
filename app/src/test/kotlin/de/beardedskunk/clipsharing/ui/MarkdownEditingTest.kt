package de.beardedskunk.clipsharing.ui

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

    @Test fun moveLines_movesSelectedLineUp() {
        // Zeilen: 0 "Titel", 1 "a", 2 "b"; Auswahl in Zeile 2 -> b nach oben.
        val v = TextFieldValue("Titel\na\nb", TextRange(8))
        val r = moveLines(v, up = true)
        assertEquals("Titel\nb\na", r.text)
    }

    @Test fun moveLines_downAtEndIsNoop() {
        val v = TextFieldValue("Titel\na\nb", TextRange(8))
        val r = moveLines(v, up = false)
        assertEquals("Titel\na\nb", r.text)
    }

    @Test fun moveLines_keepsSelectionOnMovedBlock() {
        // "Titel\na\nb\nc"; Zeile "b" (Index 2) markiert -> nach oben -> bleibt markiert.
        val v = TextFieldValue("Titel\na\nb\nc", TextRange(8, 9))
        val r = moveLines(v, up = true)
        assertEquals("Titel\nb\na\nc", r.text)
        assertEquals(6, r.selection.start)
        assertEquals(7, r.selection.end)
    }

    @Test fun moveLines_doesNotCrossTitleLine() {
        // Erste Körperzeile darf nicht über den Titel (Zeile 0) wandern.
        val v = TextFieldValue("Titel\na\nb", TextRange(6, 7))
        assertEquals("Titel\na\nb", moveLines(v, up = true).text)
    }

    @Test fun applyCode_putsFencesOnOwnLinesForPartialLines() {
        val t = "vorher UND\nmitte\nENDE rest"
        val start = t.indexOf("UND")
        val end = t.indexOf(" rest")
        val v = TextFieldValue(t, TextRange(start, end))
        val r = applyCode(v)
        assertEquals("vorher \n```\nUND\nmitte\nENDE\n```\n rest", r.text)
    }

    @Test fun moveLines_movesSelectedLineDown() {
        // Zeilen: 0 "Titel", 1 "a", 2 "b"; Auswahl in Zeile 1 -> a nach unten.
        val v = TextFieldValue("Titel\na\nb", TextRange(6))
        assertEquals("Titel\nb\na", moveLines(v, up = false).text)
    }

    @Test fun moveLines_movesMultiLineBlock() {
        // Block "a"+"b" (Zeilen 1..2) markiert -> nach unten, "c" rutscht hoch.
        val v = TextFieldValue("Titel\na\nb\nc", TextRange(6, 9))
        val r = moveLines(v, up = false)
        assertEquals("Titel\nc\na\nb", r.text)
        // Markierung bleibt auf dem verschobenen Block.
        assertEquals(8, r.selection.start)
        assertEquals(11, r.selection.end)
    }

    @Test fun moveLines_upAtFirstBodyLineIsNoop() {
        // Erste Körperzeile (Index 1) darf nicht über den Titel wandern.
        val v = TextFieldValue("Titel\na\nb", TextRange(6))
        assertEquals("Titel\na\nb", moveLines(v, up = true).text)
    }

    /**
     * Struktur-Regressionstest: pinnt die Markdown-Toolbar (Inhalt UND Reihenfolge). Verschwindet ein
     * Knopf (z. B. die Zeilen-Pfeile) oder verrutscht die Reihenfolge, schlägt dieser Test an –
     * genau das, was zuletzt unbemerkt durchgegangen wäre.
     */
    @Test fun markdownToolbar_hasAllButtonsInOrder() {
        assertEquals(
            listOf(
                MarkdownToolbarItem.TASK,
                MarkdownToolbarItem.BOLD,
                MarkdownToolbarItem.ITALIC,
                MarkdownToolbarItem.STRIKE,
                MarkdownToolbarItem.CODE,
                MarkdownToolbarItem.MOVE_UP,
                MarkdownToolbarItem.MOVE_DOWN,
                MarkdownToolbarItem.HELP,
            ),
            MARKDOWN_TOOLBAR,
        )
        // "?" muss ganz rechts hinter den Zeilen-Pfeilen stehen.
        assertEquals(MarkdownToolbarItem.HELP, MARKDOWN_TOOLBAR.last())
        // Beide Zeilen-Verschiebe-Pfeile sind vorhanden.
        assert(MARKDOWN_TOOLBAR.containsAll(listOf(MarkdownToolbarItem.MOVE_UP, MarkdownToolbarItem.MOVE_DOWN)))
    }
}
