# Offline Radio Transcriber — Technical Design Specification

**Draft 1.1 · September 2026 · Input: functional spec draft 3.2**

*Draft 1.1 applies the adversarial audit ([`audit-2026-09-06.md`](audit-2026-09-06.md)).
Sixteen defects fixed, of which five were real bugs rather than wording: the work queue's
uniqueness constraint made re-enqueueing a completed pass impossible, which silently broke
reprocessing — the mechanism half the architecture rests on; no pass had an execution timeout,
so one hung inference call would stop all processing forever; `:segment`'s declared
dependencies could not have compiled, since Silero VAD arrives through the ONNX runtime;
capture assumed a 16 kHz input device, which most USB audio adapters are not; and the FTS5
schema as written is not valid SQLite. Two design gaps were filled — model residency (§4.3)
and the digest/export interfaces (§15a) — and one unilateral spec deviation was withdrawn
(§6.1).*

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
:onnx            [JVM]  ONNX/sherpa-onnx runtime loading, session lifecycle, model residency
:capture-api     [JVM]  CaptureSource, AudioFrame, RingBuffer, SegmentSink, WAV source, resampler
:capture-android [AND]  AudioRecord source, route verification, focus/interruption, gaps
:segment         [JVM]  VAD interface, Silero impl, squelch fusion, pre-roll/post-roll, splitting
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
| `:onnx` | `:core` |
| `:capture-api`, `:lexicon`, `:rig` | `:core` |
| `:segment`, `:asr-api`, `:identity` | `:core`, `:onnx` |
| `:asr-sherpa` | `:core`, `:onnx`, `:asr-api` |
| `:capture-android` | `:core`, `:capture-api` |
| `:data` | `:core` |
| `:net` | `:core` |
| `:pipeline` | `:core`, `:onnx`, `:capture-*`, `:segment`, `:asr-*`, `:lexicon`, `:identity`, `:rig*`, `:data` |
| `:app` | `:pipeline`, `:data`, `:net`, UI-facing APIs |
| `:eval` | `:core`, `:onnx`, `:capture-api`, `:segment`, `:asr-*`, `:lexicon`, `:identity`, `:rig`, `:testing` |

`:onnx` exists because `:segment` cannot compile without it. Silero VAD arrives through
sherpa-onnx, so the draft-1 table — which gave `:segment` only `:core` — described a module
that could not have been built. Factoring the runtime out rather than letting `:segment`
depend on `:asr-sherpa` also keeps rule 2 clean: VAD is not ASR, and capture-side segmentation
must not drag a transcription engine into its dependency graph.

The forbidden edges that matter: `:capture-*` → `:asr-*` (rule 2), **anything → `:net` except
`:app`** (rule 5), and `:lexicon` / `:eval` / `:core` → anything Android (rule 3).

Rule 5 admits no exception for `:pipeline`. Draft 1 carved one out for an "asset installer",
which would have put a network-capable dependency inside the module that runs the capture
pipeline — precisely the edge the rule exists to forbid. Asset **download** lives in `:net`,
driven from `:app`; asset **install, verify and activate** (§13) are local file operations in
`:pipeline` that never touch a socket. The split falls exactly on the network boundary.

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

### 4.3 Model residency

Draft 1 did not say how models share memory, and the tier budgets do not survive naive
loading. At T2 the candidate set is Silero VAD + a streaming Zipformer (Pass A) + distil-small
(Pass B) + a speaker embedder (Pass E) + a KWS spotter (Pass C) — five graphs against a
~1.4 GB budget that FR-TIER-8 now makes a **requirement rather than an estimate**.

`:onnx` owns a `ModelResidency` manager with three classes:

| Class | Models | Policy |
|---|---|---|
| **Pinned** | VAD | Loaded for the session's lifetime. It runs on every frame; evicting it is never right |
| **Hot** | Pass B, Pass C | LRU with a floor of one; loaded on first use, evicted only under memory pressure or a tier change |
| **Cold** | Pass E embedder, enhancer, LLM | Loaded per batch of work and released. Pass E runs on a fraction of transmissions (FR-SPK-2's duration floor) and batches naturally |

Budget is checked at tier entry by summing declared model footprints from the registry
(§8.4), and enforced at runtime against `Debug.getMemoryInfo()` sampling. Exceeding it degrades
tier rather than risking the OOM killer, which on a capture app means losing the session
(AC-103). `onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)` evicts everything Cold immediately.

The single `infer-heavy` slot is what makes this tractable: only one Hot model executes at a
time, so peak activation memory is one model's, not the sum.

---

## 5. Capture subsystem (M2)

### 5.1 CaptureSource

```kotlin
interface CaptureSource {
    val deviceFormat: AudioFormat      // what the hardware actually gives us
    val outputFormat: AudioFormat      // always 16 kHz mono PCM16, post-resample
    fun start(): Flow<CaptureEvent>    // Frames, RouteChanged, Interrupted, Resumed, Failed
    fun stop()
    fun routedDevice(): AudioDeviceInfo?   // null on the file source
}
```

**Two formats, not one.** Draft 1 declared the format "fixed: 16 kHz" as though the device
would simply comply. Most USB Audio Class adapters — the intended input path — expose 44.1 or
48 kHz and nothing else, and `AudioRecord` construction at an unsupported rate fails outright
rather than resampling politely. The source therefore opens at the device's native rate,
negotiating in order of preference (16 000, 48 000, 44 100), and resamples to 16 kHz in the app
(FR-CAP-2a).

The resampler is a fixed-point windowed-sinc polyphase filter in `:capture-api` — deterministic
by construction, no platform dependency, identical on desktop and device, which matters because
**the resampler is in the signal chain of every accuracy number the project publishes**
(FR-TST-4, AC-97). Its identity and the device rate are recorded on the session. 48 → 16 kHz is
an exact 3:1 decimation, which is the common case and the cheap one; 44.1 → 16 kHz is a 160:441
rational conversion and is the reason this is a real filter rather than a decimator.

Two implementations, and the file-backed one ships in M2 (FR-TST-1 → AC-89):

- `AudioRecordSource` (`:capture-android`) — `MediaRecorder.AudioSource.UNPROCESSED` when
  `AudioManager.getProperty(PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)` reports it, falling
  back to `VOICE_RECOGNITION`; explicitly **not** `MIC` with effects, which applies AGC and
  noise suppression that fight the ASR. Which source was actually obtained is recorded on the
  session — it changes the signal, so it changes the numbers.
  `setPreferredDevice()` from the user's selection, then `getRoutedDevice()` verification
  (§5.2).
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
(default 60 000, splitting a stuck carrier → AC-70), the pre-roll prepend (FR-SEG-4) and a
`postRollMs` tail past the VAD close (default 400, FR-SEG-8).

**Segment audio is written incrementally, not buffered.** On entering `SPEECH` the segmenter
opens the staging file and appends every accepted window as it arrives; the pre-roll is
written first, from the ring snapshot. A 60-second stuck carrier therefore costs one file
handle, not 60 seconds of heap — and the 8-second ring buffer is sized for pre-roll only,
which is the only thing it is for.

**Segmentation parameters are tier-invariant** (FR-SEG-7). The segmenter takes no `Tier`
argument and cannot be given one — the constructor does not accept it. This is a deliberate
type-level lock, because CON-SEG-1 makes segmentation the one pass whose mistakes are
permanent, and AC-39 (a T0 capture reprocessing to match a native T3 capture) is only true
because boundaries are identical across tiers. Anything that would vary boundaries by device
capability must be rejected in review even where it would help, because the record it produces
can never be repaired.

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
thread — a memcpy-rate operation that cannot stall capture. Compression is a queued `STORE`
step, and **the codec is FLAC, not Opus** (FR-STO-2a):

- FLAC via a small JNI `libFLAC` binding, 16 kHz mono, compression level 5. Roughly 4.5 MB per
  wall-clock hour at 15% duty, ~13 GB per year of daily 8-hour shifts.
- The encoder is exactly reversible, so its correctness is checkable rather than assessable:
  decode the output and compare to the source PCM byte-for-byte before deleting the staging
  file. A lossy encoder affords no such check.

The PCM file is deleted only after that comparison passes. A crash in between leaves a
recoverable PCM file, which reconciliation (§12.4) finds.

> **Draft 1 specified Opus here and adopted Q5's indefinite-retention recommendation on its
> own authority. Both were wrong, and the second was a process error** — Q5 is an open question
> owned by product, and this document's own rule is to raise a change rather than diverge.
>
> The technical error is the more interesting one. Opus is a perceptual codec; Pass C
> (FR-LEX-4) is sub-phoneme acoustic discrimination on narrowband noisy speech, which is the
> worst case for perceptual coding. The ordering makes it dangerous rather than merely
> suboptimal: **the codec is committed in M2, and Pass C is not measured until M4**, so a lossy
> default would surface at M4 as the core accuracy thesis failing, with no obvious reason to
> suspect the storage layer. That is R10, and lossless retention retires it outright for about
> 13 GB a year.
>
> Opus remains available and may well be adopted — but only behind FR-STO-2b's measured
> comparison on the harness (AC-102), and the measurement that matters is its effect on
> **Pass C**, not on Pass B. Retention *duration* stays FR-STO-3's 30-day default until
> product answers Q13.

Where continuous-archive capture is enabled (FR-SEG-9, Q14), the unsegmented stream is written
to `archive/<sessionId>/<startSample>.flac` in bounded chunks, indexed by sample position so a
re-segmentation pass can seek into it. This is the only mechanism by which segmentation itself
becomes reprocessable (AC-96), and it is off by default.

---

## 7. Work queue and orchestration (M2)

### 7.1 Durable queue

An on-disk table, not an in-memory channel (FR-RUN-2 → AC-45):

```sql
CREATE TABLE work_queue_item (
  id INTEGER PRIMARY KEY,
  transmission_id TEXT NOT NULL,
  pass TEXT NOT NULL,
  state TEXT NOT NULL,            -- READY | LEASED | FAILED | DEFERRED   (no DONE; see below)
  priority INTEGER NOT NULL,      -- live traffic > reprocessing
  attempt_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  shed_level INTEGER NOT NULL DEFAULT 0,
  lease_run_id TEXT,              -- process run uuid; a lease from another run == crashed
  deadline_at INTEGER,            -- wall clock; a pass past this is cancelled (FR-RUN-10a)
  enqueued_at INTEGER NOT NULL,
  started_at INTEGER
);
CREATE INDEX idx_wq_ready ON work_queue_item(state, priority, enqueued_at);
CREATE UNIQUE INDEX idx_wq_active ON work_queue_item(transmission_id, pass)
  WHERE state IN ('READY','LEASED','DEFERRED');
```

> **Draft 1 had `UNIQUE(transmission_id, pass)` unconditionally and kept `DONE` rows, which
> made re-enqueueing a completed pass impossible.** Every reprocess in the product — FR-REP-1,
> FR-REP-5, the lexicon-update re-run of Pass D (FR-REP-6, the cheapest and highest-value one),
> and the entire cross-tier flow (FR-REP-8..11) — is exactly that operation. The constraint
> would have thrown on the second attempt at any pass, and the failure mode is worse than an
> error: nothing in the acceptance criteria distinguishes "reprocess ran and changed nothing"
> from "reprocess was silently refused".
>
> The fix is a **partial** unique index over active states only, plus deleting rows on
> completion. The queue is a queue, not a history — pass results and their fingerprints (§3.4)
> are the durable record of what ran, and they already carry everything a reprocess needs to
> decide whether to run again.

The orchestrator leases a batch, executes on `infer-heavy`, and commits the pass result and
the queue transition **in one transaction** with the transmission's state change. On launch,
any lease whose `lease_run_id` is not the current run is cleared and its transmission returns
`PROCESSING → CAPTURED` (FR-RUN-8 → AC-47). Passes are idempotent, so replay is always safe.

**Pass timeout** (FR-RUN-10a → AC-99). Each lease carries `deadline_at =
now + max(minTimeout, segmentDurationMs / expectedRtf × slack)`. A watchdog on the orchestrator
cancels the coroutine and, where the runtime supports it, the underlying inference session;
the item goes to `FAILED` with `error = timeout` and normal bounded-retry handling applies.

Draft 1 had no timeout at all. With one inference slot (§4), a single hung native call would
have stopped **all** processing permanently while capture kept filling the queue behind it —
producing a live-looking app with a frozen transcript and a growing backlog, which is a far
worse failure than crashing. F18 covers a pass that errors; nothing covered a pass that never
returns.

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

**Battery is an input, so it must have an output.** Draft 1 listed battery level and charging
state among the signals and then never used them, which is a signal that does nothing — worse
than absent, because it reads as covered. The mapping: below a configurable critical threshold
(default 15%) and not charging, the controller enters **level 4** — capture and enqueue only,
all passes deferred. Inference is the largest discretionary draw in the app, deferring costs
nothing permanent because the queue is durable and the audio is retained, and it directly
extends the thing NFR-3 is about. Processing resumes on charge or on user override.

This is also why level 4 is worth having distinct from level 5: the correct response to *"the
battery is nearly gone"* is to keep recording and stop thinking, not to stop recording.

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
behind a UI filter (FR-ASR-6 → AC-8).

AC-6 — zero accepted transcripts from a pure-noise tape — is the single most important test in
the plan, and it runs on every harness invocation **against the development noise tape**, with
the sealed eval noise tape held for M11 (§14A.2, FR-TST-7 → AC-101). Draft 1 said "runs on
every harness invocation" while the only noise tape the spec provided was eval-only and sealed
until the last milestone; the two statements could not both be true, and the consequence would
have been tuning the hallucination controls with no measurement at all.

**Thresholds are fitted, not inherited.** The defaults above are Whisper's conventional values,
and Whisper's were chosen against web audio, not squelch tails. Each is refitted against the
development noise tape during M3 and the fitted values recorded as configuration; adopting
upstream defaults for the project's #1 failure mode would be assuming the answer to the
question M3 exists to ask (TD3).

### 8.3 Transcript versioning

`transcript` rows are append-only with exactly one `is_current = 1` per transmission, enforced
by a partial unique index (`CREATE UNIQUE INDEX ... WHERE is_current = 1`, written by hand in a
migration — Room's `@Index` cannot express a partial index). Superseding writes a new row and
flips the flag in one transaction; nothing is deleted (FR-REP-3 → AC-31, P9).

**Pass A partials are not persisted; the Pass A *final* is.** Draft 1 said both — in-memory in
§15 and written with `is_current = 0` in §8.3 — which is a contradiction with a real cost
either way: persisting every revision of a live hypothesis writes hundreds of rows per
transmission for data no one reads, and persisting none of it destroys the second
architecturally-diverse hypothesis that ensemble fusion needs (FR-ASR-12..14). The split:
revisions live in an in-memory `StateFlow` keyed by transmission id and are never written; the
hypothesis current at segment close is written once, `pass='A'`, `is_current = 0`, retained as
fusion input and as the per-token agreement signal Pass D consumes.

### 8.4 Model registry

A model is `ModelDescriptor(assetId, version, family, size, quantization, providerBinaries,
isFineTuned, fineTuneId, trainingDataDescription, licence)` (FR-ASR-10, FR-LEX-28, NFR-6b).
Tier + provider resolve to a `ModelSet`; a missing or invalid model file falls back to the
next-lower model and surfaces it (F13). Descriptors also declare a **memory footprint**, which
§4.3 sums against the tier budget.

**Side-loaded models are untrusted input** (FR-ASR-8). A user-supplied ONNX file is a
graph a native runtime will execute, and the install path treats it accordingly: validate the
file is well-formed and its input/output signature matches the expected shape *before*
activation, load it first in a probe run over a bundled fixture clip, and refuse activation on
crash or signature mismatch — the previous model stays active (FR-AST-2). The user is told the
model is unverified and that its accuracy figures are their own. This does not make executing
a third-party graph safe; it makes it deliberate, which is the most the design can offer for a
feature D13 requires.

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

The ULS import is the one asset operation with a user-visible cost: ~1.1M records to parse,
insert and index on a phone — minutes of work and several hundred megabytes. It runs as a
WorkManager job with progress, is resumable, swaps transactionally against the live table
(FR-LEX-30), and the app stays fully usable at reduced ranking confidence throughout
(FR-LEX-3). Only the fields the prior needs are imported — callsign, name, state, licence
status — not the full record set.

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

`Thread` carries an open `kind` field (`qso | net | scanner | unknown`) and participation is a
join table rather than a two-station assumption, so Q9's net detection — one dominant
voiceprint alternating with many others on a fixed frequency, and a check-in sequence that is
an unusually rich source of confirmed callsigns — remains addable in v2 without a migration.
v1 sets `kind = unknown` and does not detect anything.

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

**Descriptors are untrusted data and they drive a regex engine.** A descriptor is a file the
user can write or download, and `expect` patterns are applied to every polled response — a
pattern with catastrophic backtracking would hang the rig thread indefinitely against
adversarial or merely unlucky input. Patterns are compiled once at load, rejected if they
exceed a complexity bound (nested unbounded quantifiers), and every match runs against a
length-capped input under a watchdog. A descriptor that trips any of these falls back to the
null module with a clear error (FR-RIG-11), which is the same path an invalid descriptor
already takes.

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

- **Ids** are `TEXT` ULIDs — sortable by creation time, generated offline, and collision-free
  across devices, so an export and re-import cannot alias two different records onto one id
  (FR-STO-6 → AC-80). *Draft 1 said "safe to merge", which overclaims: ULIDs prevent id
  collisions, they do not resolve whether two databases' Stations or Voiceprints are the same
  entity. AC-80 requires import onto another device — restore, not merge — and **merging two
  populated databases is out of scope**, which the export/import UI must say plainly rather
  than leaving the user to discover.*
- **FTS5** as an external-content table over the whole `transcript` table, kept in sync by the
  standard insert/update/delete triggers; `is_current` is filtered in the join, not in the
  index. *Draft 1 specified the external-content table "where `is_current = 1`" — external
  content tables take no `WHERE` clause, so that schema does not create. Indexing superseded
  transcripts costs a little space and buys something useful anyway: search can optionally
  reach text that was later revised, which P9 ("nothing is deleted quietly") argues for.*
  Search filters (callsign, frequency, band, time, attribution state, rejected) are ordinary
  indexed predicates joined against the FTS match (FR-UI-3).
- **Indices:** `transmission(session_id, sample_position)`, `transmission(started_at_utc)`,
  `transmission(attribution_state)`, `transmission(is_reprocess_candidate)`,
  `callsign_candidate(transmission_id, rank)`, `voiceprint(bound_station_id)`,
  `work_queue_item(state, priority, enqueued_at)`.
- **`sourceId`** on Session and Transmission from day one, unused in v1 (Q6) — a column now
  costs nothing and a migration later costs a release.
- `PRAGMA journal_mode=WAL`, `synchronous=NORMAL`. WAL is what makes NFR-4b (survive a kill
  mid-write) true; the pass/queue transaction boundary (§7.1) is what makes it sufficient.

### 12.2 Audio store

`audio/<sessionId>/<transmissionId>.flac`, with `pcm/` as the pre-encode staging area
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
device+model fingerprint.

Thresholds are functional spec §6.2, where **the RAM and throughput conditions are conjunctive
at every tier** — a device must both finish the work and hold the models while doing it. The
tier is additionally rejected if §4.3's summed model footprint exceeds the tier's resident
budget (FR-TIER-8 → AC-103), which is the check that makes the budget column mean something. The user may override
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

## 15a. Digest and export (M9) — interfaces only

Included because draft 1 declared `PassId.F_DIGEST` and then never mentioned the digest again,
which is a poor showing for goal G1 — the digest is the primary user-facing deliverable, not
an afterthought at the end of the pipeline.

```kotlin
interface DigestGenerator {                       // deterministic, no LLM (FR-DIG-2)
    fun generate(window: TimeRange, scope: DigestScope): Digest
}
data class Digest(
    val sections: List<DigestSection>,            // typed, not prose strings
    val generatedAt: Instant, val fold: SourceRef,
)
interface DigestNarrator {                        // optional, T3, additive only (FR-DIG-3)
    fun narrate(thread: ThreadSummary, entities: ResolvedEntities): String
}
interface Exporter { fun export(query: RecordQuery, sink: Sink): ExportResult }  // ADIF, CSV, POTA
```

Three properties the interfaces enforce rather than merely encourage:

- `Digest.sections` is **typed data, not text**. The deterministic digest renders in the UI,
  and the same structure is what the optional narrator is handed — so an LLM digest can only
  ever be *additive* (FR-DIG-3, FR-DIG-6 → AC-86), and the app with no LLM present renders a
  complete digest from the same objects (AC-84).
- `DigestNarrator` takes `ResolvedEntities`, never raw transcripts, and its output is rendered
  through a filter that rejects any token matching the callsign grammar and not present in the
  supplied entity set. D5 is enforced at the boundary, not trusted to the prompt (FR-DIG-4).
- Station counts in every section carry attribution state, because a digest that flattens
  confirmed and inferred fails G4 exactly as a log would (AC-85).

`Exporter` takes the same `RecordQuery` the search UI builds, so a confirmed-only export
(FR-EXP-5) is a filter rather than a second code path, and `INFERRED` records carry their state
into every format (FR-EXP-4 → AC-33). ADIF is the one external interop surface in the product
and is validated against a real logging program during M9 (AC-34).

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
| Determinism | Fixed seeds, pinned intra-op thread count, single-threaded reduction in ONNX sessions. Two harness runs are byte-identical **within a fixed (machine, execution provider, thread count)** — see below (FR-TST-4 → AC-90) |
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

**Determinism is bounded, and the bound must be stated with every number.** Bit-identical
output holds for a fixed machine, execution provider and thread count. It does **not** hold
across CPU and NPU, across chipsets, or across ONNX Runtime versions — floating-point
reduction order differs, and the NPU path is quantized besides. AC-90 tests reproducibility
within a configuration, which is what distinguishes a real improvement from noise; a
cross-provider comparison is a different measurement and must be run as one. Every report
therefore carries `(machine, provider, threads, runtime version, fold)`.

**The harness needs an on-device runner, and draft 1 implied desktop only.** AC-36 requires
each tier to meet its row of the NFR-1 table, and T3 is defined by an NPU that exists only on
the phone — so a desktop-only harness structurally cannot measure the reference tier, which is
the tier the entire flagship-first argument rests on. `:eval` therefore ships two entry points
over one implementation: the JVM `main()` for T0–T2 work and fast iteration, and an
instrumented-test runner that executes the same code against the same manifest on-device,
emitting the same report format. The corpus is pushed to app-private storage; the report is
pulled back. This is an M2 obligation, not an M11 one — otherwise the first attempt to measure
T3 arrives at the last milestone.

---

## 18. Open technical decisions

Carried into implementation rather than resolved here, each with a fallback that is already
designed in:

| # | Decision | Resolved by | Fallback if it goes badly |
|---|---|---|---|
| TD1 | FLAC JNI binding versus a pure-JVM encoder; and whether Opus is ever adopted | M2 build, M4 measurement | Store PCM and compress on charge. Lossless stands until FR-STO-2b's comparison exists (R10) |
| TD7 | Whether 44.1 kHz-only adapters appear in practice, and the resampler's cost on the floor device | M2, with the two dongles bought in M0.1 | Reject 44.1-only devices with a clear message rather than resampling badly |
| TD8 | Continuous-archive storage cost in practice (Q14) | M2, measured over the M0 recording sessions | Off by default; gated segments only |
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
