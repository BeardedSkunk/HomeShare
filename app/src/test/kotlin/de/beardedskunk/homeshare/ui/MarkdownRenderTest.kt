package de.beardedskunk.homeshare.ui

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRenderTest {

    @Test fun boldItalicCombined_strippedAndStyled() {
        val a = parseInline("***fett+kursiv***")
        // Marker entfernt, nur der Inhalt bleibt.
        assertEquals("fett+kursiv", a.text)
        val span = a.spanStyles.firstOrNull {
            it.item.fontWeight == FontWeight.Bold && it.item.fontStyle == FontStyle.Italic
        }
        assertTrue("erwartete fett+kursiv-Span", span != null)
        assertEquals(0, span!!.start)
        assertEquals("fett+kursiv".length, span.end)
    }

    @Test fun inlineOffsetMap_pointsToSourceCharsBehindMarkers() {
        // Quelle: "**fett** x"  ->  gerendert: "fett x"
        val inline = buildInline("**fett** x", 100)
        assertEquals("fett x", inline.spans.text)
        // gerendertes 'x' steht an Index 5; im Quelltext an Index 9 (+ srcStart 100).
        assertEquals(100 + 9, inline.sourceOffset(5))
        // gerendertes erstes 'f' (Index 0) -> Quelle Index 2 (hinter '**').
        assertEquals(100 + 2, inline.sourceOffset(0))
    }

    @Test fun plainBoldStillWorks() {
        val a = parseInline("a **b** c")
        assertEquals("a b c", a.text)
        assertTrue(a.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.item.fontStyle != FontStyle.Italic })
    }
}
