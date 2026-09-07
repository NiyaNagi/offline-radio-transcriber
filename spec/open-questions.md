# Open Questions

**Decision register · current against functional spec draft 3.2 · September 2026**

**Draft 3.2 update.** The adversarial audit ([`audit-2026-09-06.md`](audit-2026-09-06.md))
opened **Q13** (retention format — supersedes Q5, and it is now the more urgent question
because of R10) and **Q14** (continuous archive, because segmentation turns out to be the one
irreversible decision in the pipeline). Both are product calls wanted before M2 writes storage
code. Q5 is folded into Q13 and should be answered there.

## Status, 7 September 2026 — the register is nearly empty

**Closed:** Q3–Q11, Q13, Q14, Q15 and Q17, recorded as D19–D32 in spec §3. Q1 is closed but for
three hardware verifications (VID/PID, command terminator, whether `AI` pushes `BY`) — see
[`../docs/reference/th-d75a-cat.md`](../docs/reference/th-d75a-cat.md). **Q12 is settled by
rule**: a reference-tier lever that does not measure on the eval fold is deleted, not disabled.

**Two remain, and both concern the same hour of audio:**

| # | Question | Why it is still open |
|---|---|---|
| **Q2** | Record the tape | Much smaller than it was — ~1 h of validation audio rather than 3–5 h of training data (§14A.3) — but it is the one thing no amount of specification substitutes for |
| **Q16** | The labelling protocol | Gates labelling that hour. Also much smaller now: callsigns and speaker turns only |

Everything that could be decided on paper has been decided.

*Entries below keep their original reasoning, with the answer recorded where one was reached.
Answers are authoritative in spec §3; this file records how they were reached.*

Two risks moved on the strength of these answers: **R1 (fine-tune export) dropped from Severe
to Low** — see §14A.1, the export enum already carries `distil-*` variants and a merged LoRA
is structurally identical to its base — while **R5 (background-killing) became the top risk**,
because the reference device runs ColorOS.

Q11 (NPU vendor scope) is also effectively settled: the reference device is Snapdragon, which
is the one vendor with published per-device benchmarks for this exact model.

Answered questions move to §3 for the record.

---

## 1. Open

### Q1 — Rig protocol verification · **LARGELY CLOSED** · owner: engineering

**Question.** Does the TH-D75A expose a CAT interface over its USB port that enumerates as
a serial device Android can claim, and what is the actual command set? Same question for
the SDS150 remote protocol.

**Answered for the TH-D75A by material already in this repo.**
`thd75a programming details/TH_D75_Commands.pdf` (KI4LAX, May 2024, 16 pp) is a serial
command reference documenting **two-letter CAT commands with read/set syntax, parameter
widths and value tables**. Three findings, all favourable:

| Finding | Consequence |
|---|---|
| Commands are two-letter ASCII read/set — `AG` volume, `BC` band control, `BL` battery level, `BT` Bluetooth, `AE` serial number | Fits the **declarative descriptor** design (FR-RIG-4) exactly. The TH-D75A module needs no code |
| **`BY` reports squelch status** | FR-SEG-5 (squelch/VAD fusion) is achievable, not speculative. Squelch state is authoritative for transmission boundaries — a material accuracy gain |
| Kenwood ships `USB_CDC_Driver_TH-D75_V100` — the radio is a **CDC** device | `usb-serial-for-android` handles CDC-ACM natively. **No vendor driver, no root, no OTG special case** |

The repo README notes the caveat that this is third-party documentation, not Kenwood's —
nothing in the official manuals documents the serial protocol.

**Remaining work, no longer blocking:**
1. Confirm the specific command for **frequency** read (not enumerated in the README
   summary; it is in the PDF).
2. Confirm VID/PID and that Android claims it without a vendor driver.
3. Determine poll rate limits — how fast can `BY` be polled without disrupting the radio?
4. **SDS150 remains fully unverified.** Deferred to v2, but establishing whether it is
   ASCII (declarative) or binary (code module) validates the §9 three-level design.

**Recommendation.** Extract the frequency and squelch commands from the PDF into a first
draft of the `kenwood-thd75a` descriptor now — it is the cheapest possible validation that
the descriptor format is expressive enough.

---

### Q2 — Evaluation set scope · **BLOCKING** · owner: product

**Question.** How much labelled audio, covering what, and labelled to what depth?

