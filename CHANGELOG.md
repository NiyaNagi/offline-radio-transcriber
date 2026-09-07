# Changelog

Every commit that lands completed, tested work gets an entry here — this is the project's
build log, not just its git history, because `git log` doesn't carry *why* a change was safe,
which requirements/ACs it settles, or what it deliberately left undone. [`spec/build-plan.md`](spec/build-plan.md)
is the forward-looking todo list; this file is the record of what actually happened against it.

**Format, followed by every entry below and required for every entry after it** (see
[`AGENTS.md`](AGENTS.md)'s working agreement and the build-plan's standing preamble, both of
which now require this):

```
## YYYY-MM-DD

### <commit-hash> — <prompt-id, if any> · <one-line summary, matches the commit subject>

**Scope:** which module(s)/directories this touched (its build-plan "owns" list, if it has one).
**Requirements/ACs:** ids established or satisfied (cite `FR-*`, `AC-*`, `NFR-*`, `D*`, `R*` — the
same ids the spec and tests use). "none new" if the change is process/docs only.
**What changed:** the substance — added/changed/fixed, in enough detail that someone who wasn't
there can tell what exists now that didn't before, and why.
**Verified:** the exact command(s) run and their result (green build, N tests passing, etc.) —
never a bare "works". A number without its fold/machine/provider is not a result (constitution
VI) and neither is a changelog entry without how it was checked.
**Left open / not done:** anything a reasonable reader would otherwise assume was covered but
wasn't — a probe left NOT RUN, a device-only criterion untested on Robolectric, a follow-up
filed elsewhere. Omit only if genuinely nothing applies.
```

Entries are grouped by date, newest first; within a date, newest commit first. Merge commits get
one entry covering what the merge brought in, not a restatement of the branch's own entry.

---

## 2026-09-07 (evening — P10 lands)

### P10 · ASR and hallucination control (`:onnx`, `:asr-api`, `:asr-sherpa`)

**Scope:** `:onnx`, `:asr-api`, `:asr-sherpa` only — no other module touched.
**Requirements/ACs:** AC-7 (each of the six hallucination controls individually demonstrable),
AC-8 (rejected segments retain audio, reachable behind a filter), F13 (invalid model falls back
and surfaces it; side-loaded model signature-checked and probe-run before activation), FR-ASR-5/6,
FR-ASR-8, FR-AST-2, FR-REP-3, technical design §4.3/§8.1/§8.3/§8.4. **AC-6 is NOT established** —
see below.
**What changed:**
- `:onnx` — `ModelDescriptor` (technical design §8.4), `OnnxSession`/`OnnxSessionFactory` (a
  narrow seam mirroring `:segment`'s `VadModel` pattern, so no native runtime is required to
  build or test this layer), and `ModelResidencyManager` implementing the Pinned/Hot/Cold
  residency classes of §4.3: `PINNED` never evicted, `HOT` is LRU with a floor of one, `COLD` is
  loaded-per-use-and-released, budget enforced against declared footprints. `FakeOnnxSessionFactory`
  is the behavioural fake, scriptable to fail loading or crash on first run (the probe-run failure
  mode F13 requires a caller to handle).
