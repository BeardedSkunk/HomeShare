package de.beardedskunk.homeshare.data

import de.beardedskunk.homeshare.core.Hlc
import de.beardedskunk.homeshare.core.NodeContent
import de.beardedskunk.homeshare.core.NodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Klon-Planung wiederholender Aufgaben (pure, DB-frei):
 *  - Rekursion über Unterpunkte (inkl. Sub-Subtasks), überall done=false, Tags/Farbe bleiben,
 *  - Anhänge (IMAGE/FILE) und weitere Datumsknoten werden NICHT kopiert,
 *  - das Due Date (erster CALENDAR-Kindknoten) wandert aufs nächste Vorkommen, Uhrzeit bleibt,
 *  - COUNT zählt pro Kopie herunter, erschöpfte Regel beendet die Kette (kein Plan),
 *  - deterministische IDs: zweimal planen ergibt identische Knoten (Sync-Konvergenz).
 */
class TaskRepeatTest {

    private var seq = 0L

    private fun st(
        id: String,
        parent: String,
        type: NodeType = NodeType.TODO,
        text: String = id,
        done: Boolean = false,
        tags: List<String> = emptyList(),
        ext: Map<String, String> = emptyMap(),
    ) = NodeState(
        nodeId = id, parentId = parent, rootId = "feed", type = type, headVersionId = "h-$id",
        text = text, done = done, tags = tags, ext = ext,
        created = Hlc(++seq, 0), updated = Hlc(seq, 0),
    )

    private fun dueText(day: String, allDay: Boolean = true) = EventCodec.encode(
        EventData(title = "Fällig", start = day, end = day, allDay = allDay),
    )

    private fun childrenOf(tree: List<NodeState>): (String) -> List<NodeState> {
        val byParent = tree.groupBy { it.parentId }
        return { byParent[it].orEmpty() }
    }

    private fun contents(plan: TaskRepeat.ClonePlan): Map<String, NodeContent> = plan.nodes.toMap()

    // ---- Grundfall: Rekursion, Filter, ext-Verwaltung ----

    @Test
    fun plan_clonesSubtreeWithoutAttachments() {
        val task = st(
            "task", "list", text = "Wocheneinkauf", done = true, tags = listOf("haushalt"),
            ext = mapOf(
                TaskRepeat.KEY_RULE to "FREQ=WEEKLY;COUNT=3",
                TaskRepeat.KEY_MODE to TaskRepeat.MODE_DONE,
                TaskRepeat.KEY_SPAWNED to "alte-kopie",
            ),
        )
        val tree = listOf(
            task,
            st("due", "task", type = NodeType.CALENDAR, text = dueText("2026-07-06")),
            st("sub1", "task", done = true, tags = listOf("a")),
            st("sub2", "task", done = true, ext = mapOf(TaskRepeat.KEY_OF to "irgendwas")),
            st("subsub", "sub1", done = true),
            st("img", "task", type = NodeType.IMAGE),
            st("imgdesc", "img", type = NodeType.TEXT),
            st("cal2", "task", type = NodeType.CALENDAR, text = dueText("2026-09-01")), // Altbestand
        )
        val plan = TaskRepeat.plan(task, childrenOf(tree), "occ-1", LocalDate.parse("2026-07-07"), "8")!!
        val byId = contents(plan)

        // Wurzel: enthakt, Tags/Text bleiben, Regel dekrementiert, Marker getauscht.
        val root = byId.getValue(plan.rootId)
        assertFalse(root.done)
        assertEquals("Wocheneinkauf", root.text)
        assertEquals(listOf("haushalt"), root.tags)
        assertEquals("8", root.orderKey)
        assertEquals("list", root.parentId)
        assertEquals("FREQ=WEEKLY;COUNT=2", root.ext[TaskRepeat.KEY_RULE])
        assertEquals("task", root.ext[TaskRepeat.KEY_OF])
        assertNull(root.ext[TaskRepeat.KEY_SPAWNED])

        // Genau: Wurzel + Due + sub1 + sub2 + subsub; Anhang + zweiter Termin fehlen.
        assertEquals(5, plan.nodes.size)
        val texts = byId.values.map { it.text.lineSequence().first() }.toSet()
        assertTrue("sub1" in texts && "sub2" in texts && "subsub" in texts)
        assertTrue(byId.values.none { it.type == NodeType.IMAGE || it.type == NodeType.FILE })
        assertEquals(1, byId.values.count { it.type == NodeType.CALENDAR }) // nur das Due, nicht cal2
        assertTrue(byId.values.all { !it.done })
        // Sub-Subtask hängt am geklonten sub1, nicht am Original.
        val clonedSub1 = plan.nodes.first { it.second.text == "sub1" }
        val clonedSubSub = plan.nodes.first { it.second.text == "subsub" }
        assertEquals(clonedSub1.first, clonedSubSub.second.parentId)
        // Stale Repeat-Marker der Kinder reisen nicht mit.
        val clonedSub2 = plan.nodes.first { it.second.text == "sub2" }
        assertNull(clonedSub2.second.ext[TaskRepeat.KEY_OF])
        // Eltern stehen vor ihren Kindern (Erzeugungsreihenfolge).
        val pos = plan.nodes.withIndex().associate { (i, n) -> n.first to i }
        for ((i, n) in plan.nodes.withIndex()) {
            val p = pos[n.second.parentId] ?: continue // Wurzel hängt am Mutterknoten außerhalb
            assertTrue("Elternknoten muss vor dem Kind kommen", p < i)
        }
    }

