package de.beardedskunk.homeshare.data

import de.beardedskunk.homeshare.core.Hashing
import de.beardedskunk.homeshare.core.MetaKey
import de.beardedskunk.homeshare.core.NodeContent
import de.beardedskunk.homeshare.core.NodeKind
import de.beardedskunk.homeshare.core.NodeType
import de.beardedskunk.homeshare.core.OrderKeys
import de.beardedskunk.homeshare.core.RRule
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Wiederholende Aufgaben: pure Planung der **Kopie**, die beim Triggern des Repeaters entsteht
 * (Abhaken bzw. verstrichenes Due Date). Bewusst DB-frei (arbeitet auf [NodeState]-Listen wie
 * [childTaskCounts]) — die dünne Ausführungsschicht liegt in FeedRepository.
 *
 * Datenmodell (nur additive ext-Meta-Keys, kein Format-Bump):
 *  - Aufgabe: [KEY_RULE] = RRULE-String ([RRule]), [KEY_MODE] = done|due (Trigger-Anker).
 *  - Das **Due Date ist der erste CALENDAR-Kindknoten** der Aufgabe (normaler Datumsknoten).
 *  - Original nach dem Spawn: [KEY_SPAWNED] = nodeId der Kopie -> Sperre, max. EIN Nachfolger
 *    pro Aufgaben-Instanz; die Kette läuft über die Kopie weiter.
 *  - Kopie: [KEY_OF] = nodeId des Originals.
 *
 * **Deterministische IDs**: Kopie-Knoten-IDs werden aus (Quell-nodeId, occurrenceKey) gehasht.
 * Spawnen zwei Geräte offline dieselbe Wiederholung, erzeugen sie DENSELBEN Knoten mit gleichem
 * Inhalt -> nach dem Sync inhaltsgleiche Heads = kein Konflikt (Node.hasContentConflict), keine
 * Duplikate. Divergiert der Inhalt (Regel zwischendurch geändert), wird es ein normaler Konflikt
 * auf EINEM Knoten statt zweier Duplikat-Aufgaben.
 */
object TaskRepeat {
    const val KEY_RULE = "repeat"
    const val KEY_MODE = "repeatMode"
    const val KEY_SPAWNED = MetaKey.REPEAT_SPAWNED // Alias: Core kennt den Key für den LWW-Automerge
    const val KEY_OF = "repeatOf"
    const val MODE_DONE = "done"
    const val MODE_DUE = "due"

    /** Titel des automatisch erzeugten Due-Date-Knotens (Mode done ohne vorhandenes Datum). */
    const val DUE_TITLE = "Fällig"

    /** Enthaken nimmt die Kopie nur zurück, solange sie jünger ist als dieses Fenster. */
    const val UNSPAWN_WINDOW_MILLIS = 60 * 60 * 1000L

    fun rule(ext: Map<String, String>): RRule? = RRule.parse(ext[KEY_RULE])

    /** Trigger-Anker; Default done (funktioniert auch ohne Due Date). */
    fun mode(ext: Map<String, String>): String = if (ext[KEY_MODE] == MODE_DUE) MODE_DUE else MODE_DONE

    /** Erster CALENDAR-Kindknoten = Due Date der Aufgabe (weitere Datumsknoten sind Altbestand). */
    fun dueChild(children: List<NodeState>): NodeState? =
        children.firstOrNull { it.kind == NodeKind.CALENDAR && !it.deleted }

    /** Fälligkeits-TAG aus dem Datumsknoten (bei getimten Terminen der Kalendertag des Starts). */
    fun dueDate(due: NodeState): LocalDate? = EventCodec.parse(due.text)
        ?.let { runCatching { LocalDate.parse(it.start.trim().take(10)) }.getOrNull() }

    /** Überfällig = Fälligkeits-Tag ist vollständig verstrichen (Tages-Granularität). */
    fun isOverdue(dueDay: LocalDate, today: LocalDate): Boolean = dueDay.isBefore(today)

