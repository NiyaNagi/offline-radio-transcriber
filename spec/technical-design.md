# Offline Radio Transcriber — Technical Design Specification

**Draft 1 · September 2026 · Input: functional spec draft 3.1**

This document owns *how*. The functional spec owns *what* and *why*, and where the two
disagree the functional spec wins — raise a change against it rather than diverging here.

Scope of this draft: the architecture in full, and the subsystems that M0–M4 build in
implementation detail. Subsystems first built in M5–M11 are specified to the level of their
**interfaces and data shapes only**, because M4 is an architectural fork (R3) that can
delete Pass C and collapse the lexicon layer to a text path. Interfaces are specified now
because they are what M4 cannot invalidate; internals are not, because it can.

Traceability convention: `→ FR-xxx-n` cites the requirement a design decision satisfies,
`→ AC-n` the criterion that proves it.

---

## 1. Design rules

Five rules that override local convenience anywhere in this document. The first three are
restated from functional spec §17 and are not relaxable; the last two are this document's own.

1. **No pass consumes another pass's output where the audio is available** (§5.2 P1). Passes
   take `(audio, lexicon snapshot, model set, config)`.
2. **Capture never blocks on inference** (FR-RUN-1), enforced structurally: `:capture-*`
   has no compile-time dependency on `:asr-*`, `:lexicon` or `:identity`. This is checked by
   a build rule, not by discipline.
3. **Every model-bearing interface has a fake**, and `:lexicon`, `:eval`, `:core` and the
   `-api` modules have no Android dependency at all.
4. **Every stored derived value carries the fingerprint of what produced it** (§3.4). This
   single mechanism implements FR-REP-2, FR-TIER-5, FR-ACC-5, FR-LEX-18 and the `STALE`
   state, and nothing else needs to track staleness.
5. **Network capability is a module, not a permission.** Only `:net` may link an HTTP client;
   everything else is structurally incapable of a network call (§16).

---

## 2. Module structure

Gradle multi-module, Kotlin, JVM toolchain 17, `compileSdk` current, `minSdk` 26.

```
:core            [JVM]  domain types, ids, Clock, Result, PassId, fingerprints, config schema
:capture-api     [JVM]  CaptureSource, AudioFrame, RingBuffer, SegmentSink, WAV file source
:capture-android [AND]  AudioRecord source, route verification, focus/interruption, gaps
:segment         [JVM]  VAD interface, Silero impl (ONNX), squelch fusion, pre-roll, splitting
:asr-api         [JVM]  AsrEngine, StreamingAsrEngine, Enhancer, ExecutionProvider, model registry
:asr-sherpa      [JVM]  sherpa-onnx implementations (desktop + Android; same code path)
:lexicon         [JVM]  lattice, grammar FSA, ITU trie, priors, scoring, calibration   ← PURE
:identity        [JVM]  embeddings interface, incremental clustering, threading
:rig             [JVM]  RigModule contract, descriptor engine, fakes
:rig-usb         [AND]  usb-serial-for-android transport
:data            [AND]  Room entities, DAOs, FTS5, migrations, audio store, work queue
:pipeline        [AND]  pass orchestration, shed controller, tier detector, reprocess engine
:net             [AND]  the ONLY module allowed an HTTP client. Asset download, QRZ (opt-in)
:eval            [JVM]  evaluation harness, corpus manifest, metrics, reliability diagrams  ← PURE
:testing         [JVM]  fakes, fixtures, corpus loaders, deterministic seeds
:app             [AND]  Compose UI, navigation, settings, onboarding, Hilt graph
```

The `-api` / `-impl` split exists for one reason: **the evaluation harness must run the
complete pipeline on a desktop JVM** (AC-35, AC-89). sherpa-onnx ships JVM bindings with
native libraries for both desktop and Android, so `:asr-sherpa` is genuinely one code path;
only `:capture-android`, `:rig-usb`, `:data`, `:pipeline`, `:net` and `:app` are Android-only.
The harness composes `:capture-api` (WAV source) + `:segment` + `:asr-sherpa` + `:lexicon` +
`:identity` and never touches the Android modules.

**Allowed dependency edges** (enforced by a `dependencyRules` Gradle task that fails the
build on violation):

| Module | May depend on |
|---|---|
| `:core` | — |
| `:capture-api`, `:segment`, `:asr-api`, `:lexicon`, `:identity`, `:rig` | `:core` (and `:asr-api` for `:asr-sherpa`) |
| `:capture-android` | `:core`, `:capture-api` |
| `:data` | `:core` |
| `:pipeline` | everything except `:app`, `:net`, `:capture-android`'s internals |
| `:app` | `:pipeline`, `:data`, `:net`, UI-facing APIs |
| `:eval` | `:core`, `:capture-api`, `:segment`, `:asr-*`, `:lexicon`, `:identity`, `:testing` |

The forbidden edges that matter: `:capture-*` → `:asr-*` (rule 2), anything → `:net` except
`:app` and `:pipeline`'s asset installer (rule 5), and `:lexicon`/`:eval`/`:core` → anything
Android (rule 3).

---

## 3. Cross-cutting contracts

### 3.1 The pass contract

Every processing stage is a `Pass`. This is the type that makes §5.2 Principle 2 real.