    @Test
    fun plan_isDeterministic() {
        val task = st("task", "list", ext = mapOf(TaskRepeat.KEY_RULE to "FREQ=DAILY"))
        val tree = listOf(task, st("sub", "task", done = true))
        val a = TaskRepeat.plan(task, childrenOf(tree), "occ", LocalDate.parse("2026-07-07"), "8")!!
        val b = TaskRepeat.plan(task, childrenOf(tree), "occ", LocalDate.parse("2026-07-07"), "8")!!
        assertEquals(a.nodes.map { it.first }, b.nodes.map { it.first })
        assertEquals(a.nodes.map { it.second }, b.nodes.map { it.second })
        // anderes Vorkommen -> andere IDs (Enthaken + erneutes Abhaken kollidiert nicht)
        val c = TaskRepeat.plan(task, childrenOf(tree), "occ2", LocalDate.parse("2026-07-07"), "8")!!
        assertTrue(c.rootId != a.rootId)
    }

    // ---- Due-Date-Verschiebung ----

    @Test
    fun plan_shiftsDueByRule_modeDue() {
        val task = st(
            "task", "list",
            ext = mapOf(TaskRepeat.KEY_RULE to "FREQ=MONTHLY;BYMONTHDAY=6", TaskRepeat.KEY_MODE to TaskRepeat.MODE_DUE),
        )
        val tree = listOf(task, st("due", "task", type = NodeType.CALENDAR, text = dueText("2026-07-06")))
        val plan = TaskRepeat.plan(task, childrenOf(tree), "2026-07-06", LocalDate.parse("2026-07-07"), "8")!!
        assertEquals(LocalDate.parse("2026-08-06"), plan.newDue)
        val due = contents(plan).values.first { it.type == NodeType.CALENDAR }
        assertEquals("2026-08-06", EventCodec.parse(due.text)!!.start)
    }

    @Test
    fun plan_catchUp_skipsMissedPeriods() {
        // App war 3 Monate zu: genau EINE Kopie, Due erst wieder in der Zukunft.
        val task = st(
            "task", "list",
            ext = mapOf(TaskRepeat.KEY_RULE to "FREQ=MONTHLY;BYMONTHDAY=6", TaskRepeat.KEY_MODE to TaskRepeat.MODE_DUE),
        )
        val tree = listOf(task, st("due", "task", type = NodeType.CALENDAR, text = dueText("2026-07-06")))
        val plan = TaskRepeat.plan(task, childrenOf(tree), "2026-07-06", LocalDate.parse("2026-10-09"), "8")!!
        assertEquals(LocalDate.parse("2026-11-06"), plan.newDue)
    }

    @Test
    fun plan_timedDueKeepsTimeAndZone() {
        val text = EventCodec.encode(
            EventData(
                title = "Abgabe",
                start = "2026-07-06T14:00:00+02:00[Europe/Berlin]",
                end = "2026-07-06T15:30:00+02:00[Europe/Berlin]",
            ),
        )
        val shifted = EventCodec.parse(TaskRepeat.shiftEventText(text, LocalDate.parse("2026-08-06")))!!
        assertEquals("2026-08-06T14:00:00+02:00[Europe/Berlin]", shifted.start)
        assertEquals("2026-08-06T15:30:00+02:00[Europe/Berlin]", shifted.end)
    }

