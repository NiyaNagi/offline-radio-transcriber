# UI audit tooling

How to use WP0's debug scenario simulator (register R-110) and screenshot pipeline (register
R-111) — see [`spec/ui-conformance-plan.md`](../../spec/ui-conformance-plan.md) for the program
this serves, and the register at [`register.md`](register.md) for open findings.

## What this is

- `app/src/debug/**` — a debug-build-only `BroadcastReceiver`
  (`org.ort.app.debug.ScenarioReceiver`) that loads one of a fixed set of named scenarios
  (`Scenarios.kt`) into the app's real `:data` database through the real DAOs and entities, plus
  a debug-only launch alias for the reader (`ScenarioReaderActivity.kt` — see below).
- `tools/ui-audit/**` — Windows PowerShell 5.1 scripts that boot an AVD, install the app, fire a
  scenario, drive the UI to a named screen, and screenshot it to
  `results/ui-audit/<scenario>/<screen>.png`.

Everything here is debug-only: `ScenarioReceiver` and `ScenarioReaderActivity` are declared
`android:exported="true"` in `app/src/debug/AndroidManifest.xml`, which AGP merges into the debug
build variant only — neither exists in a release build.

## Environment

```
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
```

`adb` is `$env:ANDROID_HOME\platform-tools\adb.exe`; the emulator binary is
`$env:ANDROID_HOME\emulator\emulator.exe`. AVD `ort_audit` (Pixel 6, API 34, x86_64,
`google_apis`) already exists and is conventionally kept booted on port 5554 — do not kill it or
boot a second instance on that port. `.\tools\ui-audit\create-avd.ps1 -Name ort_audit_2` creates
an identical second AVD for port 5556; at most two AVDs run at once
(spec/ui-conformance-plan.md's own concurrency rule).

## The scripts

| Script | Does |
|---|---|
| `boot.ps1 -Avd <name> -Port <n>` | Boots the named AVD headless and waits for `sys.boot_completed`. |
| `create-avd.ps1 -Name <name>` | Creates a Pixel 6 / API 34 / x86_64 / google_apis AVD (`ort_audit_2` for port 5556). |
| `install.ps1 -Port <n>` | `:app:assembleDebug`, installs on `emulator-<n>`, grants `RECORD_AUDIO`/`POST_NOTIFICATIONS`. |
| `scenario.ps1 -Port <n> -Name <scenario>` | Force-stops the app, broadcasts the scenario, waits for the confirming logcat line, prints `session=<id>`. |
| `shoot.ps1 -Port <n> -Scenario <s> -Screen <name>` | `screencap -p` to a device file, then `adb pull` — to `results/ui-audit/<s>/<name>.png`. |
| `nav.ps1 -Port <n> -Screen <name>` | Replays the tap sequence for `<name>` from `screens.json`. |
| `run-set.ps1 -Port <n> -Set <V3\|V5>` | Runs every (scenario, screen) pair of one phase-E set from `sets.json`, end to end. |

## The usual sequence

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# once per session (skip if emulator-5554 is already up, per the standing rule above)
.\tools\ui-audit\boot.ps1 -Avd ort_audit -Port 5554

.\tools\ui-audit\install.ps1 -Port 5554

$out = .\tools\ui-audit\scenario.ps1 -Port 5554 -Name overnight
# $out's last line is "session=<id>" - or use run-set.ps1, which parses this for you.

$env:ANDROID_HOME\platform-tools\adb.exe -s emulator-5554 shell am start `
    -n org.ort.app/.debug.ScenarioReaderActivity --es session_id scenario-overnight

.\tools\ui-audit\nav.ps1 -Port 5554 -Screen log
.\tools\ui-audit\shoot.ps1 -Port 5554 -Scenario overnight -Screen log
```

Or, for a whole phase-E set at once:

```powershell
.\tools\ui-audit\run-set.ps1 -Port 5554 -Set V3
```

## Why a debug-only reader launch alias exists

`org.ort.app.ui.ReaderActivity` is `android:exported="false"` in the shipped manifest (register
R-007), so `adb shell am start -n org.ort.app/.ui.ReaderActivity --es session_id <id>` — the
command this program's own brief documents — fails with a `SecurityException` (found by actually
running it, not by inspection). `ReaderActivity`'s manifest entry is `app/src/main/AndroidManifest.xml`,
outside WP0's file ownership, so rather than widen it, `app/src/debug/kotlin/org/ort/app/debug/ScenarioReaderActivity.kt`
adds one debug-only forwarding activity that does nothing but relay the `session_id` extra into
`ReaderActivity` and finish. It is registered `exported="true"` only in
`app/src/debug/AndroidManifest.xml`, so it never exists in a release build:

```
adb shell am start -n org.ort.app/.debug.ScenarioReaderActivity --es session_id <id>
```

## Scenarios

Fired by `adb shell am broadcast -a org.ort.app.debug.SCENARIO --es name <scenario>` (or
`scenario.ps1`, which does exactly that plus the force-stop and log wait). Every scenario clears
every row a previous scenario wrote first (see `Scenarios.kt`'s own doc comment for exactly what
"clear" means) and resets the process-wide capture-facet singletons
(`CaptureState`/`AsrAvailability`/`VadAvailability`/`ShedStatus`/`ThermalStatus`/`RigStatus`/
`StorageForecast` — the last three added by WP11a, register R-104/R-105) before applying its own.

