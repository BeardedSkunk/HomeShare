# Teilprojekt: Streaming-Blob-Pipeline für große Dateien (bis ~1 GB)

Status: **geplant, zurückgestellt.** Erst nach dem Datei-/Baum-Umbau angehen.

Ziel: beliebig große Anhänge (Richtwert 1 GB, kein hartes Limit) zwischen Geräten + FRITZ!Box
teilen, ohne dass die ganze Datei je in den RAM geladen wird.

## Warum heute nicht möglich
Die ganze Blob-Pipeline ist aktuell **in-memory + ein Frame**:
- Erfassen: `contentResolver.openInputStream().readBytes()` → ganze Datei als `ByteArray`.
- Hashen: `Hashing.sha256Hex(bytes)` braucht das komplette Array.
- `BlobStore.put(bytes)` / `readFull(): ByteArray` → alles im RAM.
- Transport: `SecureChannel` AES-GCM, **eine** Nachricht; `BlobExchange.MAX_BLOB = 15 MB`,
  Frame-Limit 16 MB. Größere Blobs werden beim Senden still rausgefiltert.

## Umbau-Bausteine
1. **Erfassen streamend:** Quelle (SAF-Uri) in Stückchen in die Blob-Datei kopieren und dabei
   per `DigestInputStream`/`MessageDigest.update()` den SHA-256 mitrechnen (nie ganze Datei laden).
   Da der Dateiname content-adressiert ist, in eine Temp-Datei schreiben, am Ende auf `blobs/<sha>` umbenennen.
2. **BlobStore streamend:** `readFull(): ByteArray` ersetzen/ergänzen durch `openRead(sha): InputStream`
   und `openWrite(): (OutputStream, finalize→sha)`. Aufrufer (Web, Transport, Öffnen) auf Streams umstellen.
3. **Chunked Transfer-Protokoll:** Blob in Frames (z. B. 1–4 MB), jeder Frame eigene GCM-Nonce,
   Header je Blob (sha, Gesamtgröße), Empfänger schreibt direkt auf Disk. Idealerweise **resumierbar**
   (Offset/Länge + Wieder-Aufsetzen nach Abbruch), damit 900 MB nicht von vorn müssen.
   `MAX_BLOB`-Cap entfällt; stattdessen Frame-Größe begrenzt, nicht Blob-Größe.
4. **FRITZ!Box:** FTP streamt schon; Up-/Download nur nicht mehr über `ByteArray` führen. Achtung:
   Box-Speicher ~3,4 GB → Speicherwarnung/Eviction wichtiger; ggf. USB-Datenträger an die Box.
5. **Webserver:** Download streamt bereits (`newChunkedResponse`/`FileInputStream`). **Upload** großer
   Dateien von Base64-in-einem-POST auf gechunkten/Multipart-Upload umstellen (Base64 +33 %, im RAM).
6. **UI/Budget:** „Bild-Budget" → allgemeines Datei-Budget; Fortschrittsanzeige bei großen Transfers;
   Größen-/Speicherwarnungen.

## Risiken / offen
- AES-GCM-Frame-Reassembly + Resume sauber + sicher (Nonce-Eindeutigkeit pro Schlüssel).
- Eviction endlich verdrahten (heute `EvictionPlanner` ohne Aufrufer), sonst füllen 1-GB-Dateien alles.
- Speicherdruck auf der FRITZ!Box.