**Why it matters.** Every accuracy number in this spec and in all three research documents
is transferred from ATC literature or clean-speech benchmarks. **No published WER figure for
any of these models on off-air amateur radio audio exists.** M0 is the gate on everything.

**Recommendation.** Minimum viable set:

| Content | Duration | Labels needed |
|---|---:|---|
| TH-D75A, 2 m/70 cm repeater, conversational | 90 min | callsigns, speaker turns, thread bounds |
| TH-D75A, HF SSB including DX | 60 min | callsigns (incl. non-US), frequency |
| SDS150 scanning, public safety | 60 min | frequency changes, speaker turns |
| Squelch noise, no speech | 20 min | **none — this is the hallucination control** |
| Weak/marginal signals | 30 min | callsigns where humanly possible, marked uncertain |

The noise tape is not optional. AC-6 (zero accepted transcripts from pure noise) is the
single most important test in the plan, and it needs real squelch tails from both radios.

---

### Q3 — Reference device · **CLOSED** · owner: product

**Answer: OPPO Find X9 Ultra.** Snapdragon 8 Elite Gen 5 (SM8850-AC), 12–16 GB RAM,
7050 mAh, Android 16 / ColorOS 16. Recorded as D19; details in spec §10.7–10.8.

**This is the best possible choice for the accuracy ceiling and the worst for the platform
risk, and both facts are large.**

**What it buys.** It is the *exact* chipset Qualcomm published `large-v3-turbo` NPU
benchmarks for — 267–278 ms encoder per 30 s window, ~6.3 ms/token decoder. The ~22x RTF
figure underpinning T3 becomes a measurement on this silicon rather than an extrapolation,
which is true of no other number in the project. The 7050 mAh battery (~27 Wh, versus the
15–20 Wh previously assumed) makes NFR-3's 8-hour target trivially clearable — at 15%
activity, T3 NPU inference is roughly 39 seconds of accelerator time across a whole shift.
Endurance stops being a design constraint on this device.

**What it costs.** ColorOS is among the most aggressive background-killers on the market, and
the failure is subtle rather than loud: **`isIgnoringBatteryOptimizations()` can return `true`
while ColorOS kills the app anyway.** The standard check lies. Oppo requires four separate
interventions — recent-apps pinning, the security app's startup manager, battery-optimization
exemption, and a foreground notification — of which only the third is visible to the API.

**Consequence.** R5 was rated "low on Pixel, high on Samsung"; it is now **the project's top
risk**, and the mitigations changed shape: empirical heartbeat liveness (NFR-8, FR-SVC-5b),
manufacturer-specific onboarding (NFR-9, FR-SVC-5a), and a 30-minute "prove it" test run
(FR-SVC-5c) so the setup ritual becomes a pass/fail rather than an act of faith. M2 exists to
settle this before any model complicates the diagnosis.

**Floor device still needed:** any 2 GB Android 8+ handset, ~$40 used, for AC-26 and AC-37.

---

### Q4 — Frequency granularity and channel identity · **CLOSED** · owner: product

**Answer: frequency only for v1.** Channel names and channel-based threading move to v2 with
the SDS150 module (§1.5). `channelName` stays in the Transmission entity so it is not a
migration later — the recommendation below was adopted structurally and deferred in the UI.

**Question.** When the SDS150 is scanning and changes channel several times per second, what
does "the frequency of this transmission" mean? And should a transmission be tagged with a
frequency, a channel/talkgroup, or both?

**Why it matters.** FR-RIG-6 currently says "state at transmission start, flag if it
changes", which is right for the TH-D75A and possibly wrong for a scanner. Threading
(FR-SPK-5) uses frequency continuity, which behaves very differently in scan mode.

**Recommendation.** Model **channel identity** as distinct from frequency, since the SDS150
favorites CSV in this repo already keys on channel. Thread on channel identity where
available, frequency otherwise. Defer resolution to v2 with the SDS150 module, but do not
let the v1 data model foreclose it — hence `channelName` already in the Transmission entity.

---

### Q5 — Audio retention default versus reprocessing horizon · **SUPERSEDED BY Q13** · owner: product

*Q5 asked only how long to keep audio. The audit found the harder half of the question — in
what **format** — which changes the storage arithmetic below by 4x and carries an accuracy risk
(R10) that duration does not. Answer Q13; it subsumes this.*

