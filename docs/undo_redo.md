# Undo/Redo-System auf Basis des Op-Logs

## Kontext / Ziel der Änderung

Die App versioniert bereits jeden Knoten git-artig (append-only Op-Log, DAG pro Knoten), aber die UI bietet kein Undo/Redo. Gleichzeitig gibt es viele Sofort-Mutationen ohne Bestätigung (Swipe-Löschen fragt bewusst nicht nach, Drag-Umsortieren, Tags, done-Toggle, Bild-Bearbeitung), und das bisherige „Back im Edit-Modus verwirft Änderungen"-Modell ist inkonsistent umgesetzt (teils Datenverlust). Ziel:

1. **Undo/Redo-Buttons** unten links auf allen Screens (gleiche Bauart wie der Plus-Button unten rechts), pro Screen eine eigene Undo/Redo-Kette über alle dort ausgelösten Knoten-Änderungen.
2. **Undo = neue Op** (git-revert-Stil, nie destruktiv) → synct automatisch über die bestehende Head-Mechanik auf alle Geräte.
3. **Auto-Save statt Verwerfen:** Text-Edits werden bei ~3 s Tipp-Pause, beim ✓-Toggle, bei Back und bei App-in-Hintergrund committed. Es gibt kein „Back ohne Speichern" mehr; der bestehende Datenverlust-Bug (PostDetailEditor verliert bei Back im Quelltext-Modus den Text) wird dadurch mitbehoben.
4. **Kein Log-Müll:** `editNode` schreibt heute auch bei unverändertem Inhalt eine Op (neue HLC ⇒ neue versionId). Ein zentraler Gleichheits-Guard verhindert das ab jetzt (bestehender Fehler, wird mitbehoben).
5. **restore-Marker** (`ext["restore"] = versionId`) auf jeder Undo/Redo-Op — wird v1 nicht ausgelesen, hält aber den Weg zu History-Browser und geräteübergreifendem Redo offen (siehe Memory „history-browser-cursor-idea").

### Vom Nutzer fixierte Entscheidungen (nicht neu diskutieren)

- Undo-Kette **pro Screen** (Anker = angezeigter Knoten), sammelt Änderungen an beliebigen Knoten dieses Screens (Kinder, Unterpunkte, Anhänge).
- **Feingranular:** 1 Op = 1 Undo-Schritt (kein Koaleszieren von Tipp-Sessions). Kalender: jede Feldänderung = eigener Undo-Punkt.
- **Ausnahme Gruppierung:** Anhang anlegen (IMAGE/FILE-Knoten + Beschreibungs-TEXT-Kind = 2 Ops) ist **ein** Undo-Schritt.
- **Nur RAM**, keine lokale Persistenz-Tabelle. Limit **100 Einträge pro Kette** (älteste fallen raus). Kette überlebt Navigation innerhalb des Prozesses, nicht den Prozess-Tod.
- **Stale-Szenario Option (a):** Trifft per Sync eine fremde Op für Knoten X ein, werden alle Ketten-Einträge, die X betreffen, entfernt (Undo- wie Redo-Richtung). Fremde Ops kommen **nie** in die Kette (fremde Änderungen zurückdrehen = Job des späteren History-Browsers).
- Buttons: **immer sichtbar**, unten links schwebend, 56 dp, gleiche Optik wie `fab:add`, als Paar eng beieinander (deutlich mehr Abstand zum Plus-Button rechts), einzeln **ausgegraut** wenn Richtung leer.
- Fremdwurzel-Reorder (erzeugt keine Op, nur lokaler Pin) bekommt keinen Undo-Eintrag — akzeptiert.

## Relevante Erkenntnisse aus der Analyse (Code-Stand feature/list-types, b4a6af0)

Alle Pfade relativ zu `app/src/main/kotlin/de/beardedskunk/homeshare/`. Zeilennummern = Stand der Analyse, können leicht verschoben sein — Serena-Symbolsuche nutzen.

### Datenmodell / Repository
- **`core/Model.kt`**: `NodeContent` (Z. 136, data class ⇒ `==` vergleicht vollständig inkl. `ext`) mit `metaMap()`/`fromMeta()`. `MetaKey` (Z. 36) mit `KNOWN = setOf(childDefault, color, tags, done, blob, file, mime)`. **Wichtig:** `ext` = alle Meta-Keys, die NICHT in `KNOWN` sind; `fromMeta` filtert `KNOWN`-Keys aus `ext` heraus. Der restore-Marker darf deshalb **nicht** in `KNOWN` aufgenommen werden, sonst geht er beim Dekodieren verloren (kein typisiertes Feld). `NodeVersion` (Z. 200): `versionId = SHA-256(canonical())` über fmt, nodeId, sortierte parents, deviceId, HLC und alle Content-Felder inkl. Meta-Map — HLC fließt ein ⇒ inhaltsgleiche Saves erzeugen trotzdem neue versionIds (= der heutige Log-Müll).
- **`core/Node.kt`**: `Node.ingest()` (Z. 18), `heads()` (Z. 30, sortiert nach `headOrder` = HLC, deviceId, versionId — deterministisch auf allen Geräten), `shownHead()` (Z. 44) = `heads().lastOrNull()`, `allVersions()` (Z. 25), `autoMergeContent()` (Z. 75, nur bei genau 2 Heads), `lowestCommonAncestor` (Z. 136).
- **`data/FeedRepository.kt`**:
  - `author(nodeId, parents, content)` (Z. 65–81): DER zentrale Schreibpfad. Erzeugt `NodeVersion` mit `identity.nextHlc()`/`nextSeq()`, persistiert, `rebuildNodeState`, `bumpRevision()`, ruft `onLocalChange`/`onAnyChange`. **Jede** lokale Mutation läuft hier durch.
  - `currentHeads(nodeId)` (Z. 83, private), `headContent(nodeId)` (Z. 86).
  - `createNode` (Z. 90, parents = leer), `editNode` (Z. 104, parents = aktuelle Heads, **kein** Gleichheits-Guard), `deleteNode` (Z. 107, setzt `deleted=true` direkt via `author`), `moveNode` (Z. 112), `reorderNode` (Z. 123; Fremdwurzeln Z. 128–133: nur `foreign_refs`-Update, **keine Op**), `resolveConflict` (Z. 138, Merge über alle Heads).
  - `history(nodeId): Node` (Z. ~173) = `loadNode(nodeId)` — liefert den vollen DAG; darüber kommt man an den Content jeder versionId.
  - `maybeAutoResolve()` (Z. ~299–306): läuft nur beim `ingest` fremder Ops; schreibt deterministische Merge-Version (mehrere Parents).
  - `ingestOp(...)`: Einspielpfad des Syncs (wird von `SyncReconciler.reconcile` in `sync/Sync.kt` Z. 242–254 aufgerufen). Hier kommen fremde Ops an ⇒ Invalidierungs-Hook.
  - `revision: StateFlow<Int>` (Z. 47): reaktiver Trigger, alle Screens laden bei Änderung neu.
- **`data/Db.kt`**: Tabelle `ops` (version_id PK, node_id, parents, device_id, seq, hlc_*, text, meta …), `node_current` = rebuildbarer Cache. Kein Schema-Änderungsbedarf für dieses Feature (Marker lebt in `meta`/`ext`).
- **`App.kt`**: `AppGraph` (Z. 22–34) = schlanker Service-Locator; `repo: FeedRepository by lazy { FeedRepository(db, identity) }`. Hier wird der `UndoManager` eingehängt.
- **Hintergrund-Schreiber ohne UI:** `calendar/CalendarSync` und `web/WebServer` authoren ebenfalls über das Repo — sie haben keinen Screen-Anker und dürfen keine Undo-Einträge erzeugen (siehe Design unten).

### UI (alle unter `ui/`)
- **Navigation**: zustandsbasiert-modal, kein Nav-Framework. `MainActivity.kt` → `AppRoot` (Z. 100–195) hält `navIds` (Listen-Stack); tiefere Editoren werden **innerhalb** der Screens als lokaler State geöffnet und per `if (…) { Editor(...); return }` gerendert (z. B. `ListScreen.kt` Z. 400–440). Kein ViewModel; Screens greifen direkt auf `repo` zu, Aufrufe als `scope.launch { withContext(Dispatchers.IO) { repo.… } }`.
- **Save-Modell heute**:
  - `PostDetailEditor.kt` `save()` (Z. 227–244): `createNode` beim ersten Save (currentNodeId==null), sonst `editNode(entryId, hc.copy(text = text))` — Typ bewusst NICHT anfassen (Kommentar Z. 236). Save nur beim ✓-Toggle (Z. 338–340). **Bug:** `onBack = onClose` (Z. 300) — Back im Quelltext-Modus verliert den Text.
  - `TodoDetailScreen.kt` `saveBody()` (Z. 130–137), Toggle Z. 274–276.
  - `AttachmentDetailScreen.kt` `save()` (Z. 110–122); `BackHandler { if (sourceMode) { save(); sourceMode = false } else onClose() }` (Z. 216) — **das Vorbild** für die anderen Editoren.
  - `ListScreen.kt` `saveHeader()` (Z. 373–379) für die Listen-Beschreibung, Toggle Z. 527.
  - `CalendarEntryEditor.kt`: speichert eifrig pro Feldänderung via `persist()`/`ensureNode()` (Z. 234–258); Titel/Body beim Edit-Toggle (Z. 347–349); Ort bei Fokusverlust (Z. 432).
- **Sofort-Mutationen** (alle direkt persistiert, jeweils 1 Op): Drag-Reorder (`ListScreen.kt` Z. 285–293; `TodoDetailScreen.kt` Z. 413–418; Anhänge Z. 291–293 PostDetail / 455–457 Todo / 467 Calendar), Tags (`addTag`/`removeTag` je Screen, z. B. `ListScreen.kt` Z. 251–257), Swipe-Löschen (`ListScreen.kt` Z. 636–641, `TodoDetailScreen.kt` Z. 228–232), done-Toggle (`ListScreen.kt` Z. 623, `TodoDetailScreen.kt` Z. 118–123, Markdown-Task-Flip), Bild-Bearbeitung (`AttachmentDetailScreen.kt` Z. 133–189, `editNode(copy(blobHash = newSha))`).
- **Anhang anlegen** = `AttachmentPicker.addImage/addFile` (in `ui/AttachmentPicker.kt`): legt Anhang-Knoten + Beschreibungs-TEXT-Kind an (2 `createNode`-Ops) → Gruppierungs-Kandidat.
- **FAB/Buttons**: Plus-FAB unten rechts: `ListScreen.kt` Z. 537–566 (eigene 56 dp `Surface` mit `combinedClickable`, testTag `fab:add`) bzw. `AttachmentAddFab` in `Attachments.kt` Z. 58–69; Freiraum-Konstante `ATTACHMENT_FAB_CLEARANCE = 88.dp` (Z. 56). **Unten links ist frei** (kein BottomBar).
- **TopBars**: gemeinsame `DetailTopBar` in `UiComponents.kt` Z. 100–147 (+ `EditToggleButton` Z. 84–93); ListScreen baut seine eigene (Z. 445–535). Werden nicht angefasst (Buttons kommen unten links, nicht in die TopBar).
- **`ui/TestTags.kt`**: dokumentiert die testTag-Konventionen (`fab:add`, `topbar:*`, …) — neue Tags dort nachtragen.
- Blobs sind content-adressiert und bleiben im Store ⇒ Undo einer Bild-Bearbeitung (alter `blobHash`) funktioniert ohne Zusatzarbeit.

### Warum Undo als neue Op trivially synct
Eine Undo-Op ist eine normale Op: `author()` hängt sie mit den aktuellen Heads als Parents an, sie bekommt die höchste HLC und wird per `headOrder` auf **allen** Geräten der angezeigte Head, sobald sie gesynct ist. Kein neues Wire-Format, kein Format-Bump, keine Sync-Änderung.

### Tests
Reine JVM-Unit-Tests unter `app/src/test/kotlin/de/beardedskunk/homeshare/` (~140, kein Robolectric). Der `UndoManager` muss deshalb als reine Logik mit injizierbarem Executor gebaut werden. Es existieren bereits `data/`-Tests (z. B. `FeedShareTest.kt`, `ChildTaskCountsTest.kt`) — **vor dem Schreiben des editNode-Guard-Tests dort nachsehen, wie FeedRepository in JVM-Tests instanziiert wird**, und denselben Stil verwenden; falls das ohne echte SQLite-DB nicht geht, den Guard indirekt über die UndoManager-Executor-Abstraktion testen.

## Betroffene Dateien

| Datei | Änderung |
|---|---|
| `data/UndoManager.kt` | **NEU** — Ketten, Cursor, Gruppierung, Invalidierung, Anker-Stack, Executor-Interface |
| `data/FeedRepository.kt` | editNode-Guard + Marker-Strip, Record-/Invalidate-Hooks, `versionContent()`, `soleHeadId()`, Executor-Implementierung, UndoManager-Konstruktorparameter |
| `App.kt` | UndoManager im AppGraph anlegen, an FeedRepository geben |
| `ui/UndoUi.kt` | **NEU** — `UndoRedoButtons`-Composable + `RegisterUndoAnchor` |
| `ui/ListScreen.kt` | Anker registrieren, Buttons einblenden, saveHeader-Debounce + Back-Save |
| `ui/PostDetailEditor.kt` | Anker, Buttons, 3-s-Debounce, Back-Save (Bugfix), ON_PAUSE-Save |
| `ui/TodoDetailScreen.kt` | Anker, Buttons, saveBody-Debounce + Back-Save |
| `ui/AttachmentDetailScreen.kt` | Anker, Buttons, Debounce (Back-Save existiert schon) |
| `ui/CalendarEntryEditor.kt` | Anker, Buttons, Debounce für Titel/Body-Edit (persist-Mechanik sonst unverändert) |
| `ui/AttachmentPicker.kt` | `addImage`/`addFile` in `undo.group { }` wickeln |
| `ui/TestTags.kt` | `fab:undo`, `fab:redo` dokumentieren |
| `app/src/test/kotlin/.../data/UndoManagerTest.kt` | **NEU** — Kernlogik-Tests |
| `docs/undo-redo-plan.md` | **NEU** — Kopie dieses Plans (Schritt 0) |

## Konkrete Umsetzungsschritte

### Schritt 0: Plan committen und pushen (ausdrücklicher Auftrag)
Diesen Plan nach `docs/undo-redo-plan.md` kopieren, committen (`[docs] Undo/Redo-Plan`) und **pushen** (der Push ist hier ausdrücklich beauftragt; weitere Pushes nur auf Auftrag).

### Schritt 1: `data/UndoManager.kt` (neu)

Reine Logik-Klasse, DB-frei, damit JVM-testbar. Kern-API:

```kotlin
/** Vom Repo implementiert; in Tests gefaked. Alle Aufrufe auf Dispatchers.IO. */
interface UndoExecutor {
    fun soleHeadId(nodeId: String): String?                       // versionId, wenn GENAU 1 Head, sonst null
    fun versionContent(nodeId: String, versionId: String): NodeContent?
    /** Authort eine Restore-Op (Marker bleibt erhalten, kein Strip); liefert neue versionId. */
    fun authorRestore(nodeId: String, content: NodeContent): String
}

object UndoMeta { const val RESTORE = "restore" }   // ext-Key; NICHT in MetaKey.KNOWN aufnehmen!

class UndoManager {
    lateinit var executor: UndoExecutor               // FeedRepository setzt sich in init { } selbst ein

    private class Sub(val nodeId: String, val beforeId: String?, val afterId: String) {
        var doneHead: String = afterId                // Head, der den „ausgeführt“-Zustand repräsentiert
        var undoneHead: String? = null                // Head nach Undo (für Redo-Validität)
    }
    private class Entry(val subs: MutableList<Sub>)   // Gruppen-Eintrag; meist 1 Sub
    private class Chain { val entries = ArrayDeque<Entry>(); var cursor = 0 }  // cursor = Anzahl „done“-Einträge

    private val chains = HashMap<String, Chain>()     // Key = Anker-nodeId (ROOT-Sentinel für Feeds-Screen)
    private val anchorStack = ArrayList<String>()
    private var grouping: MutableList<Sub>? = null
    private var suppress = false                      // true, während Undo/Redo selbst authort
    val revision = MutableStateFlow(0)                // UI-Trigger für Button-Zustände
    // Alle öffentlichen Methoden mit synchronized(this) schützen (IO- + Main-Thread-Zugriff),
    // revision-Bump am Ende jeder Mutation.
}
```

Verhalten (jede Regel ist eine bewusste Entscheidung, nicht weglassen):

- **`pushAnchor(id)` / `popAnchor(id)`**: append bzw. letztes Vorkommen entfernen. Aktueller Anker = `anchorStack.lastOrNull()`.
- **`onLocalOp(nodeId, parents: Set<String>, versionId: String)`** — vom Repo aus `author()` gerufen:
  - `suppress == true` → Undo/Redo-eigene Op → nichts tun (Bookkeeping macht `undo()`/`redo()` selbst).
  - Kein Anker aktiv (Hintergrund-Schreiber wie CalendarSync/WebServer) **oder** `parents.size > 1` (Merge/Konfliktauflösung) → `invalidate(nodeId)` statt aufzeichnen (die Kette wäre sonst still stale).
  - Sonst: `Sub(nodeId, beforeId = parents.singleOrNull(), afterId = versionId)`; `beforeId == null` ⇔ createNode. Läuft eine Gruppierung → an `grouping` anhängen; sonst neuen Entry in die Anker-Kette: erst Redo-Schwanz kappen (`while (entries.size > cursor) entries.removeLast()`), dann anhängen, `cursor = entries.size`, bei > 100 vorne kürzen (`cursor--` mitführen).
- **`group(block: () -> Unit)`**: `grouping`-Liste öffnen, `block` ausführen, danach die gesammelten Subs als EINEN Entry anhängen (gleiche Kapp-/Limit-Logik). Verschachtelung nicht nötig — assert/ignore.
- **`invalidate(nodeId)`**: in ALLEN Ketten jeden Entry entfernen, der einen Sub mit dieser nodeId enthält; für entfernte Entries mit Index < cursor den cursor dekrementieren. (Hook: fremde Ops aus dem Sync, s. Schritt 2.)
- **`canUndo(anchor)` / `canRedo(anchor)`**: `cursor > 0` bzw. `cursor < entries.size`.
- **`undo(anchor)`**: Schleife: solange `cursor > 0`: Entry = `entries[cursor-1]`. **Validitätscheck** (Option a, lazy): für JEDEN Sub muss `executor.soleHeadId(sub.nodeId) == sub.doneHead` gelten. Falls nein → Entry entfernen, `cursor--`, mit dem nächstälteren weitermachen (stale Einträge werden übersprungen/entsorgt). Falls ja → ausführen und Schleife beenden:
  - `suppress = true; try { … } finally { suppress = false }`
  - Subs in **umgekehrter** Reihenfolge: `beforeId == null` → Content von `afterId` holen und `copy(deleted = true, ext = ext - RESTORE)` authoren (Undo eines Anlegens = Löschen, ohne Marker — es gibt nichts zu restaurieren). Sonst → `versionContent(nodeId, beforeId)` holen, `copy(ext = ext - RESTORE + (RESTORE to beforeId))` authoren. Rückgabe-versionId in `sub.undoneHead` merken.
  - `cursor--`.
- **`redo(anchor)`**: symmetrisch: Entry = `entries[cursor]`; Validität: `soleHeadId(sub.nodeId) == sub.undoneHead`; stale → Entry entfernen (cursor bleibt), weiter mit dem nächsten. Ausführung in **Original**-Reihenfolge: Content von `afterId` mit Marker `RESTORE to afterId` authoren (auch für create-Subs — das restauriert den angelegten Inhalt mit `deleted=false`); neue versionId in `sub.doneHead`; `cursor++`.
- `versionContent(...) == null` (sollte nie passieren, Op-Log ist append-only) → Entry verwerfen, weiter.

### Schritt 2: `data/FeedRepository.kt`

1. **Konstruktor**: `class FeedRepository(db, identity, val undo: UndoManager = UndoManager())`; in `init { undo.executor = <Implementierung> }` (oder Repo implementiert `UndoExecutor` direkt und setzt `undo.executor = this`).
2. **Executor-Implementierung**:
   - `soleHeadId(nodeId)` = `loadNode(nodeId).heads().singleOrNull()?.versionId`.
   - `versionContent(nodeId, versionId)` = `loadNode(nodeId).allVersions().firstOrNull { it.versionId == versionId }?.content` (falls `allVersions()` kein Lookup bietet, kleine Hilfsfunktion in `core/Node.kt` ergänzen — `Node` hält die Versionen bereits in einer Map).
   - `authorRestore(nodeId, content)` = `author(nodeId, currentHeads(nodeId), content).versionId` — bewusst OHNE Marker-Strip und OHNE Gleichheits-Guard (der Undo-Pfad liefert nie No-Ops, und der Marker muss überleben).
3. **Guard + Marker-Strip in `editNode`** (behebt den Log-Müll-Fehler):
   ```kotlin
   fun editNode(nodeId: String, content: NodeContent): NodeVersion {
       val stripped = if (UndoMeta.RESTORE in content.ext)
           content.copy(ext = content.ext - UndoMeta.RESTORE) else content
       val node = loadNode(nodeId)
       val heads = node.heads()
       // Kein-Op-Guard: identischer Inhalt bei linearem Zustand -> nichts schreiben.
       heads.singleOrNull()?.let { if (it.content == stripped) return it }
       return author(nodeId, heads.map { it.versionId }.toSet(), stripped)
   }
   ```
   Der Strip ist wichtig: normale Edits basieren auf `headContent(...).copy(...)` — ohne Strip würde der restore-Marker eines Undo-Heads in alle Folge-Edits durchsickern und die Redo-Kette wäre nicht mehr als „gebrochen" erkennbar. `resolveConflict` bleibt unangetastet (muss auch bei inhaltsgleichem Ergebnis eine Merge-Op schreiben). `deleteNode` auf `editNode(nodeId, hc.copy(deleted = true))` umstellen, damit Guard + Hooks einheitlich greifen.
4. **Record-Hook in `author()`** (ans Ende, nach `bumpRevision()`): `undo.onLocalOp(nodeId, parents, version.versionId)`.
5. **Invalidate-Hook in `ingestOp`**: für jede tatsächlich NEU eingefügte fremde Op (nicht bei Dedup via PK) → `undo.invalidate(nodeId)`. Der von `maybeAutoResolve` geschriebene Merge läuft durch `author()` und wird dort via `parents.size > 1` ohnehin zu `invalidate`.

### Schritt 3: `ui/UndoUi.kt` (neu)

```kotlin
@Composable
fun RegisterUndoAnchor(undo: UndoManager, anchorId: String) {
    DisposableEffect(anchorId) {
        undo.pushAnchor(anchorId)
        onDispose { undo.popAnchor(anchorId) }
    }
}

@Composable
fun UndoRedoButtons(undo: UndoManager, anchorId: String, modifier: Modifier = Modifier) { … }
```

- Optik: zwei 56-dp-Buttons in exakt der Bauart des Listen-FABs (`ListScreen.kt` Z. 537–566: `Surface` mit shape/elevation/Farben — dort abschauen und ggf. die Machart in eine gemeinsame private Composable ziehen), Icons `Icons.AutoMirrored.Filled.Undo`/`.Redo` (falls die im eingebundenen material-icons-Artefakt fehlen: Verfügbarkeit prüfen, notfalls `material-icons-extended` ergänzen oder wie sonst im Projekt Icons gelöst sind).
- Anordnung: `Row` unten links (`Alignment.BottomStart`), 8 dp Abstand **zwischen** den beiden, 16 dp Außenabstand (spiegelbildlich zum FAB), `imePadding()` damit sie bei offener Tastatur über dem Eingabebereich schweben (vgl. `ATTACHMENT_FAB_CLEARANCE`).
- Zustand: `undo.revision.collectAsState()` beobachten; `canUndo`/`canRedo` pro Button; deaktiviert = ausgegraut (Alpha ~0.35, Klick wirkungslos), **immer sichtbar**.
- Klick: `scope.launch { withContext(Dispatchers.IO) { undo.undo(anchorId) } }` (analog redo) — die Repo-`revision` triggert das Neuladen der Screens automatisch.
- testTags `fab:undo` / `fab:redo`; in `ui/TestTags.kt` dokumentieren (Konvention der letzten Commits, vgl. b4a6af0).

### Schritt 4: Screens verdrahten

Für jeden Screen: (1) `RegisterUndoAnchor` **ganz oben** im Composable platzieren — VOR den modalen `if (…) { Editor(...); return }`-Zweigen, damit der Anker des Listen-Screens aktiv bleibt und der Editor seinen eigenen oben drauflegt (Stack: [Liste, Editor]; beim Schließen disposed der Editor-Effekt und poppt sich selbst); (2) `UndoRedoButtons` in die Wurzel-`Box` des sichtbaren Layouts (BottomStart).

- `ListScreen.kt`: Anker = angezeigte nodeId, an der Wurzel der `ROOT`-Sentinel (aus `core/Model.kt`). UndoManager-Zugriff via `repo.undo` (Screens haben `repo` bereits).
- `PostDetailEditor.kt`: Anker = `remember { entryId ?: "new:" + UUID.randomUUID() }` — bei Neuanlage bleibt der Anker für die Dauer des Besuchs stabil; nach Schließen/Wiederöffnen ist die Kette der (dann existierenden) Notiz leer. Akzeptiert.
- `TodoDetailScreen.kt`: Anker = Todo-nodeId (deckt Body, Unterpunkte, Anhänge, Termine ab).
- `AttachmentDetailScreen.kt`: Anker = Anhang-nodeId (deckt auch Edits am Beschreibungs-Kind ab, da der Anker beim Authoring zählt, nicht die nodeId der Op).
- `CalendarEntryEditor.kt`: Anker analog PostDetailEditor (`entryId ?: "new:…"`); jede Feldänderung persistiert bereits einzeln ⇒ automatisch je ein Undo-Punkt. An der Editor-Struktur sonst nichts ändern (bewusst stabil).

### Schritt 5: Auto-Save (3-s-Debounce) + Back-Save

Muster pro Editor (Quelltext-/Edit-Modus):

```kotlin
LaunchedEffect(sourceMode, tfv.text) {          // Debounce: startet bei jedem Tastendruck neu
    if (!sourceMode) return@LaunchedEffect
    delay(3000)
    save()                                      // dank editNode-Guard gratis, wenn nichts geändert
}
```

- `PostDetailEditor.kt`: Debounce + `BackHandler`-Fix nach dem Vorbild von `AttachmentDetailScreen.kt` Z. 216 (`if (sourceMode) { save(); sourceMode = false } else onClose()`), zusätzlich `LifecycleEventObserver` auf `ON_PAUSE` → `save()` (App in Hintergrund). **Schutz:** bei `currentNodeId == null && text.isBlank()` nicht speichern (sonst legt der Debounce leere Knoten an).
- `TodoDetailScreen.kt` (`saveBody`), `ListScreen.kt` (`saveHeader`, Listen-Beschreibung inkl. Titel = 1. Zeile), `AttachmentDetailScreen.kt` (`save`, Back-Save existiert): dasselbe Debounce-Muster; Back-Save ergänzen, wo er fehlt.
- `CalendarEntryEditor.kt`: Debounce nur für den Titel/Body-Edit-Modus (Toggle-Save Z. 347–349 bleibt); die Feld-`persist()`-Aufrufe bleiben unverändert.
- Der ✓-Toggle behält seine heutige Semantik (sofort speichern + Modus wechseln). Es gibt danach keinen Pfad mehr, auf dem Text verloren geht.

### Schritt 6: Anhang-Anlegen gruppieren

In `ui/AttachmentPicker.kt` innerhalb von `addImage`/`addFile` (bzw. deren gemeinsamem Kern) die beiden `createNode`-Aufrufe (Anhang-Knoten + Beschreibungs-TEXT-Kind) in `repo.undo.group { … }` wickeln — dann ist es an ALLEN Aufrufstellen (PostDetail, Todo, Calendar, ListScreen) ein Undo-Schritt. Undo löscht beide Knoten (umgekehrte Reihenfolge), Redo restauriert beide.

### Schritt 7: Tests (`app/src/test/kotlin/de/beardedskunk/homeshare/data/UndoManagerTest.kt`)

Fake-`UndoExecutor` (HashMap nodeId → head/versions). Fälle:
1. Aufzeichnen: Entry landet in der Anker-Kette; ohne Anker → invalidate-Verhalten; `parents.size > 1` → invalidate; `suppress` → ignoriert.
2. Neue Aktion kappt Redo-Schwanz; Limit 100 wirft vorne raus (cursor korrekt).
3. Undo/Redo-Roundtrip: doneHead/undoneHead-Bookkeeping, cursor-Bewegung, Marker `restore` gesetzt, Delete-Undo ohne Marker.
4. create-Sub: Undo authort `deleted=true`, Redo restauriert Original-Content.
5. Gruppe: ein Entry, Undo in umgekehrter, Redo in Original-Reihenfolge.
6. `invalidate(nodeId)` entfernt betroffene Entries in allen Ketten, cursor konsistent.
7. Stale-Skip: Head ≠ doneHead → Entry fliegt, nächstälterer wird ausgeführt.
8. Anker-Stack: push/pop (letztes Vorkommen), Aufzeichnung auf oberstem Anker.
9. `editNode`-Guard: identischer Content → keine neue Version; restore-Marker wird bei normalem Edit gestrippt. (Stil von `FeedShareTest`/`ChildTaskCountsTest` übernehmen, s. o.; falls dort keine Repo-Instanziierung ohne Android-SQLite möglich ist, Guard-Logik in eine pure Funktion ziehen oder über den Executor-Fake abdecken.)

Lauf: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest`

### Schritt 8: Commits

Pro Chunk auf Deutsch, `[bereich] beschreibung`, Vorschlag:
1. `[data] editNode: Kein-Op-Guard + restore-Marker-Strip (Log-Müll-Fix)`
2. `[data] UndoManager: Ketten pro Screen, Gruppen, Invalidierung + Repo-Hooks`
3. `[ui] Undo/Redo-Buttons unten links auf allen Screens (fab:undo/fab:redo)`
4. `[ui] Auto-Save: 3s-Debounce + Back speichert (PostDetailEditor-Datenverlust-Fix)`
5. `[ui] Anhang-Anlegen als ein Undo-Schritt (group)`
**Nicht pushen** (nur der Plan-Commit aus Schritt 0 wird gepusht).

## Constraints / NICHT ändern

- **Kein Format-Bump, keine Sync-/Wire-Änderung**: `NodeVersion.canonical()`, `OpCodec`, `Sync.kt`-Protokoll, `ThreeWayMerge`, `autoMergeContent` bleiben unangetastet. Der restore-Marker lebt ausschließlich in `ext` und darf **nicht** in `MetaKey.KNOWN` aufgenommen werden (sonst verliert `fromMeta` den Wert — es gibt kein typisiertes Feld dafür).
- **`type` beim Editieren nie überschreiben** (Editor bearbeitet TEXT-, LIST- und TODO-Knoten) — bestehende `hc.copy(...)`-Muster beibehalten.
- **`resolveConflict` ohne Guard** — muss auch bei inhaltsgleicher Wahl eine Merge-Op schreiben.
- **Fremde Ops nie in die Undo-Kette**; Sync-Ingest invalidiert (Option a). Kein Versuch, fremde Änderungen zu mergen (Option b ist bewusst NICHT Teil von v1).
- **Undo/Redo-Ops selbst werden nicht aufgezeichnet** (suppress), tragen aber den restore-Marker; normale Edits strippen ihn.
- **Nur RAM** — keine neue DB-Tabelle, keine Persistenz der Ketten, kein Schema-Touch.
- **CalendarEntryEditor strukturell stabil lassen** (nur Anker, Buttons, Titel/Body-Debounce).
- Fremdwurzel-Reorder (`reorderNode`, Z. 128–133) unverändert lassen (kein Op → kein Undo-Eintrag).
- Tonnen-Löschen fragt weiterhin nicht nach — Undo ist jetzt das Sicherheitsnetz.
- Deutsch in UI-Strings/Kommentaren, LF, Serena für Navigation/Edits, JDK nur pro Aufruf pinnen.
- UndoManager-Zustand thread-sicher halten (`synchronized`), Aufzeichnung passiert auf Dispatchers.IO, Button-State auf Main.

## Verifikation

1. **Unit-Tests**: kompletter Lauf `:app:testDebugUnitTest` (alle ~140 + neue) grün.
2. **Build**: `:app:assembleDebug` (R8 auch im Debug — auf neue Warnungen achten).
3. **On-Device (Armor 8, `3090RH2001013207`)**, via uiautomator (testTags `fab:undo`/`fab:redo` als resource-id; Screenshots vor Auswertung auf 1/3 verkleinern, Tap-Koordinaten ×3):
   - Liste: Eintrag anlegen → Undo (weg) → Redo (wieder da, gleiche Position am Ende).
   - Swipe-Löschen → Undo stellt wieder her (inkl. orderKey/Position).
   - Drag-Reorder → Undo stellt alte Reihenfolge her.
   - Tag setzen → Undo; done-Toggle → Undo.
   - Notiz: tippen, 3 s warten (Op entsteht), weitertippen, Back im Quelltext-Modus → nichts verloren; mehrfach Undo iteriert feingranular durch die Saves; ausgegraute Buttons wenn Kette leer/am Ende.
   - Kein Log-Müll: ✓-Toggle ohne Textänderung erzeugt KEINE neue Op (Op-Zahl z. B. via Konflikt-/Debug-Sicht oder DB-Dump prüfen).
   - Anhang anlegen → EIN Undo entfernt Bild + Beschreibungs-Knoten; Redo bringt beide zurück; Bild bearbeiten → Undo zeigt altes Bild.
   - Kalender: Feld ändern → Undo stellt Feld zurück.
   - Frisch geöffneter, auf anderem Gerät erstellter Knoten: beide Buttons ausgegraut.
   - **Zwei Geräte** (Armor 8 + F101): Undo auf A → nach Sync zeigt B den alten Stand (Undo-Op ist Head). Fremd-Edit auf B an Knoten X → nach Sync auf A sind Ketteneinträge zu X weg (Undo überspringt X).