```kotlin
enum class PassId { ENH, A_STREAM, B_OFFLINE, FUSE, C_SPOT, D_RESOLVE, E_IDENTITY, F_DIGEST }

interface Pass {
    val id: PassId
    /** Everything that can change the output. Stored with the result; drives staleness. */
    fun fingerprint(env: PassEnv): PassFingerprint
    suspend fun execute(job: PassJob): PassOutcome
}

class PassJob(
    val transmissionId: TransmissionId,
    val audio: AudioHandle,          // lazily decoded; enhanced variants requested by name
    val priorResults: PassResultView, // read-only, ONLY for passes that legitimately compose
    val env: PassEnv,
)

class PassEnv(
    val clock: Clock,
    val config: ResolvedConfig,       // profile + thresholds, immutable snapshot
    val lexicon: LexiconSnapshot,     // versioned, immutable
    val models: ModelSet,             // resolved per tier + execution provider
    val tier: Tier,
    val provider: ExecutionProvider,
)

sealed interface PassOutcome {
    data class Completed(val writes: List<ResultWrite>) : PassOutcome
    data class Rejected(val rule: RejectionRule, val detail: String?) : PassOutcome  // a result
    data class Failed(val error: PassError, val retryable: Boolean) : PassOutcome    // not a result
}
```

`Rejected` versus `Failed` is FR-RUN-9 and is load-bearing: a rejection is a conclusion and
terminates the job; a failure is an accident and is retried with backoff to a bounded count
(FR-RUN-10 → AC-51).

`priorResults` is deliberately narrow. Only `FUSE` (needs A and B hypotheses), `D_RESOLVE`
(needs the lattice from C, which is itself derived from audio) and `E_IDENTITY` (needs D's
confirmed callsign) may read it, and a lint check on the constructor argument list keeps the
list from growing quietly.

### 3.2 Clock

```kotlin
interface Clock {
    fun monotonicNanos(): Long          // SystemClock.elapsedRealtimeNanos()
    fun wallMillis(): Long              // System.currentTimeMillis(), UTC
    fun utcOffsetMinutes(): Int
}
```

Injected everywhere via Hilt (FR-TST-2 → AC-91). `TestClock` advances manually. **No call site
may use `System.currentTimeMillis()` directly**; a lint rule enforces it, because thread gaps,
the 10-minute ID window, retention and voiceprint decay are all otherwise untestable.

Within a session the authoritative timeline is the **sample position** (FR-RUN-16). A
`SampleClock` created at session start maps `samplePosition ↔ (monotonicNanos, wallMillis)`
by the anchor captured at the first `AudioRecord` read plus `samples / 16000`. Every
transmission stores `samplePosition`, `monotonicStartNanos`, `startedAtUtc`,
`utcOffsetMinutes` (FR-RUN-15, FR-RUN-18 → F14).

### 3.3 Execution providers

```kotlin
interface ExecutionProvider {
    val id: String                 // "cpu", "qnn-htp", "litert-npu"
    val chipset: String?
    fun supports(model: ModelDescriptor): Boolean
    fun session(model: ModelDescriptor): InferenceSession
}
```

`CpuProvider` is always registered and always last in the resolution order (FR-ACC-2 →
AC-38). Provider selection happens once at tier detection and is recorded per record as
`executionProvider` (FR-ACC-5). No vendor SDK type escapes `:asr-sherpa`; the QNN path is a
runtime-loaded native library whose absence degrades tier rather than failing to link
(FR-ACC-4). This interface is defined in M2 and has exactly one implementation until M11.

### 3.4 Fingerprints and staleness

```kotlin
data class PassFingerprint(
    val passId: PassId,
    val codeVersion: Int,          // bumped by hand when a pass's algorithm changes
    val modelIds: List<AssetRef>,  // (assetId, version)
    val lexiconVersion: AssetRef?,
    val calibrationVersion: AssetRef?,
    val configHash: String,        // stable hash over the pass-relevant config subset
    val provider: String,
    val tier: Tier,
)
```

Stored as a canonical string on every derived row. A record is a **reprocessing candidate**
(FR-REP-8) iff `storedFingerprint != currentFingerprint(env)` for any pass that produced it,
with a per-pass table of which fields count as "materially better" (a lexicon version bump
makes Pass D stale but not Pass B — FR-REP-6, the cheap high-value reprocess → AC-30).

This one mechanism covers FR-TIER-5, FR-ASR-7, FR-REP-2, FR-ACC-5, FR-LEX-18 and F22. There
is no separate "is stale" flag to keep in sync; `isReprocessCandidate` on `Transmission` is a
denormalised cache of this computation, refreshed when assets activate and recomputed lazily
on read.

### 3.5 Configuration

`ResolvedConfig` is an immutable snapshot produced by merging: built-in defaults ← active
capture profile (FR-CFG-1) ← user overrides. Every threshold named in the functional spec is
a typed field with a documented effect and a default (FR-CFG-2). A profile switch produces a
new snapshot atomically and takes effect at the next session boundary or, for processing-only
values, the next job (FR-AST-4 → F21). The snapshot's `configHash` feeds §3.4.

---

## 4. Process and concurrency model

One process. Threads are named, few, and each has one job.

| Thread / dispatcher | Count | Priority | Work |
|---|---:|---|---|
| `capture-io` | 1 | `THREAD_PRIORITY_URGENT_AUDIO`, plain `Thread` not a coroutine | `AudioRecord.read()` into the ring buffer. Does **nothing** else |
| `segment` | 1 | default | Drains the ring, runs VAD, cuts segments, writes PCM, enqueues |
| `infer-heavy` | 1 | background | Pass B, C, E, FUSE. Single-slot so two models never contend |
| `infer-stream` | 1 | background | Pass A only (M8+), independently sheddable |
| `db` | Room's own | — | All DAO access; no DB call on `capture-io` or `segment` |
| `ui` | main | — | Compose |
| WorkManager | pool | background | Reprocessing, retention, reconciliation, digest, asset install |

**Why `capture-io` is a bare thread.** A coroutine on a shared dispatcher can be delayed by
an unrelated blocking call; an 8-hour run has no tolerance for that. It runs a `while
(running) { read(); ringBuffer.write() }` loop with no allocation, no logging on the hot path
and no locks (§5.2).

