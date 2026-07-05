# PLAN — UI-Fixes Runde 2 (Item-Reihenfolge, Orientierung, Kalender-Anhänge, Bildstreifen, Scroll-Puffer, Zoom-Bug)

> **Arbeitsauftrag für eine neue Claude-Code-Session.** Vor jeder Code-Navigation/-Änderung
> **Serena-MCP** benutzen (`get_symbols_overview`, `find_symbol` mit `include_body=true`,
> `replace_symbol_body`, `replace_content`, `insert_after_symbol`) — nicht „blind" mit Read/Edit
> arbeiten, das ist token-sparsamer und robuster. Repo ist **LF** (in `.serena/project.yml` gesetzt).
> UI-Strings **und** Kommentare **auf Deutsch**; Kommentarstil des Umfelds übernehmen.
> Commits pro fertigem Chunk als `[ui] …` bzw. `[data] …` auf Deutsch — **nicht pushen** ohne Auftrag.

Alle Pfade relativ zu `D:\AndroidProjekte\ClipSharing`. Java-Paket:
`de.beardedskunk.homeshare`, Quellwurzel `app/src/main/kotlin/de/beardedskunk/homeshare/…`.

> **Zeilennummern sind Stand der Analyse (2026-07-04) und verschieben sich beim Editieren.**
> Immer per Serena-Symbolsuche neu verorten, nie auf die Zahlen verlassen.

---

## 1. Ziel der Änderung

Sechs unabhängige UI-Fehler/Wünsche in der HomeShare-App beheben. Die Punkte sind **voneinander
unabhängig** und können in beliebiger Reihenfolge und je als eigener Commit umgesetzt werden.

- **Punkt 1 — Neue Items ans Ende.** Neu angelegte Listen-Items (Notiz, Liste, Aufgabe, Termin,
  Bild, Datei) erscheinen aktuell **ganz oben** in der Liste. Sie sollen **ans Ende** (unten)
  einsortiert werden.
