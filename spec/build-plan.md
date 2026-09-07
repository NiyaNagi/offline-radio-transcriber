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
- [x] **P5 · Data layer and queue** — Room, FTS5, migrations, lifecycle *(`:data`)* — done 2026-09-07
- [x] **P6 · Synthetic corpus and the two probes** — channel, generator, **R4**, **R1** *(`corpus/`)* — done 2026-09-07; both probes NOT RUN (no Fearless Steps / no LoRA+sherpa-onnx toolchain or GPU in this environment) — see `results/r4-speaker-separation.md`, `results/r1-lora-export.md`

**Wave C — composition.**

- [x] **P7 · Lexicon: ranking, calibration, harness** *(`:lexicon`, `:eval`)* — done 2026-09-07
- [ ] **P8 · Android capture spine** *(`:capture-android`, `:pipeline`, `:app`)*

**Wave D — the M2 gate, then transcription.**

- [ ] **P9 · GATE: 8-hour run on the reference device** *(device, mostly manual)*
- [ ] **P10 · ASR and hallucination control** *(`:onnx`, `:asr-api`, `:asr-sherpa`)*
- [ ] **P11 · End-to-end, then the M4 fork** *(`:pipeline`, decision gate)*

**Yours, not a session:** record the validation hour and write the labelling protocol (Q16).
Do it any time after P2; P6's probes and P7's harness both get better once it exists.

**After the fork.** M5 reader · M6 identity and voice library · M7 rig · M8 streaming · M9
digest, station knowledge, contribution · M10 tiers and reprocessing · M11 reference levers.
**Deliberately not decomposed** — M4 can delete Pass C, which changes what several of them
contain.

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
> cannot be met, stop and say why** rather than leaving a module half-done.

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