**Question.** FR-STO-3 defaults audio retention to 30 days. FR-REP-4 notes that retention
shorter than the reprocessing horizon defeats reprocessing. What is the intended horizon?

**Why it matters.** Storage estimate: gated Opus at 15% duty is roughly 1.1 MB per hour of
wall clock, so ~26 MB/day, ~800 MB/month, ~9.5 GB/year. Indefinite audio retention is
genuinely affordable on a modern phone.

**Recommendation.** Default audio retention to **indefinite** with a storage-pressure-driven
prune, rather than a time limit. The reprocessing value is high and the storage cost is low.
Make time-based retention available for users who want it, with the FR-REP-4 warning.

---

### Q6 — Multiple radios simultaneously · **CLOSED** · owner: product

**Answer: no for v1**, as recommended. `sourceId` is already in Session and Transmission, so
adding it later is not a migration. Note that D23 makes the TH-D75A's *two bands* a
simultaneous-source problem already, solved by squelch attribution rather than by two pipelines.

**Question.** Should v1 support capturing from the TH-D75A and the SDS150 at the same time,
on separate audio inputs?

**Why it matters.** The brief describes two deployment *modes*, which reads as
alternatives. But the phone has one USB port and a hub could carry two audio adapters. The
data model would need a per-session source identifier.

**Recommendation.** **No for v1**, but add a `sourceId` to Session and Transmission now so
it is not a migration later. Two concurrent capture pipelines double the compute at exactly
the point where tier headroom matters, and the value is unproven.

---

### Q7 — What "digest" actually contains · **CLOSED — and it grew** · owner: product

**Answer: all four candidates, ordered by salience, plus accumulation.** Stations with
confirmed/inferred counts, new stations, POTA/SOTA references and anomalies (FR-DIG-2a).

Beyond the list, the product owner asked for knowledge that **accumulates** — across nights and
per station, forming a graph that reveals more the longer the app listens. That became **D29**
and FR-DIG-7..14: deterministic station *facts*, optional and clearly-marked LLM *topic
summaries*, and links across sessions, stations, threads and transmissions.

The split is the load-bearing part. FR-DIG-12 keeps the product on the side of logging what was
transmitted rather than profiling a person, and R16 records why: a hallucinated claim about
someone's circumstances persists, is about a real person, and cannot be checked by the reader.

*This entry's original prediction — that if items 1 and 2 were what you actually read, the LLM
adds nothing and FR-DIG-3 should be cut — still stands and is still worth testing against real
M0 data.*

**Question.** FR-DIG-2 specifies a deterministic digest but the content list is a guess.
What do you actually want to read after eight hours away?

**Why it matters.** This is the goal G1 deliverable and the most user-facing part of the
product, and it is currently the least specified. It also determines whether the LLM
(FR-DIG-3) is worth building at all.

**Recommendation.** Sketch the digest as a paper mockup against real M0 data before writing
any code. Candidate content, in priority order:
1. Stations heard, with confirmed/inferred counts and last-heard time
2. New stations never heard before
3. Activity by frequency and hour, as a sparkline
4. Threads over N transmissions, with participants
5. POTA references heard
6. Anomalies — high rejection rate, long silences, unusual activity

If (1) and (2) turn out to be what you actually read, the LLM adds nothing and FR-DIG-3
should be cut.

---

### Q8 — Correction UX depth · **CLOSED** · owner: design

**Answer: tiered**, as recommended — ranked candidates, then lexicon search, then free text
marked unverified. Only the first two feed the priors. Recorded as D32, and it is also the
natural home for manual voice binding (FR-SPK-23).

**Question.** FR-UI-6 says "one-tap correction of any attribution". Correcting *to what*?
Free text? A picker over candidates? A search over the lexicon?

**Why it matters.** P3 says corrections should propagate and improve future results. A
free-text correction that does not match a lexicon entry cannot feed the priors.

**Recommendation.** Tiered: pick from the ranked candidate list (fastest, feeds priors
directly), else search the lexicon, else free text marked as unverified. Only the first two
feed the recency prior.

---

### Q9 — Handling nets and controlled sessions · **CLOSED — reversed** · owner: product

**Answer: detect nets in v1**, against this entry's own recommendation to defer.

The reasoning that changed it is the one already written below: a check-in roll call is a
sequence of callsigns read aloud deliberately, one at a time, often repeated back by the
control operator — the richest source of clearly-spoken callsigns this product will ever hear.
The voice library (D28) makes the dominant voice easy to identify, so detection got cheaper at
the same time its value became clearer.