    /**
     * Deterministische Kopie-ID im UUID-Format aus Quell-ID + Vorkommens-Schlüssel.
     * Trigger due: altes Due-Datum (geräteübergreifend gleich -> Konvergenz); Trigger done:
     * Head-versionId VOR dem Abhaken (eindeutig pro Abhak-Zyklus; die Done-Op selbst kann ihre
     * eigene versionId nicht enthalten).
     */
    fun cloneId(sourceId: String, occurrenceKey: String): String {
        val hex = Hashing.sha256Hex("repeat|$sourceId|$occurrenceKey")
        return buildString(36) {
            append(hex, 0, 8); append('-'); append(hex, 8, 12); append('-'); append(hex, 12, 16)
            append('-'); append(hex, 16, 20); append('-'); append(hex, 20, 32)
        }
    }

    /**
     * Vorkommens-Schlüssel eines Spawns: bevorzugt das alte Due-Datum (geräteübergreifend gleich
     * -> beide Geräte erzeugen DIESELBE Kopie-Id, egal ob per Abhaken oder Fälligkeits-Sweep
     * getriggert), sonst — Aufgabe ohne Due Date, nur Mode done — die Head-versionId vor dem
     * Abhaken (eindeutig pro Abhak-Zyklus).
     */
    fun occurrenceKey(children: List<NodeState>, headVersionId: String): String =
        dueChild(children)?.let { dueDate(it) }?.toString() ?: headVersionId

    /**
     * Auto-Auflösung des Due-Date-Konflikts einer Repeater-Kopie: spawnen zwei Geräte dieselbe
     * Wiederholung (gleiche Kopie-Id), aber mit unterschiedlich berechnetem neuem Datum
     * (verschiedene Abhak-/Sweep-Tage), kollidieren zwei wurzellose Fassungen des Datumsknotens.
     * Sind beide bis auf Start/Ende identische Termine, gewinnt das FRÜHERE Datum (die nächste
     * tatsächlich anstehende Fälligkeit; die spätere ist nur ein weitergesprungener Catch-up).
     * null, wenn die Fassungen anders abweichen -> normaler manueller Konflikt.
     */
    fun mergeDueTexts(a: String, b: String): String? {
        val ea = EventCodec.parse(a) ?: return null
        val eb = EventCodec.parse(b) ?: return null
        if (ea.copy(start = "", end = "") != eb.copy(start = "", end = "")) return null
        val da = runCatching { LocalDate.parse(ea.start.trim().take(10)) }.getOrNull() ?: return null
        val db = runCatching { LocalDate.parse(eb.start.trim().take(10)) }.getOrNull() ?: return null
        return if (!db.isBefore(da)) a else b
    }

    /** Fertig geplante Kopie: (nodeId, Inhalt) in Erzeugungsreihenfolge (Eltern vor Kindern). */
    data class ClonePlan(
        val rootId: String,
        val nodes: List<Pair<String, NodeContent>>,
        val newDue: LocalDate,
    )

