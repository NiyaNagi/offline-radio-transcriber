# Changelog

Every commit that lands completed, tested work gets an entry here — this is the project's
build log, not just its git history, because `git log` doesn't carry *why* a change was safe,
which requirements/ACs it settles, or what it deliberately left undone. [`spec/build-plan.md`](spec/build-plan.md)
is the forward-looking todo list; this file is the record of what actually happened against it.

**Format, followed by every entry below and required for every entry after it** (see
[`AGENTS.md`](AGENTS.md)'s working agreement and the build-plan's standing preamble, both of
which now require this):

```
## YYYY-MM-DD

### <commit-hash> — <prompt-id, if any> · <one-line summary, matches the commit subject>

**Scope:** which module(s)/directories this touched (its build-plan "owns" list, if it has one).
**Requirements/ACs:** ids established or satisfied (cite `FR-*`, `AC-*`, `NFR-*`, `D*`, `R*` — the
same ids the spec and tests use). "none new" if the change is process/docs only.
**What changed:** the substance — added/changed/fixed, in enough detail that someone who wasn't
there can tell what exists now that didn't before, and why.
**Verified:** the exact command(s) run and their result (green build, N tests passing, etc.) —
never a bare "works". A number without its fold/machine/provider is not a result (constitution
VI) and neither is a changelog entry without how it was checked.
**Left open / not done:** anything a reasonable reader would otherwise assume was covered but
wasn't — a probe left NOT RUN, a device-only criterion untested on Robolectric, a follow-up
filed elsewhere. Omit only if genuinely nothing applies.
```

Entries are grouped by date, newest first; within a date, newest commit first. Merge commits get
one entry covering what the merge brought in, not a restatement of the branch's own entry.

---

## 2026-09-08 (just after midnight — CI catches a real dependency-declaration bug)

### (pending) — Declare numpy as a real corpus dependency (found by CI, not local testing)

**Scope:** `corpus/pyproject.toml` only.
**Requirements/ACs:** none — dependency hygiene.
**What changed:** pushing commit `3c5a274` (the smoke-test build + release wiring) triggered
`ci.yml` on a clean GitHub-hosted runner for the first time in a while, and its `corpus` job
failed: `ModuleNotFoundError: No module named 'numpy'` collecting
`test_probe_speaker_separation.py`, `test_synth_channel.py` and `test_synth_generator.py`. P6's
`synth/channel.py`, `synth/generator.py`, `synth/tts.py` and `probes/speaker_separation.py` all
import `numpy` unconditionally at module load — not lazily, unlike the `r1-real` extra's
torch/transformers/etc. — but `pyproject.toml`'s base `dependencies` was `[]`. Every local session
this repo has seen (including this one) had numpy already present as a transitive dependency of
the `r1-real` extra's own toolchain (installed while proving the real sherpa-onnx/LoRA path),
which silently masked the gap. Added `numpy>=1.26` to base `dependencies`.
**Verified:** built a throwaway venv (`python -m venv`), installed only `corpus[dev]` into it
(no `r1-real` extra, so no incidental numpy from that path), ran `pytest corpus` inside it —
70 passed, 1 skipped, matching this repo's normal local result — confirming the fix actually
closes the gap rather than trusting the diagnosis. Also `python tools/spec-check/spec_check.py`
(7/7 OK).
**Left open / not done:** none for this specific bug. Worth noting as a process point: this is
exactly why CI on a clean runner matters even when every local check passes — a session's own
machine can accumulate installed packages across unrelated work (here, the real-R1 toolchain)
that quietly cover for a missing declaration.

## 2026-09-07 (later still — GitHub Releases, at the user's request)

### (pending) — Wire GitHub Releases so a build is downloadable without going through the agent

**Scope:** new `.github/workflows/release.yml`, new `tools/release_notes.py`, `README.md`.
**Requirements/ACs:** none — release/distribution tooling, not spec-governed.
**What changed:** the user asked to "wire up proper GitHub releases and build so I can download
from the releases view there with a nice changelog." Two release shapes, both attaching the debug
APK CI already builds and verifies:
- A rolling **`latest-build` prerelease**, republished on every push to `main` — `gh release
  delete latest-build --yes --cleanup-tag || true` then `gh release create`, so there is always
  exactly one stable link for "the current build," not an accumulating pile of dated releases.
- A proper **versioned release** on any `vX.Y.Z` tag push, for an actual numbered milestone
  later.
- `tools/release_notes.py` extracts release notes from `CHANGELOG.md` itself rather than a
  separate summary someone has to remember to write: it groups by the calendar date in each
  `## YYYY-MM-DD (...)` heading and returns every section under the newest date, so a rolling
  release republished several times in one day always shows that whole day's entries, not just
  the single most-recent (often tiny) sub-section. Verified locally against this repo's real
  `CHANGELOG.md` before wiring it into CI.
- Both release steps run only after the same Android-unit-test + `dependencyRules` gate CI's
  `android` job already uses (`ci.yml`) — a release is cut from a build that passed, not just one
  that compiled.
- `README.md` gained a short "Downloads" section pointing at the Releases page, with an explicit
  caveat that the APK is unsigned (debug key) and a v0 smoke test — pointing at the newest
  changelog entries rather than letting a download imply more maturity than exists.
**Verified:** `python -c "import yaml; yaml.safe_load(open('.github/workflows/release.yml'))"`
(valid YAML); `python tools/release_notes.py` run locally against this repo's actual
`CHANGELOG.md`, confirmed it returns the newest date's entries correctly. **The workflow itself
has not yet run on GitHub** — that happens on the next push to `main`, which is this same commit;
its actual behavior (release creation, asset upload, gh CLI auth via the default `GITHUB_TOKEN`)
is unverified until then.
**Left open / not done:** the APK is unsigned — a real release signing config (keystore +
secrets) is a separate, deliberate decision, not done here. No automated versioning scheme for
tagged releases yet (someone still has to decide and push a `vX.Y.Z` tag by hand).

## 2026-09-07 (later that night — a real v0 smoke-test build, at the user's request)

### (pending) — Wire real microphone capture end to end, so a debug APK does something on a real phone

**Scope:** new `capture-android/src/main/kotlin/org/ort/capture/android/AndroidAudioIo.kt`, new
`pipeline/src/main/kotlin/org/ort/pipeline/capture/RealCaptureService.kt`, new
`pipeline/src/main/AndroidManifest.xml` service entry, `app/src/main/kotlin/org/ort/app/MainActivity.kt`,
`app/src/main/kotlin/org/ort/app/status/StatusActivity.kt`,
`app/src/main/kotlin/org/ort/app/transmissions/TransmissionListActivity.kt`,
`app/build.gradle.kts` and `pipeline/build.gradle.kts` (both gained direct Room + androidx.core
dependencies — see "What changed").
**Requirements/ACs: none new, and this is explicitly not build-plan output.** Every piece
composed here (`AudioRecordSource`, `Segmenter`, `WorkQueue`, `OrtDatabase`,
`CaptureStatusRepository`) was already built test-first by P4/P5/P8/P11 against fakes/Robolectric;
what didn't exist was the glue actually running them together against a real microphone on real
hardware. The user asked directly "can we create a buildable APK to validate other aspects" and
then, after being shown the app only reached a placeholder screen, "build as much of the app as
is available... I am on my phone right now." This entry is that work, done outside the
constitution's strict-TDD process (there is no test-first version of "does the real microphone
produce real audio" — the same gap `RouteVerifier`'s real counterpart and every other Android SDK
wrapper in this repo already accepts), and said so plainly rather than dressed up as a formal
prompt's deliverable.
**What changed:**
- `AndroidAudioIo` — the real, previously-nonexistent `AudioIo` implementation (only
  `FakeAudioIo` existed before this). Uses `android.media.AudioRecord`/`AudioManager` directly;
  route-change/interruption detection is deliberately minimal (relies on read-error detection
  rather than proactive `AudioDeviceCallback` wiring) — enough to prove real capture, not the
  full FR-CAP-3/FR-RUN-11 device-side implementation.
- `RealCaptureService` (`:pipeline`, not `:capture-android`, because it needs `:segment` and
  `:data`, which `:capture-android` may not depend on — technical design §2's module graph):
  constructs a real `OrtDatabase`, `WorkQueue`, `AndroidAudioIo`+`AudioRecordSource`, and a real
  `Segmenter` behind a `SegmentSink` that stages PCM to disk incrementally, FLAC-encodes via the
  existing `FlacStore`/`DeflatePredictiveCodec` (decode-and-compare, same discipline as P8's
  `CaptureService`), inserts a real `TransmissionEntity`, and enqueues it on the real `WorkQueue`.
  Also includes `EnergyVadModel` — a simple RMS-energy threshold, **explicitly not the real
  Silero VAD**, because no real Silero ONNX model/runtime exists anywhere in this repo yet
  (only `ScriptedVad` in `:segment`'s own tests); wrapped in the real `SileroVad` hysteresis class
  so swapping in a real model later is a one-line change, not a rewrite.
- `MainActivity` — replaced with a real driver of `PermissionsFlow`'s Android side (actual
  `ActivityCompat.requestPermissions` calls, not just the state machine P8 already tested),
  starting `RealCaptureService` and the real `StatusActivity` once permitted.
- `StatusActivity`/`TransmissionListActivity` — both gained opt-in live-data polling
  (only activated when a `session_id` intent extra is present, so `StatusActivityTest`/
  `TransmissionListActivityTest` — which never set that extra — are untouched and still pass)
  querying the real database every 2 seconds. Shed level is reported as a static, honest 0 — no
  real battery/backlog telemetry is wired for this test, and fabricating one would be exactly the
  kind of invented number the constitution forbids; the transmission list reads "(captured, not
  yet transcribed)" for the same reason (no ASR pass is constructed in production yet).
**Verified:** `./gradlew build dependencyRules coverageMatrix` — full green, all pre-existing
tests (including `StatusActivityTest`/`TransmissionListActivityTest`) unaffected; manually
inspected the built `app-debug.apk` with `aapt2 dump badging` (minSdk 26, correct permissions,
`RealCaptureService` present via manifest merge). **Not verified: actual behaviour on a physical
device** — that is what this build exists to let the user check next, outside this session.
**Left open / not done:** real Silero VAD (no model exists yet), real route-change/focus
handling in `AndroidAudioIo`, no ASR pass wired into production capture (transcripts stay
"not yet transcribed" until `PassB`, from P11, is actually constructed somewhere real), shed
telemetry, and a proper M5 navigation host (this MainActivity is still a shim, not Compose).

## 2026-09-07 (night, cont. — fix the coverage-tool regex gap flagged earlier)

### (pending) — CoverageMatrix: recognise requirement ids with alphanumeric segments

