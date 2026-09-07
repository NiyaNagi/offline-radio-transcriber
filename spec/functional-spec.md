# Offline Radio Transcriber — Functional Specification

**Draft 3.2 · September 2026 · Ready for implementation**

*Draft 3.2 is an adversarial audit pass, run against this spec and against the technical
design and implementation plan it produced. Where draft 3.1 checked for **missing** coverage,
this one checked for **wrong** coverage: statements that contradict each other, thresholds
that cannot both hold, and requirements whose acceptance criterion tests something else.
Fourteen defects were fixed here and the full record — including the findings against the
downstream documents — is in [`audit-2026-09-06.md`](audit-2026-09-06.md). Three of them were
substantive: the transmission state machine and the data model disagreed about what states
exist (§8); the tier-detection trigger let a device qualify for T3 without the RAM to hold
T3's working set (§6.2); and **the eval-fold discipline sealed away the only noise tape,
making the project's most important test (AC-6) unrunnable until M11** (§14A.2). Two new
constraints and three new risks were added for issues neither document had noticed at all —
segmentation permanence (§7.2 CON-SEG-1), lossy retention versus the reprocessing horizon
(§7.7 CON-STO-1), and R10–R12. Re-verified after the changes: **103 contiguous acceptance
criteria**, zero duplicates, zero gaps, zero dangling references across all five spec
documents.*

*Draft 3.1 was an audit pass. A mechanical check for dangling references, duplicate IDs and
requirement-to-test coverage found seven requirement groups and two non-functional targets
with **no acceptance criteria at all** — including the digest, which is goal G1, and the
latency budget in NFR-2. Twenty-seven criteria were added to close them (AC-67..93).
Verified: 206 requirements, 93 contiguous acceptance criteria, zero dangling references, zero
duplicate IDs, every requirement group covered.*

*Draft 2 rewrote the design around a flagship-first ceiling: tiers defined downward from a
reference experience (§6), per-tier accuracy targets (§10.1), fine-tuning and optional NPU
acceleration as first-class (D13, D14), and cross-tier reprocessing (§11.1) making a weak
device produce a provisional record rather than a degraded one.*

*Draft 3 closes the gaps that would have blocked a technical design. Added: the runtime
architecture — concurrency, durable queue, backpressure, transmission lifecycle, audio
interruption, clock model (§7.14); asset and schema management (§7.15); platform integration
and permissions (§7.16); testability hooks (§7.17); accessibility and language (§7.18);
**confidence calibration** (FR-LEX-17..21), without which every threshold in the trust model
was undefined; operator location sourcing (FR-LEX-22..24); a project risk register (§14A);
and an architecture baseline (§17).*

Downstream documents this feeds:

- **Technical design spec** — sections 5–11. *Exists: [`technical-design.md`](technical-design.md)*
- **Implementation plan** — section 15. *Exists: [`implementation-plan.md`](implementation-plan.md)*
- **Test plan** — section 14, plus the non-functional budgets in section 10
- **Visual / UX design guide** — section 13, plus the domain model in section 4

Requirement IDs are stable. `FR-` functional, `NFR-` non-functional, `CON-` constraint.
Priority: **M** must (v1 ships without it only if descoped explicitly), **S** should,
**C** could, **W** won't (this release).

---

## 1. Purpose and scope

### 1.1 What this is

An Android application that captures audio from a connected radio, transcribes it entirely
offline, resolves callsigns by matching a lexicon against the audio signal, groups
transmissions into conversations with speaker-based attribution, and presents a searchable
log and a readable digest — on the same device that did the capture.

### 1.2 The problem it solves

Monitoring amateur and scanner traffic is a listening activity that produces no record. A
person who steps away, sleeps, or works loses everything that happened. Existing options
are cloud transcription (unacceptable — offline is a hard requirement), manual logging
(does not scale), or dedicated hardware plus a companion app (three subsystems to build and
keep in sync).

### 1.3 What makes it hard

Two things, and only two things:

1. **Off-air voice is bad audio.** Narrowband, noisy, compressed, often weak. General ASR
   degrades sharply and — worse — *hallucinates confidently* on squelch noise.
2. **The payload is rare words.** A transcript that gets the prose right and the callsign
   wrong is worthless. Callsigns are out-of-vocabulary for every general ASR model.

The entire architecture is organised around the second problem, because it is the one that
determines whether the product is useful.

### 1.4 In scope for v1

- Audio capture from a wired input, running unattended for 8+ hours
- Offline transcription with hallucination suppression, using a **domain fine-tuned** model
- Callsign resolution using audio-level lexicon matching
- Speaker clustering and conversation threading with graded attribution confidence
- A modular radio interface, with the Kenwood TH-D75A as the first module
- Searchable local log and digest
- Export to QRZ and POTA workflows
- **Cross-tier reprocessing** — a record captured on a weak device improves when reprocessed
  on a strong one
- **Reference-tier acceleration** — NPU execution, ensemble fusion and n-best rescoring on
  capable hardware, each kept only if it measures

### 1.5 Out of scope for v1

| Item | Why | Revisit |
|---|---|---|
| iOS | Background recording is technically permitted but commercially fragile under Guideline 2.5.4 | If a native-app distribution strategy changes |
| Cloud anything | Hard product constraint | Never |
| Uniden SDS150 rig module | Deferred by product owner; audio-only covers it | v2 |
| Digital voice decode (DMR, P25, D-STAR, Fusion) | Requires demodulation, not transcription | v3+ |
| **Morse / CW decoding** | A different signal-processing problem from speech — tone detection, adaptive speed tracking and timing analysis, not ASR. It shares the app's capture, storage, threading and reader surfaces but none of its transcription stack | **v2.** A natural fit for this product: CW is the other half of amateur voice traffic, callsigns are its dominant payload, and the same callsign grammar and priors (FR-LEX-7..9) apply to a decoded CW string unchanged. Nothing in v1 forecloses it — a decoded CW transmission is a Transmission with a different pass that produced its transcript |
| Transmit / logging back to the radio | Different product | Never |
| Multi-device sync | On-device only by decision | v3 |
| **Live alerts** — notify on a watched callsign, a frequency waking up, or a keyword | Deferred by product owner. The product is retrospective in v1: it records, you read later | **v2.** Nothing in v1 may foreclose it — Pass D already produces the callsign a watchlist would match on, so this is a matching rule and a notification, not an architecture change |
| **Scanner channel identity** — channel names in place of frequencies, threading on channel | Deferred by product owner with the SDS150 rig module. Audio-only scanner capture works in v1, logged by frequency | **v2** with the SDS150 module. `channelName` stays in the Transmission entity (Q4) so this is not a migration later |
| On-device LLM digest **as a required feature** | Optional, not required for the product to work. FR-DIG-3 is Must **only where the tier and the user's setting both enable it**, and the product ships complete with it absent — see FR-DIG-3a | v2 for the default-on case |

---

## 2. Users and goals

**Primary user: the operator.** A licensed amateur running a TH-D75A and a scanner, who
wants a record of what happened while not listening.

| # | Goal | Success looks like |
|---|---|---|
| G1 | Know what happened on the repeater overnight | Opens the app, reads a digest, understands the traffic in under two minutes |
| G2 | Find a specific conversation later | Searches a callsign or a phrase, finds it, hears the audio |
| G3 | Capture callsigns for QRZ / POTA follow-up | Exports a list of stations heard with frequency and timestamp, confident it is correct |
| G4 | Trust the record | Can always tell what was *heard* versus what was *inferred*, and correct it |
| G5 | Set it up once | Plugs in, taps start, walks away, and it is still running eight hours later |

**G4 is a first-class requirement, not a nicety.** A log that silently guesses is worse than
one that admits uncertainty, because it will be used to send QSL cards to people who were
never there.

---

## 3. Decisions of record

Settled with the product owner. Changing any of these invalidates parts of this spec.

| # | Decision | Rationale |
|---|---|---|
| D1 | **Android only.** Minimum API 26, target current. | The `microphone` foreground service type has no time limit; only `dataSync` and `mediaProcessing` are capped at 6 h/24 h. iOS is a tolerated edge case |
| D2 | **On-device only.** No network required for any core function. | Product constraint. Reprocessing is an extensibility point, not a dependency |
| D3 | **Design for the best available hardware first.** The reference experience is defined on a modern flagship and is not capped by what a weak device can do. | Explicit product-owner direction. Treating the floor as a design constraint was silently acting as a ceiling — see `research/04` |
| D4 | **The lexicon runs against the audio**, not only the transcribed text. | CB-Whisper measures entity recall 79.9% → 96.9%; up to +80pp on hot-word subsets |
| D5 | **Callsign extraction is deterministic and auditable.** No LLM in that path, ever. | An LLM that invents a plausible callsign is the worst possible failure |
| D6 | **Live streaming display plus complete-transmission accuracy.** | Product owner wants the live feel and the accurate record. They are complementary passes |
| D7 | **Attribution confidence is always visible**: confirmed / inferred / unknown. | Goal G4 |
| D8 | **Lower-tier devices remain fully functional, never a design constraint on the ceiling.** Four tiers, one data shape, and **any record is reprocessable at a higher tier later**. | Explicit product-owner direction. Because every pass is a pure function of retained audio, tier is a property of *processing*, not of the record |
| D9 | **Audio-only first; modular rig interface extensible to any radio.** TH-D75A first. | Explicit product-owner direction |
| D10 | **A local LLM is optional and post-hoc.** Digest only. | Product owner: "does not need to be a local LLM, as long as we have fully offline speech transcription with high accuracy" |
| D11 | **Open source self-build now; Play Store later.** | No decision may foreclose the store path |
| D12 | **Worldwide callsign support via grammar plus priors**, never a bounded list. | Product owner: "we could hear calls all over the world… think about the most robust and flexible solution" |
| D13 | **Domain fine-tuning is a first-class part of the product**, not a research aside. | ATC literature: 55.2% → 6.8% WER, and 13.7% from **55 hand-transcribed clips**. It is the largest single lever available and it lifts *every* tier |
| D14 | **NPU acceleration is an optional accelerator behind a stable interface**, used to run a *better model*, never to run the same model faster. | Qualcomm publishes `large-v3-turbo` at ~22x RTF on Snapdragon 8 Elite Gen 5. A CPU fallback is required regardless |
| D15 | **Stack: Kotlin, Compose, Room/SQLite+FTS5, Coroutines/Flow, Hilt, WorkManager.** ASR and rig layers sit behind plain interfaces. | Product owner delegated the choice. Idiomatic, well-documented, and the interfaces are what keep sherpa-onnx and each radio replaceable |
| D16 | **Audio is never dropped due to processing pressure.** Overload sheds passes and defers to a durable queue. | Product owner direction. Combined with cross-tier reprocessing this makes overload produce a *provisional* record, never a lost one — the same safety property as tier degradation |
| D17 | **Location: rig-reported, then device GPS, then manual grid.** Permission optional. | Product owner chose GPS-with-fallback. Rig-reported is placed first because the TH-D75A has GPS for APRS, giving portable accuracy with **no Android location permission at all** |
| D18 | **Solo build, heavily AI-assisted.** | Product owner. Shapes §15: front-load interfaces and testability, treat implementation throughput as high, put the risk on review gates rather than on sequencing |
| D19 | **Reference device: OPPO Find X9 Ultra.** Snapdragon 8 Elite Gen 5, 12–16 GB, 7050 mAh, Android 16 / ColorOS 16. | Product owner. It is the *exact* chipset Qualcomm published `large-v3-turbo` NPU benchmarks for, so T3's headline number is measured rather than extrapolated — **and it runs ColorOS, one of the worst offenders for background-killing.** Best case for accuracy, worst case for R5. See §10.7 |
| D29 | **Station knowledge accumulates across sessions**, split into deterministic *facts* and optional, clearly-marked LLM *topic summaries*, linked into a graph over sessions, stations and threads. No inference about persons. | Product owner: "allow cross digest to be connected together… so we can continue to get interesting things revealed the more we listen." The facts half is free and safe; the split (FR-DIG-7..14) is what keeps the other half from becoming a dossier. See R16 |
| D30 | **Net detection ships in v1**, advisory rather than structural. | Product owner, reversing Q9's deferral. A check-in roll call is the richest source of clearly-spoken callsigns the product will encounter, and the voice library makes the control operator easy to spot. See FR-SPK-27..30 |
| D31 | **Contributed audio is never published.** Derived artifacts — models, aggregate statistics, lexicon data — may be. | Product owner, closing Q17. Retires R14's irreversible half permanently: recordings of third parties stay access-controlled, and nothing published can be traced back to a voice |
| D32 | **Correction is tiered**: pick from the ranked candidates, else search the lexicon, else free text marked unverified. Only the first two feed the priors. | Product owner, closing Q8. Also the natural home for manual voice binding (FR-SPK-23) |
| D28 | **A persistent voice library**: enrolled voiceprints bound to Stations, surviving sessions, so a regular is named by voice before they identify. Cross-session matches are `INFERRED`, never `CONFIRMED`. | Product owner. FR-SPK-1..10 already bind callsigns to voices *within* a session; this makes it durable, and it compounds — the longer the app runs, the more traffic it can name. See FR-SPK-11..22 |
| D26 | **Retention is a storage budget, not a time limit** — independent budgets for gated audio and continuous archive, "unlimited" allowed, and bulk export-and-prune by date range when a budget is reached. | Product owner. Disk is what the user actually cares about; a time limit is a proxy for it that is simultaneously too aggressive on a large phone and too lax on a small one. Closes Q5 and Q13 |
| D27 | **The project lives in its own public repository**, `offline-radio-transcriber`, with the channel-plan tooling repo supplying a versioned lexicon asset bundle. | Product owner. Different toolchain, cadence and audience; one-way coupling through a file. Closes Q15 |
| D21 | **Public corpora first, synthesis second, hand-labelling last.** The training corpus is assembled from free public data; synthetic callsign audio fills the callsign gap; hand-labelled real amateur audio is reduced to a small **validation** set. | Product owner: "use every data source possible." A search established that 176 h of real off-air ham HF audio, 19,000 h of degraded analog comms with diarization labels, and free ATC corpora all exist — while **no public amateur callsign corpus exists at all**. That inverts M0: the tape is no longer the training set, it is the reality check. See §14A.3 |
| D22 | **Synthetic callsign audio = local neural TTS + real-speech splicing, degraded through a channel learned from Paderborn's parallel data.** | Product owner. Splicing real ISOLET/ATC letter and digit audio into callsign sequences gives real human phonetic units; learning the channel from 176 h of the same speech clean *and* received means the degradation is measured rather than guessed |
| D23 | **The TH-D75A is polled on both bands, and transmissions are attributed by squelch.** | Product owner monitors both bands. The CAT interface is band-scoped (`FQ A`, `BY A`) and the audio is mixed, so squelch state is what disambiguates which band a transmission came from. See §9.4 |
| D24 | **Continuous-archive capture is a first-class, always-available option.** | Product owner. Segmentation is otherwise permanent (CON-SEG-1); this is the only mechanism that makes it reprocessable |
| D25 | **Corpus contribution is opt-in at onboarding, then automatic.** This **amends FR-OBS-5 and NFR-6**, which forbade automatic transmission in any build. | Product owner direction, taken with the conflict stated. The contribution channel is strictly separate from the capture and processing paths, which remain fully offline, and the app is fully functional with contribution declined. See FR-CON-1..8 and R13 |
| D20 | **Fine-tune a base model that is already in sherpa-onnx's export list.** | Removes R1 almost entirely. `export-onnx.py --model` accepts a fixed enum, but that enum already includes every `distil-*` variant, and a LoRA merged with `merge_and_unload()` is structurally identical to its base — so the existing export graph applies unchanged |

---

## 4. Domain model and glossary

These terms are used precisely throughout, and should be used in the UI too.

| Term | Definition |
|---|---|
| **Transmission** | One continuous keying of a transmitter, delimited by VAD. The atomic unit of the system. Has audio, timestamps, a frequency, zero or more transcripts, zero or one attributed station |
| **Over** | Colloquial synonym for Transmission. Use "transmission" in code, "over" is acceptable in UI copy |
| **Session** | One continuous capture run, from tap-start to tap-stop. Groups transmissions and carries the capture configuration in force |
| **Thread** | A set of transmissions inferred to belong to one conversation — a QSO, a net, or a period of scanner activity on one channel. Bounded by frequency continuity and inter-transmission gap |
| **Station** | A distinct transmitting entity. Has zero or one resolved Callsign and zero or one Voiceprint |
| **Callsign** | A parsed, structurally valid callsign string with a confidence and a provenance |
| **Voiceprint** | A speaker embedding cluster. The mechanism by which unidentified transmissions acquire a Station |
| **Attribution** | The binding of a Transmission to a Station, with a graded confidence state |
| **Lexicon** | The union of all offline reference data used to resolve entities: ITU prefix table, FCC ULS, POTA parks, WWARA repeaters, SDS150 favorites, band plans |
| **Phonetic lattice** | A confidence-weighted sequence of candidate phonetic units derived acoustically from a transmission |
| **Rig** | A connected radio. Provides state (frequency, mode, signal) through a Rig Module |
| **Rig Module** | A pluggable adapter implementing the radio interface contract for a specific radio or protocol family |
| **Pass** | One processing stage over a transmission's audio. Passes are independently re-runnable |
| **Digest** | A generated human-readable summary over a time window |

### 4.1 Attribution confidence states

Exactly four, and they are a closed set:

| State | Meaning | Display |
|---|---|---|
| `CONFIRMED` | A callsign was heard and resolved within *this* transmission above threshold | Solid marker, callsign shown plainly |
| `INFERRED` | No callsign in this transmission; attributed by voiceprint cluster match | Hollow marker, callsign shown with score and the source transmission linked |
| `AMBIGUOUS` | Multiple candidates survived resolution with insufficient separation | Warning marker, top candidates shown, tappable to choose |
| `UNKNOWN` | No callsign heard and no cluster match | Neutral marker, no callsign shown |

`CORRECTED` is not a fifth state — it is a flag on any of the above, recording that a human
overrode the machine.

---

## 5. System overview

### 5.1 Processing pipeline

