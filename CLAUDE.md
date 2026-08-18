# HomeShare — Entwickler-Einstieg für Claude-Sessions

Persönliche Android-App (Kotlin, Compose, minSdk 29): **Verzeichnisbaum aus Listen-in-Listen** mit
Notizen, Aufgaben, Terminen und Anhängen, **LAN-Sync zwischen eigenen Geräten** (kein Cloud/Login),
git-artige Versionierung pro Knoten. Features aus Nutzersicht: `README.md`; Design-Dokumente: `docs/`.

Diese Datei ist eine **Landkarte**, keine zweite Fassung des Codes: wo etwas steht, warum es so
gebaut ist, und was einen sonst in die Falle laufen lässt. Der Code ist dicht kommentiert — er
erklärt das Wie. **Gehen Datei und Code auseinander, gilt der Code**, und die Datei gehört korrigiert.

## Bauen, Testen, Deployen

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug
# adb: ~/AppData/Local/Android/Sdk/platform-tools/adb.exe
```

- **JDK-Pfad ist maschinenspezifisch.** `gradle.properties` trägt ein eingechecktes
  `org.gradle.java.home`; auf manchen Rechnern heißt die Installation „Android Studio**1**".
  Dann pro Aufruf übersteuern (`JAVA_HOME=…` **und** `-Dorg.gradle.java.home=…`), **nie** die
  Datei committen — sie ist auf mehreren Rechnern ausgecheckt.
- Toolchain: AGP 9.0.1, Gradle 9.1.0, Kotlin 2.3.20, JDK 21, compileSdk/targetSdk 36, JVM-Target 17.
  `android.builtInKotlin=false` + `android.newDsl=false` (klassische DSL), `kotlin.incremental=false`
  (Virenscanner sperrt die Caches). R8/minify **auch im Debug** — Debug-APKs werden ausgerollt und
  material-icons-extended muss getreeshaked werden.
- **235 reine JVM-Unit-Tests** (`app/src/test/`), keine androidTest/Robolectric. Neue Logik deshalb
  nach `core/` legen oder als reine Funktion schreiben, sonst ist sie nicht testbar. Faustregel des
  Projekts: alles, was rechnet (Merge, Bänder, RRULE, orderKeys, Markdown-Transformationen), ist
  Android-frei; nur der dünne Ausführungsrand liegt im Repository oder in der UI.

## Geräte & Arbeitsweise am Gerät

- **F101** (`F10123070010615`, `192.168.178.2:5555`) = Standard-Testgerät für alles hier.
- **Pixel 8 Pro** (`45111FDJG0002R`, `192.168.178.3`, Wireless Debugging/TLS) = Alltagsgerät des
  Nutzers → nur fertige Stände. Nicht sichtbar? `adb kill-server && adb start-server`, dann
  erscheint es per mDNS als `adb-45111FDJG0002R-….._adb-tls-connect._tcp` (Port 5555 ist im
  TLS-Modus zu, `adb connect` ist der falsche Weg).
- **Armor 8** (`3090RH2001013207`, `192.168.178.1:5555`) und **Lenovo-Tablet** (`HA14C5M2`,
  `192.168.178.4:5555`) für Zwei-Geräte-Sync-Tests. **Cubot Max** ist Android 6 → App läuft dort nicht.
- **Der Nutzer testet UI-Änderungen selbst.** Nach Build + Install + Start aufhören — keine
  Screenshot-/uiautomator-Runden, außer er bittet ausdrücklich darum.
- Wenn doch geprüft wird: testTags erscheinen als resource-id im `uiautomator dump`
  (Konvention komplett in `ui/TestTags.kt`). **In Dialog-Fenstern fehlen die IDs** (eigene
  Composition ohne das Flag) → dort per `text="…"` matchen. Screenshots vor dem Ansehen auf 1/3
  verkleinern, Tap-Koordinaten ×3 zurückrechnen. `adb input text` kann keine Leerzeichen (`%s`).

## Datenmodell (der Kern, alles hängt daran)

- **Ein Knotenbaum.** Jeder Knoten = `NodeContent` (`core/Model.kt`): `parentId`, `type`
  (TEXT/CALENDAR/IMAGE/FILE/TODO), `text` (**1. Zeile = Titel**, Rest Markdown), `orderKey`,
  `done`, `color`, `tags`, `blobHash`/`fileName`/`mime` — plus offenes Meta-System.
- **Nutzersicht `NodeKind`** (`data/Domain.kt`): LIST und NOTE sind beide TEXT — LIST hat
  `childDefault` gesetzt (= Default-Typ neuer Kinder, bestimmt auch das Icon).
- **Append-only Op-Log** (`data/Db.kt`, Version 8): `versionId = SHA-256(Inhalt+Eltern+Gerät+HLC+fmt)`;
  mehrere Heads = nebenläufig. `node_current` ist ein REBUILDBARER Cache, `node_fts` (FTS4) der Index,
  `root_id` die Feed-Wurzel für den feed-begrenzten Sync. **DB-Upgrades unter Version 7 wipen**
  (bewusst inkompatibel, Geräte re-syncen aus der Gruppe).
- Automerge = deterministischer 3-Wege (`core/Node.kt#autoMergeContent` + `ThreeWayMerge`); nur echte
  Überlappungen werden manuell. Löschen-vs-Edit bleibt immer beim Menschen.
