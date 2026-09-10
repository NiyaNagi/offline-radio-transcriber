# Installed size — WPG bundled assets (R18, FR-AST-3a)

E2-H09. Measured against a real `assembleDebug` artifact installed on a real emulator, not
estimated. **`HF_TOKEN` was not set on this measurement machine**, so this run used the
local-development escape hatch (`-PortAllowMissingBundledAssets=true`) — see "What this number
does not include" below. `fetchBundledAssets`'s own log for this run is quoted in full so the gap
is auditable, not just asserted.

## Build

| Field | Value |
|---|---|
| Commit | `7dc81be` (parent of this session's WPG commit — `git rev-parse --short=7 HEAD` before committing) |
| Date | 2026-09-10 |
| Command | `./gradlew build dependencyRules platformGuards -PortAllowMissingBundledAssets=true` |
| Variant | `debug` (`app-debug.apk`) |
| `HF_TOKEN` | **absent** on this machine — Gemma 3 1B could not be fetched (see below) |
| Device | Android emulator `emulator-5556`, API 34 (`ro.build.version.release=14`), `x86_64` |

`fetchBundledAssets`'s own output for this run, verbatim:

```
> Task :app:fetchBundledAssets
fetchBundledAssets: WARNING — LLM_GEMMA3_1B could not be fetched (fetchBundledAssets: HF_TOKEN is
required to fetch LLM_GEMMA3_1B (a gated model) — accept the licence for
https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task on
HuggingFace, then set the HF_TOKEN environment variable and rerun the build.); packaging WITHOUT
it because the local-only allow-missing escape hatch is set. This is not a complete offline
install — never do this for a release build or in CI (FR-AST-3).
fetchBundledAssets: 4/5 assets verified and packaged under
<repo>/app/src/main/assets/bundled (1 marked missing)
```

## Numbers measured

| Figure | Value | How measured |
|---|---|---|
| APK size (`app-debug.apk`) | **204,558,738 bytes (≈195 MB)** | `Get-Item app/build/outputs/apk/debug/app-debug.apk` |
| Installed code size (`codePath`) | **≈195 MB** (`du -sh`) | `adb -s emulator-5556 shell du -sh <codePath>` after `dumpsys package org.ort.app \| findstr codePath` |
| App data dir after first launch | **100 MB** (`/data/data/org.ort.app`) | `adb -s emulator-5556 shell run-as org.ort.app du -sh /data/data/org.ort.app`, measured ~8 s after `am start` (enough for `BundledAssetInstaller.installAll`'s background copy to finish) |
| `files/` alone (the copied models) | **100 MB** | `du -sh /data/data/org.ort.app/files` |
| **Total installed footprint (code + data)** | **≈295 MB** | sum of the two `du` figures above |

The four non-gated assets landed exactly where the existing locators expect, each with its
`.sha256` marker (`ModelAcquisition`'s own convention, reused — `rowFor` reports `INSTALLED`
through the one existing code path, per this package's own design KDoc), plus
`bundled_assets.manifest` (the filesystem contract `measureStorageAccounting` reads,
FR-AST-3a/AC-139):

```
files/models/whisper-tiny-en-int8/tiny.en-encoder.int8.onnx(.sha256)
files/models/whisper-tiny-en-int8/tiny.en-decoder.int8.onnx(.sha256)
files/models/whisper-tiny-en-int8/tiny.en-tokens.txt(.sha256)
files/models/silero-vad/silero_vad.onnx(.sha256)
files/bundled_assets.manifest      # exactly these four relative paths, sorted; no Gemma line
```

## Per-asset breakdown (from `bundled-assets.json`, the manifest's own declared sizes)

| Asset | Tiers | Gated | Size (declared) | Present in this build |
|---|---|---|---|---|
| Whisper tiny.en encoder | T0–T3 | no | 12,937,772 B (≈12.3 MB) | yes |
| Whisper tiny.en decoder | T0–T3 | no | 89,853,865 B (≈85.7 MB) | yes |
| Whisper tiny.en tokens | T0–T3 | no | 835,554 B (≈0.8 MB) | yes |
| Silero VAD | T0–T3 | no | 643,854 B (≈0.6 MB) | yes |
| Gemma 3 1B int4 | **T3 only** | **yes** | 554,661,243 B (≈529 MB) | **no — HF_TOKEN absent, escape hatch used** |
| **Sum, all five** | | | **658,932,288 B (≈629 MB)** | — |

## What this number does not include

Gemma 3 1B (≈529 MB) is the large majority of the bundled-asset weight and is **absent from this
specific measurement** because this machine has no `HF_TOKEN`. A real CI or release build (which
sets `HF_TOKEN` from a secret and never sets the escape hatch — `.github/workflows/*.yml`) would
fetch and package it too, so the true, complete numbers are:

| Figure | This measurement (Gemma absent) | Projected complete (Gemma present) |
|---|---|---|
| APK size | 204,558,738 B (≈195 MB) | **≈195 MB + 529 MB ≈ 724 MB** |
| Installed data after first launch | 100 MB | **≈100 MB + 529 MB ≈ 629 MB** |
| Total installed footprint | ≈295 MB | **≈1.35 GB** |

The projection is arithmetic (declared size added to the measured baseline), not a second
measurement — the Gemma `.task` file is not re-compressed inside the APK any differently than the
other model files (AGP does not recompress already-compressed model binaries by default under
`noCompress`-eligible extensions, and every existing entry's on-disk `files/` size already matches
its declared `sizeBytes` exactly, byte for byte, in the table above), so the projection should be
accurate to within normal APK-container overhead (a few hundred KB).

**A future session with `HF_TOKEN` set should re-run this measurement for real** and replace the
projected row with a measured one — this file's own report is explicit about which rows are which,
per constitution VI ("no number without its provenance").

## R18's own risk, re-assessed with a real number

R18 asks whether bundling every asset "makes the install too large to distribute or install". At
the projected complete size (≈724 MB APK, ≈1.35 GB installed), this is a **large** app by mobile
standards but not obviously undistributable: Android's per-APK size ceilings (App Bundles aside)
and typical device storage headroom both accommodate it, and F-Droid/GitHub-releases-style
self-distribution (D11) has no App Store-style hard cap the way Play's classic APK limit once did.
The mitigations R18 names are in place and measured here directly: sizes are reported per asset
(the table above), bundled storage is excluded from the operator's retention budget and reported
separately (FR-AST-3a, AC-139 — see `StorageAccountingTest`'s own `AC_139` tests), and a
tier-ineligible model is stored but never loaded (AC-138 — the LLM's own `tiers = ["T3"]`, checked
by `ModelsControllerTest`'s `WPG a tier-3-only bundled asset reports tierEligible false...` test).
**Left open:** the real, complete (Gemma-included) measurement, and R18's own "revisit if it
exceeds what the distribution channel allows" — no distribution channel has been chosen yet to
check that against (D11 keeps this open pending the Play-path decision).