    /**
     * Plant die Kopie von [source] (Aufgabe mit Repeater). null wenn keine/erschöpfte Regel
     * (UNTIL überschritten, COUNT aufgebraucht) — dann endet die Kette, es entsteht KEINE Kopie.
     *
     * Regeln: Unterpunkte (TODO/NOTE/LIST) rekursiv und überall enthakt; Tags/Farbe bleiben;
     * Anhänge (IMAGE/FILE samt Beschreibung) und weitere Datumsknoten NICHT; das Due Date
     * (erster CALENDAR-Kindknoten) wird auf das nächste Vorkommen NACH [today] verschoben
     * (Catch-up: verpasste Perioden ergeben genau EINE Kopie). Fehlt ein Due Date (nur bei
     * Mode done), bekommt die Kopie ein neues als Ganztagstermin. Kinder erhalten explizite
     * orderKeys, damit die Kopien zweier Geräte content-identisch sind.
     */
    fun plan(
        source: NodeState,
        children: (String) -> List<NodeState>,
        occurrenceKey: String,
        today: LocalDate,
        cloneOrderKey: String,
    ): ClonePlan? {
        val rule = rule(source.ext) ?: return null
        val kids = children(source.nodeId)
        val due = dueChild(kids)
        val oldDue = due?.let { dueDate(it) }
        val anchor = if (mode(source.ext) == MODE_DUE && oldDue != null) oldDue else today
        var newDue = rule.nextAfter(anchor) ?: return null
        while (!newDue.isAfter(today)) newDue = rule.nextAfter(newDue) ?: return null

        val rootId = cloneId(source.nodeId, occurrenceKey)
        val nextRule = rule.decremented()
        val rootExt = buildMap {
            putAll(source.ext)
            remove(KEY_SPAWNED)
            if (nextRule != null) put(KEY_RULE, nextRule.format()) else { remove(KEY_RULE); remove(KEY_MODE) }
            put(KEY_OF, source.nodeId)
        }
        val nodes = ArrayList<Pair<String, NodeContent>>()
        nodes += rootId to NodeContent(
            parentId = source.parentId,
            type = source.type,
            orderKey = cloneOrderKey,
            text = source.text,
            childDefault = source.childDefault,
            color = source.color,
            tags = source.tags,
            done = false,
            ext = rootExt,
        )

        // Due Date der Kopie: vorhandenen Datumsknoten verschieben, sonst ganztägig neu.
        if (due != null) {
            nodes += cloneId(due.nodeId, occurrenceKey) to NodeContent(
                parentId = rootId,
                type = NodeType.CALENDAR,
                orderKey = OrderKeys.effective(due.orderKey, due.created),
                text = shiftEventText(due.text, newDue),
                color = due.color,
                tags = due.tags,
                ext = due.ext - KEY_SPAWNED - KEY_OF,
            )
        } else {
            nodes += cloneId(source.nodeId + "/due", occurrenceKey) to NodeContent(
                parentId = rootId,
                type = NodeType.CALENDAR,
                text = EventCodec.encode(
                    EventData(title = DUE_TITLE, start = newDue.toString(), end = newDue.toString(), allDay = true),
                ),
            )
        }

        // Unterpunkte rekursiv; Anhänge und (weitere) Datumsknoten bleiben beim Original.
        fun cloneKids(srcParentId: String, dstParentId: String) {
            for (child in children(srcParentId)) {
                if (child.deleted) continue
                if (child.kind == NodeKind.IMAGE || child.kind == NodeKind.FILE || child.kind == NodeKind.CALENDAR) continue
                val id = cloneId(child.nodeId, occurrenceKey)
                nodes += id to NodeContent(
                    parentId = dstParentId,
                    type = child.type,
                    orderKey = OrderKeys.effective(child.orderKey, child.created),
                    text = child.text,
                    childDefault = child.childDefault,
                    color = child.color,
                    tags = child.tags,
                    done = false,
                    ext = child.ext - KEY_SPAWNED - KEY_OF,
                )
                cloneKids(child.nodeId, id)
            }
        }
        cloneKids(source.nodeId, rootId)
        return ClonePlan(rootId, nodes, newDue)
    }

    /**
     * Verschiebt einen event-Block auf den Start-Tag [newDay]; Uhrzeit/Zone bleiben erhalten,
     * das Ende wandert um dieselbe Tages-Differenz mit (Tages-Granularität).
     */
    fun shiftEventText(text: String, newDay: LocalDate): String {
        val e = EventCodec.parse(text) ?: return text
        val startDay = runCatching { LocalDate.parse(e.start.trim().take(10)) }.getOrNull() ?: return text
        val endDay = runCatching { LocalDate.parse(e.end.trim().take(10)) }.getOrNull() ?: startDay
        val delta = ChronoUnit.DAYS.between(startDay, endDay).coerceAtLeast(0)
        fun move(s: String, day: LocalDate): String = day.toString() + s.trim().drop(10)
        return EventCodec.encode(e.copy(start = move(e.start, newDay), end = move(e.end, newDay.plusDays(delta))))
    }
}