- **Sortierung**: `orderKey` = fraktionale Hex-Keys (`core/OrderKeys.kt`); Knoten ohne Key sortieren
  über virtuelle HLC-Seeds (= Erzeugungsreihenfolge). Drag = genau 1 Op (`FeedRepository.reorderNode`),
  Geschwister werden nie umgeschrieben. orderKey-only-Konflikt → Last-Writer-Wins.
- **Anhänge**: NOTE/TODO/LIST → IMAGE/FILE-Kind (blobHash → BlobStore, content-adressiert)
  → genau EIN TEXT-Kind = Beschreibungs-Notiz (Titel startet mit Dateinamen).
- **Erweiterbarkeit ist die Grundregel**: Meta reist als sortierte Klartext-Map; unbekannte Keys
  überleben Sync und Edit wortwörtlich. **Ein neuer Meta-Key braucht KEINEN Format-Bump.**
  `FORMAT_VERSION` ist nur für echte Kernbrüche da (höheres `fmt` wird gespeichert und
  weitergereicht, aber nicht interpretiert).

### ext-Meta-Keys — das inoffizielle Schema

Alles Optionale hängt hier, nicht am DB-Schema. **Diese Keys dürfen NICHT in `MetaKey.KNOWN`**,
sonst filtert `NodeContent.fromMeta` sie aus `ext` heraus, ohne dass es ein typisiertes Feld gäbe:

| Key | wo | Bedeutung |
|---|---|---|
| `prio` | Aufgabe | Hand-Priorität "1".."3" (`core/Priority.kt`) |
| `prioSort` | Container | "1" = Kinder nach Priorität sortieren |
| `repeat` / `repeatMode` | Aufgabe | RRULE-String + Trigger-Anker `done`\|`due` (`data/TaskRepeat.kt`) |
| `repeatSpawned` | Original | nodeId der erzeugten Kopie = Spawn-Sperre (max. EIN Nachfolger) |
| `repeatOf` | Kopie | nodeId des Originals |
| `restore` | jede Undo/Redo-Op | versionId, aus der restauriert wurde (`data/UndoManager.kt`) |
| `::share::` | Feed-Text | Freigabe-Grants für Fremdgruppen (Variante A, `data/FeedShare.kt`) |

Ausnahme: `repeatSpawned` steht **zusätzlich** als `MetaKey.REPEAT_SPAWNED` im Core — nicht in
`KNOWN`, sondern damit `autoMergeContent` ihn per Last-Writer-Wins auflösen kann, statt den Nutzer
mit einem Konflikt zu behelligen.

**Prioritäten** (5 Bänder KEINE < GELB < ORANGE < ROT < ÜBERFÄLLIG): ein Due-Date leitet das Band
automatisch ab, eine bunte Hand-Prio **maskiert** den Termin (Knoten bleibt, UI blendet ihn aus).
Innerhalb eines Bands entscheidet nur der orderKey. Materialisiert wird ausschließlich beim
frischen Einschalten der Auto-Sortierung (Flag + Rekeys = EIN Undo-Schritt).

