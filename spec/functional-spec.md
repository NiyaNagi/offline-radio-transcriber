# Offline Radio Transcriber — Functional Specification

**Draft 1 · September 2026 · Ready for review**

Downstream documents this feeds:

- **Technical design spec** — sections 5–11
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
- Offline transcription with hallucination suppression
- Callsign resolution using audio-level lexicon matching
- Speaker clustering and conversation threading with graded attribution confidence
- A modular radio interface, with the Kenwood TH-D75A as the first module
- Searchable local log and digest
- Export to QRZ and POTA workflows

### 1.5 Out of scope for v1

| Item | Why | Revisit |
|---|---|---|
| iOS | Background recording is technically permitted but commercially fragile under Guideline 2.5.4 | If a native-app distribution strategy changes |
| Cloud anything | Hard product constraint | Never |
| Uniden SDS150 rig module | Deferred by product owner; audio-only covers it | v2 |
| Digital voice decode (DMR, P25, D-STAR, Fusion) | Requires demodulation, not transcription | v3+ |
| Transmit / logging back to the radio | Different product | Never |
| Multi-device sync | On-device only by decision | v3 |
| On-device LLM digest | Optional, not required for the product to work | v2, tier-gated |

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
| D3 | **Highest achievable accuracy, subject to running on any device.** | Explicit product-owner direction |
| D4 | **The lexicon runs against the audio**, not only the transcribed text. | CB-Whisper measures entity recall 79.9% → 96.9%; up to +80pp on hot-word subsets |
| D5 | **Callsign extraction is deterministic and auditable.** No LLM in that path, ever. | An LLM that invents a plausible callsign is the worst possible failure |
| D6 | **Live streaming display plus complete-transmission accuracy.** | Product owner wants the live feel and the accurate record. They are complementary passes |
| D7 | **Attribution confidence is always visible**: confirmed / inferred / unknown. | Goal G4 |
| D8 | **Must degrade to run on any device.** Four capability tiers, one data shape. | Explicit product-owner direction |
| D9 | **Audio-only first; modular rig interface extensible to any radio.** TH-D75A first. | Explicit product-owner direction |
| D10 | **A local LLM is optional and post-hoc.** Digest only. | Product owner: "does not need to be a local LLM, as long as we have fully offline speech transcription with high accuracy" |
| D11 | **Open source self-build now; Play Store later.** | No decision may foreclose the store path |
| D12 | **Worldwide callsign support via grammar plus priors**, never a bounded list. | Product owner: "we could hear calls all over the world… think about the most robust and flexible solution" |

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
                          │ PASS B  Offline ASR on complete segment  │
                          │  Whisper small.en / distil / Moonshine   │
                          │  -> verbatim text  + no_speech_prob      │
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

D8 requires running on any device. Tiers are detected at runtime from available RAM, CPU
core count, and a startup calibration benchmark — never from a device allowlist.

| Tier | Trigger | Passes enabled | Resident budget |
|---|---|---|---|
| **T0 — Minimal** | Any device that runs the app | SEG, B (Moonshine tiny), D (text-derived lattice only), STO, UI | ~300 MB |
| **T1 — Standard** | ≥1.5 GB app-available RAM, measured Pass B RTF ≥ 1.0x | + Pass C (acoustic phonetic spotting), better Pass B model | ~800 MB |
| **T2 — Full** | ≥2.5 GB app-available RAM, measured Pass B RTF ≥ 2.0x | + Pass A (live streaming), Pass E (speaker identity, threading) | ~1.4 GB |
| **T3 — Enhanced** | ≥4 GB app-available RAM | + Pass F (on-device LLM digest) | ~2.5 GB |

**FR-TIER-1 (M)** — The system SHALL detect capability tier at first run and on every
model-configuration change, using measured throughput rather than device identification.

**FR-TIER-2 (M)** — All tiers SHALL produce records with an identical schema. A tier
difference SHALL manifest as absent optional fields and lower confidence, never as a
different record shape.

**FR-TIER-3 (M)** — The user SHALL be able to override the detected tier downward (to save
battery) and upward (accepting the risk), with the override visible in settings.

**FR-TIER-4 (S)** — The system SHALL degrade tier automatically and reversibly under
sustained thermal pressure or when the processing backlog exceeds a configured depth, and
SHALL surface that it has done so.

**FR-TIER-5 (M)** — Every record SHALL store the tier and pass set that produced it, so a
later reprocess knows what is worth redoing.

---

## 7. Functional requirements

### 7.1 FR-CAP · Audio capture

**FR-CAP-1 (M)** — Capture 16 kHz mono PCM from a selectable input device.

