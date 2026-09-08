# `:asr-sherpa`

`AsrEngine` over sherpa-onnx (technical design §8.1, Pass B). See `SherpaAsrEngine`'s doc comment
for the seam this module is built around.

## What is real here, and what is a seam

- `SherpaAsrEngine` — real, tested against a `SherpaDecoder`, whichever implementation is wired
  in. Session lifecycle, error mapping and the `AsrResult` shape are exercised regardless of
  which `SherpaDecoder` sits behind them.
- `fake.FakeSherpaDecoder` — the behavioural fake every other test in this module uses (P10).
  Scriptable to a fixed decode or a crash.
- `real.RealSherpaDecoder` — a genuine sherpa-onnx `OfflineRecognizer` (P10 follow-up, 2026-09-07).
  Not wired into any production entry point yet: no code in `:pipeline` or `:app` constructs it.
  It exists so the seam has at least one real implementation behind it, proven by
  `RealSherpaDecoderRealModelTest`.

## The JVM/JNI binding

sherpa-onnx does not publish its Kotlin/JVM binding to Maven Central. The upstream project only
documents a build-it-yourself CMake + `kotlinc-jvm` path (see `kotlin-api-examples/run.sh` in
`k2-fsa/sherpa-onnx`). What upstream *does* publish, as GitHub Release assets on each tagged
release, is a set of platform-specific jars — `sherpa-onnx-jvm-<version>.jar` (the Java/Kotlin API
classes, source in `sherpa-onnx/java-api/`) plus one `sherpa-onnx-native-lib-<platform>-<version>.jar`
per platform (Windows x64/arm64, Linux x64/aarch64, macOS x64/aarch64) bundling the native
`.dll`/`.so`/`.dylib`. These are mirrored onto [JitPack](https://jitpack.io) under
`com.github.k2-fsa.sherpa-onnx:<artifact>:<version>`, which **is** a resolvable Maven repository,
so this module resolves them the ordinary Gradle way — no home-grown ivy pattern, no vendored
jar, no ad-hoc CMake build in this repo:

```kotlin
// settings.gradle.kts: JitPack added to dependencyResolutionManagement, scoped in practice to
// this module (nothing else declares a JitPack coordinate).
maven { url = uri("https://jitpack.io") }

// asr-sherpa/build.gradle.kts
implementation(libs.sherpa.onnx.jvm)               // com.github.k2-fsa.sherpa-onnx:sherpa-onnx-jvm:1.13.7
runtimeOnly(libs.sherpa.onnx.native.win.x64)        // ...:sherpa-onnx-native-lib-win-x64:1.13.7
```

Version `1.13.7` matches the version the real R1 probe (`results/r1-lora-export.md`) already
confirmed usable in this environment via Python — this is the same release, consumed from the
JVM side instead.

**Confirmed resolvable in this environment**: `./gradlew :asr-sherpa:dependencies --configuration
runtimeClasspath` resolves both coordinates, and `./gradlew :asr-sherpa:compileKotlin` /
`:asr-sherpa:test` genuinely download the jars (not just metadata) — verified by checking
`~/.gradle/caches/modules-2/files-2.1/com.github.k2-fsa.sherpa-onnx/` for the actual `.jar` files,
not just `.pom`s.

**Platform-specific note**: this build runs on Windows x64 (per this session's dev-toolchain), so
only `sherpa-onnx-native-lib-win-x64` is declared. A different host platform needs its own
`sherpa-onnx-native-lib-<platform>` coordinate — the version above lists what upstream publishes.

## The model

`RealSherpaDecoderRealModelTest` uses sherpa-onnx's own published pretrained model zoo rather
than re-deriving a model via fine-tuning: **Whisper `tiny.en`, int8-quantized**, encoder+decoder
≈101MB total.

```powershell
# From this directory:
New-Item -ItemType Directory -Force -Path .models-cache | Out-Null
Invoke-WebRequest -Uri "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2" `
    -OutFile ".models-cache\sherpa-onnx-whisper-tiny.en.tar.bz2"
python -c "import tarfile; tarfile.open('.models-cache/sherpa-onnx-whisper-tiny.en.tar.bz2').extractall('.models-cache')"
```

This produces `asr-sherpa/.models-cache/sherpa-onnx-whisper-tiny.en/` containing
`tiny.en-{encoder,decoder}.int8.onnx`, `tiny.en-tokens.txt`, and `test_wavs/` (sample clips with
a `trans.txt` ground truth) — exactly what `RealSherpaDecoderRealModelTest` looks for by default.
`.models-cache/` is gitignored; **the model is never committed**.

A real Android session would fetch this the way the technical design already specifies for other
assets (§8.4's install/verify/activate/roll-back/remove lifecycle) — this local cache directory
is a desktop-JVM-test convenience only, not a statement about the runtime asset path, which is
out of this task's scope (see "What remains" below).

To point at a model cached somewhere else, set `ORT_SHERPA_MODEL_DIR` to that directory instead
of relying on the default.

## Running the real decode test

Gated the way `corpus/tests/test_probe_lora_export.py`'s `ORT_RUN_REAL_R1` gates the real R1
probe — never runs in ordinary CI (`@EnabledIfEnvironmentVariable`, skipped when unset), and
skips (via `Assumptions`, not a failure) if the env var is set but the model isn't where expected:

```powershell
$env:ORT_RUN_REAL_SHERPA = "1"
./gradlew :asr-sherpa:test --tests "*RealSherpaDecoderRealModelTest*"
```

## The Silero VAD binding (build-plan P12)

The same `sherpa-onnx-jvm` artifact `RealSherpaDecoder` uses also exposes a real Silero VAD API —
`com.k2fsa.sherpa.onnx.Vad`, `VadModelConfig`/`SileroVadModelConfig` (builder pattern, plain
`FloatArray` in/out) — confirmed present in the resolved `sherpa-onnx-jvm-1.13.7.jar` (`jar tf`
lists the classes; `javap` on them shows the shape below). `RealSileroVad` wraps it:

```kotlin
val vad = Vad(
    VadModelConfig.builder()
        .setSileroVadModelConfig(SileroVadModelConfig.builder().setModel(pathToOnnx).build())
        .setSampleRate(16_000)
        .setProvider("cpu")
        .build(),
)
vad.compute(frame) // Float, the exact shape org.ort.segment.VadModel needs
```

No model ships inside either jar (confirmed by listing jar contents — `.class` files only, no
`.onnx` resources) — `SileroVadModelConfig.setModel(path)` is a filesystem path the native
`Vad(...)` constructor loads at construction time, exactly like `RealSherpaDecoder`'s encoder/
decoder paths. The standard release asset name/URL is:

```
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
```

(confirmed reachable — HTTP 200 — from this environment; **never committed**, same as the Whisper
model). Place it at `asr-sherpa/.models-cache/silero-vad/silero_vad.onnx` for
`RealSileroVadRealModelTest`, gated identically to `RealSherpaDecoderRealModelTest`:

```powershell
$env:ORT_RUN_REAL_SHERPA = "1"
./gradlew :asr-sherpa:test --tests "*RealSileroVadRealModelTest*"
```

`:pipeline`'s `RealVadProvider` (build-plan P12) looks for this same file at
`<app-private-files>/models/silero-vad/silero_vad.onnx` at runtime and falls back to the
RMS-energy `EnergyVadModel` stand-in — visibly, via `VadAvailability` — when it is absent, exactly
mirroring `RealAsrEngineProvider`'s pattern for the ASR model. **Not verified against a real model
in this session's environment** — no `.onnx` file was actually downloaded here (no device, and
downloading assets is deliberately kept out of the capture/processing path per constitution V);
`RealSileroVadRealModelTest` documents exactly what a future session needs to do so for real.

## Published checksums for the app's Models screen (audit F-008 follow-up)

`app/.../ui/data/ModelsViewData.kt`'s `ModelCatalog` — the "Models" screen's declared,
user-initiated download/side-load channel (constitution V, FR-ASR-1) — installs the same four
files this module's tests use, fetched as flat files rather than the `.tar.bz2` archive above.
Before this fix every entry carried a placeholder, non-hex checksum string, so a real fetch or
side-load would correctly fail closed but the catalog could not say *why* a digest was missing.
The table below is the source-of-truth record for each entry's real state, read from published
metadata — **no file was downloaded or hashed by this change to produce these values**:

| File | URL | Size (bytes) | SHA-256 | Read from |
|---|---|---|---|---|
| `tiny.en-encoder.int8.onnx` | `https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-encoder.int8.onnx` | 12,937,772 | `0ce578b827c94a961aacb8fa14b02f096504b337e5c94be37c36238cbe3e8bc6` | The HuggingFace Git-LFS pointer text at `.../raw/main/tiny.en-encoder.int8.onnx` (`oid sha256:...`, `size ...`) — read 2026-09-07. |
| `tiny.en-decoder.int8.onnx` | `https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-decoder.int8.onnx` | 89,853,865 | `06c0e6ff6348d427e51839219d1c886c18cfdf411e629e33f5e1679bff9c1527` | Same LFS pointer mechanism, `.../raw/main/tiny.en-decoder.int8.onnx` — read 2026-09-07. |
| `tiny.en-tokens.txt` | `https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-tokens.txt` | 835,554 | **Unknown — sideload only.** | Not LFS-tracked: HuggingFace's file-listing API reports only a 40-hex git blob id (`3480e077d2fc94c521e1cadb2e66cde18138b3ef`) for this path — a SHA-1 from git's own blob hashing, not a SHA-256. Checked and not found elsewhere: sherpa-onnx's own `checksum.txt` release manifest (below) covers whole `.tar.bz2` archives only, not files extracted from them. `ModelCatalog` marks this entry `ChecksumState.UnknownSideloadOnly` rather than inventing a value. |
| `silero_vad.onnx` | `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx` | not confirmed by this change (see note) | `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6` | The `checksum.txt` asset published in the same GitHub release (`k2-fsa/sherpa-onnx`, tag `asr-models`) — a tab-separated `<asset filename>\tsha256` manifest listing `silero_vad.onnx` by its exact name (distinct from the unrelated `silero_vad_v5.onnx` asset in the same release, whose Releases-API `digest` field is null). Read 2026-09-07. |

Note on `silero_vad.onnx`'s size: the GitHub Releases API for this tag paginates its asset list
(100+ assets) and the specific `silero_vad.onnx` entry (as opposed to `silero_vad_v5.onnx`, whose
size the API did surface) was not located within the pages fetched in this session; only its
checksum manifest line was read directly. This does not affect verification — `ModelFetchSpec`
carries no size field, only a `Checksum` — but a future session downloading it for real should
expect to confirm the size empirically rather than from this table.

**No on-device install, and no real download of any of these four files, was performed in this
change.** The three `Known` checksums above are what a genuine download must match before
`ModelAcquisition` will install it; `tiny.en-tokens.txt` remains side-load-only until an
authoritative sha256 for that specific file is published somewhere and can be cited the same way.

## What remains for AC-6

This proves the `SherpaDecoder` seam has a real, working implementation — a real JVM binding, a
real ONNX model, a real decode of real audio into real recognizable text. It does **not** attempt
AC-6 itself: AC-6 needs the real development noise tape (radio traffic, not LibriSpeech), which
still doesn't exist (Q2/Q16 — see `docs/reference/labelling-protocol.md`), and it needs
`RealSherpaDecoder` actually wired into `SherpaAsrEngine` construction via the real asset
install/activation path (`ModelRegistry`, `ModelDescriptor`/`AssetRef` resolution to concrete
encoder/decoder/tokens file paths) rather than constructed directly from explicit paths in a test.
