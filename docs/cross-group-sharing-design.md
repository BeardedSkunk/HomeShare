# Design: Feeds mit Fremdgruppen teilen (#10)

Status: **In Umsetzung.** Getesteter Kern steht; Integration + UI + Geräte-E2E folgen.

### Implementierungsstand (2026-06-18)
**Fertig + unit-getestet:**
- Krypto-Capability (capSecret = Kanal-Schlüssel, String-Krypto, Tokens) — `crypto/GroupCrypto.kt`.
- `FeedRight` (read/write/merge) + `ShareGrant` + Freigabe-Codec (Variante A) + QR-Pairing-Payload — `data/FeedShare.kt`.
- Cross-Group-Sync-Protokoll (feed-begrenzt, Rechtedurchsetzung) — `sync/CrossGroupProtocol.kt` + `FeedScopedSource` im Repo (`feedVersionVector`/`feedMissingFor`/`acceptIncomingOp`/`acceptForeignOp`).
- Freigabe-Verwaltung im Feed-Op — `FeedRepository.feedShares/setFeedShares`.
- Tests: `FeedShareTest`, `CrossGroupProtocolTest`.

**Noch offen (Integration + UI + Test):**
- DB: Fremdfeed-Ablage auf dem Fremdgerät (feeds-Spalten `foreign_origin/cap_id/cap_secret/foreign_right`, DB_VERSION-Migration) + Repo-Methoden (registrieren/auflisten/Recht aktualisieren/„Freigabe verlassen").
- `SyncManager`: Klartext-**Modus-Präambel** vor der Verschlüsselung (`GROUP` vs `FEED <feedId> <capId>`), Branch auf `CrossGroupProtocol`, Cross-Group-Discovery (zu Peers der Origin-Gruppe verbinden).
- Pairing-Orchestrierung (QR erzeugen/anzeigen mit 2-min-Ablauf + Auto-Close; Scan/Code → verbinden → Gruppenname melden → Grant eintragen).
- UI: Share-Icon, Feed-Einstellungen (Grant-Liste, write/merge-Schalter, „Gruppe hinzufügen"=QR, „Zugriff entziehen"), Scan-Screen (ZXing) **+ manuelle Code-Eingabe** (zum Testen ohne Kamera), Konflikt-Hinweis ohne merge-Recht, „Freigabe verlassen".
- Geräte-E2E (Tablet/F101/Armo 8) mit gefaktem QR über manuelle Code-Eingabe; diverse Rechte-/Sync-Szenarien.

Grundlage: die mit dem Nutzer abgestimmten Entscheidungen (siehe unten „Bestätigt").

## Ziel
Ein Feed bleibt in seiner **Originalgruppe** beheimatet, kann aber für andere Gruppen
freigegeben werden — mit Rechten **lesen** (immer, Minimum), **schreiben** (opt-in),
**mergen** (opt-in). Konflikte löst nur die Originalgruppe; Fremdgruppen sehen sie nur.

## Bestätigte Entscheidungen
- Pairing per **einmaligem QR** (in Person, beide im selben WLAN), alles aus der App-UI. **QR-Ablauf: 2 Minuten.**
- **Jede Fremdgruppe bekommt eine eigene Feed-Capability** (eigener Schlüssel) für denselben Originalfeed.
- Capability = Identität + Auth; angesagter Gruppenname ist nur Label.
- **Rechtestufen:** `read` = sehen (Minimum, immer). `write` = Einträge hinzufügen/bearbeiten (pushen). `merge` = **darf Konflikte selbst lösen** — das ist der EINZIGE Zweck dieses Rechts. Ohne `merge` löst nur die Originalgruppe Konflikte.
- Rechte nach Pairing in der UI änderbar, werden **über das Netz** an die Fremdgruppe übermittelt (UI-Gating) und von Originalgeräten **autoritativ** durchgesetzt.
- Widerruf = ab jetzt keine neuen Updates mehr (Altbestand bleibt bei der Fremdgruppe).
- **Capability-Secrets SYNCEN innerhalb der Originalgruppe** (verschlüsselt mit dem Originalgruppen-Schlüssel), damit ein zweites Originalgerät die Freigabe auch dann bedienen kann, wenn Sync nur über die FRITZ!Box läuft. Die Box ist vertrauter passiver Teilnehmer aller Gruppen (siehe Security-Hinweis).
- FRITZ!Box hält nur das Original (eigene Gruppe), nie Fremdkopien.
- Ganze Feeds (keine Einzel-Posts). Whole-feed **Delete** existiert jetzt (Eigengruppe) + „Freigabe verlassen" für Fremdfeeds.

## 1. Datenmodell

### 1a. Freigaben als Feed-Metadaten (synct in der Originalgruppe)
Heute trägt der Feed-Op-Text `Name [\n::kalender::]` ([FeedMeta]). Wir erweitern die
Feed-Metadaten um eine **Freigabeliste** (nur in der Originalgruppe sichtbar/änderbar):

```
share:
  - group: "Familie Müller"     # Label, vom Fremdgerät beim Pairing angesagt
    capId:  "<zufällige Id>"     # identifiziert diese Freigabe/Gruppe
    rights: read|write|merge
  - ...
```
Vorschlag: als zusätzlicher, klar abgegrenzter Block im Feed-Op-Text (analog `::kalender::`),
oder sauberer als eigene Struktur, die als Feed-Op mitsynct (siehe „Offene Punkte").
Mitsynchronisiert werden `{capId, label, rights}` **plus `capSecret` und `K_F`**, beides — wie
alle Gruppendaten — mit dem **Originalgruppen-Schlüssel verschlüsselt**. So lernt jedes zweite
Originalgerät die Freigabe, auch wenn der Abgleich nur über die FRITZ!Box läuft.

> **Security-Hinweis (für die FRITZ!Box-Doku merken):** Damit liegen die Feed-Freigabe-Secrets
> (verschlüsselt mit dem Gruppen-Schlüssel) auch auf der FRITZ!Box. Die Box ist ein vertrauter,
> passiver Voll-Replik-Teilnehmer aller Gruppen; sie sieht nur mit dem Gruppen-Schlüssel
> verschlüsselte Daten. Wem das zu heikel ist, schaltet die FRITZ!Box-Replik ab — dann syncen
> nur die Geräte direkt. Dieser Trade-off gehört in die FRITZ!Box-Feature-Doku.

### 1b. Fremdfeed-Markierung (auf dem Fremdgerät)
Jeder gehaltene Fremdfeed wird lokal getaggt mit:
`{ feedId, originGroupName, capId, capSecret, feedKey, rights }`.
→ Discovery: ein Fremdgerät fragt einen Feed **nur** bei Peers an, deren angesagte Gruppe
== `originGroupName` ist (kein Broadcast aller Fremd-Requests an Fremde).

## 2. Krypto / Capability
**Vereinfachung beim Implementieren:** Ein separater Feed-Inhalts-Schlüssel `K_F` ist NICHT nötig.
Eine Fremdgruppe bekommt den Feed ohnehin nur über den **capSecret-verschlüsselten Direktkanal**
von einem Originalgerät (sie kann die FRITZ!Box nicht lesen) und speichert die Ops lokal wie
eigene Daten. Also reicht **eine Capability je Fremdgruppe**:
- `capId` (öffentlich, identifiziert die Freigabe/Gruppe) + `capSecret` (32 Zufalls-Bytes, base64).
- `capSecret` ist direkt der **AES-Kanal-Schlüssel** (`GroupCrypto.keyFromToken`) → Transport-
  verschlüsselung UND gegenseitige Auth (wer das capSecret hat, kommt rein; das Original kennt es
  pro `capId`).
- Im Feed-Op steht `capSecret` mit dem **Originalgruppen-Schlüssel verschlüsselt** (`encSecret`),
  damit es in der Originalgruppe (inkl. FRITZ!Box) synct, aber dort nur als Chiffrat liegt.
- Revocation: Originalgerät löscht die `capId` aus der Freigabeliste → bedient diese Capability
  nicht mehr (Altbestand bei der Fremdgruppe bleibt — siehe Bestätigt).

## 3. Pairing-Protokoll (einmaliger QR)
1. Eigentümer: Feed-Einstellungen → „Gruppe hinzufügen" → App erzeugt frische `capId/capSecret`,
   einen **pending grant** (read) und zeigt QR: `{ feedId, feedName, originGroup, capId, capSecret, K_F, hostIp:port, nonce }`.
2. Fremdgerät scannt → verbindet zu `hostIp:port` → sendet `{ capId, eigenerGruppenname, beweis(nonce, capSecret) }`.
3. Originalgerät verifiziert → schreibt Freigabe `{capId,label=Gruppenname,rights=read}` in die
   Feed-Metadaten (synct an die anderen Originalgeräte) → **schließt den QR-Screen automatisch**.
4. Fremdgerät speichert den Fremdfeed-Tag (1b) + initialen Sync.
5. **Abbruch** (Netzfehler / QR-Screen anders geschlossen / Crash) → pending grant wird verworfen.

## 4. Cross-Group-Sync
- Discovery wie heute (Beacon trägt Gruppennamen). Zusätzlich: trifft ein Gerät auf eine
  **fremde** Gruppe, prüft es, ob es Fremdfeeds mit `originGroupName == peer.group` hält
  bzw. ob es als Original Freigaben für `peer.group` hat.
- Eigener Kanal je Feed, verschlüsselt/authentifiziert per Capability statt per Gruppen-Schlüssel.
- Reconcile wie gehabt (gap-aware Versions-Vektor), aber **gefiltert auf den freigegebenen Feed**.
- **Push** nur, wenn `rights ∈ {write, merge}`; ein Merge-Op wird nur akzeptiert, wenn `rights == merge`.
- **Konflikt ohne merge-Recht:** Die Fremdgruppe zeigt ihren **lokalen letzten Stand** (Anzeige-Head,
  höchste Uhr) und blendet einen **Hinweis** ein „Upstream in der Originalgruppe ist noch ein
  Konflikt offen" — die Resolve-Aktion bleibt ausgeblendet, bis die Originalgruppe (oder eine
  Gruppe mit merge-Recht) ihn löst und das Merge-Ergebnis synct.
- **Konflikt mit merge-Recht:** Fremdgruppe darf wie die Originalgruppe auflösen.

## 5. Rechtedurchsetzung
- **Autoritativ** auf den Originalgeräten: prüfen `capId → rights` vor Bedienen (read) /
  Annehmen (write/merge). Ein lügendes Fremdgerät kommt nicht weiter, weil ohne passende
  Capability der Kanal-Handshake scheitert.
- **UI-Gating** auf dem Fremdgerät: erhält seine aktuellen Rechte beim Sync; read-only →
  jegliche Bearbeitung in der UI gesperrt.

## 6. UI
- **Übersicht:** geteilte Feeds bekommen ein kleines **Share-Icon**.
- **Eigentümer (Feed-Einstellungen):** Liste der Fremdgruppen mit `{Label, Rechte}`,
  Toggles write/merge, „Gruppe hinzufügen" (QR), „Zugriff entziehen".
- **Fremdgruppe:** Scan-UI; Anzeige „geteilt von <Gruppe>"; bei read-only keine Edit-Knöpfe;
  „Freigabe verlassen" (lokal entfernen).

## 7. Betroffener Code (Abschätzung)
- `data/CalendarEvent.kt`/Feed-Metadaten: Freigabeliste serialisieren.
- `data/FeedRepository.kt`: Freigaben lesen/schreiben; Fremdfeed-Tags; materialisierter Anzeige-Head bei read-only-Konflikt.
- `sync/Discovery.kt` + `SyncManager.kt`: Cross-Group-Erkennung, per-Feed-Kanal.
- `sync/PeerProtocol.kt` + `SecureChannel.kt`: Capability-Handshake (statt nur Gruppen-Schlüssel), feed-gefilterter Reconcile, Rechteprüfung.
- `crypto/GroupCrypto.kt`: K_F + Capability-Ableitung/Beweis.
- Neu: QR-Erzeugung + Scanner (Kamera-Permission + QR-Lib), Pairing-Service.
- UI: Feed-Einstellungen, Share-Icon, Scan-Screen, „Freigabe verlassen".
- Tests: Pairing-Roundtrip, Rechtedurchsetzung (read/write/merge), Konflikt-nur-Anzeige, Revocation.

## QR-Bibliotheken (Erzeugen + Scannen)
**Erzeugen** des QR ist trivial (ZXing-`core`, ein paar Zeilen, keine UI/Kamera nötig) — das ist
unstrittig. Die Wahl betrifft v. a. das **Scannen** (Kamera + Decoder):

| Option | Was | Verbreitung | Eignung hier |
|---|---|---|---|
| **ZXing core + eigene CameraX-Vorschau** | reine Decoder-Lib + selbst gebaute Kamera-UI | sehr hoch (De-facto-Standard, alt & stabil) | volle Kontrolle, aber wir bauen die Scan-UI selbst |
| **zxing-android-embedded** (journeyapps) | fertige Scan-Activity/-View um ZXing | sehr hoch, der Klassiker für „QR scannen" | **am einfachsten**, sofort fertige Scanner-UI, keine Play-Services nötig (gut fürs Sideloaden) |
| **ML Kit Barcode Scanning** (Google, gebündelt) | on-device ML-Decoder, mit CameraX | hoch, modern | sehr robust, aber größere App, du baust Kamera-UI selbst |
| **Google Code Scanner** (Play-Services ML Kit) | fertige System-Scan-UI, kein Kamera-Code | hoch | minimal Code, aber **braucht Google Play Services** — schlecht, wenn ein Gerät die nicht hat |

**Empfehlung:** **zxing-android-embedded** zum Scannen + **ZXing core** zum Erzeugen.
Gründe: keine Play-Services-Abhängigkeit (wichtig fürs Sideloaden/F101 etc.), fertige Scanner-UI,
minimaler eigener Code, riesige Verbreitung/Stabilität. ML Kit nur, falls du modernere Erkennung
willst und die App-Größe egal ist. Kamera-Permission (`CAMERA`) wird in jedem Fall nötig.

## Offene Punkte für die Review
1. **Freigabeliste-Speicherort** (siehe Erklärung in der Chat-Antwort): Variante A = die Freigaben
   in den bestehenden Feed-Op-Text packen (der heute nur „Name [\n::kalender::]" enthält) —
   wenig Code, aber der Feed-„Name"-Op trägt dann strukturierte Daten. Variante B = ein eigenes,
   sauber getrenntes Feld/Format im Feed-Op (klarer, aber Op-Format + Parser + Migration anpassen).
   Beide synchronisieren gleich gut. **Entscheidung offen.**
2. QR: **zxing-android-embedded + ZXing core** (Empfehlung oben) ok?
- QR-Ablauf: **2 Minuten** (entschieden), zusätzlich „pending grant verfällt bei Abbruch".
