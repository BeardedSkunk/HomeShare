# PLAN — Listenansicht: Render-Kopf wie Notiz, Zeilen-Vereinheitlichung, Bildstreifen, Zoom-Bug

> Arbeitsauftrag für eine neue Claude-Code-Session. **Vor jeder Code-Navigation/-Änderung Serena
> benutzen** (`get_symbols_overview`, `find_symbol include_body`, `replace_symbol_body`,
> `replace_content`) — nicht mit Read/Edit „blind“ arbeiten. Repo ist **LF**. UI-Strings & Kommentare
> **auf Deutsch**, Kommentarstil des Umfelds übernehmen. Commits pro fertigem Chunk als
> `[ui] …`, **nicht pushen** ohne Auftrag.

Alle Pfade relativ zu `D:\AndroidProjekte\ClipSharing`. Paket:
`app/src/main/kotlin/de/beardedskunk/homeshare/…`. Zeilennummern sind Stand der Analyse und
verschieben sich beim Editieren — immer per Serena-Symbolsuche neu verorten, nicht auf Zahlen
verlassen.

---

## 1. Ziel der Änderung

Der letzte Umbau der **Listen-Ansicht** (`ListScreen.kt`) ist unfertig. Fünf Punkte sind umzusetzen:

- **A) Listen-Kopf als Notiz-Render statt grauer Box.** Jede Liste hat (wie eine Notiz) einen
  TEXT-Knoten mit Titel (1. Zeile) + Markdown-Body. Oben in der Liste soll **exakt die
  Render-Ansicht einer Einzelnotiz** stehen (gleiche Schriftgröße/Fonts, möglichst gleicher Code),
  **ohne** die derzeitige graue `Card`. Der Body ist **anfangs eingeklappt**; am Ende der Titelzeile
  sitzt ein **Ausklapp-Chevron** (`v` = ausklappen, `^` = einklappen), das den gerenderten Body
  ein-/ausblendet. **Gibt es keinen Body, erscheint kein Chevron.** Frisch beim Betreten der Liste:
  eingeklappt.
- **B) Top-Bar der Liste umbauen.** Zwischen Zurück-Pfeil und Lupe steht **kein Titeltext** mehr
  (der Titel steht jetzt eine Zeile tiefer im Render-Kopf, wie bei der Notiz). Rechts in der Top-Bar,
  in dieser Reihenfolge: **Lupe (Suche) → QR-Code → Hamburger-Menü → Haken/Stift-Combi (✓/✎)**.
  Das Hamburger-Popup enthält als **ersten Eintrag „Liste löschen“**; bei **Kalender-Listen** darunter
  ein **Boolean-Toggle** „mit Android-Kalender synchronisieren“.
- **C) Listen-Items vereinheitlichen.** Notiz-, Einzelbild- und Bilderlisten-Items sollen **gleich groß**
  sein, **gleiche Textgröße/Font** haben und **Bilder auf voller Zeilenhöhe** zeigen (so wie das
  Notiz-Item es heute schon macht). Referenz = **Notiz-Item** (heute etwas niedriger und mit
  nicht-fettem Titel; die anderen sind durch ihren grauen Bereich leicht höher und fett).
- **D) Bildstreifen in Items responsiv + ausblendend.** Statt fix „max. 3 Thumbnails“: **so viele
  Bilder wie hinpassen**, aber der Streifen darf **nie in die linke Bildschirmhälfte** ragen (Querformat
  = mehr Platz = mehr Bilder). Ist man mitten im Streifen (links/rechts noch mehr Bilder außerhalb),
  soll der jeweilige Rand **weich transparent ausblenden**; nur am **allerersten** Bild ist der linke
  Rand scharf, am **allerletzten** der rechte.
- **E) Pinch-Zoom-Bug.** Öffnet man in einer Notiz ein Bild und zoomt per Pinch, kann man
  irgendwann **nicht mehr rauszoomen** — tritt gefühlt erst nach maximalem Reinzoomen auf.

---

## 2. Betroffene Dateien