    @Test
    fun plan_withoutDue_createsAllDayDue_modeDone() {
        val task = st("task", "list", ext = mapOf(TaskRepeat.KEY_RULE to "FREQ=WEEKLY"))
        val plan = TaskRepeat.plan(task, childrenOf(listOf(task)), "occ", LocalDate.parse("2026-07-07"), "8")!!
        assertEquals(LocalDate.parse("2026-07-14"), plan.newDue)
        val due = EventCodec.parse(contents(plan).values.first { it.type == NodeType.CALENDAR }.text)!!
        assertEquals(TaskRepeat.DUE_TITLE, due.title)
        assertTrue(due.allDay)
        assertEquals("2026-07-14", due.start)
    }

    // ---- Ketten-Ende ----

    @Test
    fun plan_endsChain() {
        // COUNT=1: letzte Kopie entsteht noch, aber ohne Regel.
        val counted = st("task", "list", ext = mapOf(TaskRepeat.KEY_RULE to "FREQ=DAILY;COUNT=1"))
        val plan = TaskRepeat.plan(counted, childrenOf(listOf(counted)), "occ", LocalDate.parse("2026-07-07"), "8")!!
        val root = contents(plan).getValue(plan.rootId)
        assertNull(root.ext[TaskRepeat.KEY_RULE])
        assertNull(root.ext[TaskRepeat.KEY_MODE])
        // UNTIL verstrichen: gar keine Kopie mehr.
        val ended = st(
            "task2", "list",
            ext = mapOf(TaskRepeat.KEY_RULE to "FREQ=MONTHLY;BYMONTHDAY=6;UNTIL=20260801", TaskRepeat.KEY_MODE to TaskRepeat.MODE_DUE),
        )
        val tree = listOf(ended, st("due2", "task2", type = NodeType.CALENDAR, text = dueText("2026-08-06")))
        assertNull(TaskRepeat.plan(ended, childrenOf(tree), "occ", LocalDate.parse("2026-08-07"), "8"))
        // Ohne Regel: kein Plan.
        val plain = st("task3", "list")
        assertNull(TaskRepeat.plan(plain, childrenOf(listOf(plain)), "occ", LocalDate.parse("2026-07-07"), "8"))
    }

    // ---- Vorkommens-Schlüssel & Due-Date-Konflikt-Merge (Sync-Konvergenz) ----

    @Test
    fun occurrenceKey_prefersDueDate_fallsBackToHeadVersion() {
        val due = st("due", "task", type = NodeType.CALENDAR, text = dueText("2026-07-06"))
        // Mit Due Date: der Kalendertag — geräteübergreifend gleich, egal welcher Trigger.
        assertEquals("2026-07-06", TaskRepeat.occurrenceKey(listOf(due, st("sub", "task")), "head-1"))
        // Ohne (bzw. nur gelöschtes) Due Date: Head vor dem Abhaken.
        assertEquals("head-1", TaskRepeat.occurrenceKey(listOf(st("sub", "task")), "head-1"))
    }

    @Test
    fun mergeDueTexts_earlierDateWins_bothOrders() {
        val a = dueText("2026-07-10")
        val b = dueText("2026-07-14")
        assertEquals(a, TaskRepeat.mergeDueTexts(a, b))
        assertEquals(a, TaskRepeat.mergeDueTexts(b, a))
    }

    @Test
    fun mergeDueTexts_keepsTimeAndZoneOfWinner() {
        val a = EventCodec.encode(
            EventData(
                title = "Fällig", allDay = false,
                start = "2026-07-10T09:30+02:00[Europe/Berlin]",
                end = "2026-07-10T10:00+02:00[Europe/Berlin]",
            ),
        )
        val b = EventCodec.encode(
            EventData(
                title = "Fällig", allDay = false,
                start = "2026-07-17T09:30+02:00[Europe/Berlin]",
                end = "2026-07-17T10:00+02:00[Europe/Berlin]",
            ),
        )
        assertEquals(a, TaskRepeat.mergeDueTexts(b, a))
    }

    @Test
    fun mergeDueTexts_rejectsNonDateDifferences() {
        // Nur Start/Ende dürfen abweichen — sonst ist es KEIN reiner Doppel-Spawn-Konflikt.
        assertNull(TaskRepeat.mergeDueTexts(dueText("2026-07-10"), EventCodec.encode(
            EventData(title = "Anders", start = "2026-07-14", end = "2026-07-14", allDay = true),
        )))
        // Kein Termin-Text -> kein Auto-Merge.
        assertNull(TaskRepeat.mergeDueTexts("nur Text", dueText("2026-07-10")))
    }
}