| Scenario | What it seeds |
|---|---|
| `empty` | No sessions at all. |
| `first-session` | One session started 3 minutes ago, no transmissions, capture marked running. |
| `overnight` | A real 6h42m, two-frequency session with ~42 overs exercising every `Rows.dc.html` variant: CONFIRMED, INFERRED (linked to its confirming over), AMBIGUOUS, UNKNOWN, a corrected row, a revised row (two transcript versions), a rejected row (retained), a first-heard station, a QSO thread (4 overs, shared `threadId`), a 38s capture gap, mixed signal strength, mixed retained audio, and lattice/candidate rows for the confirmed/inferred/ambiguous overs — including one cold-start and one negative prior, for `Detail-Why`. |
| `gap-call` | `overnight` plus a second capture gap, cause `CALL` (register R-106 — `CaptureGapCause.CALL` added by WP11a). |
| `unclean-end` | A heartbeat file that reads as an unclean end (never marked clean shutdown) for a prior session; this process is not capturing. |
| `os-stopped` | The same unclean-end heartbeat as `unclean-end`, plus the `CaptureGapCause.OS_STOPPED` gap `RealCaptureService` itself now persists on relaunch, from the last heartbeat to the moment of detection (F5, register R-106). Does not "reopen" the previous session — a policy the lead has not decided. |
| `pass-a-partial` | A transmission whose only transcript row is a current Pass A partial, `processingState = PROCESSING`. |
| `corrected` | A single transmission carrying the exact shape a one-tap correction produces, plus its `CorrectionEntity` audit row. |
| `no-audio` | A confirmed transmission with no retained-audio file at all. |
| `revisions` | A single transmission with two transcript versions, the older superseded. |
| `stations-14-nights` | Fourteen sessions over a fifteen-day span (two weeks plus the skipped night), realistic hour-of-day/day-of-week spread across the design canvas's ~9 callsigns, a within-night hatch gap on most nights, and one whole calendar day with no session at all. |
| `field-tier1` | A session with `deviceTier = "T1"` (see "Known gaps" — nothing renders this yet). |
| `search-corpus` | Fourteen transcripts mentioning "park activation" across three sessions ("nights"). |
| `backlog` | `ShedStatus` set to level 3 / backlog 112 (F8), capture marked running. |
| `model-missing` | `AsrAvailability.unavailable(...)` (F13); captured but untranscribed transmissions. |
| `storage-warn` | `StorageForecast.ThreeNightsLeft` (FR-STO-3, register R-105), capture genuinely still running — replaces this scenario's earlier misuse of F6's exhaustion failure string. |
| `thermal` | `ThermalStatus.Warm`, measured RTF 0.9 (F7, register R-104), the same shed level/backlog `backlog` sets. |
| `rig-lost` | `RigStatus.Stale` since 30 minutes ago, last known on 145.230/146.960 (F9, register R-104). |

### Known gaps (report to the lead, not fixed here)

- **`pass-a-partial` is representable in data, not yet rendered.** `TranscriptEntity.pass = A`
  with no `pass = B` row is exactly what a real in-flight Pass A produces, but the Log screen
  (register R-041) does not yet render a partial state at all — this scenario proves the data
  path is ready, not that the screen shows it.
- **`field-tier1` is representable, unread.** `SessionEntity.deviceTier` is a free `String?`; no
  screen renders it yet (register R-090, `Settings-Tier`/CF05 is a placeholder).

## `screens.json` and `sets.json`

`nav.ps1` reads named tap/wait/text/key sequences from `screens.json`, keyed by screen name — the
coordinates live there, not in the script, exactly so they can be re-pointed without touching
`nav.ps1` itself. They were captured from a real `uiautomator dump` of the built (unconformed)
reader on `emulator-5554` at 1080×2400 @420dpi (the `ort_audit` AVD's own resolution), scenario
`overnight`. **They will need re-pointing as WP1–WP11 land** — the register already names this
exact UI (drawer glyph, header, row layout) for replacement (R-003, R-004, R-010, R-040...); that
is expected, not a defect in this tooling.

Seeded screens: `now` (the landing screen after `ScenarioReaderActivity` launches), `drawer`,
`log`, `search`, `threads`, `stations`, `frequencies`, `detail` (via the first Log row).

`sets.json` maps a phase-E set name (`V3`, `V5`) to its (scenario, screens) pairs for
`run-set.ps1`. Only the sets whose scenarios and screens this package made reachable are
populated — see `run-set.ps1`'s own doc comment for why the rest are deliberately absent rather
than faked.

## Verified

A real run of `scenario.ps1 -Port 5554 -Name overnight` followed by
`nav.ps1 -Port 5554 -Screen log` and `shoot.ps1 -Port 5554 -Scenario overnight -Screen log` produced
[`overnight/log.png`](overnight/log.png) on 2026-09-08 — the *current*, unconformed Log screen
(two-column captions, no filter, no group headers), showing real fixture rows: CONFIRMED (solid
dot, no score), INFERRED (hollow ring, `0.70` score), real callsigns, times, frequencies and
signal strengths. This is expected — WP0 seeds data and drives adb; it does not touch a single
screen composable.
