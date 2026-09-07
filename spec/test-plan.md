# Test Plan

**Draft 1 · September 2026 · Input: functional spec draft 3.2 §14, technical design 1.1 §17**

The functional spec's 126 acceptance criteria say *what must be true*. This document says *how
each is established, at what level, in what order, and which of them gate which release*.

**Testing approach: strict TDD.** Every module, including Android. The acceptance criterion is
written as a failing test before the code that satisfies it. Where that is awkward — services,
USB, audio hardware — the resolution is to make the logic testable rather than to skip the test
(§6).

---

## 1. The rule that makes 126 criteria tractable

Presented as one list, §14 reads as a wall that must be cleared before anything ships. It is
not. Criteria are **tiered by the release line that needs them** (implementation plan, "What v1
actually is"):

| Tier | Release line | Criteria | Meaning |
|---|---|---:|---|
| **T-A** | Usable (M0–M5) | 61 | The build is worth running overnight for its own sake |
| **T-B** | v1 (+M6, M7, M9) | 41 | The product the functional spec describes |
| **T-C** | v1.1 (+M10) | 14 | Shippable to other people |
| **T-D** | Later (M11) | 10 | The reference-tier ceiling |

A criterion is in the earliest tier whose milestone can satisfy it. **No criterion is optional**
— tiering orders them, it does not excuse them. The tier assignment for each criterion lives in
§9.

**Three criteria are gates rather than tests.** They stop the project rather than failing a
build:

| Gate | Criterion | If it fails |
|---|---|---|
| **R5** | AC-64 — 8-hour capture survives on the reference device | The device or the platform assumption changes (§10.8) |
| **R3** | The M4 comparison | Pass C is deleted and the architecture collapses to the text path |
| **R4/R15** | Cross-session identification precision (AC-106) | The voice library is not built; threading degrades to per-transmission |

---

## 2. Levels

| Level | Runs on | Speed | What it proves |
|---|---|---|---|
| **L1 · Unit** | JVM, no Android | ms | One class does what its contract says |
| **L2 · Module contract** | JVM | ms–s | A module satisfies its interface against fakes, including failure paths |
| **L3 · Golden pipeline** | JVM | s | A known WAV in produces exactly-known records out (AC-89) |
| **L4 · Integration** | Robolectric / instrumented | s–min | Room migrations, WorkManager, service lifecycle, permissions |
| **L5 · Device** | Reference + floor hardware | min–h | Audio routing, USB, thermal, OEM survival — things no emulator can prove |
| **L6 · Endurance** | Reference device | 8 h+ | NFR-3, NFR-7, AC-4, AC-64 |
| **L7 · Evaluation harness** | JVM + on-device | min | Every accuracy number in §10 |
| **L8 · Spec integrity** | CI | s | The documents remain self-consistent |

L1–L3 and L8 run on every push. L4 runs on every push (Robolectric) and before every merge to
`main` (instrumented, manual). L5–L7 run at milestone gates and are recorded in
`results/` with the run fingerprint.

---

## 3. Strict TDD, concretely

For each unit of work:

1. **Red.** Write the test from the acceptance criterion or the requirement, naming it after
   the ID: `AC_47_killing_process_mid_pass_completes_identically`. Run it; watch it fail for the
   right reason. A test that passes before the code exists is testing nothing.
2. **Green.** Smallest change that passes.
3. **Refactor.** With the test green.
4. **Trace.** The test carries the requirement ID in its name or an annotation, so §9's coverage
   matrix is generated rather than maintained.

**Requirements that are not directly testable** — "SHALL be visible in settings", "SHALL be
documented" — get a checklist item in the milestone's exit criteria instead of a fake test.
Writing an assertion that a string exists is theatre; say so and move on.

**Where TDD is genuinely awkward, and what to do instead of skipping it:**

| Hard case | Approach |
|---|---|
| `CaptureService` lifecycle | The service is a thin shell over `CaptureController`, which is pure and fully tested. The shell gets one Robolectric test that it starts, holds the wake lock, and posts the notification |
| `AudioRecord` | Behind `CaptureSource` (§5.1). All logic tests use `WavFileSource`. The real implementation gets L5 device tests only |
| USB serial | Behind `SerialTransport`. The descriptor engine is fully unit-tested against a scripted fake (AC-92); the transport gets L5 |
| Foreground survival | Cannot be unit-tested at all. L6, on hardware, with the heartbeat as the assertion (AC-64, AC-65) |
| ONNX inference | Not our code. We test *around* it: fixed seeds and pinned threads produce identical output (AC-90), and the wrapper's error and timeout paths are tested with a fake engine |
| Compose UI | State holders are pure and unit-tested. Composables get screenshot tests for the four attribution markers (AC-62) and a11y assertions (AC-63) |

---

## 4. The fake inventory

Technical design rule 3: *every model-bearing interface has a fake*. This is that list, and it
lives in `:testing`. **A module is not done until its fake exists**, because the next module up
is blocked without it.

| Fake | Stands in for | Used by |
|---|---|---|
| `WavFileSource` | `CaptureSource` | Everything. AC-89 depends on it |
| `TestClock` | `Clock` | Threading, retention, decay, ID windows (AC-91) |
| `ScriptedFakeRig` | `RigModule` | Frequency correlation, disconnection (AC-92) |
| `FakeSerialTransport` | `SerialTransport` | Descriptor engine, error paths |
| `FakeAsrEngine` | `AsrEngine` | Queue, shed, timeout, failure paths — returns canned results, hangs on demand (AC-99) |
| `FakeUnitSpotter` | `UnitSpotter` | Resolver tests without a model |
| `FakeEmbedder` | `SpeakerEmbedder` | Clustering and voice-library logic with synthetic embeddings |
| `FakeExecutionProvider` | `ExecutionProvider` | Tier and fallback logic (AC-38) |
| `InMemoryAssetStore` | asset lifecycle | Install, verify, rollback (AC-52) |
| `FakeContributionEndpoint` | `:net` upload | FR-CON paths without a server (AC-111..114) |
| `SyntheticTrafficGenerator` | a radio | Load and endurance (AC-29, AC-75) |

**Fakes are behavioural, not stubs.** `FakeAsrEngine` can be told to hang, to fail three times
then succeed, or to return a hallucination — otherwise the failure paths that most need testing
have nothing to test against.

---

## 5. Per-module definition of done

A module is **self-contained and testable** when all of these hold. This is the gate before
starting the next module.

1. Its public interface is defined and has no dependency the layering rules forbid (§2 of the
   technical design), enforced by the `dependencyRules` Gradle task.
2. Its fake exists in `:testing` and is behavioural.
3. Every requirement it owns has a test named for that requirement, passing.
4. Every failure path in its contract has a test — not just the happy path.
5. It builds and tests **without any other module's implementation** — only `:core` and fakes.
6. `./gradlew :module:test` is green in CI.
7. Its section of the coverage matrix (§9) generates cleanly.

| Module | Owns | Key criteria |
|---|---|---|
| `:core` | Ids, `Clock`, `PassId`, fingerprints, config | AC-91 |
| `:onnx` | Runtime loading, session lifecycle, residency | AC-103 |
| `:capture-api` | `CaptureSource`, ring buffer, resampler | AC-3, AC-89, AC-97 |
| `:capture-android` | `AudioRecord`, route verification, gaps | AC-1, AC-2, AC-48, AC-49, AC-98 |
| `:segment` | VAD, segmenter, squelch fusion, tier-invariance | AC-69..72, AC-94, AC-95, AC-68 |
| `:data` | Room, FTS5, migrations, queue, audio store | AC-53, AC-54, AC-45, AC-47 |
| `:asr-api` / `:asr-sherpa` | Engines, hallucination controls, transcripts | AC-6, AC-7, AC-8, AC-31, AC-90 |
| `:lexicon` | Lattice, grammar, priors, calibration | AC-9..15, AC-55, AC-56 |
| `:identity` | Embeddings, clustering, voice library, nets | AC-16..20, AC-67, AC-104..110, AC-123 |
| `:rig` / `:rig-usb` | Contract, descriptors, TH-D75A | AC-21..25, AC-50, AC-92 |
| `:pipeline` | Passes, queue orchestration, shed, tiers | AC-46, AC-51, AC-99, AC-28 |
| `:net` | Asset download, contribution | AC-59, AC-111..114 |
| `:app` | UI, a11y, onboarding | AC-62, AC-63, AC-66, AC-115 |
| `:eval` | Harness, metrics, folds | AC-35, AC-90, AC-100, AC-101 |

---

## 6. Test data

| Asset | Source | Used for | Fold |
|---|---|---|---|
| `noise-dev.flac` | 20 min, own recording | AC-6 continuously from M3 | dev |
| `noise-eval.flac` | 20 min, separate session | AC-6 at M11 only | **eval, sealed** |
| Validation hour | Own recording, hand-labelled | Callsign precision, attribution | split train/dev/eval |
| Paderborn HF | Zenodo 4247491, CC BY 4.0 | VAD, enhancement, real off-air noise | its own split |
| Fearless Steps | UT Dallas, NASA terms | Speaker separation (R4) | its own split |
| ATC merge | HuggingFace, free | Phonetic alphabet, phraseology | train |
| ISOLET | UCI, CC BY 4.0 | Letter-name pronunciations | train |
| Synthetic callsigns | Generated (D22) | Pass C training, resolver | **train/dev only — never eval** (FR-TST-9) |
| Crafted fixtures | Hand-made | Each hallucination control (AC-7) | n/a |

**Fold discipline is enforced by the harness, not by memory** (FR-TST-7): reading `eval`
requires an explicit flag, and every report states the fold that produced it (AC-100). A number
whose fold is unstated is not evidence.

---

## 7. Device test matrix

The tests no emulator can run. All require the reference device; the floor device is needed only
from M10.

| # | Test | Criteria | When |
|---|---|---|---|
| D1 | USB audio route binds and verifies; unplug detected within 5 s | AC-1 | M2 |
| D2 | Forced route mismatch halts capture | AC-2, AC-98 | M2 |
| D3 | 48 kHz-only adapter resamples correctly | AC-97 | M2 |
| D4 | **8-hour unattended run, ColorOS interventions applied** | **AC-64, AC-4** | **M2 gate** |
| D5 | Same run without exemptions fails and is reported | AC-5 | M2 |
| D6 | Heartbeat contradicts `isIgnoringBatteryOptimizations()` | AC-65 | M2 |
| D7 | Oppo-specific onboarding shown | AC-66 | M2 |
| D8 | "Prove it" 30-min test predicts D4's outcome | AC-64 | M2 |
| D9 | Incoming call produces a correct `CaptureGap`, capture resumes | AC-48 | M2 |
| D10 | USB re-attach mid-session surfaces the permission loss | F16 | M2 |
| D11 | No network during capture-and-process, contribution enabled | AC-59 | M2/M9 |
| D12 | Captured audio absent from cloud backup | AC-60 | M2 |
| D13 | Latency p95 ≤ 2 s at T1+ | AC-73 | M3 |
| D14 | Thermal degradation and recovery | AC-28 | M10 |
| D15 | TH-D75A: VID/PID, terminator, `AI` push behaviour | Q1 | M7 |
| D16 | Squelch fusion overrides VAD | AC-68 | M7 |
| D17 | Measured endurance published | AC-76, NFR-7 | M2/M10 |
| D18 | T0 on a 2 GB device | AC-26, AC-37 | M10 |

**D4 is the project's single most important test** and it takes eight hours plus setup. Run it
early, run it more than once, and run D8 first — the whole point of the "prove it" test is that
30 minutes should predict the eight hours.

---

## 8. CI pipeline

GitHub Actions on every push and PR. Public repo, so minutes are free.

```
lint        ktlint + detekt + the dependency-rules task
spec        spec-integrity checks (§8.1)
unit        :core :capture-api :segment :lexicon :identity :rig :eval  — JVM, fast
android     Robolectric: :data migrations, :pipeline, :app state holders
golden      L3 pipeline over committed fixtures
assemble    debug APK
report      coverage matrix + AC coverage delta, posted on the PR
```

Instrumented and device tests are **not** in CI: the tests that matter most cannot run on a
hosted runner, and pretending otherwise would give false confidence. They are a documented
manual checklist (§7) run at milestone gates.

### 8.1 Spec-integrity checks

These are cheap, and four of the last six defects found by hand would have been caught here:

| Check | Catches |
|---|---|
| AC ids contiguous, unique | Numbering drift |
| No dangling `FR-`/`AC-`/`NFR-`/`CON-`/`Q` reference | The Q17 defect |
| Every `D`*n* has a §16 traceability row | The recurring traceability lag |
| Every requirement group has ≥1 criterion | Draft 3.1's original finding |
| Every criterion names ≥1 requirement | Orphan tests |
| Every closed question has a decision, and vice versa | Register staleness |
| No mojibake, no tab characters | Encoding damage |

---

## 9. Traceability

**Generated, never maintained by hand.** Test names carry requirement ids; a Gradle task emits
`results/coverage-matrix.md`: every requirement, the criteria that cover it, the tests that
implement those criteria, and their last result. CI fails when a requirement in the current
release tier has no passing test.

The matrix is the answer to "are we done?", and it is the only honest one.

---

## 10. What this plan deliberately does not do

- **It does not chase a coverage percentage.** Line coverage on `:lexicon` says nothing about
  whether the grammar accepts a Slovenian callsign. The coverage that matters is §9's
  requirement matrix.
- **It does not test the ASR models.** They are third-party artifacts. We test our *use* of
  them — gating, rejection, determinism, timeouts, fallback — and we *measure* their accuracy
  through the harness, which is a different activity with a different output.
- **It does not gate M0–M2 on accuracy numbers.** Those need the corpus and the harness. Capture
  correctness, durability and survival are provable long before any number exists, and M2 is
  where the project's top risk lives.