```
                    ┌──────────────────────────────────────────┐
   RADIO ──audio──> │ FR-CAP  Capture                          │
     │              │  16 kHz mono, continuous ring buffer     │
     │              │  with 1.0 s pre-roll                     │
     │              └────────────────┬─────────────────────────┘
     │                               │
     │              ┌────────────────▼─────────────────────────┐
     │              │ FR-SEG  Segmentation (Silero VAD)        │
     │              │  emits Transmission boundaries           │
     │              └────────────────┬─────────────────────────┘
     │                               │
  CAT/serial                         ├──────────────────┐
     │                               │                  │
     ▼                    ┌──────────▼─────────┐   ┌────▼──────────────┐
┌─────────────┐           │ PASS A  Streaming  │   │ retained segment  │
│ FR-RIG      │           │  Zipformer +       │   │ audio (Opus)      │
│ Rig Module  │──freq──>  │  hotword biasing   │   └────┬──────────────┘
│  TH-D75A    │           │  -> live partials  │        │
│  null/manual│           └────────────────────┘        │
└─────────────┘                                         │
                          ┌─────────────────────────────▼────────────┐
                          │ FR-ENH  optional enhancement, PER PASS   │
                          │  GTCRN. default off, evidence-gated      │
                          └─────────────────┬────────────────────────┘
                                            │
                          ┌─────────────────▼────────────────────────┐
                          │ PASS B  Offline ASR on complete segment  │
                          │  fine-tuned model, best the tier allows  │
                          │  T3: large-v3-turbo on NPU (FR-ACC)      │
                          │  -> n-best + no_speech_prob              │
                          └─────────────────┬────────────────────────┘
                                            │
                          ┌─────────────────▼────────────────────────┐
                          │ FUSE  (T3)  ensemble + n-best rescoring  │
                          │  Pass A transducer x Pass B enc-dec      │
                          │  ROVER-style vote; LLM reranks CLOSED    │
                          │  candidate set only, acoustic in scoring │
                          └─────────────────┬────────────────────────┘
                                            │
                          ┌─────────────────▼────────────────────────┐
                          │ PASS C  Phonetic unit spotting on AUDIO  │
                          │  ~36 NATO units + digits, acoustic KWS   │
                          │  -> confidence-weighted phonetic lattice │
                          └─────────────────┬────────────────────────┘
                                            │
                          ┌─────────────────▼────────────────────────┐
                          │ PASS D  Callsign resolution              │
                          │  grammar parse -> ITU prefix validate    │
                          │  -> rank by priors -> ranked candidates  │
                          └─────────────────┬────────────────────────┘
                                            │
                          ┌─────────────────▼────────────────────────┐
                          │ PASS E  Identity & threading             │
                          │  speaker embedding -> cluster            │
                          │  -> thread -> back-propagate callsign    │
                          └─────────────────┬────────────────────────┘
                                            │
                          ┌─────────────────▼────────────────────────┐
                          │ FR-STO  Persist (SQLite + FTS5)          │
                          └─────────────────┬────────────────────────┘
                                            │
                     ┌──────────────────────┴───────────────┐
                     ▼                                      ▼
            ┌──────────────────┐                 ┌────────────────────┐
            │ FR-UI  Reader    │                 │ PASS F  Digest     │
            │  live + search   │                 │  idle-time only    │
            └──────────────────┘                 └────────────────────┘
```

### 5.2 The two principles that shape everything

**Principle 1 — audio is the source of truth, and it is retained.**
Every pass operates on retained audio. No pass consumes only the output of another pass
where it could consume the audio. This is what makes D4 possible and what makes
reprocessing (section 11) a configuration change rather than a rewrite.

**Principle 2 — passes are independent and re-runnable.**
Every pass is a pure function of (audio, lexicon snapshot, model version, config) → result.
Results carry the versions that produced them. Any pass can be re-run over historical data
without re-running the others.

---

## 6. Capability tiers

### 6.1 The inversion

Tiers are defined **downward from the reference experience**, not upward from a lowest
common denominator. T3 is the product; T0–T2 are documented reductions of it.

This is not a wording change. Under the previous framing, a capability was included only if
it could be made to work everywhere, which meant the best hardware ran a design shaped by
the worst. Under this framing, the reference experience is designed without that constraint
and each lower tier removes the most expensive thing it cannot afford.

**The property that makes this safe** — and it is the reason the requirement is coherent
rather than a compromise:

> Every pass is a pure function of retained audio (§5.2, Principle 1). Therefore **tier is a
> property of processing, not of the record.** A transmission captured at T0 on a cheap
> phone can be reprocessed at T3 on a flagship later, in the same app, producing exactly the
> record it would have produced if captured there.

A low tier is not a permanently degraded record. It is a **provisional** one.

### 6.2 The tiers

Detected at runtime from measured throughput, available RAM and accelerator availability —
**never from a device allowlist.**

| Tier | Trigger | What it adds over the tier below | Resident budget |
|---|---|---|---|
| **T3 — Reference** | ≥6 GB app-available RAM **and** (a supported NPU (FR-ACC) **or** measured Pass B RTF ≥ 8x) | `large-v3-turbo` on the accelerator · ensemble fusion of Pass A and Pass B · LLM n-best rescoring · LLM prose digest · full-size active lexicon slice | ~2.5 GB |
| **T2 — Full** | ≥2.5 GB app-available RAM **and** measured Pass B RTF ≥ 2.0x | Pass A live streaming · Pass E speaker identity and threading · decode-time hotword biasing | ~1.4 GB |
| **T1 — Standard** | ≥1.5 GB app-available RAM **and** measured Pass B RTF ≥ 1.0x | Pass C acoustic phonetic spotting · a larger Pass B model | ~800 MB |
| **T0 — Minimal** | Any device that runs the app | SEG · Pass B (Moonshine tiny) · Pass D from a text-derived lattice · storage · reader | ~300 MB |

**The RAM condition is conjunctive at every tier, and the parenthesisation above is a fix, not
a restatement.** As drafted, T3's `A and B, or C` read as `(A and B) or C`, which let a 3 GB
phone with a fast CPU qualify for a tier whose working set is 2.5 GB — it would be admitted
and then thrash or be killed. Throughput and residency are independent constraints and
**both** must hold: RTF says the device can finish the work, resident budget says it can hold
the models while doing it.

**FR-TIER-8 (M)** — The resident budget column is a **requirement on the tier, not an
estimate**. The sum of concurrently loaded models at a tier SHALL fit within it, and a tier
whose model set exceeds its budget on the detector's measurement SHALL not be entered. Model
residency and eviction are the technical design's to specify; the ceiling is this spec's.

**The fine-tuned model (D13) is available at every tier**, including T0. It is a model file,
not a capability. This is why T0 under this spec is meaningfully better than T0 under the
previous one.

### 6.3 Requirements

**FR-TIER-1 (M)** — Detect capability tier at first run, on every model-configuration change,
and when accelerator availability changes, using **measured throughput** rather than device
identification.

**FR-TIER-2 (M)** — All tiers SHALL produce records with an **identical schema**. A tier
difference SHALL manifest as absent optional fields and lower confidence, never as a
different record shape.

**FR-TIER-3 (M)** — The user SHALL be able to override the detected tier downward (to save
battery or heat) and upward (accepting the risk), with the override visible in settings.

**FR-TIER-4 (M)** — The system SHALL degrade tier automatically and reversibly under
sustained thermal pressure or when the processing backlog exceeds a configured depth, SHALL
surface that it has done so, and SHALL **mark affected records as candidates for reprocessing**
(FR-REP-8).

**FR-TIER-5 (M)** — Every record SHALL store the tier, model set, accelerator and pass set
that produced it, so a later reprocess knows what is worth redoing.

**FR-TIER-6 (M)** — Where the reference tier is unavailable, the UI SHALL state which
capabilities are inactive and why, in terms of the device rather than in terms of internal
tier numbers. A user on a mid-tier phone should understand what they are not getting.

**FR-TIER-7 (S)** — Capture SHALL be permitted to run at a lower tier than processing. On a
device that can capture but not keep up, the system MAY capture at full fidelity and defer
higher passes to a later reprocess rather than degrading the transcription permanently.

---

## 7. Functional requirements

### 7.1 FR-CAP · Audio capture

**FR-CAP-1 (M)** — Capture 16 kHz mono PCM from a selectable input device.

**FR-CAP-2 (M)** — Enumerate available inputs via `AudioManager.getDevices(GET_DEVICES_INPUTS)`
and allow explicit selection, filtering for `TYPE_USB_DEVICE`, `TYPE_WIRED_HEADSET`,
`TYPE_BUILTIN_MIC`.

**FR-CAP-2a (M)** — Capture SHALL request 16 kHz mono from the input device, and where the
device does not offer it — most USB Audio Class adapters expose 44.1 or 48 kHz only — SHALL
capture at the device's native rate and resample to 16 kHz in the app. The resampler SHALL be
deterministic (FR-TST-4) and its identity SHALL be recorded on the session, because it is part
of the signal chain every accuracy number is measured through.

**FR-CAP-3 (M)** — After binding, verify the actual route with `getRoutedDevice()` and
**halt with a visible error if it does not match the selection.** A silent fallback to the
built-in mic records the room instead of the radio and is the highest-consequence silent
failure in the system.

**FR-CAP-3a (M)** — The halt condition is **route ≠ selection**, not *route = built-in mic*.
The built-in mic is a legitimate selection (FR-CAP-2 lists it, and it is how the app is tested
without a radio attached); what is never acceptable is landing on it *without having been
chosen*. Where the built-in mic is the deliberate selection, the UI SHALL say so persistently,
because a session recorded from the room and one recorded from the radio must never be
confusable after the fact.

**FR-CAP-4 (M)** — Maintain a continuous ring buffer with **≥1.0 s of pre-roll**, so a
segment includes audio from before VAD triggered. Callsigns are frequently spoken in the
first syllable of a transmission; without pre-roll they are clipped.

**FR-CAP-5 (M)** — Detect input device disconnection and surface it immediately. Attempt
reconnection with backoff; never silently continue capturing nothing.

**FR-CAP-6 (M)** — Display a live input level meter with a clipping indicator during setup,
so gain can be set correctly (against open-squelch noise, per the hardware study).

**FR-CAP-7 (S)** — Detect and warn on a persistently silent or persistently clipping input.

**CON-CAP-1** — Bluetooth audio input SHALL NOT be offered. A2DP is output-only; mic capture
forces HFP/mSBC (SBC at 16 kHz mono, bitpool 26), which degrades an already-degraded signal
at exactly the sample rate the model consumes.

### 7.2 FR-SEG · Segmentation

**FR-SEG-1 (M)** — Segment the audio stream into Transmissions using Silero VAD (or TEN-VAD)
running before any ASR model.

**FR-SEG-2 (M)** — Expose configurable VAD sensitivity, minimum speech duration, and
minimum silence duration for segment closure.

**FR-SEG-3 (M)** — Enforce a configurable maximum segment length, splitting longer audio,
so a stuck carrier cannot produce an unbounded segment.

**FR-SEG-4 (M)** — Prepend ring-buffer pre-roll to every emitted segment.

**FR-SEG-5 (M)** — Where the Rig Module reports squelch state, fuse it with VAD: **rig
squelch is authoritative for boundaries, VAD is authoritative for whether there is speech
inside them.** This is the single highest-value use of the rig connection after frequency,
because it converts segmentation from an inference into a measurement — and squelch-tail
hallucination (F4) is the #1 failure mode. Confirmed available on the TH-D75A via the `BY`
command (FR-RIG-3). Priority is **M** where the capability exists, **N/A** where it does not.

**FR-SEG-6 (M)** — Discard segments below the minimum-duration floor without invoking any
ASR model, recording them as `rejected:too_short` rather than deleting them.

**CON-SEG-1 — Segmentation is the one decision reprocessing cannot undo.**

Every other pass is a pure function of retained audio (§5.2), and §6.1 leans on that to claim
tier is a property of processing rather than of the record. **Segmentation is the exception,
and it was unstated in drafts 1–3.1.** Only gated segments are retained; the continuous stream
between them is not. A boundary error is therefore baked in permanently — a transmission split
in two stays split, two transmissions merged stay merged, and a callsign clipped by a
too-short pre-roll is gone from the audio, not merely from the transcript. No later tier, no
better model and no rig connected afterwards can recover it.

Three consequences, all of which bind now rather than later:

**FR-SEG-7 (M)** — Segmentation parameters SHALL NOT vary by tier. A weak device SHALL segment
identically to the reference device, because segmentation is the one thing it cannot be
forgiven for later. Tier may reduce what is *derived* from a segment; it may never change
where the segment's edges are.

**FR-SEG-8 (M)** — Pre-roll SHALL be generous rather than tight (FR-CAP-4), and the retained
segment SHALL include a configurable **post-roll** past the VAD close for the same reason.
Retaining audio that turns out to be silence is cheap; discovering a clipped callsign after the
fact is unrecoverable.

**FR-SEG-9 (S)** — Where storage permits, offer a **continuous-archive** capture mode that
retains the unsegmented stream for a bounded window, so segmentation itself becomes
reprocessable. This is the only mechanism that fully closes the gap, and it is a Should rather
than a Must because at ~1 GB per 8-hour shift it is affordable on the reference device and not
on the floor device. Where enabled, records SHALL be marked as re-segmentable.

> This also bounds AC-39. A T0 capture reprocessed at T3 matches a native T3 capture **only
> because both were segmented identically**, which FR-SEG-7 is what guarantees. Without it,
> AC-39 would be testing a claim that is not true.

### 7.2a FR-ENH · Speech enhancement

**FR-ENH-1 (S)** — Support an optional speech-enhancement front end (GTCRN or DPDFNet via
sherpa-onnx). GTCRN is 48.2 K parameters at 33.0 MMACs/s, so cost is negligible at any tier.

**FR-ENH-2 (M)** — Enhancement SHALL be applicable **per pass**, not globally. The enhanced
and unenhanced signals SHALL both remain available to downstream passes.

**FR-ENH-3 (M)** — Enhancement SHALL default to **off** and SHALL be enabled only on
measured evidence from the evaluation harness (AC-35), per capture profile.

**FR-ENH-4 (M)** — Records SHALL state whether enhancement was applied and to which passes.

