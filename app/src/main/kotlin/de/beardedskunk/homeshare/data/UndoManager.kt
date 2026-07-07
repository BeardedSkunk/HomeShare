package de.beardedskunk.homeshare.data

import de.beardedskunk.homeshare.core.NodeContent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Vom Repository implementierte Schreib-/Lese-Schnittstelle des Undo-Systems; in Tests gefaked.
 * Alle Aufrufe passieren auf Dispatchers.IO.
 */
interface UndoExecutor {
    /** versionId des Heads, wenn der Knoten GENAU einen hat (linear); sonst null. */
    fun soleHeadId(nodeId: String): String?

    /** Inhalt einer konkreten Version aus dem Op-Log (append-only -> sollte immer da sein). */
    fun versionContent(nodeId: String, versionId: String): NodeContent?

    /** Authort eine Restore-Op (restore-Marker bleibt erhalten, kein Gleichheits-Guard). */
    fun authorRestore(nodeId: String, content: NodeContent): String
}

/** ext-Key des restore-Markers. NICHT in [de.beardedskunk.homeshare.core.MetaKey.KNOWN] aufnehmen —
 *  sonst filtert `fromMeta` den Wert aus `ext`, ohne dass es ein typisiertes Feld gäbe. */
object UndoMeta { const val RESTORE = "restore" }

/**
 * Op-Log-basiertes Undo/Redo (siehe docs/undo_redo.md): pro Screen (Anker = angezeigter Knoten)
 * eine Kette der dort ausgelösten lokalen Ops. Undo/Redo = NEUE Op (git-revert-Stil, nie
 * destruktiv), die den Inhalt einer früheren Version restauriert und den restore-Marker trägt —
 * synct über die normale Head-Mechanik auf alle Geräte.
 *
 * Nur RAM (kein DB-Schema): Ketten überleben Navigation im Prozess, nicht den Prozess-Tod.
 * Fremde Ops (Sync-Ingest) kommen nie in eine Kette; sie invalidieren stattdessen alle Einträge
 * des betroffenen Knotens (Option a — fremde Änderungen zurückdrehen ist Sache des späteren
 * History-Browsers). Zustand ist über `synchronized` geschützt (IO- + Main-Thread-Zugriff).
 */
class UndoManager {

    lateinit var executor: UndoExecutor

    /** Einzelne Op eines Eintrags: Zustand vor (`beforeId`, null = createNode) und nach der Op. */
    private class Sub(val nodeId: String, val beforeId: String?, val afterId: String) {
        /** Head, der den „ausgeführt“-Zustand repräsentiert (wandert bei Redo mit). */
        var doneHead: String = afterId
        /** Head nach einem Undo — Validitätsanker für das Redo. */
        var undoneHead: String? = null
    }

    /** Ein Undo-Schritt; meist genau eine Sub, bei Gruppen (Anhang anlegen, Repeat-Spawn) mehrere. */
    private class Entry(val subs: MutableList<Sub>)

    /** cursor = Anzahl „ausgeführter“ Einträge; dahinter liegt der Redo-Schwanz. */
    private class Chain {
        val entries = ArrayDeque<Entry>()
        var cursor = 0
    }

    /** Max. Einträge pro Kette; ältere fallen vorne raus. */
    private val limit = 100

    private val chains = HashMap<String, Chain>()
    private val anchorStack = ArrayList<String>()
    private var grouping: MutableList<Sub>? = null
    private var suppress = false

    /** UI-Trigger: bumpt bei jeder Zustandsänderung (Button-Enable neu auswerten). */
    val revision = MutableStateFlow(0)

    private fun bump() = revision.value++

    // ----------------------------------------------------------- Anker (Screen-Stack)

    @Synchronized
    fun pushAnchor(id: String) {
        anchorStack.add(id)
        bump()
    }

    @Synchronized
    fun popAnchor(id: String) {
        val i = anchorStack.lastIndexOf(id)
        if (i >= 0) anchorStack.removeAt(i)
        bump()
    }

    // ----------------------------------------------------------- Aufzeichnung

    /** Vom Repository aus `author()` gerufen — für JEDE lokale Op. */
    @Synchronized
    fun onLocalOp(nodeId: String, parents: Set<String>, versionId: String) {
        if (suppress) return // Undo/Redo-eigene Op: Bookkeeping macht undo()/redo() selbst.
        val anchor = anchorStack.lastOrNull()
        if (anchor == null || parents.size > 1) {
            // Hintergrund-Schreiber (CalendarSync, WebServer, Fälligkeits-Sweep) bzw. Merge:
            // nicht aufzeichnen — betroffene Ketteneinträge wären sonst still stale.
            invalidateLocked(nodeId)
            return
        }
        val sub = Sub(nodeId, parents.singleOrNull(), versionId)
        val g = grouping
        if (g != null) {
            g.add(sub)
        } else {
            append(anchor, Entry(mutableListOf(sub)))
        }
        bump()
    }

    /** Fasst alle im Block ausgelösten Ops zu EINEM Undo-Schritt zusammen (keine Verschachtelung). */
    fun <T> group(block: () -> T): T {
        val opened = synchronized(this) {
            if (grouping == null) { grouping = mutableListOf(); true } else false
        }
        try {
            return block()
        } finally {
            if (opened) synchronized(this) {
                val subs = grouping.orEmpty()
                grouping = null
                // Anker kann während des Blocks theoretisch wechseln; zählt ist der aktuelle.
                val anchor = anchorStack.lastOrNull()
                if (subs.isNotEmpty() && anchor != null) append(anchor, Entry(subs.toMutableList()))
                bump()
            }
        }
    }

