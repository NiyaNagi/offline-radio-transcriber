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

## What remains for AC-6

This proves the `SherpaDecoder` seam has a real, working implementation — a real JVM binding, a
real ONNX model, a real decode of real audio into real recognizable text. It does **not** attempt
AC-6 itself: AC-6 needs the real development noise tape (radio traffic, not LibriSpeech), which
still doesn't exist (Q2/Q16 — see `docs/reference/labelling-protocol.md`), and it needs
`RealSherpaDecoder` actually wired into `SherpaAsrEngine` construction via the real asset
install/activation path (`ModelRegistry`, `ModelDescriptor`/`AssetRef` resolution to concrete
encoder/decoder/tokens file paths) rather than constructed directly from explicit paths in a test.
