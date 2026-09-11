# Installed size — bundled assets (R18, FR-AST-3a)

E2-H09. Two measurements, both against a real `assembleDebug` artifact installed on a real
emulator, never estimated. **The 2026-09-11 measurement is the complete one** — every manifest
entry fetched with a real `HF_TOKEN`, no escape hatch — and supersedes the 2026-09-10 baseline,
which is kept below for its provenance (constitution VI: no number without how it was obtained).

## 2026-09-11 — complete (5 of 5 assets, `HF_TOKEN` set)

| Field | Value |
|---|---|
| Commit | `686212f4` (main, every capture-modes package merged; full gate green) |
| Date | 2026-09-11 10:08 local |
| Fetch | `./gradlew fetchBundledAssets` with `HF_TOKEN` in the environment — `fetchBundledAssets: 5/5 assets verified and packaged under app/src/main/assets/bundled`, 1 m 36 s (the token was verified beforehand: a headers-only request for the gated file returned `302` with `X-Linked-Size: 554661243` and an ETag equal to the pinned sha256; the same request without the token is `401`) |
| Build | `./gradlew :app:assembleDebug` — **no `-PortAllowMissingBundledAssets`** |
| Variant | `debug` (`app-debug.apk`, sha256 `0c7e1ecb6914…`) |
| Device | Android emulator `emulator-5554` (`ort_audit`, Pixel 6), API 34, `x86_64`, `/data` 5.8 GB with 4.2 GB free before install |

Packaged under `app/src/main/assets/bundled/` (gitignored — `.gitignore:44`), byte for byte the
manifest's declared sizes:

```
bundled/manifest.json                                             1,421
bundled/models/llm/gemma3-1b-it-int4.task                   554,661,243
bundled/models/silero-vad/silero_vad.onnx                       643,854
bundled/models/whisper-tiny-en-int8/tiny.en-decoder.int8.onnx  89,853,865
bundled/models/whisper-tiny-en-int8/tiny.en-encoder.int8.onnx  12,937,772
bundled/models/whisper-tiny-en-int8/tiny.en-tokens.txt            835,554
```

### Numbers measured

| Figure | Value | How measured |
|---|---|---|
| APK size (`app-debug.apk`) | **611,029,854 bytes (≈583 MiB)** | `Get-Item app/build/outputs/apk/debug/app-debug.apk`; `ls -l …/base.apk` on the device agrees byte for byte |
| Installed code (`codePath`) | **583 M** (`du -sh`) | `adb shell du -sh <codePath>` after `dumpsys package org.ort.app` |
| App data after first launch | **629 M** (`/data/data/org.ort.app`) | `run-as org.ort.app du -sh`, taken once `bundled_assets.manifest` existed (the Gemma copy took ≈ 60 s on this emulator; at 25 s it was still `gemma3-1b-it-int4.task.part` with no manifest — the installer writes the manifest last, so its presence is the completion signal) |
| of which `files/models/llm` | **529 M** | `du -sh` |
| **Total installed footprint (code + data)** | **≈1,212 M ≈ 1.18 GiB** | sum of the two `du` figures |

Every copied file matches its declared size exactly and carries its `.sha256` marker; no `.part`
or `.rejected` file remains:

```
files/models/llm/gemma3-1b-it-int4.task                   554,661,243  + .sha256
files/models/silero-vad/silero_vad.onnx                       643,854  + .sha256
files/models/whisper-tiny-en-int8/tiny.en-decoder.int8.onnx  89,853,865  + .sha256
files/models/whisper-tiny-en-int8/tiny.en-encoder.int8.onnx  12,937,772  + .sha256
files/models/whisper-tiny-en-int8/tiny.en-tokens.txt            835,554  + .sha256
files/bundled_assets.manifest   223 bytes — five relative paths, sorted, Gemma included
```

### Per-asset breakdown (declared = measured)

| Asset | Tiers | Gated | Size | Present |
|---|---|---|---|---|
| Whisper tiny.en encoder | T0–T3 | no | 12,937,772 B (≈12.3 MiB) | yes, verified |
| Whisper tiny.en decoder | T0–T3 | no | 89,853,865 B (≈85.7 MiB) | yes, verified |
| Whisper tiny.en tokens | T0–T3 | no | 835,554 B (≈0.8 MiB) | yes, verified |
| Silero VAD | T0–T3 | no | 643,854 B (≈0.6 MiB) | yes, verified |
| Gemma 3 1B int4 | **T3 only** | **yes** | 554,661,243 B (≈529 MiB) | **yes, verified** — stored on every tier, loaded only at tier 3 while idle and charging (AC-138) |
| **Sum, all five** | | | **658,932,288 B (≈628 MiB)** | — |

### Against the 2026-09-10 projection

The baseline projected ≈724 MB APK / ≈1.35 GB installed by adding Gemma's declared size to the
four-asset measurement. The measured APK is **611 MB, ≈113 MB smaller than projected**: the APK
container compresses the `.task` file (the projection assumed it would be stored uncompressed like
the ONNX files). The installed data (629 M) matches the projection (≈629 MB) because the installer
copies every asset out uncompressed. Total footprint ≈1.18 GiB against the projected ≈1.35 GB.

## R18, re-assessed with the complete number

At 611 MB APK / ≈1.18 GiB installed this is a large app but not undistributable: self-distribution
(D11 — GitHub releases / F-Droid-style) has no hard APK cap, and the device headroom the reference
phone has (hundreds of GB) is not the constraint. The mitigations R18 names are in place and now
measured for real: sizes reported per asset (this table and CF04's rows), bundled storage excluded
from the operator's retention budget and reported separately (FR-AST-3a, AC-139), and the
tier-ineligible model stored but never loaded (AC-138). **FR-AST-3a's TODO — optional asset packs
so a tier-0/1 phone need not carry 529 MiB it will never load — stays open, now with its
measured cost: the LLM is 84 % of the bundled bytes and 87 % of the APK.** Revisit when a
distribution channel with a size ceiling is chosen (D11).

---

## 2026-09-10 — baseline (4 of 5 assets, `HF_TOKEN` absent, escape hatch) — superseded

Commit `7dc81be`, `./gradlew build dependencyRules platformGuards -PortAllowMissingBundledAssets=true`,
`emulator-5556` API 34 x86_64. `fetchBundledAssets` reported `4/5 assets verified and packaged …
(1 marked missing)` with the one-line `HF_TOKEN` instruction. Measured: APK **204,558,738 B
(≈195 MB)**, installed code ≈195 MB, app data after first launch **100 MB** (`files/` 100 MB),
total ≈295 MB; the four non-gated files landed byte-exact with markers and a four-line manifest
(no Gemma). The projection made then (≈724 MB / ≈1.35 GB) is corrected by the measurement above.