    private fun append(anchor: String, entry: Entry) {
        val chain = chains.getOrPut(anchor) { Chain() }
        // Neue Aktion kappt den Redo-Schwanz.
        while (chain.entries.size > chain.cursor) chain.entries.removeLast()
        chain.entries.addLast(entry)
        chain.cursor = chain.entries.size
        while (chain.entries.size > limit) {
            chain.entries.removeFirst()
            chain.cursor--
        }
    }

    /** Fremde Op für [nodeId] eingetroffen: alle betroffenen Einträge in ALLEN Ketten entfernen. */
    @Synchronized
    fun invalidate(nodeId: String) {
        invalidateLocked(nodeId)
        bump()
    }

    private fun invalidateLocked(nodeId: String) {
        for (chain in chains.values) {
            var i = 0
            while (i < chain.entries.size) {
                if (chain.entries[i].subs.any { it.nodeId == nodeId }) {
                    chain.entries.removeAt(i)
                    if (i < chain.cursor) chain.cursor--
                } else {
                    i++
                }
            }
        }
    }

    // ----------------------------------------------------------- Undo/Redo

    @Synchronized
    fun canUndo(anchor: String): Boolean = chains[anchor]?.let { it.cursor > 0 } == true

    @Synchronized
    fun canRedo(anchor: String): Boolean = chains[anchor]?.let { it.cursor < it.entries.size } == true

    /**
     * Macht den jüngsten gültigen Eintrag rückgängig. Stale Einträge (Head ist nicht mehr die
     * aufgezeichnete Version — z. B. nach Invalidierungs-Lücken) werden entsorgt und übersprungen.
     */
    @Synchronized
    fun undo(anchor: String) {
        val chain = chains[anchor] ?: return
        while (chain.cursor > 0) {
            val entry = chain.entries[chain.cursor - 1]
            if (!entry.subs.all { executor.soleHeadId(it.nodeId) == it.doneHead }) {
                chain.entries.removeAt(chain.cursor - 1)
                chain.cursor--
                continue
            }
            val contents = entry.subs.map { sub ->
                if (sub.beforeId == null) {
                    // Undo eines Anlegens = Löschen; ohne Marker (es wird nichts restauriert).
                    executor.versionContent(sub.nodeId, sub.afterId)
                        ?.let { it.copy(deleted = true, ext = it.ext - UndoMeta.RESTORE) }
                } else {
                    executor.versionContent(sub.nodeId, sub.beforeId)
                        ?.let { it.copy(ext = it.ext - UndoMeta.RESTORE + (UndoMeta.RESTORE to sub.beforeId)) }
                }
            }
            if (contents.any { it == null }) { // sollte nie passieren (Op-Log ist append-only)
                chain.entries.removeAt(chain.cursor - 1)
                chain.cursor--
                continue
            }
            suppress = true
            try {
                for (i in entry.subs.indices.reversed()) {
                    val sub = entry.subs[i]
                    val restored = executor.authorRestore(sub.nodeId, contents[i]!!)
                    // Die Restore-Op ist jetzt der Repräsentant des VOR-Zustands: Nachbar-Einträge
                    // desselben Knotens nachziehen, sonst wäre der nächstältere fälschlich stale.
                    (sub.undoneHead ?: sub.beforeId)?.let { retarget(sub.nodeId, it, restored) }
                    sub.undoneHead = restored
                }
            } finally {
                suppress = false
            }
            chain.cursor--
            break
        }
        bump()
    }

    /** Version [old] von Knoten [nodeId] wird im Log jetzt durch [new] repräsentiert (Head-Wechsel
     *  durch eine Restore-Op): alle Bookkeeping-Verweise in ALLEN Ketten nachziehen. */
    private fun retarget(nodeId: String, old: String, new: String) {
        for (chain in chains.values) {
            for (entry in chain.entries) {
                for (sub in entry.subs) {
                    if (sub.nodeId != nodeId) continue
                    if (sub.doneHead == old) sub.doneHead = new
                    if (sub.undoneHead == old) sub.undoneHead = new
                }
            }
        }
    }

    /** Symmetrisch zu [undo]: stellt den zuletzt rückgängig gemachten Eintrag wieder her. */
    @Synchronized
    fun redo(anchor: String) {
        val chain = chains[anchor] ?: return
        while (chain.cursor < chain.entries.size) {
            val entry = chain.entries[chain.cursor]
            if (!entry.subs.all { executor.soleHeadId(it.nodeId) == it.undoneHead }) {
                chain.entries.removeAt(chain.cursor)
                continue
            }
            val contents = entry.subs.map { sub ->
                executor.versionContent(sub.nodeId, sub.afterId)
                    ?.let { it.copy(ext = it.ext - UndoMeta.RESTORE + (UndoMeta.RESTORE to sub.afterId)) }
            }
            if (contents.any { it == null }) {
                chain.entries.removeAt(chain.cursor)
                continue
            }
            suppress = true
            try {
                for (i in entry.subs.indices) {
                    val sub = entry.subs[i]
                    val restored = executor.authorRestore(sub.nodeId, contents[i]!!)
                    // Symmetrisch zum Undo: der bisherige NACH-Zustands-Repräsentant wird ersetzt.
                    retarget(sub.nodeId, sub.doneHead, restored)
                    sub.doneHead = restored
                }
            } finally {
                suppress = false
            }
            chain.cursor++
            break
        }
        bump()
    }
}
