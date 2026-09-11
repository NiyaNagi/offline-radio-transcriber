# Capture modes, Bluetooth and bundled assets — the end-to-end program

Wire D33–D36 (functional spec §3) through every part of the app, prove every state on an
emulator against behavioural fakes, and hand the operator a hardware protocol for the states no
emulator can reach. This plan is the working contract for build-plan **P19, P20 and P21**; it sits
beside [`ui-conformance-plan.md`](ui-conformance-plan.md), whose roles, gate, tour and register
mechanics it reuses unchanged.

**Inputs.** Functional spec §7.1a (FR-CAP-8..13), FR-CAP-2b, CON-CAP-1 as amended, FR-RIG-13..19,
§9.1a, FR-AST-3/3a/3b, FR-DIG-3a/3b, AC-127..140, R17, R18; [`design/design-intent.md`](../design/design-intent.md)
§2, §3, §9, §11, §12, §13, §15 (the rows dated 2026-09-10); the artboards
`Setup-Mode`, `Setup-Bluetooth-Permission`, `Setup-Input`, `Setup-Rig`, `Setup-Rig-Transport`,
`Setup-Rig-Bluetooth`, `Setup-Rig-Verified`, `Setup-Done`, `Settings-Capture`, `Settings-Mode`,
`Settings-Assets`, `Settings-Tier`, `Settings-Rig`, `Capture-Status`, `Main-Room-Audio`,
`Session`, `Digest-Prose`, `Fail-Bluetooth-Audio`, `Flow-Mode`, `Flow-Setup`.
**Output.** An app in which each of the three modes completes onboarding, captures, records its
mode and route on the session, and can be changed later; a rig catalogue driven by descriptors
with the TH-D75A reachable over USB serial and Bluetooth SPP; every asset bundled and verified;
the LLM bundled, gated and disableable — with every claim above proven by a row in
[`results/e2e-audit/checklist.md`](../results/e2e-audit/checklist.md) reaching `closed`, and the
hardware-only rows carrying a written protocol and a recorded prediction in
[`results/e2e-audit/hardware-checklist.md`](../results/e2e-audit/hardware-checklist.md).

## Roles

As [`ui-conformance-plan.md`](ui-conformance-plan.md) § Roles: the **lead** (Fable) designs, plans,
briefs, files findings, merges and never writes product code; **builders** (Sonnet, one package
each, own worktree, strict TDD, gate green before reporting); **validators** (Sonnet, one scenario
set, one emulator port, file findings, fix nothing); **reviewers** (Sonnet, no device, compare a
slice of tour captures to artboards). The operator runs the hardware protocol.

## Constitution check for the whole program

I (every disclosure — room audio, Bluetooth degradation, the `generated` badge — is content, never
decoration; the LLM never emits a callsign, D5). II (every package test-first, every fake can
fail/hang/drop, every fix shown to discriminate; hardware rows carry a prediction). III (the
segmenter takes no mode; mode is a capture-side fact). IV (a Bluetooth drop, audio or control,
never stops capture silently; a mode change never lands mid-session). V (bundling removes the
first-run download; `:net` remains the only HTTP linker; the LLM runs on-device and nothing it
sees leaves the phone). VI (Bluetooth-sourced audio reported as its own source — FR-CAP-13).
VII (new modules enter the dependency graph before any code; `:capture-*` may never reach
`:llm-*`). VIII (every new screen has an artboard first; nothing closes without a capture).

## Phases

```
A  Artboards, inventory rows, this plan, the checklist ·············· Lead          done 2026-09-10
B  Scaffolding: modules in the graph before any code ················ WP0'          running
C  Build packages in dependency order (worktrees) ··················· Builders
D  Scenarios + tour steps for every new state ······················· WPI
E  Tour run → parallel capture review → device validation V8–V11 ···· Reviewers, Validators
F  Iterate: file → re-brief → re-run, until the checklist is closed · Lead + Builders
G  Hardware protocol handed over with predictions; close-out ········ Lead, Operator
```

## Phase C — work packages

Partitioned by **file ownership**. A builder that must touch a file outside its row stops and
reports; the lead re-partitions. Every row lists what it must build, what it must test first, and
what it ships as a fake.

