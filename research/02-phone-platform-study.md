# Phone as the Transcriber

**Platform feasibility research — Android and iOS · September 2026**

Investigates whether dedicated hardware can be skipped entirely: the phone takes audio in
from the radio, runs ASR locally, stores everything, and is its own reader UI.

Companion artifact: <https://claude.ai/code/artifact/89d73e33-6fb1-44c6-b61c-d5e6fc6e63d2>

---

## 1. Verdict

**The phone path works on Android, and it works for a reason unrelated to NPUs.**

A 2019 Galaxy S10 (Exynos 9820) runs Whisper small at 2.4x real time on plain CPU via
sherpa-onnx. A scanner is busy ~15% of the time, so the threshold to clear is 0.15x — a
**16x margin** before an NPU enters the conversation. Compute was never the constraint.

The constraint is background execution, and Android answers it cleanly: the `microphone`
foreground service type carries **no time limit**. Eight hours is legal and documented.

The phone does not beat the $52 Pi Zero recorder on accuracy, because nothing on-device
beats desktop `large-v3`. It beats it by **adopting the same architecture** while also
being the screen, battery, storage and reader app you would otherwise have to build.

---

## 2. Measured throughput

The one benchmark measuring these engines on actual phone silicon is
[VoicePing's offline transcription
study](https://voiceping.net/en/blog/research-offline-speech-transcription-benchmark/) —
16 models across 9 engines on Android, iOS, macOS and Windows.

**Methodology:** 30-second looped JFK inaugural address, 16 kHz mono PCM 16-bit. Speed
only — **no WER measured**. Android device is a Samsung Galaxy S10 (Exynos 9820, 8 GB,
Android 12), meaningfully slower than anything bought today, which makes it a conservative
floor.

> **Convention conversion.** VoicePing reports RTF as processing ÷ duration (lower better).
> This project uses audio-seconds per wall-second (higher better). All figures below are
> **reciprocals** of the source. Their 0.41 is our 2.4x.

### Android — Galaxy S10, CPU only

| Model | Engine | Size | Source RTF | **Project RTF** | tok/s |
|---|---|---:|---:|---:|---:|
| Moonshine tiny | sherpa-onnx | ~125 MB | 0.05 | **20x** | 42.55 |
| Whisper tiny | sherpa-onnx | ~100 MB | 0.07 | **14x** | 27.08 |
| Whisper base | sherpa-onnx | ~160 MB | 0.13 | **7.7x** | 14.36 |
| Whisper small | sherpa-onnx | ~490 MB | 0.41 | **2.4x** | 4.70 |
| Whisper tiny | whisper.cpp | ~31 MB | 3.52 | **0.28x** | 0.55 |

Four of five clear the 0.15x bar by an order of magnitude or more. Even Whisper small
finishes a 10-second over in about four seconds on a seven-year-old midrange SoC.

> **One number here is not credible.** VoicePing reports whisper.cpp Whisper tiny at 0.28x
> against sherpa-onnx's 14x on the *same model, same device* — a claimed 51x engine gap.
> Both are CPU inference of identical weights. The likely cause is a misconfigured
> whisper.cpp build (thread count, or missing ARM NEON flags). The corroborating
> [whisper.cpp Android
> discussion](https://github.com/ggml-org/whisper.cpp/discussions/3567) describes a
> streaming loop that re-transcribes a growing buffer every tick — 5–7 s to process 1 s of
> audio, with latency climbing 3 s → 10 s → 30 s until ANR. **Read this as "sherpa-onnx is
> the better-engineered Android path," not as a measured 51x.**

### iOS — iPad Pro 3rd gen, A12X, 4 GB

| Model | Engine | tok/s | Status |
|---|---|---:|---|
| Whisper tiny | whisper.cpp | 37.8 | OK |
| Moonshine tiny | sherpa-onnx | 37.3 | OK |
| Whisper tiny | WhisperKit (CoreML) | 4.5 | OK |
| Whisper base | WhisperKit (CoreML) | 19.6 | **OOM** |
| Whisper small | WhisperKit (CoreML) | 6.3 | **OOM** |

WhisperKit CoreML crashes on 4 GB iOS devices for models above Whisper tiny.

### iPhone, Neural Engine

- **`large-v3-turbo` via WhisperKit on iPhone 15 Pro:** 10 minutes of audio in ~82 seconds
  = **~7x**. A second source claims ~19x; take 7x conservatively.
- **Apple SpeechTranscriber vs WhisperKit** on earnings calls
  ([Argmax](https://www.argmaxinc.com/blog/apple-and-argmax)): WER 14.0% at 70x speed vs
  base.en 15.2% at 111x and small.en 12.8% at 35x. Apple's is genuinely competitive.
- **iPhone 16 Pro Neural Engine measured 4.3x its own GPU** on the same model; the
  iPhone 17 Pro's vapor chamber held performance across a 30-minute continuous transcription
  stress test where the 16 Pro degraded
  ([Argmax](https://www.argmaxinc.com/blog/iphone-17-on-device-inference-benchmarks)).

**Android `large-v3-turbo` throughput: unknown.** No equivalent published figure found.

---

## 3. Runtime assessment

| Runtime | Models | VAD | Verdict | Note |
|---|---|---|---|---|
| **sherpa-onnx** | Whisper, Moonshine, Zipformer, SenseVoice | Silero + TEN, in-library | **Choose this** | Kotlin/Java API. Ships "VAD + non-streaming ASR" Android demos for both Whisper tiny.en and Moonshine tiny. Also speaker diarization, speaker ID, KWS |
| whisper.cpp | Whisper only | External | Fallback | Works, but Android bindings are the least-tuned target and streaming is a known trap |
| LiteRT / MediaPipe | Converted models | External | NPU path only | Real NPU delegation, 30+ Qualcomm devices, MediaTek NeuroPilot. Argmax and Google Meet are named production adopters |
| Google SODA | Google's own | Built in | **Unavailable** | No public third-party API. Powers Live Caption; not addressable |
| Apple SpeechAnalyzer | Apple's own | Built in | iOS only, no custom vocab | ~10 languages, and the custom-vocabulary feature present in earlier APIs was dropped |

**Why the VAD integration decides it.** Squelch-tail hallucination is the #1 practical
failure mode. The required mitigation is VAD before the model plus a `no_speech_prob`
ceiling, minimum-duration and repetition filters after it. sherpa-onnx ships Silero VAD and
TEN-VAD inside the same library, and its headline Android example *is* VAD-gated
non-streaming ASR. The primary failure mode is mitigated by the framework's default
architecture rather than something bolted on.

---

## 4. The NPU is reachable and irrelevant

> **Superseded in part — see [`04-flagship-capability.md`](04-flagship-capability.md) §3.**
> This section's reasoning holds under the assumption that the design ceiling is capped by
> the weakest supported device. Once that constraint is dropped, the conclusion reverses:
> the surplus argument shows you do not need more *speed*, but it does not show you cannot
> **spend** speed on a bigger model — and Qualcomm publishes `large-v3-turbo` on the NPU at
> roughly 22x real time, five callsign-accuracy points above what the CPU path runs. The
> section is left intact below because the reasoning is sound within its stated premise and
> the premise change is worth seeing explicitly.

Third parties can reach mobile NPUs. [LiteRT NPU
delegation](https://developers.google.com/edge/litert/next/npu) covers 30+ Qualcomm devices
and MediaTek NeuroPilot without vendor-specific compilers; Qualcomm's AI Engine Direct
(QNN) reaches Hexagon more directly; Nexa SDK wraps both.

**And it should be skipped.** Every NPU path costs ahead-of-time compilation per SoC,
vendor-fragmented tooling, and a hard dependency on specific silicon — a phone replacement
in two years becomes a porting project. What it buys is throughput, the one resource
measured at **16x surplus**.

The NPU argument would matter if `large-v3-turbo` had to run live on-device. It does not,
because the archived-audio pass gets `large-v3` proper on a desktop, which beats turbo on a
phone by every measure. **The NPU answers a question the recorder-first architecture
deletes.**

---

## 5. Risk ledger

### 01 · OEM background-killing — the skin, not the OS · *Manageable*

Stock Android is fine. Per [Android's foreground service timeout
documentation](https://developer.android.com/develop/background-work/services/fgs/timeout),
the 6-hour-per-24 cap applies **only to `dataSync` and `mediaProcessing`**. The
`microphone` type has no timeout. The documented microphone restrictions concern *starting*
a service — you cannot launch one from the background or from a `BOOT_COMPLETED` receiver
on API 34+ — which is irrelevant when the user opens the app and taps start.

The hazard is vendor firmware. [DontKillMyApp](https://dontkillmyapp.com/) ranks **Samsung
worst**, with an aggressive app-restriction setting enabled by default that stops even
foreground services holding wake locks; Xiaomi's MIUI/HyperOS kills foreground services
outright and resets autostart permissions after OTA updates. Pixel is cleanest.

Mitigations: buy a Pixel; one-time setup ritual (exempt from battery optimization, remove
from sleeping-apps lists, disable adaptive battery); hold a partial wake lock alongside the
foreground service; log service lifecycle events so a silent kill is visible.

### 02 · iOS technically fine, commercially fragile · *Use Android*

[Apple DTS states
plainly](https://developer.apple.com/forums/thread/776949) that an app which starts an
audio recording session in the foreground may continue recording **indefinitely** in the
background, surfaced as the orange status pill. The capability exists.

The problem is review. The `audio` background mode is specified for playback, and apps
declaring it for background recording have been rejected under Guideline 2.5.4 for lacking
audible content. Android's foreground service is a documented, supported, unlimited
mechanism for exactly this; iOS's is a tolerated edge case.

### 03 · Audio input — solved, dongle is a lottery · *Solved*

Software side is documented and available since API 23: enumerate with
`AudioManager.getDevices(GET_DEVICES_INPUTS)`, filter `TYPE_USB_DEVICE`, pass to
`AudioRecord.setPreferredDevice()`. **Verify with `getRoutedDevice()`** so a silent fallback
to the built-in mic — which would record the room instead of the radio — is caught
immediately rather than after eight hours.

Charging while capturing needs a USB-C hub carrying both PD passthrough and USB Audio Class
input. These exist (Belkin charge-plus-mic splitter; 3-in-1 OTG + 3.5 mm + PD adapters), but
cheap dongles fail specifically at doing *both at once*. Buy two from different makers.

**Bluetooth is disqualified.** A2DP is output-only; capturing a microphone forces HFP, whose
best codec is mSBC — ordinary SBC locked to 16 kHz mono at bitpool 26. You would feed an
already-degraded off-air signal through a low-bitrate subband codec at exactly the sample
rate Whisper consumes. Wired only.

### 04 · Thermal and battery — absorbed by duty cycle · *Non-issue*

Sustained-load studies show real degradation — up to 41.5% throughput loss under continuous
inference ([arXiv 2603.23640](https://arxiv.org/pdf/2603.23640)) — but both that and the
Argmax vapor-chamber finding measure *continuous* load. At 15% duty, applying the worst
published haircut to Whisper small's 2.4x still leaves 1.4x, nine times the threshold.

Energy over 8 h at 15% busy = 4,320 audio-seconds, using burst watts ÷ RTF:

| Model | RTF | J/audio-s @4 W | Inference | + idle & capture | Total |
|---|---:|---:|---:|---:|---:|
| Moonshine tiny | 20x | 0.20 | 0.24 Wh | 2.4 Wh | **2.6 Wh** |
| Whisper small | 2.4x | 1.67 | 2.0 Wh | 2.4 Wh | **4.4 Wh** |
| Whisper small @8 W | 2.4x | 3.33 | 4.0 Wh | 2.4 Wh | **6.4 Wh** |

Against a 15–20 Wh battery the pessimistic case uses roughly a third. **The 8-hour
requirement is met unplugged.** Burst power is an **estimate** — no measured phone-ASR power
figure was found; the sensitivity row exists because the conclusion should not depend on it.
Screen off and cellular disabled; the display and radios outdraw the ASR otherwise.

### 05 · CAT serial — recovered, not lost · *Solved*

The brief listed "no CAT serial without an OTG hub" as a cost. But the hub is needed
regardless for charging plus USB audio, so a serial adapter is one more port on hardware
already in the build — marginal cost effectively zero.

`usb-serial-for-android` is a mature, unrooted library covering FTDI, CP210x, CH34x and
CDC-ACM through Android's USB host API with a runtime permission prompt.

**Unverified:** whether the SDS150's USB port enumerates as a serial device Android can
claim, and whether the TH-D75A's CAT interface does the same. Both need a physical test.

---

## 6. Comparison against the SBC ladder

| Path | Cost | Accuracy | Reader UI | Standing |
|---|---:|---:|---|---|
| Pi Zero 2 W recorder | $52 | 95% | Must build | Still the accuracy benchmark |
| On-box live, low | $57 | ~88% | Must build | **Superseded** |
| On-box live, high | $166 | 93% | Must build | **Superseded** |
| **Used Pixel + hub** | **~$120** | **95%\*** | **Is the device** | **Recommended** |

\* via the deferred desktop pass; on-device live is the Moonshine rough tier.

**The on-box live transcription rungs are dead.** A ~$90 used Pixel 6a plus a $20 hub beats
the $166 tier on throughput while including a screen, battery, case, charger, storage, Wi-Fi
and the reader application — all of which that $166 excludes.

**The $52 Pi Zero recorder is not beaten. It is adopted.** That path wins structurally by
declining to transcribe on-device. The phone runs the same architecture and gains what the
Pi cannot offer: a live rough pass, no BLE link, no storage sync, no companion app.

### What you give up

- **A phone is tied up.** Real, but a $90 dedicated used handset is not a daily driver.
- **No GPIO.** A hardware squelch tap, discriminator-out level sensing, or PTT detect has
  pins on a Pi and nothing on a phone. **The most likely regret.**
- **Sealed battery.** The Pi runs off any pack and swaps it.
- **Physical fit.** A Pi Zero in a project box is more at home velcroed to a scanner.
- **Background-execution fragility** as a permanent low-grade risk.

---

## 7. Unknowns

- **No measured WER exists for any of these models on off-air radio audio.** Every accuracy
  figure is transferred from ATC literature or clean-speech benchmarks.
- **Android `large-v3-turbo` throughput: unknown.**
- **Phone burst power under ASR: unmeasured.**
- **The 41.5% throttling figure is LLM inference, not ASR**, and ASR's bursty profile likely
  behaves better. Directional only.
- **Whether a specific PD+UAC dongle works with a specific phone** is a device lottery.
- **SDS150 and TH-D75A USB serial enumeration under Android** — untested, and the cheapest
  thing to verify first.
- **ONNX export of a fine-tuned Whisper** into the form sherpa-onnx consumes. Export scripts
  exist for stock checkpoints; unverified for a custom fine-tune.

---

## Sources

[VoicePing offline transcription
benchmark](https://voiceping.net/en/blog/research-offline-speech-transcription-benchmark/) ·
[Android foreground service
timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout) ·
[Android 15 foreground service type
changes](https://developer.android.com/about/versions/15/changes/foreground-service-types) ·
[Apple Developer Forums — Background Audio
Recording](https://developer.apple.com/forums/thread/776949) ·
[k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) ·
[Moonshine paper](https://arxiv.org/html/2410.15608v1) ·
[Argmax — Apple SpeechAnalyzer and
WhisperKit](https://www.argmaxinc.com/blog/apple-and-argmax) ·
[Argmax — iPhone 17
benchmarks](https://www.argmaxinc.com/blog/iphone-17-on-device-inference-benchmarks) ·
[LiteRT NPU acceleration](https://developers.google.com/edge/litert/next/npu) ·
[DontKillMyApp](https://dontkillmyapp.com/) ·
[Android Police — Samsung most aggressive at killing background
apps](https://www.androidpolice.com/2021/02/18/samsung-takes-dubious-honor-as-oem-most-aggressively-killing-background-apps/) ·
[AudioRecord.setPreferredDevice](https://developer.android.com/reference/android/media/AudioRecord) ·
[LLM Inference at the Edge under Sustained Load](https://arxiv.org/pdf/2603.23640) ·
[whisper.cpp Discussion
#3567](https://github.com/ggml-org/whisper.cpp/discussions/3567) ·
[WhisperKit paper](https://arxiv.org/abs/2507.10860)
