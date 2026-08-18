# Tag-System — Implementierungsplan

Stand: 2026-07-05, Branch `feature/list-types`. Konzept mit dem Nutzer abgestimmt und final;
dieser Plan ist vollständig genug, um OHNE erneute Exploration umgesetzt zu werden.
Alle Datei-Zeilenangaben beziehen sich auf den Stand bei Planerstellung (Commit 21ea442 + Arbeitsstand).

---

## 1. Ziel der Änderung

Tags an Knoten vergeben, anzeigen, entfernen und danach suchen:

1. **Tag-Zeile** auf allen fünf Inhalts-Screens (Liste, Notiz, Aufgabe, Termin, Anhang):
   dünne, horizontal scrollbare Chip-Zeile **zwischen Top-Icon-Bar und Titelbereich**.
   Nur sichtbar, wenn der Knoten Tags hat. Ganz links **fix** (nicht mitscrollend) ein
   kleiner **Plus-Button**, danach die Chips.
2. **Vergeben**: Hamburger-Menüeintrag „Tag hinzufügen…" auf allen fünf Screens (auch wenn
   noch keine Tag-Zeile sichtbar ist) sowie der Plus-Button der Tag-Zeile. Beide öffnen
   **dasselbe** BottomSheet: Textfeld (filtert + legt Neues an) über den existierenden,
   dem Knoten noch nicht zugewiesenen Tags als Chips.
3. **Entfernen**: Long-Press auf einen Chip → kleiner Dialog mit „Vom Knoten entfernen"
   und „Nach diesem Tag suchen". Entfernen fragt nicht nach. Verschwindet die letzte
   Verwendung eines Tags, existiert das Tag automatisch nicht mehr (es gibt keine Registry).
4. **Tag-Suche**: eigener Screen (KEINE umgebaute Root-Ansicht). Einstieg über ein neues
   Icon in der Root-TopBar oder „Nach diesem Tag suchen" von überall. Zeigt alle Knoten
   (beliebige Tiefe, alle Feeds), die **alle** gewählten Tags tragen (UND-Verknüpfung).
   Nur existierende Tags wählbar, kein Freitext. Treffer zeigen einen Eltern-Breadcrumb.
   Treffer-Tap öffnet den Knoten; in Listen kann man weiter abtauchen; Back am
   Eintauchknoten führt zurück zur Trefferliste. X/Back schließt die Suche → normale Root-Ansicht.
5. **Merge**: parallel auf zwei Geräten hinzugefügte Tags werden per **Mengen-Vereinigung**
   gemergt (beide bleiben erhalten), statt wie bisher über den zeilenbasierten Listen-Merge.

**Nicht** in diesem Plan (bewusst weggelassen): Tags in Listenzeilen anzeigen, Tag global
umbenennen, ODER-/Negativ-Suche, Tags am Root.

### Vom Nutzer festgelegte Detail-Entscheidungen

- Plus-Button der Tag-Zeile: **links vorne, fixiert**, Chips scrollen rechts daneben.
- Normalisierung: trimmen, leere Strings verboten; Matching/Vorschlag/Suche **case-insensitiv**;
  angezeigt wird die Schreibweise, mit der das Tag **angelegt** wurde (existiert „Urlaub" und
  jemand tippt „urlaub", wird „Urlaub" zugewiesen — KEIN erzwungenes Lowercase).
- Breadcrumb im Treffer: bis zu **3 direkte Eltern-Titel**, mit ` / ` getrennt, Wurzelrichtung
  zuerst (also `Opa / Papa / Kind`-Reihenfolge); gibt es weitere Vorfahren darüber, wird vorne
  `… / ` vorangestellt. Der Feed-Wurzelknoten zählt als normaler Elternteil.
- Back-Verhalten: Aus der Trefferliste geöffneter Knoten kann beliebig tief weiter navigiert
  werden; zurück am **Eintauchknoten** → Trefferliste, nicht die normale Hierarchie.
- Tag-Zeile: „visuell dünn", beansprucht aber inkl. Abständen ungefähr die Höhe der Titelzeile
  (etwas Luft zur Icon-Bar und zum Titel).

---

## 2. Relevante Erkenntnisse aus der Analyse (NICHT erneut explorieren)

### Datenmodell — bereits fertig, KEINE Format-Änderung nötig

- `NodeContent.tags: List<String>` existiert (`core/Model.kt:144`), wird über
  `MetaKey.TAGS = "tags"` (`core/Model.kt:39`) und `MetaListCodec.encode/decode`
  (`core/Model.kt:100-125`, count + längenpräfixierte Elemente) in die Meta-Map serialisiert.
- `NodeState.tags: List<String>` existiert (`data/Domain.kt:45`), wird in
  `FeedRepository.readNodeState` (`FeedRepository.kt:586-611`) aus der Meta-Map gelesen.
