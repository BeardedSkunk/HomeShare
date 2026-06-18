# Design: Feeds mit Fremdgruppen teilen (#10)

Status: **Entwurf zur Review** — noch nicht implementiert. Grundlage: die mit dem Nutzer
abgestimmten Entscheidungen (siehe unten „Bestätigt").

## Ziel
Ein Feed bleibt in seiner **Originalgruppe** beheimatet, kann aber für andere Gruppen
freigegeben werden — mit Rechten **lesen** (immer, Minimum), **schreiben** (opt-in),
**mergen** (opt-in). Konflikte löst nur die Originalgruppe; Fremdgruppen sehen sie nur.

## Bestätigte Entscheidungen
- Pairing per **einmaligem QR** (in Person, beide im selben WLAN), alles aus der App-UI.
- **Jede Fremdgruppe bekommt eine eigene Feed-Capability** (eigener Schlüssel) für denselben Originalfeed.
- Capability = Identität + Auth; angesagter Gruppenname ist nur Label.
- Rechte nach Pairing in der UI änderbar, werden **über das Netz** an die Fremdgruppe übermittelt (UI-Gating) und von Originalgeräten **autoritativ** durchgesetzt.
- Widerruf = ab jetzt keine neuen Updates mehr (Altbestand bleibt bei der Fremdgruppe).
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
oder sauberer als eigene Spalten/Tabelle, die als Feed-Op mitsynct. **Die Capability-Secrets
selbst stehen NICHT im syncbaren Klartext** (sie sind das Geheimnis) — sie liegen lokal
verschlüsselt je Originalgerät; synchronisiert wird nur `{capId, label, rights}` plus der
**verschlüsselte Feed-Schlüssel** (mit dem Gruppen-Schlüssel der Originalgruppe verschlüsselt),
damit alle Originalgeräte dieselbe Freigabe bedienen können.

### 1b. Fremdfeed-Markierung (auf dem Fremdgerät)
Jeder gehaltene Fremdfeed wird lokal getaggt mit:
`{ feedId, originGroupName, capId, capSecret, feedKey, rights }`.
→ Discovery: ein Fremdgerät fragt einen Feed **nur** bei Peers an, deren angesagte Gruppe
== `originGroupName` ist (kein Broadcast aller Fremd-Requests an Fremde).

## 2. Krypto / Capability
- **Feed-Inhalts-Schlüssel `K_F`** (symmetrisch, pro Feed): verschlüsselt die Ops dieses Feeds
  feed-spezifisch, damit eine Fremdgruppe sie lesen kann, **ohne** den Originalgruppen-Schlüssel.
- **Capability** je Fremdgruppe: `capId` (öffentlich, identifiziert) + `capSecret` (geheim).
  Dient als gegenseitiges Auth-Geheimnis für den Feed-Kanal (Challenge-Response), sodass
  - das Fremdgerät beweist „ich bin Inhaber dieser Freigabe" und
  - das Fremdgerät prüft, dass es wirklich mit der Originalgruppe redet.
- Revocation: Originalgerät löscht die `capId` aus der Freigabeliste → bedient diese Capability
  nicht mehr. (K_F-Rotation optional, ändert aber Altbestand nicht.)

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
- **Konflikt:** Fremdgruppe materialisiert wie heute, zeigt `conflicted`, aber die Resolve-Aktion
  ist ausgeblendet (read/write ohne merge). Es wird ein Anzeige-Head gewählt (höchste Uhr),
  bis der Konflikt upstream gelöst ist.

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

## Offene Punkte für die Review
- Freigabeliste **im Feed-Op-Text** (einfach, aber Text wird „technischer") **oder** eigene
  syncbare Struktur (sauberer, mehr Aufwand)?
- QR-Lib-Wahl (z. B. ZXing) + Kamera-Permission — ok?
- Reicht „pending grant verfällt bei Abbruch" so, oder zusätzlich Ablaufzeit für den QR?