> **This is deliberately not a free win.** Enhancement produced >30% relative WER reduction
> on CHiME-4, but ["When Denoising Hinders"](https://arxiv.org/pdf/2603.04710) finds
> separation preprocessing can *degrade* zero-shot Whisper — plausibly because Whisper was
> trained on noisy real-world audio and enhancement introduces out-of-distribution artifacts.
>
> FR-ENH-2 exists because the answer may differ **by pass**: speaker embedding and phonetic
> spotting models were not trained on noisy web audio and may benefit where Whisper suffers.
> Per-pass application costs nothing to build now and is expensive to retrofit.

### 7.2b FR-ACC · Hardware acceleration

**FR-ACC-1 (S / T3)** — Support executing Pass B on an NPU where one is available and a
compiled model exists for it.

**FR-ACC-2 (M)** — Acceleration SHALL sit behind a stable execution-provider interface. No
pass SHALL depend on a specific vendor SDK. **A CPU path SHALL exist for every model the
product ships**, and SHALL be the fallback on any device without a supported accelerator.

**FR-ACC-3 (M)** — Acceleration SHALL be used to run a **better model**, not the same model
faster. Where an accelerator is present, the tier detector SHALL prefer a larger model over
a lower latency target, because the duty cycle already provides latency headroom.

**FR-ACC-4 (M)** — Accelerator availability SHALL be detected at runtime, and its absence
SHALL degrade tier rather than fail.

**FR-ACC-5 (M)** — Records SHALL state which execution provider produced them, so a record
transcribed on CPU can be identified for reprocessing on a device with an accelerator.

**FR-ACC-6 (C)** — Where vendor toolchains diverge, prefer a single abstraction (LiteRT NPU
delegation) over per-vendor integration, accepting some performance loss for one code path.

> Qualcomm publishes `Whisper-Large-V3-Turbo` on the NPU across 40+ chipsets: encoder
> 267–278 ms per 30 s window, decoder ~6.3 ms/token on Snapdragon 8 Elite Gen 5, tens of MB
> of activation memory. For a 10-second over that computes to **~22x real time** — nine times
> faster than Whisper small on a 2019 phone CPU, at roughly five percentage points higher
> callsign accuracy. This is what FR-ACC-3 exists to spend. See `research/04` §3.
>
> Note this **reverses** the conclusion in `research/02` §4. That analysis was correct that
> throughput has 16x surplus, and wrong to infer the NPU was therefore useless — surplus
> speed can be traded for a bigger model, which is exactly the trade available.

### 7.3 FR-ASR · Transcription

**FR-ASR-1 (M)** — **Pass B**: transcribe each complete segment with the best offline model
the tier supports. Model selectable; default per tier.

**FR-ASR-2 (M / T2+)** — **Pass A**: run a streaming transducer over the live audio to emit
partial hypotheses, revised as context arrives, finalised on segment close.

**FR-ASR-3 (M)** — Pass A output SHALL be marked provisional and SHALL be superseded by
Pass B output for the stored record. Pass A text is never the record of what was said.

**FR-ASR-4 (M / T2+)** — Pass A SHALL apply decode-time hotword biasing from the active
lexicon slice (see 7.4), using `modified_beam_search`.

**FR-ASR-5 (M)** — Apply all six hallucination controls to Pass B output:
1. VAD gate (FR-SEG-1)
2. `no_speech_prob` ceiling, configurable
3. minimum duration floor
4. n-gram repetition detection
5. known-hallucination phrase blocklist, user-extensible
6. compression-ratio anomaly check

**FR-ASR-6 (M)** — A segment failing any hallucination control SHALL be stored with state
`rejected` and the failing rule recorded. **Audio is retained.** Rejected segments are
visible in the UI behind a filter, not hidden.

**FR-ASR-7 (M)** — Store per-segment model identity, version, quantization and decode
parameters alongside the transcript.

**FR-ASR-8 (M)** — Support user-supplied and side-loaded model files, so a fine-tuned model
can be installed without an app update. Promoted from Should to Must by D13.

#### Fine-tuned models (D13)

**FR-ASR-9 (M)** — Ship a **domain fine-tuned** Pass B model as the default where one is
available, at every tier. Fine-tuning is a model file, not a capability, and applies to T0
identically.

**FR-ASR-10 (M)** — Model metadata SHALL record whether a model is stock or fine-tuned, the
fine-tune identifier and its training-data description, and this SHALL be visible in
settings and stored on every transcript.

**FR-ASR-11 (S)** — Support multiple installed fine-tunes selectable per capture profile
(FR-CFG-1), so an HF-DX profile and a local-repeater profile can use different models.

> The evidence for this being a Must rather than a nice-to-have: ATC fine-tuning takes
> Whisper from 55.2% to 6.8% WER, and one study reached 13.7% — a 54.8% relative reduction —
> from **55 hand-transcribed clips**. See `research/04` §2. The M0 evaluation set is also the
> fine-tuning set, so this costs one labelling effort, not two.

#### Ensemble fusion (T3)

**FR-ASR-12 (M / T3)** — Where Pass A and Pass B have both produced hypotheses for a segment,
**fuse them** by confidence-weighted alignment rather than discarding Pass A.

**FR-ASR-13 (M)** — Fusion SHALL combine **architecturally diverse** systems — an
encoder-decoder and a transducer — because the published gain scales with model diversity,
not model count. Two sizes of the same architecture SHALL NOT be treated as an ensemble.

**FR-ASR-14 (M)** — Fused output SHALL record its constituent hypotheses and per-token
agreement, so disagreement is available as a confidence signal to Pass D.

#### N-best rescoring (T3)

**FR-ASR-15 (S / T3)** — Where an LLM is resident, rescore the ASR n-best list using combined
linguistic and acoustic scores.

**FR-ASR-16 (M)** — Rescoring SHALL be **strictly selective**: the LLM reranks a fixed
candidate set and SHALL NOT be permitted to emit tokens outside it. Acoustic score SHALL
remain in the objective.

> This does not violate D5. Reranking a closed hypothesis set cannot introduce a callsign
> that was never heard, which is categorically different from asking a model to read a
> transcript and report who was talking. The spec permits the first and forbids the second.
> Published effect: 5–25% relative WER reduction, largest where hypotheses are only slightly
> wrong — the regime this product operates in. See `research/04` §4.

### 7.4 FR-LEX · Lexicon and callsign resolution

This is the accuracy core. See `research/03-accuracy-lexicon-identity.md` for the evidence.

#### Lexicon assets

**FR-LEX-1 (M)** — Bundle or import, and index locally:

| Asset | Role | Size |
|---|---|---|
| ITU prefix allocation table | **Structural validator** — which prefixes exist and belong to whom | Small, static |
| National callsign format rules | Grammar per allocation | Small, static |
| FCC ULS amateur dump | US ranking prior | ~1.1M records |
| POTA park list | `K-nnnn` reference resolution | Moderate |
| WWARA repeater list *(in this repo)* | Frequency → expected-station prior | Small |
| SDS150 favorites *(in this repo)* | Frequency → channel-name prior | Small |
| Band plan tables | Mode and propagation plausibility | Small |
| Phonetic alphabet + variants | Pass C spotting vocabulary | ~100 entries |

**FR-LEX-2 (M)** — Lexicon assets SHALL be updatable independently of the app binary, with
a version recorded on every record that used them.

**FR-LEX-3 (M)** — The FCC ULS dump SHALL be downloadable on demand rather than bundled, to
keep install size reasonable and avoid redistribution questions. The app SHALL be fully
functional without it, at reduced ranking confidence.

#### Pass C — acoustic phonetic spotting

**FR-LEX-4 (M / T1+)** — Run keyword spotting over the retained segment audio for the
phonetic unit vocabulary (~36 core units plus variants), producing a
confidence-weighted **phonetic lattice** with time alignment.

**FR-LEX-5 (M)** — The lattice SHALL retain alternatives with scores, not a single best
path. Downstream ranking depends on having alternatives.

**FR-LEX-6 (M / T0)** — On T0, where Pass C is unavailable, derive a degraded lattice from
Pass B text by phonetic token expansion. Records SHALL indicate the lattice source.

#### Pass D — resolution

**FR-LEX-7 (M)** — Parse the lattice against the **callsign grammar**: prefix (ITU-allocated)
+ digit + suffix, including modifiers (`/P`, `/M`, `/MM`, `/AM`, `/QRP`) and reciprocal forms
(`K7ABC/VE7`, `VE7/K7ABC`).

**FR-LEX-8 (M)** — Structural validity against the ITU prefix table SHALL be the only hard
filter. A structurally valid callsign with **zero database hits SHALL still be emitted as a
candidate** — this is what makes worldwide DX work.

**FR-LEX-9 (M)** — Rank surviving candidates by combining priors. **No prior may act as a
hard filter.**

| Prior | Weight character |
|---|---|
| Frequency / repeater match from Rig Module | Very strong when present |
| Band plausibility (is worldwide DX physically plausible here?) | Strong |
| Database presence (ULS or other national data) | Moderate; absence is weak evidence |
| Recency — heard in this session or historically | Strong once warm, useless cold |
| Geographic prefix vs. operator location | Weak alone; useful combined with band |
| Conversation context — the other station already identified | Strong |

**FR-LEX-10 (M)** — Edit distance SHALL be weighted by known ASR acoustic confusion sets
(B/D/E/P/V/T; M/N; S/F) rather than uniform Levenshtein.

**FR-LEX-11 (M)** — Emit a ranked candidate list with scores. Where the top two candidates
are within a configurable separation threshold, the attribution state is `AMBIGUOUS`.

**FR-LEX-12 (M)** — Store the raw phonetic lattice and the full candidate list alongside the
chosen result, permanently, so any resolution is auditable and re-rankable.

**FR-LEX-13 (M)** — Resolve POTA park references (`K-nnnn` and spoken forms) and frequency
mentions by the same lattice-then-grammar approach.

**FR-LEX-14 (S)** — Maintain a user-editable "my stations" list which receives a strong
recency-class prior.

#### Active lexicon slice for biasing

**FR-LEX-15 (M / T2+)** — Compute an **active slice** for Pass A hotword biasing, bounded to
a configurable maximum (default 500 entries), assembled in priority order:
1. Stations heard in the current thread
2. Stations heard in this session
3. Expected stations for the current frequency (repeater trustee, known users)
4. Recently heard stations, decayed by time
5. The user's own "my stations" list

**FR-LEX-16 (M)** — The active slice SHALL be a *bias*, never a restriction. A station not in
the slice SHALL be resolvable at full accuracy through Passes C and D.

#### Confidence calibration

Everything in §4.1 and NFR-1a depends on a threshold, and raw model scores are not
probabilities. Without this, "above threshold" is undefined and the entire trust model rests
on an arbitrary number.

**FR-LEX-17 (M)** — Resolution scores SHALL be **calibrated** to an estimated probability of
correctness, **fitted on the train and dev folds and verified on the eval fold** (§14A.2), so
that a score of 0.9 means approximately nine correct in ten.

> *Draft 3.1 said "fitted against the M0 evaluation set", which is contamination stated as a
> requirement: a calibrator fitted on the eval fold will report excellent calibration on the
> eval fold by construction, and AC-55 would have been unfalsifiable. Fitting and verification
> must use different folds — that is the entire point of having them.*

**FR-LEX-18 (M)** — Calibration parameters SHALL be a versioned, shippable asset, refittable
without an app release, and recorded on every record that used them.

**FR-LEX-19 (M)** — The `CONFIRMED` threshold SHALL be **derived from the precision target**
for the active tier (§10.1), not configured as a raw score. Changing the precision target
SHALL move the threshold; the user never sets a score directly.

**FR-LEX-20 (M)** — The evaluation harness SHALL report a **reliability diagram** (predicted
confidence versus observed accuracy) per tier. A poorly calibrated model that hits its
precision target by luck is not acceptable evidence.

**FR-LEX-21 (S)** — Calibration SHALL be fitted separately per tier and per model, since
different models produce differently-shaped score distributions.

#### Operator location

Needed by the geographic prior (FR-LEX-9) and by POTA proximity.

**FR-LEX-22 (M)** — Determine operator location in priority order: (1) **rig-reported
position** where the Rig Module exposes `POSITION`, (2) **device GPS** where permission is
granted, (3) **manually entered Maidenhead grid**, per capture profile.

**FR-LEX-23 (M)** — Location permission SHALL be **optional**. Denying it SHALL fall back to
manual grid entry with no loss of any function except the geographic prior's precision.

**FR-LEX-24 (M)** — Location SHALL never leave the device, SHALL be stored at no finer than
grid-square precision in transmission records, and SHALL be excluded from diagnostic bundles
(FR-OBS-3) unless explicitly included.

> The TH-D75A carries GPS for APRS. Sourcing position from the rig gives mobile and portable
> accuracy **with no Android location permission at all**, which is why it is first in the
> priority order rather than a curiosity. `POSITION` is therefore added to `RigCapability`
> (§9.1).

#### Propagation and time-of-day plausibility

**FR-LEX-25 (M)** — The band-plausibility prior SHALL be computed from a **static, offline
model**: band, time of day, season, and distance between operator grid and the candidate
prefix's allocation centroid. No network, no live solar data.

**FR-LEX-26 (M)** — The prior SHALL be **asymmetric and weak in the negative direction**. A
physically implausible path SHALL demote a candidate, never eliminate it — sporadic-E, grey
line, satellites, repeater linking and internet-linked systems all defeat naive propagation
reasoning routinely.

**FR-LEX-27 (S)** — Where the frequency is a known internet-linked or repeater frequency from
the WWARA data, the propagation prior SHALL be suppressed entirely. A DX callsign on a local
2 m repeater is normal, not implausible.

#### Lexicon asset provenance

**FR-LEX-28 (M)** — Each lexicon asset SHALL declare its source, licence, format, update
cadence and last-import timestamp, visible in settings.

**FR-LEX-29 (M)** — The **ITU prefix allocation table** SHALL be bundled with the app as a
static asset, since it is small, changes rarely, and is required for FR-LEX-8 to function at
all. It SHALL NOT be an optional download.

**FR-LEX-30 (M)** — Every asset import SHALL be transactional and validated (record count and
checksum) before replacing the previous version, with rollback on failure (F12).

#### Cold start

**FR-LEX-31 (M)** — With no history, the system SHALL function using only structural priors
(grammar, ITU, band, database presence, frequency). Recency and conversation priors SHALL
contribute zero rather than a default, and their absence SHALL widen confidence intervals
rather than distort ranking.

**FR-LEX-32 (S)** — Seed the recency prior from the user's own "my stations" list
(FR-LEX-14) and from callsigns present in the imported WWARA repeater data, so day one is
better than empty.

### 7.5 FR-SPK · Speaker identity and threading

**FR-SPK-1 (M / T2+)** — Extract a speaker embedding per transmission using a WeSpeaker or
3D-Speaker model via sherpa-onnx.

**FR-SPK-2 (M)** — Skip embedding extraction for transmissions below a configurable
duration floor; such transmissions are never clustered.

**FR-SPK-3 (M)** — Cluster embeddings incrementally by cosine similarity against existing
Voiceprints, with a configurable threshold. Above threshold, join; below, create a new
Voiceprint.

**FR-SPK-4 (M)** — When a callsign reaches `CONFIRMED` on any transmission, bind it to that
transmission's Voiceprint and **back-propagate** to every other transmission in the cluster,
marking them `INFERRED` with the cluster similarity as confidence.

**FR-SPK-5 (M)** — Group transmissions into **Threads** using frequency continuity plus
inter-transmission gap, with a configurable gap threshold. A frequency change ends a thread
unless the Rig Module indicates the same channel.

**FR-SPK-6 (M)** — Exploit the FCC §97.119 identification requirement: a Voiceprint active in
an amateur thread SHALL be expected to acquire a `CONFIRMED` callsign within a configurable
window (default 10 minutes). Failure to do so is a signal to lower cluster confidence, not
to fabricate an attribution.

**FR-SPK-7 (M)** — A user correction to an attribution SHALL rebind the Voiceprint and
re-propagate across the cluster. The correction SHALL be recorded with a `CORRECTED` flag and
SHALL be immune to subsequent automatic re-propagation.

**FR-SPK-8 (S)** — Where a correction implies a mis-merge (the user asserts two clustered
transmissions are different stations), the system SHALL split the cluster and re-derive.

**FR-SPK-9 (M)** — Voiceprint confidence SHALL decay with elapsed time since last confirmed
observation. Cross-day cluster identity SHALL require re-confirmation.

**FR-SPK-10 (M)** — Attribution SHALL NEVER be presented without its confidence state
(FR-UI-4). This requirement exists at the data layer as well as the UI layer: the API that
returns an attribution SHALL make the state non-optional.

#### Net detection (D30, Q9)

Nets are the highest-yield traffic this product will ever hear: a check-in roll call is a
*sequence of callsigns read aloud deliberately, clearly, one at a time*, often with the control
operator repeating each one back. Generic threading models a 30-station net as one enormous
thread with 30 voiceprints and no structure.

**FR-SPK-27 (S)** — Detect net structure heuristically: **one dominant voiceprint alternating
with many others** on a stable frequency over a sustained period. Set `Thread.kind = net`.

**FR-SPK-28 (M)** — Detection SHALL be **advisory, never structural**. A thread marked `net`
displays differently and is scored differently; it is never *threaded* differently, so a
misdetection costs presentation and not the record. The user can set or clear the marking.

**FR-SPK-29 (S)** — In a detected net, treat the **check-in sequence as a high-confidence
callsign context**: consecutive short transmissions between returns to the control voice are
strong evidence of a callsign being given, and the control operator's read-back is a second
acoustic observation of the same callsign — which FR-LEX-9's conversation-context prior can use
directly.

**FR-SPK-30 (S)** — Where a net is detected, offer the thread as a **check-in list** — an
ordered roster with attribution state per entry — because that is the artifact the operator
actually wants from a net, and it exports cleanly (FR-EXP-2).

> The voice library (D28) makes this materially better rather than harder: the control operator
> of a weekly net is exactly the kind of station that enrols quickly, so the dominant voice is
> often already named before the net starts.

#### Persistent voice identity (D28)

FR-SPK-1..10 associate a callsign with a voice **within a session**. FR-SPK-9 then deliberately
forgets it: confidence decays and cross-day identity requires re-confirmation. The consequence
is that a station heard every night is re-learned from scratch every night, and a regular who
does not identify in the first ten minutes is `UNKNOWN` until they do.

A **voice library** — durable, enrolled voiceprints bound to Stations — removes that, and it
compounds: the longer the app runs, the more of the traffic it can name. It is also the
requirement with the sharpest failure mode in the specification, so the guards are part of it
rather than a later refinement.

**FR-SPK-11 (M)** — A Station MAY carry a **persistent voiceprint** that survives sessions,
stored as a durable embedding model with a version and the observations that produced it.

**FR-SPK-12 (M)** — Enrolment SHALL require **multiple `CONFIRMED` observations across at
least two distinct sessions**, with a configurable minimum. A single confirmation is never
enough: enrolling from one mis-resolved callsign would bind the wrong identity to a voice and
then propagate that error forward indefinitely.

**FR-SPK-13 (M)** — A cross-session voice match SHALL yield **`INFERRED`, never `CONFIRMED`**.
`CONFIRMED` means a callsign was heard and resolved in *this* transmission (§4.1) and a voice
match is not that, however strong. This is the requirement that keeps NFR-1a's precision
priority intact as the library grows.

**FR-SPK-14 (M)** — The cross-session match threshold SHALL be **stricter than the
within-session clustering threshold**, and SHALL be derived from a measured false-match rate
rather than set by feel. Within a session, a wrong merge affects one conversation; across the
library, it mislabels a station indefinitely.

**FR-SPK-15 (M)** — Voice matches SHALL be **rankable and rejectable**: where several enrolled
voiceprints are close, the result is `AMBIGUOUS` (FR-LEX-11's rule applied to identity), not a
coin flip.

**FR-SPK-16 (M)** — The user SHALL be able to inspect the voice library — which stations are
enrolled, from how many observations, when last matched — and to **rename, merge, split and
delete** entries. Deleting SHALL destroy the stored embedding, not merely unlink it.

**FR-SPK-17 (M)** — A user correction of a cross-session match SHALL **de-enrol or re-enrol as
appropriate** and SHALL be recorded, so the library learns from corrections rather than
repeating the error (P3).

**FR-SPK-12a (M)** — Default enrolment is **two `CONFIRMED` observations across two distinct
sessions** (D28). Configurable per FR-CFG-2, and to be re-derived from the measured false-match
rate once FR-SPK-19 has numbers.

**FR-SPK-23 (M)** — The user SHALL be able to **bind a voice by hand, in one tap**, to a
callsign, a friendly name, or both. A manual binding is an assertion, not an inference: it
enrols immediately regardless of FR-SPK-12a's thresholds, is flagged `CORRECTED`, and is immune
to automatic re-propagation (FR-SPK-7).

**FR-SPK-24 (M)** — A voiceprint MAY carry a **user-supplied name with no callsign at all** —
"the Tuesday net control", "Dave" — because a voice you recognise but who never identifies is
still the most useful thing the log can tell you about a transmission. Such a Station SHALL
display its name with the same `INFERRED` marker and SHALL NOT be exported as a callsign
(FR-EXP-4).

**FR-SPK-25 (M)** — User-supplied names are **user content**: never inferred, never generated,
never included in a contribution payload (FR-CON-3, FR-SPK-20). They are also the field most
likely to contain a real person's name, which is the reason for that exclusion.

**FR-SPK-26 (C)** — Where QRZ enrichment is added (FR-EXP-6, v2), the manual binding flow MAY
offer a lookup to populate the name from the callsign. Explicitly online, explicitly optional,
never in the capture path (NFR-6).

**FR-SPK-18 (S)** — Handle **voice drift**: a voice changes with illness, fatigue, a different
microphone or a different radio. Enrolled voiceprints SHALL be updatable from later confirmed
observations, and a persistently failing match SHALL degrade the enrolment rather than silently
mis-matching.

**FR-SPK-19 (M)** — The evaluation harness SHALL report **cross-session identification
precision and recall separately** from within-session clustering (FR-TST-8). They are different
claims with different failure costs, and one number hides the one that matters.

> **A voiceprint is biometric data about a person who did not consent to being enrolled.**
> That is a materially different thing from a transcript of a public transmission, and it
> changes three requirements:
>
> - **FR-SPK-20 (M)** — Voiceprints and embeddings SHALL NEVER leave the device. They are
>   excluded from the corpus contribution channel (amending FR-CON-3), from diagnostic bundles
>   (FR-OBS-3), and from cloud backup (FR-PLT-5). Export (FR-STO-6) MAY include them only for
>   the user's own device-to-device transfer, and SHALL say so.
> - **FR-SPK-21 (M)** — Deleting a Station SHALL delete its voiceprint, and a single "forget
>   this voice" action SHALL exist.
> - **FR-SPK-22 (S)** — The voice library SHALL be disableable entirely, with the product fully
>   functional without it at the cost of cross-session recall. **Default on** (D28), disclosed
>   in settings rather than by an onboarding prompt.
>
> The product already records third parties by design; a persistent biometric identifier of
> them is a step beyond that, and it should be a deliberate, visible, reversible one.
>
> **The silent default is defensible only because FR-SPK-20 holds.** Enrolling biometric
> identifiers without asking would be hard to justify if they were transmitted, pooled or
> shared — but they never leave the device, are deletable outright, and the whole feature can be
> switched off. That makes FR-SPK-20 **load-bearing rather than precautionary**: if any future
> change proposes contributing, syncing or backing up voiceprints, the default must move to
> explicit opt-in in the same change.

### 7.6 FR-RIG · Radio interface

Designed per D9: start with the TH-D75A, be extensible to any radio.

**FR-RIG-1 (M)** — Define a **Rig Module contract** that all radio adapters implement:

```
RigModule
  id                : stable string identifier
  displayName       : human name
  transport         : USB_SERIAL | BLE | NETWORK | NONE
  capabilities      : Set<RigCapability>
  configSchema      : declared connection parameters (baud, etc.)

  connect(params)   : Result<Connection>
  disconnect()
  observe()         : Flow<RigState>          // push or polled, module's choice
  health()          : Flow<RigHealth>

RigCapability = FREQUENCY | MODE | SQUELCH_STATE | SIGNAL_STRENGTH
              | MEMORY_CHANNEL | CHANNEL_NAME | SUB_BAND | TIME
              | POSITION            // TH-D75A has GPS for APRS — see FR-LEX-22

RigState
  timestamp, frequencyHz?, mode?, squelchOpen?, signalStrength?,
  memoryChannel?, channelName?, position?, sourceConfidence
```

**FR-RIG-2 (M)** — Ship a **null module** implementing the contract with
`capabilities = {}`, providing manual frequency entry. This is the audio-only v1 default and
SHALL be a first-class path, not a fallback.

**FR-RIG-3 (M)** — Ship a **Kenwood TH-D75A module** over USB serial using
`usb-serial-for-android`, providing at minimum `FREQUENCY`, `MODE` and `SQUELCH_STATE`.

> **Verified from `thd75a programming details/TH_D75_Commands.pdf` in this repo.** The
> TH-D75A uses two-letter ASCII CAT commands with read/set syntax, and Kenwood ships a
> **CDC** driver (`USB_CDC_Driver_TH-D75_V100`) — so `usb-serial-for-android`'s CDC-ACM path
> claims it with no vendor driver and no root. `BY` reports squelch status, which makes
> FR-SEG-5 achievable rather than speculative. This module should therefore be a
> **declarative descriptor (FR-RIG-4), not code.**

**FR-RIG-4 (M)** — Support **declarative rig descriptors** for ASCII command/response CAT
protocols (Kenwood, Yaesu, Elecraft families), so a new radio can be added by writing a data
file rather than code. A descriptor declares commands, response patterns, field extraction,
poll intervals and capability mapping.

**FR-RIG-5 (S)** — Support **code-based modules** for protocols a declarative descriptor
cannot express — notably Icom CI-V (binary) and Uniden SDS remote.

**FR-RIG-6 (M)** — Rig state SHALL be timestamped and correlated to transmissions by time.
Where rig state changes mid-transmission, record the state at transmission *start* and flag
the change.

**FR-RIG-7 (M)** — Rig disconnection SHALL degrade to the last known frequency, marked as
stale, and SHALL NOT stop capture.

**FR-RIG-8 (M)** — Manual frequency override SHALL always be available regardless of module,
and SHALL take precedence, recorded with provenance `manual`.

**FR-RIG-9 (M)** — Every frequency value SHALL carry provenance: `rig` | `manual` |
`inherited` | `voice` | `unknown`.

**FR-RIG-10 (C)** — Voice-derived frequency ("tuning to fourteen one five zero") resolved by
Pass D, used only when no better source exists, and always marked `voice`.

### 7.7 FR-STO · Storage and retention

**FR-STO-1 (M)** — Persist all records in SQLite with FTS5 full-text indexing over
transcripts.

**FR-STO-2 (M)** — Store segment audio as Opus, gated (only transmissions, not silence).

**CON-STO-1 — The retention codec is an accuracy decision, not a storage decision, and it is
made before the pass that cares about it exists.**

FR-REP-4 requires retained audio sufficient to re-run **every** pass. Opus at conversational
bitrates is a lossy perceptual codec tuned for intelligible speech, and Pass C (FR-LEX-4) is a
sub-phoneme acoustic discrimination task over exactly the material — narrowband, noisy,
compressed off-air voice — where perceptual coding discards most aggressively. The ordering
hazard is the problem: **the codec is chosen in M2 and Pass C is not measured until M4**, so a
lossy default could silently cap the project's core accuracy thesis and be misread at M4 as
the thesis failing.

**FR-STO-2a (M)** — Until Pass C is measured (M4), captured audio SHALL be retained
**losslessly** (PCM or FLAC). FLAC on this material is roughly 50–60% of PCM and is exactly
reversible.

**FR-STO-2b (M)** — The choice of a lossy retention codec SHALL be justified by a **measured
comparison on the evaluation harness** — the same pipeline, the same fold, lossless versus each
candidate bitrate — reporting the effect on callsign precision and recall, not on file size
alone. Absent that measurement, lossless retention stands.

**FR-STO-2c (M)** — Where a lossy codec is adopted, records SHALL state the codec, bitrate and
the measurement that justified it, so a later reprocess knows whether its input was already
degraded.

**FR-STO-3 (M)** — Retention SHALL be governed by a **storage budget, not a time limit**
(D26). Independent, user-set budgets for (a) gated transmission audio and (b) the continuous
archive (FR-SEG-9), each allowing **unlimited** as an explicit choice. Text is retained
indefinitely and is not budgeted.

> *Time-based retention was the original design, and it is a proxy for the thing the user
> actually cares about, which is disk. A 30-day rule deletes material that costs nothing to keep
> on a 512 GB phone, and fails to protect a 64 GB one during a busy week. A budget is directly
> meaningful, and it is what makes "unlimited" expressible at all. This also finally closes Q5
> and Q13.*

**FR-STO-3a (M)** — On reaching a budget the system SHALL NOT silently delete. It SHALL warn,
and offer **bulk export-and-prune over a user-chosen date range** as the primary action: write
the range out to user-chosen storage, verify it, then reclaim the space. Automatic oldest-first
pruning SHALL be available but opt-in per budget.

**FR-STO-3b (M)** — Export-and-prune SHALL be **transactional in the safe direction**: nothing
is deleted until the export is written and verified, and an interrupted run SHALL leave every
record in place. Losing a recording to a half-finished cleanup is not an acceptable failure
(P9).

**FR-STO-3c (M)** — Where the audio budget is set below what the reprocessing horizon needs,
the UI SHALL name the consequence in FR-REP-4's terms rather than merely warning.

**FR-STO-4 (M)** — Warn before storage exhaustion and degrade predictably: stop writing
audio before stopping writing text, and never stop capture silently.

**FR-STO-5 (M)** — Display current and projected storage usage in settings.

**FR-STO-6 (M)** — Support export of the full database and audio archive to user-chosen
storage.

**FR-STO-7 (S)** — Support pinning a thread or transmission so retention never deletes it.

**FR-STO-8 (M)** — All storage SHALL be app-private by default. No media-store exposure of
captured audio without explicit user action.

### 7.8 FR-UI · Reader and search

**FR-UI-1 (M)** — **Live view**: a running list of transmissions as they occur, newest
visible, showing time, frequency, attribution with confidence state, and text. Pass A
partials appear and are visibly replaced by Pass B finals.

**FR-UI-2 (M)** — **Thread view**: transmissions grouped into conversations, showing the
attribution reasoning — which transmission confirmed a callsign, and which inherited it.

**FR-UI-3 (M)** — **Search**: full-text across transcripts, with filters for callsign,
frequency, band, time range, attribution state, and rejected/accepted.

**FR-UI-4 (M)** — Attribution confidence SHALL be visually distinct for all four states and
SHALL never be omitted. Inferred attributions SHALL link to the transmission that confirmed
the callsign.

**FR-UI-5 (M)** — Audio playback per transmission, with the transcript.

**FR-UI-6 (M)** — One-tap correction of any attribution, with the correction propagating per
FR-SPK-7.

**FR-UI-7 (M)** — A **capture status surface** always reachable in one tap: running state,
elapsed time, input device and verified route, rig connection state, transmissions captured,
backlog depth, current tier, storage used, battery.

**FR-UI-8 (M)** — Show the candidate list and phonetic lattice for any resolved callsign on
demand. This is how G4 is actually delivered.

**FR-UI-9 (M)** — Station view: everything heard from one station, across sessions. *Promoted
from Should: a monitoring log that runs for months can answer "who is this, and when are they
around" in a way nothing else the operator owns can, and the data is already being stored.*

**FR-UI-10 (M)** — Frequency/channel view: everything heard on one frequency, across sessions.
*Promoted from Should, same reasoning.*

**FR-UI-11 (M)** — Both views SHALL show **activity patterns**: when this station or frequency
is typically active, by hour and by day of week, and how that has changed. Computed from stored
records, no new capture required.

**FR-UI-12 (M)** — Pattern displays SHALL distinguish **"not heard" from "not listening"**. A
frequency shows no activity at 03:00 either because nothing was transmitted or because the app
was not running, and conflating those turns the feature into a lie. `CaptureGap` (FR-RUN-12)
and session bounds already carry what is needed; the pattern view must use them.

### 7.9 FR-DIG · Digest

**FR-DIG-1 (M)** — Generate a digest over a configurable window (default: since last opened).

**FR-DIG-2 (M)** — The v1 digest SHALL be **template-derived and deterministic**: activity
by frequency and time, stations heard with confidence, notable events (POTA references, new
stations, longest threads), and volume statistics. No LLM required.

**FR-DIG-3 (M / T3)** — Where an on-device LLM is available and enabled, generate prose
summaries per thread as an *addition* to the deterministic digest, never a replacement.

**FR-DIG-2a (M)** — The digest SHALL be ordered by **salience, not chronology**. G1 asks the
operator to understand a night's traffic in under two minutes, and a flat time-ordered list of
several hundred transmissions cannot deliver that at any level of transcription accuracy.
Ranking signals, all computed deterministically from stored records:

| Signal | Why it is interesting |
|---|---|
| A station never heard before | The single most reliable novelty signal |
| A frequency departing from its usual pattern (FR-UI-11) | Quiet channel suddenly busy, or a regular net absent |
| POTA / SOTA references, and portable or maritime modifiers | The traffic most likely to be acted on |
| Unusually long threads, or unusually many participants | Nets, emergencies, events |
| A high rejection or `AMBIGUOUS` rate in a window | Something went wrong — a health signal surfaced as content |

**FR-DIG-2b (M)** — Salience SHALL be a **reordering, never a filter**. Everything remains
reachable, and the digest SHALL state how much it is not showing, with one action to see all of
it. A digest that silently hides a transmission fails the same way a log that silently guesses
does (P9).

**FR-DIG-2c (M)** — The reason an item was surfaced SHALL be visible — "first time heard",
"unusually long thread" — because an unexplained ranking cannot be trusted or corrected (P2).

**FR-DIG-3a (M)** — FR-DIG-3 is **conditionally Must**: the requirement binds the *behaviour*
if the feature is built, and does not require the feature to be built. §1.5 places a
default-on LLM digest out of scope for v1, and Q7 may delete FR-DIG-3 entirely — if the
deterministic digest is what the operator actually reads, the LLM adds nothing. Shipping v1
with no LLM present is a conforming build, and AC-84 is the criterion that proves it.

**FR-DIG-4 (M)** — The LLM SHALL be given already-resolved entities and SHALL NOT be
permitted to emit a callsign. Enforce with grammar-constrained decoding where the runtime
supports it.

#### Station knowledge — the digest that accumulates (D29)

A per-session digest answers "what happened last night". The far more valuable question, for a
log that runs for months, is **"what do I know about this station?"** — and the answer should
get richer every time they transmit. This is the counterpart to the voice library: one makes
the app recognise a regular, the other makes it remember them.

**The split that keeps this honest, and it is not negotiable:**

| | **Station facts** | **Topic summaries** |
|---|---|---|
| Source | Derived deterministically from records | Generated by an LLM over transcripts |
| Examples | Frequencies and times heard, activity pattern, POTA/SOTA references, grid squares spoken, ITU region from the prefix, first and last heard, over counts by attribution state | "Talked about antenna work and a trip to Oregon" |
| Status | **Fact** — traceable to the transmissions that produced it | **Impression** — visibly marked, never asserted |
| Requires an LLM | No | Yes (T3, optional) |

**FR-DIG-7 (M)** — Maintain a **Station record** that accumulates across sessions: everything in
the "facts" column above, each traceable to the transmissions that produced it (P2).

**FR-DIG-8 (M)** — Station facts SHALL be **deterministic and re-derivable** from stored
records. A fact that cannot be recomputed from the log is not a fact; it is a summary, and it
belongs in the other column.

**FR-DIG-9 (M)** — Digests SHALL be **linkable**: a session digest links to the stations in it,
a station record links back to every session and thread it appeared in, and both link to the
transmissions underneath. This is the "graph" — built from foreign keys over existing data, not
a separate inference layer.

**FR-DIG-10 (M)** — Location claims SHALL state their **source and precision**: a POTA reference
is exact, a spoken grid square is as precise as it was spoken, an ITU prefix gives a country and
nothing more, and a mentioned place name is a mention, not a location. The prefix of a callsign
says where it was **issued**, never where the operator is (FR-LEX-26's asymmetry applied to
geography).

**FR-DIG-11 (M / T3)** — Where topic summaries are generated, they SHALL be attributed to the
transmissions that produced them, visually distinguished (FR-DIG-6), and **phrased as reported
speech** — "said they were working on an antenna" — never as asserted biography.

**FR-DIG-12 (M)** — The system SHALL NOT generate or store **inferences about a person** beyond
what was said: no mood, no health, no employment, no relationships, no "what their day was
like" as a characterisation. It may record *that they mentioned* something, quoting or citing
the transmission. **The line is between a log of what was transmitted and a profile of a
person**, and the product stays firmly on the first side of it.

**FR-DIG-13 (M)** — Station knowledge SHALL be **local-only**: excluded from the corpus
contribution channel (FR-CON-3) and from diagnostic bundles, on the same reasoning as
voiceprints (FR-SPK-20). An accumulating dossier on identifiable third parties is precisely the
thing that must not leave the device.

**FR-DIG-14 (S)** — The user SHALL be able to review and delete station knowledge, including
"forget this station entirely" alongside FR-SPK-21's "forget this voice".

> **Why FR-DIG-12 is drawn tightly.** The request behind this feature — knowing what a regular
> is like, where they operate from, what they talk about — is a genuinely good product instinct,
> and most of it is served by the facts column at zero risk. The part that is not is an LLM
> writing character studies of real people from noisy transcripts of a lossy channel, stored
> permanently and never seen by them. A hallucinated callsign is a bad log entry; a hallucinated
> claim about someone's health or circumstances is a different category of wrong, and it is
> unfalsifiable to the reader because they were not listening. That is R16.

**FR-DIG-5 (M)** — LLM inference SHALL run only when the device is idle, charging or
plugged, and thermally unconstrained. It SHALL NEVER run in the capture path.

**FR-DIG-6 (M)** — LLM-generated text SHALL be visually distinguished from deterministic
content.

### 7.10 FR-EXP · Export

**FR-EXP-1 (M)** — Export stations heard as ADIF, for logging software.

**FR-EXP-2 (M)** — Export as CSV with all fields including confidence and provenance.

**FR-EXP-3 (M)** — Export POTA-relevant activity (park reference, station, frequency, time).

**FR-EXP-4 (M)** — Exports SHALL carry attribution confidence. **An `INFERRED` attribution
SHALL NOT be exported as though `CONFIRMED`.**

**FR-EXP-5 (S)** — Offer a filtered export of confirmed-only records for users who want a
conservative log.

**FR-EXP-6 (C)** — QRZ lookup enrichment when a network is available, as an explicitly
online, explicitly optional action. Never in the capture path.

### 7.11 FR-SVC · Background execution

**FR-SVC-1 (M)** — Run capture in a foreground service of type `microphone`, started from a
foreground activity by explicit user action.

**FR-SVC-2 (M)** — Hold a partial wake lock for the duration of capture.

**FR-SVC-3 (M)** — Persistent notification showing running state, elapsed time and
transmission count, with a stop action.

**FR-SVC-4 (M)** — Detect and log service lifecycle events including unexpected termination,
so an OEM kill is visible after the fact rather than mysterious.

**FR-SVC-5 (M)** — On launch, detect whether the app is subject to battery optimization or
OEM app-restriction, and guide the user through exempting it. This is a **first-run
onboarding step**, not a settings item.

**FR-SVC-5a (M)** — Guidance SHALL be **manufacturer-specific**, keyed off
`Build.MANUFACTURER`, deep-linking to vendor settings screens where an Intent exists. On the
reference device this means all four ColorOS interventions in §10.8, not just the standard
exemption.

**FR-SVC-5b (M)** — The app SHALL **NOT** treat
`PowerManager.isIgnoringBatteryOptimizations() == true` as proof that background execution
will survive. It is a hint. **Liveness is established empirically** (NFR-8): write a heartbeat
on an interval during capture; on next launch, compare the last heartbeat against the session
end to determine whether the session was killed.

**FR-SVC-5c (S)** — Offer a **"prove it" test**: a short unattended run — 30 minutes, screen
off — that reports afterwards whether capture survived. This converts an unverifiable setup
ritual into a pass/fail the user can trust before leaving the app running overnight.

**FR-SVC-6 (M)** — On unexpected termination, recover on next launch: finalise the
interrupted session, process any unprocessed backlog, and tell the user what happened.

**FR-SVC-7 (M)** — Capture SHALL survive screen-off, device idle, and app-backgrounded
states for ≥8 hours (NFR-2).

**FR-SVC-8 (S)** — Optional auto-resume after device reboot, subject to the API 34+
restriction that a microphone foreground service cannot be started from `BOOT_COMPLETED` —
therefore implemented as a notification prompting the user to resume, not a silent restart.

### 7.12 FR-CFG · Configuration

**FR-CFG-1 (M)** — Configuration profiles per deployment (e.g. "TH-D75A HF", "scanner"),
switchable in one action, carrying audio, VAD, model, lexicon and rig settings.

**FR-CFG-2 (M)** — Expose all thresholds named in this spec (VAD sensitivity, minimum
durations, `no_speech_prob` ceiling, cluster similarity, candidate separation, thread gap,
active slice size) with sane defaults and documented effects.

**FR-CFG-3 (M)** — Provide a reset-to-defaults per profile.

**FR-CFG-4 (S)** — Export/import a profile as a file, so configurations can be shared.

### 7.13 FR-OBS · Observability

Required because most failures here are silent.

**FR-OBS-1 (M)** — Maintain a diagnostics log covering audio route changes, VAD statistics,
per-pass latency, rejection reasons, tier changes, rig connection events, and service
lifecycle.

**FR-OBS-2 (M)** — Surface a health screen showing rolling statistics: transmissions/hour,
rejection rate by reason, mean per-pass latency, backlog depth, attribution state
distribution.

**FR-OBS-3 (M)** — Support exporting a diagnostic bundle (log plus configuration, excluding
audio unless explicitly included) for bug reports.

**FR-OBS-4 (M)** — Offer a "record a labelled sample" mode that captures audio plus
ground-truth annotations, for building the evaluation set.

> **Corrected in draft 3.2.** Draft 3.1 justified this as "M0 is blocking and this is the tool
> that produces it", which contradicted §15's own — and correct — claim that M0 needs no code.
> It cannot be both: M0 blocks M2, so a tool that ships in the app cannot be what produces M0.
> **M0 is built with desktop tooling and a recorder**, and FR-OBS-4 is what keeps the corpus
> *growing* afterwards, from real sessions, in the field, in the format the harness already
> reads. That is a genuine and durable requirement — the corpus is never finished, and the
> cheapest labelled minute is the one captured where the operator already noticed something
> interesting — but it is a **v1 feature, not an M0 prerequisite**. It lands in M5 with the
> reader, where correction UI already exists and is most of the same surface.

**FR-OBS-5 (M)** — There SHALL be **no analytics, telemetry or crash reporting**, in any build.
Diagnostics leave only when the user exports a bundle (FR-OBS-3).

**FR-OBS-5a (M)** — The single exception is the **corpus contribution channel** (FR-CON-1..8,
D25), which is off unless the user turns it on, carries only what §7.13a defines, and is
independent of every other function. *Draft 3.2 removed the words "or any other automatic
transmission of data off the device, in any build" from FR-OBS-5 to make this exception
possible. That was a deliberate product decision, not an oversight, and it narrows a promise
the product previously made without qualification.*

### 7.13a FR-CON · Corpus contribution (D25)

The product improves with data it does not have, and the operators using it are generating
exactly that data. This is the mechanism, and it is the only part of the system that sends
anything anywhere.

**FR-CON-1 (M)** — Contribution SHALL be **off until the user turns it on**, presented at
onboarding as a clear choice with a plain description of what is sent. Declining SHALL be a
single tap, SHALL never be re-prompted more than once, and SHALL leave every other function
fully working.

**FR-CON-2 (M)** — Once enabled, upload MAY proceed automatically, subject to: unmetered
network only by default, device charging or above a battery threshold, and **never during
active capture** (the capture path stays offline, NFR-6).

**FR-CON-3 (M)** — What is contributed SHALL be a **closed, documented set**: transmission
audio, transcripts, callsign candidates and the chosen result, corrections, frequency, and the
model/lexicon/calibration versions in force. It SHALL NOT include operator location finer than
grid square, diagnostic logs, profile names, or any device identifier beyond an opaque
per-install token the user can reset.

**FR-CON-4 (M)** — The user SHALL be able to review **exactly what has been and will be sent**,
browse it, exclude any session or transmission, and turn contribution off at any time. Turning
it off SHALL stop future uploads immediately.

**FR-CON-5 (M)** — Contribution SHALL be **revocable**: the user can request deletion of
everything previously contributed under their install token, and the receiving side SHALL
honour it.

**FR-CON-6 (M)** — Contributed data SHALL be treated as recordings of **identifiable third
parties who did not consent**. The people heard are not the user. Consequently: contributed
audio SHALL NOT be republished as an open dataset without a separate, deliberate decision;
the receiving side SHALL keep it access-controlled by default; and the onboarding copy SHALL
state plainly that recordings of other operators are included.

**FR-CON-7 (S)** — Prefer contributing **corrections and resolved metadata without audio**
where that is sufficient. A user correction is the highest-value signal for improving ranking
and carries the least third-party exposure; audio should be contributed when it adds something
a correction cannot.

**FR-CON-8 (M)** — The contribution client SHALL live entirely in the network module and SHALL
be absent from the capture and processing paths, so NFR-6's guarantee — no network in the
capture path — remains verifiable by packet capture (AC-59).

> **Two things the product owner should decide before this ships, not after.** Whether
> contributed audio can ever be published (FR-CON-6), and what jurisdictional constraints apply
> to retransmitting third-party radio traffic — which vary by locality and are already flagged
> in NFR-6c. Neither blocks building the client; both block turning it on. Recorded as Q17.

### 7.14 FR-RUN · Runtime architecture

The single most important structural constraint, and it was implicit in draft 2:

**FR-RUN-1 (M)** — **Capture SHALL NEVER block on inference.** The capture path — read,
ring-buffer, VAD, write segment — SHALL run independently of every processing pass and SHALL
be able to proceed indefinitely with all passes stalled.

#### Backpressure and overload (D16)

**FR-RUN-2 (M)** — Segments SHALL be enqueued to a **durable on-disk work queue**, not an
in-memory one. A queued segment survives process death.

**FR-RUN-3 (M)** — **Audio SHALL NEVER be dropped due to processing pressure.** Under
sustained overload the system SHALL shed work in this order, surfacing each step:

| Order | Shed | Consequence |
|---:|---|---|
| 1 | Pass A live partials | Loses immediacy only |
| 2 | Pass E speaker identity and threading | Attribution degrades to per-transmission |
| 3 | Pass B model downgraded within the tier | Lower accuracy, recoverable |
| 4 | All passes deferred; segments queued and marked for reprocessing | Log lags; nothing lost |
| 5 | Storage exhaustion only: stop capture with a loud, unmissable alert | The single case where capture stops |

**FR-RUN-4 (M)** — Every record affected by shedding SHALL be marked a reprocessing candidate
(FR-REP-8). **Overload therefore produces a provisional record, never a lost one** — the same
mechanism as tier degradation, which is why both are safe.

**FR-RUN-5 (M)** — Queue depth, oldest-unprocessed age, and current shed level SHALL be
observable (FR-OBS-2) and visible in the capture status surface (FR-UI-7).

**FR-RUN-6 (M)** — The queue SHALL be bounded by **available storage**, not by a fixed item
count, and SHALL warn at configurable thresholds well before exhaustion.

#### Transmission lifecycle

**FR-RUN-7 (M)** — Every Transmission SHALL occupy exactly one state, with defined transitions:

```
              ┌──────────┐
   VAD close ─▶ CAPTURED │ audio on disk, queued, no passes run
              └────┬─────┘
                   ▼
              ┌──────────┐
              │PROCESSING│ ── crash / kill ──┐
              └────┬─────┘                   │
        ┌──────────┼──────────┐              │
        ▼          ▼          ▼              │
   ┌─────────┐ ┌────────┐ ┌────────┐         │
   │COMPLETE │ │REJECTED│ │ FAILED │         │
   └────┬────┘ └───┬────┘ └───┬────┘         │
        │          │          │              │
        └──────────┴──────────┴──────────────┘
                   │
                   ▼  reprocess requested / higher tier available
              ┌──────────┐
              │  STALE   │  current result exists, better is possible
              └──────────┘
```

**FR-RUN-8 (M)** — `PROCESSING` SHALL be crash-recoverable: on launch, any transmission left
in `PROCESSING` SHALL be returned to `CAPTURED` and re-queued. **Passes are idempotent**
(§5.2, Principle 2), so re-running one is always safe.

**FR-RUN-9 (M)** — `FAILED` (a pass errored) is distinct from `REJECTED` (a pass ran and
correctly declined the segment). `FAILED` is retryable with backoff and a bounded attempt
count; `REJECTED` is a result.

**FR-RUN-10 (M)** — Retries SHALL be bounded, and a transmission exceeding the limit SHALL
remain in `FAILED` with its error recorded, visible in the UI, and eligible for manual retry.

**FR-RUN-10a (M)** — Every pass execution SHALL be bounded by a **timeout** proportional to
the segment duration and the tier's expected RTF, and a pass exceeding it SHALL be cancelled
and treated as `FAILED`. FR-RUN-9 and F18 cover a pass that *errors*; a pass that **hangs** is
a different failure and was uncovered — an unbounded native inference call occupies the single
inference slot forever, so one bad segment stops all processing while capture continues
filling the queue behind it. The timeout is what stops a stuck pass from becoming a stuck
product.

#### Audio interruption

Guaranteed to occur during an 8-hour run, and absent from draft 2 entirely.

**FR-RUN-11 (M)** — Handle `AudioRecord` interruption from an incoming phone call, another
app acquiring the microphone, or an audio-focus change: detect it, record a **gap marker**
with start and end times in the session, and resume automatically when the input becomes
available.

**FR-RUN-12 (M)** — A capture gap SHALL be **first-class data**, visible in the timeline and
counted in health statistics. Silence that was never listened to must be distinguishable from
silence that was.

**FR-RUN-13 (M)** — Route changes mid-session (device unplugged, headset attached) SHALL be
detected and SHALL re-verify the route per FR-CAP-3 before resuming. A route change that lands
on **any device other than the selected one** SHALL halt, never continue — including, and
especially, the built-in mic (FR-CAP-3a).

**FR-RUN-14 (S)** — Where the platform permits concurrent capture, request it, so a phone
call degrades to a gap rather than terminating the session.

#### Time and clocks

**FR-RUN-15 (M)** — Durations and inter-event intervals SHALL use a **monotonic** clock;
wall-clock time SHALL be stored separately for display. Both SHALL be persisted (F14).

**FR-RUN-16 (M)** — Audio sample position SHALL be the authoritative timeline within a
session. Timestamps SHALL be derived from sample count, not from wall-clock reads at
processing time.

**FR-RUN-17 (M)** — Rig state SHALL be timestamped on receipt and correlated to audio by
sample position within a bounded skew of **≤250 ms**. Where skew cannot be bounded — for
example a slow poll cycle — frequency provenance SHALL be downgraded to `inherited`.

**FR-RUN-18 (M)** — All stored wall-clock times SHALL be UTC with the originating offset
retained, so DST transitions and travel do not corrupt ordering or retention.

### 7.15 FR-AST · Assets, schema and migration

**FR-AST-1 (M)** — Models, lexicon data and calibration parameters are **versioned assets**
with a common lifecycle: install, verify, activate, roll back, remove.

**FR-AST-2 (M)** — Every asset SHALL be integrity-verified (checksum, and size and format
validation) before activation. A failed verification SHALL leave the previous version active.

**FR-AST-3 (M)** — Large assets SHALL be downloadable on demand, with progress, resumability,
and a **default to unmetered networks only**. The app SHALL be usable while a download is
pending, at whatever tier the currently-installed assets support.

**FR-AST-4 (M)** — An asset in use by a running session SHALL NOT be deleted or replaced
mid-session. Activation of a new version SHALL take effect at the next session or the next
reprocess.

**FR-AST-5 (M)** — The database SHALL carry a **schema version**, with forward migrations
tested against fixtures from every previously released version.

**FR-AST-6 (M)** — Migrations SHALL never destroy captured audio or superseded transcripts.
Where a migration cannot preserve a derived field, it SHALL mark affected records as
reprocessing candidates rather than discarding them.

**FR-AST-7 (M)** — Capture profiles (FR-CFG-1) and rig descriptors (FR-RIG-4) SHALL be
versioned, and importing a newer version than the app understands SHALL fail cleanly with a
clear message rather than partially applying.

**FR-AST-8 (M)** — Audio files SHALL have a defined on-disk layout and naming scheme derived
from session and transmission identity, with a **reconciliation pass** that detects and
reports orphaned files and dangling references in both directions.

**FR-AST-9 (S)** — Where NPU execution requires per-chipset compiled binaries (FR-ACC), those
are assets under this section, selected at runtime by detected chipset, with the CPU model as
the always-present fallback.

### 7.16 FR-PLT · Platform integration

**FR-PLT-1 (M)** — Required permissions, each requested in context with an explanation of
what breaks without it:

| Permission | Why | Optional? |
|---|---|---|
| `RECORD_AUDIO` | Capture | **No** — the app cannot function |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` | 8-hour background capture (D1) | **No** |
| `POST_NOTIFICATIONS` (API 33+) | The capture notification and alerts | Degrades safety; capture still runs |
| USB device permission | Rig control and USB audio | Yes — falls back to null module and other inputs |
| `ACCESS_FINE_LOCATION` | Geographic prior, POTA proximity (FR-LEX-22) | **Yes** — manual grid fallback |
| `WAKE_LOCK` | Sustained capture | **No** |

**FR-PLT-2 (M)** — **USB device permission is granted per attachment and is not persistent by
default.** The app SHALL request persistent access where the platform allows, and SHALL
detect and clearly surface the case where a mid-session re-attach requires re-granting —
this is a realistic way to silently lose an overnight run.

**FR-PLT-3 (M)** — The capture notification SHALL NOT display transcript text. It shows state,
elapsed time, transmission count and a stop action only. Radio traffic on a lock screen is an
unnecessary disclosure.

**FR-PLT-4 (M)** — The app SHALL function fully with notifications denied, surfacing state
in-app instead, but SHALL warn that silent failures become harder to notice.

**FR-PLT-5 (M)** — All app-private storage SHALL be excluded from cloud backup by default.
Captured audio must not sync to a cloud account (NFR-6, D2).

**FR-PLT-6 (S)** — Support Android's per-app language preference and respect system settings
for font scale, contrast and reduced motion.

### 7.17 FR-TST · Testability

Not a nicety — §14 cannot be executed without these, and they must exist in the shipping
architecture rather than a test fork.

**FR-TST-1 (M)** — The capture source SHALL be an **interface with a file-backed
implementation**, so a WAV file can be replayed through the full pipeline as though live, at
real time or faster. AC-6, AC-9, AC-36 and AC-41 all depend on this.

**FR-TST-2 (M)** — The clock SHALL be injectable, so thread gaps, the 10-minute ID window,
retention and decay can be tested without waiting.

**FR-TST-3 (M)** — The Rig Module interface SHALL have a **scriptable fake** that replays a
timed sequence of rig states, so frequency correlation and disconnection are testable without
hardware.

**FR-TST-4 (M)** — Inference SHALL be **deterministic** for a fixed model, input and
configuration — fixed seeds, no nondeterministic reduction order — so the evaluation harness
produces reproducible numbers (AC-35).

**FR-TST-5 (M)** — The evaluation harness SHALL run the complete pipeline offline over a
labelled corpus and emit machine-readable per-tier, per-lever metrics.

**FR-TST-6 (S)** — Provide a synthetic traffic generator (configurable activity fraction,
transmission length distribution, SNR) for load and endurance testing without an 8-hour tape.

### 7.18 FR-A11Y · Accessibility and language

**FR-A11Y-1 (M)** — The four attribution states (§4.1) SHALL be distinguishable **without
relying on colour** — shape, fill, label or icon. This is the requirement most likely to be
violated by a good-looking design, and P1 depends on it entirely.

**FR-A11Y-2 (M)** — All interactive elements SHALL carry content descriptions, and the reader
views SHALL be navigable and comprehensible with a screen reader. A log is text; there is no
excuse for it being inaccessible.

**FR-A11Y-3 (M)** — Text SHALL respect system font scaling without clipping or overlap, up to
the largest supported scale. The dense tabular log (P7) is the hard case.

**FR-A11Y-4 (M)** — Contrast SHALL meet WCAG 2.2 AA for text and for the state markers.

**FR-A11Y-5 (M)** — v1 ships **English (US) only**, but no user-visible string SHALL be
hardcoded, and date, time, number and frequency formatting SHALL be locale-aware.

**FR-A11Y-6 (M)** — The **phonetic alphabet variant set is content, not localization**, and
SHALL be extensible independently of app language. Regional and legacy variants must be
addable without a translation pass.

---

## 8. Data model

Field lists are functional, not a schema. Types and indices belong in the technical design.

**Session** — id, startedAt, endedAt, profileId, deviceTier, appVersion,
terminationReason (`user` | `crash` | `killed` | `storage` | `unknown`)

**Transmission** — id, sessionId, threadId?, startedAt, endedAt, durationMs,
audioRef, audioFormat, preRollMs, postRollMs, frequencyHz?, frequencyProvenance, mode?,
signalStrength?, channelName?, voiceprintId?, attributionState, stationId?,
attributionConfidence?, attributionSourceTransmissionId?, corrected (bool),
processingState (`CAPTURED` | `PROCESSING` | `COMPLETE` | `REJECTED` | `FAILED`),
rejectionReason?

> `processingState` takes its values from the FR-RUN-7 lifecycle and **only** from there.
> Draft 3 added that state machine and left this field carrying draft 2's independent set
> (`pending | complete | rejected | partial`), which shared no vocabulary with it and had no
> `FAILED` — so the data model could not represent the state F18 puts a record into. `STALE`
> is intentionally absent: it is *derived* from the stored pass fingerprints (FR-REP-2) rather
> than stored, so that a flag and its cause can never disagree.

**Transcript** — id, transmissionId, pass (`A` | `B` | `reprocess`), text,
modelId, modelVersion, quantization, decodeParams, noSpeechProb?, confidence?,
isCurrent (bool), createdAt

**PhoneticLattice** — id, transmissionId, source (`acoustic` | `text-derived`),
units (unit, startMs, endMs, score, alternatives[]), modelId, createdAt

**CallsignCandidate** — id, transmissionId, callsign, rank, score,
grammarValid (bool), ituPrefix, ituCountry, priorBreakdown (map of prior → contribution),
databaseHit (bool), selected (bool)

**Station** — id, callsign?, firstHeardAt, lastHeardAt, transmissionCount,
isUserPinned, notes?

**Voiceprint** — id, embedding, memberCount, centroidUpdatedAt, boundStationId?,
bindingConfidence, lastConfirmedAt

**Thread** — id, sessionId, startedAt, endedAt, frequencyHz?, transmissionCount,
participantStationIds[], digestText?

**LexiconVersion** — assetId, version, importedAt, recordCount, checksum

**Correction** — id, transmissionId, field, previousValue, newValue, correctedAt,
propagatedToCount

**CaptureGap** — id, sessionId, startedAt, endedAt, cause (`interruption` | `route_change` |
`device_lost` | `storage` | `unknown`), recoveredAutomatically (bool). *Silence that was never
listened to must be distinguishable from silence that was.*

**WorkQueueItem** — id, transmissionId, pass, state, attemptCount, lastError?,
enqueuedAt, startedAt?, shedLevel. Durable; survives process death (FR-RUN-2).

**Calibration** — id, modelId, tier, parameters, fittedAgainstCorpusVersion, fittedAt.
Referenced by every score-bearing record (FR-LEX-18).

**Asset** — id, kind (`model` | `lexicon` | `calibration` | `rig_descriptor` |
`npu_binary`), version, source, licence, checksum, sizeBytes, installedAt, activatedAt?,
verifiedAt, chipset? *(npu_binary only)*

**OperatorLocation** — profileId, gridSquare, source (`rig` | `gps` | `manual`), updatedAt.
Stored at grid-square precision only (FR-LEX-24).

**Session** additionally carries — `sourceId` (reserved for future multi-radio capture, Q6),
`schemaVersion`, `gapCount`, `shedEvents`.

**Transmission** additionally carries — `samplePosition` (authoritative timeline position,
FR-RUN-16), `monotonicStartNanos`, `utcOffsetMinutes`, `calibrationId?`,
`enhancementApplied` (set of passes), `executionProvider`, `isReprocessCandidate`.

---

## 9. The radio interface, in detail

D9 asks for a flexible interface extensible to any radio via modules. This section is the
contract; the technical design fills in transport specifics.

### 9.1 Three extension levels

| Level | Mechanism | Covers | Requires |
|---|---|---|---|
| **1 — Declarative descriptor** | A data file declaring commands and response patterns | ASCII CAT: Kenwood, Yaesu, Elecraft, most modern HF/VHF | No code, no rebuild |
| **2 — Code module** | Implements `RigModule` in-tree | Binary protocols: Icom CI-V, Uniden SDS remote | App rebuild |
| **3 — Manual** | The null module | Anything, including receivers with no data interface | Nothing |

### 9.2 Descriptor sketch

Illustrative of the shape required, not final syntax:

```yaml
id: kenwood-thd75a
displayName: Kenwood TH-D75A
transport: usb_serial
serial: { baud: 9600, dataBits: 8, stopBits: 1, parity: none }
capabilities: [FREQUENCY, MODE, SQUELCH_STATE]
poll:
  intervalMs: 500
  commands:
    - send: "FA;"
      expect: "^FA(\\d{11});"
      map: { frequencyHz: "$1" }
    - send: "MD;"
      expect: "^MD(\\d);"
      map: { mode: "$1" }
      lookup:
        mode: { "1": LSB, "2": USB, "3": CW, "4": FM, "5": AM }
```

**FR-RIG-11 (M)** — Descriptors SHALL be validated on load, with clear errors, and a failing
descriptor SHALL fall back to the null module rather than blocking capture.

**FR-RIG-12 (S)** — Provide a rig-connection test screen showing raw command/response
traffic, so a new descriptor can be developed on-device.

### 9.3 Verification status

**TH-D75A — substantially verified** from `thd75a programming details/TH_D75_Commands.pdf`
(KI4LAX, May 2024) already in this repo: two-letter ASCII CAT commands, CDC device class,
`BY` for squelch status. Remaining: the specific frequency-read command, VID/PID
confirmation under Android, and safe poll rate. This validates the level-1 declarative
descriptor design — the first radio needs no code.

**SDS150 — unverified.** Deferred to v2. Establishing whether Uniden's remote protocol is
ASCII or binary is what tests whether the three-level design in 9.1 is correct; if it is
binary, level 2 exists for exactly that reason.

See `open-questions.md` Q1.

---

## 10. Non-functional requirements

### 10.1 Accuracy

Targets are **per tier**. A single target across all hardware was the old framing's mistake:
it necessarily described the weakest device, which is not the product.

| Tier | Callsign precision (`CONFIRMED`) | Callsign recall | Overall WER |
|---|---:|---:|---:|
| **T3 — Reference** | **≥95%** | ≥85% | ≤20% |
| T2 — Full | ≥92% | ≥80% | ≤25% |
| T1 — Standard | ≥90% | ≥75% | ≤30% |
| T0 — Minimal | ≥85% | ≥60% | ≤45% |

**NFR-1 (M)** — Meet the table above, measured against a hand-labelled evaluation set of real
off-air traffic (M0).

**NFR-1a (M)** — **Precision is prioritised over recall at every tier.** Reporting no
callsign is acceptable; reporting the wrong one is not. Where a tier cannot meet its
precision target, it SHALL sacrifice recall — moving results to `AMBIGUOUS` or `UNKNOWN` —
rather than assert.

**NFR-1b (M)** — The precision floor SHALL NOT vary by tier by more than the table states.
**A weak device may know less; it may not be more wrong.** This is the requirement that
keeps T0 trustworthy rather than merely functional.

**NFR-1c (M)** — Each tier's numbers SHALL be measured and reported separately by the
evaluation harness, on the same evaluation set. A single aggregate number is not acceptable
evidence.

> **All accuracy targets are provisional until the evaluation set exists.** No published WER
> figure for any of these models on off-air amateur radio audio was found. The T3 targets
> additionally assume domain fine-tuning delivers on this audio something like what it
> delivers on ATC (55.2% → 6.8%); that transfer is plausible from a strong structural
> analogy and is **unproven**. Building the evaluation set is the first engineering task
> (section 15), and it is what converts every number in this table from a target into a
> measurement.

### 10.2 Latency

**NFR-2 (M)** — Pass B result visible within **2 s of segment close** for a 10-second
transmission at T1+, on the reference device.

**NFR-2a (M / T2+)** — Pass A first partial within **1 s of speech onset**.

**NFR-2b (M)** — The system SHALL keep up indefinitely at 15% channel activity, and SHALL
degrade gracefully (queue, then drop tier) rather than fail at 40%.

### 10.3 Endurance and power

**NFR-3 (M)** — ≥**8 hours** continuous unattended capture on a phone starting at 100%
battery, screen off, cellular disabled, at 15% activity.

**NFR-3a (M)** — Support indefinite operation while powered, including through a PD
passthrough hub.

**NFR-3b (S)** — Modelled draw ≤5 Wh over 8 hours at T2. *Estimate — see
`research/02` section 5.04; burst power is unmeasured.*

### 10.4 Reliability

**NFR-4 (M)** — No silent data loss. Any dropped audio, skipped segment or failed pass
SHALL be recorded and surfaced.

**NFR-4a (M)** — Unexpected termination SHALL lose at most the in-flight segment.

**NFR-4b (M)** — The database SHALL survive process kill mid-write.

### 10.5 Compatibility

**NFR-5 (M)** — Minimum Android API 26; target the current API level.

**NFR-5a (M)** — Functional at T0 on a device with 2 GB total RAM.

**NFR-5b (M)** — No *required* dependency on any specific SoC, NPU or vendor SDK. NPU
acceleration is an **optional** accelerator behind the execution-provider interface
(FR-ACC-2); every model the product ships SHALL have a working CPU path, and the app SHALL
be fully functional with no accelerator present.

### 10.6 Privacy and legal

**NFR-6 (M)** — No network access in the capture or processing path. Network is used only
for explicitly user-initiated actions (lexicon download, QRZ enrichment, export).

**NFR-6a (M)** — All data app-private by default.

**NFR-6b (M)** — Licensing of bundled model weights and lexicon data SHALL be documented and
compatible with both open-source distribution and eventual Play Store release (D11).

**NFR-6c (S)** — Surface a jurisdiction notice on first run regarding the legality of
recording radio transmissions, which varies by locality.

### 10.7 Reference and floor devices (D19)

| Role | Device | Why |
|---|---|---|
| **Reference** — NFR-1 T3, NFR-2, NFR-3 measured here | **OPPO Find X9 Ultra** · Snapdragon 8 Elite Gen 5 (SM8850-AC) · 12–16 GB · 7050 mAh · Android 16 / ColorOS 16 | Exact chipset of Qualcomm's published `large-v3-turbo` NPU benchmarks |
| **Floor** — NFR-5a, AC-26, AC-37 measured here | Any 2 GB Android 8+ device, ~$40 used | Proves T0 is real rather than theoretical |

**What the reference device gives the project.** Qualcomm publishes `large-v3-turbo` on
this exact silicon at 267–278 ms encoder per 30 s window and ~6.3 ms/token decoder. The ~22x
RTF figure in `research/04` §3 is therefore **a measurement on this device's chipset**, not an
extrapolation — the only headline number in the project with that property.

**Recalculated power budget.** NFR-3b assumed a 15–20 Wh phone; this one carries **7050 mAh,
roughly 27 Wh**. Over 8 hours at 15% activity, T3 NPU inference is close to free — 4,320
audio-seconds is about 144 encoder windows, roughly 39 s of accelerator time in total.
**Inference stops being the dominant term; capture, VAD and storage become it.**

> **Consequence: NFR-3's 8-hour target is no longer the binding constraint on this device.**
> Thermal behaviour and storage are. NFR-3 remains as the *portable* requirement — it must
> still hold on the floor device and on a mid-tier phone — but the reference device should be
> characterised for its actual endurance rather than tested against a floor it clears easily.

**NFR-7 (M)** — Measure and publish actual endurance on the reference device rather than
merely asserting the 8-hour pass. If it reaches 20+ hours, that is a product capability worth
knowing and worth stating.

### 10.8 The reference device's one serious problem

**ColorOS is among the most aggressive background-killers on the market**, and this is
directly in tension with D1 — the whole reason the project is on Android.

The specific hazard, and it invalidates an assumption in draft 3:

> **`PowerManager.isIgnoringBatteryOptimizations()` can return `true` while ColorOS kills the
> app anyway.** The standard API check *lies* on this platform. FR-SVC-5 as written — detect
> battery optimization, guide the user to exempt — is necessary and **not sufficient**.

Per DontKillMyApp, Oppo requires **four separate interventions**, only one of which is the
standard exemption:

1. Pin the app in the recent-apps screen
2. Enable it in the security app's startup manager and floating-app list
   (`com.coloros.safecenter`)
3. Disable battery optimization *(the only one the standard API sees)*
4. Run a foreground service with a persistent notification *(already required by FR-SVC-1/3)*

Plus, on ColorOS 6+: "Allow Auto Start-up" in app info, and Power Saver configured to permit
background operation.

**NFR-8 (M)** — Background-execution liveness SHALL be verified **empirically, not by API**.
A heartbeat written during capture and checked on next launch is the source of truth for
whether the session survived; `isIgnoringBatteryOptimizations()` is a hint only.

**NFR-9 (M)** — Onboarding SHALL provide **OEM-specific guidance**, detected from the device
manufacturer, deep-linking to the vendor's own settings screens where possible. A generic
"disable battery optimization" instruction is inadequate on the reference device.

**NFR-10 (M)** — A session that ends without a clean stop SHALL be reported on next launch
with its last heartbeat time, so a silent kill is visible as a specific loss rather than as a
mysteriously short log (FR-SVC-4, FR-SVC-6).

---

## 11. Reprocessing as an extensibility point

D2 makes the product on-device only, but explicitly requires reprocessing to be architected
in. This section defines what "architected in" means concretely.

**FR-REP-1 (M)** — Every pass SHALL be invocable over historical transmissions, not only
live ones.

**FR-REP-2 (M)** — Every stored result SHALL record the model, model version, lexicon
version, config and tier that produced it, so staleness is computable.

**FR-REP-3 (M)** — Transcripts SHALL be versioned per transmission, with exactly one marked
current. Superseding a transcript SHALL NOT destroy the previous one.

**FR-REP-4 (M)** — Retained audio SHALL be sufficient to re-run every pass. This constrains
the audio codec and retention policy: **audio retention shorter than the reprocessing
horizon defeats the mechanism**, and the UI SHALL say so when retention is shortened.

**FR-REP-5 (M)** — Provide an on-device reprocess action scoped by time range, frequency,
attribution state, or rejection reason — e.g. "re-run resolution over everything rejected as
ambiguous since the ULS import".

**FR-REP-6 (S)** — Lexicon update SHALL offer to re-run Pass D over affected historical
records without re-running ASR. This is cheap and is the highest-value reprocess.

**FR-REP-7 (C)** — Define an export format sufficient for an external system (a desktop
running `large-v3`) to reprocess and re-import. **Designing the format is in scope for v1;
building the desktop side is not.**

### 11.1 Cross-tier reprocessing

This is the mechanism that reconciles D3 (design for the best hardware) with D8 (weak devices
stay functional), and it is a v1 requirement rather than a future nicety.

**FR-REP-8 (M)** — Records produced below the device's current tier — because the tier was
lower at capture time, because thermal degradation occurred (FR-TIER-4), or because capture
outran processing (FR-TIER-7) — SHALL be identifiable as **reprocessing candidates**.

**FR-REP-9 (M)** — The user SHALL be able to reprocess candidates at the current tier, in
bulk, with progress and an estimate.

**FR-REP-10 (S)** — Where a database is imported onto a more capable device (FR-STO-6), the
system SHALL offer to reprocess everything eligible.

**FR-REP-11 (M)** — Reprocessing SHALL be interruptible and resumable, and SHALL never leave
a record in a worse state than before it started — a failed reprocess retains the previous
current transcript.

> **The user-facing consequence, which the UX guide should lead with:** capture on a cheap
> phone in the field, reprocess at home on the good one. Same app, same database. A low tier
> yields a *provisional* record, not a permanently degraded one.

---

## 12. Failure modes

Enumerated because most are silent, and each needs a test.

| # | Failure | Detection | Response |
|---|---|---|---|
| F1 | Audio routed to built-in mic instead of radio | `getRoutedDevice()` mismatch | Halt, visible error. **Never continue** |
| F2 | Input disconnected mid-session | Device callback / silence | Surface immediately, retry with backoff, keep session open |
| F3 | Input level too low or clipping | Rolling level statistics | Warn with a link to the level meter |
| F4 | Whisper hallucinates on squelch tail | Six controls, FR-ASR-5 | Mark `rejected`, retain audio, count in health stats |
| F5 | OEM kills the foreground service | Lifecycle log gap on next launch | Recover session, report, re-guide through battery exemption |
| F6 | Storage exhausted | Threshold monitor | Warn early; stop audio before text; never stop capture silently |
| F7 | Thermal throttling | Thermal status API + measured RTF drop | Degrade tier, surface it, restore when cool |
| F8 | Backlog grows unbounded on a busy band | Queue depth monitor | Degrade tier; if still growing, prioritise capture and mark deferred |
| F9 | Rig disconnects | Module health | Fall back to last-known frequency marked stale; keep capturing |
| F10 | Speaker cluster mis-merges two stations | User correction | Split cluster, re-derive, mark `CORRECTED` |
| F11 | Callsign resolved confidently but wrong | Only a human catches this | Always show lattice and candidates (FR-UI-8); make correction one tap |
| F12 | Lexicon import corrupt or partial | Checksum + record count | Reject import, keep previous version, report |
| F13 | Model file missing or incompatible | Load-time validation | Fall back to a lower tier model, surface it |
| F14 | Clock change / DST during a session | Monotonic clock for durations | Store both wall and monotonic time (FR-RUN-15, FR-RUN-18) |
| F15 | Incoming phone call or another app takes the mic | `AudioRecord` error / audio focus change | Record a CaptureGap, resume automatically (FR-RUN-11) |
| F16 | USB permission not persistent; re-attach mid-session needs re-granting | Device attach without permission | Surface loudly. **Realistic way to lose an overnight run** (FR-PLT-2) |
| F17 | Process killed while a transmission is mid-pass | `PROCESSING` state found at launch | Return to `CAPTURED`, re-queue. Passes are idempotent (FR-RUN-8) |
| F18 | A pass errors repeatedly on one segment | Bounded retry counter | `FAILED` with recorded error, visible, manually retryable. Never blocks the queue (FR-RUN-9, FR-RUN-10) |
| F19 | Audio file missing but DB row present, or vice versa | Reconciliation pass | Report both directions; never silently delete the surviving side (FR-AST-8) |
| F20 | Schema migration fails or loses a derived field | Migration tested against released-version fixtures | Never destroy audio or superseded transcripts; mark affected records for reprocessing (FR-AST-6) |
| F21 | Model or lexicon replaced mid-session | Asset activation guard | Defer activation to next session or reprocess (FR-AST-4) |
| F22 | Confidence scores drift from calibration after a model change | Reliability diagram in the harness | Refit calibration; it is a versioned asset, no app release needed (FR-LEX-18, FR-LEX-20) |

---

## 13. Interaction principles

Input to the visual/UX design guide. These are product rules, not visual direction.

**P1 — Uncertainty is content, not an error state.** The four attribution states are
first-class, designed deliberately, and always visible. A confident-looking UI over uncertain
data is the primary way this product could fail its user.

**P2 — Every machine conclusion is inspectable in one tap.** Why is this K7ABC? Because
these phonetic units were heard with these scores, this grammar parse succeeded, and these
priors ranked it first. That screen must exist.

**P3 — Correction is cheap and propagates.** One tap to fix, and the fix improves everything
downstream. A user who corrects ten attributions should see the eleventh already right.

**P4 — The capture state is never in doubt.** Is it running? Is it hearing the radio? How
long? One tap, always, plus the persistent notification.

**P5 — Live and record are visibly different.** Pass A partials must look provisional, and
their replacement by Pass B must be legible rather than a silent swap.

**P6 — Radio vocabulary, used correctly.** Transmission, over, QSO, net, repeater, simplex,
band, mode, callsign, POTA reference. The user is an expert; the interface should not
translate their domain into generic language.

**P7 — The log is scanned, not read.** Default view is dense and time-ordered. Timestamps,
frequencies and signal figures are tabular and aligned. Reading a thread is a deliberate
second-level action.

**P8 — Setup is a guided sequence, once.** Input selection with a verified route, level
setting against noise, battery-optimization exemption, optional rig connection. Each step
verifiable before proceeding.

**P9 — Nothing is deleted quietly.** Rejected segments, superseded transcripts and
overwritten attributions remain reachable. Retention deletion is announced in advance.

**P10 — Degradation is announced.** Tier drops, thermal throttling and backlog pressure are
visible when they happen, not discovered later in a log.

**P11 — A weaker device knows less; it is never more wrong.** Lower tiers reduce recall, not
precision (NFR-1b). The interface on a cheap phone shows fewer callsigns, not shakier ones,
and says so.

**P12 — Provisional results look provisional, and improving them is one action.** Records
processed below the device's current capability are visibly marked and reprocessable in
bulk. The framing throughout is "this can get better", never "this is broken". Capturing in
the field on a spare phone and improving it at home is a **designed workflow**, and the UX
guide should treat it as a headline capability rather than an edge case.

---

## 14. Acceptance criteria

Input to the test plan. Grouped by what a test would have to establish.

### 14.1 Capture

- **AC-1** With a USB audio adapter bound, `getRoutedDevice()` returns that device and
  capture proceeds. With the adapter unplugged mid-run, F2 fires within 5 s.
- **AC-2** Forcing a route mismatch halts capture with a visible error and does not record
  from the built-in mic (F1).
- **AC-3** A transmission beginning with a callsign in its first 300 ms is captured complete,
  demonstrating pre-roll (FR-CAP-4).
- **AC-4** 8-hour unattended run on the reference device completes with capture still
  running, no gaps in the transmission record, and battery consistent with NFR-3.
- **AC-5** The same run repeated with OEM restrictions *not* exempted is expected to fail, and
  the failure is detected and reported on next launch with its last heartbeat time (F5,
  NFR-10).
- **AC-64** **On the reference device, an 8-hour capture survives with all four ColorOS
  interventions applied, and the "prove it" 30-minute test correctly predicts that outcome**
  (§10.8, FR-SVC-5c). This is the acceptance gate for R5, the project's top risk.
- **AC-65** Liveness is determined by heartbeat, not by
  `isIgnoringBatteryOptimizations()`. Verified by forcing a state where the API returns `true`
  and the session is killed anyway (NFR-8, FR-SVC-5b).
- **AC-66** Onboarding shows Oppo-specific steps on the reference device and generic steps on
  a device with no known OEM quirks (NFR-9, FR-SVC-5a).
- **AC-97** With a USB adapter that offers only 48 kHz, capture succeeds and the resampled
  16 kHz stream is bit-identical across runs; the resampler identity is recorded on the session
  (FR-CAP-2a).
- **AC-98** Selecting the built-in mic deliberately captures successfully and is persistently
  labelled as such; a route that lands on the built-in mic when a USB device was selected halts
  (FR-CAP-3a, FR-RUN-13, F1).

### 14.2 Segmentation and hallucination

- **AC-6** Against a tape of pure squelch noise with no speech, the system emits **zero**
  accepted transcripts. Every segment is `rejected` with a recorded reason (F4).
- **AC-7** Each of the six hallucination controls is individually demonstrable with a
  crafted input.
- **AC-8** Rejected segments retain audio and are reachable in the UI.

### 14.3 Callsign resolution

- **AC-9** On the hand-labelled evaluation set, `CONFIRMED` precision meets **the active
  tier's row of the NFR-1 table** — 95/92/90/85% for T3/T2/T1/T0 — reported per tier and never
  as one aggregate. *Draft 3.1 stated a flat ≥90% here, which contradicted the per-tier table
  it cited, was unmeetable at T3 as a target and too lax at T0's precision floor, and directly
  violated NFR-1c and AC-36's prohibition on a single aggregate number.* See AC-36.
- **AC-10** A structurally valid callsign with a non-US ITU prefix and no database entry
  resolves as a candidate (FR-LEX-8). Test with a DX callsign absent from ULS.
- **AC-11** A structurally *invalid* parse (unallocated prefix) does not surface as a
  `CONFIRMED` attribution.
- **AC-12** Phonetic variants resolve identically: NATO, letter-name and legacy forms of the
  same callsign all yield the same result.
- **AC-13** With a rig-reported frequency matching a known repeater, ranking demonstrably
  shifts toward that repeater's known stations — and a station *not* on that list still
  resolves (FR-LEX-16).
- **AC-14** Candidate list and phonetic lattice are viewable for every resolved callsign
  (FR-UI-8).
- **AC-15** At T0, resolution still functions from text-derived lattices, with records
  correctly indicating the degraded source.

### 14.4 Identity and threading

- **AC-16** Given a QSO where station A identifies once and then transmits four more times
  without identifying, all five are attributed to A — one `CONFIRMED`, four `INFERRED`, each
  linking to the confirming transmission (FR-SPK-4).
- **AC-17** Correcting the attribution on any one of those five re-propagates to all, and the
  corrected records are flagged and immune to re-propagation (FR-SPK-7).
- **AC-18** Two genuinely different stations in one thread produce two Voiceprints and are
  not merged, at the default threshold, on real audio (FR-SPK-3).
- **AC-19** Transmissions below the duration floor are not clustered and remain `UNKNOWN`
  rather than being attributed (FR-SPK-2).
- **AC-20** A thread ends when the frequency changes beyond the configured gap (FR-SPK-5).
- **AC-67** A Voiceprint that has gone a configured period without a confirmed observation
  loses confidence, and cross-day identity requires re-confirmation **unless the station is
  enrolled in the voice library** (FR-SPK-9, FR-SPK-11).
- **AC-104** A station enrolled from confirmed observations across two sessions is recognised
  by voice in a **third** session before identifying, and is marked `INFERRED` — never
  `CONFIRMED` (FR-SPK-11, FR-SPK-13).
- **AC-105** A single confirmed observation does **not** enrol a voice (FR-SPK-12). Verified by
  confirming once and showing no enrolment.
- **AC-106** **Cross-session identification precision is measured separately** from
  within-session clustering, on the eval fold, and meets a stated false-match target
  (FR-SPK-14, FR-SPK-19). *A library that names the wrong regular every night is worse than one
  that names nobody — NFR-1b applies to identity as well as to callsigns.*
- **AC-107** Two enrolled voices that both match closely produce `AMBIGUOUS`, not an arbitrary
  pick (FR-SPK-15).
- **AC-108** Deleting a station destroys its stored embedding, verified at the database level
  (FR-SPK-16, FR-SPK-21).
- **AC-109** No voiceprint or embedding appears in a contribution payload, a diagnostic bundle
  or a cloud backup (FR-SPK-20).
- **AC-110** With the voice library disabled, the product functions fully and cross-session
  recall drops without any loss of precision (FR-SPK-22, NFR-1b).

### 14.5 Rig interface

- **AC-21** The null module supports a complete capture session with manual frequency
  (FR-RIG-2, FR-RIG-8).
- **AC-22** The TH-D75A module reports frequency, mode and squelch state, and transmissions
  carry frequency with provenance `rig` (FR-RIG-3, FR-RIG-9).
- **AC-23** A new radio can be added by supplying only a declarative descriptor, verified by
  adding a second radio during test (FR-RIG-4).
- **AC-24** An invalid descriptor falls back to the null module and does not block capture
  (FR-RIG-11).
- **AC-25** Rig disconnect mid-session leaves capture running with frequency marked stale
  (FR-RIG-7).
- **AC-68** Rig-reported squelch state overrides VAD for transmission boundaries while VAD
  still governs whether speech is present inside them (FR-SEG-5).

### 14.6 Tiers and degradation

- **AC-26** The app runs and captures on a 2 GB device at T0 (NFR-5a).
- **AC-27** Records from T0 and T3 have identical schema; a T0 record differs only by absent
  optional fields (FR-TIER-2).
- **AC-28** Under induced thermal load, the system degrades tier, surfaces it, recovers, and
  marks affected records as reprocessing candidates (FR-TIER-4).
- **AC-29** At 40% simulated channel activity the system does not fail; it queues, then
  degrades, and reports backlog.
- **AC-36** Each tier independently meets its own row of the NFR-1 table on the same
  evaluation set, reported separately (NFR-1c).
- **AC-37** **T0 precision does not fall below its floor even as recall drops.** Verified by
  forcing T0 on hard audio and confirming results move to `AMBIGUOUS`/`UNKNOWN` rather than
  becoming wrong (NFR-1a, NFR-1b).
- **AC-38** With no accelerator present, T3 features degrade to T2 and capture continues
  (FR-ACC-4, NFR-5b). Verified on a device with no supported NPU, confirming that no vendor
  SDK is on any required path.
- **AC-93** The app installs and runs on the minimum supported API level (NFR-5).

### 14.7 Reprocessing

- **AC-30** Pass D can be re-run over a historical date range without re-running ASR, and
  updates attributions.
- **AC-31** A superseded transcript remains retrievable.
- **AC-32** Shortening audio retention below the reprocessing horizon produces a warning that
  names the consequence (FR-REP-4).
- **AC-39** **A session captured at T0 and reprocessed at T3 produces results matching a
  session captured natively at T3**, within tolerance, on the same audio. This is the single
  test that validates the whole tier inversion (FR-REP-8, §6.1).
- **AC-40** A reprocess interrupted mid-run leaves every record either updated or unchanged,
  never empty (FR-REP-11).

### 14.8 Accuracy levers

- **AC-41** A fine-tuned model measurably outperforms the stock model of the same size on the
  evaluation set, at every tier including T0 (FR-ASR-9, D13).
- **AC-42** Ensemble fusion of Pass A and Pass B outperforms the better of the two alone
  (FR-ASR-12).
- **AC-43** LLM rescoring cannot emit a token outside the supplied candidate set. Verified
  adversarially with a candidate set deliberately excluding the correct answer — the system
  must return a wrong candidate, never invent the right one (FR-ASR-16, D5).
- **AC-44** Speech enhancement can be enabled per pass, and the harness reports its effect on
  each pass separately, including where that effect is negative (FR-ENH-2, FR-ENH-3).

### 14.8a Runtime and resilience

- **AC-45** With all passes artificially stalled, capture continues indefinitely and every
  segment reaches the durable queue (FR-RUN-1, FR-RUN-2).
- **AC-46** Under induced overload, the system sheds in the documented order and **no audio is
  lost at any level**. Verified by comparing captured segment count against a known input
  (FR-RUN-3, NFR-4).
- **AC-47** Killing the process mid-pass and relaunching returns `PROCESSING` records to
  `CAPTURED` and completes them, with identical results to an uninterrupted run (FR-RUN-8).
- **AC-48** A simulated incoming call during capture produces a `CaptureGap` with correct
  bounds, and capture resumes automatically (FR-RUN-11, F15).
- **AC-49** A gap is visually distinguishable from genuine silence in the timeline
  (FR-RUN-12).
- **AC-50** Rig state is correlated to audio within 250 ms; exceeding that downgrades
  frequency provenance to `inherited` (FR-RUN-17).
- **AC-51** A repeatedly failing pass lands in `FAILED` with a recorded error and does not
  block the queue (FR-RUN-10, F18).
- **AC-99** A pass that **hangs** — verified with an injected non-returning inference call — is
  cancelled at its timeout, marked `FAILED`, and the queue continues draining. Capture is
  unaffected throughout (FR-RUN-10a).

### 14.8b Assets, calibration and migration

- **AC-52** A corrupt asset import leaves the previous version active and reports clearly
  (FR-AST-2, F12).
- **AC-53** Migration from every previously released schema version succeeds against fixtures,
  preserving audio and superseded transcripts (FR-AST-5, FR-AST-6).
- **AC-54** Reconciliation detects both an orphaned audio file and a dangling reference, and
  deletes neither (FR-AST-8).
- **AC-55** **A confidence of 0.9 corresponds to approximately 90% observed accuracy** on the
  eval fold, demonstrated by a reliability diagram per tier (FR-LEX-17, FR-LEX-20).
- **AC-56** Raising the tier's precision target moves the `CONFIRMED` threshold; the user is
  never asked to set a raw score (FR-LEX-19).

### 14.8c Platform, accessibility and privacy

- **AC-57** With location permission denied, everything functions on a manually entered grid
  square, losing only geographic-prior precision (FR-LEX-23).
- **AC-58** Where the rig reports position, no Android location permission is requested at all
  (FR-LEX-22).
- **AC-59** No network traffic originates from the app during a complete capture-and-process
  cycle, verified by packet capture (NFR-6, FR-OBS-5).
- **AC-60** Captured audio does not appear in cloud backup (FR-PLT-5).
- **AC-61** The notification never contains transcript text (FR-PLT-3).
- **AC-62** **The four attribution states are distinguishable in greyscale** and under
  simulated colour-vision deficiency (FR-A11Y-1).
- **AC-63** The log view is navigable and comprehensible via screen reader, and renders without
  clipping at maximum system font scale (FR-A11Y-2, FR-A11Y-3).

### 14.8d Segmentation quality

Segmentation is upstream of everything — a bad boundary corrupts the transcript, the lattice,
the embedding and the thread simultaneously — and draft 3 had no criteria for it.

- **AC-69** Against a hand-marked tape, detected transmission boundaries fall within a stated
  tolerance of the true keying boundaries, reported as precision and recall on *boundaries*
  rather than on words (FR-SEG-1).
- **AC-70** A stuck carrier does not produce an unbounded segment; it is split at the
  configured maximum length (FR-SEG-3).
- **AC-71** Two transmissions separated by less than the minimum silence are not merged into
  one, and a single transmission containing a natural pause is not split (FR-SEG-2). *This is
  the trade the VAD settings actually control, and it must be measured rather than tuned by
  ear.*
- **AC-72** Segments below the duration floor are recorded as `rejected:too_short` without any
  ASR model being invoked (FR-SEG-6).
- **AC-94** **Segmentation is bit-identical across tiers.** The same tape segmented at forced
  T0 and forced T3 produces the same boundaries to the sample (FR-SEG-7). *This is the
  precondition AC-39 silently assumed.*
- **AC-95** A transmission whose speech begins before the VAD trigger and ends after the VAD
  close is retained complete, demonstrating pre-roll and post-roll together (FR-SEG-8).
- **AC-96** Where continuous-archive mode is enabled, a session can be **re-segmented** with
  different VAD parameters and produces a different, valid set of transmissions from the same
  archived stream (FR-SEG-9).

### 14.8e Latency

NFR-2 stated latency targets that nothing tested.

- **AC-73** At **T1 and above**, a 10-second transmission produces a visible Pass B result
  within **2 s of segment close**, measured at the 95th percentile over a representative
  session, not as a mean (NFR-2). Measured on the reference device for T2/T3 and on the floor
  device — or with the tier forced down — for T1, since NFR-2 binds the tier, not the handset.
  *Draft 3.1 wrote T2+ here against NFR-2's T1+, which would have left the T1 latency budget
  untested.*
- **AC-74** At T2+, the first Pass A partial appears within **1 s of speech onset** (NFR-2a).
- **AC-75** At 15% simulated activity the system keeps up indefinitely with no backlog growth
  over a sustained run (NFR-2b).
- **AC-76** Measured endurance on the reference device is recorded and published, not merely
  asserted to clear 8 hours (NFR-7).

### 14.8f Storage, retention and configuration

- **AC-77** Retention deletes audio at the configured age while retaining transcripts, and
  announces deletions in advance (FR-STO-3, P9).
- **AC-78** Approaching storage exhaustion stops audio writes before text writes and never
  stops capture silently (FR-STO-4, F6).
- **AC-79** A pinned thread survives a retention pass that would otherwise delete it
  (FR-STO-7).
- **AC-80** Full database and audio export completes and re-imports on another device,
  offering reprocessing for anything eligible (FR-STO-6, FR-REP-10).
- **AC-81** Captured audio is not exposed to the system media store (FR-STO-8).
- **AC-82** Switching capture profiles applies audio, VAD, model, lexicon and rig settings
  together in one action (FR-CFG-1).
- **AC-83** A profile exported from one install imports into another, and a profile from a
  newer app version fails cleanly rather than partially applying (FR-CFG-4, FR-AST-7).

### 14.8g Digest

Goal G1 is the primary user-facing deliverable and had no acceptance criteria at all.

- **AC-84** The deterministic digest generates with **no LLM present**, covering stations
  heard with confidence counts, new stations, activity by frequency and hour, threads, POTA
  references and anomalies (FR-DIG-1, FR-DIG-2).
- **AC-85** **The digest distinguishes confirmed from inferred stations.** A digest that
  flattens the distinction fails G4 as surely as a log that does (FR-DIG-2, D7).
- **AC-86** Where the LLM digest is enabled, it *adds to* rather than replaces the
  deterministic digest, and its output is visually distinguished (FR-DIG-3, FR-DIG-6).
- **AC-87** LLM digest generation never runs during active capture, and only when the device
  is idle, charging and thermally unconstrained (FR-DIG-5).
- **AC-88** G1 is met end to end: after an unattended overnight run, the digest conveys what
  happened **in under two minutes of reading**, verified against the hand-labelled tape.

### 14.9 Export

- **AC-33** An `INFERRED` attribution exports with its state; a confirmed-only export omits
  it entirely (FR-EXP-4, FR-EXP-5).
- **AC-34** ADIF export imports cleanly into standard logging software.

### 14.10 Evaluation harness

- **AC-35** A reproducible harness exists that runs the full pipeline over the evaluation
  tape and reports, **per tier and per lever**: WER, callsign precision/recall, rejection rate
  by reason, and attribution accuracy (FR-TST-5). **This harness is a v1 deliverable, not a
  test artifact** — every number in section 10 is a target until this exists, and the
  per-lever breakdown is what decides whether ensemble fusion, rescoring and enhancement earn
  their complexity.
- **AC-89** A WAV file replays through the complete pipeline as though live, and faster than
  real time, producing identical results to a live capture of the same audio (FR-TST-1).
  *Every other criterion in §14 that uses a known input depends on this one.*
- **AC-90** Two harness runs over the same corpus, model and configuration produce **identical
  output** (FR-TST-4). Without this, no measured improvement can be distinguished from noise.
- **AC-91** Time-dependent behaviour — thread gaps, the 10-minute ID window, retention,
  voiceprint decay — is testable via clock injection without waiting in real time (FR-TST-2).
- **AC-92** A scripted fake rig replays a timed state sequence, exercising frequency
  correlation and disconnection with no hardware attached (FR-TST-3).
- **AC-100** The harness refuses to run against the `eval` fold without an explicit opt-in
  flag, and every report states which fold produced it (FR-TST-7). *A number whose fold is
  unstated is not evidence.*
- **AC-101** AC-6 is demonstrated against the **development** noise tape from M3 onward, and
  re-demonstrated against the sealed eval noise tape at M11, with both results reported
  (FR-TST-7, §14A.2).
- **AC-102** A lossy retention codec is adopted only with a harness comparison against
  lossless retention on the same fold, reporting callsign precision and recall for each
  (FR-STO-2b). Absent that report, the build retains losslessly (FR-STO-2a).
- **AC-103** At each tier, the sum of concurrently resident model memory is measured and falls
  within that tier's resident budget (FR-TIER-8, §6.2).

---

## 14A. Project risk register

Distinct from §12, which enumerates *runtime* failures. These are risks to the project.

| # | Risk | Likelihood | Impact | Mitigation | Gate |
|---|---|---|---|---|---|
| R1 | ~~Fine-tuned model cannot be exported to the runtime's ONNX form~~ **Largely resolved — see §14A.1** | Low | Medium | Fine-tune a base already in the export enum (D20); merge the LoRA before exporting | M0a, still verify once |
| R2 | ATC fine-tuning gains do not transfer to amateur radio audio | Medium | High — T3 accuracy targets become unreachable | Measure on the **dev** fold before committing to the numbers in §10.1; confirm on eval at M11 | M0a (dev) / M11 (eval) |
| R3 | **Audio-level resolution does not beat text-level** | Medium | High — invalidates D4 and the core architecture | M4 is explicitly designed so the comparison is the deliverable. Fall back to the text path and bank the saved complexity | M4 |
| R4 | Speaker embeddings do not separate on narrowband off-air audio | Medium | Medium — threading degrades, per-transmission attribution survives | Test on the M0 tape before building M6. Attribution remains correct, just less complete | M6 |
| R5 | **OEM background-killing defeats 8-hour capture** | **High — the reference device runs ColorOS** (D19) | **Severe — the product does not work** | §10.8. Empirical heartbeat liveness rather than API check (NFR-8, FR-SVC-5b); manufacturer-specific onboarding (NFR-9); the "prove it" test run (FR-SVC-5c). M2 proves it before any ASR exists | **M2 — now the highest-priority risk** |
| R6 | Scope growth from the reference-tier levers | **High** | Medium — indefinite schedule | M11 is last, each lever independently measured, with a stated kill rule (Q12) | M11 |
| R7 | Solo build stalls on an unfamiliar subsystem — NPU toolchain, DSP, migration | Medium | Medium | Every hard subsystem has a working fallback by design: CPU path, null rig module, text-derived lattice | Continuous |
| R8 | Evaluation set is too small or contaminated | Medium | **Severe** — every number becomes unfalsifiable | Split **train / dev / eval** by session **before** labelling (Q10, §14A.2). Hold the eval fold until M11, enforced by the harness rather than by memory (FR-TST-7) | M0 |
| R9 | Model or lexicon licensing blocks the Play Store path | Low | Medium — forecloses D11 | Record licence per asset from day one (FR-AST-1, FR-LEX-28) | M0a |
| R10 | **Lossy audio retention silently caps Pass C, and the damage is invisible until M4** | Medium | **High — reads at M4 as the core thesis failing when it is the codec failing** | Lossless retention until measured (CON-STO-1, FR-STO-2a..c). The codec decision is made in M2; the pass that cares is measured in M4 | **M2 decision, M4 measurement** |
| R11 | **Segmentation errors are permanent** — no tier, model or later rig connection can recover a clipped or merged transmission | Medium | Medium–High — a silent, uncorrectable accuracy floor under every other number | Tier-invariant segmentation (FR-SEG-7), generous pre/post-roll (FR-SEG-8), optional continuous archive (FR-SEG-9), boundary metrics from M2 (AC-69) | M2 |
| R16 | **Hallucinated biography** — the LLM writes a plausible, wrong claim about a real person into their permanent station record, and nobody who could contradict it ever sees it | Medium where topic summaries are enabled | **High** — categorically worse than a wrong callsign, because it is about a person, it persists, and the reader cannot check it | Facts and summaries kept structurally separate (FR-DIG-7/8); summaries as reported speech, attributed to transmissions, visibly marked (FR-DIG-11); no inference about persons at all (FR-DIG-12); local-only (FR-DIG-13); summaries are optional and tier-gated | M9 |
| R15 | **The voice library mislabels a regular, persistently** — a false cross-session match binds the wrong callsign to a voice and repeats it every night, and the user may not notice because it looks like the system working | Medium — it rests on R4, which is itself unproven on narrowband off-air audio | **High** — a confident, durable, wrong attribution is the exact failure G4 exists to prevent, and it is worse than the per-session version because it accumulates | Enrolment needs multiple confirmations across sessions (FR-SPK-12); cross-session matches are `INFERRED` only (FR-SPK-13); a stricter, *measured* threshold (FR-SPK-14); ambiguity rather than a pick (FR-SPK-15); separate precision reporting (FR-SPK-19); one-tap correction that de-enrols (FR-SPK-17) | M6 |
| R13 | **Synthetic-to-real gap** — a system tuned on TTS-derived callsign audio scores well on synthetic and poorly on real traffic | **High** — this is the expected failure mode of D21/D22, not a tail risk | Medium–High: accuracy claims collapse if not caught | Per-source metrics (FR-TST-8), synthetic barred from eval (FR-TST-9), real-speech splicing rather than pure TTS (D22), and the ~1 h real validation set exists precisely to catch this | M0 / M3 |
| R14 | **Contributed audio carries third-party voices and callsigns** whose owners never consented, in a product that may be open-sourced | Medium | Medium–High — reputational and possibly legal, and irreversible once published | Access-controlled by default, no republication without a separate decision, corrections-without-audio preferred (FR-CON-6, FR-CON-7), jurisdiction notice (NFR-6c). Q17 must close before contribution is switched on | Before contribution ships |
| R12 | An English-only base model (`distil-small.en`, per D20) meets worldwide DX traffic (D12) — accented English and non-English speech | Medium | Medium — recall drops on exactly the HF/DX material the eval fold is loaded with | Measure DX separately from local traffic; the grammar path (FR-LEX-8) does not depend on the prose being right; a multilingual base remains selectable per profile (FR-ASR-11) | M0a / M4 |

**R5 is now the top risk**, because the reference device was chosen for its accuracy ceiling
and carries the worst background-execution behaviour on the market. It is also the earliest
to test — M2 exists precisely to settle it before any model is in the picture.

**R3 and R8 follow**, and both are cheap: R8 is a decision made before labelling (§14A.2),
R3 is a milestone already planned.

### 14A.1 R1 resolved: the fine-tune export path

The concern was that a LoRA fine-tune might not export into the ONNX form sherpa-onnx
consumes, which would remove the project's largest lever and reopen the runtime choice.
**It does not, provided one constraint is respected (D20).**

The reasoning, in three steps:

1. **`export-onnx.py --model` accepts a fixed enum**, not an arbitrary path — this is the
   real limitation and the source of the concern.
2. **But that enum already includes every `distil-*` variant**, which are not OpenAI releases.
   The script therefore already exports non-OpenAI weights through the same graph; it is the
   *checkpoint selection* that is constrained, not the export machinery.
3. **A LoRA merged with `merge_and_unload()` is structurally identical to its base model** —
   merging collapses the adapter into the base weight matrices, and merging is the documented
   recommendation precisely when exporting to a format that ignores adapters, such as ONNX or
   GGUF.

So a fine-tune of, say, `distil-small.en` produces a checkpoint the existing export path
already handles. The remaining work is substituting your merged checkpoint where the script
expects the named download — plumbing, not a blocker. At least one user has independently
reported converting a fine-tuned Whisper to ONNX with their own script.

**Therefore D20: choose a fine-tune base that is already in the export enum.** This costs
nothing — `distil-small.en` was already the leading candidate for T1/T2 — and it converts the
project's biggest risk into a naming detail.

**Still verify once, early**, on a throwaway 10-minute fine-tune, before investing in
labelling. Confirming the round trip end-to-end is an hour and retires the risk completely.

### 14A.2 R8 resolved: how to split the evaluation set

The concern was contamination — measuring accuracy on data the model was trained on, which
makes every number in §10 meaningless. The rule that prevents it is not obvious, so it is
specified here rather than left to judgement.

**Split by recording session, never by transmission.**

Splitting randomly across transmissions leaks in three ways at once: the same *voice*, the
same *channel conditions*, and the same *conversation* appear in both folds, so the model is
scored on speakers and audio it effectively trained on. A model that memorised three local
repeater regulars would look excellent and generalise to nothing.

The concrete recipe:

| Step | Rule |
|---|---|
| 1 | Record in **discrete sessions** — different days, times, bands, and radios. Aim for 8–12 distinct sessions rather than one long tape |
| 2 | Assign **whole sessions** to train or eval. Never split a session |
| 3 | Target roughly **70/30 train/eval by duration** |
| 4 | Ensure the eval fold contains **at least one session with no station appearing in train** — this is the only measurement of true generalisation |
| 5 | The **noise tape** (squelch, no speech) goes in **eval only**. AC-6 must be measured on noise the model never saw. **Record a second, separate development noise tape** — see below; without it this rule makes AC-6 unrunnable for the whole build |
| 6 | Put **HF/DX traffic in eval**, since non-US callsigns test FR-LEX-8 — the grammar path that works without a database |
| 7 | Record the assignment in a manifest committed alongside the audio, so it cannot drift |
| 8 | **Do not look at the eval fold** until M11. Not for debugging, not for "a quick check" |

**If in doubt, put a session in eval.** Under-training costs a few WER points that more data
later recovers; contaminating the eval fold destroys the ability to measure anything, and it
is not recoverable — you cannot un-see it.

#### The development fold, and why sealing everything is a trap

Rules 5 and 8 above are correct and, taken together as drafted, they made the project's single
most important test unrunnable for its entire duration. AC-6 — *zero accepted transcripts from
pure squelch noise* — is the acceptance gate for F4, the #1 practical failure mode, and it is
what M3 exists to satisfy. Step 5 puts all the noise in eval; step 8 seals eval until M11.
**So the hallucination controls would have been tuned blind and first measured at the last
milestone**, which is exactly backwards for the highest-risk, easiest-to-get-wrong subsystem in
the pipeline.

The same trap applies more weakly to everything else: a build with no measurable audio at all
cannot tell progress from regression.

**The fix costs one afternoon**, because noise needs no labelling:

| Fold | Content | Sealed? | Used for |
|---|---|---|---|
| **train** | ~50% of labelled sessions by duration | No | Fine-tuning (M0a) |
| **dev** | ~20%, **as whole sessions**, plus a separately recorded 20-minute development noise tape | No | Day-to-day measurement, threshold and calibration fitting, AC-6 during M2–M10, regression detection |
| **eval** | ~30%, the HF/DX material, and the **eval noise tape** | **Yes, until M11** | Every number in §10, reported once |

**The dev fold is whole sessions, for exactly the reason the eval fold is** (step 2). An
earlier formulation of this table said "a held-out slice of train", which — read as a
transmission-level slice — reintroduces all three leaks step 2 exists to prevent, on the fold
used to tune every threshold in the system. Thresholds fitted against a leaky measurement are
fitted to the wrong thing, and the error is invisible until the eval fold is opened at M11.

Concretely, with 10 recorded sessions: **5 train, 2 dev, 3 eval**, and the two noise tapes are
additional to that count. If the tape runs short, take from train first — the ATC precedent
reached a 54.8% relative reduction from 55 clips, so the training fold is the one that
tolerates being small.

**FR-TST-7 (M)** — The corpus manifest SHALL carry three folds, not two, and the harness SHALL
refuse to run against `eval` unless explicitly and loudly opted in. Sealing by discipline alone
does not survive a debugging session at 2 a.m.; the tool should enforce it.

The two noise tapes must be **separately recorded** — different session, different squelch
tails, ideally a different day — not two halves of one file, for the same reason the speech
folds split by session.

> The ATC precedent reached a 54.8% relative WER reduction from **55 clips**, so the training
> fold does not need to be large. Given a 3–5 hour tape, spending the extra material on a
> clean, generous eval fold is the better trade.

---

### 14A.3 The corpus, rebuilt around public data (D21)

Drafts 1–3.2 assumed the corpus had to be recorded and hand-labelled, and priced M0 at 15–45
hours of labelling on that basis. A search for existing sources changed the picture: **most of
what the corpus was needed for already exists, free — and the one thing it was most needed for
does not exist anywhere.**

| Source | Size | Licence | What it covers |
|---|---|---|---|
| **Paderborn HF Ham Radio DB** | 176 h (121/18/37), 13.9% speech | CC BY 4.0 | **Real off-air amateur HF**, parallel clean+degraded. Channel character, VAD/SAD, enhancement, real off-air noise |
| **Fearless Steps (Apollo-11)** | 19,000 h, 600+ speakers | NASA guidelines, free | Degraded analog comms loops **with diarization labels** — speaker separation on bad narrowband audio |
| **ATC (ATCO2 + UWB-ATCC merge)** | Tens of hours | Free | ICAO phonetic alphabet, callsign grammar, procedural phraseology |
| **Existing ATC Whisper fine-tunes** | — | Free | A measured answer to R2 without training anything |
| **ISOLET** | 7,800 letters, 150 speakers | CC BY 4.0 | Spoken **letter-name** forms (AC-12's second pronunciation path) |
| **Synthetic (D22)** | Unbounded | Generated | Callsigns in phonetic form, at volume, ground truth free by construction |
| **Recorded amateur traffic** | ~1 h | Yours | **The only source of real conversational callsign exchanges.** Validation, not training |

**The inversion:** the tape stops being the training set and becomes the reality check. Its job
is no longer to teach the model anything — public data does that — but to answer one question
the public data cannot: *does this work on real amateur traffic?*

**FR-TST-8 (M)** — Every corpus source SHALL be a fold-tagged, versioned, licence-recorded
entry in the manifest, and the harness SHALL report metrics **per source as well as in
aggregate**. A model that scores well on synthetic audio and badly on real traffic is the
expected failure mode of this strategy, and only per-source reporting makes it visible.

**FR-TST-9 (M)** — Synthetic data SHALL NEVER appear in the eval fold. Accuracy claims are
about reality; a number measured on generated audio measures the generator.

## 15. Release plan

Ordered by dependency and by information value. Each milestone answers a question that
changes what comes after. **Expanded into executable work in
[`implementation-plan.md`](implementation-plan.md)**; this section remains the authority on
sequence and rationale, that document on task breakdown and exit criteria.

**Shaped by D18 (solo, heavily AI-assisted).** Implementation throughput is high, so the
plan front-loads **interfaces, testability and decision gates** — the things that are
expensive to retrofit and that AI assistance does not make safer. Every milestone ends with a
working app, and every milestone that produces a number ends with that number measured rather
than asserted.

### M0 — Evaluation and training set (blocking everything)

Record 3–5 hours of real traffic from the TH-D75A and the SDS150, hand-label callsigns,
frequencies, speaker turns and thread boundaries. **Nothing in section 10 is verifiable
without this, and every accuracy claim in all four research documents is transferred from
another domain.** Highest-value work in the project, and it needs no code.

> **This milestone got more valuable, not just more urgent.** The ATC literature reached
> 13.7% WER — a 54.8% relative reduction — from **55 hand-transcribed clips**. The same
> labelled tape is therefore both the evaluation set *and* the fine-tuning set for M0a. One
> labelling effort, two deliverables, and the second is the largest accuracy lever in the
> project. Split the tape into **train / dev / eval** folds before doing anything else with it
> (§14A.2), and record **two** noise tapes — one for the sealed eval fold, one for daily use.
> The second one costs an afternoon and is what makes AC-6 testable before M11.

Record losslessly (FR-STO-2a). The corpus is the one artifact in the project that can never be
re-derived, it is the input to every accuracy number, and re-recording it because it was
archived through a perceptual codec is the most expensive avoidable mistake available here.

### M0a — Domain fine-tune (D13)

LoRA fine-tune the chosen Pass B model on the M0 training fold; measure against **the dev
fold**. Offline work on a desktop or rented GPU, producing a model file that improves **every
tier including T0**.

> *Drafts 1–3.1 said "measure against the eval fold" here, and R2's gate column said the same
> — both written before §14A.2 sealed the eval fold until M11. Taken together the spec
> instructed the reader to open the sealed fold at the **second** milestone, which would have
> destroyed the ability to measure anything for the remaining nine. This is the general defect
> §14A.2 introduced: the sealing rule arrived in draft 3 and was never reconciled with the
> requirements and milestones written before it. Every "measure against eval" earlier than M11
> now reads "dev", and the eval fold is opened exactly once.*

**First check, before anything else:** confirm a fine-tuned checkpoint exports into the ONNX
form sherpa-onnx consumes (`research/03` §8 T5). If it does not, L1 is unavailable on the
chosen runtime and the runtime decision reopens. This is a half-day of work that gates the
project's biggest lever.

### M1 — Lexicon resolver, off-device

Build Pass D — grammar, ITU table, priors, confusion-weighted matching — as a standalone
component tested against M0 transcripts. This is platform-independent, is the largest single
accuracy gain available, and can be developed on a desktop in this repo alongside the
existing WWARA and POTA data.

### M2 — Capture spine and runtime skeleton

Foreground service, audio route binding and verification, VAD segmentation, Opus storage,
SQLite schema with migrations, the **durable work queue and transmission lifecycle**
(FR-RUN-1..10), **interruption and gap handling** (FR-RUN-11..14), the clock model
(FR-RUN-15..18), permissions flow, and the capture status UI. **No ASR at all.**

Two reasons this is bigger than it looks and worth doing first:

1. It proves the 8-hour requirement and the OEM background-kill risk (R5) in isolation,
   before any model exists to confuse the diagnosis.
2. **It establishes the interfaces every later milestone plugs into** — the capture source
   (FR-TST-1), the clock (FR-TST-2), the pass contract, the queue. Under D18 these are
   exactly the decisions that are cheap now and expensive later, and they are the ones AI
   assistance does not make safer.

Ship FR-TST-1's file-backed capture source **in this milestone**, not later. Every subsequent
milestone is tested through it.

### M3 — Pass B and hallucination control

Offline ASR, the six controls, transcript versioning. First end-to-end useful output.

### M4 — Pass C and audio-level resolution

Phonetic unit spotting, lattice, integration with M1's resolver. **The core accuracy thesis
is proved or disproved here** — measure against M0 and compare to M3's text-only path.

### M5 — Reader and search

Live view, thread view, search, playback, correction, inspection surfaces.

### M6 — Identity and threading

Speaker embeddings, clustering, back-propagation, correction propagation.

### M7 — Rig interface

Null module, descriptor engine, TH-D75A descriptor.

### M8 — Pass A streaming

Streaming Zipformer with hotword biasing, live partial display.

### M9 — Digest and export

Deterministic digest, ADIF/CSV/POTA export.

### M10 — Tier system and cross-tier reprocessing

Detection, degradation, T0 validation on constrained hardware, and the reprocessing candidate
flow (FR-REP-8..11). AC-39 — a T0 capture reprocessed at T3 matching a native T3 capture — is
the acceptance gate for the whole tier inversion.

### M11 — Reference-tier levers (T3)

NPU execution provider (FR-ACC), ensemble fusion (FR-ASR-12), n-best rescoring (FR-ASR-15),
enhancement evaluation (FR-ENH). **Sequenced last deliberately**: each is measured against
the harness and kept only if it earns its complexity. None of them is required for a good
product; all of them are what make the reference experience world-class.

> **Two decision points, and they are different in kind.**
>
> **M4 decides the architecture.** If audio-level resolution does not measurably beat
> text-level resolution on the M0 tape, collapse to the simpler text path and spend the saved
> complexity elsewhere. Design the milestone so that comparison is the deliverable.
>
> **M11 decides the ceiling.** Each lever is independently measurable and independently
> droppable. Expect some to fail — enhancement is genuinely contested, and rescoring is
> unproven on this audio. Keeping a lever that does not measure is worse than never building
> it, because it costs complexity forever.

---

## 16. Traceability

| Goal | Requirements |
|---|---|
| G1 Know what happened | FR-DIG-1..6, FR-UI-1, FR-UI-2 |
| G2 Find a conversation | FR-STO-1, FR-UI-3, FR-UI-5, FR-UI-9, FR-UI-10 |
| G3 Capture callsigns | FR-LEX-1..16, FR-EXP-1..6 |
| G4 Trust the record | FR-SPK-10, FR-UI-4, FR-UI-8, FR-ASR-6, FR-LEX-12, FR-EXP-4, P1, P2 |
| G5 Set it up once | FR-CAP-2..6, FR-SVC-1..8, FR-CFG-1, P8 |

| Decision | Requirements |
|---|---|
| D1 Android only | FR-SVC-1..8, NFR-5 |
| D2 On-device only | NFR-6, FR-REP-1..7 |
| D3 Flagship-first design | §6.1, FR-TIER-1..7, FR-ASR-12..16, FR-ACC-1..6, NFR-1 table |
| D4 Lexicon vs audio | FR-LEX-4, FR-LEX-5, FR-LEX-7, FR-ASR-4 |
| D5 Deterministic callsigns | FR-DIG-4, FR-LEX-7..12 |
| D6 Live + accurate | FR-ASR-2, FR-ASR-3, P5 |
| D7 Visible confidence | FR-SPK-10, FR-UI-4, FR-EXP-4, P1 |
| D8 Lower tiers stay functional | FR-TIER-2, FR-TIER-6, FR-TIER-7, FR-REP-8..11, NFR-1b, NFR-5a, AC-39 |
| D13 Fine-tuning first-class | FR-ASR-8..11, M0a, AC-41 |
| D14 NPU as optional accelerator | FR-ACC-1..6, FR-AST-9, AC-38 |
| D15 Stack chosen | §17, FR-RUN-*, FR-AST-5..7 |
| D16 Never drop audio | FR-RUN-1..6, NFR-4, AC-45, AC-46 |
| D17 Location sourcing | FR-LEX-22..24, FR-PLT-1, AC-57, AC-58 |
| D18 Solo, AI-assisted | §15 preamble, M2 scope, FR-TST-1..6 |
| D9 Modular rig | FR-RIG-1..12 |
| D10 LLM optional | FR-DIG-2..6, FR-DIG-3a |
| D11 Open then store | NFR-6b |
| D12 Worldwide callsigns | FR-LEX-7, FR-LEX-8, FR-LEX-9, R12 |
| D19 Reference device | §10.7, §10.8, NFR-7..10, FR-SVC-5a..c, AC-64..66 |
| D20 Fine-tune base in the export enum | §14A.1, FR-ASR-9, M0a, R1, R12 |

Requirement groups added in drafts 3 and 3.2, mapped to the goal or property they serve:

| Group | Serves |
|---|---|
| FR-RUN-1..18 | D16, NFR-4, G5 — the record survives overload, crash, interruption and clock change |
| FR-AST-1..9 | D13, D2, NFR-4b — assets and schema evolve without destroying the record |
| FR-PLT-1..6 | D1, NFR-6, G5 — the platform integration G5 depends on |
| FR-TST-1..7 | D18, §14 — none of §14 is executable without these |
| FR-A11Y-1..6 | G4, P1 — an uncertainty-first UI that cannot be read fails G4 |
| FR-SEG-7..9, CON-SEG-1 | D8, AC-39 — the precondition cross-tier reprocessing assumed |
| FR-STO-2a..c, CON-STO-1 | D4, FR-REP-4 — the retained audio must still support the passes |

---

## 17. Architecture baseline (D15)

Named here so the technical design has a starting point rather than a blank page. The
technical design owns the detail; this fixes only what the functional spec depends on.

| Concern | Choice | Why this one |
|---|---|---|
| Language | Kotlin | First-class Android, and sherpa-onnx ships a Kotlin/Java API |
| UI | Compose | Live-updating lists with revising partials (P5) suit a declarative model |
| Persistence | Room over SQLite, **FTS5 for transcripts** | FR-STO-1. Room gives tested, versioned migrations (FR-AST-5) |
| Concurrency | Coroutines + Flow | `Flow<RigState>` is already in the rig contract (§9.1) |
| DI | Hilt | Makes FR-TST-1..3 substitution trivial, which is the actual justification |
| Deferred work | WorkManager | Reprocessing (§11) survives process death and respects charge and thermal constraints |
| ASR / VAD / speaker | sherpa-onnx behind an interface | `research/02` §3. **The interface matters more than the choice** |
| Serial | `usb-serial-for-android` | CDC-ACM covers the TH-D75A (§9.3) |
| Audio | `AudioRecord` behind `CaptureSource` | FR-TST-1 requires a file-backed implementation |

### Module boundaries

Boundaries are drawn where **substitution must be possible**, not by layer convention:

```
:app          Compose UI, navigation, settings
:capture      CaptureSource, ring buffer, VAD, segmentation, queue, gaps
:asr          Pass A/B, fusion, hallucination controls   -> interface, sherpa-onnx impl
:lexicon      phonetic lattice, callsign grammar, ITU, priors, calibration   [PURE KOTLIN]
:identity     embeddings, clustering, threading
:rig          RigModule contract, descriptor engine, TH-D75A, null module
:data         Room entities, DAOs, migrations, asset management
:eval         evaluation harness                          [PURE KOTLIN / JVM]
```

**`:lexicon` and `:eval` have no Android dependency.** That is deliberate and load-bearing:
the highest-value, highest-uncertainty work (M1, and every accuracy number in §10) can be
built and measured on a desktop JVM with fast test cycles, long before it runs on a phone.

### Three rules the technical design must not relax

1. **No pass may depend on another pass's output where the audio is available** (§5.2). This
   is what makes reprocessing and D4 work.
2. **Capture never blocks on inference** (FR-RUN-1). Enforced by module boundary — `:capture`
   does not depend on `:asr`.
3. **Every model-bearing interface has a fake.** `:lexicon` and `:eval` staying
   Android-free is how this stays true.

---

## 18. Open questions

Tracked in [`open-questions.md`](open-questions.md).

**Only Q2 remains blocking — record the tape.** Everything else that gated technical design
is now settled: Q1 (rig protocol) by documentation already in this repo, Q3 (reference device)
as D19, Q10 (train/eval split) in §14A.2, and Q11 (NPU vendor scope) as a consequence of Q3.
Q4–Q9 and Q12 carry recommendations and do not block.

**Draft 3.2 opened two more**, both raised by the audit and both needing a product answer
before M2 commits code: **Q13** (audio retention format and the reprocessing horizon, which
also finally forces the Q5 answer) and **Q14** (whether continuous-archive capture is worth its
storage, given that segmentation is otherwise permanent). Neither blocks M0 — record
losslessly and the question stays open.

Six questions were closed during the draft-3 review — architecture baseline, build capacity,
location sourcing, overload policy, reference device and fine-tune base — recorded as D15–D20
and as C11–C16 in the register.

**The two risks that moved:** R1 (fine-tune export) from Severe to Low, because the export
enum already carries non-OpenAI `distil-*` weights and a merged LoRA is structurally identical
to its base (§14A.1). R5 (background-killing) to **top risk**, because the reference device
runs ColorOS (§10.8).