- Persistenz: Spalte `meta` in `node_current` (encodiert via `MetaCodec`, `core/Model.kt:62-98`).
  Der Select-String `NODE_SELECT` + Spaltenindizes stehen im `private companion object` von
  `FeedRepository` (`FeedRepository.kt:641-658`). `queryNodeStates(where, args)`
  (`FeedRepository.kt:579`) ist der generische Zugriffsweg.
- **FTS indiziert Tags bereits**: `rebuildNodeState` baut den Suchtext aus
  `text + fileName + tags` (`FeedRepository.kt:572`). Die normale Lupen-Suche findet Tags
  also automatisch; jede Tag-Änderung über `editNode` triggert den Rebuild. Nichts zu tun.
- Neue Meta-Keys/Format-Bump: **nicht nötig**, Tags sind ein bekannter Key.

### Schreibmuster für Tag-Änderungen

Wie der Done-Toggle in `ListScreen.kt` (TodoRow-onDone):

```kotlin
repo.headContent(nodeId)?.let { repo.editNode(nodeId, it.copy(tags = neueListe)) }
```

`editNode` (`FeedRepository.kt:103`) authort auf die aktuellen Heads. **Niemals `type`
überschreiben** — das `copy` vom `headContent` erhält alles außer `tags`.

### Merge-Verhalten (muss geändert werden)

`Node.autoMergeContent` (`core/Node.kt:68-120`) merged die Meta-Map generisch per
3-Wege-`pick`. Für `MetaKey.TAGS` gibt es schon einen Sonderzweig, der bei echtem Konflikt
`ThreeWayMerge.list` (LCS-basiert, `core/ThreeWayMerge.kt:28-34`) benutzt. Problem: fügen
beide Seiten am **Ende** je ein anderes Tag an, ist das ein überlappender Chunk → `null` →
manueller Konflikt. Der Nutzer will **Union** (beide behalten). Der Zweig wird ersetzt
(siehe Schritt 1). Die Heads sind an der Stelle bereits deterministisch sortiert
(`sortedBy { it.versionId }`), das Ergebnis muss reihenfolge-unabhängig identisch sein.

### UI-Struktur — die fünf Einbaustellen

Alle vier Detail-Screens benutzen die gemeinsame `DetailTopBar`
(`ui/UiComponents.kt:94-146`; Slots: Back | Lupe | QR | Hamburger(`menuContent`) | ✓/✎).
Die Tag-Zeile kommt jeweils **als erstes Element in die Content-Column des Scaffold**
(direkt nach der FindBar, falls offen — die FindBar bleibt oben fix):

| Screen | Datei / Einbaustelle | Knoten-Objekt | Frische |
|---|---|---|---|
| Liste | `ui/ListScreen.kt` — Content-Column (nach dem Fremdfeed-Banner, VOR `ListHeader`, nur `container != null`) | `container: NodeState?` | **stale**: kommt aus AppRoot, wird bei Revision NICHT neu geladen → Tags lokal laden (s.u.) |
| Notiz / Listen-Beschr. | `ui/PostDetailEditor.kt` — Scaffold ab Zeile 274, Column ab 321 | `post: NodeState?`, `currentNodeId: String?` (Zeile 137; bei Neuanlage anfangs null) | **stale** → Tags lokal laden; Tag-UI nur wenn `currentNodeId != null` |
| Aufgabe | `ui/TodoDetailScreen.kt` — Scaffold ab 222, Column ab 265 | `node` (Zeile 94) | frisch (LaunchedEffect auf `revision`, Zeile 97) → `node.tags` direkt nutzbar |
| Termin | `ui/CalendarEntryEditor.kt` — Scaffold ab 267, Column ab 325 | `post: NodeState?` + `currentNodeId` (analog PostDetailEditor) | **stale** → Tags lokal laden |
| Anhang (Bild/Datei) | `ui/AttachmentDetailScreen.kt` — Scaffold ab 202, Column ab 234 | `att` (Zeile 88) | frisch (LaunchedEffect auf `revision`, Zeile 95) → `att.tags` direkt nutzbar |

Wichtig beim Anhang: Tags gehören an den **IMAGE/FILE-Knoten selbst** (`att.nodeId`),
NICHT an die Beschreibungs-Notiz (`capId`).

