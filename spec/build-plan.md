# Build Plan — session by session

**Draft 1.1 · September 2026 · The working todo list. Update it as you go.**

**Eleven sessions, grouped into four waves.** Each builds a coherent set of modules test-first
and ends with them **green, fake-backed and independently testable**.

The waves exist for one reason: **everything inside a wave touches disjoint files**. Order
within a wave is free, nothing in it conflicts with anything else in it, and each unit can be
verified on its own. A wave starts when the one above it is green — under strict TDD a module
with no fake blocks everything above it, so the fake is part of the deliverable, not a follow-up.

Each prompt names what to read, the files it **owns**, the files it must **not touch**, the
tests to write **before** the code, and how to know it is done.

**Conventions.** Tests are named for the requirement or criterion they establish. A unit is done
when `./gradlew :module:test` is green in CI, its fakes exist in `:testing`, and the definition
of done in [`test-plan.md`](test-plan.md) §5 holds.

---

## Progress

Eleven units, grouped into **waves**. Everything inside a wave touches **disjoint files** and
can be built and tested without the others — so order within a wave is free, and two units in
the same wave will never conflict. A wave starts when the one above it is green.

Full prompts are in [§ The prompts](#the-prompts). The phase detail further down is the
underlying breakdown each prompt draws on.

**Wave A — foundations. No dependencies. Either order.**

- [x] **P1 · Foundation** — Gradle, CI, spec checks, `:core`, `:testing` *(Kotlin)* — done 2026-09-07, commit `5fca369`
- [x] **P2 · Corpus pipeline** — manifest, folds, acquisition, metrics *(Python, `corpus/`)* — done 2026-09-07

**Wave B — four independent modules. Each needs only Wave A.**

- [x] **P3 · Lexicon: grammar** — units, ITU trie, FSA, confusion scoring *(`:lexicon`)* — done 2026-09-07
- [x] **P4 · Capture and segmentation** — source, resampler, ring, VAD *(`:capture-api`, `:segment`)* — done 2026-09-07
- [x] **P5 · Data layer and queue** — Room, FTS5, migrations, lifecycle *(`:data`)* — done 2026-09-07; audit F-016 (2026-09-07) added `WorkQueue.requeueFailed` so terminally `FAILED` items get a path back to `READY` (FR-RUN-9, FR-REP-8); audit F-027 (2026-09-07) named FR-ASR-7, FR-AST-5/6/8, FR-RUN-2, FR-STO-1, FR-LEX-12 and NFR-4b in the coverage matrix (renamed/added tests, no production change); FR-STO-2, FR-STO-2a and CON-STO-1 stay genuinely uncovered — `:data` stores `audioFormat` as an unvalidated string, it does not choose or enforce a codec — see CHANGELOG
- [x] **P6 · Synthetic corpus and the two probes** — channel, generator, **R4**, **R1** *(`corpus/`)* — done 2026-09-07; both probes NOT RUN (no Fearless Steps / no LoRA+sherpa-onnx toolchain or GPU in this environment) — see `results/r4-speaker-separation.md`, `results/r1-lora-export.md`
- [x] **P5 · Data layer and queue** — Room, FTS5, migrations, lifecycle *(`:data`)* — done 2026-09-07
- [x] **P6 · Synthetic corpus and the two probes** — channel, generator, **R4**, **R1** *(`corpus/`)* — done 2026-09-07; both probes NOT RUN (no Fearless Steps / no LoRA+sherpa-onnx toolchain or GPU in this environment) — see `results/r4-speaker-separation.md`, `results/r1-lora-export.md`. **FR-TST-6 (audit F-027, 2026-09-07): the D22 callsign generator built here is not the FR-TST-6 load/endurance synthetic traffic generator (activity fraction, transmission length distribution, SNR) — that is unbuilt, not just untested.**

**Wave C — composition.**

- [x] **P7 · Lexicon: ranking, calibration, harness** *(`:lexicon`, `:eval`)* — done 2026-09-07
- [x] **P8 · Android capture spine** *(`:capture-android`, `:pipeline`, `:app`)* — done 2026-09-07;
  audit F-010 (2026-09-07): AC-3 was previously covered only by `RingBuffer`, which nothing in
  `src/main` constructs. `AudioRecordSource` now detects a stalled downstream collector or
  `AudioRecord` shortfall directly (elapsed wall time vs. samples delivered) and reports it through
  the existing `CaptureEvent.Interrupted`/`Resumed` pair, closed immediately by `GapTracker` — AC-3
  now holds on the real capture path. `RingBuffer` is unchanged and still unused in production.
  audit F-021 (2026-09-07): shed events were memory-only in `ShedController` with no `shed_event`
  table — the `:data` half is now fixed (`ShedEventEntity`/`ShedEventDao`, schema v2, migration).
  audit F-007 (2026-09-07): `ShedController` was never constructed in `RealCaptureService` at all —
  it only ever ran (against a fake) in `:app`'s status display — so FR-RUN-3's shed order could
  never trigger and F-021's persist call had nothing to be called from. `RealCaptureService` now
  ticks a real `ShedController` backed by the new `AndroidShedSignals` every 10 s, persists each
  transition via `ShedEventPersister` into F-021's table, republishes level+backlog through the new
  `ShedStatus` holder, and stops capture loudly at a documented free-storage floor (FR-STO-4). The
  `:app` display side (F-002: still reads its own fake-fed `ShedController`) is not done here.
  audit F-002 (2026-09-07): `:app`'s status surface now reads the real level/backlog from
  `ShedStatus` (published by F-007's `RealCaptureService`) instead of an inert, always-nominal
  local `ShedController`, and shows an explicit "Not measured" label — never a fabricated `0` —
  before capture has ever started this process. The dead v0 `StatusActivity`/
  `TransmissionListActivity` (nothing launched either once `OrtNavHost` took over, P13/P14) are
  deleted, removing the second copy of the same bug.
- [x] **P7 · Lexicon: ranking, calibration, harness** *(`:lexicon`, `:eval`)* — done 2026-09-07;
  audit F-027 (2026-09-07) named/added tests for FR-LEX-6/11(partial)/14/18/19/20/21/23/29,
  FR-TST-2/4/5, FR-A11Y-6, NFR-1a/1c, AC-57 — FR-LEX-15/16/22/32 and AC-13/35 remain genuinely
  unbuilt or unmeasurable here, see CHANGELOG
- [x] **P8 · Android capture spine** *(`:capture-android`, `:pipeline`, `:app`)* — done 2026-09-07

**Wave D — the M2 gate, then transcription.**

- [ ] **P9 · GATE: 8-hour run on the reference device** *(device, mostly manual)*
- [x] **P10 · ASR and hallucination control** *(`:onnx`, `:asr-api`, `:asr-sherpa`)* — done 2026-09-07;
  AC-7 and AC-8 pass for real against a `FakeAsrEngine`; AC-6 does NOT pass for real — no
  development noise tape exists yet (Q2/Q16) and no real ONNX ASR model was available in this
  sandbox, so only the six-control *mechanism* was proven against synthetic noise-shaped decodes
  (`SyntheticNoiseGateMechanismTest`, explicitly not claimed as AC-6) — see CHANGELOG.md
- [ ] **P11 · End-to-end, then the M4 fork** *(`:pipeline`, decision gate)* — M3 partial
  2026-09-07: the real text-derived wiring (`PassB`, `CallsignResolver`, the `UnitSpotter`
  interface) is built and tested end to end against fakes; AC-73/AC-75 have a genuine
  *mechanism* (`LatencyRecorder`, `BacklogGrowthMonitor`) but no real device measurement; M3's
  dev-fold callsign precision/recall is NOT MEASURED (no labelled dev-fold occurrences exist,
  same gap P7 hit); the M4 fork decision is explicitly left OPEN — no real KWS/encoder-similarity
  implementation or measured 3-way comparison was possible in this sandbox. See CHANGELOG.md.
  audit F-027 `:pipeline` slice (2026-09-07): named tests for FR-RUN-1, FR-RUN-9, AC-29, NFR-2b
  (mechanism only, no device number) and NFR-4a; FR-RUN-4 (reprocess-candidate marking on shed)
  is genuinely unbuilt and FR-RUN-6's "warn before exhaustion" half remains unbuilt — see
  CHANGELOG.

**Yours, not a session:** record the validation hour (Q2) and pilot the labelling protocol (Q16).
Do it any time after P2; P6's probes and P7's harness both get better once it exists. **The
protocol itself is now drafted** — [`docs/reference/labelling-protocol.md`](../docs/reference/labelling-protocol.md) —
so this is down to recording, then labelling session one under it and amending it per its own
"open items for the pilot round" section.

**Yours, not a session — R4's corpus.** Fearless Steps needs a human registration step no
session can do unattended (challenge sign-up + data-portal request, or a full-corpus request
form / email to CRSS at UT Dallas) — see
[`docs/reference/fearless-steps-acquisition.md`](../docs/reference/fearless-steps-acquisition.md)
for the exact steps. Start with the CC-licensed 100-hour Challenge Corpus's SD (speaker
diarization) track — it's what R4 actually needs, not the full ~19,000-hour archive. Once it's
on disk, a session can wire it into `corpus/src/corpus/probes/speaker_separation.py`'s
`probe_r4` and install a real WeSpeaker/3D-Speaker embedder to get R4's actual verdict — see
`results/r4-speaker-separation.md` for what's blocked pending this.

**Audit 2026-09-07/08.** `results/audit-2026-09-07.md` is the register: 28 findings, 27 closed
(the 28th, the FLAC codec's device cost, waits for P9). It corrected several notes above in place
(look for "audit F-0NN"). Two new CI gates exist because of it: `coverageMatrixCheck` and
`platformGuards`. Coverage went 101 → 179 of 419 ids. Nothing has run on a device; P9 is still the
gate that matters. **audit F-029 (2026-09-08):** the regenerated matrix itself had a hygiene bug —
5 false "orphan tests" (`F-005`/`F-011`/`F-022`/`F-028`, plus one fictitious-id test fixture) from
a too-strict cross-reference regex and a `CoverageMatrixTest` fixture that read as a fake
declaration once `buildSrc/src/test/kotlin` became a scanned root (F-027). Fixed in `buildSrc`;
header now reads 0, no requirement counts changed.

**Wave E — make it an app.** *Added 2026-09-08, after the first real on-device test of the v0
smoke build. Waves A–D produced eleven green modules and a debug APK that starts a real
foreground capture service — and a user who installed it correctly observed "I see no UI." That
is not a missed phase: **the plan through Wave D deliberately covered M0–M4 only**, and every
user-facing screen lives in M5, parked below as "deliberately not decomposed." This wave
decomposes it, and first fixes the three defects that on-device testing surfaced — none of which
any test suite here could have caught, because all three live in the glue between modules that
are individually green.*

- [ ] **P12 · Make capture actually work end to end on a device** *(`:capture-android`,
  `:pipeline`, `:app`)* — the three on-device defects, then the processing loop that turns a
  captured segment into a transcript. **This is the one that makes the app do its job at all.**
  Defects 1 and 2 fixed 2026-09-08 morning. Defect 3 (the drain loop) and the VAD question fixed
  2026-09-08 afternoon: `PassDrainRunner`/`PassB`/`RealSherpaDecoder` are now constructed and wired
  into `RealCaptureService`, proven against `FakeAsrEngine` on Robolectric; a real Silero VAD
  binding was located and wrapped (`RealSileroVad`), gated on a model file P18 now fetches.
  Audit F-013 fixed 2026-09-07: `PassBFactory`'s `PassFingerprint` had shipped with the
  `configHash = "v0-smoke"` / `provider = "cpu"` placeholders from that morning's defect-3 work
  left in permanently; both are now derived for real (`PassBFingerprintBuilder`, a real provider
  threaded from `RealAsrEngineProvider`) — see CHANGELOG.md.
  **Still not checked off**: not verified on a real device (none available), and no ASR/VAD model
  was actually run for real in this session — see CHANGELOG for exactly what remains.
  Audit F-001 (2026-09-07) fixed `RealSegmentSink` fabricating `startedAtUtc`/`endedAtUtc`/
  `monotonicStartNanos`/`utcOffsetMinutes` as `0L`/`null`/`0L`/`0` on every real transmission;
  it now derives all four from `:core`'s `SampleClock`, anchored once at session start, plus the
  segment's own sample position — see CHANGELOG.md.
  Audit F-006 (2026-09-07) fixed the same `RealSegmentSink.close()` deleting every
  `REJECTED_TOO_SHORT` segment's staged audio and returning without recording anything; it now
  persists a `REJECTED` row with `rejectionReason = "too_short"` and its FLAC audio retained,
  exactly as FR-SEG-6/AC-72 require, and never enqueues it for Pass B — see CHANGELOG.md.
  Audit F-005 (2026-09-07) fixed `RealCaptureService.onHeartbeat()` writing a fabricated
  `samplePosition = 0L` into every heartbeat record; it now reads the session's `Segmenter`'s
  own `position()` at write time, matching the sample-position provenance the parallel
  `capture-android.CaptureService` already had — see CHANGELOG.md.
  **Audit F-011 (2026-09-07) fixed:** the "constructed and wired into `RealCaptureService`,
  proven against `FakeAsrEngine` on Robolectric" claim above had no test that actually started
  the service — `CaptureProcessingLoopTest` proved `PassDrainRunner`+`PassBFactory` draining a
  hand-assembled queue, not `RealCaptureService.startCapture()`'s own composition. A small
  `RealCaptureService.Dependencies` seam (settable after `onCreate()`, real-construction
  defaults, no Hilt) now lets `RealCaptureServiceTest` start the real service under Robolectric's
  `ServiceController` with `FakeAudioIo`/`FakeAsrEngine`/`FakeShedSignals`/an in-memory
  `OrtDatabase`, feed one synthetic speech-then-silence burst, and prove a `TransmissionEntity` +
  transcript row appear (AC-31), the heartbeat carries a real non-zero sample position (F-005), a
  deliberate `ACTION_STOP` marks the session's heartbeat clean (AC-5), and an
  `Interrupted`/`Resumed` pair yields a real `capture_gap` row (AC-48, F-028) — all through the
  service's own composition, not a hand-wired harness. Still Robolectric/JVM only; no device has
  run this. See CHANGELOG.md.
  **Left open (audit F-025, 2026-09-07, recorded not fixed):** the Pass B backlog drains only
  while `RealCaptureService` is alive — `startProcessingLoop`'s coroutine runs in the same
  service-scoped `scope` that `onDestroy()` cancels, and there is no `WorkManager` job or other
  scheduler that resumes draining after the service stops. FR-RUN-2 still holds (the queue is
  durable, nothing is lost) but a backlog left behind at stop waits for the next capture session.
  Building a post-capture drain is M8 streaming/M10 reprocessing scope, not P12's — see
  CHANGELOG.md.
  **Audit F-022 (2026-09-07) fixed:** relaunching `MainActivity` while `RealCaptureService` was
  already running always minted a fresh session id and handed it to `ReaderActivity`, so the
  reader polled a session nothing was capturing into. `MainActivity` now checks
  `CaptureState.sessionId`/`isCapturing` before starting the service or choosing which id to pass
  on; `CaptureState.idle()` gained a `clearSession` parameter so only a deliberate `ACTION_STOP`
  clears the published id; `ReaderActivity` also prefers the live id over its intent extra as
  defence in depth. See CHANGELOG.md.
- [x] **P13 · Compose foundation: theme, navigation, the design canvas made real** *(`:app`)* —
  done 2026-09-08.
  `design/canvas/` has seven designed screens and the app has none; `ort.android-app` has no
  Compose wiring at all.
  **Update (audit F-020, 2026-09-08):** the drawer's Log/Threads/Capture badges were static
  labels and the storage footer reported whole-device `StatFs` under an "Audio ... of 60 GB"
  header — a device-wide figure shown as per-category, against a budget (FR-STO-3) this build has
  never set. Fixed: Log/Capture badges are now polled real counts (`ReaderPolling.drawerBadges`);
  Threads shows "—" (never a numeric 0-as-if-grouped) because `threadId` is still always `null`;
  the footer (`StorageFooterViewState.fromAudioDirectory`) reports the real byte sum of retained
  transmission audio plus real `StatFs` free space, and states "no budget set" rather than
  fabricating a total. See CHANGELOG.md's 2026-09-08 (audit — F-020) entry.
- [x] **P14 · Reader: live view and transmission detail** *(`:app`)* — done 2026-09-08.
  `NowScreen`/`LogScreen`/`TransmissionDetailScreen` render real `:data` state (transcripts,
  attributions, superseded versions) via a new `ReaderPolling`/`TransmissionDetail` read path;
  `RealTransmissionAudioPlayer` plays retained audio through `:pipeline`'s existing
  `FlacSegmentAudioProvider`. FR-UI-1/4/5 and FR-A11Y-1..4 hold; the digest, thread grouping and
  FR-UI-8 lattice/prior panel are named divergences left to P15-P17. See CHANGELOG.md.
  **2026-09-07 (audit F-003):** the read path never surfaced `TransmissionEntity.processingState`,
  so `FAILED`/`REJECTED` transmissions rendered identically to still-pending ones; fixed (FR-RUN-9)
  — see CHANGELOG 2026-09-07 entry. The `FAILED` label still lacks the queue's `lastError` text.
  **2026-09-07 audit fix (F-004):** `StatusScreen`/`NowScreen` did not surface
  `AsrAvailability`/`VadAvailability` at all — the reader gave no reason transcripts never appear.
  Fixed: `StatusViewState` now carries real ASR/VAD status labels and an honest
  "no transcription model installed" header message; see CHANGELOG.md's F-004 entry.
  **Correction (2026-09-07, audit F-012):** FR-UI-4's confidence number was reachable only via
  `AttributionMarker`'s merged `contentDescription`, never as visible text in the Log row or
  Detail header — fixed in `AttributionMarker.kt`; see the 2026-09-07 (audit — F-012) CHANGELOG
  entry.
  **Correction (2026-09-08, audit F-015):** `Log.dc.html`'s rejected-row dimming, "new" badge and
  filter chips, and `Detail.dc.html`'s playback scrubber were unrecorded divergences from the
  artboards. Recorded, not built — `design/canvas/README.md` (new) is the standing register; the
  scrubber (and even a cheap progress-text half of it) cannot be built without extending
  `TransmissionAudioPlayer`'s API, which exposes no position/duration — see the 2026-09-08
  (audit — F-015) CHANGELOG entry.
- [x] **P15 · Search and threads** *(`:app`, `:data`)* — done 2026-09-08. `SearchDao` (a new DAO
  file, per the prompt, to stay conflict-free with concurrent P17) queries the FTS5 index P5 built
  and nothing queried until now, with callsign/frequency/date filters; `SearchScreen`/`ThreadScreen`
  render it. FTS5 confirmed genuinely unavailable under this project's pinned Robolectric setup
  (`no such module: fts5`, even in `sqliteMode=NATIVE`) — the one full-text DAO test is honestly
  `Assume`-skipped rather than faked; filters, the query-construction logic, and the graceful
  degrade-to-filters-only behaviour are all proven for real. Thread grouping is proven against the
  real (currently always-null) `threadId` column with an honest "not yet grouped" fallback — M6
  still owns actually populating it. See CHANGELOG.md. **Update (audit F-017, 2026-09-07):** the
  `:data` half of FR-UI-3's remaining filters — band, attribution state, rejected/accepted — is
  now built in `SearchDao`/`Band.kt` (see CHANGELOG's audit F-017 entry). **Update (audit F-017,
  2026-09-07, `:app` half):** `SearchScreen` now exposes band, attribution-state and
  rejected/accepted controls, wired through `SearchFilterInput`/`SearchPolling` to `SearchDao`.
  F-017 is closed — see CHANGELOG's second audit F-017 entry.
- [x] **P16 · Correction, the inspection surface, and labelled-sample capture** *(`:app`,
  `:data`)* — done 2026-09-08. FR-UI-6 (one-tap correction, Q8's tiers), FR-UI-8 (the inspection
  surface — was honestly empty pending a `:pipeline` change; that change landed 2026-09-07 as
  audit F-009, `DataPassBResultSink` now writes lattice/candidate rows, see CHANGELOG), and
  FR-OBS-4 (labelled-sample capture per `docs/reference/labelling-protocol.md`) — see
  CHANGELOG.md for the CORRECTED-lock enforcement. **Update (audit F-018, 2026-09-08):** the
  "search the lexicon" tier's module-boundary substitution ("search known stations") is resolved —
  `:pipeline` now exposes `passb/LexiconLookup.kt`, and `:app`'s middle correction tier
  (`CorrectionTier.SEARCH_LEXICON`) calls it for real. See CHANGELOG.md's audit F-018 entry.
- [x] **P17 · Station and frequency views with activity patterns** *(`:app`, `:data`)* —
  done 2026-09-08. FR-UI-9/FR-UI-10 (everything heard from one station/on one frequency, across
  every session), FR-UI-11 (hour-of-day activity patterns), and FR-UI-12 (the not-heard/not-
  listening distinction, structural as a closed three-state enum) — see CHANGELOG.md for exactly
  how the distinction is computed and tested. FR-UI-11's day-of-week and week-over-week halves
  (audit F-019, fixed 2026-09-07) are now built too — see the 2026-09-07 (audit — F-019) CHANGELOG
  entry.
  Note (audit F-028, fixed 2026-09-07): P17's `:app`/`:data` half was correct, but nothing upstream
  in `:pipeline` had ever populated `captureGapDao` in production — `RealCaptureService` built no
  `GapTracker`/`GapPersister`, so FR-UI-12's distinction had no real gap to show. See CHANGELOG.md.
- [x] **P18 · Model acquisition through `:net`** *(`:net`)* — done 2026-09-08. Without a model on
  disk the app can capture but never transcribe, and P12 correctly refused to fetch one from
  `:pipeline`: **only `:net` may link an HTTP client, and never in the capture or processing
  path** (constitution V). `ModelAcquisition.fetch()`/`.sideload()` behind `HttpRangeClient` (a
  real `HttpURLConnection` implementation plus a behavioural fake) fetch, resume, checksum-verify
  and side-load a model, mirroring `corpus/acquire.py`'s semantics; `dependencyRules` confirms
  `:net -> :core` only and that neither `:capture-android` nor `:pipeline` has any edge to `:net`.
  **Not done here, by design:** the `:app` call site that mints `NetCapability.UserInitiated` and
  actually downloads the models P12 is waiting on — see CHANGELOG.md. **Update 2026-09-07 (audit
  F-024):** `RealHttpRangeClient` now has an automated loopback test (`ServerSocket`-based, not
  `jdk.httpserver`) and a real truncated-body bug it caught is fixed — see CHANGELOG.md. **Update
  2026-09-07 (audit F-008, `:net` half):** `net/src/main/AndroidManifest.xml` now declares
  `android.permission.INTERNET` (it declared none before, so even the still-missing `:app` call
  site would have failed at runtime with a `SecurityException`); `platformGuards` gained a rule
  that fails the build if `:net` itself lacks the permission, not just if another module has it.
  **Update 2026-09-08 (audit F-008, `:app` half):** the "Models" screen
  (`ReaderDestination.SETTINGS`) is that call site — `ModelsController.download()`/`.sideload()`
  mint `NetCapability.UserInitiated` and drive `ModelAcquisition` for all four required files
  (three `AsrModelLocator` files, one `SileroVadLocator` file), writing to the exact paths P12's
  providers read, and requeue F-016's `FAILED` items on a successful install. **Still not done:**
  `ModelCatalog`'s checksums were placeholders, not the real published SHA-256 (no network egress
  available to compute one) — a real fetch today verifies the *mechanism* correctly but will
  correctly refuse to install real bytes until a real digest is pinned. **Update 2026-09-08 (audit
  F-008 follow-up):** those checksums are no longer placeholders — the encoder, decoder and VAD
  entries carry real sha256 values read from published metadata (HuggingFace's Git-LFS pointer
  text; sherpa-onnx's own release `checksum.txt`), cited in KDoc and `asr-sherpa/README.md`.
  `tiny.en-tokens.txt` has no published sha256 anywhere found and is now explicitly
  `ChecksumState.UnknownSideloadOnly` rather than a placeholder: Download is refused for it with no
  network call, and Side-load installs it via a trust-on-first-use digest computed from the user's
  own file, never claimed as "checksum verified". See CHANGELOG.md. No real download or on-device
  install has been run against any of these URLs in any session.

**Wave F — capture modes and bundled assets. Added 2026-09-10 (D33–D36). All need Wave E.**
**The working contract for this wave is [`e2e-capture-modes-plan.md`](e2e-capture-modes-plan.md)**
(work packages by file ownership, scenario sets, merge order); the burn-down is
`results/e2e-audit/checklist.md`; the artboards are inventoried in `design/design-intent.md` §15.
The prompts below are the summaries; the plan is authoritative where they differ.

- [x] **P19 · Capture modes and the onboarding mode picker** *(`:app`, `:core`, `:data`,
  `:capture-android`, `:pipeline`, `:rig`, `:rig-usb`, new `:rig-bluetooth`)* — D33/D34. Turns
  the two unnamed, sequential setup axes into a named mode chosen up front and changeable
  afterwards. Packages WPA, WPB, WPC1, WPC2, WPD, WPE, WPF in the plan. **Artboards drawn
  2026-09-10** (S00, S02c, S09b, S10b, N01b, CF11, F23, FL7; S04/S09/S11/S12/CF02/CF06/N04/DG04
  redrawn) — the Principle VIII prerequisite below is met. **Done 2026-09-11:** AC-128..135 closed
  in `results/e2e-audit/checklist.md` (E2-B..G) on unit tests shown to discriminate, the
  state-settled screenshot tour (run 5: 207/207 at `686212f`) and four device validators (V8–V11);
  AC-127's end-to-end lanes are proven on the emulator up to S05, whose real 30 s listen cannot
  pass on a silent AVD microphone — the operator's hardware pass (H1/H2/H12) completes it;
  `dependencyRules` forbids every `:capture-* → :asr-*`/`:llm-*` edge (E2-A04, D10); every
  session row carries mode, route, label, profile and transport (v7) plus rig id, verified flag and
  native rate (v10, E2-A07). Left open on purpose: the TH-D75A descriptor claims no `MODE` and no
  USB vid/pid until H1 verifies them on the radio.

  **Read first:** functional spec §7.1a (FR-CAP-8..13), FR-CAP-2b, CON-CAP-1 *as amended*,
  FR-RIG-13..19, §9.1a, AC-127..135. Constitution I, IV, VII, VIII.

  **Owns:** `app/.../ui/setup/**` (the new `CaptureMode`, mode-picker screen, `SetupStep`/
  `SetupStateMachine`/`SetupStore` changes), `app/.../ui/settings/SettingsCaptureScreen.kt`
  (FR-CAP-12's re-entry), the rig catalogue types, and the new Bluetooth transport module.
  **Must not touch:** the segmenter (Principle III — segmentation takes no mode, exactly as it
  takes no tier), `:asr-*`, `:lexicon`, `:identity`.

  **Tests first, in this order** — each named for its criterion:
  1. `AC_127` each of the three modes completes onboarding on hardware offering only that mode.
  2. `AC_130` a mode presets both axes, and **Bluetooth control + wired audio** is reachable —
     the combination no mode presets. This is the test that proves FR-RIG-13's independence is
     real rather than asserted; write it before the picker, because a picker built without it
     will hard-couple the axes and pass everything else.
  3. `AC_131` mode changes from settings, takes effect at the next session, never mid-session,
     and alters no existing record.
  4. `AC_132`/`AC_133` Bluetooth audio marking, and TH-D75A parity across both transports.
  5. `AC_134`/`AC_135` the picker is generated from the descriptor set; null and generic ASCII
     CAT stay reachable without scrolling.

  **Ships with it:** a behavioural fake Bluetooth transport that can be told to fail, hang and
  drop mid-session (constitution II — a transport fake that only succeeds tests nothing about
  FR-RIG-15, which is the requirement that matters most for a link that drops more often).

  **Artboards are a prerequisite, not a follow-up (Principle VIII).** The mode picker, the
  Bluetooth pairing step and the settings re-entry are new screens with **no artboards yet** —
  `design/canvas/` has `Setup-Rig*.dc.html` but nothing for a mode choice. Add them to
  `design/design-intent.md` and draw them before building, or the screens ship unverifiable.

  **Done when:** AC-127..135 hold, `dependencyRules` still forbids every `:capture-* -> :asr-*`
  edge, and a capture started in each mode is distinguishable in the stored record without
  reading its audio.

- [x] **P20 · Bundle every asset into the artifact** *(build config, `:app`, `:asr-sherpa`,
  `:net`)* — D35/D36. Amends the delivery half of P18, not its lifecycle half. **Done
  2026-09-11:** AC-136..140 closed in `results/e2e-audit/checklist.md` (E2-H01..H09, E2-I01..I06);
  one variant; `fetchBundledAssets` 5/5 with `HF_TOKEN` (local and the GitHub secret) and the
  complete size measured on a device — APK 611,029,854 B, ≈1.18 GiB installed
  (`results/e2e-audit/installed-size.md`); FR-AST-3a's packs TODO stays open with that cost
  recorded (the LLM is 84 % of the bundled bytes).

  **Read first:** FR-AST-3, FR-AST-3a, FR-AST-3b, FR-DIG-3a, FR-DIG-3b, AC-136..140, R18.
  Constitution V and VII.

  **The point of order here:** P18 built acquisition, verification and side-loading through
  `:net`. D35 removes the *download* from the first-run path; it does **not** remove
  `ModelAcquisition` — side-load, replacement and roll-back (FR-AST-1) still run through it, and
  `:net` remains the only module that may link an HTTP client. Deleting the acquisition path
  because assets now ship bundled would take FR-AST-1's lifecycle with it.

  **Tests first:** `AC_136` (a fresh install with networking disabled reaches full capability —
  the criterion that makes D35 worth anything), `AC_137` (a corrupted bundled asset fails
  verification rather than activating — bundling establishes provenance, not integrity),
  `AC_138` (a T0 device stores the LLM and never loads it, measured against the resident budget),
  `AC_139`, `AC_140` (the deterministic digest with the LLM **disabled**, replacing AC-84's
  now-unreachable "absent").

  **Measure and record the real installed size** as soon as the asset set is fixed, under
  `results/`, with the device and the tier breakdown. R18 is the reason this prompt exists at
  all, and it cannot be assessed from an estimate.

  **Done when:** AC-136..140 hold, one build variant produces the shipping artifact, and
  `FR-AST-3a`'s TODO is either still open with a recorded size or closed by a measurement.
  Package WPG in the plan. **Asset set fixed 2026-09-10:** the four current catalogue entries
  plus Gemma 3 1B int4 (`litert-community/Gemma3-1B-IT`, sha256 `e3d981c0…9dee`,
  554,661,243 bytes, gated — the build needs `HF_TOKEN`). Larger-tier ASR models join the
  manifest when the tier system selects them (P11/M10), not before.

- [x] **P21 · The LLM — contract, fake, MediaPipe engine, prose digest** *(new `:llm-api`,
  new `:llm-mediapipe`, `:pipeline/digest`, `:data` v8, `:app` digest and settings)* — D36.
  Packages WPH (with WPE/WPF for the screens) in the plan. **Done 2026-09-11:** DG05 renders
  stored summaries badged `generated`, italic, each card titled by its stations and citing its
  overs (run 5, reviewer D4; V9 on device, incl. `Read the overs` filtering the Log to the cited
  overs); CF05's toggle disables the engine and releases it (E2-I03/I04, `ProseDigestWorkRunnerTest`
  `FR_DIG_3b_disabling_mid_run_releases_the_engine`); the digest with the engine off is unchanged
  (E2-I05, AC-140); `CallsignShapeFilter` rejects an invented callsign (E2-G08, FR-DIG-4; the
  documented `20m`-shaped false positive sacrifices recall, never precision); `dependencyRules`
  forbids `:capture-* → :llm-*` (E2-A04). The engine runs Gemma 3 1B int4 through MediaPipe
  `tasks-genai` 0.10.35 — bundled, stored on every tier, loaded only at tier 3 while idle and
  charging (AC-138, V9 `tier0-llm-stored`). Hardware H11 exercises it on the phone.

  **Read first:** FR-DIG-3, FR-DIG-3a, FR-DIG-3b, FR-DIG-4, FR-DIG-5, FR-DIG-6, FR-DIG-11,
  FR-DIG-12, FR-ASR-15/16 (deferred — rescoring is not in this prompt), AC-84, AC-86, AC-87,
  AC-138, AC-140, R16. **D5 is untouched: no LLM in the callsign path, ever.** Constitution I, V.

  **Tests first:** the `CallsignShapeFilter` against a fake that deliberately invents a callsign
  (FR-DIG-4 — MediaPipe has no grammar-constrained decoding, so the post-filter is the guard);
  the gate's five conjuncts falsified one at a time (FR-DIG-5, AC-87); `AC_140` (deterministic
  digest unchanged with the engine disabled, memory released); `AC_138` (below tier 3 the model
  is never loaded); the prompt builder's closed field list (FR-DIG-12 — no station knowledge,
  no names, no location).

  **Ships with it:** `FakeLlmEngine` that can hang, fail to load, over-run and hallucinate.

  **Done when:** DG05 renders real stored summaries badged `generated` and attributed to their
  overs; CF04's toggle disables and releases; the digest with the engine off is byte-identical to
  today's; `dependencyRules` still forbids `:capture-*` → `:llm-*`.