**FR-CAP-2 (M)** — Enumerate available inputs via `AudioManager.getDevices(GET_DEVICES_INPUTS)`
and allow explicit selection, filtering for `TYPE_USB_DEVICE`, `TYPE_WIRED_HEADSET`,
`TYPE_BUILTIN_MIC`.

**FR-CAP-3 (M)** — After binding, verify the actual route with `getRoutedDevice()` and
**halt with a visible error if it does not match the selection.** A silent fallback to the
built-in mic records the room instead of the radio and is the highest-consequence silent
failure in the system.

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

**FR-ASR-8 (S)** — Support user-supplied model files, so a fine-tuned model can be dropped
in without an app update.

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

RigState
  timestamp, frequencyHz?, mode?, squelchOpen?, signalStrength?,
  memoryChannel?, channelName?, sourceConfidence
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

**FR-STO-3 (M)** — Provide a configurable retention policy with independent controls for
audio and text. Default: audio 30 days, text indefinite.

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

**FR-UI-9 (S)** — Station view: everything heard from one station, across sessions.

**FR-UI-10 (S)** — Frequency/channel view: everything heard on one frequency.

### 7.9 FR-DIG · Digest

**FR-DIG-1 (M)** — Generate a digest over a configurable window (default: since last opened).

**FR-DIG-2 (M)** — The v1 digest SHALL be **template-derived and deterministic**: activity
by frequency and time, stations heard with confidence, notable events (POTA references, new
stations, longest threads), and volume statistics. No LLM required.

**FR-DIG-3 (M / T3)** — Where an on-device LLM is available and enabled, generate prose
summaries per thread as an *addition* to the deterministic digest, never a replacement.

**FR-DIG-4 (M)** — The LLM SHALL be given already-resolved entities and SHALL NOT be
permitted to emit a callsign. Enforce with grammar-constrained decoding where the runtime
supports it.

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

**FR-OBS-4 (S)** — Offer a "record a labelled sample" mode that captures audio plus
ground-truth annotations, for building an evaluation set.

---

## 8. Data model

Field lists are functional, not a schema. Types and indices belong in the technical design.

**Session** — id, startedAt, endedAt, profileId, deviceTier, appVersion,
terminationReason (`user` | `crash` | `killed` | `storage` | `unknown`)

**Transmission** — id, sessionId, threadId?, startedAt, endedAt, durationMs,
audioRef, audioFormat, preRollMs, frequencyHz?, frequencyProvenance, mode?,
signalStrength?, channelName?, voiceprintId?, attributionState, stationId?,
attributionConfidence?, attributionSourceTransmissionId?, corrected (bool),
processingState (`pending` | `complete` | `rejected` | `partial`), rejectionReason?

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

**NFR-1 (M)** — Callsign resolution SHALL achieve **≥90% precision** on confirmed
attributions, measured against a hand-labelled evaluation set of real off-air traffic.

**NFR-1a (M)** — **Precision is prioritised over recall for `CONFIRMED`.** Reporting no
callsign is acceptable; reporting the wrong one is not. Target recall ≥75% at that
precision.

**NFR-1b (S)** — Overall WER target ≤25% on real off-air audio at T1+. This is deliberately
loose: the ATC literature reaches 13.5–16.7% only with fine-tuning, and no stock model has
been measured on this audio.

> **All accuracy targets are provisional until an evaluation set exists.** No published WER
> figure for any of these models on off-air amateur radio audio was found. Building the
> evaluation set is the first engineering task (section 15).

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

**NFR-5b (M)** — No dependency on any specific SoC, NPU or vendor SDK. NPU acceleration, if
ever added, SHALL be an optional accelerator behind the same interface.

### 10.6 Privacy and legal

**NFR-6 (M)** — No network access in the capture or processing path. Network is used only
for explicitly user-initiated actions (lexicon download, QRZ enrichment, export).

**NFR-6a (M)** — All data app-private by default.

**NFR-6b (M)** — Licensing of bundled model weights and lexicon data SHALL be documented and
compatible with both open-source distribution and eventual Play Store release (D11).

**NFR-6c (S)** — Surface a jurisdiction notice on first run regarding the legality of
recording radio transmissions, which varies by locality.

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
| F14 | Clock change / DST during a session | Monotonic clock for durations | Store both wall and monotonic time |

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
- **AC-5** The same run repeated on a Samsung device with battery optimization *not*
  exempted is expected to fail, and the failure is detected and reported on next launch (F5).

### 14.2 Segmentation and hallucination

- **AC-6** Against a tape of pure squelch noise with no speech, the system emits **zero**
  accepted transcripts. Every segment is `rejected` with a recorded reason (F4).
- **AC-7** Each of the six hallucination controls is individually demonstrable with a
  crafted input.
- **AC-8** Rejected segments retain audio and are reachable in the UI.

### 14.3 Callsign resolution

