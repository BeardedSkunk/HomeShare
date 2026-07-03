# HomeShare

Eine sideloadbare Android-App (min. Android 10 / API 29): ein **Verzeichnisbaum aus Listen-in-Listen**
für Notizen, Aufgaben, Termine und Datei-Anhänge – **auf allen eigenen Geräten synchron**, ohne Login,
ohne Cloud, ohne fremden Server. Replikation läuft Gerät-zu-Gerät im LAN (Auto-Discovery), mit
git-artiger Versionierung pro Knoten, **automatischer** Konfliktauflösung im Hintergrund (manuell nur
bei echten Überlappungen), Zugriff per Webbrowser vom PC und einer FRITZ!Box als passivem Voll-Backup.

> Persönliches Projekt. Läuft bewusst **ohne Google Play Services** – also auch auf
> de-googelten Systemen wie LineageOS ohne Play Store.

## Was es kann

- **Listen in Listen** (Verzeichnisbaum): jede Liste hat einen Default-Eintragstyp (Notiz, Termin,
  Aufgabe, …) und eine eigene Beschreibung (Titel + Markdown). Gemischte Inhalte pro Liste sind normal.
- **Notizen**: erste Zeile = Titel, Rest Markdown (eigener Renderer: Überschriften, Listen,
  antippbare Aufgaben-Haken, Zitate, Code, Links; Editor mit Toolbar, In-Text-Suche,
  Tipp-auf-gerenderten-Text springt an die Quellstelle).
- **Aufgaben** mit Haken, Markdown-Body, abhakbaren Unterpunkten (Quick-Add) und Anhängen.
- **Anhänge beliebigen Typs** (Fotos + Dateien, content-adressiert/dedupliziert): jede*r mit eigener
  Beschreibungs-Notiz und Detailansicht (Bild mit Pinch-Zoom, extern bearbeiten/teilen/öffnen).
- **Manuelles Umsortieren per Drag-Handle** (Listen, Unterpunkte, Anhänge, Markdown-Listenzeilen) –
  die Reihenfolge ist versioniert und synct mit (fraktionale Sortierschlüssel, 1 Op pro Drag).
- **Swipe-links → Mülltonne** zum schnellen Löschen (zweistufig: Tonne bleibt stehen, Tap löscht).
- **Git-artige Versionierung** pro Knoten (DAG aus inhaltsadressierten Versionen). Nebenläufige
  Bearbeitung wird per **deterministischem 3-Wege-Merge** (diff3) im Hintergrund zusammengeführt;
  nur echte, überlappende Konflikte landen in der manuellen Auflösung (farbiger Wort-Diff). Eine
  einmal getroffene Auflösung gilt danach für alle Geräte.
- **LAN-Sync** per NSD/mDNS + Versions-Vektoren (lückenbewusst), funktioniert in **jedem** gemeinsamen
  WLAN – Heimnetz oder fremd.
- **Verschlüsselte Übertragung** (AES-GCM, Schlüssel via PBKDF2 aus der Gruppen-Passphrase). Es koppeln
  nur Geräte mit gleichem Gruppennamen + Passphrase.
- **Bilder** content-adressiert (SHA-256, Dedup), Thumbnails immer lokal, Voll-Bilder per Budget;
  Peer-zu-Peer-Übertragung direkt zwischen Geräten **oder** über die FRITZ!Box.
- **FRITZ!Box als Voll-Replik** über FTPES (Op-Log + Blobs als Dateien) – primärer Anlaufpunkt im
  Heimnetz, kein Single Point of Truth: fällt sie aus, rekonstruieren die Geräte alles untereinander.
- **Kalender-Feeds**, die in den Android-Kalender gespiegelt werden; .ics-Import.
- **Feed-Sharing mit anderen Gruppen** (lesen/schreiben/mergen, Rechte beim Eigentümer).
- **Webbrowser-Zugriff** vom PC (manuell startbar): ansehen, suchen, editieren, Screenshot aus der
  Zwischenablage einfügen.

## Architektur (Kurzüberblick)