**Wave G through K — the 2026-09-19 governance session's backlog (D42-D50).** *Added 2026-09-19,
after constitution 2.0.0 and the matching spec amendment (commit `d58ed8fd`, merged `2c17934a`)
landed nine decisions in one sitting: setup must actually finish (D43), models need a real
distribution story (D43/D44), the voice-correction copy has been lying since v0.1.1 (D45),
analytics exists now (D42/D48), and live alerts are promoted off the backlog. None of this depends
on M4's fork — it is orthogonal build-plan debt the governance session surfaced, not new product
scope invented here.* **Read first, every unit:** `.specify/memory/constitution.md` 2.0.0
(Principles I, V, VII, VIII bind almost everything below), `AGENTS.md`'s "Rules that are not
negotiable", and `spec/functional-spec.md` D42-D50 in §3, plus each unit's own citations below.

**Wave order.** Five waves, eleven units, P22-P32. **Wave G** (P22-P27, six units) is mutually
disjoint and needs only Wave F. **Waves H-K exist because a hot file is already spoken for
earlier in the sequence, not because of any functional dependency**: `TransmissionDetailScreen.kt`
goes to P26 in Wave G, so P29's relabelling (which also touches it) waits for Wave H.
`SettingsViewData.kt` (the `SettingsScreenId` enum), `SettingsContent.kt` (the router) and
`SettingsRootScreen.kt` (the menu) are claimed once per wave — P27 in G, P28 in H, P30 in I, P31 in
J — because every one of the four screens they add (licence notices, analytics tiers, export/
restore, alerts) needs a row, an enum case and a router branch in those same three files, and a
builder adding a case cannot be concurrent with another builder adding a different case to the
same `when`. **Wave K** (P32, accessibility) touches nearly every screen in the app by nature, so
it is alone, last, and needs every screen change above already landed. Within a wave, order is
free.

