# Umbau: Knoten-Baum (ersetzt Feeds+Posts+eingebettete Bilder)

Status: **in Arbeit** auf Branch `feature/tree-nodes`. Bewusst **abwärts-inkompatibel** (alle Geräte
einmal wipen + neuer Build). Ziel: ein einheitlicher, versionierter Knoten-Baum statt Feeds mit Posts
mit eingebetteten Bildern.

## Grundidee
Alles ist ein **Knoten** im selben Op-Log/Versions-DAG. Kein getrenntes Feed-/Post-/Bild-Konzept mehr.
Ein Feed = Wurzelknoten (Typ TEXT, parent=ROOT). Ein „Post" = TEXT-Knoten unter einem Feed. Bilder/
Dateien = Kindknoten des Post-Knotens; ihre Beschreibung = TEXT-Kindknoten des Bild-/Datei-Knotens.

## Knoten-Inhalt (NodeContent, fließt in versionId)
- `parentId` (String) – Elternknoten oder `ROOT`.
- `type` (enum) – `TEXT | CALENDAR | IMAGE | FILE | TODO`.
- `orderKey` (String) – Geschwister-Reihenfolge; vorerst aus HLC abgeleitet, Feld reserviert fürs Sortieren.
- `color` (Int?, ARGB) – optional, an jedem Knoten.
- `tags` (List<String>) – optional, vorerst leer.
- `childDefault` (enum?) – UI-Hinweis, welcher Kindtyp gemeint ist (z. B. CALENDAR für Kalender-Feed).
- Payload:
  - TEXT/CALENDAR/TODO → `text` (TEXT: 1. Zeile Titel + Markdown; CALENDAR: EventCodec; TODO: Titel).
  - TODO zusätzlich `done` (Bool) – vorerst rudimentär.
  - IMAGE/FILE → `blobHash`, `fileName`, `mime`.
- `deleted` (Bool) – Tombstone; löscht Knoten + blendet Teilbaum aus.

## versionId (neu, einheitlich)
`versionId = sha256(canonical)`, canonical über: nodeId, sortierte DAG-parents, device, hlc,
deleted, type, parentId, orderKey, color, childDefault, tags(längenpräfixiert), blobHash, fileName,
mime, done, text(längenpräfixiert). **Keine Bild-Sonderlocke** – ein Format für alle Typen.
Determinismus + Konflikt/Auto-Merge bleiben pro Knoten wie gehabt.

## DB
- `ops`: version_id, node_id, parent_id, device_id, seq, hlc_wall, hlc_counter, parents, deleted,
  type, order_key, color, child_default, tags, blob_hash, file_name, mime, done, text, device_name.
  (raus: feed_id, image_hashes, image_titles)
- `node_current` (materialisiert): node_id, parent_id, type, head_version_id, alle Payload-Felder,
  color, tags, child_default, deleted, conflicted, created_*, updated_*. (ersetzt post_current + feeds)
- `FEEDS_FEED`-Meta entfällt (Feeds sind Root-Knoten). FTS über text der TEXT/CALENDAR/TODO-Knoten.
- `calendar_link` bleibt (post_id → node_id).
- MATERIALIZATION-/Protokollversion hoch.

## Repository-API (Skizze, NodeRepository)
- `children(parentId): List<NodeState>` (sortiert nach orderKey, ohne gelöschte).
- `roots()` = `children(ROOT)`.
- `subtree(nodeId)` / `loadNode(nodeId)`.
- `createNode(parentId, type, payload, …): NodeState` (autor lokal).
- `editNode(nodeId, …)`, `deleteNode(nodeId)`, `moveNode(nodeId, newParent, orderKey)`.
- Auto-Merge pro Knoten wie heute; Blob-Sync unverändert (eager).

## Abbildung auf die bestehende UI (UI bleibt gleich)
- FeedListScreen → `roots()` (TEXT-Knoten). „Feed anlegen" → Root-TEXT-Knoten; Kalender-Haken → childDefault=CALENDAR.
- FeedScreen(feed) → `children(feed.nodeId)`. childDefault steuert FAB (Neuer Eintrag vs. Neuer Termin)
  und welche Zeilen-Renderer (PostRow vs. CalendarRow).
- PostDetailEditor(textNode) → editiert **Teilbaum**: der TEXT-Knoten + seine IMAGE/FILE-Kinder
  (Karten) + deren Beschreibungs-TEXT-Kinder. „Bild/Datei hinzufügen" = Kindknoten anlegen;
  Beschreibung tippen = Beschreibungs-Kindknoten editieren. Anzeige identisch zu heute.
- Kalender/Conflict/DetailMerge/Sharing analog auf Knoten umstellen.

## Datei-Typ (nach dem Baum)
- IMAGE = blob mit Bild-MIME (Thumbnail), FILE = beliebig (kein Thumbnail → Icon/Badge).
- Picker: SAF `OpenMultipleDocuments("*/*")`; Name+MIME erfassen.
- Anzeige: Bild → Thumbnail; sonst Standard-Icon (material-icons-extended, R8 an) bzw.
  Endungs-Badge + generisches Datei-Icon für Unbekanntes.
- Antippen: ACTION_VIEW (öffnen mit), ACTION_SEND (teilen); erst Blob lokal sicherstellen, dann
  in Cache-Datei mit Originalname kopieren. „Durch neue Version ersetzen" via SAF-Pick.
- Editieren-und-zurück: nur Bilder (bestehender MediaStore-Flow).

## Farbe / Tags / Teilen
- Farbe ab Start am Knoten (UI: Text/Kalender/Todo). Tags: Feld da, UI später.
- Teilen vorerst nur Root-Knoten (Cross-Group-Mechanik auf Knoten verallgemeinern; später jeder Knoten).

## Große Dateien
Eigener Block, s. [large-file-streaming-plan.md](large-file-streaming-plan.md). Vorerst alter
In-Memory-Pfad + Cap; Streaming später.

## Schrittfolge (jeder Schritt baut grün, Verifikation auf USB-F101)
1. **Core:** Model (Node/NodeType/NodeContent/NodeVersion + neue canonical) + Aggregation (Node.kt:
   heads/conflict/LCA/auto-merge) + Core-Unit-Tests.
2. **Daten:** Db-Schema + NodeRepository (author/ingest/materialize/children/subtree/search) + Tests.
3. **Sync:** Op-Wire-Codec + PeerProtocol-Felder + Version-Vektor (Op-Format), Cross-Group anpassen.
4. **UI:** FeedList/FeedScreen/PostDetailEditor(als Teilbaum)/Kalender/Conflict/Merge/Sharing auf Knoten.
5. Bauen, alle Geräte wipen, deployen, am USB-F101 gegenprüfen (Feed anlegen, Eintrag mit Bildern,
   Kalender, Sync zu anderen Sascha-Geräten).
6. **Danach** Datei-Typ (Icons/Picker/Öffnen/Ersetzen) + Web-UI + Share-Empfang.