- **AC-9** On the hand-labelled evaluation set, `CONFIRMED` precision ≥90% (NFR-1).
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
  linking to the confirming transmission.
- **AC-17** Correcting the attribution on any one of those five re-propagates to all, and the
  corrected records are flagged and immune to re-propagation.
- **AC-18** Two genuinely different stations in one thread produce two Voiceprints and are
  not merged, at the default threshold, on real audio.
- **AC-19** Transmissions below the duration floor are not clustered and remain `UNKNOWN`
  rather than being attributed.
- **AC-20** A thread ends when the frequency changes beyond the configured gap.

### 14.5 Rig interface

- **AC-21** The null module supports a complete capture session with manual frequency.
- **AC-22** The TH-D75A module reports frequency, and transmissions carry it with provenance
  `rig`.
- **AC-23** A new radio can be added by supplying only a declarative descriptor, verified by
  adding a second radio during test.
- **AC-24** An invalid descriptor falls back to the null module and does not block capture.
- **AC-25** Rig disconnect mid-session leaves capture running with frequency marked stale.

### 14.6 Tiers and degradation

- **AC-26** The app runs and captures on a 2 GB device at T0 (NFR-5a).
- **AC-27** Records from T0 and T2 have identical schema; a T0 record differs only by absent
  optional fields (FR-TIER-2).
- **AC-28** Under induced thermal load, the system degrades tier, surfaces it, and recovers.
- **AC-29** At 40% simulated channel activity the system does not fail; it queues, then
  degrades, and reports backlog.

### 14.7 Reprocessing

- **AC-30** Pass D can be re-run over a historical date range without re-running ASR, and
  updates attributions.
- **AC-31** A superseded transcript remains retrievable.
- **AC-32** Shortening audio retention below the reprocessing horizon produces a warning that
  names the consequence (FR-REP-4).

### 14.8 Export

- **AC-33** An `INFERRED` attribution exports with its state; a confirmed-only export omits
  it entirely (FR-EXP-4, FR-EXP-5).
- **AC-34** ADIF export imports cleanly into standard logging software.

### 14.9 Evaluation harness

- **AC-35** A reproducible harness exists that runs the full pipeline over the evaluation
  tape and reports WER, callsign precision/recall, rejection rate by reason, and attribution
  accuracy. **This harness is a v1 deliverable, not a test artifact** — the accuracy numbers
  in section 10 cannot be validated or defended without it.

---

## 15. Release plan

Ordered by dependency and by information value. Each milestone answers a question that
changes what comes after.

### M0 — Evaluation set (blocking everything)

Record 3–5 hours of real traffic from the TH-D75A and the SDS150, hand-label callsigns,
frequencies, speaker turns and thread boundaries. **Nothing in section 10 is verifiable
without this, and every accuracy claim in all three research documents is transferred from
another domain.** This is the highest-value work in the project and needs no code.

### M1 — Lexicon resolver, off-device

Build Pass D — grammar, ITU table, priors, confusion-weighted matching — as a standalone
component tested against M0 transcripts. This is platform-independent, is the largest single
accuracy gain available, and can be developed on a desktop in this repo alongside the
existing WWARA and POTA data.

### M2 — Capture spine

Foreground service, audio route binding and verification, VAD segmentation, Opus storage,
SQLite schema, capture status UI. **No ASR.** Proves the 8-hour requirement and the
background-execution risk in isolation, which is the biggest platform risk.

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

### M10 — Tier system

Detection, degradation, T0 validation on constrained hardware.

> **M4 is the decision point.** If audio-level resolution does not measurably beat text-level
> resolution on the M0 tape, the architecture should collapse to the simpler text path and
> the saved complexity spent elsewhere. Design the milestone so that comparison is the
> deliverable.

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
| D3 Highest accuracy | FR-ASR-1..8, FR-LEX-4..12 |
| D4 Lexicon vs audio | FR-LEX-4, FR-LEX-5, FR-LEX-7, FR-ASR-4 |
| D5 Deterministic callsigns | FR-DIG-4, FR-LEX-7..12 |
| D6 Live + accurate | FR-ASR-2, FR-ASR-3, P5 |
| D7 Visible confidence | FR-SPK-10, FR-UI-4, FR-EXP-4, P1 |
| D8 Runs on anything | FR-TIER-1..5, NFR-5a |
| D9 Modular rig | FR-RIG-1..12 |
| D10 LLM optional | FR-DIG-2..6 |
| D11 Open then store | NFR-6b |
| D12 Worldwide callsigns | FR-LEX-7, FR-LEX-8, FR-LEX-9 |

---

## 17. Open questions

Tracked in [`open-questions.md`](open-questions.md). Nine are open at draft 1; **Q1
(rig protocol verification), Q2 (evaluation set scope) and Q3 (reference device)** should be
closed before technical design begins.
