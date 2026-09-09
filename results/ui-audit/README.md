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
| `install.ps1 -Port <n> [-Clear]` | `:app:assembleDebug`, installs on `emulator-<n>`, grants `RECORD_AUDIO`/`POST_NOTIFICATIONS`. `-Clear` runs `adb shell pm clear org.ort.app` right after install and re-grants both permissions — see "Known gaps / hygiene" below for why. |
| `scenario.ps1 -Port <n> -Name <scenario> [-NoRestart]` | Force-stops the app (unless `-NoRestart`), broadcasts the scenario, waits for the confirming logcat line, prints `session=<id>`, then a best-effort warning if a real (non-scenario) session is still in the app's own database (see "Known gaps / hygiene"). |
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

### Recovery toast recipes (register R-103, R-230, R-231)

`RecoveryAnnouncer.diff` (`app/src/main/kotlin/org/ort/app/ui/failures/RecoveryAnnouncer.kt`, WP11b's
file) fires a toast only on a real transition *into* the recovered state — the two-scenario,
`-NoRestart` recipe above is what makes that transition observable at all, but two of the toasts
had no scenario that ever reached the recovered half of the pair before register R-230/R-231:

- **"Radio reconnected"** needs `RigStatus.State.Stale` → `RigStatus.State.Connected`. `rig-lost` →
  `empty` never fires it (`empty` publishes no `RigStatus` at all, so the holder just resets to
  `Absent` and stays there — not the transition the toast is watching for). Use `rig-reconnected`
  instead — same descriptor and bands `rig-lost` itself uses, so the "same radio came back" story is
  honest:
  ```powershell
  .\tools\ui-audit\scenario.ps1 -Port 5554 -Name rig-lost
  $env:ANDROID_HOME\platform-tools\adb.exe -s emulator-5554 shell am start `
      -n org.ort.app/.debug.ScenarioReaderActivity --es session_id scenario-rig-lost
  .\tools\ui-audit\nav.ps1 -Port 5554 -Screen now
  .\tools\ui-audit\scenario.ps1 -Port 5554 -Name rig-reconnected -NoRestart
  # watch for "Radio reconnected", or shoot.ps1 a few seconds later.
  ```
- **"Storage back above the floor"** needs `StorageForecast.State.ThreeNightsLeft`/`OneNightLeft`/
  `AtFloor` → `StorageForecast.State.Fine`. No scenario published `Fine` at all before `storage-fine`
  existed, so this toast was unreachable no matter what preceded it:
  ```powershell
  .\tools\ui-audit\scenario.ps1 -Port 5554 -Name storage-warn
  $env:ANDROID_HOME\platform-tools\adb.exe -s emulator-5554 shell am start `
      -n org.ort.app/.debug.ScenarioReaderActivity --es session_id scenario-storage-warn
  .\tools\ui-audit\nav.ps1 -Port 5554 -Screen now
  .\tools\ui-audit\scenario.ps1 -Port 5554 -Name storage-fine -NoRestart
  # watch for "Storage back above the floor", or shoot.ps1 a few seconds later.
  ```

