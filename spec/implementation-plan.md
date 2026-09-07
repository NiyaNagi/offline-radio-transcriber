# Offline Radio Transcriber — Implementation Plan

**Draft 1.1 · September 2026 · Companion to [`technical-design.md`](technical-design.md)**

*Draft 1.1 applies the adversarial audit ([`audit-2026-09-06.md`](audit-2026-09-06.md)). The
plan-level findings: **FR-OBS-4 had no milestone at all** — draft 1 dropped it when it moved
corpus building to desktop tooling, leaving a Must requirement unassigned; **the harness was
planned as desktop-only**, which cannot measure T3 and so could not satisfy AC-36 for the
reference tier the whole design is built around; and **M0 would have produced a corpus that
seals away the only noise tape**, making AC-6 unrunnable until M11. M0 and M2 gained tasks;
the coverage check gained the rows it was missing.*

Milestone sequencing from functional spec §15, expanded into executable work. **M0–M4 are
detailed; M5–M11 are outlined only**, deliberately — M4 is an architectural fork (R3) whose
outcome can delete Pass C, collapse the lexicon layer to a text path, and change what the five
milestones after it are made of. Planning them in detail now would be planning the wrong thing
with high confidence.

Sizing is given in **relative units (S / M / L / XL)**, not dates. D18 (solo, heavily
AI-assisted) makes implementation throughput high and unevenly so; what does not compress is
recording tape, labelling it, waiting eight hours for an endurance run, and thinking about
interfaces. Those are called out where they dominate.

---

## The shape of the plan

```
M0  eval + training tape ─────────────┬──> M0a fine-tune ──┐
                                      │                     │
                                      └──> M1 resolver ─────┼──> M4 FORK ──> M5..M11
                                                            │       │
M2  capture spine (proves R5) ──> M3 Pass B ────────────────┘       │
                                                                    │
                              R3 answered here ─────────────────────┘
```

M0 blocks everything that produces a number. M2 blocks everything that runs on a phone. They
are independent of each other, which matters: **M2 can proceed while the tape is being
recorded and labelled**, and should, because M2 settles R5 — the project's top risk — and
needs no audio corpus to do it.

### Gates

| Gate | Question | Answer changes |
|---|---|---|
| **G-M0a** | Does a merged LoRA fine-tune export into sherpa-onnx's ONNX form? | The runtime choice itself (R1). **Half a day. Do it first.** |
| **G-M2** | Does an 8-hour capture survive on the reference device? | Whether the product is possible on this phone (R5) |
| **G-M0a′** | Do ATC fine-tuning gains transfer to amateur audio? | Every accuracy target in §10.1 (R2) |
| **G-M4** | Does audio-level resolution beat text-level? | The core architecture (R3), and the content of M5–M11 |
| **G-M11** | Does each reference-tier lever measure? | Whether it ships or is **deleted** (R6, Q12 kill rule) |

### Standing rules for every milestone

1. **Every milestone ends with a running app** (from M2 onward) and, where it produces a
   number, with that number **measured by the harness**, not asserted.
2. **Nothing touches the eval fold until M11.** Not for debugging, not for a quick check
   (§14A.2, R8). Development measurement uses a held-out slice of the *training* fold.
3. **New thresholds are config fields with documented defaults**, never literals (FR-CFG-2).
4. **No `System.currentTimeMillis()`, no direct `AudioRecord` outside `:capture-android`, no
   network outside `:net`.** These are build-checked, not remembered.
5. A subsystem is not done until its **fake** exists in `:testing`.

---

## M0 — Evaluation and training set · **XL, and almost none of it is code**

The blocking milestone. Every accuracy figure in the entire document set is transferred from
ATC literature or clean-speech benchmarks; nothing has been measured on off-air amateur audio.
This tape converts targets into measurements — **and it is also the fine-tuning set** (M0a), so
one labelling effort yields the project's largest accuracy lever as well as its ability to
measure anything at all.

### Tasks