| Datei | Betrifft Punkt | Was |
|---|---|---|
| `ui/ListScreen.kt` | A, B, C, D | Top-Bar, `ListHeader`, `PostRow`, `NodeRow`, `TodoRow`, `RowImageStrip`; neuer gemeinsamer Bildstreifen + Fading-Modifier |
| `ui/AttachmentDetailScreen.kt` | E | Pinch-Zoom-Block (Bild in Anhang-Detail = der „Bild in Notiz öffnen“-Pfad) |
| `ui/ImageViewerScreen.kt` | E | identischer Pinch-Zoom-Block (Vollbild-Viewer) |
| `ui/PostDetailEditor.kt` | A (nur **lesen** als Referenz!) | `RenderedView` = Vorbild für den Render-Kopf; **nicht ändern** |
| `ui/Markdown.kt` | A (nur nutzen) | `postTitle`, `postBody`, `MarkdownBody` wiederverwenden; **nicht ändern** |
| `ui/TestTags.kt` | – | `.tag()`-Extension und `rowTag()` bereits vorhanden; nur nutzen |

Keine Änderungen an Backend/`core/`/`data/` nötig. `settings.isCalendarFeedEnabled` /
`setCalendarFeedEnabled`, `repo.editNode`, `repo.deleteNode`, `repo.reorderNode` existieren bereits.

---

## 3. Relevante Erkenntnisse aus der Analyse

### 3.1 Datenmodell / vorhandene Helfer
- `postTitle(text)` = 1. Zeile (markup-frei). `postBody(text)` = ab Zeile 2. `parseMarkdownBody(text)`
  parst **nur den Body ab Zeile 2** (in `Markdown.kt`).
- **`MarkdownBody(text, modifier, onToggleTask, onEditAt, highlight)`** (in `Markdown.kt`) rendert den
  Body (ohne Titel), Stil `bodyLarge`. **Das ist der gemeinsame Renderer** — genau ihn nutzt auch die
  Notiz-Referenz.
- Die **Notiz-Render-Referenz** ist `AttachmentDetailScreen`s Render-Zweig und `RenderedView`
  (`PostDetailEditor.kt`): Titel = `Text(title, style = MaterialTheme.typography.headlineSmall,
  fontWeight = FontWeight.Medium)`, darunter der Body. **Genau dieses Titel-Styling für den
  Listen-Kopf übernehmen** (nicht das jetzige `titleMedium` aus `ListHeader`).

### 3.2 Aktueller Listen-Kopf `ListHeader` (in `ListScreen.kt`, ~Z. 778–832)
- Steckt in einer `Card` (= die graue Box, die weg soll).
- Hat **selbst** einen ✎/✓-Toggle und ein Chevron. Titel: `titleMedium` (falsch → soll `headlineSmall`).
- Body im Ausklapp-Fall: rendert `parseMarkdownBody` per `MdBlockView`-Schleife (funktioniert, aber wir
  ersetzen das durch den einfacheren `MarkdownBody`-Aufruf, damit es 1:1 wie die Notiz ist).
- Wird in `ListScreen` aufgerufen (`if (container != null) { ListHeader(container, readOnly=!canWrite,
  onSave = { newText -> … editNode … }) }`).
- **Umbau:** Der ✎/✓-Toggle **wandert in die Top-Bar** (Punkt B). Der Edit-/Ausklapp-Zustand muss
  daher **in `ListScreen` hochgezogen** werden (State-Hoisting), damit die Top-Bar ihn steuert.

### 3.3 Aktuelle Top-Bar von `ListScreen` (im `Scaffold { topBar = { TopAppBar(...) } }`)
- `title`: bei Suche = Suchfeld; bei Root = `Text("Feeds")`; sonst = `Text(container.title)` ← **dieser
  Container-Titel soll weg** (nur noch leer).
- `actions` heute: Suche-Icon → (Overflow `MoreVert`, nur Kalender-Sync als einziges Item) → QR →
  (Settings nur Root). **Reihenfolge/Inhalt gemäß Punkt B neu.**
- Es gibt bereits: `overflowOpen`, `calSyncConfirm`, `calEnabled`, `isCalendar`, `onRequestCalendarSync`,
  `settings`. Der bestehende Kalender-Confirm-Dialog (`calSyncConfirm`) kann bleiben ODER durch einen
  direkten Toggle ersetzt werden (siehe B).

### 3.4 Item-Zeilen (in `ListScreen.kt`)
- **`PostRow`** (NOTE, ~Z. 723–761): `rowHeight = 56.dp`, `Row(...).height(rowHeight)`, **kein**
  Leading-Icon, Titel-`Text` **ohne** `fontWeight` (= normal), Bildstreifen `RowImageStrip(hashes,
  blobStore, rowHeight, …)` → **Bilder schon auf voller Höhe (56 dp)**. Das ist die Referenz.