Alles ist **ein Knotenbaum**: Listen, Notizen, Aufgaben, Termine und Anhänge sind Knoten mit
`parentId`, Typ, Text (1. Zeile = Titel), Sortierschlüssel und offenem Meta-System (unbekannte Felder
überleben den Sync mit älteren App-Versionen). Quelle der Wahrheit ist ein **append-only Op-Log**:
jeder Knoten hat eine git-artige History aus inhaltsadressierten Versionsknoten
(`versionId = SHA-256(Inhalt + Eltern + Gerät + HLC)`). Mehrere „Heads" = nebenläufige Bearbeitung.
Versionen werden nie gelöscht, nur überholt – alte Stände bleiben rekonstruierbar.

- `core/` – reine, Android-freie Logik (JVM-getestet): `Model` (NodeContent, Meta-Codec, HLC),
  `Node` (Versions-DAG, Heads, LCA, Auto-Merge), `OrderKeys` (fraktionale Sortierschlüssel),
  `ThreeWayMerge` (deterministischer diff3), `Hashing`, `TextDiff`.
- `data/` – Persistenz (Framework-SQLite, kein Room): `Db`, `FeedRepository` (Autoring, Sync-Ingest,
  Auto-Merge, Umsortieren, FTS4-Suche, Versions-Vektor), `BlobStore` (content-adressiert, Thumbnails),
  `EvictionPlanner`, `DeviceIdentity`, `Settings`.
- `sync/` – `Sync` (Wire-Codec + Reconciler), `SecureChannel` (AES-GCM), `PeerProtocol` /
  `CrossGroupProtocol` (inkl. Blob-Austausch), `SyncManager` (NSD/mDNS + TCP, Gruppenfilter),
  `SyncForegroundService` (Hintergrundbetrieb).
- `web/` – `WebServer` (NanoHTTPD, JSON-API + Blob-Serving) + `WebUi` (Single-Page, Clipboard-Paste).
- `backup/` – `FritzReplica` (FTPES) + `FritzController` (Voll-Backup auf der FRITZ!Box).
- `ui/` – Jetpack-Compose-UI: `ListScreen` (Baum-Navigation, gemischte Zeilen, Drag-Sortierung,
  Swipe-Löschen), `PostDetailEditor` (Notizen/Listen-Beschreibung, Markdown gerendert ↔ Quelltext),
  `TodoDetailScreen` (Aufgabe + Unterpunkte), `AttachmentDetailScreen` (Anhang + Beschreibung),
  Konfliktauflösung, Kalender-Editor, Einstellungen. Interaktive Elemente tragen testTags
  (per uiautomator automatisierbar, siehe `CLAUDE.md`).

### Bewusste Abweichungen (gleiche Funktion, robuster Build)
- **Framework-SQLite statt Room** (kein KSP/Annotation-Processing).
- **FTS4 statt FTS5** (auf allen Zielversionen garantiert verfügbar).
- **NanoHTTPD statt Ktor** für den eingebetteten Webserver (leichtgewichtig).
- **ZXing-android-embedded** für QR (eigene Scanner-Activity, **kein** Play-Services-Vision).

## Bauen & Installieren

Toolchain (erprobt): AGP 9.0.1, Gradle 9.1, Kotlin 2.3.20, JDK 21 (Android Studio JBR). Das JDK wird
**nur projektlokal** gesetzt – keine globalen Pfade.

```bash
# Bauen:
./gradlew :app:assembleDebug

# Unit-Tests (reine Logik: Merge/Konflikte, Sync, Diff, Blobs, Markdown):
./gradlew :app:testDebugUnitTest

# Auf ein angeschlossenes Gerät installieren:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`applicationId` / Namespace: `de.beardedskunk.homeshare`.

## Lizenz

[PolyForm Noncommercial License 1.0.0](LICENSE.md).

Klartext: **Du darfst HomeShare kostenlos nutzen, kopieren, verändern und weitergeben – für jeden
nicht-kommerziellen Zweck** (privat, Hobby, Lernen, gemeinnützig). **Nicht erlaubt ist die
kommerzielle Nutzung** (damit Geld verdienen) durch Dritte. Die Software kommt **ohne jede Gewähr**.

## Mitwirken

Pull Requests für nicht-kommerzielle Verbesserungen sind willkommen. Mit einem Beitrag stimmst du zu,
dass er unter derselben Lizenz steht.