| # | Task | Size | Notes |
|---|---|---|---|
| M0.1 | Build the capture rig: TH-D75A and SDS150 audio out → USB-C adapter → phone or laptop, gain set against open squelch | S | Buy **two dongles from different makers** (`research/02` §5.03). Verifies T7 early |
| M0.2 | Define the corpus manifest and label formats (below) **before recording** | S | This is the harness's input contract; getting it wrong costs a relabel |
| M0.3 | Record **8–12 discrete sessions**, 3–5 h total, across different days, times, bands and both radios | L | Per Q2's content table. Calendar time, not work time |
| M0.4 | Record **two noise tapes** — 20 min each of squelch, no speech, both radios, on different days | S | One for `eval`, one for `dev`. AC-6 depends on it, and with only the eval tape it is unrunnable until M11 (§14A.2, AC-101) |
| M0.4a | Record **losslessly** (FLAC or PCM), and enable continuous-archive capture for these sessions if it exists yet | S | FR-STO-2a. The corpus is the one artifact that cannot be re-derived, and M0's own boundaries are the ones you will most want to redo (Q14) |
| M0.5 | Assign whole sessions to **train / dev / eval**, ~70/30 by duration with dev held out of train, per §14A.2 | S | Manifest committed. **Tiebreak: if unsure, put it in eval** |
| M0.6 | Hand-label: callsigns, speaker turns, thread bounds, frequency, keying boundaries | XL | The dominant cost. Audacity label tracks → converter, or Label Studio |
| M0.7 | Label-quality pass: relabel a 10% sample blind, report disagreement | M | If self-disagreement is high, every downstream number inherits it |
| M0.8 | `:eval` harness v0 — manifest loader, metric implementations, report writer | M | Runs on hand transcripts before any model exists |

### Formats to fix now (M0.2)

```
corpus/
  manifest.json          # sessions, fold (train|dev|eval), radio, band, date, duration, checksum
  sessions/<id>/audio.flac           # lossless, 16 kHz mono — the harness's only input format
  sessions/<id>/labels.tsv           # startMs, endMs, speaker, callsign?, freqHz?, threadId, notes
  sessions/<id>/boundaries.tsv       # true keying start/end — feeds AC-69
```

**Three folds, not two** (§14A.2, FR-TST-7). The manifest is the enforcement point: the harness
refuses to read `eval` without an explicit flag and stamps the fold on every report (AC-100).
Sealing by discipline alone does not survive a debugging session at 2 a.m., and the cost of
one lapse is that no number the project publishes afterwards means anything.

`labels.tsv` is deliberately flat and hand-editable. `speaker` is a per-session opaque id (S1,
S2…) — the ground truth for clustering (AC-18) — and `callsign` is present only where a human
actually heard it, with an `uncertain` flag for the weak-signal material.

### Exit criteria

- Manifest committed, three folds assigned, **at least one eval session contains no station
  present in train** (§14A.2 step 4), eval noise tape and HF/DX traffic both in eval, **dev
  noise tape available and unsealed**.
- Harness v0 reports callsign precision/recall and WER against hand transcripts, stamped with
  the fold it read.
- Inter-pass label disagreement measured and stated.
- Audio retained losslessly; no perceptual codec anywhere in the corpus chain.

### Risks

R8 (contaminated eval set) is retired here or not at all. The failure is silent and permanent
— you cannot un-see the eval fold — so the manifest and the discipline are the deliverable as
much as the audio is.

---

## M0a — Domain fine-tune · **M, mostly waiting on a GPU**

The largest single lever in the project (L1: ATC 55.2% → 6.8% WER; 54.8% relative from 55
clips), and it improves **every tier including T0**, because a fine-tuned model is a file, not
a capability.

### Tasks

| # | Task | Size | Notes |
|---|---|---|---|
| M0a.1 | **G-M0a: the export round trip.** Fine-tune `distil-small.en` on ~10 minutes of anything, `merge_and_unload()`, run sherpa-onnx `export-onnx.py`, load the result, transcribe | S | **Do this first, before labelling finishes.** Half a day, retires R1 (§14A.1) |
| M0a.2 | Training environment: desktop or rented GPU, pinned versions, a committed script | S | Reproducibility matters more than speed |
| M0a.3 | LoRA fine-tune on the M0 **training fold** — start r=32, α=64 per the distil-Whisper aviation paper | M | Base must be in the export enum (D20) |
| M0a.4 | Measure stock vs. fine-tuned on a **held-out slice of train**, not the eval fold | S | AC-41's real measurement waits for M11 |
| M0a.5 | Record base-model licence, fine-tune id, training-data description as model metadata | S | FR-ASR-10, R9, NFR-6b. Cheap now, blocking later |
| M0a.6 | Refit the §9.3 confusion cost matrix from the fine-tuned model's error counts | S | Feeds `:lexicon` directly |