**Wave G — six mutually disjoint units. Needs only Wave F.**

- [x] **P22 · Setup completes everything** — done 2026-09-19 (see CHANGELOG's own entry for the
  commit hash, the `MainActivity.kt` gap left open, and the AC-190 play-variant half deferred to
  P23) *(`:app` — `ui/setup/**`, `ui/data/ModelsViewData.kt`,
  a new WorkManager job)* — D43, FR-AST-10..12.

  **Read first:** `spec/functional-spec.md` FR-AST-3 (amended), FR-AST-10..12, NFR-6, NFR-6c,
  AC-184..190; `app/src/main/kotlin/org/ort/app/ui/setup/SetupStateMachine.kt`,
  `SetupStep.kt`, `ReadyScreen.kt`, `OvernightScreen.kt`; `net/src/main/kotlin/org/ort/net/ModelAcquisition.kt`
  (P18's fetch/verify/atomic-rename primitives — do not reimplement them); `app/src/main/kotlin/org/ort/app/ui/data/ModelsViewData.kt`
  (`ModelsController.download()`, already wired for the Settings > Assets screen, per P18's
  follow-up). Constitution I (READY must not lie), IV (background survival), V (Wi-Fi-only,
  no network during capture), VII (the gate is structural).

  **Owns:** `app/src/main/kotlin/org/ort/app/ui/setup/**` (a new `MODELS` `SetupStep`, a new
  jurisdiction/consent notice step for NFR-6c, the `ReadyScreen.kt` hard gate, `OvernightScreen.kt`'s
  heartbeat-based return), `app/src/main/kotlin/org/ort/app/ui/data/ModelsViewData.kt` (extend
  `ModelsController.download()`'s existing call path for the setup step; do not change its
  Settings-screen behaviour), a new `app/src/main/kotlin/org/ort/app/work/ModelDownloadWorker.kt`
  (foreground `WorkManager` job, Wi-Fi-only by default, per-download override). **Must not touch:**
  `ui/settings/**`, `ui/navigation/OrtNavHost.kt`, `:net`'s public API (call it, do not change it),
  `:pipeline`.

  **Tests first:** `AC_184` the MODELS step downloads a missing required model through
  `ModelAcquisition` as a foreground `WorkManager` job; `AC_185` a killed-and-relaunched download
  resumes rather than restarting; `AC_186` a sha256 mismatch rejects and re-queues, never
  activates; `AC_187` Wi-Fi-only by default, overridable per download; `AC_188` READY is
  unreachable while any required model is missing/unverified **or** the mic/level check has not
  passed — both conditions, together; `AC_189` the battery-exemption step recurs on every launch
  until the heartbeat proves survival, even when the OS reports the exemption already granted;
  `AC_166` the jurisdiction/consent notice shows exactly once, on first run, and blocks capture
  until dismissed.

  **Ships with it:** a fake `ModelDownloadWorker`/`WorkInfo` source so `ReadyScreen`'s gate is
  tested without a real `WorkManager` test harness dependency on every run.

  **Done when:** AC-166, AC-184..189 hold; a fresh install with networking disabled on the `full`
  variant still reaches READY (nothing here narrows P20's guarantee); this touches no `*Screen`/
  `*Content`/`*ViewState` outside `ui/setup/**`, so the visual re-verification (constitution VIII)
  is scoped to the setup tour steps only — capture them at 1.0 and 2.0.

- [x] **P23 · Download manifest, model mirror and the two build variants** — done 2026-09-19: the
  `full`/`play` product flavors exist and both build (`assembleFullDebug`, `assemblePlayDebug`,
  `bundlePlayRelease`, all verified locally); `bundled-assets.json` carries a pinned `mirrorUrl`
  per entry and the generated per-flavor catalog carries `bundled`/`downloadUrl`
  (`BundledAssetCatalogRenderer`, tested); `PublishModelMirrorTask`/`ModelMirrorPublisher`
  (idempotent by name+size, tested) is wired into `release.yml` on release tags only; the FGS-type
  guard, privacy policy, Data Safety sheet and FGS checklist exist. **AC-190's runtime half
  (installing each variant and comparing its setup flow) is NOT verified here** — it needs P22's
  `ui/setup`/`ModelsViewData.kt` work (parallel, not owned by this unit) to read the new
  `bundled`/`downloadUrl` fields at all; today both flavors' `ModelCatalog.entries` still report
  every entry `bundled = true` regardless of flavor, since that mapping lives in the file P22 owns.
  `verifySherpaNativeLibrariesPackaged` (R-1001) was rescoped to the `full` flavor's own debug
  APK only, not re-proven against `play`'s. **Three callers outside this unit's ownership are now
  broken and were not fixed here** — `.github/workflows/ci.yml`'s `android` job
  (`:app:testDebugUnitTest` is now ambiguous; `:app:assembleDebug`'s own output path changed),
  `.github/workflows/emulator.yml` (`:app:connectedDebugAndroidTest` is now ambiguous), and
  `tools/ui-audit/install.ps1` (`:app:assembleDebug`'s APK now lands under `apk/full/debug/`, not
  `apk/debug/`) — see this unit's own session report for the exact fix each needs *(`buildSrc`,
  `bundled-assets.json`, `app/build.gradle.kts`, `.github/workflows/release.yml`, `docs/`)* —
  D43, D44, FR-AST-13, FR-AST-14.

  **Read first:** FR-AST-13, FR-AST-14, D43, D44; `buildSrc/src/main/kotlin/ort.android-app.gradle.kts`
  (`generateBundledAssetCatalog`, `fetchBundledAssets`) and `buildSrc/src/main/kotlin/org/ort/gradle/FetchBundledAssetsTask.kt`
  — the existing build-time bundling path this unit extends, not replaces; `bundled-assets.json`'s
  own top-of-file `note` block, which already states the URL/sha256/size/destination/tiers/gated
  shape FR-AST-14 wants for the mirror too. Constitution V, VII.

  **Owns:** `buildSrc/**` (a new mirror-publish task, e.g. `PublishModelMirrorTask`, alongside the
  existing `FetchBundledAssetsTask`), `bundled-assets.json` (extended per-entry with whatever the
  `play`-variant download URL needs — the mirror's own `models-v1` release asset URL, distinct
  from each entry's existing build-time source URL), `app/build.gradle.kts` (two product flavors,
  `full` and `play`), `.github/workflows/release.yml` (a step that uploads the asset set to a
  `models-v1` tag on this repository), new `docs/privacy-policy.md` and a Play Data Safety answer
  sheet (`docs/play-data-safety.md`), an FGS-type-declaration check in
  `buildSrc/src/main/kotlin/org/ort/gradle/PlatformGuards.kt`. **Must not touch:** any file under
  `app/src/main/kotlin/org/ort/app/ui/**`, `app/src/main/AndroidManifest.xml` (use flavor source
  sets — `app/src/full/`, `app/src/play/` — if a variant genuinely needs a manifest difference),
  `:net`.

  **Tests first:** a `buildSrc` unit test that the `play` flavor's generated catalog carries a
  download URL and sha256 per required-tier model while the `full` flavor's does not need one
  (both still carry the build-time bundling fields); `AC_190` installing each variant and
  comparing its setup flow — `full` never reaches a model-download step, `play` does; `AC_191` a
  manifest entry's sha256 mismatch against the actually-downloaded bytes is rejected; a
  `dependencyRules`-style guard (or a `buildSrc` test) that `bundled-assets.json` stays
  byte-sorted/deterministic (constitution: generated files must be byte-identical wherever
  generated).

  **Ships with it:** nothing model-bearing — this is distribution plumbing. A fixture-sized
  `bundled-assets.json` fixture for the `buildSrc` tests, per "no unit test reads a real bundled
  model."

  **Done when:** AC-190, AC-191 hold; two build variants exist and both build in CI; the
  `models-v1` mirror step is wired into `release.yml` (may run only on an actual release tag, not
  every push — say so if that's the design); the privacy-policy and Data-Safety docs exist and
  name the FR-ANL-14 sentence verbatim where they make a privacy claim at all.

  **Follow-up, done 2026-09-19 (see CHANGELOG's own entry for the commit hash):** the gap this
  unit's own note named above — `ModelCatalog.entries` (P22's file) never reading
  `bundled`/`downloadUrl` off the generated per-flavor manifest, so `play` still reported every
  entry bundled — is now closed. `ModelCatalog.mapEntries` reads both fields straight from
  `GeneratedBundledAssetEntry`; `specFor`/`unverifiedSpecFor` fetch from the mirror
  (`ModelCatalogEntry.fetchUrl()`) once an entry is not bundled. AC-190's runtime half (installing
  each variant and comparing its setup flow on a device) remains unverified from this worktree —
  see this fix's own CHANGELOG entry for exactly what was and was not proven.

- [x] **P24 · Automatic thread grouping after Pass B** *(new `:pipeline` threading package)* —
  D45's own note names this as the thing v0.1.1 overclaimed; FR-SPK-5, AC-163..165. Done
  2026-09-19: `pipeline/.../threading/**` (`ThreadGrouper`, `ThreadKindClassifier`,
  `ThreadGroupingCoordinator`, `RoomThreadRepository`, `FakeThreadRepository`) plus one call added
  to `DataPassBResultSink.record`. AC-163..165 hold end to end against a real in-memory
  `OrtDatabase` (`DataPassBResultSinkThreadingTest`); the two writes no DAO method covers
  (`transmission.threadId`, extending an existing `thread` row) go through `org.ort.data.execRaw`
  (an existing, already-public `:data` primitive) rather than a new DAO method, since `:data` was
  not in this unit's Owns list. `ThreadKindSource.USER` has no `:app` writer yet (left open); the
  gap threshold and net-detection numbers are documented provisional defaults pending Q16.

  **Read first:** FR-SPK-5 (amended, this session) and its "Amendment, this session" note in
  full, FR-SPK-27..28 (net detection, advisory only), AC-163..165; `pipeline/src/main/kotlin/org/ort/pipeline/passb/DataPassBResultSink.kt`
  (where a transmission's Pass B closure is already recorded — the integration point) and
  `data/src/main/kotlin/org/ort/data/entity/CatalogEntities.kt`'s `ThreadEntity` (already has
  `kind`; `Transmission.threadId` already exists and is always `null` today — this unit is the
  first thing that ever writes it). Constitution III (this runs after Pass B closes a
  transmission, never re-segments, never touches audio), IV (`:capture-*` boundary).

  **Owns:** a new `pipeline/src/main/kotlin/org/ort/pipeline/threading/**` package (frequency
  continuity + inter-transmission gap grouping, net-sequence detection per FR-SPK-27, scanner-
  activity grouping), one narrow, named integration point in
  `pipeline/src/main/kotlin/org/ort/pipeline/passb/DataPassBResultSink.kt`: a single call to the
  new grouper immediately after a transmission's Pass B result is recorded, passing frequency,
  timestamp and rig-channel-continuity — no other line in that file. **Must not touch:**
  `:capture-*`, `:segment` (Principle III — thread grouping is not segmentation and must not
  become a second place boundaries are decided), `:data`'s schema (no migration — `threadId` and
  `Thread.kind` already exist), `:app`.

  **Tests first:** `AC_163` an ordinary two-station QSO threads automatically into one `Thread`
  record end to end against a tape containing one; `AC_164` a detected net's check-in sequence
  threads into one `Thread` marked `net`, and the thread exists **whether or not** the net marking
  is ever set or cleared (FR-SPK-28 — marking is advisory, never structural); `AC_165` scanner
  activity on one channel over a sustained period threads automatically, and grouping runs
  **immediately after Pass B closes each transmission**, not waiting on a transcript — assert this
  by grouping a transmission whose Pass B (transcript) never completes and confirming it still
  joins its thread by frequency/gap alone.

  **Ships with it:** a scriptable fake transmission-closure feed (reuses `:testing`'s existing
  clock injection, FR-TST-2) so gap-threshold behaviour is tested without wall-clock waits.

  **Done when:** AC-163..165 hold; `RELEASES.md`'s `## Unreleased` correction (already recorded in
  the governance commit) is now true rather than aspirational; `dependencyRules` still shows no
  `:pipeline/threading` edge into `:capture-*` or `:segment`.