- **`NodeRow`** (LIST/IMAGE/FILE, ~Z. 606–660): Höhe über `padding(start=14, top=14, bottom=14)`
  (→ leicht höher/uneinheitlich), Titel **`fontWeight = FontWeight.Medium`** (= fett), Bildstreifen mit
  **`40.dp`** (→ nicht volle Höhe). Leading-Icon nur für LIST/FILE.
- **`TodoRow`** (~Z. 673–700): Checkbox + Titel `FontWeight.Medium`, Höhe über `padding(top/bottom=4)`
  + Checkbox-Eigenhöhe.
- **`TaskBadge`** (~Z. 662–671): „✓ x/y“-Chip, unverändert lassen.
- **`CalendarRow`** liegt in `ui/CalendarEntryEditor.kt` (mehrzeilig: Datum/Ort/Wiederholung). **Nicht
  Teil des Vereinheitlichungs-Auftrags — unangetastet lassen.**
- **`RowImageStrip`** (~Z. 763–776): `LazyRow(Modifier.widthIn(max = cellSize*3).height(cellSize))`,
  quadratische Thumbnails, `ContentScale.Crop`. **Wird komplett ersetzt** (Punkt D).
- `rememberBlobBitmap(blobStore, sha, preferFull=false)` liefert das Thumbnail.

### 3.5 Zoom-Bug (Punkt E)
- **Beide** Screens nutzen identischen Code:
  ```kotlin
  .pointerInput(key) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero },
                                         onLongPress = { menuOpen = true } /* nur AttachmentDetail */) }
  .pointerInput(key) { detectTransformGestures { _, pan, zoom, _ ->
      scale = (scale * zoom).coerceIn(1f, 6f)
      offset = if (scale > 1f) offset + pan else Offset.Zero
  } }
  ```
- **Der „Bild in Notiz öffnen“-Pfad ist `AttachmentDetailScreen`** (ein Bild-Anhang öffnet diese
  Detailansicht). Dort gibt es zusätzlich `onLongPress = { menuOpen = true }`.
- **Wahrscheinlichste Ursache:** Am Maximal-Zoom hört man auf, die Finger zu spreizen (keine Bewegung
  mehr), hält sie aber aufgelegt → der **Long-Press-Timer feuert** → `menuOpen = true` → das
  DropdownMenu/dessen Scrim fängt die folgenden Gesten ab → **Rauszoomen unmöglich**. Passt exakt zu
  „tritt erst nach maximalem Reinzoomen auf“. (Die reine Scale-Mathematik `scale*zoom` ist korrekt;
  Rauszoomen liefert `zoom<1` und würde `scale` normal senken.)
- Zusatzursache/Absicherung: die zwei getrennten `pointerInput`-Blöcke konkurrieren; ein Long-Press bei
  Multitouch soll gar nicht auslösen.

---

## 4. Konkrete Umsetzungsschritte

> Reihenfolge-Empfehlung: **E (klein, isoliert) → C → D → A → B**. A und B hängen zusammen
> (State-Hoisting des Kopf-Edit-Zustands in die Top-Bar) und sind am invasivsten.

### Punkt E — Zoom-Bug (zuerst, klein & isoliert)

In **`AttachmentDetailScreen.kt`** (Bild-Zweig, der `Box` mit den beiden `pointerInput`) und in
**`ImageViewerScreen.kt`** (der `Box` mit den beiden `pointerInput`):