### Exit criteria

Export round trip verified end to end; a fine-tuned ONNX model loads in sherpa-onnx and beats
its stock equivalent on held-out training material; licence and provenance recorded.

### Risks

**R2 lives here.** If ATC gains do not transfer, the §10.1 targets are unreachable as written
and the honest response is to restate them from measurement rather than to chase them. That is
a spec amendment, not a schedule problem — and it is better to know at M0a than at M11.

---

## M1 — Lexicon resolver, off-device · **L, pure Kotlin, no phone involved**

`:lexicon` and `:eval` are Android-free precisely so this milestone runs on a desktop JVM with
fast test cycles. It is the largest single accuracy gain available and it can be built entirely
against M0's hand transcripts.

### Tasks

| # | Task | Size | Design ref |
|---|---|---|---|
| M1.1 | ITU prefix table, hand-verified, built into a trie asset | M | §9.6. Bundled, never optional (FR-LEX-29) |
| M1.2 | Phonetic unit vocabulary + variant table (NATO, legacy, regional, digit forms) | S | §9.1, FR-A11Y-6 |
| M1.3 | `PhoneticLattice` types and the `TEXT_DERIVED` builder | S | §9.2. **This is M4's baseline** |
| M1.4 | Grammar FSA + beam search over the lattice, with modifiers and reciprocals | L | §9.2, FR-LEX-7, FR-LEX-8 |
| M1.5 | Confusion-weighted substitution costs | M | FR-LEX-10 |
| M1.6 | Prior computation and clamped log-odds combination | L | §9.3, FR-LEX-9 |
| M1.7 | Static propagation model, asymmetric and repeater-suppressed | M | §9.4, FR-LEX-25..27 |
| M1.8 | Asset build scripts in this repo's Python tooling: WWARA, SDS150 favorites, POTA, D-STAR TSV → app assets | M | §9.6. The data is already here |
| M1.9 | ULS import: download, parse, index, transactional swap with checksum + count | M | FR-LEX-3, FR-LEX-30 |
| M1.10 | Platt calibration fitting + threshold-from-precision-target derivation | M | §9.5, FR-LEX-17..21 |
| M1.11 | Harness v1: reliability diagrams, per-prior ablation | M | AC-55, AC-56 |
| M1.12 | Cold-start behaviour: zero contribution, widened intervals, my-stations seeding | S | FR-LEX-31, FR-LEX-32 |

### Exit criteria

- On M0 hand transcripts (the training fold), the resolver reports callsign precision/recall
  with a **per-prior ablation** — which priors actually earn their place.
- A DX callsign with an allocated prefix and no ULS entry resolves as a candidate (AC-10); an
  unallocated prefix never reaches `CONFIRMED` (AC-11); phonetic variants of one callsign yield
  one result (AC-12).
- A confidence of 0.9 corresponds to ~90% observed accuracy on held-out training material,
  shown as a reliability diagram (AC-55, measured properly at M11).

### Note

M1 running against *hand transcripts* measures the resolver in isolation, with perfect input.
That is the right measurement here, and it is also the number M4 compares against once real ASR
output replaces the perfect input.

---

## M2 — Capture spine and runtime skeleton · **XL, and the one to do first on-device**

Bigger than it looks, for two reasons stated in functional spec §15: it settles R5 before any
model exists to confuse the diagnosis, and it establishes the interfaces every later milestone
plugs into. Under D18 these are exactly the decisions that are cheap now and expensive later.

**No ASR at all in this milestone.**

### Tasks