| WP | Scope | Owns | Depends on |
|---|---|---|---|
| **WP0'** | Module scaffolding | `settings.gradle.kts`, `buildSrc/.../ModuleGraph.kt` (+ its tests), `llm-api/`, `llm-mediapipe/`, `rig-bluetooth/` skeletons, `gradle/libs.versions.toml` (mediapipe line), `pipeline/build.gradle.kts` and `app/build.gradle.kts` (new project edges only), `spec/technical-design.md` §2 | — |
| **WPA** | `:rig` core — the contract and the catalogue | `rig/src/**` | WP0' |
| **WPB** | Transports — USB serial and Bluetooth SPP | `rig-usb/src/**`, `rig-bluetooth/src/**`, their `build.gradle.kts`, the `usb-serial-for-android` line in `libs.versions.toml` | WPA's contract commit |
| **WPC1** | Mode types, session schema, Bluetooth audio route | new `core/src/main/kotlin/org/ort/core/capture/**`, `data/src/**` (SessionEntity v7 columns, migration 6→7, DAO, fixtures, tests), `capture-android/src/**` (Bluetooth SCO in `AndroidAudioIo`, `AudioDeviceKind`, `FakeAudioIo`, route verification) | WP0' |
| **WPC2** | Pipeline wiring — rig supervision and session facts | `pipeline/src/main/kotlin/org/ort/pipeline/capture/{RealCaptureService,InputStatus,RigStatus}.kt`, new `pipeline/.../rig/**`, `pipeline/.../CaptureStatus.kt` | WPA, WPB, WPC1 |
| **WPD** | Setup UI — S00, S02c, S04, S09, S09b, S10, S10b, S11, S12 | `app/src/main/kotlin/org/ort/app/ui/setup/**`, `app/.../permissions/**`, `app/src/main/AndroidManifest.xml` (permissions and USB intent filter only), `app/src/main/res/values/strings.xml` (setup strings) | WPA (catalogue), WPB (transport states), WPC1 (`CaptureMode`) |
| **WPE** | Settings UI and nav — CF02, CF04 screen, CF05, CF06, CF11; the routes to them | `app/.../ui/settings/**`, `app/.../ui/navigation/**`, `app/.../ui/data/SettingsViewData.kt`, `app/.../ui/screens/ModelsScreen.kt` | WPC1, WPC2, WPG's view-state commit, WPH's engine-state API |
| **WPF** | Status, Now, Session, Log mark, Digest prose, F23 | `app/.../ui/screens/{CaptureStatusScreen,CaptureStatusContent,NowContent,LogContent}.kt`, `app/.../ui/data/{CaptureStatusViewState,NowViewState,NowSummaryMapper,LiveBarPolling,LogViewData}.kt`, `app/.../ui/components/{LiveBar,Rows}.kt`, `app/.../ui/digest/**`, `app/.../ui/failures/**` | WPC1, WPC2, WPH's summary read API |
| **WPG** | Bundled assets — build-time fetch, first-launch verify, catalogue, storage accounting | new `buildSrc/.../FetchBundledAssetsTask.kt` and its wiring in `ort.android-app.gradle.kts`, `app/build.gradle.kts` (asset packaging), new `app/.../assets/BundledAssetInstaller.kt`, `app/.../ui/data/ModelsViewData.kt`, `app/.../OrtApplication.kt` (first-launch call), `pipeline/.../capture/StorageAccounting.kt`, `.gitignore`, `README.md` (the `HF_TOKEN` section), the CI workflow under `.github/workflows/` | WP0' |
| **WPH** | The LLM — contract, fake, MediaPipe engine, prose digest generator and gate | `llm-api/src/**`, `llm-mediapipe/src/**`, new `pipeline/src/main/kotlin/org/ort/pipeline/digest/**`; `data/src/**` **only** for the `prose_summary` table as migration 7→8, and only after WPC1 has merged (the lead says when) | WP0', WPC1 (for the v8 migration) |
| **WPI** | Debug scenarios and tour steps for every new state | `app/src/debug/**`, `tools/ui-audit/tour.json`, `tools/ui-audit/screens.json`, `results/ui-audit/README.md` | everything above |

### What each package builds — the binding detail

