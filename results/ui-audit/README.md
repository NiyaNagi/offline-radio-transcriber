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
| `scenario.ps1 -Port <n> -Name <scenario> [-NoRestart]` | Force-stops the app (unless `-NoRestart`), broadcasts the scenario, waits for the confirming logcat line, prints `session=<id>`. |
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

### Watching a live transition (register R-179)

The sequence above force-stops the app before every broadcast, so nothing already on screen can
ever witness the scenario's own effect — the process reading it is a fresh launch every time. To
watch a running UI observe a real transition (a degraded banner clearing, a recovery toast, a poll
picking up a session that just started capturing), load the *first* scenario normally, launch the
reader, then broadcast the *second* scenario with `-NoRestart` so it lands in the same
already-running process instead of a new one:

```powershell
.\tools\ui-audit\scenario.ps1 -Port 5554 -Name thermal
$env:ANDROID_HOME\platform-tools\adb.exe -s emulator-5554 shell am start `
    -n org.ort.app/.debug.ScenarioReaderActivity --es session_id scenario-thermal
.\tools\ui-audit\nav.ps1 -Port 5554 -Screen now
# ... the reader is now open and polling on the thermal scenario ...
.\tools\ui-audit\scenario.ps1 -Port 5554 -Name empty -NoRestart
# watch the open UI, or shoot.ps1 a few seconds later, to see the banner clear / recover live.
```

**Verified (R-179): `ScenarioReceiver` updates the process-wide holders in-process, not out of
band.** It is a plain manifest-registered `BroadcastReceiver` (`app/src/debug/AndroidManifest.xml`)
with no IPC boundary of its own — when the broadcast (an explicit `-n` target, so it is delivered
even to a background process) lands in the app's already-running process, `onReceive` runs
`Scenarios.load(...)` in that same process, which calls `CaptureState`/`ThermalStatus`/
`RigStatus`/`LevelStatus`/`InputStatus`/... directly — the exact singleton instances the reader's
own poll loops (`ReaderPolling`, `LiveBarPolling`, `FailureHost`'s mapper) are already reading. This
was read from `ScenarioReceiver.kt`'s and `Scenarios.kt`'s source, not just inferred: there is no
second process, no serialization boundary, and no cache to go stale — the write and the read share
the same JVM heap. `-NoRestart` only changes whether Android delivers the broadcast to a fresh
process or the one already on screen; it does not change how the receiver updates state.

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
`StorageForecast` — the last three added by WP11a, register R-104/R-105 — and `LevelStatus`/
`InputStatus`, added by WP11c, register R-112/R-113) before applying its own.

| Scenario | What it seeds |
|---|---|
| `empty` | No sessions at all. |
| `first-session` | One session started 3 minutes ago, no transmissions, capture marked running. |
| `overnight` | A real 6h42m, two-frequency session with ~42 overs exercising every `Rows.dc.html` variant: CONFIRMED, INFERRED (linked to its confirming over), AMBIGUOUS, UNKNOWN, a corrected row, a revised row (two transcript versions), a rejected row (retained), a first-heard station, a QSO thread (4 overs, shared `threadId`), a 38s capture gap, mixed signal strength, mixed retained audio, and lattice/candidate rows for the confirmed/inferred/ambiguous overs — including one cold-start and one negative prior, for `Detail-Why`. |
| `gap-call` | `overnight` plus a second capture gap, cause `CALL` (register R-106 — `CaptureGapCause.CALL` added by WP11a). |
| `overnight-live` | The same `overnight` fixture, but `SessionEntity.endedAt` is `null` and `CaptureState` is actually `capturing` on that id, with a fresh heartbeat (register R-171) — reaches the *populated, running* shape of `Main.dc.html` (N01) and `Capture-Status.dc.html` (N04) that neither `overnight` (populated but ended) nor `first-session` (running but empty) can reach on its own. |
| `unclean-end` | A heartbeat file that reads as an unclean end (never marked clean shutdown) for a prior session; this process is not capturing. |
| `os-stopped` | The same unclean-end heartbeat as `unclean-end`, plus the `CaptureGapCause.OS_STOPPED` gap `RealCaptureService` itself now persists on relaunch, from the last heartbeat to the moment of detection (F5, register R-106). Does not "reopen" the previous session — a policy the lead has not decided. |
| `pass-a-partial` | A transmission whose only transcript row is a current Pass A partial, `processingState = PROCESSING`. |
| `pass-failed` | R-153, F18 `Fail-Pass.dc.html`: a transmission whose Pass B is terminally `FAILED` (a real `work_queue_item` row, 3 attempts, `lastError = "out of memory in the decoder"`), `processingState = FAILED`, its Pass A partial kept as the current transcript, and retained audio. |
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
| `level-low` | `LevelStatus.Measured` peak −38 dBFS, floor −60 dBFS, no clip (F3, register R-112), capture genuinely still running. |
| `level-clip` | `LevelStatus.Measured` peak 0 dBFS, clipped, 12 clips in the last second (F3, register R-112). |
| `input-verified` | `InputStatus.Opened` with a USB descriptor, native 48 kHz, a recorded resampler identity, `routeVerified`/`routedDeviceMatches` both true (register R-113). |
| `input-mismatch` | `InputStatus.Mismatch` — built-in mic routed instead of the chosen USB device (F1, register R-113). Capture is **not** marked running — see "Known gaps" below. |
| `clock-dst` | F14 (`Fail-Clock.dc.html`) — `DebugFailureOverride` set to `FailurePresentation.Clock`. No runtime signal exists; see "Known gaps" below. |
| `usb-permission` | F16 (`Fail-Usb.dc.html`) — `DebugFailureOverride` set to `FailurePresentation.Usb`. No runtime signal exists. |
| `interrupted-pass` | F17 (`Fail-Interrupted.dc.html`) — `DebugFailureOverride` set to `FailurePresentation.Interrupted`. No runtime signal exists. |
| `reconcile` | F19 (`Fail-Reconcile.dc.html`) — `DebugFailureOverride` set to `FailurePresentation.Reconcile`, three records with no file and two files with no record. No runtime signal exists. |
| `migration-failed` | F20 (`Fail-Migration.dc.html`) — `DebugFailureOverride` set to `FailurePresentation.Migration`, one failed step (activity patterns) among three passed ones. No runtime signal exists. |
| `asset-swap` | F21 (`Fail-Asset-Swap.dc.html`) — `DebugFailureOverride` set to `FailurePresentation.AssetSwap`, an active and a staged lexicon. No runtime signal exists. |
| `calibration` | F22 (`Fail-Calibration.dc.html`) — `DebugFailureOverride` set to `FailurePresentation.Calibration`, a five-point reliability scatter. No runtime signal exists. |

### WP11b's failure screens (register R-100/R-101/R-103)

`org.ort.app.ui.failures.FailureHost`, mounted in `ReaderActivity.kt` above `OrtNavHost`, maps
`CaptureState`/`InputStatus`/`LevelStatus`/`ThermalStatus`/`RigStatus`/`StorageForecast`/
`ShedStatus` plus the newest `CaptureGapEntity` to at most one failure to show
(`FailureMapper.map`, pure and unit-tested — `app/src/test/kotlin/org/ort/app/ui/failures/
FailureMapperTest.kt`). Nine of the seventeen F-ids this package owns have a real signal today and
need no scenario beyond the ones WP0/WP11a/WP11c already added above: **F1** `input-mismatch`,
**F2** any scenario that sets `InputStatus.Lost` (none yet seeds this directly — see "Known gaps"),
**F3** `level-low`/`level-clip`, **F5** `os-stopped`, **F6** `storage-warn` (warning stage) —
the hard-floor takeover needs `StorageForecast.AtFloor` *and* `CaptureState.Failed` together,
which nothing seeds directly either, **F7** `thermal`, **F8** `backlog`, **F9** `rig-lost`, **F15**
`gap-call` (though see "Known gaps" — it does not currently produce a *live* session, which F15's
own trigger requires). The seven scenarios this table's last block adds
(`clock-dst`/`usb-permission`/`interrupted-pass`/`reconcile`/`migration-failed`/`asset-swap`/
`calibration`) are the ones with no real signal at all — `org.ort.app.ui.failures.
DebugFailureOverride`'s own kdoc says precisely what each would need and from which package.

### Known gaps (report to the lead, not fixed here)

- **F2's disconnect banner and F6's hard-floor takeover have no scenario that seeds them
  directly.** Both are real, mapped signals (`InputStatus.State.Lost`,
  `StorageForecast.State.AtFloor` + `CaptureState.State.Failed`) — `FailureMapperTest` proves the
  mapping — but no existing scenario in this file sets either combination; the closest are
  `input-mismatch` (a different `InputStatus` state) and `storage-warn` (the earlier warning
  stage, not the floor). A future scenario for either is a small, additive change to this file,
  outside this package's remaining time.
- **`gap-call`'s `CaptureGapCause.CALL` gap does not currently trigger F15's banner from the
  emulator**, because `FailureMapper.map` requires `CaptureState.State.Capturing` for F15 (a
  banner about a call that just ended must not show on a session that has already ended), and
  `gap-call` (`OvernightScenario.kt`, outside this package's file ownership) sets `endedAt` on the
  session rather than marking it live. `FailureMapperTest`'s own unit tests prove the F15 mapping
  works given a live session; reaching it from the emulator needs either a new scenario or a small
  change to `gap-call` itself, both outside this package's row.
- **`input-mismatch` seeds the holder directly; it does not drive a real capture tick.** Like
  `thermal`/`rig-lost`/`backlog` before it, this scenario sets `InputStatus.mismatch(...)` on an
  idle process rather than running `RealCaptureService` end to end — a real capture tick would
  overwrite it with whatever the (non-existent, in the simulator) audio route actually reports.
  Capture is deliberately left marked idle for this one scenario specifically (unlike the others in
  this batch): `Fail-Route.dc.html` states "capture stopped in the same second" on a mismatch, so a
  scenario claiming both `Mismatch` and `CaptureState.isCapturing` would misrepresent the one thing
  this state exists to show.
- **F16's "grant USB permission again" recovery action has no real destination.** `:capture-android`
  exposes no `UsbManager`/`PendingIntent` permission-request call (FR-RIG unbuilt, register R-084);
  `FailureHost`'s `onRequestUsbPermission` stays a documented no-op until it does.
- **"Choose another input"/"Retry"/"Reconnect"/"Set the frequency by hand" have no destination this
  package can reach.** They would navigate into Setup or a rig-detail screen, both inside
  `OrtNavHost.kt` (WP3's file, not this package's row) or unbuilt (FR-RIG). `FailureHost`'s
  corresponding callbacks stay documented no-op stubs at their default; only
  `onOpenBatteryExemptionSettings`/`onOpenStorageSettings`/`onOpenRetentionSettings` reach a real
  platform surface (`Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`/
  `Settings.ACTION_INTERNAL_STORAGE_SETTINGS`, wired in `ReaderActivity.kt`).
- **Banners render as a fixed top-anchored overlay across every destination, not embedded inside
  each screen's own layout.** This package owns no individual screen's content file
  (`NowContent.kt`/`CaptureStatusContent.kt`/`LogContent.kt`/... belong to WP4/WP5/WP8), so an
  overlay `FailureHost` itself draws is the only placement achievable without editing a file
  outside this package's row. `F14`/`F17`'s cards render the same way for now, though the boards
  place them inline in a session-detail screen that does not exist yet.

- **`input-mismatch` seeds the holder directly; it does not drive a real capture tick.** Like
  `thermal`/`rig-lost`/`backlog` before it, this scenario sets `InputStatus.mismatch(...)` on an
  idle process rather than running `RealCaptureService` end to end — a real capture tick would
  overwrite it with whatever the (non-existent, in the simulator) audio route actually reports.
  Capture is deliberately left marked idle for this one scenario specifically (unlike the others in
  this batch): `Fail-Route.dc.html` states "capture stopped in the same second" on a mismatch, so a
  scenario claiming both `Mismatch` and `CaptureState.isCapturing` would misrepresent the one thing
  this state exists to show.
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

## The JVM unit-test gate (register R-129)

This validator's own V3 pass found `Log` crashing on entry (`LogContent.kt`'s `rememberSaveable`
with no `Saver` — register R-129, `register.md`) *only once a real `Activity` was involved*: every
JVM-side test up to that point composed the reader's screens through `createComposeRule()`, which
installs no real `SaveableStateRegistry`, so nothing on the JVM side ever hit the same crash a real
device did. `org.ort.app.ui.navigation.ReaderActivityDestinationSmokeTest` closes that gap — one
real `Activity`, launched and `recreate()`d, per destination and drill-in — but real `Activity`
launches this numerous are heavier than anything else in this suite: left inside the same JVM worker
`:app:testDebugUnitTest` reuses for its other ~900 tests, they could leave that worker's Compose
test environment unable to reach idle for whatever test happened to compose *next*, unrelated to the
code under test (see that class's own KDoc for the full account). `app/build.gradle.kts` now runs it
as its own Gradle task instead, `smokeTestDebugUnitTest` — the same classpath/test classes/JVM
argument providers as `testDebugUnitTest`, excluded from that task, forked into its own JVM worker.
Both tasks are part of `check`/`build`, so `./gradlew build` (this repo's own top-level gate command,
per `AGENTS.md`) still runs every one of this class's cases; a reviewer confirming R-129 stays fixed
(or checking this validator's own findings are covered) needs both:

```
./gradlew :app:testDebugUnitTest
./gradlew :app:smokeTestDebugUnitTest
```

## Verified

A real run of `scenario.ps1 -Port 5554 -Name overnight` followed by
`nav.ps1 -Port 5554 -Screen log` and `shoot.ps1 -Port 5554 -Scenario overnight -Screen log` produced
[`overnight/log.png`](overnight/log.png) on 2026-09-08 — the *current*, unconformed Log screen
(two-column captions, no filter, no group headers), showing real fixture rows: CONFIRMED (solid
dot, no score), INFERRED (hollow ring, `0.70` score), real callsigns, times, frequencies and
signal strengths. This is expected — WP0 seeds data and drives adb; it does not touch a single
screen composable.