**Backpressure never reaches `capture-io`.** The ring buffer is lossy *toward the segmenter*
only in the sense that the segmenter must keep up with a 16 kHz mono stream — a workload
measured in microseconds per second of audio. If `segment` ever falls behind, that is a bug
and is surfaced as a `CaptureGap` with cause `unknown`, not silently tolerated (NFR-4).

---

## 5. Capture subsystem (M2)

### 5.1 CaptureSource

```kotlin
interface CaptureSource {
    val format: AudioFormat            // fixed: 16 kHz, mono, PCM16
    fun start(): Flow<CaptureEvent>    // Frames, RouteChanged, Interrupted, Resumed, Failed
    fun stop()
    fun routedDevice(): AudioDeviceInfo?   // null on the file source
}
```

Two implementations, and the file-backed one ships in M2 (FR-TST-1 → AC-89):

- `AudioRecordSource` (`:capture-android`) — `MediaRecorder.AudioSource.UNPROCESSED` where
  available, falling back to `VOICE_RECOGNITION`; explicitly **not** `MIC` with effects, which
  applies AGC and noise suppression that fight the ASR. `setPreferredDevice()` from the user's
  selection, then `getRoutedDevice()` verification (§5.2).
- `WavFileSource` (`:capture-api`) — replays a WAV at real time or as fast as the consumer
  drains, emitting the same event stream. The harness and every timing-independent test use it.

### 5.2 Route binding and verification

The highest-consequence silent failure in the system (F1 → AC-2):

```
enumerate  AudioManager.getDevices(GET_DEVICES_INPUTS), filter TYPE_USB_DEVICE |
           TYPE_WIRED_HEADSET | TYPE_BUILTIN_MIC                          → FR-CAP-2
bind       AudioRecord.setPreferredDevice(selected)
verify     after first successful read AND on every AudioDeviceCallback:
           getRoutedDevice()?.id == selected.id  else  HALT               → FR-CAP-3
```

Verification runs **after the first read**, not immediately after binding, because
`getRoutedDevice()` returns null until the record actually starts. A mismatch stops capture
with a full-screen error and a persistent notification; it never falls back (FR-RUN-13). The
same check runs on every route change mid-session.

### 5.3 Ring buffer and pre-roll