**WPA — `:rig`.** `RigModule` (FR-RIG-1, exactly the contract in functional spec §7.6),
`RigTransport` (open/close/write/read-lines with timeouts, a `Flow<TransportState>`), `RigDescriptor`
(§9.2's shape: `transports: [{kind, serial?, capabilities}]`, poll commands, response patterns,
push support for `AI` — see `docs/reference/th-d75a-cat.md` "AI gives push, not poll"), a loader
and **validator** (FR-RIG-11: an invalid descriptor falls back to the null module with a stated
error, never blocks), the **TH-D75A descriptor** with both transports declared and identical
capabilities (FR-RIG-14 — parity is a test, AC-133), the **generic ASCII CAT** descriptor
(frequency and mode only), the **null module** (FR-RIG-2), and the **catalogue** (FR-RIG-16..18:
generated from the descriptor set, always containing null and generic, exposing per-transport
capabilities for the picker). Descriptor import from a file (FR-RIG-19) validates through the same
validator. Format: JSON if `kotlinx-serialization` is already in the catalogue, else a small
hand parser — the builder decides and says which. Fakes: `FakeRigTransport` (scripted replies;
can hang, fail to open, drop mid-stream, return garbage) and `FakeRigModule`. **Contract-first
commit:** land the interfaces, the descriptor model and the fakes as the first commit and report
it, so WPB and WPC2 can branch from it before the descriptors and catalogue are finished.

**WPB — transports.** `UsbSerialTransport` over `usb-serial-for-android`'s CDC-ACM driver
(FR-RIG-3: VID/PID from the descriptor, permission request surfaced as a transport state — F16 —
never a crash), `BluetoothSppTransport` over `BluetoothSocket` RFCOMM (SPP UUID
`00001101-0000-1000-8000-00805F9B34FB`; `BLUETOOTH_CONNECT` absent → a state the UI can show,
never a `SecurityException` in capture), reconnect with the same backoff ladder capture uses
(FR-RIG-15 — a Bluetooth drop degrades exactly as a USB drop does). Both are `:rig`'s
`RigTransport`; both ship Android-side fakes. Robolectric tests for the state machine; the real
sockets are hardware rows H1–H3.

**WPC1 — mode plumbing.** `CaptureMode { LOCAL_MICROPHONE, USB_RADIO, BLUETOOTH_RADIO }`,
`AudioRouteKind`, `RouteProvenance` in `:core` (no Android). `SessionEntity` gains `captureMode`,
`audioRouteKind`, `audioRouteLabel`, `bluetoothProfile` (nullable — `HFP/mSBC`, `HFP/CVSD`),
`rigTransport` (nullable) — FR-CAP-13 — with migration 6→7 tested against the v6 fixture and every
earlier one (FR-AST-5). `AndroidAudioIo` gains the Bluetooth SCO route: `setCommunicationDevice`
on API ≥ 31, `startBluetoothSco` below, the negotiated profile/codec read back and reported through
the descriptor (FR-CAP-11), route verification treating `TYPE_BLUETOOTH_SCO` like any other
selected device (FR-CAP-3, never a silent fallback). `FakeAudioIo` can present a Bluetooth device,
negotiate a stated profile, and drop it mid-read.

**WPC2 — pipeline.** `RealCaptureService` records mode, route and rig transport on the session at
start (FR-CAP-13), refuses to change any of them mid-session (FR-CAP-12 — a change is written to
the store for the next session and the current session is untouched, AC-131), constructs the
chosen `RigModule` over the chosen transport, and feeds `RigStatus` from it (`Connected`/`Stale`/
`Absent` with the transport named); frequency provenance `rig` while connected, `manual` when the
operator overrides (FR-RIG-8/9), stale marking on a drop (FR-RIG-7/15), recovery announced.
`InputStatus` carries route kind and Bluetooth profile so the UI never re-derives them. A
Bluetooth *audio* drop produces `InputStatus.Lost` and a `CaptureGap` (FR-CAP-5, F23); a
Bluetooth *control* drop produces `RigStatus.Stale` and no gap (FR-RIG-15).

**WPD — setup.** `SetupStep.MODE` before `MICROPHONE`; `SetupStep.BLUETOOTH_PERMISSION` after
`MICROPHONE` in Bluetooth mode only; `SetupStep.RIG_TRANSPORT` and `RIG_BLUETOOTH`; the counter
`n of 8` per `design-intent.md` §2; `SetupStore` gains `captureMode`, `rigTransport`,
`rigBluetoothAddress`; the state machine presets `selectedInputId` and the rig choice from the mode
(FR-CAP-9) and every preset remains changeable in place — **write AC-130 first** (Bluetooth control
with wired audio reachable), because a picker built without it hard-couples the axes and passes
everything else. S04 renders the preset chip and the `RouteAdvisory` rows already built
(`4fed9de`). S09 is generated from `:rig`'s catalogue (FR-RIG-16, AC-134/135). S12 leads with the
Mode row. `MainActivity` re-entry with `EXTRA_STEP = MODE` from CF11.