| # | Task | Size | Design ref |
|---|---|---|---|
| M2.1 | Gradle module skeleton + the dependency-rule task that fails the build on a forbidden edge | M | §2. Do it before there is anything to violate it |
| M2.2 | `:core` — ids, `Clock`, `PassId`, `PassFingerprint`, config schema, `Result` | M | §3 |
| M2.3 | `CaptureSource` interface + **`WavFileSource`** | M | §5.1, FR-TST-1 → AC-89. **Ships here, not later** |
| M2.4 | `AudioRecordSource`: device enumeration, preferred-device binding, **route verification with halt** | L | §5.2, FR-CAP-1..3, FR-CAP-3a → AC-1, AC-2, AC-98 |
| M2.4a | **Deterministic resampler** (48/44.1 → 16 kHz) and native-rate negotiation | M | §5.1, FR-CAP-2a → AC-97. Most USB adapters do not offer 16 kHz |
| M2.5 | Lock-free ring buffer with ≥1.2 s pre-roll | M | §5.3 → AC-3 |
| M2.6 | Level meter, clipping/silence detection | S | §5.4, FR-CAP-6, FR-CAP-7 |
| M2.7 | Silero VAD + segmenter state machine, max-length splitting, too-short rejection, **post-roll**, incremental write | L | §6 → AC-70, AC-71, AC-72, AC-95 |
| M2.7a | **Tier-invariance lock on segmentation** — the segmenter takes no `Tier` | S | FR-SEG-7 → AC-94. The precondition AC-39 assumed |
| M2.8 | PCM staging + **FLAC** encode step + decode-and-compare-then-delete | M | §6.1, FR-STO-2a, TD1. **Not Opus** — see R10 |
| M2.8a | Continuous-archive writer, off by default | M | FR-SEG-9, Q14 → AC-96. Cheap now, unbuildable later |
| M2.9 | Room schema, FTS5, indices, WAL, migration harness + first fixture | L | §12 |
| M2.10 | Durable work queue: leasing, crash recovery, transaction boundary, **partial-unique active index** | L | §7.1 → AC-45, AC-47. The unconditional constraint would have broken every reprocess |
| M2.10a | **Pass execution timeout + watchdog cancellation** | S | §7.1, FR-RUN-10a → AC-99. One hung inference call otherwise stops all processing forever |
| M2.11 | Transmission lifecycle state machine + legal-transition test | M | §7.2, FR-RUN-7..10 → AC-51 |
| M2.12 | Shed controller with hysteresis, `shed_event` logging, **battery→level-4 mapping** | M | §7.3 → AC-46 |
| M2.12a | `ModelResidency` classes and tier-budget enforcement | M | §4.3, FR-TIER-8 → AC-103 |
| M2.13 | Interruption/focus/route-change handling and `CaptureGap` | L | §5.5, FR-RUN-11..14 → AC-48, AC-49 |
| M2.14 | Clock model: `SampleClock`, monotonic + UTC + offset persistence | M | §3.2, FR-RUN-15..18 |
| M2.15 | `CaptureService`, wake lock, notification (no transcript text) | M | §5.6 → AC-61 |
| M2.16 | **Heartbeat liveness + kill reporting on next launch** | M | §5.6, NFR-8, NFR-10 → AC-5, AC-65 |
| M2.17 | **OEM guidance table + deep links, Oppo/ColorOS entry complete** | M | FR-SVC-5a, NFR-9 → AC-66 |
| M2.18 | **"Prove it" 30-minute unattended test with a pass/fail report** | M | FR-SVC-5c → AC-64 |
| M2.19 | Capture status surface (FR-UI-7) and a minimal raw transmission list | M | P4. Enough UI to see the machine working |
| M2.20 | Permissions flow, each requested in context | M | FR-PLT-1..4 |
| M2.21 | Synthetic traffic generator | S | FR-TST-6 → AC-29, AC-75 |
| M2.21a | **On-device harness runner** — same manifest, same report format, instrumented entry point | M | §17, AC-36. A desktop-only harness structurally cannot measure T3 |
| M2.22 | Reconciliation job | S | §12.4 → AC-54 |
| M2.23 | Storage projection, retention job, exhaustion ladder | M | FR-STO-3..5 → AC-77, AC-78 |
| M2.24 | Backup exclusion, media-store exclusion, no-network build check | S | §16 → AC-59, AC-60, AC-81 |

### Exit criteria — this is the R5 gate

- **AC-64**: an 8-hour unattended capture on the reference device, all four ColorOS
  interventions applied, survives; and the 30-minute "prove it" run **correctly predicted** it.
- **AC-5**: the same run with restrictions *not* exempted fails, and the failure is reported on
  next launch with its last heartbeat time.
- **AC-65**: liveness comes from the heartbeat, verified against a forced case where
  `isIgnoringBatteryOptimizations()` returns `true` and the app is killed anyway.
- **AC-45**: with all passes stalled, capture continues indefinitely and every segment reaches
  the durable queue.
- **AC-47**: killing the process mid-pass and relaunching completes the work identically.
- **AC-89**: a WAV replays through the pipeline faster than real time with identical results.

### Risks

