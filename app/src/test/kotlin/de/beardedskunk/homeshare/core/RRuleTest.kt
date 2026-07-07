package de.beardedskunk.homeshare.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Wiederholungsregeln für Aufgaben:
 *  - parse/format-Roundtrip (kanonische Reihenfolge, tolerante Eingabe),
 *  - nextAfter über alle Varianten inkl. Monats-Kanten (31. + Februar), Schaltjahr,
 *    Wochen-Raster mit INTERVAL, „letzter Tag/letzter Mittwoch",
 *  - Ende über UNTIL/COUNT und das COUNT-Herunterzählen pro Kopie,
 *  - Catch-up: verpasste Perioden werden übersprungen, ohne dass Kopien entstehen.
 */
class RRuleTest {

    private fun d(s: String) = LocalDate.parse(s)

    // ---- parse/format ----

    @Test
    fun roundtrip_allFields() {
        val rules = listOf(
            "FREQ=DAILY",
            "FREQ=DAILY;INTERVAL=3",
            "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,FR",
            "FREQ=MONTHLY;BYDAY=3MO",
            "FREQ=MONTHLY;BYDAY=-1WE",
            "FREQ=MONTHLY;BYMONTHDAY=1,15,-1",
            "FREQ=MONTHLY;BYMONTHDAY=6;UNTIL=20261231",
            "FREQ=YEARLY;INTERVAL=2;COUNT=5",
        )
        for (s in rules) {
            val r = RRule.parse(s)!!
            assertEquals(s, r.format())
            assertEquals(r, RRule.parse(r.format()))
        }
    }

    @Test
    fun parse_tolerant() {
        // ISO-UNTIL, Kleinschreibung, fremde Keys, Leerzeichen
        val r = RRule.parse("freq=weekly; byday=mo ,fr; until=2026-12-31; wkst=MO")!!
        assertEquals(RRule.Freq.WEEKLY, r.freq)
        assertEquals(listOf(RRule.ByDay(0, DayOfWeek.MONDAY), RRule.ByDay(0, DayOfWeek.FRIDAY)), r.byDay)
        assertEquals(d("2026-12-31"), r.until)
        // iCal-Basic-UNTIL mit Zeitanteil
        assertEquals(d("2026-08-06"), RRule.parse("FREQ=DAILY;UNTIL=20260806T000000Z")!!.until)
        assertNull(RRule.parse(null))
        assertNull(RRule.parse(""))
        assertNull(RRule.parse("BYDAY=MO"))
        assertNull(RRule.parse("FREQ=HOURLY;INTERVAL=17")) // Tages-Granularität: kein HOURLY
    }

    // ---- nextAfter: einfache Intervalle ----

    @Test
    fun daily_weekly_monthly_yearly_plain() {
        assertEquals(d("2026-07-10"), RRule.parse("FREQ=DAILY;INTERVAL=3")!!.nextAfter(d("2026-07-07")))
        assertEquals(d("2026-07-21"), RRule.parse("FREQ=WEEKLY;INTERVAL=2")!!.nextAfter(d("2026-07-07")))
        assertEquals(d("2026-10-07"), RRule.parse("FREQ=MONTHLY;INTERVAL=3")!!.nextAfter(d("2026-07-07")))
        assertEquals(d("2028-07-07"), RRule.parse("FREQ=YEARLY;INTERVAL=2")!!.nextAfter(d("2026-07-07")))
        // Monats-/Schaltjahr-Klemmen
        assertEquals(d("2026-02-28"), RRule.parse("FREQ=MONTHLY")!!.nextAfter(d("2026-01-31")))
        assertEquals(d("2025-02-28"), RRule.parse("FREQ=YEARLY")!!.nextAfter(d("2024-02-29")))
    }

    // ---- nextAfter: Wochentage ----

    @Test
    fun weekly_byDay() {
        val r = RRule.parse("FREQ=WEEKLY;BYDAY=MO,FR")!!
        assertEquals(d("2026-07-10"), r.nextAfter(d("2026-07-07"))) // Di -> Fr derselben Woche
        assertEquals(d("2026-07-13"), r.nextAfter(d("2026-07-10"))) // Fr -> Mo nächste Woche
    }