- [ ] **P25 · VAD fallback disclosure** *(`:app` — `LiveBar.kt`, `LiveBarPolling.kt`, a session
  record)* — D50, Q22 closed, FR-SEG-10.

  **Read first:** D50, FR-SEG-10 (already built at the data layer — `core/src/main/kotlin/org/ort/core/capture/VadDetectorKind.kt`,
  `TransmissionEntity`/`SessionEntity`'s existing fields, `pipeline/src/main/kotlin/org/ort/pipeline/diagnostics/DiagnosticsLog.kt`'s
  `vad_stats` line, all already tested per `RealSegmentSinkTest`/`RealCaptureServiceTest`), AC-162
  (the data-layer AC this session's UI half was never built for). This unit is UI-only: the
  detector identity already reaches the database and the debug dump; it has never reached a
  screen. Constitution I (a fallback used silently is exactly the thing Uncertainty Is Content
  forbids).

  **Owns:** `app/src/main/kotlin/org/ort/app/ui/components/LiveBar.kt` and
  `app/src/main/kotlin/org/ort/app/ui/data/LiveBarPolling.kt` (a chip, shape-distinct per
  constitution VII's accessibility floor, shown whenever the active session's VAD detector is not
  the FR-SEG-1 one), `app/src/main/kotlin/org/ort/app/ui/recordings/RecordingSessionScreen.kt` and
  `RecordingSessionViewStateMapper.kt` (a durable per-session line naming which detector produced
  that session's boundaries — the "session record" the disclosure survives to, not only a live
  chip that disappears when the session ends). **Must not touch:** `ui/navigation/OrtNavHost.kt`,
  `ui/screens/TransmissionDetailScreen.kt`, `ui/settings/**`.

  **Tests first:** a live session using the fallback detector shows the chip; a live session using
  the FR-SEG-1 detector does not; the session review screen names the real detector for a past
  session regardless of which one produced it, sourced from the existing `vad_stats`/entity
  fields, never a guess; the chip and the session line never disagree for the same session in a
  test that drives both from one fixture.

  **Done when:** the disclosure is visible live and after the fact, from data this build already
  records; captured again by the tour at 1.0/2.0 for `Now` and the session-review screen
  (constitution VIII — this touches `*Content`/`*ViewState` files).

- [ ] **P26 · Playback stops on navigation, plus five small defects** *(`:app` — navigation,
  transport bar, several screens; `:asr-sherpa` KDoc)* — R-1006, AC-168, R-1074, R-1079, R-1080,
  R-1012, and the known-red `ImproveDestinationStatePreservationTest`.

  **Read first:** AC-168 and FR-UI-5; `results/ui-audit/register.md` rows R-1006 (open — note its
  own history: fixed once as a per-screen `DisposableEffect`, then **deliberately reversed** by
  the C10 transport-bar work so playback survives navigation — AC-168 now asks for the original
  behaviour back, at the transport-bar layer this time, not the per-screen layer that no longer
  owns playback), R-1074, R-1079, R-1080, R-1012; `app/src/main/kotlin/org/ort/app/ui/navigation/OrtNavHost.kt`'s
  `resolveTransportBarState`; `CHANGELOG.md`'s 2026-09-12 WPUI entries for exactly what the
  reversal changed and why. Constitution VIII (screenshot over test), II (discrimination).

  **Owns:** `app/src/main/kotlin/org/ort/app/ui/navigation/OrtNavHost.kt`,
  `app/src/main/kotlin/org/ort/app/ui/components/TransportBar.kt`,
  `app/src/main/kotlin/org/ort/app/ui/audio/TransportPlaybackController.kt`,
  `app/src/main/kotlin/org/ort/app/ui/screens/TransmissionDetailScreen.kt`,
  `app/src/main/kotlin/org/ort/app/ui/audio/RealTransmissionAudioPlayer.kt` (R-1006/AC-168);
  `app/src/main/kotlin/org/ort/app/ui/screens/NowContent.kt` and
  `app/src/main/kotlin/org/ort/app/ui/data/ActivityPattern.kt` (R-1074 — reuse `SessionCoverageMapper`'s
  real-gap-boundary fix from R-1069, do not re-derive it); `app/src/main/kotlin/org/ort/app/ui/screens/CaptureScreen.kt`
  and `app/src/main/kotlin/org/ort/app/ui/recordings/RecordingsScreen.kt`/`RecordingsViewData.kt`
  (R-1079 — reuse R-1068's adaptive-unit formatter); `app/src/main/kotlin/org/ort/app/ui/failures/FailureHost.kt`
  and `FailureBanners.kt` (R-1080); `asr-sherpa/src/main/kotlin/org/ort/asrsherpa/real/RealSherpaDecoder.kt`
  (R-1012 — KDoc correction only, no behaviour change); the `ImproveDestinationStatePreservationTest`
  suite under `app/src/test/kotlin/org/ort/app/ui/navigation/` (add `WorkManagerTestInitHelper`
  initialisation). **Must not touch:** `ui/settings/**`, `ui/setup/**`.

  **Tests first:** `AC_168` navigating away from a transmission detail view while its clip plays
  stops playback, and returning does not auto-resume — written to discriminate against the C10
  transport-bar behaviour specifically (fail with the bar's current "survives navigation" logic,
  pass once it stops on a genuine leave); a same-screen switch to a different over's detail is
  distinguished from a genuine leave if the artboard still wants that case to survive (check
  `design/canvas/Detail-Playback.dc.html` before assuming AC-168 wants both); a 22-minute gap in a
  3-hour `Now` session never hatches more than 22 minutes of the bar (R-1074); an archive/recording
  card with a small non-zero byte count never rounds to `0.0 GB` (R-1079); the failure banner's
  border closes on all four sides with the collapse chevron present (R-1080);
  `ImproveDestinationStatePreservationTest` passes with `WorkManagerTestInitHelper` initialised in
  its harness (show it red without the init, green with it — constitution II).

  **Ships with it:** nothing new — every fake this touches already exists.

  **Done when:** AC-168 holds and is reconciled with whatever `Detail-Playback.dc.html` actually
  shows for the same-screen-switch case (**decide this explicitly and record it in the register
  row**, since it reverses a deliberate prior decision — do not silently re-reverse without
  saying so); R-1074/1079/1080/1012 close on evidence; `ImproveDestinationStatePreservationTest`
  is green in CI; recaptured by the tour at 1.0/2.0 for `Now`, `Capture`, `Recordings`,
  Transmission Detail and any screen with a failure banner.

- [x] **P27 · Third-party licence notices screen** *(`:app` — a new Settings screen)* — NFR-6d,
  AC-167.

  **Read first:** NFR-6d, AC-167, FR-AST-14's own notices clause (Gemma's redistribution
  requirement in particular); `app/src/main/kotlin/org/ort/app/ui/settings/SettingsRootScreen.kt`,
  `SettingsContent.kt`, `SettingsViewData.kt` (the `SettingsScreenId` enum this adds exactly one
  case to). Constitution V (NFR-6b licensing), VII.

  **Owns:** a new `app/src/main/kotlin/org/ort/app/ui/settings/SettingsLicensesScreen.kt`, **one**
  new `SettingsScreenId` entry in `SettingsViewData.kt`, **one** new row in
  `SettingsRootScreen.kt`, **one** new `when` branch in `SettingsContent.kt` — this is the narrow,
  named integration point every other Settings-adding unit this wave-set also gets; touch nothing
  else in those three files. Bundled licence text for Gemma, Whisper, sherpa-onnx, ONNX Runtime,
  Silero and any other bundled component with a notice obligation, stored as app assets reachable
  offline. **Must not touch:** any other `SettingsScreenId` case, `OrtNavHost.kt` (Settings
  sub-screens route through `SettingsContent.kt`'s own router, not the nav host).

  **Tests first:** `AC_167` the licence-notices screen lists Gemma, Whisper, sherpa-onnx, ONNX
  Runtime and Silero, each reachable and its full text readable, with the device's network
  disabled (a Robolectric/instrumented test that airplane-modes or simply never touches `:net`).

  **Done when:** AC-167 holds; recaptured by the tour at 1.0/2.0; the register's design inventory
  gains this screen if `design/design-intent.md` does not already list one for it (Principle
  VIII's "every screen in the inventory must have an artboard" — draw one first if it is missing).

**Wave H — needs Wave G** (`TransmissionDetailScreen.kt` released by P26; the Settings hot files
released by P27; P22's setup steps released for P28 to extend).

- [x] **P28 · Analytics — `:telemetry`, `:net`'s uploader, `:app`'s tiers, and the ingest tooling**
  *(new `:telemetry`, `:net`, `:app` Settings/setup/crash-capture, `tools/analytics/`)* — D42,
  D48, D49, FR-ANL-1..14, AC-172..183. Needs Wave G (P26/P27 must have released
  `TransmissionDetailScreen.kt` and the Settings hot files first).

  **Read first:** the whole of §7.13d FR-ANL-1..14, D42, D48, D49 (private destination before
  public launch, 90-day retention), AC-172..183; `spec/technical-design.md`'s `:telemetry` module
  entry and dependency table (§2, already amended: `:telemetry` depends on `:core` only, and
  `:capture-*` ↔ `:telemetry` is a forbidden edge both ways); `net/src/main/kotlin/org/ort/net/NetCapability.kt`
  (the two existing token kinds — this adds a third); `settings.gradle.kts` (this module does not
  exist yet — add it). Constitution V (this is the fourth declared channel), VI (provenance),
  VII (structural boundary — `:capture-*` must never see `:telemetry`).

  **Owns:** `settings.gradle.kts` (add `:telemetry`), a new `:telemetry` module in full (event
  schema per tier's closed field list, provenance envelope per FR-ANL-8, a bounded on-device
  queue), `net/src/main/kotlin/org/ort/net/NetCapability.kt` (add `AnalyticsUpload`) and a new
  `net/src/main/kotlin/org/ort/net/analytics/**` uploader gated on `ORT_ANALYTICS_ENDPOINT` (absent
  means queue-only, per D48's self-hosted/build-configured destination), `buildSrc`'s
  `DependencyRulesTask`/`ModuleGraph` (register `:telemetry`'s allowed edges and the forbidden
  `:capture-*` edge both ways), `app/build.gradle.kts` (add the `:telemetry` dependency to `:app`),
  `app/src/main/kotlin/org/ort/app/OrtApplication.kt` (crash/ANR capture wiring, queue start-up —
  the one unit this wave-set that touches this file), a new
  `app/src/main/kotlin/org/ort/app/ui/settings/SettingsAnalyticsScreen.kt` plus **one** new
  `SettingsScreenId` case / row / router branch (this wave's turn at that integration point — see
  P27's note), a new setup step offering tiers 2 and 3 in `app/src/main/kotlin/org/ort/app/ui/setup/SetupStep.kt`/`SetupStateMachine.kt`
  (extending P22's now-landed MODELS/consent steps, not conflicting with them since P22 is already
  merged), `tools/analytics/**` in Python (reference ingest server writing NDJSON, a DuckDB
  loader, named queries, a report generator, `field`-fold separation, its own pytest suite, a CI
  job, `.gitignore` entries for raw data). **Must not touch:** `:capture-*` in either direction,
  `TransmissionDetailScreen.kt`, any other `SettingsScreenId` case.

  **Tests first:** `AC_172` tier 1's exact field set uploads by default with no transcript/
  callsign/name/station-knowledge/location; `AC_173`/`AC_174` tiers 2/3 off means their fields
  never appear queued or uploaded; `AC_175` no tier combination ever carries a name, station
  knowledge or sub-grid-square location; `AC_176` a payload is reproduced bit-for-bit by
  recomputing from stored records, never a live entity serialisation; `AC_177` no analytics
  network call during an active capture session (packet capture, alongside AC-59/AC-146);
  `AC_178` the full FR-ANL-8 provenance envelope on every event; `AC_179` three toggles, each
  matching FR-ANL-2..4's own language, tier 2/3 off takes effect immediately; `AC_180` setup
  explains tier 1 and offers 2/3 unchecked, declining leaves everything else working; `AC_181`
  install-id reset purges the destination's rows for the old id; `AC_182` rows land only in the
  `field` fold; `AC_183` the bounded queue drops oldest-first with no measurable capture impact.

  **Ships with it:** a `FakeAnalyticsUploader` that can be told to fail, queue, or drop (constitution
  II); `tools/analytics/`'s own fixture NDJSON for its pytest suite.

  **Done when:** AC-172..183 hold; `dependencyRules` shows `:telemetry` with no edge to or from
  `:capture-*`; `tools/analytics/`'s CI job is green; the private-destination-before-public-launch
  condition from D49 is recorded as a release gate somewhere reachable (RELEASING.md or the
  release workflow itself), not just in this prompt.

- [x] **P29 · D45 relabel — "same voice" becomes "same callsign"; `CatalogDao.allVoiceprints()`**
  *(`:app` correction UI, `:data`)* — D45, FR-SPK-5's own amendment note.

  **Read first:** the D45 amendment note under "Persistent voice identity (D28)" and the note
  under FR-SPK-5/FR-SPK-7 in full; `app/src/main/kotlin/org/ort/app/ui/data/CorrectionPolling.kt`'s
  `CorrectionScope.EVERY_OVER_SAME_VOICE` and every call site (`TransmissionDetailScreen.kt`,
  `app/src/main/kotlin/org/ort/app/ui/screens/CorrectionSheet.kt`); `data/src/main/kotlin/org/ort/data/dao/CatalogDao.kt`;
  `app/src/main/kotlin/org/ort/app/fieldreport/bundle/VoiceprintEmbeddingsProducer.kt`'s own
  documented gap ("cannot reach a voiceprint never bound to a station because `:data` has no query
  for it" — register R-1010's "left open" note). Constitution I (never assert a claim the data
  cannot back — the UI has been describing voice-based propagation it does not do).

  **Owns:** `app/src/main/kotlin/org/ort/app/ui/data/CorrectionPolling.kt` (rename/relabel the
  scope and its user-facing copy to "every over with the same callsign," matching what it actually
  does), `app/src/main/kotlin/org/ort/app/ui/screens/TransmissionDetailScreen.kt` and
  `CorrectionSheet.kt` (the copy sites, not the playback logic P26 already changed — coordinate by
  reading P26's landed diff first, since this is a later wave), `data/src/main/kotlin/org/ort/data/dao/CatalogDao.kt`
  (add `allVoiceprints()`), `app/src/main/kotlin/org/ort/app/fieldreport/bundle/VoiceprintEmbeddingsProducer.kt`
  (use the new query to reach an unbound voiceprint). **Must not touch:** `ui/navigation/**`,
  `ui/settings/**`, the playback/transport-bar code P26 owns.

  **Tests first:** every user-facing string that says "voice" in the correction-scope context now
  says "callsign," asserted on the real string content this once (constitution II's usual
  "never assert on prose" applies to *wording a designer might change*, not to a factual claim
  about *what propagates* — this test asserts the latter: no user-facing text implies voice-based
  propagation where callsign-based propagation is what runs); `CatalogDao.allVoiceprints()`
  returns every voiceprint including ones with no `boundStationId`; `VoiceprintEmbeddingsProducer`
  now includes a previously-unreachable unbound voiceprint in its output.

  **Done when:** no UI surface describes "same voice" propagation; `allVoiceprints()` is real and
  used; recaptured by the tour wherever the correction sheet is reachable.

**Wave I — needs Wave H** (the Settings hot files released by P28).

- [x] **P30 · Export completion — POTA, the share sheet, and restore** *(`:app` export/backup,
  `:pipeline` export, `AndroidManifest.xml`)* — FR-EXP-7, FR-STO-6, FR-STO-9, AC-170, AC-171.
  Needs Wave H (the Settings hot files pass to this unit next).

  **Read first:** FR-EXP-7, FR-STO-6, FR-STO-9, AC-170, AC-171; `app/src/main/kotlin/org/ort/app/export/ExportCoordinator.kt`
  (`buildPotaActivity` already exists and is unwired — do not rewrite it, wire it),
  `pipeline/src/main/kotlin/org/ort/pipeline/export/PotaActivityExportWriter.kt`,
  `app/src/main/kotlin/org/ort/app/ui/settings/SettingsExportScreen.kt` (`ExportFileFormat`'s
  closed enum — POTA needs a new case or its own action, matching the artboard);
  `app/src/main/kotlin/org/ort/app/diagnostics/localsave/LocalSaveBundleBuilder.kt` (the existing
  *diagnostics* save/write path — FR-STO-6's full database-and-audio bundle is a different thing
  with a different purpose and must not be confused with it or built by extending it). Constitution
  III ("nothing is deleted quietly" — a restore conflict is shown, never auto-resolved).

  **Owns:** `app/src/main/kotlin/org/ort/app/ui/settings/SettingsExportScreen.kt` (POTA wiring,
  the share-sheet action for a digest/thread transcript/single over's audio), a new
  `app/src/main/kotlin/org/ort/app/backup/**` package (`BackupBundleBuilder`/`BackupRestoreCoordinator`
  — FR-STO-6's full database+audio export and FR-STO-9's restore, genuinely new, not a rename of
  `LocalSaveBundleBuilder`), a new `SettingsBackupScreen.kt` (or a Restore action inside
  `SettingsStorageScreen.kt` — pick one and say why), **one** new `SettingsScreenId` case/row/
  router branch if a new screen is added, `app/src/main/AndroidManifest.xml` (a `FileProvider`
  entry — none exists today), a new `res/xml/file_paths.xml`. **Must not touch:** `ExportCoordinator.kt`'s
  existing ADIF/CSV/JSON/text paths beyond adding the POTA call, `LocalSaveBundleBuilder.kt`,
  any other `SettingsScreenId` case.

  **Tests first:** `AC_171` a digest, a thread transcript and a single over's audio clip each
  share via the platform share sheet through the new `FileProvider`, and the shared file carries
  no user-supplied station name, station-knowledge field or location beyond what the transcript
  text itself already contains; a POTA export produces the same writer output
  `PotaActivityExportWriter` already tests in isolation, now reachable from the screen;
  `AC_170` restoring a bundle reproduces every session, correction and audio file from the source
  device; a record conflicting with one already on the restoring device is shown to the operator,
  never auto-merged or overwritten; nothing already present is deleted by a restore — and a
  round-trip test (export from a seeded database, restore into an empty one, compare) proves it
  rather than asserting each half separately.

  **Ships with it:** a fake `FileProvider`-backed share target for the instrumented/Robolectric
  test, and a fixture "conflicting record" pair for the restore test.

  **Done when:** AC-170, AC-171 hold; the round-trip test passes; recaptured by the tour for
  Settings > Export and the new restore surface at 1.0/2.0.

**Wave J — needs Wave I** (the Settings hot files released by P30).

- [ ] **P31 · Live alerts** *(new `:pipeline` alerts package, `:app` Settings)* — FR-ALR-1..6,
  AC-192..197. Needs Wave I (the Settings hot files pass to this unit next).

  **Read first:** §7.19 FR-ALR-1..6 in full, AC-192..197; `pipeline/src/main/kotlin/org/ort/pipeline/passb/DataPassBResultSink.kt`
  (the same Pass B closure point P24 already hooks — alerts fire from Pass B results, never a
  Pass A partial, per FR-ALR-3); `app/src/main/kotlin/org/ort/app/ui/settings/SettingsRootScreen.kt`,
  `SettingsContent.kt`, `SettingsViewData.kt`. Constitution IV (capture never waits on alert
  evaluation), I (an `INFERRED` match must never read as heard).

  **Owns:** a new `pipeline/src/main/kotlin/org/ort/pipeline/alerts/**` package (watch definitions
  — callsign/keyword/frequency — matching against a resolved Pass B/D result, local notification
  dispatch, its own notification channel created lazily on first use), a second narrow integration
  point in `DataPassBResultSink.kt` (one call to the alert matcher after a transmission's
  attribution is recorded — coordinate with P24's own call in the same file, both additive, not
  overlapping lines), a new `app/src/main/kotlin/org/ort/app/ui/settings/SettingsAlertsScreen.kt`
  plus **one** new `SettingsScreenId` case/row/router branch. **Must not touch:** `:capture-*`,
  `app/src/main/kotlin/org/ort/app/OrtApplication.kt` (register the channel from inside the alerts
  package itself, not the Application class), any other `SettingsScreenId` case.

  **Tests first:** `AC_192` add/edit/delete a callsign, keyword and frequency watch from Settings;
  `AC_193` no watch, match or firing event is ever transmitted off the device (packet capture
  across a session firing all three kinds); `AC_194` a matching Pass A partial never fires an
  alert, and a transmission later corrected away from the watched value at Pass B never does
  either; `AC_195` capture is measurably unaffected while alert evaluation is stalled or slow —
  no added pass-queue latency, no dropped audio; `AC_196` a `CONFIRMED` match's notification and
  an `INFERRED` match's notification are visibly and textually distinct, and the `INFERRED` one
  never states or implies the callsign was heard in that transmission; `AC_197` every watch is
  visible and manageable from one screen.

  **Ships with it:** a fake notification dispatcher so `AC_192`/`AC_196`/`AC_197` are testable off
  a real Android `NotificationManager`, and a stalled-alert-matcher fake for `AC_195`.

  **Done when:** AC-192..197 hold; `dependencyRules` shows no `:pipeline/alerts` edge into
  `:capture-*`; recaptured by the tour for the new Settings screen.

**Wave K — needs Waves G-J** (every screen this wave-set touched must already be in its final
shape before an accessibility pass sweeps it).

- [ ] **P32 · Accessibility pass — WCAG 2.2 AA, FR-A11Y** *(`:app`, every screen)* — FR-A11Y-1..6.
  Last, deliberately, after every other screen change in this wave-set has landed.

  **Partial progress, 2026-09-20 (see `CHANGELOG.md`'s own entry):** two shared-component fixes
  landed — `ScreenHeader`/`DrillInHeader`'s under-floor touch targets (the R-1073 pattern, missed
  on three sibling icons) and `CheckboxRow`/`ToggleRow`'s missing content descriptions (the
  R-380/R-381 pattern, missed on these two). Not done: the full screen-by-screen sweep (Now, Log,
  Detail, Correction sheet, Search, Threads, Stations, Digest, Recordings, Capture, every setup
  step, every Settings screen), and routing the contrast findings (`marker/unknown`,
  `line/control`, `text/disabled`, `text/figure` all measured below their required WCAG ratio
  against `design/design-guide.md`'s own tokens) to whoever owns the guide. Still open for the
  next session on this unit.

  **Read first:** FR-A11Y-1..6, constitution VII's accessibility-floor bullet, constitution
  VIII in full (the device-dump discipline — "accessibility is judged on a device, never from the
  test tree"); `results/ui-audit/register.md` for every still-open `polish`/accessibility-flavoured
  row (R-1073's pattern — a shared component's touch target under 44dp affecting 29+ screens — is
  the shape to look for first, since one component fix closes many rows at once).

  **Owns:** content descriptions and touch-target fixes across `app/src/main/kotlin/org/ort/app/ui/**`
  wherever `uiautomator` evidence shows a gap — this is the one unit in this wave-set allowed to
  touch many screens at once, precisely because it runs after everything else and nothing after it
  competes for the same lines. **Must not touch:** `:pipeline`, `:data`, `:net`, `:telemetry` — this
  is a UI-surface-only pass.

  **Tests first:** a `uiautomator` dump of each of the main reading screens (Now, Log, Detail,
  Search, Settings root, and every screen this wave-set added: Licences, Analytics, Alerts,
  Export/Restore) showing every interactive element with a real accessible name and a ≥44dp target;
  TalkBack reading order asserted for the same screens (an ordered list of node labels, not a
  screenshot); contrast checked against WCAG 2.2 AA for text and the four attribution-state
  markers together (not just each marker in isolation — this is where a marker can pass alone and
  fail beside its own label).

  **Done when:** every screen this wave-set touched, plus the pre-existing main reading screens,
  has current `uiautomator` evidence in the register; no interactive element lacks a content
  description; nothing is below the 44dp floor; recaptured by the tour at 1.0 and 2.0 with the
  device dumps attached to the register rows, per constitution VIII ("a test that passes is not
  that evidence").

**Wave L — beta blockers.** Six units, files disjoint, all parallel. Every one of these exists
because an external tester hits it; none is a feature. Sources: the roadmap research of
2026-09-20 and its red-team pass (`research/market/roadmap/`, git-excluded).

- [ ] **P33 · Pass B honesty — wire the evidence priors, stop asserting `CONFIRMED` uncalibrated**
  *(`:pipeline` passb)* — FR-LEX-9, FR-LEX-17..21, FR-SPK-10, constitution I. **Beta blocker.**

  **Read first:** `pipeline/src/main/kotlin/org/ort/pipeline/passb/PassBFactory.kt` — it builds
  `PriorCombiner(emptyList())` and sets `confirmThreshold = -1f`, so every evidence prior is dead
  in production and the resolver asserts `CONFIRMED` on an uncalibrated score; `:lexicon`'s
  `defaultPriors` and `PriorCombiner`; `CallsignResolver.kt`; `eval/.../Main.kt`, today the only
  caller that builds the real prior set.

  **Owns:** `PassBFactory.kt` and the Pass B construction path in `:pipeline`.
  **Must not touch:** `:app` screens (Wave M owns the UI sweep), `:capture-*`, the prior
  implementations in `:lexicon`.

  **Tests first:** each of the seven priors contributes a non-zero term on a fixture over, and the
  test fails against the empty combiner; with no calibration present the state is `AMBIGUOUS` and
  never `CONFIRMED` (no hand-picked threshold — the threshold arrives with the calibration, per
  constitution VI); the transcript fingerprint carries `calibrationVersion` when one exists.

  **Ships with it:** a fake calibrator so the AMBIGUOUS-until-calibrated rule is testable both ways.

  **Done when:** a device capture shows the inspection surface listing each prior's real
  contribution; a debug dump shows no `CONFIRMED` without a calibration version; **and the tour is
  re-captured for Log, Now, Thread, Station, Digest and Detail-Why at 1.0 and 2.0.** This unit
  carries that re-capture explicitly: constitution VIII's path trigger does not fire for
  `:pipeline`, yet this change alters what every one of those screens states.

- [ ] **P34 · Capture route and sample rate** *(`:capture-android`)* — FR-CAP-2, FR-CAP-2a,
  FR-CAP-3, FR-RUN-11, constitution IV. **Beta blocker.**

  **Read first:** `AndroidAudioIo.kt` — `setEventListener` stores the lambda and nothing invokes
  it, and the class defaults to 48 000 Hz with no negotiation; `AudioRecordSource.kt`'s
  `io.read() < 0` path, today the only real interruption signal; `FakeAudioIo`, which is what every
  existing recovery test actually exercises.

  **Owns:** `AndroidAudioIo` and the route-verification path in the capture service.
  **Must not touch:** `:pipeline`, `:app`.

  **Tests first:** a route-change callback halts capture per FR-CAP-3 and writes a gap of true
  length; an adapter offering only 44.1 kHz negotiates and resamples deterministically instead of
  failing to open; periodic re-verification **never blocks the capture loop** — assert no added
  pass-queue latency and no dropped audio with the verifier stalled (constitution IV).

  **Done when:** H4 and the 8-hour run show route-verified lines throughout and a forced mid-run
  switch halts capture rather than recording the room.

- [ ] **P35 · Durability — stale leases and the migration guard** *(`:data`, `:app` startup)* —
  FR-RUN-8, AC-47, FR-REP-4. **Beta blocker.**

  **Read first:** `WorkQueue.recoverStaleLeases` (implemented, never called);
  `applyHandWrittenSchema`'s unique-index creation; the Migration failure screen.

  **Tests first:** kill the process mid-Pass-B, relaunch, the queue row returns to `READY` and the
  over completes; an upgrade over a database that violates the partial unique index opens and
  shows the failure screen instead of crashing on every launch.

  **Done when:** both are observed on a device, not in Robolectric.

- [ ] **P36 · Outbound channels — field reports in release, private and redacted; Gemma notices**
  *(`:app` fieldreport, `:net`, licences screen)* — D55, D49, FR-OBS-6..12, FR-OBS-10,
  constitution V. **Beta blocker.**

  **Read first:** `FieldReportAppWiring.kt` (the channel is `BuildConfig.DEBUG`-only, so it is
  absent from exactly the signed builds a beta ships); `FieldReportUploadClientFactory.kt` (the
  repository is hardcoded); `RealFieldReportUploadClient.kt` — **the visibility guard is already
  wired and fail-closed; do not rebuild it**; `ScreenFrameCapturer.kt`,
  `FieldReportBundleBuilder.kt`.

  **Owns:** the release wiring, the build-configurable destination, and redaction of
  user-supplied names **at capture**. **Must not touch:** the visibility guard's semantics.

  **Tests first:** a release build carries the channel; a build without a configured destination
  refuses to upload; a bundle built from a screen showing an operator-typed station name contains
  that name in no frame. Redaction happens at capture, not at bundle time — constitution V puts
  names in no channel at all, so a frame that ever held one is already a breach.

  **Done when:** an upload from a signed build lands in the named private repository; a device
  capture of a real bundle shows the redaction; the licences screen renders Gemma's terms and its
  prohibited-use policy.

- [ ] **P37 · Evidence tooling — make `diff.py` able to fail** *(`tools/ui-audit/`)* —
  constitution VIII. **Beta blocker, and it gates the evidence value of every other unit.**

  **Read first:** `tools/ui-audit/diff.py` — it returns 0 except when a manifest file is missing,
  never reads `runId` or `apkHash`, and its 4×4 mean-RGB signature at threshold 6.0 cannot see a
  changed line of text (a one-line difference moves it by about 1.0). The tour's own stale-manifest
  fix landed already; the leak moved downstream into the comparison step.

  **Tests first:** a run-A manifest against a run-B directory exits non-zero; a one-line text
  change reports `changed`; a mismatched `runId` or `apkHash` fails rather than passing quietly.

  **Done when:** a **live tour invocation against a deliberately stale manifest exits non-zero**,
  with the transcript committed. Until this lands, no `diff.py` "unchanged" counts as evidence
  anywhere in this plan.

- [ ] **P38 · Packaging and the model mirror** *(buildSrc, release workflow)* — FR-AST-10..14,
  D44. **Beta blocker.**

  **Read first:** `verifySherpaNativeLibrariesPackaged` (it reads the `full` APK only, so the slim
  `play` artifact the beta ships is unverified); `bundled-assets.json`'s `mirrorUrl` entries and
  `ModelMirrorPublisher`.

  **Tests first:** the packaging check runs over `playRelease` and fails when a native library is
  missing from that artifact.

  **Done when:** the check lists the native libraries in the play artifact, and every `models-v1`
  asset returns HTTP 200 from a clean client.

**Waves M-Q — planned, expanded into prompts when each wave starts.** **M** UI honesty sweep
(hide the unreachable Improve destination, share from the open screen, an alert refusal must not
open the coalescing episode, unattributed overs in the thread order, the storage screen's
"next deletion" claim, the Detail-Why drill-in seam) · **N** operator work, parallel throughout
(hardware sessions, the Q2 tape, the privacy policy, the listing) · **O** TalkBack pass, backup
round trip across a wipe, the analytics decision under D54 · **P** post-beta repair, triaged from
what the twelve testers actually report · **Q** desktop accuracy chain (transcripts exporter →
dev-fold number → the labelling pilot → `distil-small.en`). Off the beta path but inside 1.0:
identity behind D52's two gates, the Pass C prototype and verdict per D53, and the Teams surface
per D51 once its requirement ids exist.

**Manual, not a builder session (yours).** The hardware protocol H1-H15 and the labelled
validation hour / dev-fold M3 measurement (`results/e2e-audit/checklist.md`,
`docs/reference/labelling-protocol.md`) stay the operator's — no session here can drive a real
TH-D75A or label a real tape unattended. Revisit after Wave G-K lands, since P22-P23 change what
setup and model acquisition look like on the device H1-H15 exercises.

**After the fork.** M6 identity and voice library · M7 rig · M8 streaming · M9 digest, station
knowledge, contribution · M10 tiers and reprocessing · M11 reference levers. **Deliberately not
decomposed** — M4 can delete Pass C, which changes what several of them contain. (M5 was on this
list until Wave E decomposed it: the reader is not actually fork-dependent — only FR-UI-8's
inspection surface is, and it renders whichever lattice source survives, one screen either way,
as implementation-plan M5 already says.)

---

## The prompts

Paste one to open a session. Each names what to read, the files it **owns**, the files it must
**not touch** (this is what keeps a wave conflict-free), the tests to write first, and how to
know it is done.

**Every prompt is preceded by this standing preamble — paste it too, or rely on `AGENTS.md`
being loaded automatically:**

> Read [`.specify/memory/constitution.md`](../.specify/memory/constitution.md) and
> [`AGENTS.md`](../AGENTS.md) before anything else. Open with a **Constitution Check**: name the
> principles that bear on this work and what they forbid here. Strict TDD — the test is written
> and seen to fail for the right reason before the code. Ship each interface's behavioural fake
> in the same change. Touch only the files this prompt says it owns. **If the exit criteria
> cannot be met, stop and say why** rather than leaving a module half-done. **Before you commit,
> append an entry to [`../CHANGELOG.md`](../CHANGELOG.md)** in the format documented at that
> file's top (scope, requirements/ACs, what changed, how it was verified, what was left open) —
> this is part of the definition of done, not a follow-up.

*The principles most often relevant, by prompt: P1 VII · P2 VI · P3 I, VII · P4 III, IV, VII ·
P5 II, III, IV · P6 VI · P7 I, VI · P8 IV, V, VII · P9 II, IV · P10 I, II · P11 I, VI.*

### P1 · Foundation

> Read `spec/technical-design.md` §2–3 and `spec/test-plan.md` §3, §5, §8.
>
> Build the Gradle multi-module skeleton for every module in technical design §2 — empty but
> wired — plus `:core` and `:testing` in full. Strict TDD: every test written and failing before
> its implementation.
>
> **Owns:** root build files, `:core`, `:testing`, `.github/workflows/`, `tools/spec-check`.
> **Do not touch:** any other module beyond an empty stub.
>
> Write first: the `dependencyRules` task must fail on a deliberately-added `:capture-api` →
> `:asr-api` edge before you remove it. The spec-integrity checker must fail on a deliberately
> introduced dangling reference before you fix it — implement all seven checks in test-plan
> §8.1. Then `:core`: `Clock` monotonic/wall separation; `SampleClock` mapping sample position
> to time and surviving a wall-clock jump (F14, FR-RUN-15..18); ULID ordering and uniqueness;
> `PassFingerprint` equality and staleness (technical design §3.4); `ResolvedConfig` merge
> precedence and a stable `configHash`; the four attribution states; the transmission lifecycle
> with an asserted legal-transition table. Then `:testing`: `TestClock`, fixture loaders, and
> the annotation that lets a test carry a requirement id into the coverage matrix.
>
> Also in this session, because the constitution check surfaces them and nothing else owns them:
> **AC-93** — a CI job asserting the app installs and runs at the minimum supported API level
> (NFR-5); the `coverageMatrix` Gradle task; and **the LICENSE file**, which the repository is
> currently public without. D11 intends open source and the constitution's governance section
> records its absence as outstanding — Apache-2.0 is the usual fit for a project that wants a
> Play Store path and a patent grant, but **ask before choosing**; it is a product decision.
>
> **Done when:** CI is green, both meta-guards demonstrated failing then passing, AC-91 holds,
> `results/coverage-matrix.md` generates, and the LICENSE question is answered rather than left.
> `:core` has no Android dependency.

### P2 · Corpus pipeline

> Read `spec/functional-spec.md` §14A.2 and §14A.3, and `spec/implementation-plan.md` M0.
>
> Create the `corpus/` Python subproject with its own `pyproject.toml` and CI job. Strict TDD.
>
> **Owns:** `corpus/`. **Do not touch:** any Kotlin module.
>
> Write first: a manifest placing one session in two folds is rejected; a synthetic entry in
> `eval` is rejected outright (FR-TST-9); reading `eval` without an explicit flag is refused
> (AC-100); every emitted report carries the fold that produced it. Then the fold-assignment
> checker enforcing §14A.2 — whole sessions only, at least one eval session containing no train
> station, the two noise tapes separated.
>
> Then acquisition: fetch and verify Paderborn HF (Zenodo 4247491), Fearless Steps, the
> ATC merge and ISOLET. Normalise everything to 16 kHz mono FLAC. Record source, licence and
> checksum per entry. Resumable and idempotent.
>
> Then harness v0: WER, callsign precision/recall, boundary precision/recall, rejection rate by
> reason, attribution accuracy — computed over hand transcripts, reported **per source** as well
> as aggregate (FR-TST-8), with a run fingerprint.
>
> **Done when:** `make corpus` produces a validated four-source manifest with licences, and the
> fold gate refuses `eval` without the flag.

### P3 · Lexicon — grammar

> Read `spec/functional-spec.md` §7.4 and `spec/technical-design.md` §9.
>
> Build `:lexicon`'s structural half. Pure Kotlin, no Android, fast tests. Strict TDD.
>
> **Owns:** `:lexicon` (types, units, trie, FSA, scoring). **Do not touch:** `:eval`, priors,
> calibration — those are P7.
>
> Write first: the variant table maps every spoken form to its unit and rejects unknown forms;
> the lattice retains alternatives with scores rather than a best path (FR-LEX-5); **AC-10** — a
> structurally valid callsign with a non-US prefix and zero database hits still resolves as a
> candidate; **AC-11** — an unallocated prefix never reaches `CONFIRMED`; **AC-12** — NATO,
> letter-name and legacy pronunciations of one callsign yield one result; modifiers and
> reciprocal forms (`/P`, `/M`, `K7ABC/VE7`, `VE7/K7ABC`) parse; confusion-weighted substitution
> costs less for B/D/E/P/V/T than for an arbitrary pair (FR-LEX-10).
>
> Then: `PhoneticUnit` and the variant table as versioned assets, the ITU prefix trie, the
> callsign grammar FSA with beam search over lattice slots, and the confusion cost matrix.
> Structural validity against the ITU table is the **only** hard filter.
>
> **Done when:** AC-10, AC-11, AC-12 pass, and the module builds against `:core` and fakes only.

### P4 · Capture and segmentation

> Read `spec/technical-design.md` §5–6 and `spec/functional-spec.md` §7.1–7.2.
>
> Build `:capture-api` and `:segment`. Pure JVM — no Android APIs anywhere in this session.
> Strict TDD.
>
> **Owns:** `:capture-api`, `:segment`. **Do not touch:** `:capture-android`, `:data`.
>
> Write first: **AC-89** — a WAV replays through the source faster than real time with results
> identical to real-time replay; **AC-97** — a 48 kHz input resamples deterministically to
> 16 kHz and the resampler identity is recorded; **AC-3** — pre-roll captures audio from before
> the VAD trigger; ring-buffer overrun is detectable rather than silent; **AC-69** boundary
> precision/recall against hand-marked keying times; **AC-70** a stuck carrier is split at the
> configured maximum; **AC-71** the merge/split trade is measured, not tuned by ear; **AC-72**
> segments below the floor are `rejected:too_short` with no model invoked; **AC-95** pre-roll
> and post-roll together retain a transmission complete; **AC-94** — the segmenter **cannot
> accept a `Tier`**, enforced by its constructor signature (FR-SEG-7, CON-SEG-1).
>
> Then: `CaptureSource` with `deviceFormat`/`outputFormat`, `WavFileSource`, the fixed-point
> polyphase resampler, the lock-free ring buffer with pre-roll snapshot, the Silero VAD wrapper,
> and the `IDLE → SPEECH → HANGOVER → CLOSED` segmenter writing incrementally rather than
> buffering.
>
> **Done when:** AC-3, AC-69..72, AC-89, AC-94, AC-95, AC-97 pass and `WavFileSource` is in
> `:testing` for everything downstream.

### P5 · Data layer and queue

> Read `spec/technical-design.md` §7 and §12, and `spec/functional-spec.md` §7.14 and §8.
>
> Build `:data`. Room, FTS5, migrations, the durable queue and the transmission lifecycle.
> Strict TDD, Robolectric where a device is not needed.
>
> **Owns:** `:data`. **Do not touch:** `:capture-*`, `:pipeline`.
>
> Write first: **AC-45** — with every pass stalled, segments still reach the durable queue;
> **AC-47** — killing the process mid-pass returns `PROCESSING` to `CAPTURED` and completes with
> identical results; **AC-99** — a pass that hangs is cancelled at its deadline, marked `FAILED`,
> and the queue keeps draining (use a `FakeAsrEngine` that never returns); **AC-51** — a
> repeatedly failing pass lands `FAILED` with its error and does not block the queue;
> **re-enqueueing a completed pass succeeds** — the partial unique index covers active states
> only; **AC-53** — migration from every released schema fixture preserves audio and superseded
> transcripts; **AC-54** — reconciliation reports an orphaned file and a dangling row and
> deletes neither; **AC-31** — a superseded transcript stays retrievable, with exactly one
> current per transmission enforced by a partial index.
>
> Then: the entities in functional spec §8 including `Voiceprint` enrolment fields, station
> knowledge, `Thread.kind` and `ContributionItem`; FTS5 over the whole transcript table filtered
> in the join; WAL; the queue with run-id leasing and deadlines; the lifecycle state machine.
>
> **Done when:** AC-31, AC-45, AC-47, AC-51, AC-53, AC-54, AC-99 pass.

### P6 · Synthetic corpus and the two probes

> Read `spec/functional-spec.md` §14A.3, D22, and `spec/build-plan.md` S1.3–S1.7.
> Requires P2.
>
> **Owns:** `corpus/synth/`, `corpus/probes/`. **Do not touch:** the manifest or acquisition
> code from P2 except to add entries.
>
> First, the channel model: fit the degradation from Paderborn's parallel clean/degraded pairs
> and expose it as a deterministic transform. Test that applying it to a clean source reproduces
> the measured degradation within tolerance.
>
> Then the synthetic generator: ULS callsigns → phonetic expansion → local neural TTS **plus
> spliced real ISOLET/ATC letter and digit audio** → the learned channel → labelled audio. Test
> that labels always match audio, that phonetic expansion round-trips, that spliced units land
> at declared offsets, and that output is structurally barred from `eval`.
>
> Then run the two probes and **write up each as a decision**:
>
> - **R4** — a WeSpeaker or 3D-Speaker embedder over Fearless Steps, clustered, measured against
>   its diarization labels. Report separation on degraded narrowband voice and the false-match
>   rate at candidate thresholds (FR-SPK-14). A poor result deletes much of M6 and part of D29,
>   so say so plainly rather than hedging.
> - **R1** — LoRA fine-tune `distil-small.en` on ten minutes of anything, `merge_and_unload()`,
>   export with sherpa-onnx's script, load it, transcribe. A failure reopens the runtime choice.
>
> **Third-party corpora carry their own splits** (Principle VI). Fearless Steps and the ATC
> merge have their own train/dev/eval divisions — report against those and say so; do not
> silently fold them into this project's structure, and do not treat a good number on someone
> else's eval set as a number on ours.
>
> **Done when:** both probes have written verdicts in `results/` — each stating the corpus, its
> split, the model and the run fingerprint — and the generator's output is in the manifest as
> `train`/`dev` only.

### P7 · Lexicon — ranking, calibration, harness

> Read `spec/functional-spec.md` §7.4 (priors, calibration, cold start) and
> `spec/technical-design.md` §9.3–9.6, §17. Requires P3, and P2 for the harness.
>
> **Owns:** `:lexicon` ranking and calibration, `:eval`. **Do not touch:** the grammar and trie
> from P3 except through their public interfaces.
>
> Write first: each prior's contribution is **clamped**, so no prior can eliminate a candidate
> (FR-LEX-9); cold start contributes **exactly zero**, not a default, and widens the interval
> (FR-LEX-31); the propagation prior demotes but never eliminates and is suppressed on known
> repeater frequencies (FR-LEX-25..27); **AC-55** — a confidence of 0.9 corresponds to ~90%
> observed accuracy on the dev fold, shown as a reliability diagram; **AC-56** — raising the
> precision target moves the `CONFIRMED` threshold and the user never sets a raw score;
> **AC-15** — the text-derived lattice produces the same type as an acoustic one and records its
> source; **AC-90** — two harness runs over the same corpus, model and configuration are
> byte-identical within a fixed machine, provider and thread count.
>
> Then: the clamped log-odds prior combination, the static propagation model, Platt calibration
> fitted on train+dev and verified on eval, threshold-from-precision-target derivation, the
> text-derived lattice builder, and `:eval` v1 with per-tier reliability diagrams and per-prior
> ablation.
>
> **Done when:** AC-15, AC-55, AC-56, AC-90 pass, and the harness reports callsign
> precision/recall with a per-prior ablation on the dev fold. **The text-derived path is M4's
> baseline — record its number.**

### P8 · Android capture spine

> Read `spec/technical-design.md` §4–7, §14 and `spec/functional-spec.md` §7.11, §7.16.
> Requires P4 and P5.
>
> **Owns:** `:capture-android`, `:pipeline`, `:app` (status surface and permissions only).
> **Do not touch:** `:asr-*`, `:lexicon`, the reader UI.
>
> Write first, with Robolectric and fakes: **AC-2** and **AC-98** — the route is verified after
> the first read, a mismatch halts, and a deliberately selected built-in mic is legal but
> persistently labelled; **AC-48**/**AC-49** — an interruption produces a `CaptureGap` with
> correct bounds, capture resumes, and a gap is distinguishable from silence; **AC-46** — under
> induced overload the shed order is followed and **no audio is lost at any level**, verified by
> comparing segment counts against a known input; **AC-103** — the summed resident model
> footprint stays inside the tier budget; battery below the critical threshold and not charging
> enters shed level 4; **AC-65** — liveness comes from the heartbeat, verified by forcing a case
> where `isIgnoringBatteryOptimizations()` returns true and the session is killed anyway;
> **AC-5** — an unclean end is reported next launch with its last heartbeat; **AC-61** — the
> notification never contains transcript text; **AC-96** — a session captured with the
> continuous archive on can be re-segmented with different VAD parameters.
>
> Then: `AudioRecordSource` with device enumeration and route verification, interruption and
> focus handling, the FLAC store with decode-and-compare before deleting staged PCM, the
> continuous-archive writer (default off), `CaptureService` with wake lock and heartbeat, the
> OEM guidance table keyed on `Build.MANUFACTURER` with the four ColorOS steps, the "prove it"
> 30-minute test, the shed controller with hysteresis, model residency, and the capture status
> surface.
>
> **Done when:** the app captures unattended to a durable queue with no ASR present, and every
> criterion above passes on Robolectric or the device.

### P9 · GATE — the 8-hour run

> Read `spec/test-plan.md` §7 and `spec/implementation-plan.md` M2 exit criteria.
> Requires P8. Mostly manual, on the reference device.
>
> Build the on-device harness runner first: the same manifest and the same report format as the
> JVM harness, run as an instrumented test, corpus pushed to app-private storage and the report
> pulled back. A desktop-only harness cannot measure T3, which is why this is an M2 obligation.
>
> Then run the device matrix in order: **D8** (the 30-minute "prove it" run — record its
> prediction *before* the long run), **D4** (8 hours, screen off, unattended, all four ColorOS
> interventions applied — **AC-64**), **D5** (the same run with exemptions removed, expected to
> fail and required to be correctly reported — AC-5), then D1, D2, D3, D9, D10, D11, D12.
> Publish measured endurance rather than asserting the 8-hour pass (AC-76, NFR-7).
>
> **Done when:** AC-64 passes and D8 correctly predicted D4's outcome.
>
> **If D4 fails with all four interventions applied, stop.** Do not work around it. Re-open D19
> — the options are a second capture device or a different reference phone, and that is a
> product decision.

### P10 · ASR and hallucination control

> Read `spec/technical-design.md` §8 and `spec/functional-spec.md` §7.3. Requires P8, and the
> dev noise tape from your recording session.
>
> **Owns:** `:onnx`, `:asr-api`, `:asr-sherpa`. **Do not touch:** `:capture-*`, `:lexicon`.
>
> Write first: **AC-7** — each of the six hallucination controls is individually demonstrable
> with a crafted input; **AC-6** — the development noise tape produces **zero** accepted
> transcripts, every segment rejected with a recorded reason; **AC-8** — rejected segments
> retain audio and are reachable behind a filter; F13 — an invalid model falls back to a lower
> one and surfaces it; a side-loaded model is signature-checked and probe-run before activation;
> the engine's timeout path returns `FAILED` rather than hanging.
>
> Then: `:onnx` session lifecycle with the Pinned/Hot/Cold residency classes; `:asr-sherpa`
> implementing `AsrEngine` with n-best and `no_speech_prob` retained; the six controls as named
> `RejectionRule`s ordered cheapest-first, **with thresholds fitted against the dev noise tape
> rather than inherited from Whisper's defaults**; transcript versioning with exactly one
> current; the model registry with declared memory footprints.
>
> **Done when:** AC-6, AC-7, AC-8 pass. **AC-6 is the single most important test in the plan.**

### P11 · End-to-end, then the M4 fork

> Read `spec/implementation-plan.md` M3 and M4, and `spec/technical-design.md` §9.7.
> Requires P7 and P10.
>
> First, wire it end to end: capture → VAD → Pass B → text-derived lattice → resolver →
> attribution, on the device, with Pass B as a real `Pass` carrying its fingerprint. Add latency
> instrumentation and confirm **AC-73** (p95 ≤ 2 s at T1+) and **AC-75** (no backlog growth at
> 15% activity). **Record M3's callsign precision and recall on the dev fold — this is the
> number M4 must beat, and it must be written down before M4 starts.**
>
> **This is the first point in the project where an attribution reaches a screen, so Principle I
> binds here rather than at M5.** The attribution type must make its state non-optional
> (FR-SPK-10) — a compile error, not a lint warning — and the transmission list must show all
> four states distinguishably **without relying on colour** (FR-A11Y-1). Write those tests
> first; they are cheap now and expensive to retrofit once a reader UI exists.
>
> Then M4. Build the `UnitSpotter` interface and the sherpa-onnx open-vocabulary KWS
> implementation over the ~36 phonetic units, reporting per-unit precision/recall. Run the TD2
> probe — can sherpa-onnx expose Whisper encoder hidden states on Android? — which gates the
> CB-Whisper approach entirely; half a day, and if it fails, skip that option.
>
> Then **the comparison, which is the deliverable**: text-derived vs KWS vs encoder-similarity,
> same audio, same resolver, same dev fold, reporting callsign precision, callsign recall,
> per-unit metrics, latency cost and resident memory for each.
>
> **Done when:** the comparison is reported and **one of three branches is chosen explicitly and
> written into the functional spec before M5 begins** — proceed as specified, collapse to the
> text path and delete Pass C, or tier-gate Pass C to T2+. Implementation plan M4 has the
> consequences of each.

### P12 · Make capture actually work end to end on a device

> Read `spec/technical-design.md` §5–7, the `CHANGELOG.md` entries for the v0 smoke build, and
> `pipeline/src/main/kotlin/org/ort/pipeline/capture/RealCaptureService.kt` in full. Requires
> P8, P10, P11 (all done).
>
> **Owns:** `:capture-android`, `:pipeline`, `:app`'s capture wiring. **Do not touch:** the
> reader UI (P13–P17 own that), `:lexicon`, `:data`'s schema.
>
> Three defects that on-device testing found and no test suite here could, then the loop that
> makes a captured segment become a transcript:
>
> 1. **Route selection fabricates a device id, so capture halts on its first read.**
>    `AndroidAudioIo.builtInMicDescriptor()` returns id `"builtin"`; a real
>    `AudioDeviceInfo.getId()` is a number, so `RouteVerifier` correctly reports a mismatch
>    (AC-2 working exactly as designed) and `AudioRecordSource` halts. Select a **real**
>    enumerated device. Write first: a test over `AndroidAudioIo`'s enumeration→selection path
>    proving the selected descriptor's id is one `availableDevices()` actually returned.
> 2. **The status surface asserts `isCapturing = true` unconditionally**, so a halted capture
>    still reads "Capturing" — the exact silent failure constitution IV forbids, in the one
>    screen whose job is to report it. Track real state and surface the failure reason. Write
>    first: capture fails → the status surface says so, naming the reason.
> 3. **Nothing drains the queue and nothing runs ASR**, so a captured transmission can never
>    become a transcript. `PassDrainRunner` (P8), `PassB` (P11) and `RealSherpaDecoder` (P10
>    follow-up) all exist and none is constructed in the running app. Wire them, with the model
>    fetched to app-private storage (`asr-sherpa/README.md` documents the URL and cache layout)
>    rather than committed. Write first: a Robolectric test that a queued transmission reaches
>    `COMPLETE` with a transcript row, using `FakeAsrEngine`.
>
> Also: **replace `EnergyVadModel`**, the RMS-threshold stand-in, with the real Silero VAD, or —
> if no usable Silero ONNX build can be resolved for Android — say so plainly and leave the
> stand-in clearly labelled, exactly as the R4/R1 probes did. Do not quietly ship an energy
> threshold as if it were the specified VAD.
>
> **Done when:** a debug APK captures on a real device, the status surface tells the truth about
> whether it is capturing, and a spoken callsign produces a transmission row with a transcript.
> **If the device work cannot be verified because no device is available to the session, say so
> and stop** — this prompt's whole point is behaviour no fake can establish.

### P13 · Compose foundation: theme, navigation, the design canvas made real

> Read `design/canvas/` (seven designed screens: Main, Log, Detail, Menu, Timeline, States,
> Editorial), `spec/functional-spec.md` §13 (interaction principles) and FR-A11Y-1..6.
>
> **Owns:** `:app` UI infrastructure, `buildSrc`'s `ort.android-app` convention plugin (Compose
> wiring). **Do not touch:** `:pipeline`, `:capture-*`, `:data`.
>
> The stack decision (D15) says Compose and the app has none — P8 and P11 both shipped plain
> `TextView`s with an explicit note that Compose was out of their scope. Land it: the Compose
> dependency and compiler in the convention plugin, a theme derived from the canvas (dark-first),
> a navigation host with the drawer the canvas specifies, and the four-state attribution markers
> as a **reusable component**, not a string built per screen.
>
> Write first: **AC-62** — every attribution state is distinguishable in greyscale (render each
> and assert distinct non-colour content); **AC-63** — the reader survives maximum system font
> scale without clipping (FR-A11Y-3) and every interactive element carries a content description
> (FR-A11Y-2). These are cheap now and expensive once six screens exist.
>
> **Done when:** the app's existing status and transmission-list surfaces are Compose screens
> behind the navigation host, with no behaviour change and their existing tests still passing or
> honestly rewritten, and AC-62/AC-63 hold.

### P14 · Reader: live view and transmission detail

> Read `spec/functional-spec.md` §7.9 (FR-UI-1..8), `design/canvas/Main.dc.html`,
> `Log.dc.html`, `Detail.dc.html`, `States.dc.html`. Requires P13.
>
> **Owns:** `:app` reader screens. **Do not touch:** `:data`'s schema, `:pipeline`.
>
> Write first: **FR-UI-1** — a transmission appears in the live view as it is captured, newest
> first, and a superseded partial is *visibly* superseded rather than silently replaced;
> **FR-UI-4** — all four attribution states are visually distinct and never colour-only (reusing
> P13's component); **FR-UI-5** — playback of a transmission's retained audio alongside its
> transcript.
>
> **Done when:** capturing with the app open shows transmissions arriving live, each openable to
> a detail view with its audio, transcript, attribution state and confidence.

### P15 · Search and threads

> Read `spec/functional-spec.md` FR-UI-2, FR-UI-3, `spec/technical-design.md` §12.1 (the FTS5
> index), `design/canvas/Log.dc.html`. Requires P14.
>
> **Owns:** `:app` search/thread screens, `:data` query surface (new DAO queries only — **no
> schema change**; P5's FTS5 table and triggers already exist and nothing queries them yet).
>
> Write first: full-text search returns a transmission by a word in its transcript and filters by
> callsign, frequency and date (FR-UI-3); a thread view groups transmissions into conversations
> showing why each was attributed (FR-UI-2).
>
> **Done when:** the FTS5 index P5 built is actually reachable from the app, with filters, and
> threads render.

### P16 · Correction, the inspection surface, and labelled-sample capture

> Read `spec/functional-spec.md` FR-UI-6, FR-UI-8, FR-OBS-4, Q8 (tiered correction),
> `spec/implementation-plan.md` M5. Requires P14, P7.
>
> **Owns:** `:app` correction and inspection screens, `:data`'s `CorrectionEntity` write path.
>
> Write first: one-tap correction of an attribution propagates per FR-SPK-7 and sets the
> `CORRECTED` lock (a correction must never be re-propagated over); the inspection surface renders
> the phonetic lattice, the candidate list **and the per-prior breakdown** `PriorCombiner` already
> produces (FR-UI-8) — this is the surface that makes the resolver auditable, and P7 built the
> data for it with nothing yet displaying it. Then **FR-OBS-4**: record a labelled sample from a
> live session into the format `corpus/`'s harness already reads, per
> `docs/reference/labelling-protocol.md`.
>
> **Done when:** a wrong attribution can be corrected in one tap, the reasoning behind any
> attribution is inspectable, and a session can contribute a labelled sample to the corpus.

### P17 · Station and frequency views with activity patterns

> Read `spec/functional-spec.md` FR-UI-9..12, `design/canvas/Timeline.dc.html`. Requires P15.
>
> **Owns:** `:app` station/frequency screens, `:data` aggregate queries.
>
> Write first: **FR-UI-12** — a pattern display distinguishes "not heard" from "not listening",
> using the `CaptureGap` rows P5 and P8 already record. This is the requirement most likely to be
> silently got wrong: an empty hour looks identical either way unless the gap data is consulted,
> and presenting "not listening" as "not heard" is a fabricated absence.
>
> **Done when:** everything heard from one station and everything heard on one frequency each
> have a view, with activity patterns that never present a capture gap as silence.

### P18 · Model acquisition through `:net`

> Read `.specify/memory/constitution.md` principle V, `spec/functional-spec.md` FR-ASR-8..11 and
> FR-AST-1..8, `spec/technical-design.md` §8.4, and `asr-sherpa/README.md` (which documents the
> sherpa-onnx model URLs and cache layout the gated real-decode tests already use).
>
> **Owns:** `:net` — the whole module, which is currently an empty stub. **Do not touch:**
> `:pipeline`, `:capture-*`, `:asr-*`, `:app` (a later, tiny commit wires the call site; keeping
> this prompt to one module keeps it conflict-free while the reader UI is being built in
> parallel).
>
> P12 wired a real ASR engine and a real Silero VAD binding, and both are inert because **no
> model file exists on the device**. P12 was right not to fetch one: `:pipeline` may not link an
> HTTP client, and the capture/processing path may never touch the network at all — that is
> principle V, and `dependencyRules` enforces the module half of it.
>
> Write first: a fetch that **verifies a checksum before the file is usable** and refuses on
> mismatch, leaving nothing half-written that a later run could mistake for a good model
> (FR-AST-2); resumability, so a dropped connection on a 100 MB download does not restart from
> zero (the corpus pipeline's `acquire.py` already does exactly this — read it, the semantics
> should match); a side-loaded file is signature-checked and probe-run **before** activation and
> refused otherwise, keeping the previous model active (FR-ASR-8, FR-AST-2 — `:asr-sherpa`'s
> `ModelActivation` already implements the activation half, so do not duplicate it: `:net`'s job
> ends when verified bytes are on disk).
>
> Everything network-facing goes behind an interface with a behavioural fake, so `:app` and every
> test can drive acquisition without a network.
>
> **Done when:** a model can be fetched, resumed, checksum-verified and landed in app-private
> storage entirely within `:net`, with `dependencyRules` still green — and the capture path still
> has no path to an HTTP client, which is the point.

---

## Underlying breakdown

The phase detail below is what the prompts above draw on — the finer-grained session list, kept
for when a prompt needs unpacking mid-session.

---

## Phase 0 — foundation

### S0.1 · Repo skeleton, Gradle, CI

**Goal.** A repository that builds nothing but proves the rules.

**Tests first.** The dependency-rules task must fail on a deliberately-added forbidden edge
(`:capture-api` → `:asr-api`) before it is removed. The spec-integrity checks must fail on a
deliberately-introduced dangling reference before it is fixed.

**Build.** Gradle multi-module skeleton for all modules in technical design §2, empty but
wired; `dependencyRules` task; ktlint/detekt; GitHub Actions per test-plan §8; the
spec-integrity checker; `results/` directory.

**Exit.** CI green on an empty build. Both meta-checks demonstrated failing then passing.

> **Prompt.** *Read `spec/technical-design.md` §2 and `spec/test-plan.md` §8. Create the Gradle
> multi-module skeleton, the `dependencyRules` task, ktlint/detekt, and the GitHub Actions
> workflow. Write the spec-integrity checker with the seven checks in test-plan §8.1. Prove each
> guard fails before it passes.*

### S0.2 · `:core`

**Goal.** The types every other module depends on.

**Tests first.** `Clock` monotonic/wall separation and `TestClock` advance; `SampleClock` maps
sample position ↔ time and survives a wall-clock jump (F14, FR-RUN-15..18); ULID ordering and
uniqueness; `PassFingerprint` equality and staleness semantics (§3.4); `ResolvedConfig` merge
precedence (defaults ← profile ← override) and stable `configHash`.

**Build.** Ids, `Clock`, `SampleClock`, `PassId`, `PassFingerprint`, `ResolvedConfig`, `Result`,
domain enums including the four attribution states and the transmission lifecycle with its legal
transition table.

**Exit.** AC-91 demonstrable. Lifecycle transition table asserted. No Android dependency.

> **Prompt.** *Read `spec/functional-spec.md` §4, §7.14 and `spec/technical-design.md` §3.
> TDD `:core`: ids, Clock/SampleClock, PassId, PassFingerprint, ResolvedConfig, the four
> attribution states, and the transmission lifecycle with an asserted legal-transition table.
> No Android dependencies.*

### S0.3 · `:testing`

**Goal.** The fake harness other sessions depend on.

**Build.** `TestClock`, fixture loaders, the corpus manifest reader, deterministic seeds, and
the assertion helpers that let a test be named for a requirement id and appear in the coverage
matrix.

**Exit.** A trivial test in `:core` is named for a requirement and appears in
`results/coverage-matrix.md`.

---

## Phase 1 — corpus and the two cheap probes

Python subproject at `corpus/`, its own `pyproject.toml`, its own CI job. **Both probes are
decision gates and both are cheap — do them before Phase 2.**

### S1.1 · Manifest, validator, fold gate

**Tests first.** A manifest with a session in two folds is rejected; a synthetic entry in `eval`
is rejected (FR-TST-9); reading `eval` without the explicit flag is refused (AC-100); every
report carries its fold.

**Build.** Manifest schema, validator, fold-assignment checker enforcing §14A.2's rules
(whole sessions, ≥1 eval session with no train station, noise tapes separated).

> **Prompt.** *Read `spec/functional-spec.md` §14A.2 and §14A.3 and `spec/implementation-plan.md`
> M0. Create the `corpus/` Python subproject. TDD the manifest schema, validator and fold gate.
> `eval` must be unreadable without an explicit flag, and synthetic data must be rejected from
> `eval` outright.*

### S1.2 · Acquisition

**Build.** Fetch and verify Paderborn (Zenodo 4247491), Fearless Steps, the ATC merge and
ISOLET; normalise everything to 16 kHz mono FLAC; record licence, source and checksum per entry;
emit manifest entries. Resumable, checksum-verified, idempotent.

**Exit.** `make corpus` produces a validated manifest with four sources and their licences.

### S1.3 · Channel model (D22)

**Tests first.** Applying the learned channel to a clean Paderborn source reproduces the
measured degradation within tolerance; the transform is deterministic.

**Build.** Fit the channel from Paderborn's parallel clean/degraded pairs; expose it as a
transform for synthetic audio.

### S1.4 · Synthetic callsign generator (D22)

**Tests first.** Generated labels always match generated audio; the phonetic expansion of a
callsign round-trips; spliced ISOLET units land at the declared offsets; output is barred from
`eval`.

**Build.** ULS callsigns → phonetic expansion → local neural TTS **plus spliced real ISOLET/ATC
letter and digit audio** → S1.3's channel → labelled audio.

### S1.5 · Harness v0

**Build.** Metric implementations — WER, callsign precision/recall, boundary precision/recall,
rejection rate by reason, attribution accuracy — over hand transcripts, reported per source
(FR-TST-8) with a run fingerprint.

### S1.6 · PROBE: R4 — speaker separation *(decision gate)*

**Why now.** M6 grew a lot (voice library, nets) and D29's station knowledge leans on identity
working. Fearless Steps ships diarization labels on degraded analog comms, so this is answerable
**before any tape exists**, and a negative result deletes a large part of M6.

**Do.** Run a WeSpeaker/3D-Speaker embedder over Fearless Steps; cluster; measure against the
labels. Report separation on degraded narrowband voice, and the false-match rate at candidate
thresholds (FR-SPK-14).

**Decide.** Good separation → build M6 as specified. Poor → threading degrades to
per-transmission attribution, the voice library is not built, and D28/D29 are amended.

### S1.7 · PROBE: R1 — ONNX export *(decision gate)*

**Do.** LoRA fine-tune `distil-small.en` on ten minutes of anything, `merge_and_unload()`,
export with sherpa-onnx's script, load the result, transcribe. Half a day.

**Decide.** Works → D13 is safe. Fails → the runtime choice reopens and much of the technical
design changes.

### S1.8 · Record and label the validation hour *(yours, not a session)*

FM repeater conversation, one HF/DX session, some scanner, plus **two separately-recorded
20-minute noise tapes**. Lossless. Write the labelling protocol (Q16) against session one, then
label: callsigns and speaker turns only.

---

## Phase 2 — lexicon, off-device

Pure Kotlin, fast tests, no device. This is the largest single accuracy gain available and it
runs on a desktop JVM.

| Session | Tests first | Exit |
|---|---|---|
| **S2.1** types, units, variants | Variant table maps every spoken form to a unit; unknown forms rejected; lattice retains alternatives with scores | FR-LEX-5 held structurally |
| **S2.2** ITU trie + grammar FSA | AC-10 DX callsign with no database entry resolves; AC-11 unallocated prefix never `CONFIRMED`; modifiers and reciprocals parse; beam search returns ranked paths | AC-10, AC-11 |
| **S2.3** confusion scoring | B/D/E/P/V/T collapse costs less than an arbitrary substitution; matrix is a versioned asset | FR-LEX-10 |
| **S2.4** priors | Each prior's contribution is clamped; no prior can eliminate a candidate; cold start contributes exactly zero, not a default | FR-LEX-9, FR-LEX-31 |
| **S2.5** propagation | Asymmetric — implausible demotes, never eliminates; suppressed on known repeater frequencies | FR-LEX-25..27 |
| **S2.6** calibration | 0.9 means ~90% on held-out dev; raising the precision target moves the threshold; user never sets a raw score | AC-55, AC-56 |
| **S2.7** text-derived lattice | Same type as acoustic; records its source; **this is M4's baseline** | AC-15 |
| **S2.8** `:eval` v1 | Two runs byte-identical within a fixed configuration; reliability diagram per tier; per-prior ablation | AC-90, AC-100 |

> **Prompt for S2.2.** *Read `spec/functional-spec.md` §7.4 and `spec/technical-design.md` §9.
> TDD the ITU prefix trie and the callsign grammar FSA with beam search over the lattice.
> Structural validity is the only hard filter: a valid callsign with zero database hits must
> still be emitted (AC-10), and an unallocated prefix must never reach CONFIRMED (AC-11).*

---

## Phase 3 — capture spine · the R5 gate

**The most important phase.** It settles the project's top risk and establishes the interfaces
everything else plugs into. No ASR anywhere in it.

| Session | Tests first | Exit |
|---|---|---|
| **S3.1** `:capture-api` | WAV replays faster than real time with identical results (AC-89); 48 kHz resamples deterministically (AC-97); both formats exposed | AC-89, AC-97 |
| **S3.2** ring buffer | Pre-roll captures audio before the trigger (AC-3); overrun is detectable, never silent; no allocation on the write path | AC-3 |
| **S3.3** `:segment` | Boundary precision/recall against hand marks (AC-69); stuck carrier split (AC-70); merge/split trade measured (AC-71); too-short rejected with no model invoked (AC-72); pre+post-roll (AC-95); **segmenter takes no `Tier`** (AC-94) | AC-69..72, AC-94, AC-95 |
| **S3.4** `:data` | Migration from every released schema fixture preserves audio and superseded transcripts (AC-53); reconciliation finds both orphan directions and deletes neither (AC-54); FTS5 search with filters | AC-53, AC-54 |
| **S3.5** queue | All passes stalled → capture continues, everything reaches the queue (AC-45); kill mid-pass → identical results (AC-47); a hanging pass is cancelled and the queue drains (AC-99); repeated failure lands `FAILED` without blocking (AC-51); **re-enqueue of a completed pass works** | AC-45, AC-47, AC-51, AC-99 |
| **S3.6** shed + residency | Sheds in documented order, no audio lost at any level (AC-46); hysteresis prevents oscillation; battery below threshold → level 4; tier budget enforced (AC-103) | AC-46, AC-103 |
| **S3.7** `:capture-android` | Route verified after first read, mismatch halts (AC-2, AC-98); interruption produces a correct gap and resumes (AC-48); gap distinguishable from silence (AC-49) | AC-2, AC-48, AC-49, AC-98 |
| **S3.8** FLAC + archive | Decode-and-compare before deleting PCM; archive is re-segmentable (AC-96) | AC-96 |
| **S3.9** service + liveness | Heartbeat determines survival, not the API (AC-65); kill reported next launch with last heartbeat (AC-5); Oppo-specific onboarding (AC-66); notification carries no transcript (AC-61) | AC-5, AC-61, AC-65, AC-66 |
| **S3.10** status UI | Capture state, backlog, tier, storage always one tap away; permissions in context | FR-UI-7, FR-PLT-1 |
| **S3.11** on-device harness | Same manifest, same report format, run on the phone | AC-36 groundwork |
| **S3.12** **GATE** | **D4/D8 from test-plan §7** | **AC-64** |

> **Prompt for S3.5.** *Read `spec/technical-design.md` §7 and `spec/functional-spec.md` §7.14.
> TDD the durable work queue: partial-unique index over active states so a completed pass can be
> re-enqueued, run-id leasing with crash recovery, a pass deadline with watchdog cancellation,
> and bounded retry. Use `FakeAsrEngine` set to hang for AC-99. Capture must proceed with every
> pass stalled (AC-45).*

**S3.12 in detail.** Apply all four ColorOS interventions. Run D8 (30-minute "prove it") first
and record its prediction. Then D4: 8 hours, screen off, unattended. Then D5: the same run with
exemptions removed, which is *expected to fail* and must be correctly reported. Publish measured
endurance (AC-76). **If D4 fails with all interventions applied, stop and re-open D19** — the
options are a second capture device or a different reference phone, and that is a product
decision, not an engineering workaround.

---

## Phase 4 — transcription

| Session | Tests first | Exit |
|---|---|---|
| **S4.1** `:onnx` | Session lifecycle; residency classes evict correctly under pressure; determinism within a fixed configuration | AC-90 |
| **S4.2** `:asr-sherpa` | Engine returns n-best and `no_speech_prob`; invalid model falls back and surfaces it (F13); timeout path | — |
| **S4.3** hallucination controls | **Each of the six individually demonstrable with a crafted input (AC-7)**; the dev noise tape yields zero accepted transcripts (AC-6); rejected segments retain audio and are reachable (AC-8); thresholds fitted, not inherited | **AC-6**, AC-7, AC-8 |
| **S4.4** transcripts + registry | Exactly one current per transmission enforced by index; superseded retrievable (AC-31); model metadata stored per transcript; side-loaded model probed before activation | AC-31 |
| **S4.5** end to end | Capture → VAD → Pass B → text lattice → resolver → attribution, on device; T0 path records its degraded lattice source (AC-15) | AC-15 |
| **S4.6** latency | p95 ≤ 2 s at T1+ (AC-73); no backlog growth at 15% activity (AC-75) | AC-73, AC-75 |

**Record M3's callsign precision/recall on the dev fold.** That number is the baseline M4 must
beat, and it must be recorded before M4 begins.

---

## Phase 5 — the fork

| Session | Do |
|---|---|
| **S5.1** | `UnitSpotter` interface; sherpa-onnx open-vocabulary KWS over the ~36 units. Per-unit precision/recall reported |
| **S5.2** | TD2 probe: can sherpa-onnx expose Whisper encoder hidden states on Android? Half a day. Gates the CB-Whisper approach entirely |
| **S5.3** | **The comparison.** Text-derived vs KWS vs encoder-similarity, same audio, same resolver, same dev fold. Report precision, recall, per-unit metrics, latency and resident memory for each |

**Then take one of three branches explicitly, and write it into the functional spec before
starting M5:** proceed as specified · collapse to the text path and delete Pass C · tier-gate
Pass C to T2+. Implementation plan M4 has the consequences of each.

---

## Standing rules for every session

**These are the operational form of [the constitution](../.specify/memory/constitution.md).
Where the two differ, the constitution governs.**

0. **Constitution Check first, definition of done last.** Name the principles that bear on the
   work before starting; confirm test-plan §5's definition of done before calling it finished.
1. **Red before green.** A test that passes before the code is testing nothing.
2. **The fake ships with the module.** The next session is blocked without it.
3. **Failure paths are tested, not just happy paths.** Most of this product's failures are
   silent, which is why §12 of the spec exists.
4. **Nothing touches the `eval` fold until M11.** The harness enforces it; do not add a flag to
   get around it.
5. **A number without its fold, machine and provider is not a result.**
6. **If a session's exit criteria cannot be met, stop and say so** rather than moving on with a
   module half-done — under strict TDD, a skipped test is an unmet requirement wearing a
   disguise.
7. **A change that touches a screen re-runs the visual verification, triggered by the diff**
   (constitution VIII, 1.2.0). If the change touches `app/src/main/kotlin/org/ort/app/ui/**`,
   `app/src/main/res/**`, `design/**`, a scenario or tour step, or any `*Content`/`*Screen`/
   `*ViewState`/`*ViewData`/`*Mapper` under `:app`, it is not done until the affected screens are
   captured again by the tour (1.0, 2.0, `-end`) and compared against their artboards, with the
   capture path in the register row — see `docs/debug-fix-session-prompt.md` for the exact steps.
8. **Green means green on CI and on the Release workflow**, on the pushed commit. A local gate is
   one machine, one locale, one filesystem.
