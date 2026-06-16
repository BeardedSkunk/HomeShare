# ClipSharing

Eine sideloadbare Android-App (min. Android 10 / API 29) für einen geteilten, chat-artigen Feed
über alle eigenen Geräte – **ohne Login, ohne Cloud**. Replikation läuft Gerät-zu-Gerät im LAN
(Auto-Discovery), mit git-artiger Versionierung pro Beitrag, manueller Konfliktauflösung,
Webbrowser-Zugriff vom PC und einer FRITZ!Box als passivem Voll-Backup.

## Architektur (Kurzüberblick)

Quelle der Wahrheit ist ein **append-only Op-Log**: jeder Beitrag hat eine git-artige History aus
inhaltsadressierten Versionsknoten (SHA-256). Mehrere „Heads“ = nebenläufige Bearbeitung = Konflikt.

- `core/` – reine, Android-freie Logik (JVM-getestet): `Model`/`Post` (Versions-DAG, Heads,
  LCA, Merge), `Hashing`, `TextDiff` (Wort-Diff).
- `data/` – Persistenz (Framework-SQLite, kein Room): `Db`, `FeedRepository` (Autoring, Sync-Ingest,
  FTS4-Suche, Versions-Vektor), `BlobStore` (content-adressiert, Thumbnails), `EvictionPlanner`,
  `DeviceIdentity`, `Settings`.
- `sync/` – `Sync` (Wire-Codec + `SyncReconciler`, JVM-getestet), `PeerProtocol` (Socket-Protokoll),
  `SyncManager` (NSD/mDNS-Discovery + TCP-Server/Client, Gruppenfilter).
- `web/` – `WebServer` (NanoHTTPD, JSON-API + Blob-Serving) + `WebUi` (Single-Page, Clipboard-Paste).
- `backup/` – `FritzReplica` (FTPES) + `FritzController` (Voll-Backup auf der FRITZ!Box).
- `ui/` – Compose-UI: Feed-Liste, Feed-Ansicht, Editor (mit In-Post-Suche), Konfliktauflösung
  (farbiger Diff), Teilen-Auswahl, Einstellungen.

### Bewusste Abweichungen vom ursprünglichen Plan
Pragmatisch gewählt, um auf diesem Rechner ohne Versions-Raten robust zu bauen (gleiche Funktion):
- **Framework-SQLite statt Room** (kein KSP/Annotation-Processing).
- **FTS4 statt FTS5** (auf allen Zielversionen garantiert verfügbar).
- **NanoHTTPD statt Ktor** für den eingebetteten Webserver (leichtgewichtig).

## Bauen & Installieren

Toolchain (maschinen-erprobt): AGP 9.0.1, Gradle 9.1.0, Kotlin 2.3.20, JDK 21 (Android Studio JBR).
Das JDK wird **nur projektlokal** über `gradle.properties` gesetzt – keine globalen Pfade.

```bash
# Bauen (JBR nur für diesen Aufruf):
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug

# Unit-Tests (reine Logik: Konflikte, Sync, Diff, Blobs):
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest

# Auf ein angeschlossenes Gerät installieren:
"$HOME/AppData/Local/Android/sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

## Status

Implementiert und (wo ohne Gerät möglich) per Unit-Test verifiziert:
- Beliebig viele benannte Feeds; Text- und Bild-Beiträge; Editieren/Löschen.
- Git-artige Versionierung, Konflikterkennung, manuelle Auflösung mit Wort-Diff (Auflösung gilt für alle).
- LAN-Sync per NSD + Versions-Vektoren; funktioniert in jedem gemeinsamen WLAN.
- Bilder content-adressiert + Thumbnails; Eviction-Strategie (Budget) als Logik vorhanden.
- Webbrowser-Zugriff (manuell startbar, IP-Anzeige) inkl. Einfügen aus der Zwischenablage.
- Share-to-App (Text/Bild) mit Feed-Auswahl.
- FRITZ!Box-Voll-Backup über FTPES (Ops + Blobs als Dateien).

### Muss auf echten Geräten getestet werden (hier kein Emulator verfügbar)
- App-UI insgesamt; LAN-Sync zwischen zwei Geräten (auch im fremden WLAN); Webserver vom PC;
  Share-Intent; FRITZ!Box-FTPES (ggf. SMB/SBM-Cipher-Feintuning, Box-Option „Nur sichere
  FTP-Verbindungen (FTPS)“ aktivieren, Benutzer mit NAS-Recht).

### Offen / nächste Schritte
- **Transportverschlüsselung (TLS) + Gruppen-Passphrase** (Task 10): Sync-Sockets per TLS
  (selbstsigniertes Zertifikat aus einem Gruppen-Secret), Gruppen-Authentifizierung,
  QR-Pairing später. Aktuell ist eine feste Standardgruppe gesetzt; Transport unverschlüsselt.
- Foreground-Service für dauerhaften Hintergrundbetrieb von Sync/Webserver.
- Automatisches Auslösen der Bild-Eviction inkl. „anderswo gesichert?“-Prüfung + Warnung.

Persönliches Projekt – privates Repo `BeardedSkunk/ClipSharing`.