Both recipes are also proved directly (no emulator needed) in `ScenariosTest.kt`, by reading
`RigStatus.state`/`StorageForecast.state` off the real holders after two consecutive `Scenarios.load`
calls and feeding both snapshots straight into `RecoveryAnnouncer.diff` — the exact function
`FailureHost`'s own poll loop calls.

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
| `overnight` | A real 6h42m, two-frequency session with ~42 overs exercising every `Rows.dc.html` variant: CONFIRMED, INFERRED (linked to its confirming over), AMBIGUOUS, UNKNOWN, a corrected row, a revised row (two transcript versions), a rejected row (retained), a first-heard station, a QSO thread (4 overs, shared `threadId`), a 38s capture gap, mixed signal strength, mixed retained audio, and lattice/candidate rows for the confirmed/inferred/ambiguous overs — including one cold-start and one negative prior, for `Detail-Why`. The AMBIGUOUS over's own candidates are three, ranked and distinctly scored (`KE7QRS` 8.20 / `KE7QRF` 8.05 / `KE7QRZ` 7.90, register R-184), so `Detail-Correct-A`'s Tier A list has real rows to render, not one candidate filtered down to zero. Its CONFIRMED opener and its corrected INFERRED over both carry real, found (not hand-computed) `lattice_slot` char spans into their own phonetically spelled transcript text (register R-421), so `Detail-Confirmed`/`Detail-Ambiguous`'s transcript highlight has a real span to render; the AMBIGUOUS over's own candidate slots stay unselected (no winner to highlight — `AmbiguousCandidatesFixtureTest` already establishes why), feeding only `Detail-Why`'s per-slot list. Also seeds the configured-device state `Settings*` (CF01/CF02/CF03/CF06) reads on a clean install — a chosen, verified input, a radio choice (`RadioChoice.NONE` + 145.230 MHz by hand, honest since FR-RIG is unbuilt), a 60 GB storage budget, and every `ModelCatalog` entry "installed" on disk (register R-440 — a clean install genuinely had none of this before). |
| `gap-call` | `overnight` plus a second capture gap, cause `CALL` (register R-106 — `CaptureGapCause.CALL` added by WP11a). Now genuinely live (`SessionEntity.endedAt = null`, `CaptureState.Capturing`) with that CALL gap closed 90s before real "now" — the session's own *newest* gap, inside `FailureMapper`'s 5-minute recency window — so F15 (`Fail-Call.dc.html`)'s banner is actually reachable (register R-410); before this the gap was timed against the session's own start offset, hours before "now" by the time any validator or the tour looked, and the session had already ended, so `FailureMapper.isRecentCallGap`'s own `CaptureState.Capturing` check could never pass either. The ended-session Log row (`not listening · 52 s · incoming call`) is unaffected. |
| `overnight-live` | The same `overnight` fixture (including its R-421/R-440 additions above), but `SessionEntity.endedAt` is `null` and `CaptureState` is actually `capturing` on that id, with a fresh heartbeat (register R-171) — reaches the *populated, running* shape of `Main.dc.html` (N01) and `Capture-Status.dc.html` (N04) that neither `overnight` (populated but ended) nor `first-session` (running but empty) can reach on its own. |
| `unclean-end` | A heartbeat file that reads as an unclean end (never marked clean shutdown) for a prior session; this process is not capturing. |
| `os-stopped` | The same unclean-end heartbeat as `unclean-end`, plus the `CaptureGapCause.OS_STOPPED` gap `RealCaptureService` itself now persists on relaunch, from the last heartbeat to the moment of detection (F5, register R-106). Does not "reopen" the previous session — a policy the lead has not decided. |
| `pass-a-partial` | A transmission whose only transcript row is a current Pass A partial, `processingState = PROCESSING`. |
| `pass-failed` | R-153, F18 `Fail-Pass.dc.html`: a transmission whose Pass B is terminally `FAILED` (a real `work_queue_item` row, 3 attempts, `lastError = "out of memory in the decoder"`), `processingState = FAILED`, its Pass A partial kept as the current transcript, and retained audio. |
| `corrected` | A single transmission carrying the exact shape a one-tap correction produces, plus its `CorrectionEntity` audit row. |
| `no-audio` | A confirmed transmission with no retained-audio file at all. |
| `revisions` | A single transmission with two transcript versions, the older superseded. |
| `stations-14-nights` | Fourteen sessions over a fifteen-day span (two weeks plus the skipped night), realistic hour-of-day/day-of-week spread across the design canvas's ~9 callsigns, a within-night hatch gap on most nights, one whole calendar day with no session at all, (register R-184) one AMBIGUOUS over on the primary session with the same three ranked, distinctly-scored candidates `overnight`'s own AMBIGUOUS over carries (its own candidate slots also real per R-421, unselected — same reasoning as `overnight`'s own AMBIGUOUS over), and (register R-272) `WA7HJR` bound to two real voiceprint clusters (`voiceprint-wa7hjr-a`/`-b`), each with a real, non-fabricated `memberCount` matching the overs actually assigned to it — so `Station-Identity`'s Split screen has a genuine multi-voice case to render, not just the single-cluster empty state. Also seeds the same configured-device state `overnight` does (register R-440 — verified input, radio choice, 60 GB budget, every model installed). |
| `field-tier1` | A session with `deviceTier = "T1"`, twelve overs, each with a real, decodable retained-audio file at the exact path `FlacSegmentAudioProvider`/the real `ReprocessRunner` reads (register R-290 — before this fix, only the database rows existed, so `Improve-Running`/`Improve-Done` (R03/R04) crashed the app on the first item instead of ever completing). |
| `search-corpus` | Fourteen transcripts mentioning "park activation" across three sessions ("nights"). |
| `search-unavailable` | The same real corpus `search-corpus` seeds, plus a one-shot `DebugSearchOverride` (`SearchViewData.kt`, WP7's file) forced ahead of `SearchPolling.search`'s own free-text query (register — `Search-Unavailable.dc.html`, the amber `search-unavailable-banner` and the field's "Not applied" cue). The *first* free-text search after this scenario loads genuinely returns `textSearchUnavailable = true` through the real code path (the same `buildResult` branch the genuine fts5-missing exception handler also uses); the override is one-shot, so `Retry`'s own next call genuinely recovers to real results — never a sticky/simulated banner. A real SQL-level break of `transcript_fts`'s shadow tables was tried first and rejected: it survives `OrtDatabase.create()`'s own unconditional `ensureFtsIndex` rebuild step and instead breaks the fts5 vtable's *construction* (`vtable constructor failed: transcript_fts`), crashing every later `OrtDatabase.create()` call app-wide, not just Search — confirmed by running it, not assumed. A filters-only search (no free text) is unaffected either way, since it never touches `transcript_fts`. |
| `backlog` | `ShedStatus` set to level 3 / backlog 112 (F8), capture marked running. |
| `model-missing` | `AsrAvailability.unavailable(...)` (F13); captured but untranscribed transmissions. Explicitly uninstalls every `ModelCatalog` entry's fixture file (register R-440) — genuinely "0 of 4 assets" even after an earlier `overnight`/`overnight-live`/`stations-14-nights` load in the same process installed them all. |
| `storage-warn` | `StorageForecast.ThreeNightsLeft` (FR-STO-3, register R-105), capture genuinely still running — replaces this scenario's earlier misuse of F6's exhaustion failure string. |
| `storage-fine` | `StorageForecast.Fine` (register R-231) — the recovery half of `storage-warn`'s transition; see "Recovery toast recipes" below. |
| `thermal` | `ThermalStatus.Warm`, measured RTF 0.9 (F7, register R-104), the same shed level/backlog `backlog` sets. |
| `rig-lost` | `RigStatus.Stale` since 30 minutes ago, last known on 145.230/146.960 (F9, register R-104). |
| `rig-reconnected` | `RigStatus.Connected` (register R-230), the same descriptor/bands `rig-lost` uses — the recovery half of `rig-lost`'s transition; see "Recovery toast recipes" below. |
| `level-low` | `LevelStatus.Measured` peak −38 dBFS, floor −60 dBFS, no clip (F3, register R-112), capture genuinely still running. |
| `level-clip` | `LevelStatus.Measured` peak 0 dBFS, clipped, 12 clips in the last second (F3, register R-112). |
| `input-verified` | `InputStatus.Opened` with a USB descriptor, native 48 kHz, a recorded resampler identity, `routeVerified`/`routedDeviceMatches` both true (register R-113). |
| `input-mismatch` | `InputStatus.Mismatch` — built-in mic routed instead of the chosen USB device (F1, register R-113). Capture is **not** marked running — see "Known gaps" below. |
| `setup-verified` | Seeds `org.ort.app.setup`'s real `SharedPreferences` (through `SharedPreferencesSetupStore`, not a duplicated key set) so `SetupStateMachine.stepFor` lands at `SetupStep.READY` (S12) directly, its Level row already green (register R-227) — see "Reaching S07/S12" below. |
| `setup-level` | The same verified-input base as `setup-verified`, but `levelInBand`/`levelPeakDbfs` are left honestly unset so `stepFor` lands at `SetupStep.LEVEL` (S07) directly, from a cold launch (register R-264) — see "Reaching S07/S12" below. Also publishes a real `LevelStatus` (peak −14 dBFS, noise floor −58, no clipping) so S07's own meter has real bars/facts to render on a clean install instead of the honest-but-empty "no bars, noise —" state (register R-411) — independent of `levelInBand` itself, which stays unset on purpose so `stepFor` still resumes here. |
| `setup-radio` | The same verified-input/level/overnight base as `setup-verified`, but `radioChoice`/`manualFrequencyHz` are left honestly unset (explicitly cleared — `SharedPreferences` persist across scenario loads, unlike `:data`) so `stepFor` lands at `SetupStep.RADIO` (S09) directly, from a cold launch (register R-285) — see "Reaching S07/S09/S12" below. |
| `clock-dst` | F14 (`Fail-Clock.dc.html`) — `DebugFailureOverride` set to `FailurePresentation.Clock`. No runtime signal exists; see "Known gaps" below. |
| `usb-permission` | F16 (`Fail-Usb.dc.html`) — `DebugFailureOverride` set to `FailurePresentation.Usb`. No runtime signal exists. |
| `interrupted-pass` | F17 (`Fail-Interrupted.dc.html`) — `DebugFailureOverride` set to `FailurePresentation.Interrupted`. No runtime signal exists. |
| `reconcile` | F19 (`Fail-Reconcile.dc.html`) — `DebugFailureOverride` set to `FailurePresentation.Reconcile`, three records with no file and two files with no record. No runtime signal exists. |
| `migration-failed` | F20 (`Fail-Migration.dc.html`) — `DebugFailureOverride` set to `FailurePresentation.Migration`, one failed step (activity patterns) among three passed ones. No runtime signal exists. |
| `asset-swap` | F21 (`Fail-Asset-Swap.dc.html`) — `DebugFailureOverride` set to `FailurePresentation.AssetSwap`, an active and a staged lexicon. No runtime signal exists. |
| `calibration` | F22 (`Fail-Calibration.dc.html`) — `DebugFailureOverride` set to `FailurePresentation.Calibration`, a five-point reliability scatter. No runtime signal exists. |
| `lexicon-corrupt` | R-154, F12 (`Fail-Lexicon.dc.html`), FR-LEX-12/FR-LEX-30/FR-AST-2 — unlike every scenario above, this one is **not** a `DebugFailureOverride` stand-in: it seeds a real "previous" `lexicon_version` row (2026.08 · 1,104,208 records), then calls the *real* `org.ort.app.ui.data.ModelsController.installLexicon` against a genuinely corrupt bundled asset (`app/src/debug/assets/lexicon-corrupt/lexicon-2026.09.tsv` — a manifest declaring 1,122,410 records whose checksum matches neither the 2 data rows actually present nor their count), through the new `:lexicon` package `org.ort.lexicon.import` (`LexiconImportValidator`/`LexiconImportInstaller`). The genuine `LexiconImportResult.Rejected` this produces is stored in `org.ort.app.debug.LexiconCorruptScenario.lastResult` — see "Known gaps" below for why nothing renders it yet. |

### Reaching S07/S09/S12 (register R-227, R-264, R-285)

Before `setup-verified`, S07 (`Setup-Level.dc.html`), S09 (`Setup-Rig.dc.html`) and S12
(`Setup-Done.dc.html`) were unreachable on this AVD at all: `SetupStateMachine.stepFor` resumes at
`SetupStep.INPUT` until `SetupStore.inputVerified` is real, and the only way that becomes real is
S05's own 30 s raw-signal listen (`RealRouteCheck`) actually hearing something — which the AVD's
silent virtual mic never does. `setup-verified` itself also resolves `radioChoice` up front (`NONE`,
a manual frequency), so it alone never stops at S09 either — not even via S12's own Radio row,
since that row previously had no action at all once a choice already existed (R-285's own finding,
now fixed by the `Change` action `readyRowsFor`'s `radioRow` adds). `setup-verified`/`setup-level`/
`setup-radio` seed the real `SetupStore` preferences (not a fake) so setup's own state machine
resumes at S12/S07/S09 respectively, no `run-as`/manual `SharedPreferences` edit needed.

**R-264 (V7 accessibility pass) found the recipe below this line used to launch — `adb shell am
start -n org.ort.app/org.ort.app.ui.setup.SetupActivity` — throws a `SecurityException` on a real
device.** `SetupActivity` is `android:exported="false"` (`app/src/main/AndroidManifest.xml`; only
`MainActivity` may launch it, the same restriction `ReaderActivity` has and
`ScenarioReaderActivity.kt` exists to work around for the reader — see "Why a debug-only reader
launch alias exists" above). `MainActivity` **is** exported (it is the app's own launcher
activity) — launch that instead, and let its own real routing decision
(`SetupStateMachine.isComplete`/`stepFor`, the exact function `SetupActivity` itself calls) carry
you the rest of the way, exactly as a real cold launch would:

```powershell
$env:ANDROID_HOME\platform-tools\adb.exe -s emulator-5554 shell pm grant org.ort.app android.permission.RECORD_AUDIO
$env:ANDROID_HOME\platform-tools\adb.exe -s emulator-5554 shell pm grant org.ort.app android.permission.POST_NOTIFICATIONS

# S12 (Ready), Level row already green:
.\tools\ui-audit\scenario.ps1 -Port 5554 -Name setup-verified
$env:ANDROID_HOME\platform-tools\adb.exe -s emulator-5554 shell am start -n org.ort.app/.MainActivity

# S07 (Level), reached directly rather than via S12's own Fix row:
.\tools\ui-audit\scenario.ps1 -Port 5554 -Name setup-level
$env:ANDROID_HOME\platform-tools\adb.exe -s emulator-5554 shell am start -n org.ort.app/.MainActivity

# S09 (Rig), reached directly rather than via S12's own Radio row's new Change action:
.\tools\ui-audit\scenario.ps1 -Port 5554 -Name setup-radio
$env:ANDROID_HOME\platform-tools\adb.exe -s emulator-5554 shell am start -n org.ort.app/.MainActivity
```

**Why `setup-level` exists as its own scenario rather than an `EXTRA_STEP` recipe.**
`SetupActivity.EXTRA_STEP` (WP9 round 3) is real and still does exactly what its own doc comment
says — but only for a caller that can actually reach `SetupActivity` directly (WP3's
`ReaderNavigator`, an in-process caller, not an adb command), and **only when the store's own gate
allows it**: a requested step is honored only if its ordinal sits at or before
`SetupStateMachine.stepFor`'s own natural resume point — never a way to skip a verification the
guide requires (see `SetupActivity.kt`'s own doc comment for the exact rule). `MainActivity` itself
does not read or forward any `step` extra to `SetupActivity` today (confirmed by reading
`MainActivity.kt` before writing this — its own `route()` starts `SetupActivity` with no extras at
all), so there is no adb-reachable path to `EXTRA_STEP` at all right now; `setup-level` reaches S07
the honest way instead — by seeding a `SetupStore` snapshot whose own natural resume point already
*is* `LEVEL`, no override needed.

**The two OS permissions above are not part of what either scenario seeds** — `RECORD_AUDIO`/
`POST_NOTIFICATIONS` are live `PackageManager` state, not a `SetupStore` preference
(`SetupStateMachine.stepFor` reads both from a live `PermissionsState`, confirmed by reading it
before writing this) — grant them the same way `install.ps1` already does for every other scenario
that exercises a real screen.

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

### Known gaps / hygiene (report to the lead, not fixed here)

- **Scenario reloads never delete a real (non-scenario) capture session.**
  `Scenarios.clearPriorScenarioData` only ever deletes rows whose id starts with `scenario-` — a
  genuine session an earlier mis-tap started (`Now`'s own "Start capture", pressed by accident
  while poking around a scenario) or one the OS killed mid-capture is never touched by any scenario
  load from then on, and persists in the app's own database across every scenario switch on a
  shared AVD. Because it is real (`endedAt = null`, possibly a more recent `startedAt` than
  whatever the current scenario just seeded), it can outrank the scenario's own fixture session on
  `Now`. `install.ps1 -Clear` (`adb shell pm clear org.ort.app`, re-granting
  `RECORD_AUDIO`/`POST_NOTIFICATIONS` afterward) is the reliable fix — it wipes the app's entire
  on-device state, this stray session included; `scenario.ps1` also prints a best-effort warning
  when it can query the device's own database for one (via `adb shell run-as` + the device's own
  `sqlite3` binary, degrading to a printed reminder when either is unavailable — not verified
  against a live device this round, since both shared AVD ports were in use; a validator exercises
  both flags on the next pass).
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
- **`field-tier1` is now read, not just representable** — WP10 wired `Settings-Tier`/CF05 and
  `Improve`/`Improve-Select`/`Improve-Running` to `SessionEntity.deviceTier` end to end (register
  R-090/R-091). What was missing until register R-290's fix was retained audio: the real
  `ReprocessRunner` reaches this scenario's overs through `FlacSegmentAudioProvider`, which requires
  a real file at each transmission's `audioPath` — this scenario now writes one for every over (see
  the scenario table above), so `Improve-Running`/`Improve-Done` (R03/R04) are actually reachable,
  not just `Improve`/`Improve-Select` (R01/R02).
- **`lexicon-corrupt` is representable, unread — and, unlike every other row in this list, the
  underlying feature is now real, not just the fixture.** R-154's own register row previously said
  "no lexicon-import validator exists in `:app`/`:lexicon`/`:net` — F12 is an unbuilt feature, not a
  staging gap"; that validator now exists (`:lexicon`'s `org.ort.lexicon.import` package,
  `ModelsController.installLexicon` in `:app`). What is still missing is the **screen**:
  `Fail-Lexicon.dc.html` has no WP10 build, so nothing in `ui/screens/ModelsScreen.kt` reads
  `LexiconCorruptScenario.lastResult` (or the `LexiconImportViewState` an "Install from a file"
  gesture would produce) yet. `ModelsViewData.kt`'s `LexiconImportViewState`/`LexiconCheckViewRow`
  are exactly what such a screen needs — see that file's own kdoc for the shape.

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

## Screenshot tour (spec/ui-conformance-plan.md WP12)

`tools/ui-audit/tour.ps1` is the bulk, deterministic alternative to the manual
`scenario.ps1` → `nav.ps1` → `shoot.ps1` sequence above: one command produces every screenshot a
validator brief names, in one pass, without hand-driving `uiautomator`. It exists so the expensive
part of a validation pass — comparing each screen against its artboard — can happen off-device, in
parallel, and so `diff.py` can show which screens actually changed since the last run.

### How it works

`ScreenshotTourActivity` (`app/src/debug/kotlin/org/ort/app/debug/tour/ScreenshotTourActivity.kt`,
debug-build-only, `android:exported="true"` for the same adb-only reason `ScenarioReceiver` and
`ScenarioReaderActivity` already are) reads a JSON step list, and for each step: loads the step's
scenario through the real `Scenarios.load` — the same seeding `ScenarioReceiver` uses — then either
composes the real `OrtNavHost` *directly inside its own `setContent`* (a destination step) or
launches the real `SetupActivity` with its own, already-public `EXTRA_STEP` extra (a setup step),
waits for composition/polling to settle, captures a `Bitmap` via
`androidx.core.view.drawToBitmap`, and writes it to `<filesDir>/tour/<id>.png` plus one JSON line
to `<filesDir>/tour/manifest.json`. A step that throws — an unknown scenario, an unresolvable
destination, an unsupported `drillIn` key — is caught and recorded as one `error` line; the tour
never aborts early.

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

.\tools\ui-audit\install.ps1 -Port 5554
.\tools\ui-audit\tour.ps1 -Port 5554

# a subset only, by id glob:
.\tools\ui-audit\tour.ps1 -Port 5554 -Only "overnight/*"
```

`tour.ps1` pushes `tools/ui-audit/tour.json` to the device, launches the activity, polls the
on-device `<filesDir>/tour/manifest.json` (via `run-as`, since it is app-private storage) for its
trailing `{"done": true, ...}` line (15-minute hard cap), then stages the whole `tour/` directory out
to a world-readable `/sdcard` path with `run-as ... cp -r` and pulls it with a plain `adb pull` —
never `adb exec-out` redirected to a file (brief-common.md's own rule). Screenshots land at
`results/ui-audit/<scenario>/<screen>.png`, the same layout `shoot.ps1` already uses; the manifest
lands at `results/ui-audit/tour-manifest.json`.

### `tour.json`'s schema

```jsonc
{ "id": "overnight/N01-now", "scenario": "overnight", "destination": "NOW", "fontScale": 1.0 }
{ "id": "overnight/CF03-settings-storage", "scenario": "overnight", "destination": "SETTINGS",
  "drillIn": { "settingsScreen": "STORAGE" } }
{ "id": "setup-verified/S01-welcome", "setup": "S01", "scenario": "setup-verified" }
```

`id` must be unique and follows the existing `<scenario>/<screen>` naming. `destination` is a
`org.ort.app.ui.navigation.ReaderDestination` name (a `setup` step names a design-intent S-id
instead — see `SetupStepIds.kt` for the S-id → `SetupStep` table — never both). `drillIn` is an
optional object carried through verbatim from a validator brief's own vocabulary
(`transmissionId`/`stationId`/`frequencyHz`/`sessionId`/`settingsScreen`/`openLogFilter`) — **only
`settingsScreen` is honored**; see "What v1 cannot capture" below. `override` is a second scenario
name, loaded in-process after the destination has composed and settled — the recovery-toast/
transition shape (`rig-lost` → `rig-reconnected`, `storage-warn` → `storage-fine`). `fontScale`
(destination steps only — see below) and `waitMillis` are optional.

### What v1 cannot capture

**No taps, no sheets, no drill-ins.** `OrtNavHost`'s public surface is exactly `sessionId`,
`navigator` (`rememberReaderNavigator(initialDestination, initialSettingsScreen)`), and
`failureActions`; `ReaderNavigator`'s is `open`, `openSettings`, `openSetupInput`. Every drill-in id
— `NavHostNavState.openTransmissionId`/`openStationId`/`openFrequencyHz`/`openThreadId`/
`pendingLogFilter`/`openCaptureLevelMeter`/`pendingReviewSessionId` — is `private` inside
`OrtNavHost.kt` (WP3's file), populated only by a callback a real tap fires. That leaves v1 able to
reach only: the ten `ReaderDestination` roots, `Settings`'s nine sub-screens
(`initialSettingsScreen`), and `SetupActivity`'s `EXTRA_STEP` steps. It **cannot** reach: any
transmission detail (D01–D11, F04/F10/F11/F18's own detail states), a thread detail (T02/T03), a
station or frequency detail (ST02–04, FQ02–03), the `Log`/`Search` filter sheets open (L02, Q02),
any `Search` result/empty/unavailable state (Q03–Q05 — reaching them needs a submitted query, a
tap), the capture level-meter drill-in from `Capture` (N06, though `level-low`/`level-clip` scenarios
still reach a *close* approximation by landing `Capture` itself in a degraded-level state), or
`Earlier nights`' `Settings-Storage`-review seed. **This is the exact host-parameter gap reported to
the lead** — `OrtNavHost`/`NavHostNavState`/`ReaderNavigator` would need new, explicit
constructor/Intent parameters for each drill-in id, all inside `ui/navigation/**` (WP3's row, not
WP12's) — see this package's own report for the literal fields.

**Setup steps S05 (`VERIFY`) and S06 (`ROUTE_MISMATCH`) are excluded from `tour.json`.** Both are
reached, in the real app, only as the *live result* of S04's "Verify this input" action
(`SetupActivity.onStartVerify`/`onVerifyStateChanged`) — `EXTRA_STEP` can set `step` directly, but
never runs that check, so a forced `VERIFY`/`ROUTE_MISMATCH` step would render from whatever
`verifyState` happens to default to, not the real checked-or-mismatched content. **S10
(`RADIO_USB`) and S11 (`RADIO_VERIFIED`) are excluded for the same reason** — real USB attach /
rig verification, not a store gate a scenario can seed.

**Setup steps have no font-scale hook at all.** `fontScale` on a `setup` step is accepted by the
schema (silently ignored by `ScreenshotTourActivity` today) but never applied — `SetupActivity` is a
real, separate `Activity` this package only launches; it exposes no `CompositionLocalProvider`
seam the way composing `OrtNavHost` in place lets a destination step's `fontScale` actually work.
A future fix would add an `EXTRA_FONT_SCALE` to `SetupActivity` itself (`ui/setup/**`, not WP12's
row).

**No `Search` result ever renders**, `search-corpus`/`search-unavailable` steps only reach the
initial `Search` screen (`Q01`) — the query field starts empty and nothing submits it.

A validator still drives every one of the states above by hand, exactly as `results/ui-audit/README.md`'s
existing sections describe; the tour is additive, not a replacement for validation.

### `diff.py`

```powershell
python tools\ui-audit\diff.py --before main --after results\ui-audit --manifest results\ui-audit\tour-manifest.json
```

`--before` is either a directory (an earlier tour run pulled somewhere else) or a git ref, read via
`git show <ref>:<path>` per file. Each of the current manifest's `ok` steps lands in exactly one of
`new` / `missing` / `changed` / `unchanged`, decided by downsampling both images to a 4×4 grid of
per-cell mean RGB (the top 4% of rows — the OS status bar — excluded from every cell) and comparing
mean absolute per-channel difference against `--threshold` (default `6.0`) — a coarse perceptual
check, not a pixel-exact diff, since a live clock/elapsed header and PNG re-encoding are not
byte-identical run to run. Writes `results/ui-audit/tour-diff.md` alongside printing the four lists.
Uses Pillow if the interpreter running it has it; otherwise a small stdlib-only PNG decoder covers
the common case these screenshots are actually encoded as (non-interlaced 8-bit RGB/RGBA — what
`Bitmap.compress(PNG, ...)` produces) and fails loudly, never silently wrong, on anything else.