**Scope:** `buildSrc/src/main/kotlin/org/ort/gradle/CoverageMatrix.kt`,
`buildSrc/src/test/kotlin/org/ort/gradle/CoverageMatrixTest.kt`. No session currently owns
`:buildSrc`; this is a small, well-contained fix to a shared tool, not a drive-by edit to
someone else's module.
**Requirements/ACs:** none new — tool accuracy only. Makes `FR-A11Y-1` (the real accessibility
requirement P11's `TransmissionListViewStateMapperTest` correctly cites) count as covered
instead of orphaned.
**What changed:** the earlier hygiene pass (`b0fbb60`) documented but didn't fix a real gap in
`CoverageMatrix`'s id-recognition regex; P11 immediately produced a second, concrete case of it —
`@Requirement("FR-A11Y-1")` was flagged as an orphan even though `FR-A11Y-1` is a real,
correctly-cited functional-spec requirement (§7.16), because the regex's middle-segment pattern
(`[A-Z]{2,5}`) rejected the digits in `A11Y`. Widened both `REQUIREMENT` and `NAME_ID` to
`[A-Z][A-Z0-9]{1,5}` (still starts with a letter, so it doesn't start matching arbitrary numbers)
so alphanumeric segment codes like `A11Y` are recognised. Added a regression test proving
`FR-A11Y-1` is now counted as a real requirement, shows as covered, and is not orphaned.
**Deliberately not attempted here:** widening the regex further to recognise bare `F`/`D`/`R`/`Q`
ids (`F13`, `D28`, `R15`, `Q16` — flagged in `b0fbb60`) — those single-letter prefixes carry a
real risk of false-positive matches against ordinary prose elsewhere in the spec (quarter/date
references, resistor-style callouts, etc.) that would need checking against the whole spec
corpus before landing, which this session didn't have the scope to do safely. Left as still-open,
now with a narrower, better-understood shape than before.
**Verified:** `./gradlew :buildSrc:test` (new test green, all existing ones still pass);
`./gradlew build dependencyRules coverageMatrix` (full multi-module build green); coverage
matrix now reports 419 requirement ids (was 413 — six previously-invisible alphanumeric-segment
ids, including `FR-A11Y-1`, now counted), 101 covered (was 100), 1 orphan remaining (`F13`, the
documented bare-prefix gap).
**Left open / not done:** the bare `F`/`D`/`R`/`Q` id gap, as above.

## 2026-09-07 (evening, cont. — post-merge ktlint fix)

### (pending) — Fix a ktlint class-signature violation in RealSherpaDecoder after merging

**Scope:** `asr-sherpa/src/main/kotlin/org/ort/asrsherpa/real/RealSherpaDecoder.kt` only.
**Requirements/ACs:** none — style only.
**What changed:** merging the real-sherpa-onnx follow-up in surfaced a ktlint
`standard:class-signature` violation (`: SherpaDecoder, AutoCloseable` needed to start on its own
line) that the follow-up's own narrower verification (`:asr-sherpa:test`, `dependencyRules`)
didn't run against — `./gradlew build`'s full `ktlintMainSourceSetCheck` caught it. Fixed with
`./gradlew :asr-sherpa:ktlintFormat` rather than hand-editing, so the result matches the project's
own formatter exactly.
**Verified:** `./gradlew build dependencyRules` — full multi-module build green (830 tasks).
**Left open / not done:** none — this is a reminder for future sessions to run the full
`./gradlew build`, not just their own module's tests, before calling a merge clean; noted here
rather than silently fixed with no trace.

## 2026-09-07 (evening, cont. — drafting Q16 while P11 and a P10 follow-up run)

### (pending) — Draft the Q16 labelling protocol

**Scope:** `docs/reference/labelling-protocol.md` (new), `spec/open-questions.md`,
`spec/build-plan.md`, `AGENTS.md` — documentation only, no code.
**Requirements/ACs:** Q16 (does not close it — see below), Q2 (records what audio to capture and
why the noise tape specifically cannot be substituted).
**What changed:** while P11 and a P10 follow-up ran in the background, drafted a concrete
labelling protocol answering every open question Q16 lists: transmission boundaries are defined
as keying edges (PTT down/up), matching what `:segment`'s `AC-69` actually measures rather than a
speech-content boundary; doubling gets two rows when separable, one `doubled_unresolvable` row
when not; partial audibility is **never omitted** (three certainty levels — `certain`/
`uncertain`/`partial` — with an explicit rule against looking a doubtful callsign up against a
database while labelling, which would leak the very prior AC-10/AC-11 test into the ground
truth); threads default to a 20-minute same-frequency/shared-participant continuation rule,
explicitly flagged as an unvalidated default for the pilot to test; phonetic/tactical/club-station
handling; non-speech content (DTMF, courtesy tones, data bursts, CW IDs) is out of ASR scope and
never scored as a missed callsign; and a concrete TSV output schema at transmission grain. Also
investigated whether a downloaded CC0 "radio static" clip could stand in for the real development
noise tape — **concluded no and did not pursue it**: Q2 is explicit that AC-6 needs real squelch
tails from the two specific radios (TH-D75A, SDS150), not generic noise, so a substitute would be
actively misleading rather than useful groundwork. Updated `open-questions.md`'s Q16 entry to
"DRAFTED, not yet piloted" (not closed — closing it needs the pilot round this document itself
prescribes, which needs the Q2 recording first), added the doc to `AGENTS.md`'s reference table,
and updated `build-plan.md`'s "yours, not a session" line to point at it.
**Verified:** `python tools/spec-check/spec_check.py` (7/7 OK — Q16's status-line edit didn't
break the closed/open-question-to-decision mapping check).
**Left open / not done:** the actual recording (Q2) and the pilot labelling round (Q16's real
closing condition) — both explicitly require the user, not a session. The document's own "open
items for the pilot round" section lists three specific defaults (the 20-minute thread gap
foremost) that should be revisited once real audio exists.

---

## 2026-09-07 (P10 follow-up — real sherpa-onnx JVM binding)

### (pending) — P10 follow-up · Real sherpa-onnx JVM binding and a genuine ASR decode

**Scope:** `asr-sherpa/` (new `real/RealSherpaDecoder.kt`, its README, its `.gitignore`, the
gated `RealSherpaDecoderRealModelTest`), `gradle/libs.versions.toml` (new `sherpaOnnx` version
and two library coordinates), `settings.gradle.kts` (one new repository).

**Requirements/ACs:** FR-ASR-1 (the decode path this proves is real). Not AC-6 — see "Left open"
below; this task was explicitly scoped narrower than AC-6.

**What changed:** P10 built `SherpaAsrEngine` against a `SherpaDecoder` seam with only
`FakeSherpaDecoder` behind it, because no real JVM sherpa-onnx binding had been confirmed
resolvable and no model was on hand to exercise it. This follow-up closes that:

- **A real JVM/JNI binding is resolvable and used.** sherpa-onnx does not publish its Kotlin/JVM
  artifact to Maven Central — upstream only documents a build-it-yourself CMake + `kotlinc-jvm`
  path. What upstream does publish, as GitHub Release assets per tag, is a `sherpa-onnx-jvm`
  jar (the Java/Kotlin API classes) plus per-platform `sherpa-onnx-native-lib-<platform>` jars
  bundling the native library. These are mirrored onto JitPack
  (`com.github.k2-fsa.sherpa-onnx:<artifact>:1.13.7` — the same version the real R1 probe already
  confirmed usable via Python, `results/r1-lora-export.md`), which is an ordinary resolvable
  Maven repository. Added `maven { url = uri("https://jitpack.io") }` to
  `settings.gradle.kts`'s `dependencyResolutionManagement` (required — `FAIL_ON_PROJECT_REPOS` is
  set, so a project-level repository isn't an option), and
  `implementation(libs.sherpa.onnx.jvm)` / `runtimeOnly(libs.sherpa.onnx.native.win.x64)` in
  `asr-sherpa/build.gradle.kts`. Verified genuinely resolvable, not just metadata: after
  `:asr-sherpa:compileKotlin` and `:asr-sherpa:test`, `sherpa-onnx-jvm-1.13.7.jar` (187KB) is
  present in `~/.gradle/caches/modules-2/files-2.1/com.github.k2-fsa.sherpa-onnx/` — an actual
  jar, not a `.pom` stub. `./gradlew dependencyRules` still passes with `:asr-sherpa`'s module
  edges unchanged (`:asr-api`, `:core`, `:onnx`) — this is an external library dependency, not a
  new module edge.
- **`real.RealSherpaDecoder`** (`asr-sherpa/src/main/kotlin/org/ort/asrsherpa/real/RealSherpaDecoder.kt`)
  — a genuine second `SherpaDecoder` implementation alongside the untouched
  `fake.FakeSherpaDecoder`. Wraps a real sherpa-onnx `OfflineRecognizer` built from explicit
  Whisper encoder/decoder/tokens file paths; `decode()` creates an `OfflineStream`, calls
  `acceptWaveform` at the pipeline's fixed 16kHz (`SampleClock.DEFAULT_SAMPLE_RATE`), decodes,
  and maps `OfflineRecognizerResult` (text, tokens, timestamps, durations) onto this module's
  `DecodedHypothesis`. Not wired into any production construction path (`:pipeline`/`:app`) —
  see "Left open".
- **The model**: sherpa-onnx's own published pretrained model zoo, per the task's preferred
  option — Whisper `tiny.en`, int8-quantized (`tiny.en-{encoder,decoder}.int8.onnx`, ≈101MB
  combined), from `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2`.
  Not committed (`asr-sherpa/.gitignore` excludes `.models-cache/`); `asr-sherpa/README.md`
  documents the exact download/extract commands and the `ORT_SHERPA_MODEL_DIR` override.
- **`RealSherpaDecoderRealModelTest`** (`asr-sherpa/src/test/kotlin/org/ort/asrsherpa/real/`) —
  gated behind `@EnabledIfEnvironmentVariable(named = "ORT_RUN_REAL_SHERPA", matches = "1")`
  (mirrors `corpus/tests/test_probe_lora_export.py`'s `ORT_RUN_REAL_R1` pattern), with an
  `Assumptions.assumeTrue` skip (not a failure) if the model directory is missing even when the
  env var is set. Reads `test_wavs/0.wav` (16kHz mono PCM16, via a small WAV reader written
  inline — no new module dependency) through a real `RealSherpaDecoder` and asserts the output
  contains real recognizable words from the clip's actual ground truth
  (`test_wavs/trans.txt`: "AFTER EARLY NIGHTFALL THE YELLOW LAMPS ...").

**Verified:**
- `./gradlew :asr-sherpa:dependencies --configuration runtimeClasspath` — both new coordinates
  resolve (`BUILD SUCCESSFUL`); confirmed via cache inspection that the actual jars download.
- `./gradlew dependencyRules :asr-sherpa:test` (JDK 17, Windows 10, this machine) — `dependencyRules:
  OK`; 7 tests pass, `RealSherpaDecoderRealModelTest` **SKIPPED** (env var unset — proves it does
  not run in ordinary CI).
- `./gradlew :asr-sherpa:test --tests "*RealSherpaDecoderRealModelTest*"` with
  `ORT_RUN_REAL_SHERPA=1` set and the model extracted under `asr-sherpa/.models-cache/` — **PASSED**.
  Actual transcribed text (captured via the test's own stdout, JDK 17, Windows 10, sherpa-onnx
  1.13.7, CPU provider, Whisper tiny.en int8): `"After early nightfall, the yellow lamps would
  light up here and there the squalid quarter of the brothels."` — matches the clip's ground
  truth (`test_wavs/trans.txt`) essentially exactly (Whisper adds its own casing/punctuation).
  This is a real decode through a real binding and a real model, not a claim about accuracy
  (constitution VI) — no fold, no aggregate metric, one clip.

**Left open / not done:**
- **Not AC-6.** AC-6 needs the real development noise tape (radio traffic, not LibriSpeech-style
  read speech), which still doesn't exist (Q2/Q16; see `docs/reference/labelling-protocol.md`
  for the drafted protocol). This task proves the decode *mechanism* is real; it does not attempt
  AC-6's accuracy claim.
- `RealSherpaDecoder` is not constructed anywhere in `:pipeline` or `:app`. Wiring it in needs the
  real asset install/activation path (`ModelRegistry`, `ModelDescriptor`/`AssetRef` resolved to
  concrete encoder/decoder/tokens paths, side-loaded or downloaded per FR-ASR-8/FR-AST-2) — out
  of scope here, which was narrowly "prove the seam is real."
- Only `sherpa-onnx-native-lib-win-x64` is declared (this machine is Windows x64). A build on a
  different host platform needs its own `sherpa-onnx-native-lib-<platform>` coordinate —
  documented in `asr-sherpa/README.md`.
- No n-best beyond a single hypothesis, no `noSpeechProb`/`avgLogProb` (the Java API's
  `OfflineRecognizerResult` doesn't expose them for greedy-search Whisper decoding) — both fields
  are `null` in `RealSherpaDecoder`'s output, same shape `FakeSherpaDecoder` already allows.

---

## 2026-09-07 (evening, cont. — coverage-matrix hygiene)

### (pending) — Fix three misused `@Requirement` tags; note a real gap in the coverage tool

**Scope:** `onnx/src/test/kotlin/org/ort/onnx/ModelResidencyManagerTest.kt`,
`asr-sherpa/src/test/kotlin/org/ort/asrsherpa/SherpaAsrEngineTest.kt`,
`asr-api/src/test/kotlin/org/ort/asrapi/TranscriptSeriesTest.kt`, `results/coverage-matrix.md`.
**Requirements/ACs:** FR-TIER-8 (resident budget is a requirement on the tier), AC-31 (superseded
transcript retained, exactly one current) — both now correctly traced from P10's tests instead of
a bogus id.
**What changed:** `coverageMatrix` flagged 4 orphan test-requirement-id pairs after P10 landed.
Three were genuinely wrong: `ModelResidencyManagerTest`, `SherpaAsrEngineTest` and
`TranscriptSeriesTest` cited section references (`"technical-design-4.3"`, `"technical-design-8.1"`,
`"technical-design-8.3"`) as if they were requirement ids in `@Requirement(...)` — that annotation
exists for spec ids the coverage matrix indexes (`AC-*`/`FR-*`/`NFR-*`/`CON-*`), not section
numbers, which aren't ids at all. Replaced with the real ids that actually apply: `FR-TIER-8` (the
tier resident-budget requirement `ModelResidencyManagerTest` establishes) and `AC-31` (the
transcript-versioning criterion `TranscriptSeriesTest` establishes); `SherpaAsrEngineTest`'s tag
already carried `FR-ASR-1` alongside the bogus one, so that one was simply dropped.
**The fourth orphan, `"F13"` in `RejectionPipelineTest`/`ModelRegistryTest`, is NOT a test bug —
it's a real gap in `buildSrc`'s `CoverageMatrix.kt`.** `F13` ("model file missing or incompatible")
is a genuine id from functional-spec.md's failure-mode table (§12), correctly cited by both test
files. `CoverageMatrix.REQUIREMENT`'s regex only recognises `FR|AC|NFR|CON` prefixes, missing the
bare `F\d+` failure-mode ids, and (per a quick check against `AGENTS.md`'s own "cited by id"
example list — `D28`, `R15`) likely also misses `D\d+` (decisions), `R\d+` (risks) and `Q\d+`
(open questions) entirely, none of which would be flagged as covered or orphaned — they'd just
silently not count. **Left open, not fixed here**: widening `CoverageMatrix`'s regex to recognise
`F`/`D`/`R`/`Q` ids is a `:buildSrc` change outside this session's scope (no session currently owns
`:buildSrc`/`tools/spec-check`), but it's a real, demonstrated gap in a tool the whole project
relies on for traceability — worth a dedicated small session before the coverage numbers are
quoted anywhere important.
**Verified:** `./gradlew :onnx:test :asr-sherpa:test :asr-api:test coverageMatrix` — all green;
orphan count dropped from 4 to 1 (the remaining `F13` is the tool gap above, not a code defect).
**Left open / not done:** the `CoverageMatrix.kt` regex gap itself (see above).