Detection is **advisory, never structural** (FR-SPK-28): a misdetection changes presentation and
never the record. Recorded as D30, FR-SPK-27..30.

**Question.** A net has a control operator, a check-in list, and a rigid turn structure —
very different from a two-station QSO. Should the threading model know about nets?

**Why it matters.** Nets are among the highest-value traffic to log (they are literally a
list of callsigns being read out), and generic threading will model a 30-station net as one
enormous thread with 30 voiceprints.

**Recommendation.** Not a v1 feature, but note it: a net is detectable heuristically
(one dominant voiceprint alternating with many others on a fixed frequency), and the
check-in sequence is an unusually rich source of confirmed callsigns. Worth a v2 feature and
worth not designing the Thread entity in a way that precludes it.

---

### Q10 — Fine-tuning data split and licensing · **ANSWERED** · owner: engineering

**Question.** How much labelled amateur-radio audio for the training fold, how is it split,
and can any of it be shared if the project is open-sourced?

**This was mis-filed as a product decision. It is methodology, and it is specified in full in
spec §14A.2.** The short version:

**Split by recording session, never by transmission.** Splitting randomly across
transmissions leaks three ways at once — the same voice, the same channel conditions, and the
same conversation land in both folds — so the model is scored on speakers and audio it
effectively trained on. A model that memorised three local repeater regulars would look
excellent and generalise to nothing.

Recipe: record **8–12 discrete sessions** across different days, bands and radios rather than
one long tape; assign **whole sessions** to train or eval at roughly **70/30 by duration**;
ensure at least one eval session contains **no station appearing in train**; put the **noise
tape and the HF/DX traffic in eval**; commit a manifest recording the assignment; and **do not
look at the eval fold until M11** — not for debugging, not for a quick check.

**The tiebreak rule, for when you are unsure about a given session: put it in eval.**
Under-training costs a few WER points that more data later recovers. Contaminating the eval
fold destroys your ability to measure anything and is not recoverable — you cannot un-see it.
The ATC precedent got a 54.8% relative reduction from 55 clips, so the training fold does not
need to be large; spend surplus material on a generous eval fold.

**Licensing remains genuinely open, and is not blocking.** A fine-tuned model derived from
your own recordings is yours to publish if the base model's licence allows. The *recordings*
capture identifiable third parties and redistribution norms for off-air amateur audio are not
obvious — so publish weights freely, and treat publishing audio as a separate, deliberate
decision. Record the base model's licence from day one (FR-AST-1) so this stays open.

---

### Q11 — NPU vendor scope · owner: engineering

**Question.** Which accelerators does T3 support at v1 — Qualcomm only, or Qualcomm plus
Google Tensor plus MediaTek?

**Why it matters.** Qualcomm has published, per-device, per-chipset `large-v3-turbo`
benchmarks across 40+ combinations. Tensor and MediaTek are supported by LiteRT but with far
less published evidence for this specific model. Supporting three means three toolchains or
accepting LiteRT's abstraction and some performance loss (FR-ACC-6).

**Recommendation — now effectively settled by Q3.** The reference device is Snapdragon
8 Elite Gen 5, the exact silicon Qualcomm publishes `large-v3-turbo` benchmarks for.
**Qualcomm only at v1**; treat T3 as unavailable on other accelerators until measured.
FR-ACC-4 already requires graceful absence, so this costs nothing but a smaller T3 population.

The Q3 answer resolved the tension noted here — a Pixel reference would have meant Tensor,
where the published evidence for this model is much thinner. The device chosen has the best
evidence base available, at the cost of the worst background-execution behaviour (§10.8).

---

### Q13 — Audio retention format and the reprocessing horizon · **CLOSED** · owner: product

**Answer: storage budgets, not time limits** (D26) — independent user-set budgets for gated
audio and the continuous archive, "unlimited" allowed, and bulk export-and-prune over a date
range when one is reached rather than silent deletion (FR-STO-3a..c). On format, **lossless
stands** until FR-STO-2b's measured comparison justifies otherwise, which retires R10.

*The storage table below is still the right arithmetic; note that Q14's answer makes the
continuous-archive row the one that dominates in practice.*

**Question.** Lossless (FLAC) or lossy (Opus) retention, at what bitrate, for how long? This
supersedes and finally forces **Q5**, which asked only about duration.