Single-producer/single-consumer, preallocated `ShortArray` of `preRollSeconds + slack`
(default 8 s = 256 KB), power-of-two length, `AtomicLong` write and read cursors, no locks and
no allocation in `write()`. `snapshotPreRoll(atSample, millis)` copies backwards from the VAD
trigger point (default **1200 ms**, ≥ FR-CAP-4's 1.0 s floor) — this is what stops a callsign
in the first syllable being clipped (AC-3).

Overrun (writer laps reader) is detectable via cursor distance and is a `CaptureGap`, never a
silent overwrite.

### 5.4 Level metering and input health

A cheap RMS/peak accumulator on the `segment` thread produces 20 Hz level updates for the
setup meter (FR-CAP-6) and rolling 60-second statistics for the persistent-silence and
persistent-clipping warnings (FR-CAP-7 → F3). Clipping is `|sample| >= 32700` for ≥ 3 samples
in a window; silence is `peak < -50 dBFS` sustained for a configurable period.

### 5.5 Interruption, focus and gaps

`AudioRecord` errors, `AudioManager.OnAudioFocusChangeListener` losses, and
`AudioRecordingConfiguration` callbacks (API 29+, showing another client taking the mic) all
funnel into one handler that:

1. Closes the in-flight segment if one is open (it is retained — NFR-4a).
2. Opens a `CaptureGap` row with `startedAt` = current sample position and the cause.
3. Retries acquisition on a backoff ladder (1 s, 2 s, 5 s, 10 s, then every 30 s).
4. On success, re-verifies the route (§5.2), closes the gap, resumes.

Gaps are first-class rows, shown in the timeline and counted in health stats (FR-RUN-11,
FR-RUN-12 → AC-48, AC-49). `AudioRecord.Builder.setPrivacySensitive(false)` and, on API 31+, a
request for concurrent capture where the platform grants it (FR-RUN-14) — best-effort, and the
gap path is the guaranteed behaviour.

### 5.6 Foreground service

`CaptureService`, `foregroundServiceType="microphone"`, started only from a foreground
activity by explicit user action (FR-SVC-1), holding a `PARTIAL_WAKE_LOCK` for its lifetime
(FR-SVC-2). Notification shows state, elapsed time and transmission count with a stop action
and **never transcript text** (FR-PLT-3 → AC-61).

**Liveness is empirical** (NFR-8, FR-SVC-5b → AC-65). A `heartbeat` row is rewritten every
30 s with `(sessionId, monotonicNanos, wallMillis, samplePosition)`. On next launch:

```
if session.endedAt == null:
    session.terminationReason = if (lastHeartbeat within 60 s of process death marker) "crash"
                                else "killed"
    report to the user with the last heartbeat time            → FR-SVC-6, NFR-10, AC-5
    requeue everything left in PROCESSING                       → FR-RUN-8, AC-47
```

`isIgnoringBatteryOptimizations()` is recorded as a diagnostic field and used for onboarding
guidance only. It is never treated as proof (§10.8).

OEM guidance is a data table keyed on `Build.MANUFACTURER` + `Build.BRAND`, each entry
carrying ordered steps and optional deep-link `Intent`s, resolved with
`queryIntentActivities` before being offered so a missing activity degrades to text
(FR-SVC-5a, NFR-9 → AC-66). Oppo/ColorOS ships with all four §10.8 interventions.

The **"prove it" test** (FR-SVC-5c → AC-64) is a 30-minute capture with a distinct session
flag; on completion it reports heartbeat continuity, gap count and whether the process was
restarted. It is the acceptance gate for R5.

---

## 6. Segmentation (M2)

```kotlin
interface Vad { fun accept(frame: FloatArray): VadDecision }   // 30 ms frames
interface BoundarySource { val squelch: Flow<SquelchState>? }  // from the rig, nullable
```

Silero VAD via sherpa-onnx `VoiceActivityDetector`, on 512-sample (32 ms) windows at 16 kHz.
The segmenter is an explicit state machine — `IDLE → SPEECH → HANGOVER → CLOSED` — with
configurable `minSpeechMs` (default 250), `minSilenceMs` (default 600), `maxSegmentMs`
(default 60 000, splitting a stuck carrier → AC-70) and the pre-roll prepend (FR-SEG-4).

**Squelch fusion** (FR-SEG-5 → AC-68), where the rig provides it: squelch open/close define
the boundaries, VAD decides whether the enclosed audio contains speech. A squelch-bounded
segment with no VAD speech is stored `rejected:no_speech` without invoking any model. Rig
state is only trusted for boundaries when its correlation skew is ≤ 250 ms (FR-RUN-17); beyond
that it degrades to an advisory signal and provenance drops to `inherited` (AC-50).

Segments below `minSpeechMs` are written as `rejected:too_short` with audio retained and **no
model invoked** (FR-SEG-6 → AC-72).

Boundary quality is measured, not tuned by ear: the harness reports boundary precision/recall
against hand-marked keying times (AC-69) and the merge/split trade at the configured
thresholds (AC-71).

### 6.1 Audio storage

Capture writes **raw PCM16** to `pcm/<sessionId>/<transmissionId>.pcm` on the `segment`
thread — a memcpy-rate operation that cannot stall capture. Encoding to Ogg Opus is a queued
`STORE` step:

- API 29+: `MediaCodec` `audio/opus` encoder, 16 kHz mono, ~24 kbps VBR, wrapped by an
  in-house Ogg page writer (`MediaMuxer`'s OGG support is not dependable across the range).
- API 26–28: Concentus (pure-Java Opus) at the same settings. Slower, and irrelevant at 15%
  duty on the floor device.

The PCM file is deleted only after the Opus file verifies (decode header + duration within
tolerance). A crash between the two leaves a recoverable PCM file, which the reconciliation
pass (§12.4) finds. Estimated cost ≈ 1.1 MB per wall-clock hour at 15% duty (Q5), which is why
the default retention is **indefinite with storage-pressure pruning** rather than 30 days —
adopt the Q5 recommendation and make FR-STO-3's time policy opt-in with the FR-REP-4 warning
(AC-32, AC-77).

---

## 7. Work queue and orchestration (M2)

### 7.1 Durable queue

An on-disk table, not an in-memory channel (FR-RUN-2 → AC-45):

```sql
CREATE TABLE work_queue_item (
  id INTEGER PRIMARY KEY,
  transmission_id TEXT NOT NULL,
  pass TEXT NOT NULL,
  state TEXT NOT NULL,            -- READY | LEASED | DONE | FAILED | DEFERRED
  priority INTEGER NOT NULL,      -- live traffic > reprocessing
  attempt_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  shed_level INTEGER NOT NULL DEFAULT 0,
  lease_run_id TEXT,              -- process run uuid; stale lease == crashed
  lease_expires_at INTEGER,
  enqueued_at INTEGER NOT NULL,
  started_at INTEGER,
  UNIQUE(transmission_id, pass)
);
CREATE INDEX idx_wq_ready ON work_queue_item(state, priority, enqueued_at);
```

The orchestrator leases a batch, executes on `infer-heavy`, and commits the pass result and
the queue transition **in one transaction** with the transmission's state change. On launch,
any lease whose `lease_run_id` is not the current run is cleared and its transmission returns
`PROCESSING → CAPTURED` (FR-RUN-8 → AC-47). Passes are idempotent, so replay is always safe.

Queue capacity is bounded by **available storage**, not item count (FR-RUN-6): a monitor
computes free bytes minus a reserve and warns at configurable fractions.

### 7.2 Transmission lifecycle

Implemented exactly as functional spec §7.14, as a single `TransmissionState` enum with an
explicit legal-transition table asserted in a unit test. `STALE` is not stored — it is derived
from §3.4 — which removes the class of bug where a flag and its cause disagree.

### 7.3 Shed controller

Inputs, sampled every 10 s with hysteresis (enter at threshold, leave at 0.7× threshold, and a
minimum 60 s dwell so the level does not oscillate):

| Signal | Source |
|---|---|
| Queue depth, oldest-unprocessed age | `work_queue_item` |
| Thermal status | `PowerManager.getCurrentThermalStatus()` (API 29+), else measured RTF drop |
| Measured Pass B RTF, rolling | pass telemetry |
| Battery level and charging state | `BatteryManager` |
| Free storage | `StatFs` |

Output is a level 0–5 mapping onto FR-RUN-3's shed order exactly: 1 drops Pass A, 2 drops
Pass E, 3 downgrades the Pass B model within the tier, 4 defers all passes (capture and
enqueue only), 5 is the storage-exhaustion stop — the only case where capture stops, and it is
loud (FR-STO-4 → AC-46, AC-78). Every level change writes a `shed_event`, is visible in the
capture status surface (FR-RUN-5 → FR-UI-7), and marks affected records as reprocessing
candidates (FR-RUN-4).

Level 5 is the only path that stops capture. Everything else defers, which is the property
that makes D16 true.

---

## 8. ASR subsystem (M3, plus M8/M11)

### 8.1 Interfaces

```kotlin
interface AsrEngine {                                   // Pass B
    suspend fun transcribe(audio: FloatArray, opts: DecodeOptions): AsrResult
}
data class AsrResult(
    val text: String, val nBest: List<Hypothesis>, val noSpeechProb: Float?,
    val avgLogProb: Float?, val tokens: List<TokenScore>, val modelRef: AssetRef,
)
interface StreamingAsrEngine {                          // Pass A, M8
    fun stream(hotwords: HotwordSet): StreamSession      // emits partials, finalises on close
}
interface Enhancer { fun enhance(audio: FloatArray): FloatArray }   // GTCRN, M11
```

`:asr-sherpa` implements all three over sherpa-onnx. The n-best list is retained from M3
onward even though nothing consumes it until M11 — it is cheap to keep and expensive to
retrofit into stored records.

### 8.2 Hallucination controls

Six controls (FR-ASR-5 → AC-7), each a named `RejectionRule` evaluated in this order so the
cheapest rejects first:

| Order | Rule | Default | Notes |
|---:|---|---|---|
| 1 | `too_short` | < 250 ms | Evaluated in the segmenter; no model runs |
| 2 | `vad_no_speech` | Silero | Segmenter |
| 3 | `no_speech_prob` | > 0.60 | Post-decode |
| 4 | `repetition` | 4-gram repeated ≥ 3× or > 45% of tokens | Post-decode |
| 5 | `blocklist` | user-extensible phrase list, normalised match | Ships with the documented Whisper artifacts |
| 6 | `compression_ratio` | gzip ratio > 2.4 | Post-decode |

A rejected segment is a **result**: state `REJECTED`, rule recorded, audio retained, visible
behind a UI filter (FR-ASR-6 → AC-8). AC-6 (zero accepted transcripts from a pure-noise tape)
is the single most important test in the plan and runs on every harness invocation.

### 8.3 Transcript versioning

`transcript` rows are append-only with exactly one `is_current = 1` per transmission,
enforced by a partial unique index. Superseding writes a new row and flips the flag in one
transaction; nothing is deleted (FR-REP-3 → AC-31, P9). Pass A partials are written with
`pass='A'` and `is_current = 0` from the start — they are never the record (FR-ASR-3).

### 8.4 Model registry

A model is `ModelDescriptor(assetId, version, family, size, quantization, providerBinaries,
isFineTuned, fineTuneId, trainingDataDescription, licence)` (FR-ASR-10, FR-LEX-28, NFR-6b).
Tier + provider resolve to a `ModelSet`; a missing or invalid model file falls back to the
next-lower model and surfaces it (F13). Side-loading a user model is a first-class install
path through §13 (FR-ASR-8).

---

## 9. Lexicon subsystem (M1, M4) — the accuracy core

Pure Kotlin, no Android, no I/O beyond a `LexiconSnapshot` handed in. This is the module that
gets built and measured on a desktop before the app can run at all.

### 9.1 Types

```kotlin
/** One time-ordered slot of the acoustic hypothesis, alternatives retained (FR-LEX-5). */
data class LatticeSlot(val startMs: Int, val endMs: Int, val alts: List<UnitScore>)
data class UnitScore(val unit: PhoneticUnit, val logProb: Float)
data class PhoneticLattice(
    val slots: List<LatticeSlot>,
    val source: LatticeSource,        // ACOUSTIC (Pass C) | TEXT_DERIVED (T0, FR-LEX-6)
    val modelRef: AssetRef?,
)
```

`PhoneticUnit` is the ~36-unit closed vocabulary — 26 letters, 10 digits — plus separators and
modifiers, with a **variant table** mapping spoken forms to units (`alpha|able|adam → A`,
`niner|nine → 9`, `stroke|slash|portable → /`). The variant table is **content, not
localization** (FR-A11Y-6): a data asset, extensible without a translation pass, versioned
with the lexicon.

### 9.2 Grammar as an FSA over the lattice

The parser is a beam search over lattice slots against a finite-state acceptor for the ITU
callsign grammar, pruned by an ITU prefix trie:

```
S0 --letter/digit--> P1 --letter?--> P2 --digit--> D --letter{1,4}--> SUF --('/' mod)?--> ACCEPT
                     └── prefix path must remain a live node in the ITU trie ──┘
```

- **Structural validity against the ITU table is the only hard filter** (FR-LEX-8 → AC-10,
  AC-11). A parse reaching `ACCEPT` with an allocated prefix is a candidate even with zero
  database hits; that is what makes worldwide DX work with no list.
- Path score = Σ slot log-probs + insertion/deletion penalties, where substitution cost is
  **confusion-weighted**, not uniform Levenshtein (FR-LEX-10): a symmetric cost matrix over
  units with the known ASR collapse sets (B/D/E/P/V/T, M/N, S/F) at reduced cost. The matrix
  is a fitted asset — initialised from the literature, refitted from M0 confusion counts.
- Reciprocal and modifier forms (`K7ABC/VE7`, `VE7/K7ABC`, `/P /M /MM /AM /QRP`) are additional
  accepting paths, not post-processing (FR-LEX-7).
- Output: top-K paths (default 10) as `CallsignCandidate` with the acoustic component of the
  score and the exact slot span consumed.

**T0 path** (FR-LEX-6 → AC-15): `TextDerivedLatticeBuilder` expands Pass B text into the same
`PhoneticLattice` type with a single alternative per slot and a flat confidence. Everything
downstream is identical, and the record records `source = TEXT_DERIVED`. This is also what
makes M1 buildable before M4 exists — and it is exactly the baseline M4 must beat (R3).

### 9.3 Priors and ranking

Priors are **log-odds contributions**, each clamped to a configured maximum magnitude, summed
onto the acoustic score. Clamping is how "no prior may act as a hard filter" (FR-LEX-9)
becomes structural rather than a rule someone remembers:

| Prior | Computation | Clamp (default) |
|---|---|---:|
| Frequency / repeater | rig frequency → WWARA / SDS150 / D-STAR index → expected stations | ±3.0 |
| Band plausibility | §9.4 static propagation model | +0.5 / −1.5 (asymmetric, FR-LEX-26) |
| Database presence | ULS or other national data hit | +1.0 / −0.3 (absence is weak) |
| Recency | exponential decay over session and history | ±2.0, zero when cold (FR-LEX-31) |
| Geographic | operator grid vs. prefix allocation centroid | ±0.8 |
| Conversation context | other station in this thread already identified | ±2.0 |
| My stations | user list (FR-LEX-14) | ±2.0 |

Cold start contributes **exactly zero**, not a default, and widens the reported interval
(FR-LEX-31); seeding from the user's list and from WWARA trustee callsigns makes day one
non-empty (FR-LEX-32).

`AMBIGUOUS` is declared when the top two calibrated scores are within `separationThreshold`
(FR-LEX-11).

### 9.4 Propagation model

Static and offline (FR-LEX-25): a function of band, local solar time, season, and great-circle
distance between the operator grid and the candidate prefix's allocation centroid, evaluated
from a small bundled table of per-band day/night distance plausibility. No network, no live
solar indices. It **demotes and never eliminates** (FR-LEX-26), and it is suppressed entirely
when the frequency matches a known repeater or internet-linked system in the WWARA data
(FR-LEX-27) — a DX callsign on a local 2 m repeater is normal.

### 9.5 Calibration

Raw scores are not probabilities, and every threshold in the trust model depends on them
being probabilities (FR-LEX-17):

- **Model:** Platt scaling (a logistic on the combined score), fitted per tier and per model
  (FR-LEX-21) on the M0 **training** fold, evaluated on the eval fold.
- **Asset:** `calibration.json` — `{modelId, tier, a, b, fittedAgainstCorpusVersion, fittedAt}`
  — versioned, shippable without an app release, referenced by `calibrationId` on every
  score-bearing record (FR-LEX-18 → F22).
- **Threshold derivation:** the user sets a **precision target** for the tier; the app scans
  the stored calibrated PR curve for the lowest threshold meeting it. The user never sets a
  raw score (FR-LEX-19 → AC-56).
- **Evidence:** the harness emits a reliability diagram per tier — predicted confidence versus
  observed accuracy in 10 bins, with expected calibration error (FR-LEX-20 → AC-55).

### 9.6 Lexicon assets and the build pipeline

The Python tooling already in this repo builds the app assets, which is the cheapest possible
path given WWARA, SDS150 favorites, POTA parks and the Kenwood D-STAR TSV are already here:

| Asset | Source | Form | Bundled? |
|---|---|---|---|
| ITU prefix allocation | static table, hand-verified | trie, ~10 KB | **Yes, always** (FR-LEX-29) |
| Callsign format rules per allocation | static | JSON | Yes |
| Phonetic variants | static, extensible | JSON | Yes |
| Confusion cost matrix | literature init, refit from M0 | JSON | Yes |
| Band plan / propagation table | static | JSON | Yes |
| WWARA repeaters | this repo | SQLite table, freq-indexed | Yes |
| SDS150 favorites | `washington-sds150-favorites.csv` | SQLite table | Yes |
| POTA parks | `pota-parks/` | SQLite table | Yes |
| FCC ULS | download on demand | SQLite table + index, ~1.1M rows | **No** (FR-LEX-3) |

Every asset carries source, licence, format, cadence and import timestamp, shown in settings
(FR-LEX-28). Import is transactional with checksum and record-count validation and rollback on
failure (FR-LEX-30 → AC-52, F12).

### 9.7 Pass C — acoustic spotting (M4)

Specified at the interface only, because M4 decides whether it exists at all:

```kotlin
interface UnitSpotter { fun spot(audio: FloatArray): PhoneticLattice }
```

Two candidate implementations to be raced in M4, both producing the same type:

1. **sherpa-onnx open-vocabulary KWS** over the ~36-unit vocabulary — a small transducer,
   keywords specified without retraining. Lowest risk, and the tractability argument in
   `research/03` §2.5 is exactly about this scale.
2. **CB-Whisper-style encoder-similarity spotting** — TTS-derived unit representations pushed
   through the encoder, cosine-similarity matrices, a small CNN over the diagonal patterns.
   Higher ceiling, and gated on whether sherpa-onnx exposes encoder hidden states on Android
   (unknown T3).

M4's deliverable is the measured comparison of both against the `TEXT_DERIVED` baseline (§9.2)
on the eval fold, per R3.

---

## 10. Identity subsystem (M6) — interfaces now, internals later

```kotlin
interface SpeakerEmbedder { fun embed(audio: FloatArray): FloatArray }   // WeSpeaker / 3D-Speaker
```

Incremental clustering against existing voiceprint centroids by cosine similarity, joining
above threshold and creating below (FR-SPK-3); embeddings stored as `FLOAT32` BLOBs with a
running centroid and member count. Transmissions below the duration floor are never embedded
and never clustered (FR-SPK-2 → AC-19).

Back-propagation on `CONFIRMED` (FR-SPK-4 → AC-16), correction rebinding with a `CORRECTED`
lock immune to re-propagation (FR-SPK-7 → AC-17), cluster split on asserted mis-merge
(FR-SPK-8 → F10), and confidence decay with elapsed time requiring cross-day re-confirmation
(FR-SPK-9 → AC-67).

Threading on frequency/channel continuity plus gap (FR-SPK-5). Per Q4, the thread key is
**channel identity where available, frequency otherwise**, so a scanning receiver does not
shred threads on every channel hop; `channelName` is already in the entity for this reason.

The API returns attribution with a **non-optional** state (FR-SPK-10) — `Attribution` has no
constructor that omits it, which is how the data layer enforces G4 rather than trusting the UI.

The §97.119 10-minute window (FR-SPK-6) is a scheduled check on `Clock`, lowering cluster
confidence on expiry and never fabricating.

---

## 11. Rig subsystem (M7) — contract now, engine later

`RigModule`, `RigCapability`, `RigState` exactly as functional spec §9.1, including
`POSITION` (FR-LEX-22). Three implementations at v1: `NullRigModule` (first-class, manual
frequency — FR-RIG-2 → AC-21), `DescriptorRigModule` (the engine — FR-RIG-4), and
`ScriptedFakeRig` in `:testing` replaying a timed state sequence (FR-TST-3 → AC-92).

**Descriptor format:** JSON with kotlinx.serialization (not YAML — no extra parser, and
schema-versioned per FR-AST-7), carrying the fields sketched in functional spec §9.2:
transport, serial parameters, capability set, and a poll table of `{send, expect (regex),
map, lookup, intervalMs}`. Validation on load with precise errors; a failing descriptor falls
back to the null module and never blocks capture (FR-RIG-11 → AC-24).

**Transport** is `usb-serial-for-android` CDC-ACM in `:rig-usb` behind a `SerialTransport`
interface so the engine itself is testable on the JVM. USB permission is per-attachment;
persistent access is requested where allowed, and a mid-session re-attach requiring re-grant
is surfaced loudly (FR-PLT-2 → F16 — a realistic way to lose an overnight run).

**TH-D75A descriptor** — two-letter ASCII CAT, CDC device class, `BY` for squelch. Three
things remain to verify with the radio in hand and they are M7 tasks, not design unknowns:
the frequency read command (it is in `TH_D75_Commands.pdf`; not extractable from the current
tooling here), VID/PID under Android, and a safe poll rate for `BY`. Poll scheduling is
adaptive: start at 500 ms, back off on timeout, and downgrade skew-bound provenance per
FR-RUN-17 rather than asserting a frequency it cannot time-align (AC-50).

---

## 12. Data layer (M2)

### 12.1 Schema

Room entities map 1:1 onto functional spec §8, with these implementation notes:

- **Ids** are `TEXT` ULIDs — sortable by creation time, generated offline, safe to merge
  across an export/import (FR-STO-6 → AC-80).
- **FTS5** as an external-content table over `transcript(text)` where `is_current = 1`, kept
  in sync by triggers; search filters (callsign, frequency, band, time, attribution state,
  rejected) are ordinary indexed predicates joined against the FTS match (FR-UI-3).
- **Indices:** `transmission(session_id, sample_position)`, `transmission(started_at_utc)`,
  `transmission(attribution_state)`, `transmission(is_reprocess_candidate)`,
  `callsign_candidate(transmission_id, rank)`, `voiceprint(bound_station_id)`,
  `work_queue_item(state, priority, enqueued_at)`.
- **`sourceId`** on Session and Transmission from day one, unused in v1 (Q6) — a column now
  costs nothing and a migration later costs a release.
- `PRAGMA journal_mode=WAL`, `synchronous=NORMAL`. WAL is what makes NFR-4b (survive a kill
  mid-write) true; the pass/queue transaction boundary (§7.1) is what makes it sufficient.

### 12.2 Audio store

`audio/<sessionId>/<transmissionId>.opus`, with `pcm/` as the pre-encode staging area
(§6.1). Paths are **derived**, never stored, so a row and its file cannot disagree about where
the file should be — only about whether it exists (FR-AST-8).

### 12.3 Migrations

Explicit Room `Migration` objects, schemas exported to `:data/schemas/` and committed, plus a
**fixture database per released schema version** in `:testing`. The migration test iterates
every fixture forward to head and asserts audio and superseded transcripts survive (FR-AST-5,
FR-AST-6 → AC-53). A migration that cannot preserve a derived field marks affected records as
reprocessing candidates instead of dropping them (F20).

### 12.4 Reconciliation and retention

A WorkManager job walks both directions — files with no row, rows with no file — and reports
both, **deleting neither** (FR-AST-8 → AC-54, F19). Retention is a separate job with
independent audio and text policies, pinning support (FR-STO-7 → AC-79), advance announcement
of deletions (P9 → AC-77) and the FR-REP-4 warning when the audio horizon is shortened below
the reprocessing horizon (AC-32).

App-private storage throughout, `android:allowBackup="false"` plus an explicit
`data_extraction_rules` exclusion so captured audio never reaches a cloud account (FR-PLT-5,
FR-STO-8 → AC-60, AC-81).

---

## 13. Asset management (M2 skeleton, filled through M11)

One lifecycle for models, lexicon data, calibration, rig descriptors and NPU binaries
(FR-AST-1): **install → verify → activate → roll back → remove**.

- Verification is checksum + size + format probe (a model must load; a lexicon must parse and
  match its record count) **before** activation; a failure leaves the previous version active
  (FR-AST-2 → AC-52).
- Downloads run in `:net` under WorkManager, resumable, with progress, **unmetered-only by
  default**, and the app stays usable at whatever the installed assets support (FR-AST-3).
- An asset in use by a running session is never replaced; activation defers to the next
  session or reprocess (FR-AST-4 → F21).
- NPU binaries are assets selected at runtime by detected chipset with the CPU model as the
  always-present fallback (FR-AST-9).

---

## 14. Tier detection and policy (M10; stub from M3)

```kotlin
data class TierProbe(
    val appAvailableRamMb: Int,       // ActivityManager.MemoryInfo + getLargeMemoryClass
    val measuredPassBRtf: Float,      // bundled 10 s reference clip, current model
    val providers: List<String>,
    val thermalHeadroom: Float?,      // getThermalHeadroom(), API 30+
)
```

**Measured, never a device allowlist** (FR-TIER-1). The probe runs at first launch, on model
configuration change, and when accelerator availability changes; the result is cached with a
device+model fingerprint. Thresholds are functional spec §6.2 exactly. The user may override
in both directions with the override visible (FR-TIER-3), automatic degradation under thermal
or backlog pressure is reversible and announced (FR-TIER-4 → AC-28, P10), and the UI explains
inactive capabilities in device terms rather than tier numbers (FR-TIER-6).

Where a device can capture but not keep up, capture runs at full fidelity and processing
defers (FR-TIER-7) — which is the same mechanism as §7.3 level 4, not a second one.

A stub exists from M3: a fixed tier from a debug setting, so tier-conditional code paths are
exercised long before the detector is real.

---

## 15. UI architecture (M5)

Compose with unidirectional data flow; one `ViewModel` per screen exposing a single immutable
`UiState` from a `StateFlow`, fed by Room `Flow` queries so live updates need no polling. The
live view uses Paging 3 over a keyset-paged DAO query; Pass A partials render from a separate
in-memory `StateFlow` keyed by transmission id and are visibly superseded rather than silently
swapped (P5, FR-UI-1).

`AttributionChip` is the one component that carries G4, and it takes a non-optional state
(§10). It must be distinguishable **without colour** — shape plus fill plus label
(FR-A11Y-1 → AC-62) — and readable at maximum font scale in the dense tabular log
(FR-A11Y-3 → AC-63). No user-visible string is hardcoded and all formatting is locale-aware
even though v1 ships en-US only (FR-A11Y-5).

The **inspection surface** (P2, FR-UI-8 → AC-14) renders the phonetic lattice, the candidate
list, each candidate's per-prior contribution breakdown (which `priorBreakdown` stores for
exactly this purpose) and the grammar parse. This screen is how G4 is actually delivered and
is not optional.

---

## 16. Privacy and network enforcement

NFR-6 and FR-OBS-5 are enforced structurally, not by policy:

1. `:net` is the only module permitted an HTTP client; the dependency rule task fails the
   build otherwise (§2).
2. No analytics, telemetry or crash-reporting dependency exists in any build variant,
   including debug. A build check greps the merged dependency graph for a denylist.
3. Every `:net` entry point requires a `UserInitiated` token that can only be minted from a UI
   action, so "the capture path makes no network call" is a type-level property.
4. AC-59 verifies it empirically with a packet capture over a complete capture-and-process
   cycle.

Location never leaves the device, is stored at grid-square precision only, and is excluded
from diagnostic bundles unless explicitly included (FR-LEX-24 → AC-57, AC-58).

---

## 17. Testing architecture

| Layer | Mechanism |
|---|---|
| Unit, pure modules | JUnit5 on `:core`, `:lexicon`, `:segment`, `:rig`, `:eval` — fast, no device |
| Golden pipeline | WAV in → full pipeline → asserted records, via `WavFileSource` (AC-89) |
| Determinism | Fixed seeds, single-threaded reduction in ONNX sessions; two harness runs must be byte-identical (FR-TST-4 → AC-90) |
| Time | `TestClock` everywhere; no real waiting (AC-91) |
| Rig | `ScriptedFakeRig` (AC-92) |
| DB migration | Fixture DB per released version (AC-53) |
| Instrumented | Route verification, foreground service lifecycle, permissions, USB — the parts that only exist on a device |
| Endurance | Synthetic traffic generator with configurable activity fraction, length distribution and SNR (FR-TST-6), plus the real 8-hour run (AC-4, AC-64) |

**The harness is a product deliverable, not a test artifact** (AC-35). `:eval` is a JVM
`main()` taking a corpus manifest and a config matrix, emitting machine-readable per-tier,
per-lever metrics: WER, callsign precision/recall, boundary precision/recall, rejection rate
by reason, attribution accuracy, reliability diagrams, and a run fingerprint. Every number in
functional spec §10 is a target until this exists.

---

## 18. Open technical decisions

Carried into implementation rather than resolved here, each with a fallback that is already
designed in:

| # | Decision | Resolved by | Fallback if it goes badly |
|---|---|---|---|
| TD1 | Opus encoding path on API 26–28 (Concentus performance) | M2 | Store PCM and encode on charge |
| TD2 | Whether sherpa-onnx exposes Whisper encoder hidden states on Android | M4 | KWS-based `UnitSpotter` (§9.7 option 1) |
| TD3 | Silero VAD threshold set for squelch-tail audio | M2/M3 against the M0 tape | Squelch fusion where a rig exists (FR-SEG-5) |
| TD4 | Voiceprint embedding model choice, and whether it separates at all (R4) | M6 | Per-transmission attribution, no threading |
| TD5 | QNN context binary packaging and size | M11 | CPU path, T3 unavailable (AC-38) |
| TD6 | Whether the digest LLM earns its place (Q7) | M9 against real data | Deterministic digest only — already the requirement |

---

## 19. What this design deliberately does not decide

- **Pass C's implementation** (§9.7) — M4 chooses, and may choose neither (R3).
- **Anything downstream of M4 in internal detail.** M5–M11 have interfaces here and plans in
  the implementation plan; their internals are specified when M4's result is known.
- **Visual design.** Functional spec §13 feeds the UX guide, which does not exist yet. §15
  fixes architecture and the accessibility constraints, not appearance.