1. Einen Pointer-Zähler ergänzen und Long-Press nur bei ≤1 Finger zulassen. Konkret den Bild-`Box`
   so umbauen (Serena `replace_content` auf den `.pointerInput`-Kette-Block):
   ```kotlin
   var pointerCount by remember(key) { mutableStateOf(0) }
   // …
   Box(
       Modifier
           .fillMaxSize() // bzw. fillMaxWidth().weight(1f) wie gehabt
           .pointerInput(key) {
               awaitPointerEventScope {
                   while (true) {
                       val ev = awaitPointerEvent()
                       pointerCount = ev.changes.count { it.pressed }
                   }
               }
           }
           .pointerInput(key) {
               detectTapGestures(
                   onDoubleTap = { scale = 1f; offset = Offset.Zero },
                   onLongPress = { if (pointerCount <= 1) menuOpen = true }, // nur AttachmentDetail
               )
           }
           .pointerInput(key) {
               detectTransformGestures { _, pan, zoom, _ ->
                   scale = (scale * zoom).coerceIn(1f, 6f)
                   offset = if (scale > 1f) offset + pan else Offset.Zero
               }
           },
       // …
   )
   ```
   - `key` = das bereits genutzte `sha` bzw. `att.blobHash`.
   - In `ImageViewerScreen` gibt es kein `onLongPress`/`menuOpen` — dort genügt der Pointer-Zähler
     nicht zwingend; trotzdem denselben robusten Aufbau spendieren (schadet nicht). Der eigentliche
     Fix wirkt in `AttachmentDetailScreen`.
2. Imports sicherstellen: `androidx.compose.ui.input.pointer.pointerInput`,
   `androidx.compose.foundation.gestures.awaitEachGesture`/`awaitPointerEventScope` (schon via
   `pointerInput`-Scope verfügbar).
3. **On-Device verifizieren** (siehe §6): maximal reinzoomen, Finger kurz still halten, dann
   rauszoomen — muss jetzt gehen; Long-Press mit **einem** Finger muss weiterhin das Teilen/
   Bearbeiten-Menü öffnen.

> Falls der Bug nach diesem Fix *doch* bleibt, als nächste Hypothese die Scale-/Offset-Logik in **eine**
> `detectTransformGestures`-Kette zusammenführen und `offset` an die Bildgrenzen klammern. Erst nach
> Gerätetest entscheiden.

### Punkt C — Item-Zeilen vereinheitlichen

Ziel: Notiz-, Bild-, Bilderlisten- (und Aufgaben-)Items **gleiche Höhe, gleiche Titel-Typografie,
Bilder auf voller Zeilenhöhe**. Referenz = `PostRow` (Notiz).

1. In `ListScreen.kt` eine gemeinsame Höhe definieren (oben bei den anderen top-level Helfern):
   ```kotlin
   private val ROW_HEIGHT = 56.dp
   ```
2. **`NodeRow`** umbauen:
   - Die äußere `Row` bekommt **feste Höhe** statt top/bottom-Padding:
     `Row(Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(start = 14.dp), verticalAlignment = CenterVertically)`.
   - Titel-`Text`: **`fontWeight` entfernen** (auf Notiz-Stil = normal), Rest (maxLines=1, Ellipsis)
     bleibt. So sind alle Titel gleich „dick“ wie beim Notiz-Item.
   - Bildstreifen-Aufruf: `RowImageStrip(imageHashes, blobStore, ROW_HEIGHT, onOpenImage)` (statt
     `40.dp`) → Bilder auf voller Höhe.
   - Leading-Icon (LIST/FILE) bleibt.
3. **`PostRow`** angleichen: `rowHeight`-lokale Konstante durch `ROW_HEIGHT` ersetzen; Aufruf
   `RowImageStrip(imageHashes, blobStore, ROW_HEIGHT, onOpenImage)`. Titel bleibt ohne `fontWeight`.
4. **`TodoRow`** angleichen: äußere `Row` auf `.height(ROW_HEIGHT)`; Titel-`fontWeight` entfernen
   (Konsistenz), `textDecoration` (LineThrough bei done) bleibt. Checkbox bleibt.
5. Sichtprüfung: alle Zeilen gleich hoch, Titel gleich, Bilder füllen die Zeilenhöhe.

> Entscheidung getroffen: **Titel überall normal-gewichtet (Notiz-Referenz)**. Falls dem Nutzer die
> Listen-/Aufgaben-Titel dadurch zu dünn wirken, ist das ein Einzeiler-Rückbau (`fontWeight` wieder
> setzen) — vor dem Rückbau aber nachfragen.

### Punkt D — Responsiver, ausblendender Bildstreifen

`RowImageStrip` in `ListScreen.kt` **komplett ersetzen**:

```kotlin
/**
 * Horizontaler Bildstreifen für Item-Zeilen: quadratische Thumbnails auf voller Zeilenhöhe
 * ([cellSize]). Breite ist auf die RECHTE Bildschirmhälfte begrenzt (nie in die linke Hälfte).
 * Ränder blenden weich aus, solange in die jeweilige Richtung weitergescrollt werden kann;
 * am ersten Bild ist der linke Rand scharf, am letzten der rechte.
 */
@Composable
private fun RowImageStrip(
    imageHashes: List<String>,
    blobStore: BlobStore,
    cellSize: androidx.compose.ui.unit.Dp,
    onOpenImage: (String) -> Unit,
) {
    if (imageHashes.isEmpty()) return
    val config = LocalConfiguration.current
    // Nie in die linke Bildschirmhälfte ragen: max. halbe Bildschirmbreite.
    val maxStripWidth = (config.screenWidthDp / 2).dp
    val listState = rememberLazyListState()
    val fadeLeft by remember { derivedStateOf { listState.canScrollBackward } }
    val fadeRight by remember { derivedStateOf { listState.canScrollForward } }
    LazyRow(
        state = listState,
        modifier = Modifier
            .widthIn(max = maxStripWidth)
            .height(cellSize)
            .horizontalFadingEdges(fadeLeft, fadeRight),
    ) {
        items(imageHashes) { sha ->
            val bmp = rememberBlobBitmap(blobStore, sha, preferFull = false)
            Box(Modifier.size(cellSize).clickable { onOpenImage(sha) }, contentAlignment = Alignment.Center) {
                if (bmp != null) Image(bitmap = bmp, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text("🖼")
            }
        }
    }
}
```

Und den Fading-Modifier als **top-level** Funktion in `ListScreen.kt` ergänzen:

```kotlin
/** Weicher Rand-Ausblend links/rechts (nur wenn in die Richtung weiter gescrollt werden kann). */
private fun Modifier.horizontalFadingEdges(fadeLeft: Boolean, fadeRight: Boolean, fadeWidth: androidx.compose.ui.unit.Dp = 20.dp): Modifier =
    this
        .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fw = fadeWidth.toPx()
            if (fadeLeft) {
                drawRect(
                    brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fw),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (fadeRight) {
                drawRect(
                    brush = Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fw, endX = size.width),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
```

Nötige Imports (per `insert_after_symbol` am Import-Block bzw. Serena-Edit ergänzen, falls nicht schon da):
- `androidx.compose.ui.platform.LocalConfiguration`
- `androidx.compose.runtime.derivedStateOf`
- `androidx.compose.foundation.lazy.rememberLazyListState`
- `androidx.compose.ui.draw.drawWithContent`
- `androidx.compose.ui.graphics.graphicsLayer` (bzw. `androidx.compose.ui.graphics.CompositingStrategy`)
- `androidx.compose.ui.graphics.Brush`, `…graphics.Color`, `…graphics.BlendMode`

Hinweise:
- `CompositingStrategy.Offscreen` ist zwingend, sonst wirkt `BlendMode.DstIn` nicht.
- Die alte Grenze „`cellSize * 3`“ und „max. 3 sichtbar“ **entfällt** — Anzahl ergibt sich aus
  `maxStripWidth / cellSize`, im Querformat automatisch mehr.
- Der Streifen sitzt in der Zeile **rechts** (nach dem `weight(1f)`-Titel), dadurch ist seine
  linke Kante ≥ Bildschirmmitte → Anforderung „nie in linke Hälfte“ erfüllt.

### Punkt A — Listen-Kopf als Notiz-Render (ohne graue Box, Chevron, eingeklappt)

**Zustand nach `ListScreen` hochziehen** (weil die Top-Bar in B den ✎/✓-Toggle bekommt). In
`ListScreen` (bei den anderen `remember`-States) ergänzen:

```kotlin
// Render-Kopf der Liste (wie Notiz): Edit-/Ausklapp-Zustand hier gehalten, Toggle sitzt in der Top-Bar.
var headerSource by remember(container?.nodeId) { mutableStateOf(false) }   // false = gerendert, true = Quelltext
var headerExpanded by remember(container?.nodeId) { mutableStateOf(false) } // Body eingeklappt starten
var headerText by remember(container?.nodeId, container?.text) { mutableStateOf(container?.text ?: "") }

fun saveHeader() {
    val t = headerText
    val id = container?.nodeId ?: return
    scope.launch { withContext(Dispatchers.IO) { repo.headContent(id)?.let { repo.editNode(id, it.copy(text = t)) } } }
    headerSource = false
    headerExpanded = false
}
```

