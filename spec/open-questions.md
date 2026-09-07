# Open Questions

**Decision register · functional spec draft 1 · September 2026**

Questions that remain open after the initial requirements interview. Each carries a
recommendation, so none of them blocks progress by default — but the ones marked
**blocking** should be closed before technical design begins.

**Status at draft 2:** Q1 largely closed by documentation already in this repo. **Q2
(evaluation set), Q3 (reference device) and Q10 (fine-tuning data split) are blocking.**
Q10–Q12 are new, arising from the flagship-first reframing.

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

### Q3 — Reference device · **BLOCKING** · owner: product

**Question.** Which specific phone are NFR-2 and NFR-3 measured against?

**Why it matters.** "Must run on anything" (D8) sets the floor via tiers, but latency and
endurance targets need a named device or they are unfalsifiable. Also determines what
hardware to buy for testing.

**Recommendation.** Two devices:
- **Reference (targets measured here):** a Pixel 7a or 8a. Clean background execution, mid-tier
  silicon, ~$150–250 used, and representative of T2.
- **Floor (AC-26 measured here):** any 2 GB Android 8+ device, ~$40 used.

Deliberately *not* a flagship. Targets set on a Pixel 9 Pro would be unreachable on the
hardware most people would actually dedicate to this.

---

### Q4 — Frequency granularity and channel identity · owner: product

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

### Q5 — Audio retention default versus reprocessing horizon · owner: product

**Question.** FR-STO-3 defaults audio retention to 30 days. FR-REP-4 notes that retention
shorter than the reprocessing horizon defeats reprocessing. What is the intended horizon?

**Why it matters.** Storage estimate: gated Opus at 15% duty is roughly 1.1 MB per hour of
wall clock, so ~26 MB/day, ~800 MB/month, ~9.5 GB/year. Indefinite audio retention is
genuinely affordable on a modern phone.

**Recommendation.** Default audio retention to **indefinite** with a storage-pressure-driven
prune, rather than a time limit. The reprocessing value is high and the storage cost is low.
Make time-based retention available for users who want it, with the FR-REP-4 warning.

---

### Q6 — Multiple radios simultaneously · owner: product

**Question.** Should v1 support capturing from the TH-D75A and the SDS150 at the same time,
on separate audio inputs?

**Why it matters.** The brief describes two deployment *modes*, which reads as
alternatives. But the phone has one USB port and a hub could carry two audio adapters. The
data model would need a per-session source identifier.

**Recommendation.** **No for v1**, but add a `sourceId` to Session and Transmission now so
it is not a migration later. Two concurrent capture pipelines double the compute at exactly
the point where tier headroom matters, and the value is unproven.

---

### Q7 — What "digest" actually contains · owner: product

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

### Q8 — Correction UX depth · owner: design

**Question.** FR-UI-6 says "one-tap correction of any attribution". Correcting *to what*?
Free text? A picker over candidates? A search over the lexicon?

**Why it matters.** P3 says corrections should propagate and improve future results. A
free-text correction that does not match a lexicon entry cannot feed the priors.

**Recommendation.** Tiered: pick from the ranked candidate list (fastest, feeds priors
directly), else search the lexicon, else free text marked as unverified. Only the first two
feed the recency prior.

---

### Q9 — Handling nets and controlled sessions · owner: product

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

### Q10 — Fine-tuning data volume and licensing · **BLOCKING M0a** · owner: product

**Question.** How much labelled amateur-radio audio for the training fold, and can any of it
be shared if the project is open-sourced?

**Why it matters.** D13 makes fine-tuning first-class. The ATC precedent says 55 clips
produced a 54.8% relative WER reduction, so the volume needed is small — but the M0 tape
must now be **split into train and eval folds before any measurement happens**, or the
evaluation is contaminated. That is a decision to make before labelling, not after.

Licensing matters separately: a fine-tuned model derived from your own recordings is yours
to publish, but the recordings themselves capture identifiable third parties, and
redistribution norms for off-air amateur audio are not obvious.

**Recommendation.** Target **2 h train / 1 h eval** minimum, folded by session so no
conversation spans both. Publish the *model weights* if the licence of the base model allows;
publish the *audio* only with a considered decision. Keep the eval fold untouched until M11.

---

### Q11 — NPU vendor scope · owner: engineering

**Question.** Which accelerators does T3 support at v1 — Qualcomm only, or Qualcomm plus
Google Tensor plus MediaTek?

**Why it matters.** Qualcomm has published, per-device, per-chipset `large-v3-turbo`
benchmarks across 40+ combinations. Tensor and MediaTek are supported by LiteRT but with far
less published evidence for this specific model. Supporting three means three toolchains or
accepting LiteRT's abstraction and some performance loss (FR-ACC-6).

**Recommendation.** **Qualcomm first, via whichever path the reference device uses**, and
treat T3 as unavailable elsewhere until measured. FR-ACC-4 already requires graceful absence,
so this costs nothing but a smaller T3 population. Revisit once the harness can measure a
second vendor cheaply.

**Note this interacts with Q3.** If the reference device is a Pixel, the accelerator is
Tensor, not Snapdragon — and the published evidence base is thinner. That may be a reason to
choose a Snapdragon reference device despite Pixel's better background-execution behaviour,
or a reason to keep T3 CPU-only at v1.

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
| C11 | Architecture constraints, or should I choose? | Choose | D15, §17. Kotlin/Compose/Room+FTS5/Coroutines/Hilt/WorkManager, with `:lexicon` and `:eval` deliberately Android-free so accuracy work runs on a desktop JVM |
| C12 | Who is building this, at what pace? | Solo, heavily AI-assisted | D18. §15 front-loads interfaces, testability and decision gates — what is expensive to retrofit and what AI assistance does not make safer. M2 grew to include the runtime skeleton for this reason |
| C13 | Where does operator location come from? | GPS when available, manual fallback | D17, FR-LEX-22..24. Rig-reported position placed **first** because the TH-D75A has GPS for APRS, giving portable accuracy with no Android location permission at all |
| C14 | What gives when the device cannot keep up? | Never drop audio | D16, FR-RUN-1..6. Five-level shed order ending in deferral, not loss. Combined with cross-tier reprocessing, overload yields a provisional record — the same safety property as tier degradation |
| C10 | If the run-on-anything requirement is dropped, does it get materially better? | Yes. "A modern device must have a highly accurate and world class experience with things still working on lower tier devices" | D3, D8 rewritten; D13 and D14 added. §6 tier model inverted — tiers now defined *downward* from a reference experience. NFR-1 becomes a per-tier table. Cross-tier reprocessing (FR-REP-8..11) is what reconciles the two halves: a low tier yields a provisional record, not a permanently degraded one |
