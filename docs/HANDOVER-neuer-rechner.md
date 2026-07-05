# Übergabe: HomeShare auf einem zweiten Rechner einrichten

Dieses Dokument ist als **Prompt für eine KI (Claude Code o. ä.)** auf dem neuen Rechner
gedacht. Es beschreibt, wie das Projekt geklont und lauffähig gemacht wird und welche
Dateien **nicht über Git** kommen und deshalb von Hand mitgebracht werden müssen.

---

## Prompt für die KI auf dem neuen Rechner

> Ich richte das Android-Projekt **HomeShare** (Repo `BeardedSkunk/HomeShare`) auf einem
> zweiten Windows-Rechner ein. Ich bin derselbe GitHub-Nutzer wie auf dem Erstrechner und
> will weiterhin direkt pullen/pushen (kein Fork, kein Pull-Request-Workflow).
>
> Bitte richte das Projekt so ein:
>
> 1. **Klonen**: `git clone https://github.com/BeardedSkunk/HomeShare.git`
>    (oder per SSH, falls SSH-Key eingerichtet). Danach die relevanten Branches auschecken:
>    `main` und der Arbeitsbranch `feature/list-types`.
> 2. **Auth prüfen**: `git config user.name` / `user.email` setzen (Name „BeardedSkunk“,
>    Mail `22100243+BeardedSkunk@users.noreply.github.com`). Für HTTPS-Push wird ein GitHub-**Personal Access Token**
>    oder der Git Credential Manager benötigt; für SSH ein hinterlegter Key. Test: `git push --dry-run`.
> 3. **Android SDK**: Android Studio installieren (bringt JDK 21 als JBR mit). Danach
>    `local.properties` anlegen (wird von Android Studio i. d. R. automatisch erzeugt):
>    `sdk.dir=C:\\Users\\<NUTZER>\\AppData\\Local\\Android\\sdk`
> 4. **Toolchain-Check**: `gradle.properties` ist eingecheckt und pinnt
>    `org.gradle.java.home=C:\Program Files\Android\Android Studio\jbr`. Falls Android Studio
>    auf dem neuen Rechner woanders liegt, diesen Pfad in `gradle.properties` anpassen
>    **(lokal, nicht committen — oder projektlokal überschreiben)**. Ebenfalls gesetzt:
>    `android.builtInKotlin=false`, `android.newDsl=false`, `kotlin.incremental=false`
>    (letzteres wegen Virenscanner-Sperre auf Cache-Dateien).
> 5. **Bauen**: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug`
> 6. **MCP-Tooling** (siehe „Von Hand mitbringen“ unten): `.mcp.json` für den mobile-MCP-Server
>    anlegen und Serena als MCP-Server registrieren.
> 7. `F101` (`F10123070010615`) darf geflasht werden (die frühere „nicht flashen"-Sperre wurde am 2026-07-05 aufgehoben).
>
> Toolchain-Eckdaten: AGP 9.0.1, Gradle 9.1, Kotlin 2.3.20, JDK 21, minSdk 29. Alle Tests sind
> reine JVM-Unit-Tests: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest`.

---

## Was über Git kommt (nichts zu tun)

- Kompletter Quellcode inkl. `AndroidManifest.xml`, `gradle.properties`, Gradle-Wrapper.
- `CLAUDE.md` (Projekt-Kontext für Claude) — ist eingecheckt.
- `.serena/project.yml` + `.serena/.gitignore` — Serena-Projektkonfig (u. a. `line_ending: lf`).

## Was NICHT über Git kommt — von Hand mitbringen

| Datei / Sache | Ort | Warum wichtig |
|---|---|---|
| **`~/.android/debug.keystore`** | `C:\Users\<NUTZER>\.android\debug.keystore` | **Wichtigster Punkt.** Debug-Signatur. Ohne Kopie signiert der neue Rechner die APK mit einem anderen Debug-Key → die App lässt sich **nicht als Update** über die bestehende Installation auf den Geräten spielen (Signatur-Konflikt), sondern nur nach Deinstallation. Datei einfach rüberkopieren. |
| **`.mcp.json`** | Projektwurzel (git-ignored) | Registriert den mobile-MCP-Server. Inhalt: siehe unten. |
| **`.claude/settings.local.json`** | Projekt (nicht getrackt) | Permission-Allowlist + `enabledMcpjsonServers: ["mobile"]`. Optional, spart nur Berechtigungs-Rückfragen. |
| **Serena-Installation** | global (nicht im Repo) | Serena selbst ist ein separat installiertes MCP-Tool. Auf dem neuen Rechner installieren und als MCP-Server registrieren. Projektkonfig (`.serena/project.yml`) kommt über Git. |
| **Claude-Code-Projektspeicher** | `C:\Users\<NUTZER>\.claude\projects\D--AndroidProjekte-ClipSharing\memory\` | Der persistente Gedächtnis-Ordner von Claude (MEMORY.md + Einzeldateien). Rein lokal, synct nie. Nur rüberkopieren, wenn Claude auf dem neuen Rechner denselben Wissensstand haben soll. |
| `local.properties` | Projektwurzel | Maschinenlokaler SDK-Pfad. Android Studio erzeugt ihn neu — nicht kopieren, neu anlegen. |
| `.serena/cache/`, `.serena/project.local.yml` | Projekt | Lokaler Serena-Cache. Nicht kopieren, wird neu aufgebaut. |
| `build/`, `.gradle/`, `.idea/`, `*.apk` | Projekt | Build-Artefakte/IDE-State. Nicht kopieren. |

### Inhalt `.mcp.json`
```json
{
  "mcpServers": {
    "mobile": {
      "command": "cmd",
      "args": ["/c", "npx", "-y", "@mobilenext/mobile-mcp@latest"]
    }
  }
}
```

## Hinweis zum Pull/Push ohne Pull-Request
Weil du derselbe GitHub-Account und Eigentümer des Repos bist, brauchst du keinen Fork und
keinen PR: einfach auf `main` bzw. `feature/list-types` committen und `git push`. Voraussetzung
ist nur eine funktionierende Authentifizierung auf dem neuen Rechner (PAT via Credential Manager
oder SSH-Key). Solange kein Branch-Protection-Rule auf `main` gesetzt ist (aktuell keine), sind
direkte Pushes auf `main` möglich.