- **Punkt 2 — Orientierungswechsel wirft zurück nach Root.** Beim Drehen des Geräts (Hoch-↔Querformat)
  springt die App aus der aktuell geöffneten (Unter-)Liste zurück zur Wurzel („Feeds"). Der
  Navigations-Zustand soll den Konfigurationswechsel **überleben**.
- **Punkt 3 — Anhänge an Kalender-Einträgen.** Kalender-Einträge (CALENDAR-Knoten) sollen — wie
  Notizen und Aufgaben — unten einen **Anhänge-Bereich** (Bilder + Dateien) bekommen. Diese Anhänge
  werden **nicht** in den Android-Kalender gesynct (der kann das nicht), sollen aber **in unserer App
  existieren** und mitsyncen.
- **Punkt 4 — Bildstreifen in Items zu breit.** Der Miniatur-Bildstreifen rechts in Listenzeilen ist
  gefühlt halbe Bildschirmbreite. Er soll **nie über die Bildschirmmitte nach links** ragen und dann
  **nochmal 10 % schlanker** sein (= max. 45 % der Bildschirmbreite).
- **Punkt 5 — Plus-Button verdeckt den letzten Eintrag.** Beim Scrollen ans Listenende überdeckt der
  runde FAB (Plus) unten rechts die Infos des letzten Eintrags. Man soll **etwas weiter ins Leere
  scrollen** können, sodass der letzte Eintrag **über** dem FAB zu liegen kommt.
- **Punkt 6 — Zoom-Bug: kein Rauszoomen mehr.** In der **Bild-Anhang-Detailansicht** (wo über dem Bild
  zusätzlich der **Bild-Titel und das gerenderte Markdown** stehen) kann man nach starkem Reinzoomen
  **nicht mehr rauszoomen**. Tritt **nur** in dieser kombinierten Ansicht auf (nicht im Vollbild-Viewer).

---

## 2. Betroffene Dateien

| Datei | Punkt | Was |
|---|---|---|
| `data/FeedRepository.kt` | **1** | `createNode(...)` bekommt eine „ans Ende einsortieren"-Logik (zentraler Fix für ALLE Anlege-Pfade) |
| `MainActivity.kt` **oder** `app/src/main/AndroidManifest.xml` | **2** | Navigations-Stack config-change-fest machen (Empfehlung: Manifest `configChanges`) |
| `ui/CalendarEntryEditor.kt` | **3** | Anhänge-Kasten (`AttachmentBox`) + Laden (`loadAttachmentRows`) + Bild/Datei-Picker anfügen |
| `ui/ListScreen.kt` | **4, 5** | `RowImageStrip`-Breite (Punkt 4); `contentPadding` der Listen-`LazyColumn` (Punkt 5) |
| `ui/AttachmentDetailScreen.kt` | **6** | Bild-`Box` clippen, damit das gezoomte Bild nicht in den Scroll-Bereich darüber überläuft |
| `ui/Attachments.kt` | **3** (nur lesen/nutzen) | `AttachmentBox`, `AttachmentRow`, `loadAttachmentRows` — Signaturen unten dokumentiert; **nicht ändern** |
| `ui/AttachmentPicker.kt` | **3** (nur nutzen) | `AttachmentPicker.addImage/addFile` — **nicht ändern** |
| `ui/PostDetailEditor.kt` / `ui/TodoDetailScreen.kt` | **3** (nur lesen als Vorbild) | Referenz-Aufrufe von `AttachmentBox` + Picker-FAB; **nicht ändern** |
| `core/OrderKeys.kt` | **1** (nur nutzen) | `OrderKeys.between`, `OrderKeys.effective` — **nicht ändern** |
| `app/src/test/.../core/OrderKeysTest.kt` | **1** (evtl. Testfall) | ggf. Regressionstest ergänzen |

---

## 3. Relevante Erkenntnisse aus der Analyse

### 3.1 Punkt 1 — Warum neue Items oben landen (Ursache)

- Alle Nutzer-Anlagen laufen **zentral** durch `FeedRepository.createNode(content: NodeContent)`
  (`data/FeedRepository.kt`, ~Z. 89–93):
  ```kotlin
  fun createNode(content: NodeContent): NodeState {
      val id = UUID.randomUUID().toString()
      author(id, emptySet(), content)
      return getNode(id)!!
  }
  ```
  Aufrufer (alle mit **leerem** `orderKey`): `createList` (Listen), `ListScreen`/`TodoDetailScreen`
  (TODO), `PostDetailEditor` (Notiz), `CalendarEntryEditor` + `IcsParser` (Termin),
  `AttachmentPicker.addImage/addFile` + `WebServer`/`SharePickerScreen` (Bild/Datei + Caption).
- `NodeContent.orderKey` ist standardmäßig `""` (`core/Model.kt`).
- Sortierung der Geschwister (`data/Domain.kt`, ~Z. 12–13):
  ```kotlin
  val siblingOrder: Comparator<NodeState> =
      compareBy({ OrderKeys.effective(it.orderKey, it.created) }, { it.created }, { it.nodeId })
  ```
  `OrderKeys.effective(orderKey, created)` gibt bei leerem `orderKey` den **HLC-Seed** zurück
  (`core/OrderKeys.kt`, ~Z. 20–23):
  ```kotlin
  fun seed(created: Hlc): String = "%016x%08x".format(created.wallMillis, created.counter)
  fun effective(orderKey: String, created: Hlc): String = orderKey.ifEmpty { seed(created) }
  ```
- **Kern des Bugs:** Der Seed ist ein Hex-String mit **führenden Nullen** (`%016x` → z. B.
  `000001978fd6a740…`). Sobald ein Geschwister-Knoten jemals einen **echten** `orderKey` aus
  `OrderKeys.between(...)` bekommen hat (durch Drag/Umsortieren; solche Keys beginnen mit `1`–`f`),
  sortiert der führende-Nullen-Seed **lexikografisch DAVOR** (`"00000…" < "8"`). → Frisch angelegte
  Items (leerer orderKey = Seed) landen **oben** vor den umsortierten. In einer nie umsortierten
  Liste fällt es nicht auf, weil dort alle Seeds sind und der neueste (größte Seed) unten landet.
- **`children(parentId)`** (`data/FeedRepository.kt`, ~Z. 148) liefert die (nicht gelöschten) Kinder
  **bereits `siblingOrder`-sortiert** — `.lastOrNull()` ist also das visuell letzte Kind.
- `OrderKeys.between(a, b)` mit `b == null` erzeugt einen Schlüssel **strikt hinter `a`** (offenes Ende).

### 3.2 Punkt 2 — Warum der Orientierungswechsel nach Root springt

- Navigation ohne Framework, rein zustandsbasiert in `MainActivity.kt` → `fun AppRoot(...)` (~Z. 99):
  ```kotlin
  val navStack = remember { mutableStateListOf<NodeState>() }   // Z. ~101
  // …
  val current = navStack.lastOrNull()                           // Z. ~114
  onOpenList = { navStack.add(it) },                            // Z. ~165
  onBack     = { navStack.removeAt(navStack.lastIndex) },       // Z. ~169
  ```
  `container == null` (leerer Stack) ⇒ Wurzelansicht „Feeds".
- **`remember` (nicht `rememberSaveable`)** ⇒ der Stack lebt nur im RAM einer Compose-Instanz.
- Das `AndroidManifest.xml` (`app/src/main/AndroidManifest.xml`, `<activity android:name=".MainActivity">`)
  hat **kein `android:configChanges`** ⇒ bei Orientierungswechsel wird die Activity **komplett neu
  erstellt**, `remember`-States gehen verloren, der Stack ist leer ⇒ zurück zu „Feeds".
- `NodeState` (`data/Domain.kt`, ~Z. 31) ist eine große `data class` (viele Felder), **nicht
  `Parcelable`** ⇒ `rememberSaveable` würde ohne eigenen `Saver` nicht funktionieren.

### 3.3 Punkt 3 — Anhänge-Infrastruktur (bereits vorhanden, wiederverwenden!)

- **`data class AttachmentRow(val node: NodeState, val captionTitle: String)`** (`ui/Attachments.kt`, ~Z. 34).
- **`fun loadAttachmentRows(repo, parentId): List<AttachmentRow>`** (`ui/Attachments.kt`, ~Z. 37–43) —
  lädt die IMAGE/FILE-Kinder von `parentId` samt Caption-Titel. **IO — in `Dispatchers.IO` aufrufen.**
- **`@Composable fun AttachmentBox(...)`** (`ui/Attachments.kt`, ~Z. 50) — Signatur:
  ```kotlin
  fun AttachmentBox(
      attachments: List<AttachmentRow>,
      blobStore: BlobStore,
      modifier: Modifier = Modifier,
      openTrashKey: String? = null,
      onOpenTrash: ((String?) -> Unit)? = null,
      onDelete: ((AttachmentRow) -> Unit)? = null,
      onReorder: ((moved: NodeState, prev: NodeState?, next: NodeState?) -> Unit)? = null,
      onOpen: (AttachmentRow) -> Unit,
  )
  ```
  Rendert **nichts**, wenn `attachments` leer ist (`if (attachments.isEmpty()) return`).
- **Vorbild-Aufruf** in `ui/PostDetailEditor.kt` (~Z. 103–111 Laden, ~Z. 264–278 Aufruf):
  ```kotlin
  var attachments by remember { mutableStateOf<List<AttachmentRow>>(emptyList()) }
  var attOpen by remember { mutableStateOf<NodeState?>(null) }
  val revision by repo.revision.collectAsState()
  LaunchedEffect(revision, currentNodeId) {
      attachments = if (currentNodeId != null)
          withContext(Dispatchers.IO) { loadAttachmentRows(repo, currentNodeId!!) } else emptyList()
  }
  // …
  AttachmentBox(attachments, blobStore,
      openTrashKey = openTrash, onOpenTrash = { openTrash = it },
      onDelete = { a -> scope.launch { withContext(Dispatchers.IO) { repo.deleteNode(a.node.nodeId) } } },
      onReorder = { moved, prev, next -> scope.launch { withContext(Dispatchers.IO) { repo.reorderNode(moved.nodeId, prev, next) } } },
      onOpen = { attOpen = it.node })
  ```
- **Anhänge hinzufügen** (Picker): `AttachmentPicker.addImage(context, repo, blobStore, parentId, uri)`
  und `AttachmentPicker.addFile(context, repo, blobStore, parentId, uri)` (`ui/AttachmentPicker.kt`,
  ~Z. 37/49). `parentId` = **die nodeId des Kalender-Knotens**. Launcher-Muster (aus `ListScreen.kt`
  ~Z. 298–307):
  ```kotlin
  val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
      scope.launch { withContext(Dispatchers.IO) { uris.forEach { AttachmentPicker.addImage(context, repo, blobStore, calNodeId, it) } } ; reload/revision }
  }
  val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) scope.launch { withContext(Dispatchers.IO) { AttachmentPicker.addFile(context, repo, blobStore, calNodeId, uri) } ; … }
  }
  ```
- **`CalendarEntryEditor`** (`ui/CalendarEntryEditor.kt`, ~Z. 85–244): `Scaffold { padding -> Column(
  Modifier.fillMaxSize().padding(padding).imePadding().padding(16.dp).verticalScroll(...)) { … } }`.
  Reihenfolge der Felder: Titel, Ganztägig, Start, Ende, Ort, Erinnerung, Wiederholung, Gebucht/Frei,
  Beschreibung, **Save-Button (~Z. 241–242)**. **Danach** kommt der Anhänge-Kasten.
- **Der Termin-Datenpfad bleibt unberührt:** Der CALENDAR-Knoten speichert im `text`-Feld die
  `EventCodec.encode(...)`-Kodierung (`data/CalendarEvent.kt`). Anhänge sind **separate IMAGE/FILE-
  Kindknoten** des CALENDAR-Knotens — sie berühren `EventCodec` nicht und werden bewusst **nicht** in
  den Android-Kalender gesynct.
- **Wichtige Einschränkung:** Ein **neuer, noch nicht gespeicherter** Termin hat noch **keine nodeId**
  (`post == null`). Anhänge brauchen aber eine Eltern-nodeId. ⇒ Anhänge-Bereich **nur anzeigen, wenn
  `post != null`** (also für bereits gespeicherte Einträge). Siehe Umsetzung.

### 3.4 Punkt 4 — Bildstreifen-Breite

- `ui/ListScreen.kt`, `@Composable private fun RowImageStrip(...)` (~Z. 822–850). Aktuell:
  ```kotlin
  val config = LocalConfiguration.current
  // Nie in die linke Bildschirmhälfte ragen: max. halbe Bildschirmbreite.
  val maxStripWidth = (config.screenWidthDp / 2).dp          // ← 50 % — zu breit
  // …
  LazyRow(state = listState, modifier = Modifier.widthIn(max = maxStripWidth).height(cellSize)
      .horizontalFadingEdges(fadeLeft, fadeRight)) { … }
  ```
- `LocalConfiguration.current` reagiert automatisch auf Orientierung ⇒ Querformat gibt mehr Platz.
- Nur diese eine Konstante ändern; die weichen Ausblende-Ränder (`horizontalFadingEdges`, ~Z. 852)
  bleiben unverändert.

### 3.5 Punkt 5 — FAB verdeckt letzten Eintrag

- `ui/ListScreen.kt`: Die Listen-`LazyColumn` (~Z. 531) hat **kein `contentPadding`**:
  ```kotlin
  LazyColumn(Modifier.fillMaxSize(), state = listState) { itemsIndexed(displayed, …) { … } }
  ```
  Das Scaffold-`padding` wird nur auf die äußere `Column` gelegt, nicht als unterer Innenabstand der
  Liste. Der FAB (`Modifier.size(56.dp)`, `shadowElevation = 6.dp`, Standard-Scaffold-Position unten
  rechts, ~Z. 475–505) schwebt über dem Listenende.

### 3.6 Punkt 6 — Zoom-Bug (Ursache identifiziert)

- `ui/AttachmentDetailScreen.kt`, im `Scaffold { padding -> Column(...) { … } }` (~Z. 217–334):
  - **Oben** eine **scrollbare** Beschreibung: `Column(Modifier.weight(1f, fill = false)
    .verticalScroll(rememberScrollState())) { Text(title, headlineSmall) ; MarkdownBody(...) }` (~Z. 220–245).
  - **Darunter** der **fixe** Bild-Block: `Box(Modifier.fillMaxWidth().weight(1f)` mit drei
    `pointerInput`-Blöcken (Finger-Zähler, `detectTapGestures`, `detectTransformGestures`), das `Image`
    hat `graphicsLayer { scaleX = scale; scaleY = scale; translationX/ Y = offset }` (~Z. 256–288).
- **Ursache:** `graphicsLayer` clippt standardmäßig **nicht** (`clip = false`). Beim starken
  Reinzoomen (`scale` bis 6f) wird das Bild visuell so groß, dass es **über die Box-Grenzen nach oben
  in den Bereich der scrollbaren Beschreibung überläuft**. Das Compose-**Hit-Testing** richtet sich
  aber nach den **Layout-Grenzen** der Box, nicht nach dem gezoomten Bild. Legt der Nutzer die Finger
  auf das **sichtbar** vergrößerte Bild (das in Wahrheit über der Beschreibung liegt), landen die
  Touches bei der **scrollbaren Beschreibungs-Column** statt beim Bild-`Box` ⇒ die
  `detectTransformGestures`-Geste bekommt die Finger nicht ⇒ **Rauszoomen unmöglich**. Das erklärt
  exakt: „nur in der kombinierten Ansicht" (nur dort gibt es den Scroll-Nachbarn darüber) und „erst
  nach starkem Reinzoomen" (erst dann überläuft das Bild).
- Der Pointer-Zähler-Fix aus Commit `7eff6d7` (`onLongPress = { if (pointerCount <= 1) menuOpen = true }`)
  bekämpft nur ein Nebenproblem (Long-Press-Menü) und behebt den Überlauf **nicht**.

---

## 4. Konkrete Umsetzungsschritte

> Empfohlene Reihenfolge (klein/isoliert → größer): **4 → 5 → 1 → 6 → 2 → 3.**
> Nach jedem Punkt bauen (`assembleDebug`) und — wo möglich — testen. Je 1 Commit.

### Punkt 4 — Bildstreifen schlanker (1 Zeile)

In `ui/ListScreen.kt`, Funktion `RowImageStrip`, die Berechnung von `maxStripWidth` ändern:

```kotlin
// Nie über die Bildschirmmitte hinaus (max. halbe Breite) und nochmal 10 % schlanker.
val maxStripWidth = (config.screenWidthDp * 0.45f).dp
```

(0.45 = halbe Breite × 0,9.) Kommentar entsprechend anpassen. Sonst nichts ändern.

### Punkt 5 — Scroll-Puffer unter der Liste (1 Zeile)

In `ui/ListScreen.kt` die Listen-`LazyColumn` (die mit `itemsIndexed(displayed, …)`) um ein
`contentPadding` mit unterem Abstand ergänzen, damit der letzte Eintrag über den FAB gescrollt werden
kann:

```kotlin
LazyColumn(
    Modifier.fillMaxSize(),
    state = listState,
    contentPadding = PaddingValues(bottom = 88.dp),   // Platz, damit der FAB (56dp) den letzten Eintrag nicht verdeckt
) { itemsIndexed(displayed, key = { _, n -> n.nodeId }) { index, node -> … } }
```

Import `androidx.compose.foundation.layout.PaddingValues` ggf. ergänzen (Serena `insert`/prüfen, ob
schon importiert). 88.dp = 56 (FAB) + 2×16 Rand. **Nicht** die äußere `Column` anfassen.

### Punkt 1 — Neue Items ans Ende (zentraler Repository-Fix)

In `data/FeedRepository.kt`, `fun createNode(...)` so umbauen, dass bei **leerem** `orderKey` ein
Schlüssel **hinter dem letzten Geschwister** vergeben wird (deckt alle Anlege-Pfade auf einen Schlag ab):

```kotlin
fun createNode(content: NodeContent): NodeState {
    val id = UUID.randomUUID().toString()
    // Neue Knoten ans ENDE ihrer Geschwister einsortieren. Ohne das sortiert der leere orderKey
    // über den HLC-Seed (führende Nullen) lexikografisch VOR bereits umsortierte Geschwister mit
    // echten Schlüsseln – neue Items poppten dann oben auf.
    val withKey = if (content.orderKey.isEmpty()) {
        val last = children(content.parentId).lastOrNull()
        val loKey = last?.let { OrderKeys.effective(it.orderKey, it.created) }
        content.copy(orderKey = OrderKeys.between(loKey, null))
    } else content
    author(id, emptySet(), withKey)
    return getNode(id)!!
}
```

- Import `de.beardedskunk.homeshare.core.OrderKeys` prüfen/ergänzen (ist im selben Modul; `reorderNode`
  nutzt `OrderKeys` bereits → Import existiert vermutlich schon).
- **Warum zentral:** Notiz, Liste, Aufgabe, Termin, Bild, Datei, Caption, Web-/Share-/ICS-Import laufen
  alle durch `createNode` → alle profitieren, ohne 6 Call-Sites einzeln anzufassen.
- **Caption-TEXT-Knoten** (unter Bild/Datei) bekommen ebenfalls einen Endschlüssel — harmlos (i. d. R.
  genau eine Caption pro Anhang).
- **Sync/Merge unberührt:** Remote-Knoten kommen über `author(...)`/`resolveConflict(...)` herein,
  **nicht** über `createNode`.

### Punkt 6 — Zoom-Bug: Bild-Box clippen

In `ui/AttachmentDetailScreen.kt`, im Bild-Zweig (`if (att.kind == NodeKind.IMAGE && att.blobHash != null)`),
die **Bild-`Box`** so clippen, dass das gezoomte Bild nicht mehr über die Box-Grenzen (in den
Scroll-Nachbarn darüber) hinausläuft. Am einfachsten `.clipToBounds()` **an den Anfang** der
`Modifier`-Kette der Box setzen:

```kotlin
Box(
    Modifier.fillMaxWidth().weight(1f)
        .clipToBounds()                    // <-- NEU: gezoomtes Bild bleibt in der Box, Touches bleiben zuordenbar
        .pointerInput(att.blobHash) { … }  // Finger-Zähler (unverändert)
        .pointerInput(att.blobHash) { detectTapGestures(…) }
        .pointerInput(att.blobHash) { detectTransformGestures { _, pan, zoom, _ -> … } },
    contentAlignment = Alignment.Center,
) { … }
```

- Import `androidx.compose.ui.draw.clipToBounds` ergänzen.
- Die drei `pointerInput`-Blöcke und die Zoom-Mathematik **unverändert lassen** — nur clippen.
- **Nach dem Bau unbedingt auf dem Gerät prüfen** (siehe §6): stark reinzoomen, dann rauszoomen.
- Optional (Konsistenz, geringes Risiko): dieselbe `.clipToBounds()`-Zeile auch im Vollbild-Viewer
  `ui/ImageViewerScreen.kt` an der analogen Bild-Box ergänzen. Dort tritt der Bug laut Nutzer nicht
  auf (kein Scroll-Nachbar), daher optional.

### Punkt 2 — Orientierungswechsel: Zustand erhalten

**Empfohlener Weg (robust, 1 Zeile, erhält ALLE States inkl. Scrollposition & Editier-Zustände):**
In `app/src/main/AndroidManifest.xml` beim `<activity android:name=".MainActivity" …>` das Attribut
ergänzen:

```xml
android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden|uiMode|density"
```

Damit wird die Activity beim Drehen **nicht neu erstellt**; Compose recomposed nur, `LocalConfiguration`
aktualisiert sich weiterhin (der Bildstreifen aus Punkt 4 bleibt also responsiv). Kein Kotlin-Code nötig.

> **Achtung Berechtigung:** Der Manifest-Ordner ist in dieser Session evtl. schreibgeschützt (Tool
> könnte verweigern). Falls das Editieren der Manifest-Datei nicht erlaubt ist, **den Nutzer bitten**,
> die eine Zeile selbst einzufügen, **oder** den Fallback unten nehmen.

**Fallback (falls Manifest nicht editierbar) — nur den Navigations-Stack persistieren:**
In `MainActivity.kt` → `AppRoot(...)` den `navStack` als **Liste von nodeIds** (Strings sind Saveable)
speichern und die `NodeState`-Objekte daraus rekonstruieren:

```kotlin
// Statt: val navStack = remember { mutableStateListOf<NodeState>() }
val navIds = rememberSaveable { mutableStateListOf<String>() }              // nur IDs überleben Config-Change
val current: NodeState? = navIds.lastOrNull()?.let { graph.repo.getNode(it) }  // aus dem Graph rekonstruieren
// onOpenList = { navIds.add(it.nodeId) }
// onBack     = { navIds.removeAt(navIds.lastIndex) }
// SharePicker "onShared": navIds.clear(); navIds.add(feed.nodeId)
```
Dabei alle bisherigen `navStack.add/removeAt/lastOrNull/clear`-Stellen (~Z. 114, 128, 165, 169) auf
`navIds` umstellen. `graph.repo.getNode(id)` liefert den aktuellen `NodeState` (vorhandene Funktion).
Beachten: gibt `getNode` `null` (Knoten inzwischen gelöscht), sauber auf Root zurückfallen.

> Der Manifest-Weg ist **vorzuziehen**: weniger Code, erhält zusätzlich Suchzustand, Scrollposition
> und offene Dialoge. Den Fallback nur nehmen, wenn das Manifest wirklich nicht angefasst werden darf.

### Punkt 3 — Anhänge an Kalender-Einträgen

In `ui/CalendarEntryEditor.kt`, Funktion `CalendarEntryEditor`. Vorbild ist `PostDetailEditor.kt`
(§3.3). Schritte:

1. **Benötigte Parameter/Imports:** `blobStore: BlobStore` wird gebraucht (für `AttachmentBox` +
   Picker). Prüfen, ob `CalendarEntryEditor` den `blobStore` schon bekommt; **falls nicht**, den
   Parameter ergänzen **und** den einzigen Aufrufer in `ui/ListScreen.kt` (~Z. 392,
   `CalendarEntryEditor(repo = repo, parentId = parentId, post = calEdit, onClose = …)`) um
   `blobStore = blobStore` erweitern (`blobStore` ist in `ListScreen` vorhanden).
2. **State + Laden** (oben in der Funktion, nach den vorhandenen `var`-Deklarationen):
   ```kotlin
   val scope = rememberCoroutineScope()          // falls noch nicht vorhanden
   val context = LocalContext.current            // falls noch nicht vorhanden
   val revision by repo.revision.collectAsState()
   var attachments by remember { mutableStateOf<List<AttachmentRow>>(emptyList()) }
   var attOpen by remember { mutableStateOf<NodeState?>(null) }
   var openTrash by remember { mutableStateOf<String?>(null) }
   LaunchedEffect(post?.nodeId, revision) {
       val id = post?.nodeId
       attachments = if (id != null) withContext(Dispatchers.IO) { loadAttachmentRows(repo, id) } else emptyList()
   }
   ```
3. **Anhang-Detail modal öffnen** (wie in PostDetailEditor/TodoDetailScreen): wenn `attOpen != null`,
   `AttachmentDetailScreen(...)` rendern und mit `return` aus dem restlichen Editor aussteigen — den
   exakten Aufruf aus `ui/TodoDetailScreen.kt` (Suche nach `attOpen`) bzw. `ui/PostDetailEditor.kt`
   übernehmen (gleiche Argumente: `repo, blobStore, att = attOpen!!, onClose = { attOpen = null }`,
   ggf. `readOnly`). **Vorlage 1:1 kopieren**, nicht neu erfinden.
4. **Picker-Launcher** (`pickImages`, `pickFile`) analog zu `ListScreen.kt` (§3.3) anlegen, mit
   `parentId = post!!.nodeId` (nur erreichbar, wenn `post != null`).
5. **Anhänge-Kasten rendern:** Im scrollbaren `Column`-Inhalt **nach dem Save-Button** (~Z. 242),
   **nur wenn `post != null`**:
   ```kotlin
   if (post != null) {
       Text("Anhänge", style = MaterialTheme.typography.titleSmall)  // schlichte Überschrift
       AttachmentBox(
           attachments, blobStore,
           openTrashKey = openTrash, onOpenTrash = { openTrash = it },
           onDelete = { a -> openTrash = null; scope.launch { withContext(Dispatchers.IO) { repo.deleteNode(a.node.nodeId) } } },
           onReorder = { moved, prev, next -> scope.launch { withContext(Dispatchers.IO) { repo.reorderNode(moved.nodeId, prev, next) } } },
           onOpen = { attOpen = it.node },
       )
       Row {  // zwei Buttons zum Hinzufügen (kein FAB nötig, im Scroll-Content einfacher)
           TextButton(onClick = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Bild anhängen") }
           TextButton(onClick = { pickFile.launch(arrayOf("*/*")) }) { Text("Datei anhängen") }
       }
   } else {
       Text("Anhänge können nach dem Speichern hinzugefügt werden.", style = MaterialTheme.typography.bodySmall)
   }
   ```
6. **Reload nach Picker:** `loadAttachmentRows` hängt an `revision` — prüfen, ob `AttachmentPicker.
   addImage/addFile`/`repo` die `revision` erhöhen (tun sie, da sie über `createNode`→`author` laufen).
   Falls die Liste sich nicht aktualisiert, im Picker-Callback zusätzlich neu laden.
7. **EventCodec/Termin-Formular nicht anfassen** — nur additiv unten anhängen.

---

## 5. Wichtige Constraints / NICHT ändern

- **Backend/Datenmodell:** Außer dem `createNode`-Fix (Punkt 1) **keine** Änderungen an `core/`,
  `data/Db.kt`, Op-Log, `NodeContent`-Feldern, `EventCodec` oder Sync. `OrderKeys.kt` **nicht** ändern.
- **Beim Editieren nie `type` überschreiben** (Editoren bearbeiten TEXT/LIST/TODO/CALENDAR-Knoten).
- **`CalendarEntryEditor` bewusst stabil halten** — Termin-Formular & `EventCodec`-Pfad additiv lassen;
  nur den Anhänge-Block unten ergänzen.
- **`ui/Attachments.kt`, `ui/AttachmentPicker.kt`, `ui/Markdown.kt` nicht ändern** — nur nutzen.
- **Zoom (Punkt 6):** nur `.clipToBounds()` ergänzen; Gesten-Blöcke & Zoom-Mathe unverändert.
- **F101 (`F10123070010615`) NIE flashen** — Referenzgerät mit alter App-Version.
- UI darf restriktiver sein als das Backend (`KindRules`), aber hier keine neuen Kind-Regeln nötig.
- Löschen bleibt `deleted`-Flag; Tonnen-Löschen fragt bewusst nicht nach.

---

## 6. Test- / Verifikationsbefehle

**JDK immer pro Aufruf pinnen (Arbeitsrechner — nie global setzen):**

```bash
# Unit-Tests (reine JVM, ~140 Stück):
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest

# Debug-APK bauen:
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug
```

- **Punkt 1 (optional Regressionstest):** In `app/src/test/.../core/OrderKeysTest.kt` einen Fall
  ergänzen, der zeigt, dass `OrderKeys.between(seed, null) > seed` **und** `> "8"` (ein kurzer echter
  Key) sortiert — belegt, dass neue Items hinter umsortierte rutschen. (Ein DB-gestützter
  `createNode`-Test ist aufwändiger; die `OrderKeys`-Ebene reicht als Beleg. Falls ein
  Repository-Testharness existiert — s. `app/src/test/.../sync/SyncTest.kt` —, dort einen
  `createNode`-Append-Test ergänzen.)
- **On-Device (Haupttestgerät „Armor 8"):**
  - `adb`: `~/AppData/Local/Android/Sdk/platform-tools/adb.exe` (Gerät `3090RH2001013207` bzw. per
    TCP `192.168.178.1:5555`).
  - Installieren: `adb -s <id> install -r app/build/outputs/apk/debug/app-debug.apk`
  - **UI-Automatisierung:** `testTags` erscheinen als `resource-id` im `uiautomator dump`
    (Konvention `ui/TestTags.kt`: `row:<titel>`, `fab:add`, `topbar:*` …). In Dialogen fehlen IDs →
    per `text="…"` matchen.
  - **Screenshots vor dem Auswerten auf 1/3 der Kantenlänge verkleinern** (Pillow, Farbe behalten) —
    spart Tokens; **Tap-Koordinaten wieder ×3 zurückrechnen**.
- **Manuelle Prüfungen je Punkt:**
  1. Neues Item (Notiz/Liste/…) anlegen → erscheint **unten**. In einer zuvor per Drag umsortierten
     Liste gegenprüfen.
  2. In eine Unterliste navigieren, Gerät drehen → **bleibt** in der Unterliste (nicht Root). Danach
     zurückdrehen, mehrere Ebenen tief testen.
  3. Termin öffnen → unten Anhänge-Bereich; Bild/Datei anhängen → erscheint, öffnet in Detailansicht,
     löschbar. **Neuen** Termin anlegen → Hinweis „nach dem Speichern" statt Anhänge-Buttons.
  4. Listenzeile mit mehreren Bildern → Streifen ragt **nicht** über die Bildschirmmitte; im Querformat
     mehr Bilder sichtbar.
  5. Ans Listenende scrollen → letzter Eintrag lässt sich **über** den FAB schieben.
  6. Bild-Anhang mit Titel+Markdown öffnen → **stark reinzoomen**, dann **rauszoomen** — muss wieder
     funktionieren. Auch Doppeltipp (Reset) prüfen.

---

## 7. Offene Risiken

- **Punkt 1:** `createNode` liest jetzt vor dem Schreiben `children(parentId)` (zusätzliche Query pro
  Anlage) — für die App-Größe unkritisch. Zwei Geräte, die zeitgleich ans Ende anlegen, können
  denselben/kollidierende `orderKey` erzeugen → das ist der normale orderKey-Konflikt (Last-Writer-Wins
  / Sekundärsortierung nach HLC), **kein** manueller Merge. Akzeptiert.
- **Punkt 2 (Manifest-Weg):** `configChanges` verhindert Activity-Neustart — falls irgendwo auf einen
  Neustart bei Rotation gebaut wird (unwahrscheinlich in dieser App), müsste man das prüfen. Der
  Fallback (`rememberSaveable` der IDs) ist verhaltensnäher, aber code-intensiver und muss `getNode ==
  null` sauber abfangen. **Manifest-Datei evtl. schreibgeschützt** → ggf. Nutzer einbinden.
- **Punkt 3:** `CalendarEntryEditor`-Signatur ändert sich evtl. (neuer `blobStore`-Parameter) → den
  Aufrufer in `ListScreen.kt` mit anpassen, sonst Compile-Fehler. Der `AttachmentDetailScreen`-Aufruf
  muss exakt der bestehenden Signatur entsprechen (Vorlage aus Todo/Post kopieren). Neuer, ungespeicherter
  Termin hat keine nodeId → Anhänge erst nach Speichern (bewusst so).
- **Punkt 6:** Falls sich nach `.clipToBounds()` das Rauszoomen **immer noch** nicht wie erwartet
  verhält, ist die Zweitursache eine echte Gesten-Konkurrenz zwischen dem scrollbaren Nachbarn und
  `detectTransformGestures`. Dann Rückfrage/On-Device-Debug — nicht ins Blaue mehrere Gesten-Umbauten
  stapeln. Der Clip-Fix ist die risikoärmste, sehr wahrscheinlich ausreichende Maßnahme.
- **Allgemein:** R8/minify ist auch im Debug aktiv — nach Änderungen immer `assembleDebug` laufen
  lassen, nicht nur Tests.