**Why it matters, and why it is more urgent than it looks.** FR-REP-4 requires retained audio
sufficient to re-run *every* pass, and Pass C (FR-LEX-4) is sub-phoneme acoustic
discrimination on narrowband noisy speech — the worst case for a perceptual codec. The
ordering is the hazard: **the codec is chosen in M2, and the pass that cares is not measured
until M4.** A lossy default would be indistinguishable, at M4, from the core accuracy thesis
simply failing. That is R10.

Storage, for the actual decision:

| Format | Per wall-clock hour @15% duty | Per 8 h shift | Per year of daily 8 h |
|---|---:|---:|---:|
| Opus ~24 kbps | ~1.1 MB | ~9 MB | ~3.2 GB |
| FLAC 16 kHz mono | ~4.5 MB | ~36 MB | ~13 GB |
| PCM 16 kHz mono | ~9 MB | ~72 MB | ~26 GB |
| Continuous FLAC (Q14, no gating) | ~30 MB | ~240 MB | ~88 GB |

**Recommendation.** **FLAC, indefinite, with storage-pressure pruning of the oldest audio
first.** 13 GB/year is affordable on the reference device, it is exactly reversible so no
future pass can be blamed on it, and it retires R10 outright rather than managing it. Revisit
only if FR-STO-2b's measurement shows Opus costs nothing — and note that a measurement showing
"no effect on Pass B" is not the same as "no effect on Pass C", which is the point.

Time-based retention stays available for users who want it, with the FR-REP-4 warning.

---

### Q14 — Is continuous-archive capture worth its storage? · **CLOSED** · owner: product

**Answer: yes, and keep it always available** (D24) — broader than this entry recommended,
which was to enable it only for M0 recording. Segmentation is otherwise permanent (CON-SEG-1),
and this is the only mechanism that makes it reprocessable. Default off; budgeted under D26.

**Question.** FR-SEG-9 offers an optional mode retaining the *unsegmented* stream. Ship it in
v1, defer it, or drop it?

**Why it matters.** CON-SEG-1 established that segmentation is the one decision reprocessing
cannot undo — a clipped callsign, a merged pair of overs, a transmission split in two are
permanent, because the audio between gated segments is never kept. Every other quality
decision in the product is revisable; this one is not. Continuous archive is the only
mechanism that fully closes it, and it converts VAD tuning from an irreversible commitment
into just another reprocessable pass.

Against that: ~30 MB per wall-clock hour, ~88 GB per year of daily 8-hour shifts. Affordable
on a 512 GB flagship, not on the floor device, and it makes the gated-Opus storage argument
irrelevant since the archive dominates.

**Recommendation.** **Build it, default it off, offer it during M0 recording specifically.**
The M0 tape is the one recording session whose segmentation you will certainly want to redo —
VAD parameters are untuned at that point, and every boundary metric (AC-69, AC-71) is measured
against it. Turning it on for M0 and leaving it off by default afterwards captures nearly all
the value for one shift's worth of disk.

---

### Q15 — Where does the app's code live? · **CLOSED** · owner: product

**Answer: its own public repository**, `offline-radio-transcriber` (D27), migrated in September
2026 with full commit history via `git subtree split`. The channel-plan repo keeps the radio
data and will supply a versioned lexicon asset bundle; the coupling runs one way, through a
file. Kenwood's copyrighted PDFs stayed behind — the facts the rig module needs are recorded in
`docs/reference/th-d75a-cat.md` with attribution.

**Question.** Android project inside this repository, or its own?

**Why it matters.** It touches D11 (open source now, Play Store later) more than it touches
engineering. This repo is Python tooling for channel plans; the app is a Kotlin/Gradle project
with no shared toolchain, CI, test runner or release cadence. They are coupled in exactly one
direction and through exactly one thing: the lexicon asset bundle this repo's data produces
(technical design §9.6).

**Recommendation.** Separate repo for the app. This repo gains an asset-build target emitting a
versioned, checksummed bundle as a release artifact, which the app consumes through the normal
asset lifecycle it needs anyway (FR-LEX-2, FR-AST-1). One-way coupling through a file is the
cheapest kind, and it keeps both repos explicable to a stranger — which matters on the D11
path.

**Cheap to defer, expensive to reverse late.** Deciding after M2 means moving a working Gradle
build and its history.

---