**R5 is settled here or the reference device changes.** If ColorOS defeats an 8-hour capture
even with all four interventions and the empirical heartbeat confirms it, the honest options
are (a) a second device for capture with the flagship reserved for reprocessing — which the
cross-tier design already supports as a *designed workflow*, not a workaround — or (b) a
different reference device, which costs the only measured headline number in the project
(§10.7). Decide it with data from M2, not with a preference.

---

## M3 — Pass B and hallucination control · **L**

First end-to-end useful output: audio in, transcript on screen, nothing invented.

### Tasks

| # | Task | Size | Design ref |
|---|---|---|---|
| M3.1 | `:asr-api` interfaces; `:asr-sherpa` offline engine, same code path on JVM and Android | L | §8.1 |
| M3.2 | Model registry, descriptors, side-loading, fallback on invalid model | M | §8.4, FR-ASR-8 → F13 |
| M3.3 | Install the M0a fine-tuned model as the default where available | S | FR-ASR-9 |
| M3.4 | The six hallucination controls as named rules, ordered cheapest-first, **thresholds fitted against the dev noise tape** rather than inherited from Whisper defaults | M | §8.2, TD3 → AC-7, AC-101 |
| M3.5 | `REJECTED` as a first-class result with audio retained and a UI filter | M | FR-ASR-6 → AC-8 |
| M3.6 | Transcript versioning with the single-current partial index | M | §8.3 → AC-31 |
| M3.7 | Pass B wired into the queue as a real `Pass` with fingerprinting | M | §3.1, §3.4 |
| M3.8 | Text-derived lattice → M1 resolver → attribution, end to end on-device | M | §9.2, FR-LEX-6 → AC-15 |
| M3.9 | Tier stub (debug-fixed tier) so tier-conditional paths exist early | S | §14 |
| M3.10 | Latency instrumentation: p95 segment-close-to-visible | S | NFR-2 → AC-73 |

### Exit criteria

- **AC-6**: the **development** noise tape produces **zero** accepted transcripts, every
  segment rejected with a recorded reason. This is the single most important test in the plan,
  and it is re-run against the sealed eval noise tape at M11 (AC-101).
- **AC-73**: p95 Pass B latency ≤ 2 s for a 10-second transmission on the reference device.
- **AC-75**: no backlog growth at 15% simulated activity over a sustained run.
- The full text path — capture → VAD → Pass B → text lattice → resolver → attribution — works
  on the phone, and the harness reports its callsign precision/recall on the training fold.

**That last line is the number M4 has to beat.** Record it deliberately; it is the baseline of
the fork.

---

## M4 — Pass C and audio-level resolution · **L–XL, and the deliverable is a comparison**

D4 — the lexicon runs against the audio, not the text — is the project's core accuracy thesis
and the reason the architecture is shaped as it is. M4 proves it or kills it (R3). The
milestone is designed so that **the comparison is the deliverable**, not the feature.

### Tasks

| # | Task | Size | Design ref |
|---|---|---|---|
| M4.1 | `UnitSpotter` interface; the `TEXT_DERIVED` path is already its baseline implementation | S | §9.7 |
| M4.2 | **Option 1**: sherpa-onnx open-vocabulary KWS over the ~36-unit vocabulary | L | Lower risk. Try first |
| M4.3 | **TD2 probe**: can sherpa-onnx expose Whisper encoder hidden states on Android? | S | Gates option 2 entirely. Half a day |
| M4.4 | **Option 2** (only if M4.3 succeeds): CB-Whisper-style encoder-similarity spotting + small CNN | XL | Higher ceiling, unproven on this audio |
| M4.5 | Acoustic lattice → grammar FSA integration; alternatives retained with scores | M | FR-LEX-4, FR-LEX-5 |
| M4.6 | Per-unit precision/recall reporting in the harness (T2) | M | The diagnostic that explains a bad result |
| M4.7 | Refit calibration on acoustic-lattice scores; they are a different distribution | M | FR-LEX-21 |
| M4.8 | **The comparison run**: text-derived vs. each acoustic spotter, same audio, same resolver, same folds | M | R3 |

### Exit criteria — the fork

Report, on the training fold, for each of {text-derived, KWS, encoder-similarity}: callsign
precision, callsign recall, per-unit precision/recall, latency cost, resident memory cost.

Then take one of three branches, explicitly, and record the decision:

| Result | Branch | Consequence for M5–M11 |
|---|---|---|
| An acoustic spotter clearly beats text (recall ↑ at equal precision) | **Proceed as specified** | M5–M11 unchanged. Pass C is real, T1+ gets it, T0 keeps the text path |
| No measurable difference | **Collapse to the text path** | Delete Pass C. `:lexicon` keeps one lattice source. Spend the saved complexity on M6 and M9. Amend D4 in the functional spec |
| Acoustic is better but only with the encoder-similarity path, which is heavy | **Tier-gate it** | Pass C becomes a T2+/T3 capability, T0/T1 use text. Cross-tier reprocessing (already built) is what makes this safe |

**Whichever branch is taken, write it into the functional spec before starting M5.** M4's
result is the input to planning everything after it, which is why nothing after it is planned
in detail here.

### Risks

R3 is the milestone's whole purpose. The fallback — the text path — is already built and
measured at M3, so a negative result costs nothing but the M4 effort and **banks the saved
complexity forever**. That is the cheapest possible way to hold a core architectural thesis.

---

## M5–M11 — outline only

Deliberately thin. Each is stated as its purpose, its interface commitments (which M4 cannot
invalidate), its gate, and what M4's fork would change about it.

### M5 — Reader and search · **L**

Live view with visibly-superseded partials, thread view showing attribution reasoning, FTS5
search with filters, playback, one-tap correction, and the inspection surface that renders the
lattice, candidates and per-prior breakdown (P2, FR-UI-8). Accessibility is a build constraint
here, not a later pass: greyscale-distinguishable states (AC-62), screen-reader navigation and
maximum font scale (AC-63).

Also lands here: **FR-OBS-4, the "record a labelled sample" mode** — a Must that draft 1 of
this plan left with no milestone at all, having moved corpus building to desktop tooling in M0
and then never rehoused the requirement. It belongs in M5 rather than M0 (a tool shipping in
the app cannot be what produces the corpus that gates the app) and it belongs with the reader
rather than alone, because correction UI is most of the same surface. Its job is keeping the
corpus **growing** from real sessions in the format the harness already reads — the cheapest
labelled minute is the one captured where the operator just noticed something interesting.

*M4 effect:* the inspection surface renders whatever lattice source survives — one screen,
either way. Adopt Q8's tiered correction (pick from candidates → search lexicon → free text
marked unverified), because only the first two can feed the priors.

### M6 — Identity and threading · **L**

Speaker embeddings, incremental clustering, back-propagation on `CONFIRMED`, correction
propagation with `CORRECTED` locks, cluster split, decay and cross-day re-confirmation.
Threading keys on channel identity where available, frequency otherwise (Q4).

*Gate:* **R4.** Test embedding separation on the M0 tape **before building the milestone** —
if narrowband off-air audio does not separate, threading degrades but per-transmission
attribution survives intact, which is a fine product. Do not build clustering into a negative
result.

### M7 — Rig interface · **M**

Null module first-class, descriptor engine, TH-D75A descriptor, connection test screen showing
raw traffic (FR-RIG-12), scripted fake in `:testing`. Three hardware verifications remain:
frequency read command, VID/PID under Android, safe `BY` poll rate (§11, Q1).

*Payoff beyond frequency:* squelch fusion (FR-SEG-5 → AC-68) converts segmentation from an
inference into a measurement, against the #1 failure mode. Worth pulling earlier if M3's
rejection rate on real traffic is disappointing.

### M8 — Pass A streaming · **L**

Streaming Zipformer with `modified_beam_search` hotword biasing from the active lexicon slice
(FR-LEX-15, capped at 500 entries), partials visibly provisional and superseded (P5).

*Gate:* T4 — hotword automaton cost at ~100 units. *M4 effect:* the active-slice construction
is unchanged either way; only what feeds Pass D changes.

### M9 — Digest and export · **M**

Deterministic digest (FR-DIG-2), ADIF/CSV/POTA export with confidence carried and `INFERRED`
never exported as `CONFIRMED` (FR-EXP-4 → AC-33).

*Do Q7 first:* sketch the digest on paper against real M0 data before writing code. G1 is the
primary user-facing deliverable and currently the least specified. If items 1 and 2 of Q7's
list are what you actually read, **FR-DIG-3's LLM digest should be cut** rather than built.
AC-88 — "conveys what happened in under two minutes" — is the acceptance test.

### M10 — Tier system and cross-tier reprocessing · **L**

Real tier detection from measured throughput (never an allowlist), automatic reversible
degradation, T0 validation on the 2 GB floor device, and the reprocessing candidate flow.