    @Test
    fun weekly_byDay_interval() {
        // Jede 2. Woche Mo+Fr; Raster hängt an der Anker-Woche.
        val r = RRule.parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,FR")!!
        assertEquals(d("2026-07-10"), r.nextAfter(d("2026-07-06"))) // Mo -> Fr derselben Woche
        assertEquals(d("2026-07-20"), r.nextAfter(d("2026-07-10"))) // Fr -> Mo in Woche +2
    }

    // ---- nextAfter: Monatstage ----

    @Test
    fun monthly_byMonthDay() {
        val r = RRule.parse("FREQ=MONTHLY;BYMONTHDAY=1,15,-1")!!
        assertEquals(d("2026-07-15"), r.nextAfter(d("2026-07-07")))
        assertEquals(d("2026-07-31"), r.nextAfter(d("2026-07-15"))) // -1 = letzter Tag
        assertEquals(d("2026-08-01"), r.nextAfter(d("2026-07-31")))
    }

    @Test
    fun monthly_byMonthDay_skipsShortMonths() {
        // Der 31. existiert im Februar/April nicht -> Vorkommen fällt aus, kein Klemmen.
        val r = RRule.parse("FREQ=MONTHLY;BYMONTHDAY=31")!!
        assertEquals(d("2026-03-31"), r.nextAfter(d("2026-01-31")))
    }

    // ---- nextAfter: Position im Monat ----

    @Test
    fun monthly_byWeekdayPosition() {
        val third = RRule.parse("FREQ=MONTHLY;BYDAY=3MO")!!
        assertEquals(d("2026-07-20"), third.nextAfter(d("2026-07-07")))
        assertEquals(d("2026-08-17"), third.nextAfter(d("2026-07-20")))
        val last = RRule.parse("FREQ=MONTHLY;BYDAY=-1WE")!!
        assertEquals(d("2026-07-29"), last.nextAfter(d("2026-07-07")))
        assertEquals(d("2026-08-26"), last.nextAfter(d("2026-07-29")))
    }

    // ---- Ende: UNTIL/COUNT ----

    @Test
    fun until_endsChain() {
        val r = RRule.parse("FREQ=DAILY;UNTIL=20260708")!!
        assertEquals(d("2026-07-08"), r.nextAfter(d("2026-07-07")))
        assertNull(r.nextAfter(d("2026-07-08")))
    }

    @Test
    fun count_decrementsPerCopy() {
        val r = RRule.parse("FREQ=DAILY;COUNT=2")!!
        assertEquals(RRule.parse("FREQ=DAILY;COUNT=1"), r.decremented())
        assertNull(r.decremented()!!.decremented()) // letzte Kopie -> Kette endet
        assertNull(RRule.parse("FREQ=DAILY;COUNT=0")!!.nextAfter(d("2026-07-07")))
        assertEquals(RRule.parse("FREQ=DAILY"), RRule.parse("FREQ=DAILY")!!.decremented()) // ohne COUNT unverändert
    }

    // ---- Catch-up ----

    @Test
    fun catchUp_iteratesToFuture() {
        // App war lange zu: Aufrufer iteriert bis hinter „heute" — genau eine Kopie.
        val r = RRule.parse("FREQ=WEEKLY")!!
        val today = d("2026-07-07")
        var due = d("2026-05-01")
        while (due <= today) due = r.nextAfter(due)!!
        assertEquals(d("2026-07-10"), due) // 01.05. + n*7 Tage, erstes Datum nach dem 07.07.
    }

    // ---- summary ----

    @Test
    fun summary_german() {
        assertEquals("täglich", RRule.parse("FREQ=DAILY")!!.summary())
        assertEquals("alle 2 Wochen am Montag und Freitag", RRule.parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,FR")!!.summary())
        assertEquals("monatlich am 1., 15. und letzten Tag", RRule.parse("FREQ=MONTHLY;BYMONTHDAY=1,15,-1")!!.summary())
        assertEquals("monatlich am 3. Montag, noch 5-mal", RRule.parse("FREQ=MONTHLY;BYDAY=3MO;COUNT=5")!!.summary())
        assertEquals("monatlich am letzten Mittwoch", RRule.parse("FREQ=MONTHLY;BYDAY=-1WE")!!.summary())
        assertEquals("jährlich, bis 31.12.2026", RRule.parse("FREQ=YEARLY;UNTIL=20261231")!!.summary())
    }
}