Hamburger-Menüs (dort kommt „Tag hinzufügen…" hinein, jeweils VOR den Löschen-Eintrag):

- ListScreen: inline `DropdownMenu` im eigenen `TopAppBar` (nur `container != null`-Zweig).
- PostDetailEditor: `menuContent` ab Zeile 281 — Achtung, die umgebende Bedingung
  (`if (!readOnly && post != null || …)`) ggf. erweitern, damit der Eintrag bei
  `!readOnly && currentNodeId != null` erscheint.
- TodoDetailScreen: `menuContent` ab Zeile 229.
- CalendarEntryEditor: `menuContent` ab Zeile 274.
- AttachmentDetailScreen: `menuContent` ab Zeile 209.

„Tag hinzufügen…" nur anbieten, wenn `!readOnly` bzw. in ListScreen `canWrite` (Fremdfeeds
mit Nur-Lese-Recht dürfen keine Tags setzen; die Tag-Zeile selbst wird read-only angezeigt:
Plus-Button und Entfernen ausblenden, „Nach diesem Tag suchen" bleibt erlaubt).

### Navigation

- `AppRoot` (`MainActivity.kt:97-175`): zustandsbasiert, `navIds: SnapshotStateList<String>`
  (rememberSaveable) = Stack geöffneter Listen; Vollbild-Ausnahmen (SharePicker, Settings,
  FeedShare) werden VOR dem `ListScreen`-Aufruf per `if (…) { …; return }` gerendert.
  Der Tag-Such-Screen wird genauso eingehängt.
- `ListScreen` rendert Detail-Screens modal über eigene States (`noteEdit`, `todoOpen`,
  `attOpen`, `calEdit`, `descEdit`) mit `BackHandler` + `return` — dieses Muster in der
  Tag-Suche wiederverwenden.
- Root-TopBar (in `ListScreen`, `isRoot`-Zweig): Lupe → QR → Settings. Das Tag-Such-Icon
  kommt zwischen Lupe und QR, nur `isRoot && !searching`.

### Sonstiges

- Icons: `material-icons-extended` ist eingebunden (`app/build.gradle.kts:57`, R8 shrinkt).
  Tag-Icon: `Icons.Filled.Sell` (Material-„Tag"-Icon; falls es im Bundle fehlen sollte:
  `Icons.Filled.LocalOffer`).
- testTags: Konvention in `ui/TestTags.kt` (`Modifier.tag(...)`, kleingeschrieben,
  Doppelpunkt-getrennt). Neue Tags siehe Schritt 7. In DropdownMenu-/Dialog-Fenstern
  erscheinen resource-ids NICHT im uiautomator-Dump (bekanntes Verhalten, ok).
- `deleteNode` (`FeedRepository.kt:107`) flaggt NUR den Knoten selbst — Kinder gelöschter
  Listen behalten `deleted = 0`. Die Tag-Suche muss Treffer mit gelöschtem Vorfahren
  ausfiltern (fällt beim Breadcrumb-Aufbau mit ab, s. Schritt 2).
- Tests: reine JVM-Unit-Tests unter `app/src/test/kotlin/de/beardedskunk/homeshare/…`
  (kein Robolectric, kein androidTest). Bestehende Merge-Tests: `core/PostConflictTest.kt`,
  `core/ThreeWayMergeTest.kt`; Meta-Roundtrip: `core/NodeMetaTest.kt`.

---

## 3. Betroffene Dateien

**Neu:**

| Datei | Inhalt |
|---|---|
| `app/src/main/kotlin/de/beardedskunk/homeshare/core/Tags.kt` | reine Tag-Logik (Normalisierung, case-insensitives Matching, Union-Merge) |
| `app/src/main/kotlin/de/beardedskunk/homeshare/ui/TagUi.kt` | `TagRow`, `TagChip`, `TagPickerSheet`, Long-Press-Dialog |
| `app/src/main/kotlin/de/beardedskunk/homeshare/ui/TagSearchScreen.kt` | Tag-Such-Screen |
| `app/src/test/kotlin/de/beardedskunk/homeshare/core/TagsTest.kt` | Tests für `core/Tags.kt` + Union-Merge in `autoMergeContent` |

**Geändert:**

| Datei | Änderung |
|---|---|
| `core/Node.kt` | `autoMergeContent`: TAGS-Zweig auf Union-Merge umstellen |
| `data/FeedRepository.kt` | neu: `allTags()`, `nodesWithTags(...)`, `parentPathTitles(...)` |
| `ui/ListScreen.kt` | Tag-Zeile + Menüeintrag (nicht-Root), Tag-Such-Icon (Root), `onSearchTag`-Param, Durchreichen an Detail-Screens |
| `ui/PostDetailEditor.kt` | Tag-Zeile + Menüeintrag + `onSearchTag`-Param |
| `ui/TodoDetailScreen.kt` | dito |
| `ui/AttachmentDetailScreen.kt` | dito |
| `ui/CalendarEntryEditor.kt` | dito (minimal-invasiv! Formular/EventCodec NICHT anfassen) |
| `MainActivity.kt` (`AppRoot`) | Tag-Such-Zustand + Screen einhängen, `onSearchTag` verdrahten |
| `ui/TestTags.kt` | Konventions-Kommentar um neue Tags ergänzen |

---

## 4. Konkrete Umsetzungsschritte

Reihenfolge einhalten; nach jedem Schritt kompilieren, nach Schritt 1+2 Tests laufen lassen.
Pro abgeschlossenem Schritt (oder sinnvollem Bündel) ein Commit `[bereich] beschreibung` (deutsch).

### Schritt 1 — `core/Tags.kt` + Union-Merge (`[core]`)

Neue Datei `core/Tags.kt`, reine Funktionen (object `Tags`), deutsche Doc-Kommentare:

```kotlin
/** Normalisiert Nutzereingabe: trimmen; leer -> null. Schreibweise bleibt erhalten. */
fun normalize(raw: String): String?

/** Case-insensitiver Contains-Test. */
fun contains(tags: List<String>, tag: String): Boolean

/**
 * Fügt [raw] normalisiert hinzu. Existiert im Vokabular [vocab] (alle Tags der App)
 * bereits eine case-insensitiv gleiche Schreibweise, wird DIESE übernommen
 * (Anzeige = Schreibweise der Erst-Anlage). Ist das Tag am Knoten schon vorhanden
 * oder die Eingabe leer -> unveränderte Liste.
 */
fun add(tags: List<String>, raw: String, vocab: List<String>): List<String>

/** Entfernt [tag] case-insensitiv. */
fun remove(tags: List<String>, tag: String): List<String>

/**
 * 3-Wege-Union für den Tag-Merge: Ergebnis = (a ∪ b) minus allem, was gegenüber
 * base auf EINER Seite entfernt wurde. Reihenfolge: erst a in seiner Reihenfolge,
 * dann neue aus b — deterministisch, weil der Aufrufer (autoMergeContent) die
 * Seiten bereits nach versionId sortiert. Duplikate (exakter String) vermeiden.
 */
fun mergeSets(base: List<String>, a: List<String>, b: List<String>): List<String>
```

`mergeSets` konkret: `removedA = base - a`, `removedB = base - b` (exakter String-Vergleich
reicht — die Werte stammen aus demselben Op-Log), Ergebnis =
`(a + b.filter { it !in a }).filter { it !in removedA && it !in removedB }`.

In `core/Node.kt#autoMergeContent` den TAGS-Zweig ersetzen: statt
`ThreeWayMerge.list(...) ?: return null` jetzt `Tags.mergeSets(baseListe, aListe, bListe)`
(decodiert wie bisher via `MetaListCodec.decode`), Ergebnis wenn nicht leer encodiert in
`merged[k]`. Der Zweig kann nicht mehr fehlschlagen (kein `return null` mehr nötig).
Kommentar anpassen („Tags sind eine Menge → Vereinigung, parallel Hinzugefügtes bleibt beides erhalten").

Tests in `TagsTest.kt` (JUnit4 wie die Nachbartests):
- normalize: trim, leer/blank → null.
- add: neu; case-insensitiv schon vorhanden → unverändert; Vokabular-Schreibweise gewinnt
  („urlaub" tippen, vocab hat „Urlaub" → „Urlaub" landet in der Liste).
- remove case-insensitiv.
- mergeSets: beide fügen je ein anderes Tag an → beide im Ergebnis; eine Seite entfernt,
  andere unverändert → entfernt; entfernen vs. gleichzeitig hinzufügen unterschiedlicher
  Tags; Determinismus (a/b vertauscht → gleiche Menge).
- End-to-End über `Node`: zwei Heads mit divergenten Tag-Ergänzungen → `autoMergeContent()`
  liefert Union statt null (Vorlage: bestehende Konflikt-Konstruktion in `PostConflictTest.kt`).

### Schritt 2 — Repository-Abfragen (`[data]`)

In `FeedRepository` (Region „Lesen", z. B. nach `search`):

```kotlin
/** Alle existierenden Tags (lebende Knoten), case-insensitiv dedupliziert
 *  (erste = angelegte Schreibweise gewinnt), alphabetisch sortiert. */
fun allTags(): List<String>
```
Implementierung: `db.rawQuery("SELECT meta FROM node_current WHERE deleted = 0 AND meta != ''", …)`,
je Zeile `MetaCodec.decode(...)[MetaKey.TAGS]` → `MetaListCodec.decode`; in eine
`LinkedHashMap<String /*lowercase*/, String /*Anzeige*/>` einsammeln (nur erste Schreibweise
behalten), Werte case-insensitiv sortiert zurückgeben.

```kotlin
/** Knoten, die ALLE [tags] tragen (case-insensitiv, UND-verknüpft), ohne solche in
 *  gelöschten Teilbäumen. */
fun nodesWithTags(tags: List<String>): List<NodeState>
```
Implementierung: `queryNodeStates("n.deleted = 0", emptyArray())`, in Kotlin filtern
(`tags.all { t -> node.tags.any { it.equals(t, ignoreCase = true) } }`), danach Vorfahren-Check:
Eltern-Kette via Map nodeId→NodeState aus derselben Gesamtabfrage hochlaufen (keine
N+1-Einzelqueries!) — trifft die Kette einen `deleted`-Knoten, Treffer verwerfen. Dafür
intern einmal ALLE Knoten laden (auch gelöschte: zweite Query `"1=1"` oder WHERE weglassen)
und daraus sowohl Filter als auch Breadcrumbs speisen. Alternativ eine kombinierte Methode:

```kotlin
/** Treffer + Breadcrumb in einem Rutsch. */
data class TagHit(val node: NodeState, val parentTitles: List<String>, val more: Boolean)
fun tagSearch(tags: List<String>): List<TagHit>
```
**Empfehlung: nur `tagSearch` + `allTags()` bauen** (eine Methode weniger API).
`parentTitles` = bis zu 3 nächste Eltern, Reihenfolge wurzelnah zuerst, `more` = es gibt
weitere Vorfahren über den dreien. Sortierung der Treffer: erst nach Feed
(`rootId`), innerhalb per `created` — die Default-Reihenfolge von `queryNodeStates` reicht.

Kleiner JVM-Test dafür ist möglich (die bestehenden data-Tests wie `FeedShareTest`
zeigen, wie ein Repository im Test instanziiert wird — gleiche Infrastruktur nutzen);
mindestens: Tag vergeben → `allTags`/`tagSearch` finden ihn; Knoten unter gelöschter
Liste wird nicht gefunden; UND-Verknüpfung; Breadcrumb-Kappung auf 3 + `more`-Flag.

### Schritt 3 — `ui/TagUi.kt`: Zeile, Chips, Picker (`[ui]`)

```kotlin
/** Dünne Tag-Zeile: fixes Plus links, rechts horizontal scrollende Chips.
 *  Rendert NICHTS, wenn [tags] leer und [readOnly] (bzw. onAdd == null). */
@Composable fun TagRow(
    tags: List<String>,
    onAdd: (() -> Unit)?,            // null = kein Plus (readOnly)
    onRemove: ((String) -> Unit)?,   // null = Entfernen nicht anbieten
    onSearchTag: ((String) -> Unit)?,// null = Suchen-Option ausblenden
)
```

- Sichtbarkeitsregel: `tags.isEmpty()` → gar nichts rendern (Zeile entfällt komplett;
  Vergeben läuft dann übers Hamburger-Menü). Bei nicht-leeren Tags: `Row` mit fixem
  Plus-`IconButton` (klein, ~32dp, `Icons.Filled.Add`, tag `tags:add`) und `LazyRow`
  (`Modifier.weight(1f)`) mit den Chips.
- Chip: kleine `Surface` (RoundedCornerShape(50), `surfaceVariant`-Farbe, `labelSmall`-Text,
  padding horizontal 8dp / vertical 2dp) mit `combinedClickable` (onClick = nichts oder
  ebenfalls Long-Press-Dialog — onLongClick = Aktions-Dialog). KEIN Material `AssistChip`
  (Mindesthöhe 32dp zu klobig). Gesamthöhe der Zeile inkl. `padding(vertical = 6dp)`
  bewusst schlank, aber mit Luft zu TopBar und Titel.
- Long-Press-Dialog (AlertDialog, Muster = `actionNode`-Dialog in `ListScreen.kt`):
  Titel = Tag-Name, `TextButton`s „Nach diesem Tag suchen" (tag `action:tag-search`) und
  „Vom Knoten entfernen" (tag `action:tag-remove`, ohne Rückfrage), „Abbrechen".
- `TagPickerSheet`:

```kotlin
/** BottomSheet zum Zuweisen: Textfeld filtert [available] (case-insensitiv) und legt
 *  bei Nicht-Treffer neu an ([allowCreate]). [assigned] wird ausgeblendet. */
@Composable fun TagPickerSheet(
    available: List<String>,   // repo.allTags()
    assigned: List<String>,    // Tags des Knotens (bei Suche: gewählte Tags)
    allowCreate: Boolean,      // true am Knoten, false in der Tag-Suche
    onPick: (String) -> Unit,  // gewähltes/neues Tag (Roh-Eingabe; Aufrufer normalisiert via Tags.add)
    onDismiss: () -> Unit,
)
```
  `ModalBottomSheet` (Material3): oben `OutlinedTextField` (tag `field:tag`), darunter
  `FlowRow` (`androidx.compose.foundation.layout.FlowRow`) mit den gefilterten, noch nicht
  zugewiesenen Chips (Tap = `onPick`). Ist die Eingabe nicht leer und existiert case-insensitiv
  weder in `available` noch `assigned`, zusätzlich oben einen Eintrag
  „‚<eingabe>' neu anlegen" (nur `allowCreate`; tag `action:tag-create`). IME-Action „Done"
  = wie Tap auf diesen Eintrag. Keine Tags vorhanden und `!allowCreate` → Hinweistext
  „Noch keine Tags vergeben.".

### Schritt 4 — Einbau in die fünf Screens (`[ui]`)

Für jeden Screen (Tabelle in Abschnitt 2 beachten):

1. Neuer optionaler Parameter `onSearchTag: ((String) -> Unit)? = null`.
2. Tag-Zustand:
   - TodoDetail: `node.tags`; AttachmentDetail: `att.tags` (beide schon revision-frisch).
   - ListScreen: `var containerTags by remember(parentId) { mutableStateOf(container?.tags ?: emptyList()) }`,
     im bestehenden `reload()` mit `repo.getNode(parentId)?.tags` aktualisieren (nur `!isRoot`).
   - PostDetailEditor / CalendarEntryEditor: analoges lokales State-Feld, initial
     `post?.tags`, aktualisiert in einem `LaunchedEffect(revision)` über
     `currentNodeId?.let { repo.getNode(it)?.tags }` (revision ist dort noch nicht
     collected → `val revision by repo.revision.collectAsState()` ergänzen).
3. Aktionen (gemeinsames Muster, IO-Dispatcher wie überall):

```kotlin
fun addTag(raw: String) = scope.launch { withContext(Dispatchers.IO) {
    val vocab = repo.allTags()
    repo.headContent(id)?.let { repo.editNode(id, it.copy(tags = Tags.add(it.tags, raw, vocab))) }
} }
fun removeTag(tag: String) = scope.launch { withContext(Dispatchers.IO) {
    repo.headContent(id)?.let { repo.editNode(id, it.copy(tags = Tags.remove(it.tags, tag))) }
} }
```
   (`id` = `container.nodeId` / `currentNodeId` / `node.nodeId` / `att.nodeId`.)
4. `TagRow(...)` als erstes Element der Content-Column einfügen (Positionen s. Abschnitt 2;
   in ListScreen zwischen Fremdfeed-Banner und `ListHeader`). `onAdd`/`onRemove` nur wenn
   schreibberechtigt, sonst null.
5. Picker-Sichtbarkeit: `var tagPicker by remember { mutableStateOf(false) }`; geöffnet vom
   Plus der TagRow UND vom neuen Hamburger-Eintrag:

```kotlin
DropdownMenuItem(
    leadingIcon = { Icon(Icons.Filled.Sell, contentDescription = null) },
    text = { Text("Tag hinzufügen…") },
    onClick = { dismiss(); tagPicker = true },
    modifier = Modifier.tag("menu:add-tag"),
)
```
   Der Sheet braucht `available = repo.allTags()` — beim Öffnen einmalig per
   `LaunchedEffect`/IO laden, nicht bei jeder Recomposition.
6. `onSearchTag` an die TagRow durchreichen. **ListScreen zusätzlich**: den Parameter an
   alle modal geöffneten Detail-Screens weitergeben (`PostDetailEditor`, `TodoDetailScreen`,
   `AttachmentDetailScreen`, `CalendarEntryEditor` — auch im `descEdit`-Zweig).
7. PostDetailEditor-Sonderfall: gesamte Tag-UI (Zeile + Menüeintrag) nur bei
   `currentNodeId != null` (bei Neuanlage existiert der Knoten erst nach dem ersten Speichern).
8. CalendarEntryEditor: NUR TagRow + Menüeintrag + revision-State ergänzen; Formular,
   `EventCodec`, persist()-Logik unangetastet lassen.

### Schritt 5 — `ui/TagSearchScreen.kt` (`[ui]`)

```kotlin
/** Tag-Suche: zeigt alle Knoten (alle Feeds, beliebige Tiefe), die ALLE gewählten Tags
 *  tragen. Eigener Screen oberhalb der normalen Navigation; X/Back schließt. */
@Composable fun TagSearchScreen(
    repo: FeedRepository, blobStore: BlobStore, sync: SyncManager, settings: Settings,
    initialTags: List<String>,
    onOpenShare: (NodeState) -> Unit, onRequestCalendarSync: () -> Unit,
    onClose: () -> Unit,
)
```

- Zustand: `selectedTags` (startet mit `initialTags`), `hits: List<TagHit>` — neu geladen in
  `LaunchedEffect(selectedTags, revision)` via `repo.tagSearch(selectedTags)` (IO).
  Wird das letzte gewählte Tag entfernt → `onClose()`.
- **TopBar** (eigene `TopAppBar`, NICHT DetailTopBar): navigationIcon = Back (`onClose`),
  title = Text „Tag-Suche", actions = nur X (`Icons.Filled.Close`, tag `topbar:close`,
  ebenfalls `onClose`). Kein QR, keine Lupe, keine Settings.
- Unter der TopBar die **Auswahl-Zeile**: gleiche Chip-Optik wie TagRow, fixes Plus links
  (öffnet `TagPickerSheet` mit `allowCreate = false`, `available = repo.allTags()`,
  `assigned = selectedTags`), Chips der gewählten Tags mit trailing „✕" im Chip
  (Tap auf ✕ = Tag aus Auswahl entfernen; tag je Chip `tagsel:<tag>`').
- **Trefferliste**: `LazyColumn`, eigene schlichte Zeile `TagHitRow` (NICHT die
  ListScreen-Rows wiederverwenden): Typ-Icon (`node.kind.uiIcon()` aus `ListScreen.kt` —
  ist top-level und paketweit nutzbar), Titel (`node.title`), darunter klein der Breadcrumb
  (`(if (more) "… / " else "") + parentTitles.joinToString(" / ")`, `labelSmall`,
  `onSurfaceVariant`), testTag `rowTag(node.title)`. Tap → öffnen. Leerzustand:
  „Keine Treffer.".
- **Öffnen + Rücknavigation** (Kern-Anforderung „Back am Eintauchknoten → Trefferliste"):
  - `val localNav = remember { mutableStateListOf<String>() }` — Stack ab Eintauchknoten.
  - LIST-Treffer: `localNav.add(node.nodeId)`. Ist `localNav` nicht leer, rendert der Screen
    statt der Trefferliste einen `ListScreen(container = repo.getNode(localNav.last()), …,
    onOpenList = { localNav.add(it.nodeId) }, onBack = { localNav.removeAt(localNav.lastIndex) },
    onSearchTag = /* s.u. */, onOpenShare = onOpenShare, …)` + `BackHandler` mit demselben Pop
    → leerer Stack = wieder Trefferliste. (ListScreen übernimmt ab da alles Weitere selbst,
    inkl. seiner eigenen modalen Detail-Screens.)
  - NOTE/TODO/CALENDAR/IMAGE/FILE-Treffer: modal wie in ListScreen (`noteEdit`/`todoOpen`/
    `calEdit`/`attOpen`-States + BackHandler + return); `parentId` für PostDetailEditor/
    CalendarEntryEditor = `node.parentId`. readOnly-Ermittlung wie in ListScreen
    (`node.isForeign`-Kette ist hier nicht verfügbar → einfachste korrekte Näherung:
    Schreibrecht über den Feed-Wurzelknoten `repo.getNode(node.rootId)` prüfen, gleiche
    Logik wie `canWrite` in ListScreen).
  - `onSearchTag` INNERHALB der Tag-Suche (aus TagRows tiefer geöffneter Knoten): ersetzt
    `selectedTags` durch `listOf(tag)` und leert `localNav` + modale States (zurück zur
    frischen Trefferliste).

**AppRoot-Verdrahtung** (`MainActivity.kt`):

- `var tagSearchTags by remember { mutableStateOf<List<String>?>(null) }` (bewusst nur
  `remember` — nach Config-Change zur normalen Ansicht zurückzufallen ist akzeptabel und
  spart einen Custom-Saver; im Plan-Risiko vermerkt).
- Vor dem `ListScreen`-Aufruf (nach dem `sharing`-Block):

```kotlin
val tagTags = tagSearchTags
if (tagTags != null) {
    BackHandler { tagSearchTags = null }
    TagSearchScreen(…, initialTags = tagTags, onOpenShare = { sharingFeed = it },
        onRequestCalendarSync = { graph.calendarSync.requestSync() },
        onClose = { tagSearchTags = null })
    return
}
```
- Dem `ListScreen`-Aufruf `onSearchTag = { tagSearchTags = listOf(it) }` mitgeben.
  Der bestehende `navIds`-Stack bleibt unangetastet → Schließen der Suche landet exakt dort,
  wo man war.

**Root-Einstieg** (in `ListScreen`, `isRoot`-Zweig der TopAppBar, zwischen Lupe und QR,
nur `!searching`):

```kotlin
IconButton(onClick = { rootTagPicker = true }, modifier = Modifier.tag("topbar:tags")) {
    Icon(Icons.Filled.Sell, contentDescription = "Nach Tags suchen")
}
```
`rootTagPicker` öffnet `TagPickerSheet` (`allowCreate = false`, `assigned = emptyList()`);
`onPick = { rootTagPicker = false; onSearchTag?.invoke(it) }`. (Der Root-Zweig braucht den
`onSearchTag`-Parameter also ebenfalls.)

### Schritt 6 — testTags dokumentieren (`[ui]`)

Konventions-Kommentar in `ui/TestTags.kt` ergänzen:
`tags:add` (Plus der Tag-Zeile), `topbar:tags` (Root-Icon), `topbar:close` (Tag-Suche zu),
`menu:add-tag`, `field:tag`, `action:tag-search|tag-remove|tag-create`, `tagsel:<tag>`.

### Schritt 7 — Bauen, Testen, Deploy (KEINE On-Device-Tests!)

Siehe Abschnitt 6. Der Nutzer testet selbst auf dem Gerät — die Session installiert nur
die frische APK aufs Pixel 8 Pro und startet die App, mehr nicht (keine uiautomator-Flows,
keine Screenshot-Verifikation, außer der Nutzer bittet darum).

---

## 5. Wichtige Constraints / NICHT ändern

- **`type` niemals überschreiben** — Tag-Änderungen immer als
  `headContent(id).copy(tags = …)` → `editNode`. Kein `NodeContent()` from scratch.
- **Kein Format-Bump, keine neuen Meta-Keys, keine DB-Migration** — alles existiert.
- **Kanonik/versionId nicht anfassen** (`NodeVersion.canonical`, `MetaCodec`,
  `MetaListCodec` unverändert lassen) — sonst divergieren Hashes zwischen App-Versionen.
- `autoMergeContent`: NUR den TAGS-Zweig ersetzen; `pick`, orderKey-LWW, Text-Merge,
  generischer Meta-Merge bleiben exakt wie sie sind. Ergebnis muss reihenfolge-unabhängig
  deterministisch bleiben.
- **CalendarEntryEditor bewusst stabil**: nur TagRow + Menüeintrag + revision-Collect.
- `ThreeWayMerge.kt` unverändert (wird weiter für Text u. a. gebraucht).
- Fremdfeeds: ohne Schreibrecht keine Tag-Vergabe/-Entfernung (UI ausblenden); Suche/Anzeige ok.
- Neue Logik testbar halten: Normalisierung/Merge nach `core/` (reine Funktionen), UI dünn.
- Deutsch in UI-Strings und Kommentaren; Kommentarstil der Umgebung; Repo ist LF.
- **`gradle.properties` ist lokal modifiziert (maschinenspezifische Pfade) — NIEMALS
  committen**; ebenso die anderen untracked `docs/*-plan.md` nicht mit einsammeln
  (nur gezielt `git add` auf die eigenen Änderungen + `docs/tag-system-plan.md` falls gewünscht).
- Commits pro Chunk auf `feature/list-types`, Format `[core]/[data]/[ui] …`; **nicht pushen**.
- Serena-MCP für Code-Navigation und -Edits benutzen (token-effizient).

---

## 6. Test-/Verifikationsbefehle

JDK pro Aufruf pinnen — auf DIESEM Rechner heißt das Verzeichnis „Android Studio1":

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr" ./gradlew :app:testDebugUnitTest
JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr" ./gradlew :app:assembleDebug
```

Alle ~140 Bestandstests müssen grün bleiben (besonders `PostConflictTest`,
`ThreeWayMergeTest`, `NodeMetaTest`, `FeedShareTest`).

**Deploy aufs Pixel 8 Pro** (Wireless-TLS, verifiziertes Rezept; adb liegt unter
`~/AppData/Local/Android/Sdk/platform-tools/adb.exe`):

1. `adb devices -l` — Pixel erscheint als `adb-45111FDJG0002R-….\_adb-tls-connect._tcp`.
2. Falls nicht sichtbar: `adb kill-server && adb start-server`, kurz warten, erneut
   `adb devices -l` (mDNS verbindet automatisch; KEIN `adb connect`, Port 5555 ist zu).
   Der Suffix nach der Serial ist ephemer → immer den aktuellen String aus `devices -l` nehmen.
3. `adb -s <device-string> install -r app/build/outputs/apk/debug/app-debug.apk`
4. `adb -s <device-string> shell am start -n de.beardedskunk.homeshare/.MainActivity`

Danach STOPP — On-Device-Prüfung macht der Nutzer selbst.

---

## 7. Offene Risiken

1. **Union-Merge ändert Konfliktverhalten**: Fälle, die bisher als manueller Konflikt
   endeten (divergente Tag-Listen), lösen sich jetzt automatisch. Gewollt — aber ein
   Alt-Gerät ohne dieses Update merged denselben Konflikt anders (dort ggf. manuell).
   Kein Hash-/Formatproblem, nur uneinheitliche Auto-Auflösung während der Übergangszeit.
2. **`tagSearch`-Vollscan** über `node_current` (zweifach: lebende + alle für
   Vorfahren-Check). Bei persönlicher Datenmenge unkritisch; falls spürbar träge, Meta-Spalte
   vorfiltern (`meta LIKE '%tags%'` als Grob-Filter) — erst optimieren, wenn nötig.
3. **Stale-Container in ListScreen/PostDetailEditor/CalendarEntryEditor**: Die Screens
   halten teils veraltete NodeStates. Der Plan sieht lokale, revision-getriebene Tag-States
   vor (Schritt 4.2) — beim Einbau prüfen, dass nach Add/Remove die Zeile sofort nachzieht
   (revision-Bump kommt durch `editNode`).
4. **`Icons.Filled.Sell`**: sollte in material-icons-extended existieren; falls der Name in
   der eingebundenen Version fehlt → `Icons.Filled.LocalOffer` verwenden.
5. **Tag-Suche überlebt keinen Config-Change** (bewusst nur `remember` in AppRoot).
   Akzeptiert; bei Bedarf später `rememberSaveable` mit `listSaver` nachrüsten.
6. **readOnly-Ermittlung in der Tag-Suche** (über `node.rootId` → Wurzel-`foreignRight`)
   nachprüfen: `getNode(rootId)` liefert bei abonnierten Fremd-Wurzeln den `foreignOrigin`/
   `foreignRight` über den `foreign_refs`-Join — sollte damit exakt der ListScreen-Logik
   entsprechen. Im Zweifel Treffer aus Fremdfeeds erstmal readOnly öffnen (sicherer Default).
7. **BottomSheet + IME**: `ModalBottomSheet` mit Textfeld braucht ggf. `imePadding()` im
   Sheet-Inhalt, damit die Tastatur das Feld nicht verdeckt — auf dem Gerät checkt das der
   Nutzer, im Code vorsorglich setzen.
8. **Chip-Zeile in Fremdfeeds ohne Schreibrecht**: Zeile erscheint nur mit Tags und ohne
   Plus/Entfernen — sicherstellen, dass `TagRow` mit `onAdd = null, onRemove = null` sauber
   rendert (nur Chips + Such-Option).