*Gate:* **AC-39** — a T0 capture reprocessed at T3 matches a native T3 capture. That single
test validates the entire tier inversion, and it is only possible because §3.4's fingerprints
and §5.2's audio retention were built in from M2.

### M11 — Reference-tier levers · **XL, and expected to shed parts**

NPU execution provider (Qualcomm only at v1, per Q11), ensemble fusion of Pass A and Pass B,
LLM n-best rescoring over a **closed** candidate set (AC-43 verifies adversarially that it
cannot invent the right answer), and per-pass enhancement evaluation.

**This is where the eval fold is finally opened**, and every §10.1 number becomes a
measurement. Q12's kill rule applies literally: a lever that does not measure is **deleted, not
disabled** — a disabled lever costs complexity forever.

*Expect some to fail.* Enhancement is genuinely contested; rescoring is unproven on this
audio. That is the plan working, not the plan going wrong.

---

## Coverage check

Where each acceptance-criteria group is first satisfied:

| §14 group | Milestone |
|---|---|
| 14.1 Capture · 14.8a Runtime · 14.8f Storage/config | M2 |
| 14.2 Segmentation/hallucination · 14.8d Segmentation quality | M2 (boundaries) / M3 (hallucination) |
| 14.3 Callsign resolution · 14.8b Calibration | M1 (off-device) → M3 (on-device text path) → M4 (acoustic) |
| 14.4 Identity and threading | M6 |
| 14.5 Rig interface | M7 |
| 14.6 Tiers and degradation | M2 (shedding) → M10 (detection, AC-39) |
| 14.7 Reprocessing | M10 |
| 14.8 Accuracy levers | M0a (AC-41) → M11 |
| 14.8c Platform/a11y/privacy | M2 (privacy, platform) → M5 (a11y) |
| 14.8e Latency | M3 |
| 14.8g Digest | M9 |
| 14.9 Export | M9 |
| 14.10 Harness | M0 (v0, AC-100) → M1 (v1) → M2 (AC-89, AC-90, AC-91, on-device runner) → M7 (AC-92) → M11 (full per-lever) |

Criteria added in draft 3.2:

| Criterion | Milestone |
|---|---|
| AC-94 tier-invariant segmentation · AC-95 pre/post-roll · AC-97 resampling · AC-98 built-in mic · AC-99 pass timeout · AC-103 residency budget | M2 |
| AC-96 re-segmentation from continuous archive | M2 (writer) → M10 (re-segmentation flow) |
| AC-100 harness fold gating | M0 |
| AC-101 AC-6 on dev tape, then eval tape | M3, re-run at M11 |
| AC-102 lossy-codec justification | M4 (measurement) — until then, lossless stands |

AC-92 was previously filed under the harness group and belongs to **M7**, since a scripted fake
rig cannot exist before the rig contract does. Two groups still have no home before M5 and that
is intentional: AC-62/63 (accessibility) need a real UI, and AC-84..88 (digest) need real
captured data to be worth designing against. AC-93 (installs on the minimum API level) is a CI
check from M2 onward rather than a milestone deliverable.

---

## What would make this plan wrong

Stated so it is checkable rather than assumed:

1. **M0's tape is too small or too uniform.** Eight to twelve sessions across days, bands and
   radios is the requirement; one long evening on one repeater would satisfy the hour count and
   measure nothing.
2. **M2 slips into building UI.** M2's UI is the capture status surface and a raw list. The
   reader is M5, and pulling it forward delays the R5 answer, which is the point of M2.
3. **M4's comparison is not run cleanly.** Same audio, same resolver, same folds, harness-run,
   both numbers recorded before either is judged. A thesis this central deserves a measurement
   that could have gone the other way.
4. **The eval fold gets opened early.** Once, for a quick check, is enough to make every
   subsequent number unfalsifiable.
5. **A lever survives M11 without measuring.** Q12's kill rule exists because "disabled" is how
   complexity becomes permanent.
6. **M2 commits to a lossy retention codec to save space.** The pass that cares about it is not
   measured until M4, so the cost would be misread as the accuracy thesis failing (R10). This
   is the one storage decision that is an accuracy decision.
7. **The dev/eval noise split is skipped as bureaucracy.** It costs one afternoon of recording
   and it is the difference between tuning the hallucination controls against a measurement and
   tuning them against a feeling.