`ListHeader` **neu schreiben** (State wird von außen reingereicht; keine eigene Card, kein eigener
Toggle-Button; Chevron bleibt im Kopf):

```kotlin
/**
 * Render-Kopf der aktuellen Liste — sieht aus wie die Render-Ansicht einer Notiz (kein grauer Kasten):
 * Titel (headlineSmall) + optionales Ausklapp-Chevron; ausgeklappt der gerenderte Markdown-Body.
 * Im Quelltext-Modus (vom Top-Bar-Toggle gesteuert) eine gemeinsame Editbox (Titel + Body).
 */
@Composable
private fun ListHeader(
    container: NodeState,
    sourceMode: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    editText: String,
    onEditTextChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        if (sourceMode) {
            OutlinedTextField(
                value = editText,
                onValueChange = onEditTextChange,
                placeholder = { Text("Titel (1. Zeile), dann Markdown…") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().tag("field:listbody"),
            )
        } else {
            val title = postTitle(container.text)
            val hasBody = postBody(container.text).isNotBlank()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title.ifBlank { "(ohne Namen)" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).tag("header:title"),
                )
                if (hasBody) {
                    IconButton(onClick = { onExpandedChange(!expanded) }, modifier = Modifier.tag("header:expand")) {
                        Icon(
                            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (expanded) "Einklappen" else "Ausklappen",
                        )
                    }
                }
            }
            if (expanded && hasBody) {
                MarkdownBody(text = container.text, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }
}
```

Aufruf in `ListScreen` (ersetzt den bestehenden `ListHeader(...)`-Aufruf im `Column`):

```kotlin
if (container != null) {
    ListHeader(
        container = container,
        sourceMode = headerSource,
        expanded = headerExpanded,
        onExpandedChange = { headerExpanded = it },
        editText = headerText,
        onEditTextChange = { headerText = it },
    )
}
```

Wichtig:
- **Kein `Card`** mehr → keine graue Box (Anforderung).
- Titel-Styling **identisch zur Notiz** (`headlineSmall` + `FontWeight.Medium`), Body über
  `MarkdownBody` (= derselbe Renderer/Fonts wie Notiz).
- `expanded` startet `false` (eingeklappt).
- Chevron **nur** wenn Body vorhanden.

### Punkt B — Top-Bar umbauen

Im `TopAppBar` von `ListScreen`:

1. **`title`**: Container-Titel entfernen. Nur noch:
   ```kotlin
   title = {
       if (searching) { /* bestehendes Suchfeld unverändert */ }
       else if (isRoot) { Text("Feeds") }
       // sonst: nichts (kein Container-Titel mehr)
   }
   ```
2. **`actions`** (Reihenfolge = Anzeige links→rechts). Für Root bleibt es weitgehend wie gehabt
   (Suche, QR/Beitreten, Settings). Für **Nicht-Root** (`container != null`, `!searching`):
   ```
   [Lupe/Suche]  [QR]  [Hamburger ⋮ mit Popup]  [✓/✎]
   ```
   - **Lupe**: bestehender Such-Toggle-IconButton (`topbar:search`), unverändert.
   - **QR**: bestehender Share-IconButton (`topbar:share`), unverändert — aber in der Reihenfolge
     **vor** das Hamburger-Menü ziehen.
   - **Hamburger** (`Icons.Filled.MoreVert`, `topbar:overflow`) — `DropdownMenu` mit:
     1. `DropdownMenuItem("Liste löschen", tag "menu:delete-list")` → öffnet Bestätigungsdialog
        (neuer State `var deleteListConfirm by remember { mutableStateOf(false) }`), bei Bestätigung
        `repo.deleteNode(container.nodeId)` dann `onBack()`.
     2. **nur wenn `isCalendar`**: ein Eintrag mit **Switch** als `trailingIcon`, der direkt toggelt:
        ```kotlin
        DropdownMenuItem(
            text = { Text("Mit Android-Kalender synchronisieren") },
            trailingIcon = {
                Switch(checked = calEnabled, onCheckedChange = null) // Klick wird vom Item-onClick behandelt
            },
            onClick = {
                val newState = !calEnabled
                calEnabled = newState
                settings.setCalendarFeedEnabled(parentId, newState)
                onRequestCalendarSync()
                // overflowOpen bewusst offen lassen ODER schließen — Entscheidung: schließen
                overflowOpen = false
            },
            modifier = Modifier.tag("menu:calendar-sync"),
        )
        ```
        (Der bisherige `calSyncConfirm`-Dialog wird damit **überflüssig** — entfernen, wenn der direkte
        Toggle gewünscht ist. Der Nutzer hat explizit „Boolean-Toggle“ gesagt.)
   - **✓/✎** (Kopf-Edit-Toggle, nur wenn `canWrite`): **aus `PostDetailEditor` übernommenes Muster**:
     ```kotlin
     IconButton(
         onClick = { if (headerSource) saveHeader() else headerSource = true },
         modifier = Modifier.tag(if (headerSource) "topbar:save" else "topbar:edit"),
     ) {
         if (headerSource) Icon(Icons.Filled.Edit, contentDescription = "Speichern & anzeigen")
         else Icon(Icons.Filled.Check, contentDescription = "Bearbeiten", tint = Color(0xFF2E7D32), modifier = Modifier.size(30.dp))
     }
     ```
   - **Settings-Icon** nur Root (unverändert).