### Q16 — Corpus and labelling protocol · **DRAFTED, not yet piloted** · owner: product + engineering

**Question.** What exactly gets labelled, by what rules?

**2026-09-07: a draft protocol answering every question below is written** —
[`docs/reference/labelling-protocol.md`](../docs/reference/labelling-protocol.md). This does
**not** close Q16: per this question's own recommendation, closing it requires labelling session
one under the draft, finding what's still ambiguous, and amending it — the pilot round — which
needs the Q2 recording to exist first. Treat the draft as ready to use the moment that recording
happens, not as a settled answer yet.

**Why it matters.** M0 is the blocking milestone, its dominant cost is hand-labelling, and the
plan currently specifies that cost in one line. Labelling without a written protocol produces a
corpus whose disagreements are invisible until they show up as unexplained accuracy variance —
and the fix is relabelling, which is the single most expensive rework available in this project.
M0.7 measures self-disagreement; nothing yet *reduces* it.

The questions a protocol has to answer, none of which are obvious at 11pm with headphones on:

- When does a transmission boundary fall — at carrier, at first phoneme, at squelch open?
- A station doubles with another. One transmission or two? Labelled how?
- A callsign is 80% audible. `uncertain`, or omitted? **This directly sets the recall ceiling
  every measurement is against.**
- Partial callsign heard ("...seven alpha bravo"). Labelled as what?
- Where does a thread end — and does a 20-minute gap on the same repeater continue it?
- Phonetic variants, spelled-out versus spoken callsigns, tactical callsigns, club stations
- Non-speech: DTMF, courtesy tones, data bursts, CW IDs — labelled, or noise?

**Recommendation.** Write it before recording session two. Session one doubles as the protocol's
pilot: label it, notice what was ambiguous, write the rules those ambiguities imply, then
relabel session one under the finished protocol. That costs one session's labelling and is the
cheapest possible insurance on the project's most expensive irreversible artifact.

---

### Q17 — May contributed audio ever be published? · **CLOSED** · owner: product

**Question.** The contribution channel (D25) accumulates recordings of third parties. Can any
of it ever be released as an open dataset?

**Why it mattered.** It is the irreversible half of R14. Publishing cannot be undone, the
people recorded never consented, and a research field with no public amateur-radio corpus
(§14A.3) makes the temptation real — this project could plausibly create the first one.

**Answer: never publish contributed audio. Derived artifacts only** (D31) — fine-tuned models,
aggregate statistics, lexicon data, and measurements. Recordings stay access-controlled.

This closes R14's irreversible half permanently and leaves the reversible half — custody of the
archive — as an ordinary operational responsibility. It also means FR-CON-7's preference for
contributing corrections and metadata *without* audio is now the main path rather than a
nicety, which conveniently matches GitHub's storage limits for the destination chosen.

---

### Q12 — Which reference-tier levers are worth their complexity · owner: engineering

**Question.** Ensemble fusion, n-best rescoring and speech enhancement are all specified.
Which survive contact with real data?

**Why it matters.** Each adds permanent complexity. The evidence differs sharply in quality:
ensemble is classical and reliable in direction but unquantified here; rescoring is published
at 5–25% relative but not on radio audio; enhancement is **genuinely contested** — CHiME-4
says >30% relative improvement, "When Denoising Hinders" says it can degrade zero-shot
Whisper.

**Recommendation.** M11 is sequenced last for exactly this reason, and AC-35 requires
per-lever reporting. **Adopt a stated kill rule now:** a lever that does not produce a
measurable improvement on the eval fold gets deleted, not disabled. A disabled lever costs
complexity forever; a deleted one costs nothing.

---

## 2. Technical unknowns

Tracked in `research/03-accuracy-lexicon-identity.md` §8. Restated here because they gate
milestones rather than decisions:

| # | Unknown | Gates |
|---|---|---|
| T1 | Speaker embedding separation on narrowband off-air audio | M6 |
| T2 | Phonetic-unit KWS accuracy on radio audio | M4 — **the core thesis** |
| T3 | Whether CB-Whisper's encoder-hidden-state approach ports to sherpa-onnx on Android | M4 |
| T4 | Hotword automaton cost at ~100 units on target hardware | M8 |
| T5 | **ONNX export of a fine-tuned Whisper for sherpa-onnx** | **M0a — gates D13, the biggest lever. Check this first; it is a half-day of work** |
| T8 | Whether ATC fine-tuning gains transfer to amateur radio audio | M0a. Strong structural analogy, zero direct evidence |
| T9 | End-to-end RTF for `large-v3-turbo` on a real Android app (vendor figures are component latencies) | M11, T3 tier boundary |
| T10 | Whether enhancement helps or hurts *per pass* on this audio | M11, FR-ENH-3 |
| T6 | Real per-transmission latency on a mid-tier phone | Tier boundaries, M10 |
| T7 | Whether a specific PD + USB-Audio-Class hub works with the reference device | M2 |

