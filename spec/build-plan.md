# Build Plan — session by session

**Draft 1 · September 2026 · The working todo list. Update it as you go.**

One session builds one module, test-first, and ends with that module **green, fake-backed and
independently testable**. Nothing starts until the thing below it is done, because under strict
TDD a module with no fake blocks everything above it.

Each entry gives: the goal, what must exist first, the tests to write **before** the code, the
exit condition, and a paste-ready prompt to open the session with.

**Conventions.** `S`*n* is a session. Tests are named for the requirement or criterion they
establish. A session is done when `./gradlew :module:test` is green in CI, the module's fake
exists, and the definition of done in [`test-plan.md`](test-plan.md) §5 holds.

---

## Progress

**Phase 0 — foundation**

- [ ] S0.1 Repo skeleton, Gradle, CI, spec-integrity checks
- [ ] S0.2 `:core` — ids, `Clock`, `PassId`, fingerprints, config
- [ ] S0.3 `:testing` — fake harness scaffolding, `TestClock`

**Phase 1 — corpus and the two cheap probes (M0, M0a)**

- [ ] S1.1 Corpus manifest schema, validator, fold gate
- [ ] S1.2 Acquisition: Paderborn, Fearless Steps, ATC, ISOLET
- [ ] S1.3 Channel model learned from Paderborn's parallel pairs
- [ ] S1.4 Synthetic callsign generator
- [ ] S1.5 Harness v0 — metrics over hand transcripts
- [ ] S1.6 **PROBE: R4** — speaker separation on Fearless Steps
- [ ] S1.7 **PROBE: R1** — ONNX export round trip
- [ ] S1.8 Record and label the validation hour *(you, not a session)*

**Phase 2 — lexicon, off-device (M1)**

- [ ] S2.1 `:lexicon` types, phonetic units, variant table
- [ ] S2.2 ITU prefix trie and the callsign grammar FSA
- [ ] S2.3 Confusion-weighted scoring
- [ ] S2.4 Priors and clamped log-odds combination
- [ ] S2.5 Propagation model
- [ ] S2.6 Calibration and threshold derivation
- [ ] S2.7 Text-derived lattice builder
- [ ] S2.8 `:eval` harness v1, reliability diagrams

**Phase 3 — capture spine (M2) · the R5 gate**

- [ ] S3.1 `:capture-api` — `CaptureSource`, `WavFileSource`, resampler
- [ ] S3.2 Ring buffer and pre-roll
- [ ] S3.3 `:segment` — VAD, segmenter, tier-invariance lock
- [ ] S3.4 `:data` — Room schema, FTS5, migrations
- [ ] S3.5 Durable queue, lifecycle, leasing, pass timeout
- [ ] S3.6 Shed controller and model residency
- [ ] S3.7 `:capture-android` — `AudioRecord`, route verification, gaps
- [ ] S3.8 FLAC store and continuous archive
- [ ] S3.9 `CaptureService`, heartbeat, OEM guidance, "prove it"
- [ ] S3.10 Capture status UI and permissions
- [ ] S3.11 On-device harness runner
- [ ] S3.12 **GATE: 8-hour run on the reference device (AC-64)**

**Phase 4 — transcription (M3)**

- [ ] S4.1 `:onnx` — runtime, sessions, residency
- [ ] S4.2 `:asr-sherpa` — offline engine behind `:asr-api`
- [ ] S4.3 The six hallucination controls
- [ ] S4.4 Transcript versioning and model registry
- [ ] S4.5 Pass B in the pipeline, end to end to the resolver
- [ ] S4.6 Latency instrumentation

**Phase 5 — the fork (M4)**

- [ ] S5.1 `UnitSpotter` interface and KWS implementation
- [ ] S5.2 Encoder-hidden-state probe (TD2)
- [ ] S5.3 **GATE: the comparison, and the architecture decision**

**Phase 6+ — after the fork**

M5 reader · M6 identity and voice library · M7 rig · M8 streaming · M9 digest, station
knowledge, contribution · M10 tiers and reprocessing · M11 reference levers. **Not decomposed
here on purpose** — M4 can delete Pass C, which changes what several of them contain.

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