3. **Löschen-Bestätigungsdialog** (bei den anderen Dialogen am Ende von `ListScreen`):
   ```kotlin
   if (deleteListConfirm) {
       AlertDialog(
           onDismissRequest = { deleteListConfirm = false },
           title = { Text("Liste löschen?") },
           text = { Text("Die Liste und ihr Inhalt werden entfernt.") },
           confirmButton = {
               TextButton(onClick = {
                   deleteListConfirm = false
                   val id = container?.nodeId
                   if (id != null) scope.launch { withContext(Dispatchers.IO) { repo.deleteNode(id) }; onBack() }
               }) { Text("Löschen") }
           },
           dismissButton = { TextButton(onClick = { deleteListConfirm = false }) { Text("Abbrechen") } },
       )
   }
   ```

> Hinweis: „Liste löschen“ mit Bestätigung ist bewusst vom Tonnen-Sofortlöschen abgegrenzt (man löscht
> hier den Container, in dem man gerade steht, und navigiert weg). Falls der Nutzer lieber ohne
> Rückfrage will, ist der Dialog leicht entfernbar.

---

## 5. Wichtige Constraints / NICHT ändern

- **`type`/Knotentyp beim Editieren nie überschreiben** — der Kopf-Save nutzt `headContent(id).copy(text=…)`
  (Typ bleibt). Genau so lassen.
- **`PostDetailEditor.kt`, `Markdown.kt`, `CalendarEntryEditor.kt` (inkl. `CalendarRow`) nicht ändern** —
  nur lesen/wiederverwenden. `CalendarEntryEditor` ist ausdrücklich „bewusst stabil“.
- **Root-Ebene** (`container == null`, „Feeds“) behält Titel „Feeds“, Settings-Icon und **keinen**
  Render-Kopf/✎-Toggle. Der Umbau betrifft **Nicht-Root-Listen**.
- **Foreign/Read-Only** (`canWrite == false`): kein ✎/✎-Toggle, kein „Liste löschen“, keine Editbox
  anbieten (Header nur gerendert). Bestehende `canWrite`/`canMerge`-Logik respektieren.
- Bestehende **testTags** beibehalten/konsistent erweitern (`ui/TestTags.kt`-Konvention:
  `topbar:*`, `menu:*`, `header:*`, `field:*`, `row:*`). Neue Tags: `menu:delete-list`,
  `topbar:edit`/`topbar:save`, `header:expand`, `header:title`.
- **Kein Nav-Framework** — Navigation bleibt modal-geschachtelt (State + `return`), `onBack()` nutzen.
- **Drag-Sortierung, Swipe→Tonne, Suche** in der Liste dürfen nicht kaputtgehen (der `LazyColumn`-Teil
  bleibt unverändert; nur Kopf/Top-Bar/Row-Styling ändern).
- **Neue Logik testbar halten** (reine Funktionen nach `core/` bzw. als pure Funktion) — hier
  überwiegend UI, daher v. a. On-Device-Verifikation. Bestehende ~140 JVM-Tests dürfen nicht brechen.
- Repo ist **LF**; deutsche Kommentare/Strings.

---

## 6. Test- / Verifikationsbefehle