**Wiederholende Aufgaben**: Kopie-IDs sind **deterministisch** aus (Quell-nodeId, Vorkommens-
Schlüssel) gehasht. Spawnen zwei Geräte offline dieselbe Wiederholung, entsteht DERSELBE Knoten mit
gleichem Inhalt → inhaltsgleiche Heads = kein Konflikt, keine Duplikate. Das ist der Grund, warum der
Vorkommens-Schlüssel bevorzugt das alte **Due-Datum** ist (geräteübergreifend gleich) und nur
ersatzweise die Head-versionId.

**Undo/Redo** ist Op-Log-basiert (`docs/undo_redo.md`): nie destruktiv, jedes Undo ist eine NEUE Op
im git-revert-Stil und synct über die normale Head-Mechanik. Ketten liegen pro Screen-Anker im RAM
(überleben Navigation, nicht den Prozess). Fremde Ops kommen nie in eine Kette — sie **invalidieren**
die betroffenen Einträge. Hintergrund-Schreiber (CalendarSync, WebServer, Fälligkeits-Sweep) ohne
Anker werden bewusst nicht aufgezeichnet.

## Sync (das schwierigste Stück — hier steckt die meiste Erfahrung)

- **Discovery dreigleisig** (`sync/SyncManager.kt`): UDP-Broadcast-Beacon ist der Hauptweg (global
  *und* gerichtet je Schnittstelle, weil manche Router den globalen filtern), NSD/mDNS als Zusatz
  (Resolves serialisiert, sonst „has no client mapping"), manuelle Fallback-Peers aus den Einstellungen.
- **Verschlüsselung** per `SecureChannel` (AES-GCM, Schlüssel aus PBKDF2 über die Gruppen-Passphrase,
  Salt = Gruppenname). Falsche Passphrase = GCM-Tag schlägt fehl → implizite Gruppen-Authentifizierung.
- **`ConnectionGate` ist kein Feintuning, sondern ein Bugfix**: eine Verbindung pro Peer plus 4 s
  Cooldown. Ohne das erzeugt der 2-Sekunden-Beacon einen Verbindungssturm, der IO-Pool läuft voll und
  der Accept-Loop nimmt nichts mehr an (Symptom: „Read timed out", lange laufende Geräte degradieren).
- **Der Versions-Vektor trägt Lücken** (`PeerState`: maxSeq + gaps), nicht nur die höchste Seq.
  Fehlte früher eine Op in der Mitte, blieb sie unsichtbar und wurde nie nachgeliefert → Dauer-
  divergenz und Phantom-Konflikte, die kein Merge auflösen konnte.
- **Foreground-Service-Typ ist `connectedDevice`, nicht `dataSync`** — Absicht: `dataSync` hat seit
  Android 15 ein hartes 6h/24h-Budget, danach killt das System den Service und jeder STICKY-Neustart
  crasht sofort wieder. Ohne Service sind schlafende Geräte (Doze) von außen gar nicht erreichbar;
  das war die Ursache der „mal geht es, mal nicht"-Aussetzer.
- **FRITZ!Box** = passive Voll-Replik über FTP, flaches Log-Layout (`<deviceId>__<seq>.json`), alles
  im Klartext. Kein Single Point of Truth: fällt sie aus, rekonstruieren die Geräte alles untereinander.
- **Cross-Group** (`sync/CrossGroupProtocol.kt`): pro Fremdgruppe eine eigene Feed-Capability
  (nicht ein geteilter Schlüssel) → das Original erkennt, wer verbindet, setzt dessen Rechte
  autoritativ durch und kann genau eine Gruppe entziehen. Rechte werden **zweimal** durchgesetzt:
  autoritativ beim Eigentümer, kosmetisch als UI-Gating beim Fremdgerät.

## UI-Landkarte (`ui/`)

Navigation ist **modal geschachtelt** (State + `return` in `AppRoot`), kein Nav-Framework. Der
Navigations-Stack ist `rememberSaveable` als nodeId-Liste, damit Drehen ihn nicht wegwirft.

| Datei | Zweck |
|---|---|
| `ListScreen.kt` | Kinder EINES Knotens (oder Wurzel=Feeds): gemischte Zeilen, FAB (Tap=childDefault, Langdruck=Typmenü, Wurzel nur LIST — `KindRules.kt`), Langdruck=Aktionsdialog, Suche, Drag-Sortierung, Swipe→Tonne |
| `PostDetailEditor.kt` | Notiz/Listen-Beschreibung: gerendert (✓) ↔ Quelltext (✎), In-Text-Suche, Zeilen-Drag in gerenderter Ansicht, Anhänge-Kasten. `showAttachments=false` für Listen-Beschreibungen! |
| `TodoDetailScreen.kt` | Aufgabe: Haken+Titel, Body, Unterpunkte-Kasten (Quick-Add), Erinnerungs-Kasten, Anhänge. Unterpunkte sind im Baum flache Geschwister — die Gruppierung ist rein visuell |
| `TaskRepeatUi.kt` | Erinnerungs-Kasten: Termin-Chip links, Prio-Kreise rechts, Wiederholungs-Zeile darunter. Bunte Prio maskiert den Termin |
| `PriorityUi.kt` | feste Bandfarben (Volltonfläche im Picker, Alpha-Tönung auf Zeilen) |
| `AttachmentDetailScreen.kt` | EIN Anhang: Beschreibung editierbar, darunter fix Bild (Pinch-Zoom) bzw. Datei; Long-Press Teilen/Bearbeiten bzw. Teilen/Öffnen |
| `Attachments.kt` / `AttachmentPicker.kt` | gemeinsamer Anhänge-Kasten + Anlege-/Öffnen-/Teilen-Helfer |
| `TagUi.kt` / `TagSearchScreen.kt` | Chip-Zeile + Picker auf allen fünf Detail-Screens / UND-Suche über alle Feeds mit Breadcrumb |
| `UndoUi.kt` | Undo/Redo-Buttons + Anker-Registrierung. **Ganz oben im Composable**, VOR den modalen `if (…) { …; return }`-Zweigen |
| `ReorderableList.kt` | DragDropState (LazyColumn, Vorschau), ColumnDrag (Kästen), SwipeRevealRow (Tonne bleibt stehen, Tap löscht sofort, max. 1 offen). Landeplatz = **Mittellinien-Regel** |
| `Markdown.kt` / `MarkdownEditing.kt` / `MarkdownEditField.kt` | eigener Renderer / reine Text-Transformationen (testbar) / gemeinsames Quelltextfeld — **Editier-Verhalten nur dort ändern** |
| `CalendarEntryEditor.kt` | Termin-Formular (EventCodec) — bewusst stabil lassen |
| `WheelPicker.kt` | Zahlen-/Text-Räder. „Zentriertes Item" ist EINE Definition für Styling und Settle — auseinandergelaufen war das der Flacker-Bug |
| `SharingUi.kt` / `SharePickerScreen.kt` | Freigabe-Verwaltung eines eigenen Feeds / Ziel-Auswahl für Share-Intents |
| `ConflictScreen` / `DetailMergeScreen` | manuelle Konfliktauflösung (soll langfristig fast nie nötig sein) |

## Konventionen

- **Deutsch** in UI-Strings und Kommentaren; Kommentarstil des Umfelds übernehmen.
- **Erst reden, dann bauen**: bei Meldungen/Fehlern erst Ursache + Vorschlag, Umbauten nach Absprache.
  Rückfragen im Fließtext mit Empfehlung, **nicht** als Auswahl-Dialog.
- Commits: `[bereich] beschreibung` auf Deutsch, pro abgeschlossenem Chunk, laufend statt am Ende;
  auf dem ausgecheckten Branch bleiben. **Nicht pushen** ohne Auftrag.
- Serena-MCP für Code-Navigation und -Edits (token-effizient), CodeGraph für die Breite —
  die Arbeitsteilung steht in `CLAUDE.local.md`.
- UI darf restriktiver sein als das Backend (`KindRules`): sinnlose Knoten-Kombinationen weder
  anlegen noch anzeigen. Wurzelebene: nur Listen.
- Beim Editieren NIE `type` überschreiben (der Editor bearbeitet TEXT-, LIST- und TODO-Knoten).
- Löschen = `deleted`-Flag (Op-Log behält alles); Tonnen-Löschen fragt bewusst nicht nach.

## Fallstricke (teuer gelernt)

- **Nie auf einem nicht-konvergierten Gerät editieren.** Die neue Version hängt am lokalen Head;
  ist der veraltet, zweigt sie an einer alten Fassung ab → Konflikt auf den anderen Geräten *und*
  stiller Datenverlust. Die Sync-Meldung „+0 empfangen" beweist keine Konvergenz, sie beschreibt nur
  die letzte Runde. Und: **einen Konflikt auf dem Gerät auflösen, das beide Heads sieht.**
- **Geräte-DB immer mit `-wal`/`-shm` ziehen**, sonst fehlen die frischen Ops
  (`adb exec-out run-as de.beardedskunk.homeshare cat databases/homeshare.db`).
- **Serena schrieb früher CRLF** in dieses LF-Repo. `line_ending: "lf"` steht in `.serena/project.yml`;
  nach einer Änderung dort den Server neu verbinden. Zeigt ein Diff plötzlich die ganze Datei:
  `git diff --ignore-cr-at-eol` zeigt die echte Änderung.
- **Nicht gleichzeitig vom Dev-Rechner und von den Geräten auf die FRITZ!Box** — sie verträgt nur
  wenige FTP-Sessions und quittiert das mit „550 No files found" oder Abbrüchen.
- `MSYS_NO_PATHCONV=1` bei allen adb-Kommandos mit Gerätepfaden (sonst wird `/sdcard/…` zu
  `C:/Program Files/Git/sdcard/…`).
- Der **Armor 8** hat programmierbare Hardware-Tasten, die während adb-Sessions spontan
  Back-Navigationen auslösen; `KEYCODE_BACK` zum Tastatur-Schließen triggert dort das App-Back.
- **`applicationId`/Namespace `de.beardedskunk.homeshare`**, DB `homeshare.db` — aber die
  SharedPreferences heißen historisch `clip_identity.xml`/`clip_settings.xml`, die Application-Klasse
  `ClipApplication` und der mDNS-Dienst `_clipfeed._tcp`. Das ist Absicht (kein Datenverlust), nicht
  vergessene Umbenennung.

## Aktueller Stand / offene Baustellen

Branch `feature/list-types`, HEAD 2026-07-08. Unit-Tests grün (235/235).

**Fertig:** einheitliche Listen-UI · Todo-UI · Anhänge-Redesign + Detailansicht · versionierte
Drag-Sortierung · Swipe-Löschen · testTags · Cross-Group-Sharing (#10, E2E verifiziert) ·
Tag-System (Zeile, Picker, UND-Suche) · Undo/Redo · wiederholende Aufgaben (RRULE-Untermenge,
Tages-Granularität) · Prioritäts-Farben + Auto-Sortierung · Erinnerungs-Kasten.

**Uncommitted im Arbeitsbaum** (liegt seit 2026-07-08, Tests grün): Repeater-Due-Automerge —
`TaskRepeat.occurrenceKey`/`mergeDueTexts`, `FeedRepository.mergeRepeatDue`, `MetaKey.REPEAT_SPAWNED`
im Core-Automerge. Löst den Doppel-Spawn nach Offline-Phasen. Dazu untracked `docs/tag-system-plan.md`
und `.codegraph/`. **`gradle.properties` ist lokal auf diesem Rechner angepasst und darf nicht mit
rein** — nie `git add .` oder `-A`.

**Offen:** On-Device-Verifikation von Prioritäten und Undo/Redo (Zwei-Geräte-Sync des `prioSort`-Flags,
Drag-/Tag-/Kalender-Undo) · Zeilen-Drag ohne Live-Preview · Knoten verschieben (`moveNode` existiert
im Backend, keine UI) · 15-MiB-Blob-Limit (`docs/large-file-streaming-plan.md`) · History-Browser ·
Op-Log-Kompaktierung · Eviction wieder verdrahten · Web-UI · Dialog-Fenster ohne testTag-resource-ids ·
Suche in der gerenderten Ansicht (#5) · Cursor-Autoscroll über der Tastatur (#9).