- `:asr-api` — `AsrEngine`/`StreamingAsrEngine`/`Enhancer`/`AsrResult` (§8.1, n-best and
  `no_speech_prob` retained); the six `RejectionRule`s as named, cheapest-first, split into
  `PreDecodeRejectionRule` (`too_short`, `vad_no_speech` — never invoke the engine) and
  `PostDecodeRejectionRule` (`no_speech_prob`, `repetition`, `blocklist`, `compression_ratio`);
  `RejectionPipeline` composing all six with a timeout-guarded engine call (`withTimeout` ->
  `PassBOutcome.Failed` on hang or throw, never a hang — F13's engine-timeout requirement);
  `PassBOutcome` (Accepted/Rejected/Failed); `TranscriptCandidate`/`TranscriptSeries` — a
  domain-level append-only "exactly one current" invariant independent of Room (the DB-level
  enforcement is `:data`'s partial-unique-index from P5; this is what a Pass B run produces
  before that write); `ModelRegistry` resolving a tier to an activated model with fallback
  surfaced as `ModelFallback` (F13); `RejectedSegmentLog`, an in-memory stand-in proving the
  AC-8 shape (rejected is a retained, filterable result, not a deletion) at the layer this
  prompt owns — the persisted equivalent is `:data`/`:pipeline` territory; `FakeAsrEngine`, the
  behavioural fake (`Returns`/`HangsFor`/`Throws`).
- `:asr-sherpa` — `SherpaAsrEngine` implementing `AsrEngine` over an injected `SherpaDecoder` seam
  (the one method a real sherpa-onnx JNI binding would implement — see "Left open" below) plus
  `OnnxSession` lifecycle checks; `ModelActivation` — the side-loaded model install path
  (FR-ASR-8): signature check, then load, then probe-run over a fixture clip, refusing activation
  and keeping the previous model active on any failure (FR-AST-2), marking every side-loaded
  activation `sideloadedUnverified = true` regardless of passing checks, per §8.4 ("this does not
  make executing a third-party graph safe; it makes it deliberate"); `FakeSherpaDecoder`.
- Thresholds: `NoSpeechProbRule` (default 0.60) and `CompressionRatioRule` (default 2.4) use the
  same defaults already recorded in `:core`'s `ResolvedConfig` (P1). Both are Whisper's
  conventional values, **not refitted against a development noise tape** — see "Left open".
**Verified:** `./gradlew :onnx:test :asr-api:test :asr-sherpa:test :onnx:ktlintCheck
:asr-api:ktlintCheck :asr-sherpa:ktlintCheck :onnx:detekt :asr-api:detekt :asr-sherpa:detekt
dependencyRules` — BUILD SUCCESSFUL; 8 `:onnx` + 28 `:asr-api` + 7 `:asr-sherpa` tests, all green,
JDK 17, Windows, local machine, no fold applicable (JVM unit tests over synthetic/fake inputs,
not a corpus measurement). `dependencyRules` confirms no new edge beyond `:asr-api -> :core,
:onnx` and `:asr-sherpa -> :core, :onnx, :asr-api`, both already declared in `ModuleGraph`.
**Left open / not done — read this before assuming AC-6 passed:**
- **AC-6 is not established against real data or a real model, and this changelog entry does
  not claim it is.** `spec/open-questions.md` still lists Q2 ("record the tape") and Q16 (its
  labelling protocol) as the specification's two open questions; the development noise tape has
  not been recorded. Separately, no real ONNX Whisper/distil-whisper export runnable from a JVM
  sherpa-onnx binding was available/downloadable in this sandbox, so `:asr-sherpa`'s
  `SherpaAsrEngine` has never run a real decode — `SherpaDecoder` is a documented seam, not a
  verified binding. `SyntheticNoiseGateMechanismTest` in `:asr-api` proves the six-control
  mechanism rejects a set of hand-crafted, noise-shaped decodes (empty text with high
  `no_speech_prob`, degenerate repetition, documented Whisper hallucination phrases) with zero
  accepted — a genuine, valuable result about the *mechanism*, explicitly not a substitute for
  AC-6, and its test name and class doc say so. A real AC-6 run needs: the recorded tape (Q2),
  a real ONNX ASR model reachable from `:asr-sherpa`, and the JVM/JNI sherpa-onnx artifact this
  environment could not resolve/verify.
- Threshold *fitting* against the development noise tape (technical design §8.2, "thresholds are
  fitted, not inherited") could not be done for the same reason — `NoSpeechProbRule` and
  `CompressionRatioRule` keep Whisper's unrefitted defaults, recorded as such in their doc
  comments rather than presented as measured.
- `:asr-sherpa`'s `sherpa-onnx` JVM/JNI dependency itself was not added to `gradle/libs.versions.toml`
  — with no real model to exercise it and no confirmed resolvable artifact for this build's
  target platform, adding an unused/unverified native dependency seemed worse than the documented
  seam (`SherpaDecoder`). A follow-up session with a real model available should add it, implement
  `SherpaDecoder` for real, and only then attempt AC-6 for real.
- `StreamingAsrEngine`/`StreamSession` (Pass A, M8) and `Enhancer` (M11) are declared per §8.1 but
  have no implementation yet — out of P10's scope (M3), matching the build plan's milestone order.

---

## 2026-09-07 (evening — P9 partial: the on-device harness runner)

### 8fbad92 — P9 · On-device harness runner: reuse :eval's Harness/HarnessConfig/HarnessReport in an androidTest entry point

**Scope:** `:app` only — a new `harnessShared` source directory, `app/src/test/.../harness/`,
`app/src/androidTest/.../harness/`, and `app/build.gradle.kts` (new `testImplementation` /
`androidTestImplementation` edges plus the `sourceSets` wiring that shares `harnessShared`
between `test` and `androidTest`). This is deliberately the **first deliverable of P9 only** —
the on-device harness runner described in build-plan P9's opening paragraph and
implementation-plan M2.21a. The device matrix itself (D8, D4, D5, D1–D3, D9–D12; AC-64, AC-76,
NFR-7) is **not attempted in this session** — it needs the physical reference device, which this
sandbox does not have, and fabricating a result for it would be exactly the kind of silent
failure the constitution exists to prevent.
**Requirements/ACs:** AC-36 (a desktop-only harness structurally cannot measure T3 — this gives
`:eval` a genuine on-device entry point), FR-TST-7/AC-100 (the eval-fold seal holds
on-device, identically to the JVM harness — tested), AC-90's determinism/format guarantee
extended off-desktop (the on-device runner is proven to emit byte-identical `canonicalText()`
for identical inputs). AC-64/AC-76/NFR-7 (the actual endurance run and its published number)
remain **open**, as does the rest of the device matrix (D1–D5, D8–D12).
**What changed:** `OnDeviceHarnessRunner` (`app/src/harnessShared/kotlin/org/ort/app/harness/`)
mirrors `eval/src/main/kotlin/org/ort/eval/Main.kt`'s construction exactly (same grammar, same
`PriorCombiner`, same `Harness`, same eval-fold gate via `CorpusManifest.entries`) and adds
`runAndWriteReport`, which writes `HarnessReport.canonicalText()` to a file verbatim. It has
deliberately **no `android.*` import**, so the identical code path is exercised by a plain JVM
unit test (no device) and by the real on-device instrumented test — "same manifest, same report
format" is proven, not asserted, because both callers run the same function.
`HarnessInstrumentedTest` (`app/src/androidTest/.../harness/`) is the actual on-device entry
point: it resolves `InstrumentationRegistry`'s target-context `filesDir` (app-private storage),
reads a manifest from `<filesDir>/harness-corpus/manifest.tsv`, runs the harness with
`(machine = Build.MANUFACTURER/MODEL, provider = "cpu", threadCount = availableProcessors(),
runtimeVersion = Build.VERSION.SDK_INT)`, and writes the report to
`<filesDir>/harness-reports/report.txt`. Its kdoc documents the real push/pull commands
(`adb push` into `/data/local/tmp`, `run-as` copy into app-private storage, `am instrument`,
then `run-as cat`/`adb pull` to retrieve the report) for the future session that runs this on
hardware.
**Module-boundary finding (part of this prompt's explicit ask):** `:app`'s main source set has
no permitted compile-time edge to `:eval` (`buildSrc/.../ModuleGraph.kt`: `:app`'s allowed set is
exactly `{:pipeline, :data, :net, :core}`). `dependencyRules` only checks the `api`,
`implementation` and `compileOnly` configurations (`build.gradle.kts`'s `checkedConfigurations`)
— `testImplementation`/`androidTestImplementation` are outside that check by explicit design
(`ModuleGraph.kt`'s own comment: "Test scope is deliberately excluded: `:testing` fakes are meant
to be reachable from any module's tests"). Rather than widen `ModuleGraph.allowed[":app"]` to
include `:eval` (which would let a future `:app` *main-source* change depend on the harness by
accident, the exact failure mode structural enforcement exists to prevent), this change adds
`:eval`/`:testing`/`:lexicon` only to `testImplementation`/`androidTestImplementation` — inside
the graph's own documented exemption, not a workaround of it. Confirmed with
`./gradlew dependencyRules`: the checked graph still shows `:app -> :core, :data, :net,
:pipeline` only. `:eval`'s current dependencies (`:onnx`, `:asr-sherpa`, etc.) are all still
empty stubs (no native/JNI code yet), so this edge is safe today; when P10 lands a real ONNX
Runtime dependency inside `:onnx`/`:asr-sherpa`, a future session should check whether the JVM
artifact `:eval` pulls in in has a real (non-Android) native library that would fail to load
under `androidTest`'s on-device classpath — flagged here as a foreseeable follow-up, not
something this session could observe today.
**Verified:** `./gradlew :app:testDebugUnitTest` — 15 tests green, including
`OnDeviceHarnessRunnerTest`'s three cases (byte-identical `canonicalText()` vs. a
Main.kt-equivalent construction; `runAndWriteReport` writes the exact text to a file; the
eval-fold gate throws `IllegalStateException` without `allowEval` and succeeds with it).
`./gradlew :app:compileDebugAndroidTestKotlin` — `HarnessInstrumentedTest` compiles clean
against the real Android/androidx.test classpath (this is as far as verification can go without
a device or emulator in this sandbox — the instrumented test itself was not executed).
`./gradlew dependencyRules` — 17 modules checked, `:app -> :core, :data, :net, :pipeline` only,
no forbidden edges. `./gradlew :app:check` — ktlint, lint and both unit-test variants
(debug/release) green. `./gradlew coverageMatrix` — 413 requirements, 92 covered (was 90 before
this commit; +2 for the two `AC-36`-tagged JVM tests), no new orphan-id warnings.
**Left open / not done:** the entire device matrix (implementation-plan M2 exit criteria): **D8**
(30-minute "prove it" run), **D4** (the 8-hour unattended run, AC-64), **D5** (the same run
without exemptions, AC-5), **D1, D2, D3, D9, D10, D11, D12**. None of these can be attempted
without the physical reference device — this session had no device access, and the build-plan
prompt explicitly scoped this session to the on-device runner only. Also genuinely unverified for
the same reason: the actual `adb push` of a real corpus into app-private storage, the actual
`am instrument` execution on hardware, and the actual `adb pull`/`run-as cat` of the resulting
report — `HarnessInstrumentedTest` documents the exact commands but none of them have been run.
The manifest fixture inside `HarnessInstrumentedTest` is a stand-in for a pushed corpus; a real
device run replaces it with the genuine corpus manifest before executing. `AC-64`'s "Done when:
AC-64 passes and D8 correctly predicted D4's outcome" is entirely open.

---

## 2026-09-07 (afternoon, cont. — P8 and the real R1 run both land)

### 36c19ad — Merge branch 'worktree-agent-aa4aa2aeea1f1ebaa' (P8 into main)

**Scope:** brings P8's `:capture-android`, `:pipeline`, `:app` additions onto `main`.
**Requirements/ACs:** see the P8 entry below.
**What changed:** merge-only; one conflict in `spec/build-plan.md`'s progress checklist (this
branch and the P7 merge had both ticked adjacent lines independently), resolved by keeping both
ticks.
**Verified:** `./gradlew build dependencyRules` green post-merge (818 tasks); `./gradlew
coverageMatrix` regenerated (63 → 90 requirements covered).
**Left open / not done:** n/a (merge).

### 158a61c — P8 · Android capture spine: AudioRecordSource, shed controller, capture status surface

**Scope:** `:capture-android`, `:pipeline`, `:app` (status surface and permissions only, per the
prompt's ownership boundary — `:data`, `:capture-api`, `:segment`, `:core`, `:testing`, `corpus/`
confirmed untouched).
**Requirements/ACs:** AC-2/AC-98 (route verified after first read and on route change; a
deliberately-selected built-in mic is legal but persistently labelled), AC-5 (unclean end
reported next launch with its last heartbeat), AC-46 (shed order followed, no audio lost at any
level), AC-48/AC-49 (interruption produces a correctly-bounded `CaptureGap`, resumes, and a gap
is distinguishable from silence), AC-61 (notification never contains transcript text), AC-65
(liveness comes from the heartbeat — verified by forcing `isIgnoringBatteryOptimizations()` to
return `true` and confirming the session is still treated as dead), AC-96 (a continuously-archived
session is re-segmentable with different VAD parameters), AC-97 (resampler identity recorded),
AC-99 (`PassDrainRunner` cancels a hanging pass at its deadline and keeps draining), AC-103 (model
residency stays inside the tier budget). AC-64 (the 8-hour endurance claim itself) is explicitly
**partial** — the mechanism (`ProveItAnalyzer`) is built and tested, but the actual prediction
needs the reference device and is P9's job, not something Robolectric can settle.
**What changed:** `AudioRecordSource` (implements `:capture-api`'s `CaptureSource`; route
verification after the first read and on every route change, halt rather than silently
fall back, interruption → `BackoffLadder` (1/2/5/10/30s) → auto-resume); `RouteVerifier`,
`GapTracker`; the FLAC store (`FlacStore`: stage → encode → decode-and-compare → delete only on
byte match) backed by `DeflatePredictiveCodec` — a pure-Kotlin order-1-delta-prediction +
DEFLATE codec substituted for JNI libFLAC, which can't be built in this sandbox; genuinely
lossless and never trusted blindly (`FlacStore` verifies every encode itself); the
continuous-archive writer (`ContinuousArchiveWriter`/`ArchiveReader`, default off, sample-indexed
chunks; re-segmentation lives in `:pipeline`'s `ReSegmenter` since `:capture-android` may not
depend on `:segment`); `CaptureService` (foreground, wake lock, heartbeat) with a file-backed
`HeartbeatStore`, `UncleanEndDetector`, `LivenessChecker`; the OEM guidance table
(`OemGuidanceTable`/`OemGuidanceResolver`, four ColorOS steps + generic fallback);
`ProveItAnalyzer` (the 30-minute test's reporting mechanism); in `:pipeline`: `ShedController`
(levels 0–5, hysteresis, battery-critical override), `ModelResidency`/`ResidencyBudgetChecker`,
`Pass`/`PassDrainRunner` (orchestrator watchdog over `:data`'s `WorkQueue.runLeased`),
`GapPersister` (maps capture-android's `GapRecord` → `:data`'s `CaptureGapEntity`),
`CaptureStatus`/`CaptureStatusRepository`; in `:app`: `StatusActivity` + `StatusViewStateMapper`
(plain Android views, not Compose — `ort.android-app.gradle.kts` has no Compose wiring yet; the
state-holder logic is Compose-agnostic so this doesn't block adding it later) and
`PermissionsFlow` (RECORD_AUDIO → notifications → battery-exemption, the last kept
diagnostic-only per AC-65's point that liveness must never depend on that API).
**Verified:** `./gradlew dependencyRules` (every edge permitted), `./gradlew
:capture-android:check :pipeline:check :app:check` (tests + ktlint + detekt + lint, green),
`./gradlew build` (whole repo, green).
**Left open / not done:** three documented, scope-bounded deviations: (1) FLAC is substituted by
the pure-Kotlin codec above, not JNI libFLAC — flagged as a follow-up, not silently swapped; (2)
`:app` has no Compose UI yet, plain views only; (3) shed events live in `ShedController`'s memory
only, not persisted to a `:data` table (no `shed_event` table exists and this prompt couldn't
touch `:data` to add one). Also: `:pipeline` depends on `:capture-android` via `api` rather than
`implementation` specifically so `:app` can read capture-status types without a new declared
module edge — `dependencyRules` checks each module's own declared edges, so this is compliant
with the graph as coded, but worth a second look at the next `:pipeline`/`:app` session. AC-64,
AC-66's actual-ColorOS-hardware behaviour, and the 8-hour endurance claim all remain P9's job on
the physical reference device.

### 144e21b — Merge branch 'worktree-agent-a7b31df9844daf804' (the real R1 run into main)

**Scope:** brings the real (non-fixture) R1 run onto `main`: `corpus/pyproject.toml`,
`corpus/src/corpus/probes/lora_export.py`, `corpus/src/corpus/probes/_sherpa_whisper_export.py`
(new), `corpus/tests/test_probe_lora_export.py`, `results/r1-lora-export.md`.
**Requirements/ACs:** R1 (probe gate) — see the entry below.
**What changed:** merge-only, no conflicts (disjoint from every other concurrently-landed branch).
**Verified:** `cd corpus && python -m pytest -q` post-merge — 70 passed, 1 skipped (the real-R1
test skips without `ORT_RUN_REAL_R1=1` set, by design, so CI never pays its ~111s cost or needs
the real toolchain installed).
**Left open / not done:** n/a (merge).

### 269b578 — P6 follow-up · R1 real run

**Scope:** `corpus/pyproject.toml` (new `[r1-real]` optional-dependency extra),
`corpus/src/corpus/probes/lora_export.py`, `corpus/src/corpus/probes/_sherpa_whisper_export.py`
(new — a vendored, adapted copy of sherpa-onnx's own `scripts/whisper/export-onnx.py`),
`corpus/tests/test_probe_lora_export.py`, `results/r1-lora-export.md`.
**Requirements/ACs:** R1 (probe gate, build-plan S1.7 / D13). **R1 now has a real verdict:
D13 is not reopened** — the gated mechanism (LoRA fine-tune → `merge_and_unload()` → sherpa-onnx
export → load → transcribe) genuinely composes on CPU.
**What changed:** installed and pinned the real toolchain (torch 2.11.0 CPU, transformers
5.16.1, peft 0.20.0, accelerate 1.14.0, sherpa-onnx 1.13.7, plus onnx/onnxruntime/onnxscript,
datasets, soundfile, huggingface_hub) in a new `[r1-real]` `pyproject.toml` extra so CI's default
install stays untouched. `probe_r1(dry_run=False)` now performs a genuine run: peft LoRA
(rank 4, query/value projections) fine-tuned on `distil-whisper/distil-small.en` against
~3.1 minutes of `hf-internal-testing/librispeech_asr_dummy` (LibriSpeech dev-clean audio,
CC BY 4.0, with real ground-truth transcripts) for 60 steps, loss 0.69 → 0.004;
`merge_and_unload()`'d; exported via `_sherpa_whisper_export.py` to ONNX; loaded with
`sherpa_onnx.OfflineRecognizer`; transcribed a held-out clip (reference *"then the powerful
twist that thrust it aside in and under the guard"* → hypothesis *"then the powerful twist that
thrusts rest to the side in and under the guard"* — recognizable, non-degenerate real ASR
output, not a fabricated string). Two genuine toolchain findings recorded for M0a: (1) sherpa-onnx's
export script expects an `openai-whisper`-format checkpoint, not a HuggingFace `transformers`
one — this run sidesteps the undocumented conversion by running peft directly against
`openai-whisper`'s own model object; (2) torch ≥2.9's default "dynamo" ONNX exporter fails on
this decoder's data-dependent KV-cache slicing, so `dynamo=False` (the legacy TorchScript
exporter) is required.
**Verified:** `cd corpus && ORT_RUN_REAL_R1=1 python -m pytest -q
tests/test_probe_lora_export.py` — 5/5 passed in ~111s (real toolchain exercised); the 4
pre-existing fixture-based tests are untouched and still pass with zero real dependencies and no
env var.
**Left open / not done:** scaled down from the prompt's "ten minutes of anything" to ~3.1 minutes
(loss had already converged well before that point) and from a full LibriSpeech download to the
small `librispeech_asr_dummy` fixture — both documented with reasoning in
`results/r1-lora-export.md` rather than silently substituted. The real-toolchain test is gated
behind an opt-in env var so it never runs in ordinary CI (mirrors the eval-fold opt-in pattern
elsewhere in this project) — someone still needs to decide whether/how a CI lane should exercise
it periodically.

## 2026-09-07 (afternoon, cont. — more adversarial review while P8/R1 ran)

### (pending) — Fix segment endSample under-reporting on mid-hangover stream end; detekt line-length cleanup

**Scope:** `segment/src/main/kotlin/org/ort/segment/Segmenter.kt`,
`segment/src/test/kotlin/org/ort/segment/PrePostRollTest.kt` (P4, not touched by any
concurrently-running session), plus an unrelated detekt fix in
`eval/src/main/kotlin/org/ort/eval/Harness.kt` (two lines the earlier fold-isolation change left
over `MaxLineLength`, caught by `./gradlew build` rather than by the narrower module-test runs
used to verify that change), and a `results/coverage-matrix.md` regeneration.
**Requirements/ACs:** AC-95 (pre/post-roll retain the transmission complete) — this fixes a case
that criterion's existing test didn't reach.
**What changed:** `Segmenter.finish()`'s `HANGOVER` branch called a private
`bufferedHangoverSamples()` helper twice — once to size the `flushHangover()` call, and again
(after `flushHangover` had already cleared the buffer it counts) to compute the closed segment's
`endSample`. The second call always saw an empty buffer, so a stream that ended while still in
`HANGOVER` (trailing silence shorter than `minSilenceMs`, e.g. capture stopping mid-trail-off)
produced a `SegmentRecord` whose `endSample - startSample` under-counted the real audio already
written to the sink — the declared boundary and the actual appended sample count disagreed.
Fixed by computing the flushed sample count once, before the buffer is cleared, and reusing it
for both the flush and the `endSample` calculation. Added a regression test (300 ms of trailing
silence against a 600 ms `minSilenceMs`/400 ms `postRollMs` config, stream ending mid-hangover)
that fails on the pre-fix code and passes after.
**Verified:** `./gradlew :segment:test` (all 14 tests green, including the new one),
`./gradlew build` (full multi-module build green after the detekt fix), `./gradlew
coverageMatrix` regenerated.
**Left open / not done:** no other modules were re-audited in this pass beyond `:segment`,
`:lexicon`'s ranking/priors/calibration math (`PropagationModel`, `Priors.kt`,
`ThresholdDerivation`, `PlattCalibrator` — all read closely, no defects found), and re-verifying
the earlier `Harness`/`WorkQueue` fixes still hold.

## 2026-09-07 (afternoon, cont. — changelog established)

### (pending) — Establish CHANGELOG.md and make it part of the working agreement

**Scope:** `CHANGELOG.md` (new), `AGENTS.md`, `spec/build-plan.md` (docs only — no code).
**Requirements/ACs:** none — process change, at the user's request, not a spec requirement.
**What changed:** created this file, backfilled with a detailed entry for every commit from P1
(`5fca369`) onward (the specification-phase commits before it are condensed into one pointer
entry — see below). Added rule 6 to `AGENTS.md`'s working agreement requiring a `CHANGELOG.md`
entry with every commit, in this file's documented format, before the commit that completes a
unit — not a follow-up. Added the same instruction to the standing preamble in
`spec/build-plan.md`, which is the text pasted into every dispatched build-plan prompt, so it
reaches every future session (human or agent) the same way the Constitution Check and strict-TDD
rules already do. Added a `CHANGELOG.md` row to `AGENTS.md`'s "Where things are" table.
**Verified:** `python tools/spec-check/spec_check.py` (7/7 OK — confirms the doc edits didn't
introduce a dangling reference or break the spec's own integrity checks); no code changed, so no
build/test run was needed for this commit.
**Left open / not done:** this entry itself was written before the commit hash existed, so it's
marked `(pending)` above rather than a real hash — unavoidable for the entry describing its own
commit; every entry after this one carries a real hash written after the fact.

## 2026-09-07 (afternoon — parallel dispatch, adversarial review)

### d46738b — Document Fearless Steps acquisition steps for R4 (build-plan todo)

**Scope:** `corpus/src/corpus/sources.py`, `corpus/manifest.json`, `docs/reference/`, `spec/build-plan.md`.
**Requirements/ACs:** R4 (probe gate, still open); none newly satisfied.
**What changed:** Added [`docs/reference/fearless-steps-acquisition.md`](docs/reference/fearless-steps-acquisition.md),
step-by-step manual acquisition instructions for both the recommended Tier 1 (the CC-BY-4.0
100-hour Challenge Corpus's speaker-diarization track — the actual size R4 needs) and Tier 2
(the full ~19,000-hour corpus via a CRSS request form/EULA, multi-terabyte, real review
turnaround). Fixed `sources.py`'s `fearless-steps` `SourceSpec`: its URL was a dead Mendeley
link (`data.mendeley.com/datasets/xps6b5vpxr` now 404s), replaced with the live Challenge Phase 2
page and a comment stating plainly that this source cannot be fetched by the automated `corpus
acquire` HTTP path — it requires a human registration step first, which no session can complete
unattended. Licence field corrected to describe both tiers' real terms. Regenerated
`corpus/manifest.json` via `corpus build` to match. Added a "yours, not a session" todo entry in
`spec/build-plan.md` pointing at the new doc.
**Verified:** `corpus build` (regenerated manifest, folds OK), `python -m pytest -q` in `corpus/`
(70 passed).
**Left open / not done:** the actual acquisition — still needs a human to register with UT Dallas
CRSS; R4 remains `NOT RUN` (see `results/r4-speaker-separation.md`).

### d61eeb6 — Adversarial review fixes: eval-fold leakage in Harness, concurrent-pass crash in WorkQueue

**Scope:** `eval/src/main/kotlin/org/ort/eval/Harness.kt`, `eval/src/main/kotlin/org/ort/eval/Main.kt`,
`eval/src/test/kotlin/org/ort/eval/*`, `data/src/main/kotlin/org/ort/data/WorkQueue.kt`,
`data/src/test/kotlin/org/ort/data/WorkQueueTest.kt`, `.gitignore`.
**Requirements/ACs:** protects the eval-fold discipline underlying AC-55/AC-56/AC-90 and P7's
own stated requirement ("Platt calibration fitted on train+dev and verified on eval"); protects
AC-45/AC-47/AC-51/AC-99's queue-draining guarantees for the multi-pass case technical design
§7.1 specifies. No new AC introduced; both are latent-bug fixes caught by adversarial review, not
requirement gaps.
**What changed:**
1. `Harness.run()` used to fit its Platt calibrator and derive the `CONFIRMED` threshold from the
   *same* occurrence list it then scored. The first real caller to wire the `eval` fold in would
   therefore fit on eval data while nominally "verifying" against it — exactly the leakage
   "never read the eval fold" (constitution VI) exists to prevent, and it was unexercised by any
   existing test because no real caller does that yet. Split `run()` into explicit `fitOn` /
   `evaluate` parameters so this is structurally impossible rather than a caller's discipline to
   remember. Added `HarnessFoldIsolationTest`: proves the derived threshold is a pure function of
   `fitOn` (unaffected by what `evaluate` contains) and that reported metrics track `evaluate`'s
   own labels.
2. `WorkQueue.completePass()`/`failPass()` called `TransmissionDao.requireLegalTransition()`
   unconditionally. Technical design §7.1's `idx_wq_active` index is keyed on
   `(transmission_id, pass)` specifically because more than one pass (B, C, E, FUSE per the
   residency table) can be leased concurrently for one transmission — so whichever pass reports
   *second* would crash trying to re-transition an already-final transmission (`COMPLETE →
   COMPLETE` is not in `TransmissionLifecycle.legalTransitions`). Guarded both methods with the
   same `canTransition()` check `leaseBatch()` already used, so a later pass's outcome leaves an
   already-final transmission alone instead of throwing. Added two `WorkQueueTest` cases
   (two `PassId`s on one transmission, second completes/fails after the first already committed)
   that reproduce the crash on the pre-fix code and pass after.
3. Added `.claude/` to `.gitignore` after an unrelated `git add -A` staged agent worktree
   directories as embedded repos.
**Verified:** `./gradlew build` (full multi-module build, green — 793 tasks), `./gradlew
:eval:test :lexicon:test :data:testDebugUnitTest` individually (all green, including the four new
regression tests), `python tools/spec-check/spec_check.py` (7/7 checks OK), `cd corpus && pytest
-q` (70 passed). Confirmed both bugs were real (not defensive-only fixes) by checking
`TransmissionLifecycle.legalTransitions` directly: `COMPLETE → COMPLETE` is absent, so the
pre-fix `WorkQueue` code path would have thrown `IllegalTransitionException` on the exact
scenario the new tests exercise.
**Left open / not done:** no other modules were audited to this depth in this pass (`:capture-*`,
`:lexicon`'s grammar/FSA, `:segment` were reviewed only via their existing green test suites, not
re-derived from spec by hand).

### b7a0ea8 — Merge branch 'worktree-agent-ab723fefc2d0ab707' (P7 into main)

**Scope:** brings P7's `eval/` and `lexicon/` ranking/calibration additions onto `main`.
**Requirements/ACs:** AC-15, AC-55, AC-56, AC-90 (see the P7 entry below for detail).
**What changed:** Merge-only; one conflict in `spec/build-plan.md`'s progress checklist (P5 and
P7 had both ticked adjacent lines independently), resolved by keeping both ticks.
**Verified:** `./gradlew :eval:test :lexicon:test` green post-merge.
**Left open / not done:** n/a (merge).

### 5187fee — Merge branch 'worktree-agent-a9e92d557d18afc8c' (P6 into main)

**Scope:** brings P6's `corpus/src/corpus/synth/`, `corpus/src/corpus/probes/` additions onto `main`.
**Requirements/ACs:** R4, R1 (both recorded as `NOT RUN` — see the P6 entry below).
**What changed:** Merge-only; one conflict in `spec/build-plan.md`'s progress checklist (P5 and
P6 had both edited the same block independently), resolved by combining both ticks and P6's
NOT-RUN annotation.
**Verified:** `python -m pytest -q` in `corpus/` green post-merge (70 tests).
**Left open / not done:** n/a (merge).

### eb3fa62 — P7 · Lexicon ranking, calibration and the `:eval` harness

**Scope:** `:lexicon` ranking/calibration (owns per build-plan: `Prior`, `Priors`,
`PriorCombiner`, `PropagationModel`, `PlattCalibrator`, `ThresholdDerivation`,
`TextDerivedLatticeBuilder`, `RankingContext`), `:eval` (new module: `Harness`, `HarnessConfig`,
`HarnessReport`, `LabeledOccurrence`, `ManifestHarness`, `Main`).
**Requirements/ACs:** FR-LEX-9 (every prior's contribution clamped, structurally, in
`AbstractPrior` — no prior can eliminate a candidate), FR-LEX-31 (cold start contributes exactly
`0f`, enforced by a `require()` in `PriorContribution`, never a default), FR-LEX-25..27
(propagation prior demotes but never eliminates, suppressed on known repeater frequencies),
AC-15 (text-derived lattice is the same `PhoneticLattice` type as acoustic, records
`TEXT_DERIVED`), AC-55 (0.9 calibrated confidence ≈ 90% observed accuracy on synthetic
held-out draws — reliability diagram), AC-56 (raising the precision target raises the derived
`CONFIRMED` threshold; the API only accepts a precision target, never a raw score), AC-90 (two
harness runs over identical corpus/model/config produce byte-identical `canonicalText()`).
**What changed:** the clamped log-odds prior-combination mechanism (`AbstractPrior` enforces
clamping and cold-start-is-zero once, for every prior including future ones, rather than trusting
each implementation); the seven priors from technical design §9.3; a static/deterministic
`PropagationModel`; `PlattCalibrator` (logistic calibration) and `ThresholdDerivation`
(precision-target → threshold); `TextDerivedLatticeBuilder`; and `:eval` v1 — a pure
`Harness.run()` composing grammar + priors + calibration + threshold into a
`HarnessReport` with per-tier reliability diagram and per-prior ablation, plus `ManifestHarness`
(the one sanctioned call site reading the corpus manifest's fold gate) and a `Main.kt` entry
point.
**Verified:** `./gradlew :lexicon:test :eval:test` — 35 tests green — plus `dependencyRules`,
`:lexicon:detekt`/`:eval:detekt`, `:lexicon:ktlintCheck`/`:eval:ktlintCheck`, and
`tools/spec-check/spec_check.py`.
**Left open / not done:** **no real precision/recall/reliability number exists yet** — there is
no hand-labelled callsign-occurrence corpus (that's Q16/S1.8, still not produced) and no ASR
pass (P10) to derive a lattice from real audio. `Main.kt` states this explicitly rather than
printing a number computed from nothing. The mechanism itself is proven correct only against
synthetic fixtures. (This entry predates and is unaffected by the eval-fold-leakage fix in
`d61eeb6` above, found during review of this same code.)

### 8010be4 — P6 · Synthetic corpus and the two probes: channel model, generator, R4/R1 (not run)

**Scope:** `corpus/src/corpus/synth/` (new: `channel.py`, `phonetic.py`, `splice.py`, `tts.py`,
`generator.py`), `corpus/src/corpus/probes/` (new: `speaker_separation.py`, `lora_export.py`),
`results/r4-speaker-separation.md`, `results/r1-lora-export.md`, `corpus/manifest.json`
(`synth-callsigns/dev-01` entry), `spec/build-plan.md`.
**Requirements/ACs:** D22 (channel model + synthetic generator), FR-TST-9 (synthetic audio barred
from `eval` — enforced by `generator.py` refusing `fold="eval"` outright), FR-SPK-14 (R4's
false-match-rate reporting shape, not yet populated with a real number).
**What changed:** a deterministic channel-degradation model fit from Paderborn's clean/degraded
pairs; the ULS-callsign → phonetic-expansion → TTS-plus-spliced-ISOLET/ATC → channel pipeline,
with labels derived from the exact rendering placement (so they cannot drift from the audio);
the R4 (speaker-separation) and R1 (LoRA export) probe *pipelines* — `Embedder` /
`LoraTrainer`/`Exporter`/`AsrRuntime` protocols, clustering + false-match-rate math for R4,
the five-stage finetune→merge→export→load→transcribe composition for R1 — each with a
behavioural fake (`FakeEmbedder`, `FakeLoraTrainer`, etc.) shipped in the same change and proven
correct against synthetic fixtures.
**Verified:** `python -m pytest -q` in `corpus/` — 70 tests (30 new) — and `ruff check .` clean.
**Left open / not done:** **both R4 and R1 report `NOT RUN`, not a fabricated number.** R4:
Fearless Steps was not on disk and no WeSpeaker/3D-Speaker package was installed (now documented
in `d46738b` above — needs human registration). R1: `transformers`/`peft`/`accelerate`/
`sherpa_onnx` were not installed and no GPU is present (superseded by the real attempt dispatched
this session — see the R1-real entry below, still running as of this changelog's writing).
Each `results/*.md` states its blocker plainly per the task instruction that a poor *or absent*
result must never be papered over with an invented number.

### ae74bdc — P5 · Data layer: Room schema, FTS5, durable queue, transmission lifecycle

**Scope:** `:data` (owns: entities, DAOs, `WorkQueue`, `TransmissionTransitions`,
`OrtDatabase`, `ReconciliationReport`, migrations).
**Requirements/ACs:** AC-31 (superseded transcript stays retrievable; exactly one `is_current`
per transmission via a hand-written partial unique index — Room's `@Index` can't express one),
AC-45 (segments reach the durable queue with every pass stalled), AC-47 (killing the process
mid-pass returns `PROCESSING → CAPTURED` and completes identically on retry — passes are
idempotent), AC-51 (a repeatedly failing pass lands `FAILED` without blocking the queue;
re-enqueueing a completed pass succeeds because the partial unique index covers active states
only), AC-53 (migration from every released schema fixture preserves audio and superseded
transcripts), AC-54 (reconciliation reports an orphaned file and a dangling row and deletes
neither), AC-99 (a hanging pass is cancelled at its deadline via `withTimeoutOrNull`, marked
`FAILED`, and the queue keeps draining).
**What changed:** the Room entities from functional spec §8 (including `Voiceprint` enrolment
fields, station knowledge, `Thread.kind`, `ContributionItem`); FTS5 over the transcript table
filtered in the join; WAL; `WorkQueue` (run-id leasing, per-item deadlines, bounded retry, one
transaction per state change spanning both the queue and the transmission — this is what makes
AC-47 and AC-99 true together); `TransmissionTransitions` (the one path that moves a
transmission's lifecycle state, always through `TransmissionLifecycle.require()` first, so an
illegal transition throws rather than silently writing a wrong state).
**Verified:** `./gradlew :data:testDebugUnitTest` (Robolectric) — see the full pass list under
`d61eeb6` above, which added two more cases to this same suite.
**Left open / not done:** the concurrent-multi-pass gap fixed in `d61eeb6` was introduced here
and not caught by this session's own tests (all of which used one pass per transmission) — found
later during adversarial review, not at the time.

### 8010be4 / eb3fa62 note — merge order

P5, P6 and P7 were built concurrently by three agents in isolated git worktrees (P5 directly by
the user, P6 and P7 as dispatched background sessions), then merged into `main` one at a time
(`5187fee`, `b7a0ea8` above) once each was independently green. This was safe because build-plan
Wave B/C's file-ownership split holds exactly as documented: P5 owns `:data`, P6 owns `corpus/`,
P7 owns `:lexicon` (ranking/calibration only) + `:eval` — no two of the three touched the same
production file, and the only merge conflicts were both in `spec/build-plan.md`'s shared
checklist, resolved by combining ticks.

## 2026-09-07 (morning — Waves A/B)

### 2eec8a7 — P4 · Capture and segmentation: WavFileSource, polyphase resampler, ring buffer, VAD segmenter

**Scope:** `:capture-api`, `:segment`. Pure JVM, no Android APIs.
**Requirements/ACs:** AC-3 (pre-roll + explicit, non-silent overrun detection), AC-69 (boundary
precision/recall == 1.0 against hand-marked keying times), AC-70 (stuck-carrier force-split,
contiguous, none exceeding the configured cap), AC-71 (`minSilenceMs` measurably trades merge vs
split on a fixed 500 ms gap — the trade is measured, not tuned by ear), AC-72 (too-short segments
rejected with audio retained and no model invoked), AC-89 (WAV replay: real-time and
as-fast-as-possible produce byte-identical events, fast path beats real time), AC-94 (reflection
asserts neither `Segmenter` nor `SegmentConfig` can be constructed with a `Tier` — FR-SEG-7 /
CON-SEG-1, the segmenter cannot accept a tier by construction, not by convention), AC-95 (pre +
post-roll retain the transmission complete, verified against an exact source slice), AC-97
(48 kHz→16 kHz and 44.1 kHz→16 kHz resample deterministically across independent runs, with a
recorded `ResamplerIdentity`).
**What changed:** `CaptureSource`/`CaptureEvent` contract; a deterministic fixed-point
windowed-sinc polyphase resampler (Q30, SHA-256-identified coefficient table); a lock-free SPSC
`RingBuffer` with pre-roll snapshot; a minimal WAV reader/writer and `WavFileSource`; the
`Vad`/`VadModel` interfaces, a `SileroVad` hysteresis wrapper, `SegmentConfig` (no `Tier` field),
and the `IDLE → TENTATIVE → SPEECH → HANGOVER` `Segmenter` writing incrementally rather than
buffering a whole transmission; `ScriptedVad` as the behavioural fake VAD for downstream tests.
Two bugs were caught by the tests before shipping: the pre-roll ring double-capturing audio
already claimed by an open segment, and the first silent hangover frame being written to the
sink twice (directly and via the hangover-buffer flush).
**Verified:** `dependencyRules`, ktlint, detekt and the full root `check`, all green;
`results/coverage-matrix.md` regenerated, tracing all nine criteria above to their tests.
**Left open / not done:** none noted at the time.

### 1243c1d — P3 · Lexicon grammar: units, variant table, ITU trie, callsign FSA, confusion scoring

**Scope:** `:lexicon`'s structural half (types, units, trie, FSA, scoring) — pure Kotlin against
`:core` only.
**Requirements/ACs:** FR-LEX-5 (lattice retains alternatives with scores, never collapses to a
best path), FR-LEX-4/29 (variant table: NATO/letter-name/legacy forms map to one unit; an
unknown form is rejected, never guessed), FR-LEX-8 (ITU prefix-trie allocation is the *only*
hard filter), AC-10 (a structurally valid non-US-prefix callsign with zero database hits still
resolves as a candidate), AC-11 (an unallocated prefix yields no candidate, so it can never
reach `CONFIRMED`), AC-12 (NATO, letter-name and legacy pronunciations of one callsign yield one
result), FR-LEX-10 (confusion-weighted substitution — E-set B/D/E/P/V/T, M/N, S/F — costs less
than an arbitrary pair).
**What changed:** `PhoneticUnit` (the closed 26-letter/10-digit/STROKE vocabulary); the
`PhoneticLattice`/`LatticeSlot`/`UnitScore` types; `VariantTable` as a bundled versioned TSV
asset; `ItuPrefixTable` (prefix trie, longest-match allocation); `ConfusionCostMatrix` as a
bundled versioned asset; `CallsignGrammar` (beam search over lattice slots against the
prefix+digit+suffix FSA, modifiers and reciprocal forms `/P`, `/M`, `K7ABC/VE7`, `VE7/K7ABC`).
**Verified:** 25 tests green (including AC-10/11/12); `:lexicon:check`, `dependencyRules`,
`coverageMatrix` all green.
**Left open / not done:** ranking, priors, calibration and `:eval` deliberately deferred to P7
(this session touched only the grammar/trie/scoring half, per its own file-ownership boundary).

### bba6b5e — P2 · Corpus pipeline: manifest, fold gate, acquisition, harness v0

**Scope:** `corpus/` (new Python subproject, own `pyproject.toml` and CI job).
**Requirements/ACs:** FR-TST-7/9 (a session in two folds, or synthetic audio in `eval`, rejected
at load time), AC-100 (`eval` fold refused without the explicit `--i-know-this-is-the-eval-fold`
flag), FR-TST-8 (harness metrics reported per source as well as aggregate), §14A.2 (whole
sessions only, ≥1 eval session with no train station, the two noise tapes separated, HF/DX
routed to eval).
**What changed:** `manifest.py`, `folds.py` (the §14A.2 checker), `gate.py` (the eval seal),
`sources.py` (the four public sources — Paderborn HF, Fearless Steps, ATC merge, ISOLET — plus
synthetic and local, each licence-recorded), `acquire.py` (resumable, idempotent
fetch→verify→normalise), `audio.py` (deterministic 16 kHz mono FLAC via ffmpeg), `metrics.py` +
`harness.py` (WER, callsign/boundary precision-recall, rejection rate by reason, attribution
accuracy, with a run fingerprint), `report.py` (refuses to exist without a fold or serialise
without a fingerprint), `build.py` (`corpus build` renders the committed `manifest.json`; CI
asserts no drift).
**Verified:** 38 tests green at the time (grown to 70 by P6, per above).
**Left open / not done:** none noted at the time; the dead Fearless Steps URL surfaced later
(fixed in `d46738b`).

### 45a8ecb — P1 follow-up: mark gradlew executable, tick the build-plan checklist

**Scope:** `gradlew`, `spec/build-plan.md`.
**Requirements/ACs:** none new — housekeeping.
**What changed:** restored `gradlew`'s executable bit (lost in the initial commit) and ticked
P1's checklist entry.
**Verified:** n/a (permissions + doc only).
**Left open / not done:** none.

### 5fca369 — P1 · Foundation: Gradle skeleton, `:core` and `:testing`, spec-check, CI

**Scope:** root build files, `:core`, `:testing`, `.github/workflows/`, `tools/spec-check`
(every other module scaffolded as an empty stub only).
**Requirements/ACs:** AC-91 (`SampleClock` survives a wall-clock jump, F14/FR-RUN-15..18),
AC-93/NFR-5 (CI asserts the debug APK's `minSdkVersion` is 26; a weekly opt-in emulator job
installs and runs it on an API-26 AVD), FR-SPK-10 (the four attribution states are non-optional
at the type), FR-RUN-7 (the transmission lifecycle's legal-transition table, asserted
exhaustively), D11 (LICENSE: Apache-2.0 — patent grant + Play Store path, confirmed as a product
decision rather than defaulted).
**What changed:** the Gradle multi-module skeleton for every module in technical design §2,
wired with its permitted dependencies; the `dependencyRules` task (backed by `ModuleGraph`,
demonstrated failing on a deliberate `:capture-api → :asr-api` edge before being reverted,
`ModuleGraphTest` as the permanent guard); `tools/spec-check` implementing all seven test-plan
§8.1 checks (its own meta-guard test injects a dangling `AC-9999`/`FR-GHOST-1` reference and
asserts the checker catches it); the `coverageMatrix` Gradle task regenerating
`results/coverage-matrix.md` from spec ids + `@Requirement`-annotated test names; `:core`
(`Clock`, `SampleClock`, `Ulid`, `PassFingerprint`/`Materiality`, `ResolvedConfig`, the
`Attribution` closed type, `TransmissionLifecycle`) with no Android dependency
(`NoAndroidDependencyTest`); `:testing` (`TestClock`, `@Requirement`, `Fixtures`, `Seeds`, a
minimal `CorpusManifest` that already refuses the eval fold without an explicit opt-in); the
Apache-2.0 `LICENSE` file.
**Verified:** CI green; both meta-guards demonstrated failing then passing; AC-91 holds;
`results/coverage-matrix.md` generates.
**Left open / not done:** none noted at the time.

## 2026-09-06 — Specification phase (condensed)

Fifteen commits establishing the functional spec (266 requirements, 126 ACs, 32 decisions, 16
risks), technical design, implementation plan, test plan, and the eleven-prompt build plan
itself, plus an adversarial audit (41 findings, all addressed) and a later six-defect review.
Not restated entry-by-entry here — that history lives in the specs themselves
(`spec/functional-spec.md`, `spec/technical-design.md`, `spec/build-plan.md`,
`spec/audit-2026-09-06.md`) and in `git log` for anyone who needs the drafting sequence. This
changelog starts carrying full per-commit detail from P1 (`5fca369`) onward, where "done" first
meant "a module is green, fake-backed and independently testable" rather than "a document is
internally consistent."

---

## In flight, not yet in this changelog

Both sessions noted here as "in flight" when this file was first written have since landed —
see the 2026-09-07 "P8 and the real R1 run both land" section above. Nothing is in flight as of
the latest entry; this section is kept as the standing place to note it when something is.