**JDK immer pro Aufruf pinnen (Arbeitsrechner — nie global setzen):**

```bash
# Unit-Tests (müssen grün bleiben):
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest

# Debug-APK bauen:
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug
```

**Auf Gerät (Armor 8, `3090RH2001013207` — Haupttestgerät; F101 `F10123070010615` NIE flashen):**
`adb` liegt unter `~/AppData/Local/Android/Sdk/platform-tools/adb.exe`.

Für Screenshots die **mobile-MCP-Tools** nutzen und **vor dem Auswerten auf 1/3 verkleinern** (Pillow),
Farbe behalten; Tap-Koordinaten aus dem verkleinerten Bild ×3 zurückrechnen:

```bash
python -c "from PIL import Image; im=Image.open('shot.png'); w,h=im.size; im.resize((w//3,h//3)).save('shot_small.png')"
```

**Manuelle Verifikations-Checkliste:**
- E: Bild in einer Notiz öffnen → maximal reinzoomen → Finger kurz still halten → rauszoomen geht.
  Long-Press mit **einem** Finger öffnet weiterhin Teilen/Bearbeiten.
- C: Notiz-, Einzelbild-, Bilderlisten-Item nebeneinander → gleiche Höhe, gleiche Titel-Typografie,
  Bilder füllen die Zeilenhöhe.
- D: Bilderliste mit vielen Bildern → Streifen reicht nicht in die linke Hälfte; im Hochformat weniger,
  im Querformat mehr Bilder; Ränder blenden weich aus, außer am ersten (links) / letzten (rechts) Bild.
- A: Liste öffnen → Kopf sieht aus wie Notiz-Render (kein grauer Kasten), Body eingeklappt; Chevron nur
  bei vorhandenem Body; `v`/`^` klappt den gerenderten Body ein/aus; kein Container-Titel in der Top-Bar.
- B: Top-Bar-Reihenfolge Lupe → QR → Hamburger → ✓/✎. ✎ öffnet Editbox (Titel+Body), ✓ speichert &
  zeigt gerendert. Hamburger: „Liste löschen“ (mit Bestätigung, navigiert zurück); bei Kalender-Liste
  darunter der Sync-Toggle.

---

## 7. Offene Risiken / Fallstricke

- **Zoom-Bug (E):** Die Long-Press-Menü-Hypothese ist die wahrscheinlichste, aber nicht 100 % bewiesen.
  **Erst On-Device gegenprüfen.** Falls es bleibt: Gesten in **eine** `detectTransformGestures`-Kette
  zusammenführen und `offset` an Bildgrenzen klammern (Fallback-Plan in §4/E).
- **Fading-Edges:** `BlendMode.DstIn` wirkt nur mit `CompositingStrategy.Offscreen` — sonst blendet
  nichts aus (dann fehlt der Import/die Zeile). Kurz visuell prüfen.
- **`config.screenWidthDp / 2`:** Integer-Division ist ok (dp-Genauigkeit reicht). Bei sehr schmalen
  Geräten könnte der Streifen wenige Bilder zeigen — gewollt (nie in linke Hälfte).
- **State-Hoisting Kopf (A/B):** `headerText` wird an `container.text` re-gekeyt. Beim Speichern erhöht
  `editNode` die Revision → `reload()` → `container` (aus `children` des Elternteils? Nein: `container`
  ist Parameter) — **prüfen, ob `container` nach dem Save aktualisiert reinkommt.** `container` kommt von
  der aufrufenden Ebene (`MainActivity`-Stack). Ggf. aktualisiert sich `container.text` nicht sofort;
  dann zeigt der Kopf evtl. den alten Text bis zum Re-Betreten. Falls das stört: nach `saveHeader()`
  lokal `headerText` als angezeigte Quelle behalten oder `container` neu laden. **On-Device beobachten.**
- **Nur-Lesen-Listen:** sicherstellen, dass ohne `canWrite` weder Toggle noch Editbox noch „Liste
  löschen“ erscheinen.
- **Aufgaben-Item-Höhe (C):** Die `Checkbox` hat eine Mindest-Touchgröße (~48 dp); mit `ROW_HEIGHT=56.dp`
  unkritisch, aber Ausrichtung prüfen.
- **CalendarRow** ist mehrzeilig und bewusst ausgenommen — nicht „mitvereinheitlichen“.
```
