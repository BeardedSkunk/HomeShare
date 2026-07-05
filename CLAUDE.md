# HomeShare — Entwickler-Einstieg für Claude-Sessions

Persönliche Android-App (Kotlin, Compose, minSdk 29): **Verzeichnisbaum aus Listen-in-Listen** mit
Notizen, Aufgaben, Terminen und Anhängen, **LAN-Sync zwischen eigenen Geräten** (kein Cloud/Login),
git-artige Versionierung pro Knoten. Details/Features: `README.md`; Design-Dokumente: `docs/`.

## Bauen, Testen, Deployen

```bash
# JDK NUR pro Aufruf pinnen, nie global (Arbeitsrechner!):
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug
# adb: ~/AppData/Local/Android/Sdk/platform-tools/adb.exe
```

- Toolchain: AGP 9.0.1, Gradle 9.1, Kotlin 2.3.20, JDK 21. R8/minify auch im Debug aktiv.
- Alle Tests sind **reine JVM-Unit-Tests** (`app/src/test/`, ~140). Kein Robolectric/androidTest —
  neue Logik deshalb nach `core/` legen oder als reine Funktion schreiben, damit sie testbar ist.
- **Geräte**: Armor 8 (`3090RH2001013207`) = Haupttestgerät. F101 (`F10123070010615`) darf geflasht
  werden (frühere „nicht flashen"-Sperre am 2026-07-05 aufgehoben). Cubot Max = Android 6, App läuft dort nicht.
- UI-Automatisierung: testTags erscheinen als resource-id im `uiautomator dump`
  (Konvention in `ui/TestTags.kt`: `row:<titel>`, `fab:add`, `topbar:*`, `menu:create:*`,
  `drag:*`, `trash:*`, `box:*`, `field:*`). In Dialog-Fenstern fehlen die IDs → per `text="…"` matchen.
  Screenshots vor dem Auswerten auf 1/3 verkleinern (Pillow), Tap-Koordinaten ×3.

## Datenmodell (der Kern, alles hängt daran)

- **Ein Knotenbaum.** Jeder Knoten = `NodeContent` (core/Model.kt): `parentId`, `type`
  (TEXT/CALENDAR/IMAGE/FILE/TODO), `text` (**1. Zeile = Titel**, Rest Markdown), `orderKey`,
  `done`, `color`, `tags`, `blobHash`/`fileName`/`mime`, offenes Meta-System (`ext`-Map, unbekannte
  Keys überleben Sync mit älteren Versionen; neue Meta-Keys brauchen KEINEN Format-Bump).
- **Nutzersicht `NodeKind`** (data/Domain.kt): LIST und NOTE sind beide TEXT — LIST hat `childDefault`
  gesetzt (= Default-Typ neuer Kinder, bestimmt auch das Icon).
- **Append-only Op-Log** (data/Db.kt): `versionId = SHA-256(Inhalt+Eltern+Gerät+HLC)`; mehrere Heads =
  nebenläufig. Automerge = deterministischer 3-Wege (core/Node.kt#autoMergeContent, ThreeWayMerge);
  nur echte Überlappungen werden manuell. `node_current` ist ein REBUILDBARER Cache.
- **Sortierung**: `orderKey` = fraktionale Hex-Keys (core/OrderKeys.kt); Knoten ohne Key sortieren
  über virtuelle HLC-Seeds (= Erzeugungsreihenfolge). Drag = genau 1 Op (`FeedRepository.reorderNode`).
  orderKey-only-Konflikt → Last-Writer-Wins statt manueller Auflösung.
- **Anhänge**: NOTE/TODO/LIST → IMAGE/FILE-Kind (blobHash → BlobStore, content-adressiert)
  → genau EIN TEXT-Kind = Beschreibungs-Notiz (Titel startet mit Dateinamen).

## UI-Landkarte (`ui/`)

| Datei | Zweck |
|---|---|
| `ListScreen.kt` | Kinder EINES Knotens (oder Wurzel=Feeds): gemischte Zeilen, FAB (Tap=childDefault, Langdruck=Typmenü, Wurzel nur LIST — `KindRules.kt`), Langdruck=Aktionsdialog, Suche, Drag-Sortierung, Swipe→Tonne. Navigation ist **modal geschachtelt** (State + `return`), kein Nav-Framework. |
| `PostDetailEditor.kt` | Notiz/Listen-Beschreibung: gerendert (✓) ↔ Quelltext (✎), Markdown-Toolbar, In-Text-Suche, Zeilen-Drag in gerenderter Ansicht, Anhänge-Kasten, FAB nur Bild+Datei. `showAttachments=false` für Listen-Beschreibungen! |
| `TodoDetailScreen.kt` | Aufgabe: Haken+Titel, Body, separierter Unterpunkte-Kasten (Quick-Add, alle mit Haken), Anhänge, Termine. Kinder sind im Baum flache Geschwister — Gruppierung rein visuell. |
| `AttachmentDetailScreen.kt` | EIN Anhang: Beschreibung (Titel+Markdown) editierbar, darunter fix Bild (Pinch-Zoom, Long-Press Teilen/Bearbeiten) bzw. Datei (Long-Press Teilen/Öffnen). |
| `Attachments.kt` / `AttachmentPicker.kt` | gemeinsamer Anhänge-Kasten + Anlege-/Öffnen-/Teilen-Helfer |
| `ReorderableList.kt` | DragDropState (LazyColumn, Vorschau), ColumnDrag (Kästen), SwipeRevealRow (Tonne: bleibt stehen, Tap löscht sofort, max. 1 offen). Landeplatz = **Mittellinien-Regel**. |
| `Markdown.kt` / `MarkdownEditing.kt` | eigener Renderer (Blocks+Inline, Tipp-zur-Quellstelle-Karte) / reine Text-Transformationen (testbar): flipTaskLine, moveLineTo, deleteLineWithChildren, handleEnter … |
| `CalendarEntryEditor.kt` | Termin-Formular (EventCodec) — bewusst stabil lassen |
| `ConflictScreen/DetailMergeScreen` | manuelle Konfliktauflösung (soll langfristig fast nie nötig sein) |

## Konventionen

- **Deutsch** in UI-Strings und Kommentaren; Kommentarstil des Umfelds übernehmen.
- Serena-MCP für Code-Navigation/-Edits nutzen (token-effizient); Repo ist **LF** (Serena-Config gesetzt).
- Commits: `[bereich] beschreibung` auf Deutsch, pro abgeschlossenem Chunk; **nicht pushen** ohne Auftrag.
- UI darf restriktiver sein als das Backend (KindRules): sinnlose Knoten-Kombinationen weder
  anlegen noch anzeigen. Wurzelebene: nur Listen.
- Beim Editieren NIE `type` überschreiben (Editor bearbeitet TEXT-, LIST- und TODO-Knoten).
- Löschen = `deleted`-Flag (Op-Log behält alles); Tonnen-Löschen fragt bewusst nicht nach.

## Aktueller Stand / offene Baustellen

Branch `feature/list-types`. Fertig: einheitliche Listen-UI, Todo-UI, Anhänge-Redesign +
Detailansicht, versionierte Drag-Sortierung, Swipe-Löschen, testTags. Offen (Auswahl):
Zeilen-Drag ohne Live-Preview; Farben/Tags-UI; Knoten verschieben (moveNode existiert im Backend);
15-MiB-Blob-Limit (docs/large-file-streaming-plan.md); History-Browser; Op-Log-Kompaktierung;
Eviction wieder verdrahten; Web-UI. Cross-Gruppen-Design: docs/cross-group-sharing-design.md.