---

## 2026-09-07 (night — P11 partial: the M3 text-derived wiring, M4 left open)

### P11 · End-to-end, then the M4 fork (`:pipeline`, `:lexicon`, `:app`)

**Scope:** `:pipeline` (new `passb/`, `latency/`, `backlog/` packages), `:lexicon` (new
`UnitSpotter.kt`, `fake/FakeUnitSpotter.kt`), `:app` (new `transmissions/` package,
`AndroidManifest.xml`). `:capture-android`, `:data`, `:onnx`, `:asr-api`, `:asr-sherpa` untouched
beyond consuming their existing public API (no edits to any of their files); `dependencyRules`
confirms no new module edge was introduced anywhere.

**Requirements/ACs:** AC-15 (text-derived lattice resolves identically to an acoustic one — now
proven through a real `Pass`, not just `TextDerivedLatticeBuilder` in isolation), FR-LEX-6/11,
FR-SPK-10 (non-optional attribution state — already true of `:core`'s `Attribution` from an
earlier prompt; this session's addition is that an attribution now reaches a *screen*),
FR-A11Y-1 (the four states distinguishable without colour, tested on a real rendered view, not
just the intermediate view-state), constitution III (Pass B carries its `PassFingerprint`).
**AC-73, AC-75 and M3's dev-fold callsign precision/recall are NOT MEASURED** — see below. **The
M4 fork decision (proceed / collapse to text / tier-gate) is explicitly left OPEN** — not chosen,
per this session's own constraints; see below.

**What changed:**

- **Merged P9 and P10 into this worktree first**, as instructed. Both had branched from the same
  P8 changelog commit and diverged, so this was two sequential merges (`worktree-agent-
  a67daa1ff68391962` fast-forwarded cleanly, `worktree-agent-aa1031e59afecdbf5` conflicted only
  in `CHANGELOG.md` — two independent appends, resolved by keeping both entries in full, P10's
  first). No file outside `CHANGELOG.md` conflicted; `git diff --stat` between the pre-merge and
  post-merge trees showed exactly the 45 non-changelog files P10's own changelog entry already
  describes (`:onnx`, `:asr-api`, `:asr-sherpa`), confirming the merge brought in nothing beyond
  what P9 and P10 each already landed. Verified: `./gradlew build dependencyRules` green
  immediately after the merge, before any P11 code was written (829 tasks, JDK 17, Windows).
- **`:pipeline/passb/PassB.kt`** — the real Pass B (build-plan P11, M3): a `Pass` (P8's
  interface) composing `:asr-api`'s `RejectionPipeline` (P10) → a per-token text-derived
  `PhoneticLattice` (deliberately *not* `TextDerivedLatticeBuilder.build`, which throws on the
  first unrecognised word — a real transcript of continuous speech contains many non-phonetic
  words, so unrecognised tokens are excluded rather than aborting the whole spot; documented in
  the class kdoc as this session's explicit, necessarily-degraded T0 approximation, since
  spotting a callsign embedded in running speech is exactly M4's job) → `:lexicon`'s
  `CallsignGrammar`/`PriorCombiner` (P3/P7) → the new `CallsignResolver` → an `Attribution`.
  Carries a `PassFingerprint` end to end (constitution III), grouped via the new
  `PassBResolutionChain` data class to keep the primary constructor under detekt's parameter-count
  threshold. `SegmentAudioProvider` and `PassBResultSink` are new seams this session owns — real
  audio decode (`:capture-android`'s `FlacStore`) and real attribution persistence (a `:data` DAO
  write path) are both explicitly left for a follow-up that can touch those modules; see below.
- **`:pipeline/passb/CallsignResolver.kt`** — the M1 resolver (technical design §9.2/§9.3,
  FR-LEX-11): ranked candidates → one of the four closed `AttributionState`s. Only `CONFIRMED`,
  `AMBIGUOUS` and `UNKNOWN` are reachable — never `INFERRED`, because a text-derived candidate's
  callsign text came directly out of *this* transmission's own transcript (constitution I: "heard
  and resolved in this transmission"), which is exactly what makes `CONFIRMED` legitimate here
  and not a violation of "never promote a voice match to it".
- **`:lexicon/UnitSpotter.kt`** (M4.1, technical design §9.7) — `fun interface UnitSpotter { fun
  spot(audio: FloatArray): PhoneticLattice }`, exactly as specified, plus
  `TextDerivedUnitSpotter`, wrapping the same per-token expansion `PassB` uses behind the
  interface so the M4 comparison can drive every candidate spotter — including the text baseline
  — through one uniform type. `:lexicon/fake/FakeUnitSpotter.kt` is its behavioural fake
  (constitution II), scriptable to a fixed lattice or "spotted nothing".
- **`:pipeline/passb/SpotterComparisonTest.kt`** — proves the *plumbing* M4.8 needs ("same audio,
  same resolver, same folds" for multiple spotters): `TextDerivedUnitSpotter` and two
  `FakeUnitSpotter`s driven through the identical grammar → priors → `CallsignResolver` chain
  produce independently inspectable attributions. This is a mechanism proof over synthetic data,
  explicitly not a claim about any acoustic spotter's real accuracy.
- **`:pipeline/latency/LatencyRecorder.kt`** (AC-73's mechanism) — nearest-rank p95 over
  recorded segment-close-to-visible durations; `null` (never a fabricated zero) with no samples.
- **`:pipeline/backlog/BacklogGrowthMonitor.kt`** (AC-75's mechanism) — least-squares slope of
  backlog-depth samples, flagged as growth only when positive and large relative to the mean
  depth (an oscillating-but-draining backlog is not growth; a monotonic climb is), so a single
  noisy sample cannot flip the verdict either way.
- **`:app/transmissions/`** — the first point in the project an `Attribution` reaches a screen
  (M3, not M5's full reader): `TransmissionRow`/`TransmissionListRowViewState`/
  `TransmissionListViewStateMapper` (pure, Room-independent by design) and
  `TransmissionListActivity` (plain Android views, same choice `StatusActivity` made in P8).
  Every one of the four states renders a distinct plain-text label carrying the state's name plus
  a distinct ASCII marker (✓ / ~ / ? / —) — FR-A11Y-1's "distinguishable without colour" tested as
  a plain-string assertion, which is what makes it checkable with no display, greyscale or
  otherwise. Registered in `AndroidManifest.xml`, not yet the app's launcher (same reasoning as
  `StatusActivity`).
- **TD2 probe (M4.3), attempted and answered — negative for the public API:** sherpa-onnx's
  documented C API (`sherpa-onnx/c-api/c-api.h`) and its generated Kotlin API
  (`OfflineRecognizer.kt`) expose only final decode results — `text`, `tokens`, `timestamps`,
  `json` — with no function returning encoder hidden states, embeddings or any intermediate
  tensor. The Whisper ONNX export docs describe the encoder/decoder `.onnx` files themselves but
  document no runtime path to their intermediate activations through sherpa-onnx's recognizer
  wrapper. **Conclusion: sherpa-onnx does NOT expose Whisper encoder hidden states on Android
  through its public API today.** Getting them would mean forking sherpa-onnx's C++ source to add
  an extraction path, or exporting a custom ONNX graph with the hidden-state tensor as an extra
  named output and running it directly via bare ONNX Runtime — bypassing sherpa-onnx's recognizer
  entirely, a materially heavier and different engineering path than "sherpa-onnx exposes it".
  Per M4.3's own framing ("gates option 2 entirely... if it fails, skip that option"), **Option 2
  (CB-Whisper-style encoder-similarity spotting) is skipped** on this finding; only Option 1
  (open-vocabulary KWS) remains a live M4 candidate for a future session with a real device and a
  real sherpa-onnx artifact. Sources: `github.com/k2-fsa/sherpa-onnx` (`sherpa-onnx/c-api/c-api.h`,
  `sherpa-onnx/kotlin-api/OfflineRecognizer.kt`) and `k2-fsa.github.io/sherpa/onnx/pretrained_models/
  whisper/export-onnx.html`, fetched 2026-09-07. This is a documentation-level finding, not a
  from-source verification of every internal code path — a from-source audit could still be
  wrong if an undocumented API exists; flagged as the residual uncertainty a future session
  should note if a real device changes this answer.

**Verified:** `./gradlew build dependencyRules` — BUILD SUCCESSFUL, 830 tasks, no forbidden
module edge (`:lexicon -> :core` and `:pipeline -> ...` unchanged from pre-P11). `./gradlew
:pipeline:testDebugUnitTest :lexicon:test :app:testDebugUnitTest` — every new test green:
`CallsignResolverTest` (5), `PassBTest` (3, including the full spelled-callsign → CONFIRMED path
and the rejected-segment → UNKNOWN path), `SpotterComparisonTest` (1), `LatencyRecorderTest` (5),
`BacklogGrowthMonitorTest` (5), `UnitSpotterTest` (3), `FakeUnitSpotterTest` (2),
`TransmissionListViewStateMapperTest` (3), `TransmissionListActivityTest` (2) — 29 new tests, all
written and confirmed failing to compile for the right reason (the class under test did not yet
exist) before each implementation was added, then green after. `./gradlew coverageMatrix` — 413
requirements, 100 covered (was 92 pre-P11); the tool's own id-regex cannot parse `FR-A11Y-1`
(`[A-Z]{2,5}` excludes the digit in `A11Y`), so it is listed among the pre-existing orphan-test
warnings alongside P10's already-unaddressed `F13`/`TECHNICAL-DESIGN-*` — a tool limitation, not
a missing requirement (`spec/functional-spec.md:1606` defines `FR-A11Y-1`); out of this prompt's
file scope to fix (`buildSrc`). `python tools/spec-check/spec_check.py` — all 7 checks PASS,
including after the `spec/build-plan.md` checklist edit. JDK 17, Windows, local machine
throughout; no fold applicable to any of the above (JVM/Robolectric unit tests over synthetic
fixtures and fakes, not a corpus measurement).

**Left open / not done — read this before assuming M3 or M4 landed:**

- **AC-73 and AC-75 are NOT MEASURED.** No reference device and no real segment timings exist in
  this sandbox. `LatencyRecorder` and `BacklogGrowthMonitor` are genuine, tested mechanisms that
  would report AC-73/AC-75 correctly if fed real device timings — that is what their own test
  suites prove — but no real p95 latency number or real sustained-backlog trend was measured, and
  none is claimed here.
- **M3's "record the harness's callsign precision/recall on the dev fold" — the line M4 must
  beat — could NOT be produced.** This needs real labelled dev-fold occurrences (real transmitted
  callsigns with ground truth), which do not exist in this repository (the same gap P7's session
  hit and recorded; `spec/open-questions.md` Q2/Q16 remain open). `SpotterComparisonTest` and
  `PassBTest` exercise the identical resolver chain against synthetic fixtures, which proves the
  *mechanism* computes attributions correctly — it is not, and is not presented as, the dev-fold
  number the build plan asks for.
- **Real audio decode is not wired.** `PassB`'s `SegmentAudioProvider` seam is real and tested,
  but nothing in this session connects it to `:capture-android`'s `FlacStore`/`LosslessCodec` —
  doing so is straightforward but was left out because it touches no new logic worth testing
  beyond what `FlacStore`'s own P8 tests already cover, and the prompt's file-ownership boundary
  (`:capture-android` untouched except by consuming its API) made a same-session change to wire
  it in without a real device to verify against feel like exactly the "half-finished" the
  constitution warns against.
- **Real attribution persistence is not wired, and this is a genuine gap this session found,
  not a choice.** `:data`'s `TransmissionDao` has no write path for `attributionState`/
  `stationId`/`attributionConfidence` — only `setProcessingState` and `setReprocessCandidate`
  exist as partial updates. `PassBResultSink` is the seam `PassB` writes through instead;
  `TransmissionListActivity` renders whatever `TransmissionRow`s it is handed, but nothing in
  this session produces those rows from a live database, because doing so needs a `:data` DAO
  method this prompt's file-ownership boundary explicitly reserves for a session that owns
  `:data` (per the prompt: "if a real gap forces a change to one of those, stop and report it
  rather than reaching across the boundary"). **Reported, not silently worked around.**
- **The M4 fork decision is explicitly OPEN, not chosen.** No real KWS implementation was built
  (no real sherpa-onnx JNI artifact was available in this sandbox — the same gap P10's session
  hit for Pass B's engine), no encoder-similarity implementation was attempted (skipped on the
  TD2 finding above), and no real 3-way comparison was run on real audio or the dev fold. Per the
  build plan's own M4 exit criteria, a fork decision requires exactly that comparison; picking a
  branch without it would be the fabricated-confidence failure mode the constitution exists to
  prevent. A future session with a real device, a real sherpa-onnx KWS binding and real labelled
  dev-fold audio should run `SpotterComparisonTest`'s shape for real and record the decision in
  the functional spec before M5, per the build plan's "Done when".
- Per-unit precision/recall reporting (M4.6) and calibration refit on acoustic-lattice scores
  (M4.7) are not attempted — both depend on the KWS/encoder-similarity implementations this
  session could not build for real.

---

## 2026-09-07 (evening — P10 lands)

### P10 · ASR and hallucination control (`:onnx`, `:asr-api`, `:asr-sherpa`)

**Scope:** `:onnx`, `:asr-api`, `:asr-sherpa` only — no other module touched.
**Requirements/ACs:** AC-7 (each of the six hallucination controls individually demonstrable),
AC-8 (rejected segments retain audio, reachable behind a filter), F13 (invalid model falls back
and surfaces it; side-loaded model signature-checked and probe-run before activation), FR-ASR-5/6,
FR-ASR-8, FR-AST-2, FR-REP-3, technical design §4.3/§8.1/§8.3/§8.4. **AC-6 is NOT established** —
see below.
**What changed:**
- `:onnx` — `ModelDescriptor` (technical design §8.4), `OnnxSession`/`OnnxSessionFactory` (a
  narrow seam mirroring `:segment`'s `VadModel` pattern, so no native runtime is required to
  build or test this layer), and `ModelResidencyManager` implementing the Pinned/Hot/Cold
  residency classes of §4.3: `PINNED` never evicted, `HOT` is LRU with a floor of one, `COLD` is
  loaded-per-use-and-released, budget enforced against declared footprints. `FakeOnnxSessionFactory`
  is the behavioural fake, scriptable to fail loading or crash on first run (the probe-run failure
  mode F13 requires a caller to handle).
- `:asr-api` — `AsrEngine`/`StreamingAsrEngine`/`Enhancer`/`AsrResult` (§8.1, n-best and
  `no_speech_prob` retained); the six `RejectionRule`s as named, cheapest-first, split into
  `PreDecodeRejectionRule` (`too_short`, `vad_no_speech` — never invoke the engine) and
  `PostDecodeRejectionRule` (`no_speech_prob`, `repetition`, `blocklist`, `compression_ratio`);
  `RejectionPipeline` composing all six with a timeout-guarded engine call (`withTimeout` ->
  `PassBOutcome.Failed` on hang or throw, never a hang — F13's engine-timeout requirement);
  `PassBOutcome` (Accepted/Rejected/Failed); `TranscriptCandidate`/`TranscriptSeries` — a
  domain-level append-only "exactly one current" invariant independent of Room (the DB-level
  enforcement is `:data`'s partial-unique-index from P5; this is what a Pass B run produces
  before that write); `ModelRegistry` resolving a tier to an activated model with fallback
  surfaced as `ModelFallback` (F13); `RejectedSegmentLog`, an in-memory stand-in proving the
  AC-8 shape (rejected is a retained, filterable result, not a deletion) at the layer this
  prompt owns — the persisted equivalent is `:data`/`:pipeline` territory; `FakeAsrEngine`, the
  behavioural fake (`Returns`/`HangsFor`/`Throws`).
- `:asr-sherpa` — `SherpaAsrEngine` implementing `AsrEngine` over an injected `SherpaDecoder` seam
  (the one method a real sherpa-onnx JNI binding would implement — see "Left open" below) plus
  `OnnxSession` lifecycle checks; `ModelActivation` — the side-loaded model install path
  (FR-ASR-8): signature check, then load, then probe-run over a fixture clip, refusing activation
  and keeping the previous model active on any failure (FR-AST-2), marking every side-loaded
  activation `sideloadedUnverified = true` regardless of passing checks, per §8.4 ("this does not
  make executing a third-party graph safe; it makes it deliberate"); `FakeSherpaDecoder`.
- Thresholds: `NoSpeechProbRule` (default 0.60) and `CompressionRatioRule` (default 2.4) use the
  same defaults already recorded in `:core`'s `ResolvedConfig` (P1). Both are Whisper's
  conventional values, **not refitted against a development noise tape** — see "Left open".
**Verified:** `./gradlew :onnx:test :asr-api:test :asr-sherpa:test :onnx:ktlintCheck
:asr-api:ktlintCheck :asr-sherpa:ktlintCheck :onnx:detekt :asr-api:detekt :asr-sherpa:detekt
dependencyRules` — BUILD SUCCESSFUL; 8 `:onnx` + 28 `:asr-api` + 7 `:asr-sherpa` tests, all green,
JDK 17, Windows, local machine, no fold applicable (JVM unit tests over synthetic/fake inputs,
not a corpus measurement). `dependencyRules` confirms no new edge beyond `:asr-api -> :core,
:onnx` and `:asr-sherpa -> :core, :onnx, :asr-api`, both already declared in `ModuleGraph`.
**Left open / not done — read this before assuming AC-6 passed:**
- **AC-6 is not established against real data or a real model, and this changelog entry does
  not claim it is.** `spec/open-questions.md` still lists Q2 ("record the tape") and Q16 (its
  labelling protocol) as the specification's two open questions; the development noise tape has
  not been recorded. Separately, no real ONNX Whisper/distil-whisper export runnable from a JVM
  sherpa-onnx binding was available/downloadable in this sandbox, so `:asr-sherpa`'s
  `SherpaAsrEngine` has never run a real decode — `SherpaDecoder` is a documented seam, not a
  verified binding. `SyntheticNoiseGateMechanismTest` in `:asr-api` proves the six-control
  mechanism rejects a set of hand-crafted, noise-shaped decodes (empty text with high
  `no_speech_prob`, degenerate repetition, documented Whisper hallucination phrases) with zero
  accepted — a genuine, valuable result about the *mechanism*, explicitly not a substitute for
  AC-6, and its test name and class doc say so. A real AC-6 run needs: the recorded tape (Q2),
  a real ONNX ASR model reachable from `:asr-sherpa`, and the JVM/JNI sherpa-onnx artifact this
  environment could not resolve/verify.
- Threshold *fitting* against the development noise tape (technical design §8.2, "thresholds are
  fitted, not inherited") could not be done for the same reason — `NoSpeechProbRule` and
  `CompressionRatioRule` keep Whisper's unrefitted defaults, recorded as such in their doc
  comments rather than presented as measured.
- `:asr-sherpa`'s `sherpa-onnx` JVM/JNI dependency itself was not added to `gradle/libs.versions.toml`
  — with no real model to exercise it and no confirmed resolvable artifact for this build's
  target platform, adding an unused/unverified native dependency seemed worse than the documented
  seam (`SherpaDecoder`). A follow-up session with a real model available should add it, implement
  `SherpaDecoder` for real, and only then attempt AC-6 for real.
- `StreamingAsrEngine`/`StreamSession` (Pass A, M8) and `Enhancer` (M11) are declared per §8.1 but
  have no implementation yet — out of P10's scope (M3), matching the build plan's milestone order.

---

## 2026-09-07 (evening — P9 partial: the on-device harness runner)

### 8fbad92 — P9 · On-device harness runner: reuse :eval's Harness/HarnessConfig/HarnessReport in an androidTest entry point

**Scope:** `:app` only — a new `harnessShared` source directory, `app/src/test/.../harness/`,
`app/src/androidTest/.../harness/`, and `app/build.gradle.kts` (new `testImplementation` /
`androidTestImplementation` edges plus the `sourceSets` wiring that shares `harnessShared`
between `test` and `androidTest`). This is deliberately the **first deliverable of P9 only** —
the on-device harness runner described in build-plan P9's opening paragraph and
implementation-plan M2.21a. The device matrix itself (D8, D4, D5, D1–D3, D9–D12; AC-64, AC-76,
NFR-7) is **not attempted in this session** — it needs the physical reference device, which this
sandbox does not have, and fabricating a result for it would be exactly the kind of silent
failure the constitution exists to prevent.
**Requirements/ACs:** AC-36 (a desktop-only harness structurally cannot measure T3 — this gives
`:eval` a genuine on-device entry point), FR-TST-7/AC-100 (the eval-fold seal holds
on-device, identically to the JVM harness — tested), AC-90's determinism/format guarantee
extended off-desktop (the on-device runner is proven to emit byte-identical `canonicalText()`
for identical inputs). AC-64/AC-76/NFR-7 (the actual endurance run and its published number)
remain **open**, as does the rest of the device matrix (D1–D5, D8–D12).
**What changed:** `OnDeviceHarnessRunner` (`app/src/harnessShared/kotlin/org/ort/app/harness/`)
mirrors `eval/src/main/kotlin/org/ort/eval/Main.kt`'s construction exactly (same grammar, same
`PriorCombiner`, same `Harness`, same eval-fold gate via `CorpusManifest.entries`) and adds
`runAndWriteReport`, which writes `HarnessReport.canonicalText()` to a file verbatim. It has
deliberately **no `android.*` import**, so the identical code path is exercised by a plain JVM
unit test (no device) and by the real on-device instrumented test — "same manifest, same report
format" is proven, not asserted, because both callers run the same function.
`HarnessInstrumentedTest` (`app/src/androidTest/.../harness/`) is the actual on-device entry
point: it resolves `InstrumentationRegistry`'s target-context `filesDir` (app-private storage),
reads a manifest from `<filesDir>/harness-corpus/manifest.tsv`, runs the harness with
`(machine = Build.MANUFACTURER/MODEL, provider = "cpu", threadCount = availableProcessors(),
runtimeVersion = Build.VERSION.SDK_INT)`, and writes the report to
`<filesDir>/harness-reports/report.txt`. Its kdoc documents the real push/pull commands
(`adb push` into `/data/local/tmp`, `run-as` copy into app-private storage, `am instrument`,
then `run-as cat`/`adb pull` to retrieve the report) for the future session that runs this on
hardware.
**Module-boundary finding (part of this prompt's explicit ask):** `:app`'s main source set has
no permitted compile-time edge to `:eval` (`buildSrc/.../ModuleGraph.kt`: `:app`'s allowed set is
exactly `{:pipeline, :data, :net, :core}`). `dependencyRules` only checks the `api`,
`implementation` and `compileOnly` configurations (`build.gradle.kts`'s `checkedConfigurations`)
— `testImplementation`/`androidTestImplementation` are outside that check by explicit design
(`ModuleGraph.kt`'s own comment: "Test scope is deliberately excluded: `:testing` fakes are meant
to be reachable from any module's tests"). Rather than widen `ModuleGraph.allowed[":app"]` to
include `:eval` (which would let a future `:app` *main-source* change depend on the harness by
accident, the exact failure mode structural enforcement exists to prevent), this change adds
`:eval`/`:testing`/`:lexicon` only to `testImplementation`/`androidTestImplementation` — inside
the graph's own documented exemption, not a workaround of it. Confirmed with
`./gradlew dependencyRules`: the checked graph still shows `:app -> :core, :data, :net,
:pipeline` only. `:eval`'s current dependencies (`:onnx`, `:asr-sherpa`, etc.) are all still
empty stubs (no native/JNI code yet), so this edge is safe today; when P10 lands a real ONNX
Runtime dependency inside `:onnx`/`:asr-sherpa`, a future session should check whether the JVM
artifact `:eval` pulls in in has a real (non-Android) native library that would fail to load
under `androidTest`'s on-device classpath — flagged here as a foreseeable follow-up, not
something this session could observe today.
**Verified:** `./gradlew :app:testDebugUnitTest` — 15 tests green, including
`OnDeviceHarnessRunnerTest`'s three cases (byte-identical `canonicalText()` vs. a
Main.kt-equivalent construction; `runAndWriteReport` writes the exact text to a file; the
eval-fold gate throws `IllegalStateException` without `allowEval` and succeeds with it).
`./gradlew :app:compileDebugAndroidTestKotlin` — `HarnessInstrumentedTest` compiles clean
against the real Android/androidx.test classpath (this is as far as verification can go without
a device or emulator in this sandbox — the instrumented test itself was not executed).
`./gradlew dependencyRules` — 17 modules checked, `:app -> :core, :data, :net, :pipeline` only,
no forbidden edges. `./gradlew :app:check` — ktlint, lint and both unit-test variants
(debug/release) green. `./gradlew coverageMatrix` — 413 requirements, 92 covered (was 90 before
this commit; +2 for the two `AC-36`-tagged JVM tests), no new orphan-id warnings.
**Left open / not done:** the entire device matrix (implementation-plan M2 exit criteria): **D8**
(30-minute "prove it" run), **D4** (the 8-hour unattended run, AC-64), **D5** (the same run
without exemptions, AC-5), **D1, D2, D3, D9, D10, D11, D12**. None of these can be attempted
without the physical reference device — this session had no device access, and the build-plan
prompt explicitly scoped this session to the on-device runner only. Also genuinely unverified for
the same reason: the actual `adb push` of a real corpus into app-private storage, the actual
`am instrument` execution on hardware, and the actual `adb pull`/`run-as cat` of the resulting
report — `HarnessInstrumentedTest` documents the exact commands but none of them have been run.
The manifest fixture inside `HarnessInstrumentedTest` is a stand-in for a pushed corpus; a real
device run replaces it with the genuine corpus manifest before executing. `AC-64`'s "Done when:
AC-64 passes and D8 correctly predicted D4's outcome" is entirely open.

---

## 2026-09-07 (afternoon, cont. — P8 and the real R1 run both land)

### 36c19ad — Merge branch 'worktree-agent-aa4aa2aeea1f1ebaa' (P8 into main)

**Scope:** brings P8's `:capture-android`, `:pipeline`, `:app` additions onto `main`.
**Requirements/ACs:** see the P8 entry below.
**What changed:** merge-only; one conflict in `spec/build-plan.md`'s progress checklist (this
branch and the P7 merge had both ticked adjacent lines independently), resolved by keeping both
ticks.
**Verified:** `./gradlew build dependencyRules` green post-merge (818 tasks); `./gradlew
coverageMatrix` regenerated (63 → 90 requirements covered).
**Left open / not done:** n/a (merge).

### 158a61c — P8 · Android capture spine: AudioRecordSource, shed controller, capture status surface

**Scope:** `:capture-android`, `:pipeline`, `:app` (status surface and permissions only, per the
prompt's ownership boundary — `:data`, `:capture-api`, `:segment`, `:core`, `:testing`, `corpus/`
confirmed untouched).
**Requirements/ACs:** AC-2/AC-98 (route verified after first read and on route change; a
deliberately-selected built-in mic is legal but persistently labelled), AC-5 (unclean end
reported next launch with its last heartbeat), AC-46 (shed order followed, no audio lost at any
level), AC-48/AC-49 (interruption produces a correctly-bounded `CaptureGap`, resumes, and a gap
is distinguishable from silence), AC-61 (notification never contains transcript text), AC-65
(liveness comes from the heartbeat — verified by forcing `isIgnoringBatteryOptimizations()` to
return `true` and confirming the session is still treated as dead), AC-96 (a continuously-archived
session is re-segmentable with different VAD parameters), AC-97 (resampler identity recorded),
AC-99 (`PassDrainRunner` cancels a hanging pass at its deadline and keeps draining), AC-103 (model
residency stays inside the tier budget). AC-64 (the 8-hour endurance claim itself) is explicitly
**partial** — the mechanism (`ProveItAnalyzer`) is built and tested, but the actual prediction
needs the reference device and is P9's job, not something Robolectric can settle.
**What changed:** `AudioRecordSource` (implements `:capture-api`'s `CaptureSource`; route
verification after the first read and on every route change, halt rather than silently
fall back, interruption → `BackoffLadder` (1/2/5/10/30s) → auto-resume); `RouteVerifier`,
`GapTracker`; the FLAC store (`FlacStore`: stage → encode → decode-and-compare → delete only on
byte match) backed by `DeflatePredictiveCodec` — a pure-Kotlin order-1-delta-prediction +
DEFLATE codec substituted for JNI libFLAC, which can't be built in this sandbox; genuinely
lossless and never trusted blindly (`FlacStore` verifies every encode itself); the
continuous-archive writer (`ContinuousArchiveWriter`/`ArchiveReader`, default off, sample-indexed
chunks; re-segmentation lives in `:pipeline`'s `ReSegmenter` since `:capture-android` may not
depend on `:segment`); `CaptureService` (foreground, wake lock, heartbeat) with a file-backed
`HeartbeatStore`, `UncleanEndDetector`, `LivenessChecker`; the OEM guidance table
(`OemGuidanceTable`/`OemGuidanceResolver`, four ColorOS steps + generic fallback);
`ProveItAnalyzer` (the 30-minute test's reporting mechanism); in `:pipeline`: `ShedController`
(levels 0–5, hysteresis, battery-critical override), `ModelResidency`/`ResidencyBudgetChecker`,
`Pass`/`PassDrainRunner` (orchestrator watchdog over `:data`'s `WorkQueue.runLeased`),
`GapPersister` (maps capture-android's `GapRecord` → `:data`'s `CaptureGapEntity`),
`CaptureStatus`/`CaptureStatusRepository`; in `:app`: `StatusActivity` + `StatusViewStateMapper`
(plain Android views, not Compose — `ort.android-app.gradle.kts` has no Compose wiring yet; the
state-holder logic is Compose-agnostic so this doesn't block adding it later) and
`PermissionsFlow` (RECORD_AUDIO → notifications → battery-exemption, the last kept
diagnostic-only per AC-65's point that liveness must never depend on that API).
**Verified:** `./gradlew dependencyRules` (every edge permitted), `./gradlew
:capture-android:check :pipeline:check :app:check` (tests + ktlint + detekt + lint, green),
`./gradlew build` (whole repo, green).
**Left open / not done:** three documented, scope-bounded deviations: (1) FLAC is substituted by
the pure-Kotlin codec above, not JNI libFLAC — flagged as a follow-up, not silently swapped; (2)
`:app` has no Compose UI yet, plain views only; (3) shed events live in `ShedController`'s memory
only, not persisted to a `:data` table (no `shed_event` table exists and this prompt couldn't
touch `:data` to add one). Also: `:pipeline` depends on `:capture-android` via `api` rather than
`implementation` specifically so `:app` can read capture-status types without a new declared
module edge — `dependencyRules` checks each module's own declared edges, so this is compliant
with the graph as coded, but worth a second look at the next `:pipeline`/`:app` session. AC-64,
AC-66's actual-ColorOS-hardware behaviour, and the 8-hour endurance claim all remain P9's job on
the physical reference device.

### 144e21b — Merge branch 'worktree-agent-a7b31df9844daf804' (the real R1 run into main)

**Scope:** brings the real (non-fixture) R1 run onto `main`: `corpus/pyproject.toml`,
`corpus/src/corpus/probes/lora_export.py`, `corpus/src/corpus/probes/_sherpa_whisper_export.py`
(new), `corpus/tests/test_probe_lora_export.py`, `results/r1-lora-export.md`.
**Requirements/ACs:** R1 (probe gate) — see the entry below.
**What changed:** merge-only, no conflicts (disjoint from every other concurrently-landed branch).
**Verified:** `cd corpus && python -m pytest -q` post-merge — 70 passed, 1 skipped (the real-R1
test skips without `ORT_RUN_REAL_R1=1` set, by design, so CI never pays its ~111s cost or needs
the real toolchain installed).
**Left open / not done:** n/a (merge).

### 269b578 — P6 follow-up · R1 real run

**Scope:** `corpus/pyproject.toml` (new `[r1-real]` optional-dependency extra),
`corpus/src/corpus/probes/lora_export.py`, `corpus/src/corpus/probes/_sherpa_whisper_export.py`
(new — a vendored, adapted copy of sherpa-onnx's own `scripts/whisper/export-onnx.py`),
`corpus/tests/test_probe_lora_export.py`, `results/r1-lora-export.md`.
**Requirements/ACs:** R1 (probe gate, build-plan S1.7 / D13). **R1 now has a real verdict:
D13 is not reopened** — the gated mechanism (LoRA fine-tune → `merge_and_unload()` → sherpa-onnx
export → load → transcribe) genuinely composes on CPU.
**What changed:** installed and pinned the real toolchain (torch 2.11.0 CPU, transformers
5.16.1, peft 0.20.0, accelerate 1.14.0, sherpa-onnx 1.13.7, plus onnx/onnxruntime/onnxscript,
datasets, soundfile, huggingface_hub) in a new `[r1-real]` `pyproject.toml` extra so CI's default
install stays untouched. `probe_r1(dry_run=False)` now performs a genuine run: peft LoRA
(rank 4, query/value projections) fine-tuned on `distil-whisper/distil-small.en` against
~3.1 minutes of `hf-internal-testing/librispeech_asr_dummy` (LibriSpeech dev-clean audio,
CC BY 4.0, with real ground-truth transcripts) for 60 steps, loss 0.69 → 0.004;
`merge_and_unload()`'d; exported via `_sherpa_whisper_export.py` to ONNX; loaded with
`sherpa_onnx.OfflineRecognizer`; transcribed a held-out clip (reference *"then the powerful
twist that thrust it aside in and under the guard"* → hypothesis *"then the powerful twist that
thrusts rest to the side in and under the guard"* — recognizable, non-degenerate real ASR
output, not a fabricated string). Two genuine toolchain findings recorded for M0a: (1) sherpa-onnx's
export script expects an `openai-whisper`-format checkpoint, not a HuggingFace `transformers`
one — this run sidesteps the undocumented conversion by running peft directly against
`openai-whisper`'s own model object; (2) torch ≥2.9's default "dynamo" ONNX exporter fails on
this decoder's data-dependent KV-cache slicing, so `dynamo=False` (the legacy TorchScript
exporter) is required.
**Verified:** `cd corpus && ORT_RUN_REAL_R1=1 python -m pytest -q
tests/test_probe_lora_export.py` — 5/5 passed in ~111s (real toolchain exercised); the 4
pre-existing fixture-based tests are untouched and still pass with zero real dependencies and no
env var.
**Left open / not done:** scaled down from the prompt's "ten minutes of anything" to ~3.1 minutes
(loss had already converged well before that point) and from a full LibriSpeech download to the
small `librispeech_asr_dummy` fixture — both documented with reasoning in
`results/r1-lora-export.md` rather than silently substituted. The real-toolchain test is gated
behind an opt-in env var so it never runs in ordinary CI (mirrors the eval-fold opt-in pattern
elsewhere in this project) — someone still needs to decide whether/how a CI lane should exercise
it periodically.

## 2026-09-07 (afternoon, cont. — more adversarial review while P8/R1 ran)

### (pending) — Fix segment endSample under-reporting on mid-hangover stream end; detekt line-length cleanup

**Scope:** `segment/src/main/kotlin/org/ort/segment/Segmenter.kt`,
`segment/src/test/kotlin/org/ort/segment/PrePostRollTest.kt` (P4, not touched by any
concurrently-running session), plus an unrelated detekt fix in
`eval/src/main/kotlin/org/ort/eval/Harness.kt` (two lines the earlier fold-isolation change left
over `MaxLineLength`, caught by `./gradlew build` rather than by the narrower module-test runs
used to verify that change), and a `results/coverage-matrix.md` regeneration.
**Requirements/ACs:** AC-95 (pre/post-roll retain the transmission complete) — this fixes a case
that criterion's existing test didn't reach.
**What changed:** `Segmenter.finish()`'s `HANGOVER` branch called a private
`bufferedHangoverSamples()` helper twice — once to size the `flushHangover()` call, and again
(after `flushHangover` had already cleared the buffer it counts) to compute the closed segment's
`endSample`. The second call always saw an empty buffer, so a stream that ended while still in
`HANGOVER` (trailing silence shorter than `minSilenceMs`, e.g. capture stopping mid-trail-off)
produced a `SegmentRecord` whose `endSample - startSample` under-counted the real audio already
written to the sink — the declared boundary and the actual appended sample count disagreed.
Fixed by computing the flushed sample count once, before the buffer is cleared, and reusing it
for both the flush and the `endSample` calculation. Added a regression test (300 ms of trailing
silence against a 600 ms `minSilenceMs`/400 ms `postRollMs` config, stream ending mid-hangover)
that fails on the pre-fix code and passes after.
**Verified:** `./gradlew :segment:test` (all 14 tests green, including the new one),
`./gradlew build` (full multi-module build green after the detekt fix), `./gradlew
coverageMatrix` regenerated.
**Left open / not done:** no other modules were re-audited in this pass beyond `:segment`,
`:lexicon`'s ranking/priors/calibration math (`PropagationModel`, `Priors.kt`,
`ThresholdDerivation`, `PlattCalibrator` — all read closely, no defects found), and re-verifying
the earlier `Harness`/`WorkQueue` fixes still hold.

## 2026-09-07 (afternoon, cont. — changelog established)

### (pending) — Establish CHANGELOG.md and make it part of the working agreement

**Scope:** `CHANGELOG.md` (new), `AGENTS.md`, `spec/build-plan.md` (docs only — no code).
**Requirements/ACs:** none — process change, at the user's request, not a spec requirement.
**What changed:** created this file, backfilled with a detailed entry for every commit from P1
(`5fca369`) onward (the specification-phase commits before it are condensed into one pointer
entry — see below). Added rule 6 to `AGENTS.md`'s working agreement requiring a `CHANGELOG.md`
entry with every commit, in this file's documented format, before the commit that completes a
unit — not a follow-up. Added the same instruction to the standing preamble in
`spec/build-plan.md`, which is the text pasted into every dispatched build-plan prompt, so it
reaches every future session (human or agent) the same way the Constitution Check and strict-TDD
rules already do. Added a `CHANGELOG.md` row to `AGENTS.md`'s "Where things are" table.
**Verified:** `python tools/spec-check/spec_check.py` (7/7 OK — confirms the doc edits didn't
introduce a dangling reference or break the spec's own integrity checks); no code changed, so no
build/test run was needed for this commit.
**Left open / not done:** this entry itself was written before the commit hash existed, so it's
marked `(pending)` above rather than a real hash — unavoidable for the entry describing its own
commit; every entry after this one carries a real hash written after the fact.

## 2026-09-07 (afternoon — parallel dispatch, adversarial review)

### d46738b — Document Fearless Steps acquisition steps for R4 (build-plan todo)

**Scope:** `corpus/src/corpus/sources.py`, `corpus/manifest.json`, `docs/reference/`, `spec/build-plan.md`.
**Requirements/ACs:** R4 (probe gate, still open); none newly satisfied.
**What changed:** Added [`docs/reference/fearless-steps-acquisition.md`](docs/reference/fearless-steps-acquisition.md),
step-by-step manual acquisition instructions for both the recommended Tier 1 (the CC-BY-4.0
100-hour Challenge Corpus's speaker-diarization track — the actual size R4 needs) and Tier 2
(the full ~19,000-hour corpus via a CRSS request form/EULA, multi-terabyte, real review
turnaround). Fixed `sources.py`'s `fearless-steps` `SourceSpec`: its URL was a dead Mendeley
link (`data.mendeley.com/datasets/xps6b5vpxr` now 404s), replaced with the live Challenge Phase 2
page and a comment stating plainly that this source cannot be fetched by the automated `corpus
acquire` HTTP path — it requires a human registration step first, which no session can complete
unattended. Licence field corrected to describe both tiers' real terms. Regenerated
`corpus/manifest.json` via `corpus build` to match. Added a "yours, not a session" todo entry in
`spec/build-plan.md` pointing at the new doc.
**Verified:** `corpus build` (regenerated manifest, folds OK), `python -m pytest -q` in `corpus/`
(70 passed).
**Left open / not done:** the actual acquisition — still needs a human to register with UT Dallas
CRSS; R4 remains `NOT RUN` (see `results/r4-speaker-separation.md`).

### d61eeb6 — Adversarial review fixes: eval-fold leakage in Harness, concurrent-pass crash in WorkQueue

**Scope:** `eval/src/main/kotlin/org/ort/eval/Harness.kt`, `eval/src/main/kotlin/org/ort/eval/Main.kt`,
`eval/src/test/kotlin/org/ort/eval/*`, `data/src/main/kotlin/org/ort/data/WorkQueue.kt`,
`data/src/test/kotlin/org/ort/data/WorkQueueTest.kt`, `.gitignore`.
**Requirements/ACs:** protects the eval-fold discipline underlying AC-55/AC-56/AC-90 and P7's
own stated requirement ("Platt calibration fitted on train+dev and verified on eval"); protects
AC-45/AC-47/AC-51/AC-99's queue-draining guarantees for the multi-pass case technical design
§7.1 specifies. No new AC introduced; both are latent-bug fixes caught by adversarial review, not
requirement gaps.
**What changed:**
1. `Harness.run()` used to fit its Platt calibrator and derive the `CONFIRMED` threshold from the
   *same* occurrence list it then scored. The first real caller to wire the `eval` fold in would
   therefore fit on eval data while nominally "verifying" against it — exactly the leakage
   "never read the eval fold" (constitution VI) exists to prevent, and it was unexercised by any
   existing test because no real caller does that yet. Split `run()` into explicit `fitOn` /
   `evaluate` parameters so this is structurally impossible rather than a caller's discipline to
   remember. Added `HarnessFoldIsolationTest`: proves the derived threshold is a pure function of
   `fitOn` (unaffected by what `evaluate` contains) and that reported metrics track `evaluate`'s
   own labels.
2. `WorkQueue.completePass()`/`failPass()` called `TransmissionDao.requireLegalTransition()`
   unconditionally. Technical design §7.1's `idx_wq_active` index is keyed on
   `(transmission_id, pass)` specifically because more than one pass (B, C, E, FUSE per the
   residency table) can be leased concurrently for one transmission — so whichever pass reports
   *second* would crash trying to re-transition an already-final transmission (`COMPLETE →
   COMPLETE` is not in `TransmissionLifecycle.legalTransitions`). Guarded both methods with the
   same `canTransition()` check `leaseBatch()` already used, so a later pass's outcome leaves an
   already-final transmission alone instead of throwing. Added two `WorkQueueTest` cases
   (two `PassId`s on one transmission, second completes/fails after the first already committed)
   that reproduce the crash on the pre-fix code and pass after.
3. Added `.claude/` to `.gitignore` after an unrelated `git add -A` staged agent worktree
   directories as embedded repos.
**Verified:** `./gradlew build` (full multi-module build, green — 793 tasks), `./gradlew
:eval:test :lexicon:test :data:testDebugUnitTest` individually (all green, including the four new
regression tests), `python tools/spec-check/spec_check.py` (7/7 checks OK), `cd corpus && pytest
-q` (70 passed). Confirmed both bugs were real (not defensive-only fixes) by checking
`TransmissionLifecycle.legalTransitions` directly: `COMPLETE → COMPLETE` is absent, so the
pre-fix `WorkQueue` code path would have thrown `IllegalTransitionException` on the exact
scenario the new tests exercise.
**Left open / not done:** no other modules were audited to this depth in this pass (`:capture-*`,
`:lexicon`'s grammar/FSA, `:segment` were reviewed only via their existing green test suites, not
re-derived from spec by hand).

### b7a0ea8 — Merge branch 'worktree-agent-ab723fefc2d0ab707' (P7 into main)

**Scope:** brings P7's `eval/` and `lexicon/` ranking/calibration additions onto `main`.
**Requirements/ACs:** AC-15, AC-55, AC-56, AC-90 (see the P7 entry below for detail).
**What changed:** Merge-only; one conflict in `spec/build-plan.md`'s progress checklist (P5 and
P7 had both ticked adjacent lines independently), resolved by keeping both ticks.
**Verified:** `./gradlew :eval:test :lexicon:test` green post-merge.
**Left open / not done:** n/a (merge).

### 5187fee — Merge branch 'worktree-agent-a9e92d557d18afc8c' (P6 into main)

**Scope:** brings P6's `corpus/src/corpus/synth/`, `corpus/src/corpus/probes/` additions onto `main`.
**Requirements/ACs:** R4, R1 (both recorded as `NOT RUN` — see the P6 entry below).
**What changed:** Merge-only; one conflict in `spec/build-plan.md`'s progress checklist (P5 and
P6 had both edited the same block independently), resolved by combining both ticks and P6's
NOT-RUN annotation.
**Verified:** `python -m pytest -q` in `corpus/` green post-merge (70 tests).
**Left open / not done:** n/a (merge).

### eb3fa62 — P7 · Lexicon ranking, calibration and the `:eval` harness

**Scope:** `:lexicon` ranking/calibration (owns per build-plan: `Prior`, `Priors`,
`PriorCombiner`, `PropagationModel`, `PlattCalibrator`, `ThresholdDerivation`,
`TextDerivedLatticeBuilder`, `RankingContext`), `:eval` (new module: `Harness`, `HarnessConfig`,
`HarnessReport`, `LabeledOccurrence`, `ManifestHarness`, `Main`).
**Requirements/ACs:** FR-LEX-9 (every prior's contribution clamped, structurally, in
`AbstractPrior` — no prior can eliminate a candidate), FR-LEX-31 (cold start contributes exactly
`0f`, enforced by a `require()` in `PriorContribution`, never a default), FR-LEX-25..27
(propagation prior demotes but never eliminates, suppressed on known repeater frequencies),
AC-15 (text-derived lattice is the same `PhoneticLattice` type as acoustic, records
`TEXT_DERIVED`), AC-55 (0.9 calibrated confidence ≈ 90% observed accuracy on synthetic
held-out draws — reliability diagram), AC-56 (raising the precision target raises the derived
`CONFIRMED` threshold; the API only accepts a precision target, never a raw score), AC-90 (two
harness runs over identical corpus/model/config produce byte-identical `canonicalText()`).
**What changed:** the clamped log-odds prior-combination mechanism (`AbstractPrior` enforces
clamping and cold-start-is-zero once, for every prior including future ones, rather than trusting
each implementation); the seven priors from technical design §9.3; a static/deterministic
`PropagationModel`; `PlattCalibrator` (logistic calibration) and `ThresholdDerivation`
(precision-target → threshold); `TextDerivedLatticeBuilder`; and `:eval` v1 — a pure
`Harness.run()` composing grammar + priors + calibration + threshold into a
`HarnessReport` with per-tier reliability diagram and per-prior ablation, plus `ManifestHarness`
(the one sanctioned call site reading the corpus manifest's fold gate) and a `Main.kt` entry
point.
**Verified:** `./gradlew :lexicon:test :eval:test` — 35 tests green — plus `dependencyRules`,
`:lexicon:detekt`/`:eval:detekt`, `:lexicon:ktlintCheck`/`:eval:ktlintCheck`, and
`tools/spec-check/spec_check.py`.
**Left open / not done:** **no real precision/recall/reliability number exists yet** — there is
no hand-labelled callsign-occurrence corpus (that's Q16/S1.8, still not produced) and no ASR
pass (P10) to derive a lattice from real audio. `Main.kt` states this explicitly rather than
printing a number computed from nothing. The mechanism itself is proven correct only against
synthetic fixtures. (This entry predates and is unaffected by the eval-fold-leakage fix in
`d61eeb6` above, found during review of this same code.)

### 8010be4 — P6 · Synthetic corpus and the two probes: channel model, generator, R4/R1 (not run)

**Scope:** `corpus/src/corpus/synth/` (new: `channel.py`, `phonetic.py`, `splice.py`, `tts.py`,
`generator.py`), `corpus/src/corpus/probes/` (new: `speaker_separation.py`, `lora_export.py`),
`results/r4-speaker-separation.md`, `results/r1-lora-export.md`, `corpus/manifest.json`
(`synth-callsigns/dev-01` entry), `spec/build-plan.md`.
**Requirements/ACs:** D22 (channel model + synthetic generator), FR-TST-9 (synthetic audio barred
from `eval` — enforced by `generator.py` refusing `fold="eval"` outright), FR-SPK-14 (R4's
false-match-rate reporting shape, not yet populated with a real number).
**What changed:** a deterministic channel-degradation model fit from Paderborn's clean/degraded
pairs; the ULS-callsign → phonetic-expansion → TTS-plus-spliced-ISOLET/ATC → channel pipeline,
with labels derived from the exact rendering placement (so they cannot drift from the audio);
the R4 (speaker-separation) and R1 (LoRA export) probe *pipelines* — `Embedder` /
`LoraTrainer`/`Exporter`/`AsrRuntime` protocols, clustering + false-match-rate math for R4,
the five-stage finetune→merge→export→load→transcribe composition for R1 — each with a
behavioural fake (`FakeEmbedder`, `FakeLoraTrainer`, etc.) shipped in the same change and proven
correct against synthetic fixtures.
**Verified:** `python -m pytest -q` in `corpus/` — 70 tests (30 new) — and `ruff check .` clean.
**Left open / not done:** **both R4 and R1 report `NOT RUN`, not a fabricated number.** R4:
Fearless Steps was not on disk and no WeSpeaker/3D-Speaker package was installed (now documented
in `d46738b` above — needs human registration). R1: `transformers`/`peft`/`accelerate`/
`sherpa_onnx` were not installed and no GPU is present (superseded by the real attempt dispatched
this session — see the R1-real entry below, still running as of this changelog's writing).
Each `results/*.md` states its blocker plainly per the task instruction that a poor *or absent*
result must never be papered over with an invented number.

### ae74bdc — P5 · Data layer: Room schema, FTS5, durable queue, transmission lifecycle

**Scope:** `:data` (owns: entities, DAOs, `WorkQueue`, `TransmissionTransitions`,
`OrtDatabase`, `ReconciliationReport`, migrations).
**Requirements/ACs:** AC-31 (superseded transcript stays retrievable; exactly one `is_current`
per transmission via a hand-written partial unique index — Room's `@Index` can't express one),
AC-45 (segments reach the durable queue with every pass stalled), AC-47 (killing the process
mid-pass returns `PROCESSING → CAPTURED` and completes identically on retry — passes are
idempotent), AC-51 (a repeatedly failing pass lands `FAILED` without blocking the queue;
re-enqueueing a completed pass succeeds because the partial unique index covers active states
only), AC-53 (migration from every released schema fixture preserves audio and superseded
transcripts), AC-54 (reconciliation reports an orphaned file and a dangling row and deletes
neither), AC-99 (a hanging pass is cancelled at its deadline via `withTimeoutOrNull`, marked
`FAILED`, and the queue keeps draining).
**What changed:** the Room entities from functional spec §8 (including `Voiceprint` enrolment
fields, station knowledge, `Thread.kind`, `ContributionItem`); FTS5 over the transcript table
filtered in the join; WAL; `WorkQueue` (run-id leasing, per-item deadlines, bounded retry, one
transaction per state change spanning both the queue and the transmission — this is what makes
AC-47 and AC-99 true together); `TransmissionTransitions` (the one path that moves a
transmission's lifecycle state, always through `TransmissionLifecycle.require()` first, so an
illegal transition throws rather than silently writing a wrong state).
**Verified:** `./gradlew :data:testDebugUnitTest` (Robolectric) — see the full pass list under
`d61eeb6` above, which added two more cases to this same suite.
**Left open / not done:** the concurrent-multi-pass gap fixed in `d61eeb6` was introduced here
and not caught by this session's own tests (all of which used one pass per transmission) — found
later during adversarial review, not at the time.

### 8010be4 / eb3fa62 note — merge order

P5, P6 and P7 were built concurrently by three agents in isolated git worktrees (P5 directly by
the user, P6 and P7 as dispatched background sessions), then merged into `main` one at a time
(`5187fee`, `b7a0ea8` above) once each was independently green. This was safe because build-plan
Wave B/C's file-ownership split holds exactly as documented: P5 owns `:data`, P6 owns `corpus/`,
P7 owns `:lexicon` (ranking/calibration only) + `:eval` — no two of the three touched the same
production file, and the only merge conflicts were both in `spec/build-plan.md`'s shared
checklist, resolved by combining ticks.

## 2026-09-07 (morning — Waves A/B)

### 2eec8a7 — P4 · Capture and segmentation: WavFileSource, polyphase resampler, ring buffer, VAD segmenter

**Scope:** `:capture-api`, `:segment`. Pure JVM, no Android APIs.
**Requirements/ACs:** AC-3 (pre-roll + explicit, non-silent overrun detection), AC-69 (boundary
precision/recall == 1.0 against hand-marked keying times), AC-70 (stuck-carrier force-split,
contiguous, none exceeding the configured cap), AC-71 (`minSilenceMs` measurably trades merge vs
split on a fixed 500 ms gap — the trade is measured, not tuned by ear), AC-72 (too-short segments
rejected with audio retained and no model invoked), AC-89 (WAV replay: real-time and
as-fast-as-possible produce byte-identical events, fast path beats real time), AC-94 (reflection
asserts neither `Segmenter` nor `SegmentConfig` can be constructed with a `Tier` — FR-SEG-7 /
CON-SEG-1, the segmenter cannot accept a tier by construction, not by convention), AC-95 (pre +
post-roll retain the transmission complete, verified against an exact source slice), AC-97
(48 kHz→16 kHz and 44.1 kHz→16 kHz resample deterministically across independent runs, with a
recorded `ResamplerIdentity`).
**What changed:** `CaptureSource`/`CaptureEvent` contract; a deterministic fixed-point
windowed-sinc polyphase resampler (Q30, SHA-256-identified coefficient table); a lock-free SPSC
`RingBuffer` with pre-roll snapshot; a minimal WAV reader/writer and `WavFileSource`; the
`Vad`/`VadModel` interfaces, a `SileroVad` hysteresis wrapper, `SegmentConfig` (no `Tier` field),
and the `IDLE → TENTATIVE → SPEECH → HANGOVER` `Segmenter` writing incrementally rather than
buffering a whole transmission; `ScriptedVad` as the behavioural fake VAD for downstream tests.
Two bugs were caught by the tests before shipping: the pre-roll ring double-capturing audio
already claimed by an open segment, and the first silent hangover frame being written to the
sink twice (directly and via the hangover-buffer flush).
**Verified:** `dependencyRules`, ktlint, detekt and the full root `check`, all green;
`results/coverage-matrix.md` regenerated, tracing all nine criteria above to their tests.
**Left open / not done:** none noted at the time.

### 1243c1d — P3 · Lexicon grammar: units, variant table, ITU trie, callsign FSA, confusion scoring

**Scope:** `:lexicon`'s structural half (types, units, trie, FSA, scoring) — pure Kotlin against
`:core` only.
**Requirements/ACs:** FR-LEX-5 (lattice retains alternatives with scores, never collapses to a
best path), FR-LEX-4/29 (variant table: NATO/letter-name/legacy forms map to one unit; an
unknown form is rejected, never guessed), FR-LEX-8 (ITU prefix-trie allocation is the *only*
hard filter), AC-10 (a structurally valid non-US-prefix callsign with zero database hits still
resolves as a candidate), AC-11 (an unallocated prefix yields no candidate, so it can never
reach `CONFIRMED`), AC-12 (NATO, letter-name and legacy pronunciations of one callsign yield one
result), FR-LEX-10 (confusion-weighted substitution — E-set B/D/E/P/V/T, M/N, S/F — costs less
than an arbitrary pair).
**What changed:** `PhoneticUnit` (the closed 26-letter/10-digit/STROKE vocabulary); the
`PhoneticLattice`/`LatticeSlot`/`UnitScore` types; `VariantTable` as a bundled versioned TSV
asset; `ItuPrefixTable` (prefix trie, longest-match allocation); `ConfusionCostMatrix` as a
bundled versioned asset; `CallsignGrammar` (beam search over lattice slots against the
prefix+digit+suffix FSA, modifiers and reciprocal forms `/P`, `/M`, `K7ABC/VE7`, `VE7/K7ABC`).
**Verified:** 25 tests green (including AC-10/11/12); `:lexicon:check`, `dependencyRules`,
`coverageMatrix` all green.
**Left open / not done:** ranking, priors, calibration and `:eval` deliberately deferred to P7
(this session touched only the grammar/trie/scoring half, per its own file-ownership boundary).

### bba6b5e — P2 · Corpus pipeline: manifest, fold gate, acquisition, harness v0

**Scope:** `corpus/` (new Python subproject, own `pyproject.toml` and CI job).
**Requirements/ACs:** FR-TST-7/9 (a session in two folds, or synthetic audio in `eval`, rejected
at load time), AC-100 (`eval` fold refused without the explicit `--i-know-this-is-the-eval-fold`
flag), FR-TST-8 (harness metrics reported per source as well as aggregate), §14A.2 (whole
sessions only, ≥1 eval session with no train station, the two noise tapes separated, HF/DX
routed to eval).
**What changed:** `manifest.py`, `folds.py` (the §14A.2 checker), `gate.py` (the eval seal),
`sources.py` (the four public sources — Paderborn HF, Fearless Steps, ATC merge, ISOLET — plus
synthetic and local, each licence-recorded), `acquire.py` (resumable, idempotent
fetch→verify→normalise), `audio.py` (deterministic 16 kHz mono FLAC via ffmpeg), `metrics.py` +
`harness.py` (WER, callsign/boundary precision-recall, rejection rate by reason, attribution
accuracy, with a run fingerprint), `report.py` (refuses to exist without a fold or serialise
without a fingerprint), `build.py` (`corpus build` renders the committed `manifest.json`; CI
asserts no drift).
**Verified:** 38 tests green at the time (grown to 70 by P6, per above).
**Left open / not done:** none noted at the time; the dead Fearless Steps URL surfaced later
(fixed in `d46738b`).

### 45a8ecb — P1 follow-up: mark gradlew executable, tick the build-plan checklist

**Scope:** `gradlew`, `spec/build-plan.md`.
**Requirements/ACs:** none new — housekeeping.
**What changed:** restored `gradlew`'s executable bit (lost in the initial commit) and ticked
P1's checklist entry.
**Verified:** n/a (permissions + doc only).
**Left open / not done:** none.

### 5fca369 — P1 · Foundation: Gradle skeleton, `:core` and `:testing`, spec-check, CI

**Scope:** root build files, `:core`, `:testing`, `.github/workflows/`, `tools/spec-check`
(every other module scaffolded as an empty stub only).
**Requirements/ACs:** AC-91 (`SampleClock` survives a wall-clock jump, F14/FR-RUN-15..18),
AC-93/NFR-5 (CI asserts the debug APK's `minSdkVersion` is 26; a weekly opt-in emulator job
installs and runs it on an API-26 AVD), FR-SPK-10 (the four attribution states are non-optional
at the type), FR-RUN-7 (the transmission lifecycle's legal-transition table, asserted
exhaustively), D11 (LICENSE: Apache-2.0 — patent grant + Play Store path, confirmed as a product
decision rather than defaulted).
**What changed:** the Gradle multi-module skeleton for every module in technical design §2,
wired with its permitted dependencies; the `dependencyRules` task (backed by `ModuleGraph`,
demonstrated failing on a deliberate `:capture-api → :asr-api` edge before being reverted,
`ModuleGraphTest` as the permanent guard); `tools/spec-check` implementing all seven test-plan
§8.1 checks (its own meta-guard test injects a dangling `AC-9999`/`FR-GHOST-1` reference and
asserts the checker catches it); the `coverageMatrix` Gradle task regenerating
`results/coverage-matrix.md` from spec ids + `@Requirement`-annotated test names; `:core`
(`Clock`, `SampleClock`, `Ulid`, `PassFingerprint`/`Materiality`, `ResolvedConfig`, the
`Attribution` closed type, `TransmissionLifecycle`) with no Android dependency
(`NoAndroidDependencyTest`); `:testing` (`TestClock`, `@Requirement`, `Fixtures`, `Seeds`, a
minimal `CorpusManifest` that already refuses the eval fold without an explicit opt-in); the
Apache-2.0 `LICENSE` file.
**Verified:** CI green; both meta-guards demonstrated failing then passing; AC-91 holds;
`results/coverage-matrix.md` generates.
**Left open / not done:** none noted at the time.

## 2026-09-06 — Specification phase (condensed)

Fifteen commits establishing the functional spec (266 requirements, 126 ACs, 32 decisions, 16
risks), technical design, implementation plan, test plan, and the eleven-prompt build plan
itself, plus an adversarial audit (41 findings, all addressed) and a later six-defect review.
Not restated entry-by-entry here — that history lives in the specs themselves
(`spec/functional-spec.md`, `spec/technical-design.md`, `spec/build-plan.md`,
`spec/audit-2026-09-06.md`) and in `git log` for anyone who needs the drafting sequence. This
changelog starts carrying full per-commit detail from P1 (`5fca369`) onward, where "done" first
meant "a module is green, fake-backed and independently testable" rather than "a document is
internally consistent."

---

## In flight, not yet in this changelog

Both sessions noted here as "in flight" when this file was first written have since landed —
see the 2026-09-07 "P8 and the real R1 run both land" section above. Nothing is in flight as of
the latest entry; this section is kept as the standing place to note it when something is.