---

## 3. Closed

Answered in the requirements interview, September 2026. Recorded so the reasoning is not
lost.

| # | Question | Answer | Consequence |
|---|---|---|---|
| C1 | Model topology — one multimodal model or a dedicated ASR stack? | Highest accuracy available; lexicon run against the audio; summary local and after the fact | D3, D4, D5, D10. Gemma 3n single-model rejected |
| C2 | Latency target — streaming, per-transmission, or batch? | Both — live streaming for experience, plus complete-transmission accuracy with reasoning over the whole conversation | D6. Pass A + Pass B, and FR-SPK threading |
| C3 | Does the deferred desktop `large-v3` pass survive? | On-device only, but architect reprocessing as an extensibility point | D2, §11 |
| C4 | Distribution intent? | Open source self-build now, Play Store eventually | D11, NFR-6b |
| C5 | Target device? | Must run on anything | D8, §6 tiers |
| C6 | How to bound the biasing lexicon? | Worldwide traffic; location intelligence for patterns but very flexible; most robust and flexible solution | D12. Grammar + ITU prefixes as the structural filter, priors that rank but never exclude (FR-LEX-8, FR-LEX-9) |
| C7 | How to present inferred attribution? | Show with confidence | D7, FR-UI-4, four-state model |
| C8 | v1 scope? | Audio-only first; flexible interface pulling frequency and other data from a connected radio; start with the Kenwood; extensible to any radio via modules | D9, §9 three-level extension design |
| C9 | Is a local LLM required? | No — "as long as we have fully offline speech transcription with high accuracy that is fine" | D10. FR-DIG-2 deterministic digest is the requirement; FR-DIG-3 LLM is tier-gated and optional |
| C15 | Which device is the reference? | OPPO Find X9 Ultra | D19, §10.7–10.8. Snapdragon 8 Elite Gen 5 — the exact chipset Qualcomm benchmarked `large-v3-turbo` on, so T3's headline number is measured not extrapolated. 27 Wh battery makes NFR-3 trivial. But ColorOS makes R5 the top risk |
| C16 | Can a LoRA fine-tune be exported to sherpa-onnx? | Yes, with a constraint | D20, §14A.1. `export-onnx.py --model` takes a fixed enum — but that enum already includes `distil-*` variants, so it exports non-OpenAI weights routinely, and `merge_and_unload()` makes a LoRA structurally identical to its base. Fine-tune a base already in the enum |
| C11 | Architecture constraints, or should I choose? | Choose | D15, §17. Kotlin/Compose/Room+FTS5/Coroutines/Hilt/WorkManager, with `:lexicon` and `:eval` deliberately Android-free so accuracy work runs on a desktop JVM |
| C12 | Who is building this, at what pace? | Solo, heavily AI-assisted | D18. §15 front-loads interfaces, testability and decision gates — what is expensive to retrofit and what AI assistance does not make safer. M2 grew to include the runtime skeleton for this reason |
| C13 | Where does operator location come from? | GPS when available, manual fallback | D17, FR-LEX-22..24. Rig-reported position placed **first** because the TH-D75A has GPS for APRS, giving portable accuracy with no Android location permission at all |
| C14 | What gives when the device cannot keep up? | Never drop audio | D16, FR-RUN-1..6. Five-level shed order ending in deferral, not loss. Combined with cross-tier reprocessing, overload yields a provisional record — the same safety property as tier degradation |
| C10 | If the run-on-anything requirement is dropped, does it get materially better? | Yes. "A modern device must have a highly accurate and world class experience with things still working on lower tier devices" | D3, D8 rewritten; D13 and D14 added. §6 tier model inverted — tiers now defined *downward* from a reference experience. NFR-1 becomes a per-tier table. Cross-tier reprocessing (FR-REP-8..11) is what reconciles the two halves: a low tier yields a provisional record, not a permanently degraded one |