**WPE — settings and nav.** CF02's Capture-mode row; CF11 (`Settings-Mode`) with the live-session
banner driven by `CaptureState`; CF06 real (`RigStatus` with transport, `Reconnect` and `Switch`
wired to WPC2); CF04 rendering WPG's `bundled`/`sizeBytes`/`tierEligible` fields and WPH's prose
toggle; CF05's LLM line; `OrtNavHost` routes for CF11 and the F23 actions. **Every screen takes a
view-state, never a `Context`** — the tour depends on it.

**WPF — status, now, session, log, digest, failures.** N04's Input/Radio sub-lines; N01b's chip
and live-bar `room` mark whenever the current session's mode is `LOCAL_MICROPHONE` (FR-CAP-3a:
persistent, on every screen carrying the live bar); DG04's Mode/Input/Rig-link fact rows from the
v7 columns (retiring R-450's deviation); the `bt audio` row mark on every over from a Bluetooth
session (FR-CAP-13); DG05's prose section from WPH's stored summaries (FR-DIG-6: visually
distinct, badged `generated`, attributed to its overs — FR-DIG-11); F23 in `ui/failures` mapped
from `InputStatus.Lost` with a Bluetooth route.

**WPG — bundled assets.** A `fetchBundledAssets` Gradle task that downloads each manifest entry
once into a cached directory, verifies it against the pinned sha256, and fails the build loudly on
a mismatch or a missing file; the manifest is the single source of truth (`ModelCatalog` reads it
too). Entries: Whisper `tiny.en` encoder/decoder/tokens, Silero VAD (the four current entries) and
**Gemma 3 1B int4** (`litert-community/Gemma3-1B-IT/gemma3-1b-it-int4.task`, sha256
`e3d981c01aeaaac69a84ffa0d4be13281b3176731063f1bea1c9fe6887bd9dee`, 554,661,243 bytes — the
repository is gated `auto`, so the task reads `HF_TOKEN` and fails with a one-line instruction when
it is absent; **never** silently skips the entry). `tiny.en-tokens.txt` has no published digest
(`ModelsViewData.kt` records why): the task pins the digest of the file it fetched the first time
into the manifest, marked `trust-on-first-fetch`, so every later build verifies against it.
Packaged under `app/src/main/assets/bundled/` (gitignored, generated). `BundledAssetInstaller`
runs on first launch: copies each asset to the exact path the locators read, verifies the sha256
again (FR-AST-3b — provenance is not integrity), writes the `.sha256` marker
`ModelAcquisition` writes, and reports a stated, recoverable state on failure (AC-137) — never
activates a corrupt file. `ModelsController.currentState` reports `bundled = true`, real
`sizeBytes`, and `tierEligible` from the tier detector; `StorageAccounting` excludes the bundled
directory from the retention budget (FR-AST-3a, AC-139). `:net`'s `ModelAcquisition` stays for
side-load and replacement (FR-AST-1) — do not delete it. Record the **installed size** under
`results/e2e-audit/installed-size.md` with the device (R18).

**WPH — the LLM.** `:llm-api`: `LlmEngine` (`load`, `generate(prompt, constraints)`, `release`,
`state: Flow<LlmState>`), `LlmRequest`/`LlmResult`, and **`CallsignShapeFilter`** — a post-filter
that rejects any output containing a callsign-shaped token not present in the supplied resolved
set (FR-DIG-4's fallback where the runtime has no grammar-constrained decoding; MediaPipe does
not). `FakeLlmEngine` can hang, fail to load, exceed a token budget, and — deliberately — emit a
callsign that was not supplied, so the filter test is real. `:llm-mediapipe`: `MediaPipeLlmEngine`
over `com.google.mediapipe:tasks-genai`'s `LlmInference`, loading the bundled `.task` file.
`:pipeline/digest`: `ProseDigestGenerator` (one summary per thread from resolved entities and the
thread's transcripts only — FR-DIG-3/4; stored with the over ids it came from — FR-DIG-11) and
`ProseDigestGate` (runs only when idle **and** charging **and** not capturing **and** tier 3
**and** enabled — FR-DIG-5, AC-87, AC-138; disabling releases memory — FR-DIG-3b, AC-140).
Persistence: `prose_summary` in `:data` as migration 7→8 after WPC1 merges. AC-140 (the
deterministic digest with the LLM disabled) is a test against the real `DigestPolling` path.

**WPI — scenarios and tour.** One scenario per new state, each seeding the real stores and
holders (never a UI stand-in): `mode-local-mic`, `mode-usb`, `mode-bluetooth`, `setup-mode`,
`setup-bt-permission`, `setup-rig-transport`, `setup-rig-bluetooth` (paired list from a fake
adapter), `rig-bt-connected`, `rig-bt-lost`, `bt-audio-session` (rows carrying the mark),
`bt-audio-dropped` (F23), `mode-change-pending` (CF11's banner), `assets-bundled`,
`asset-corrupt` (AC-137), `llm-enabled-prose`, `llm-disabled`, `tier0-llm-stored`. Tour steps for
every board in this plan's input list at font scale 1.0 and 2.0, plus `-end` frames for the long
ones; `SetupStepIds` gains `S00`, `S02c`, `S09b`, `S10b`. Steps the tour genuinely cannot reach
are named in `results/ui-audit/README.md`, not faked.

### Builder rules

Exactly [`ui-conformance-plan.md`](ui-conformance-plan.md) § Phase D's list — TDD, view-states not
`Context`, every artboard element findable by `testTag`, changelog entry before the commit, the
full gate pasted into the report (`build dependencyRules platformGuards`, then `-p buildSrc test`,
then `spec_check.py`, then `coverageMatrix` and `coverageMatrixCheck` separately), never
`git stash`, never `gradlew --stop`, `git reset --hard main` if the worktree started stale, commit
messages without double quotes. Plus two for this program: **a fake that cannot drop the link
tests nothing about FR-RIG-15 or FR-CAP-5**, and **no builder touches `results/e2e-audit/` or the
register** — report, and the lead files.

### Merge order

WP0' → WPA (contract commit) → { WPB, WPC1, WPG, WPH-api } → WPC2 → WPH (engine, generator, v8)
→ { WPD, WPE, WPF } → WPI → tour run 1. Follow-ups return to the same agent (context intact) after
`git merge --ff-only main`.

## Phase D/E — scenario sets and validation

| Set | Scenarios | Screens | Port |
|---|---|---|---|
| **V8 Setup, three lanes** | fresh-install ×3 (one per mode via `setup-mode` + taps), `setup-bt-permission` denied and granted, `setup-rig-transport`, `setup-rig-bluetooth` (connect, identify, verify, drop), the S04 preset override in each lane, AC-130 by hand | S00–S12, S02c, S09b, S10b, F23 | 5554 |
| **V9 Settings, assets, LLM** | `mode-change-pending`, CF11 in each mode, CF06 over both transports, `assets-bundled`, `asset-corrupt`, `llm-enabled-prose`, `llm-disabled`, `tier0-llm-stored` | CF02, CF04, CF05, CF06, CF11, F13, F21 | 5556 |
| **V10 Status, session, digest, failures** | `mode-local-mic` live (N01b mark on every destination), `bt-audio-session`, `bt-audio-dropped`, `rig-bt-lost` → `rig-reconnected`, DG04 for each mode, DG05 | N01b, N04, L01 (mark), DG04, DG05, F9, F23 | 5554 |
| **V11 Accessibility** | every new screen at 1.0 and 2.0, TalkBack dump per screen (`uiautomator dump` — the merged semantics tree is not evidence), greyscale | all new | 5556 |

The tour runs first (`tools/ui-audit/tour.ps1 -Port 5558`, when no gate is running) and four
reviewers take the PNG slices with lead-assigned bases **R-810, R-820, R-830, R-840**; validators
number from **R-850, R-860, R-870, R-880**. Findings go in
[`results/ui-audit/register.md`](../results/ui-audit/register.md)'s E2 section, one row each;
the lead files. A row reaches `closed` only on a capture or a device dump.

## Phase G — hardware and close

The operator runs [`results/e2e-audit/hardware-checklist.md`](../results/e2e-audit/hardware-checklist.md)
(H1–H13) — each row has steps, a prediction recorded before the test, and a result file path
under `results/e2e-audit/hardware/`. **H6 is the honest one**: it tests whether the TH-D75A itself
can be the phone's Bluetooth audio source, with the prediction that stock Android will not offer it
(both ends are HFP audio gateways). Whatever H6 finds is written into the spec as a note under
FR-CAP-11, not left implicit.

Close when: every non-hardware row in the checklist is `closed`; every hardware row has a
protocol and a prediction (and a result where the operator has run it); `design-intent.md`'s
2026-09-10 rows read `drawn + built`; the installed size is recorded; the CHANGELOG has one entry
per merged package plus a close-out entry; memory updated.

### Phase G record — closed 2026-09-11

**Built and merged:** WP0', WPA, WPB, WPC1, WPC2, WPC3, WPD, WPE, WPF, WPG, WPH, WPI — thirty-odd
rounds in all, each merged to `main` behind a full gate (`build dependencyRules platformGuards`,
`coverageMatrix`/`coverageMatrixCheck`, `spec_check.py`); coverage 241 of 450 requirement ids.
The last product merge is `e5b084ec`; the last gate with every asset present and no escape hatch
is green on `ee97a720`.

**Evidence:** screenshot tour runs 1–7 — run 2 was void (the tour settled on wall-clock time,
R-803) and the settle was rebuilt on observed state; run 5 (207/207, apk `686212f`) was the full
closing tour, runs 6–7 targeted re-captures (20/20, 25/25); the manifest stands at 219/219.
Capture reviewers A–D, A2–D2, A3/B3/D3, A4/B4/C3/D4, E1, E2 filed and confirmed register rows
R-810..R-985; validators V8 (setup, three lanes), V9 (settings, assets, LLM), V10 (status,
session, digest, failures) and V11 (accessibility at 2.0, semantics dumps, greyscale) ran on real
emulators with taps. Register section E2: 109 rows — 87 closed on evidence, 4 rejected with a
stated reason (R-867 API-34 non-linear font scaling; R-901 the Bluetooth mode's cabled preset;
R-952 the tier card until P11's detector; R-972 an OCR slip), 2 handed to hardware (R-884/R-946
renderer ghost → H14), R-801 the pre-existing guide gaps, and R-985 a non-blocking process note.
Checklist: 104 rows — 90 closed, 14 hardware.

**Found on the way, fixed:** the heartbeat file read after its existence check (E2-A08a); a
smoke test anchored to the wall clock across midnight (E2-A08b); every Room migration lacking the
driver-native `migrate(SQLiteConnection)` the real open path calls — an upgrade crash the legacy
test helper never exercised (R-885, hardware H15); the live bar keyed on the raw session id
(R-910); a double live-bar reservation in the nav host (R-957); two tests and a 2 g daemon heap
that assumed the no-token build (R-807); a scenario whose seeded level envelope fell outside the
drawn window (R-944); layout tests at 360 dp for a 390 dp device (now test-plan §7.5).

**Installed size, measured:** APK 611,029,854 B; 583 M code + 629 M data on the device with all
five assets (`results/e2e-audit/installed-size.md`); FR-AST-3a's packs TODO stays open with that
cost recorded (the LLM is 84 % of the bundled bytes).

**Open by design, for the operator:** hardware H1–H15 (H6 tests the D75A-as-audio-source
prediction; H15 the upgrade over an older install); the CI half of E2-K08 — `main` is unpushed;
`HF_TOKEN` is set locally and as the repository secret. Deferred with reasons recorded:
`usb-serial-for-android` 3.11 (needs compileSdk 35), the TH-D75A descriptor's `MODE` and USB
vid/pid until H1, the `20m`-shaped false positive in `CallsignShapeFilter`.

## Concurrency and safety

As [`ui-conformance-plan.md`](ui-conformance-plan.md) § Concurrency and safety, plus: the
build-time fetch needs network and `HF_TOKEN` — builders WPG and WPH must never commit a fetched
asset or the token; the emulator has no Bluetooth, so every Bluetooth state on an AVD is a fake
transport or a fake audio device, and the report says so; nothing in a scenario is a real
callsign tied to a real person.
