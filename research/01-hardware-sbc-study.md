# Transcribing the Band on a Battery

**Hardware feasibility study — single-board computers · rev. 5 · September 2026**

Preserved complete from the original study. Assumptions throughout: non-gated builds,
2x18650 (25.9 Wh) unless stated, 15% channel activity.

Which speech models actually fit and actually keep up on each single-board computer, what
that costs in watts, why almost every cheap NPU cannot run speech recognition at all, and
the builds that survive.

---

## 1. The verdict

**RAM stops being the constraint the moment you leave the Pi Zero.** A Pi 5 holds up to
16 GB; both it and the Rockchip boards fit `large-v3-turbo-q5_0` (547 MB on disk) with
room to spare. The 512 MB ceiling on the Zero 2 W is the outlier, not the rule.

**But fitting the model and affording to run it are different questions.** The energy cost
of transcription is *burst watts divided by real-time factor* — joules per second of
speech. A Pi 5 running `small.en` spends 14 J on every second of audio; the same Pi 5
running Moonshine base spends 0.3 J. Running a big model on a fast CPU is the most
expensive thing in this study, and it is what kills the Pi 5's battery life — not its idle
draw.

**The way out is an NPU — but only about four NPUs on the market qualify.** Cheap
accelerators are conv-net engines built for vision, and most of their compilers cannot
express attention, layernorm, or variable sequence length at all. A chip can advertise
1 TOPS and be structurally unable to run Whisper-tiny. The survey below disqualifies nine
SoC families for documented reasons and names the three that work.

**The demonstrated price floor is $15** — the Pi Zero 2 W, running whisper.cpp `tiny.en`
at ~267 MB peak of 512 MB with zero swap. Nothing cheaper has a working open-vocabulary
pipeline, and no NPU board under $30 does either. The silicon exists at that price; the
software does not.

**On-box 93% starts at $166 all-in and does not get cheaper.** The Radxa ROCK 4D is the
floor for turbo-class accuracy on a battery, at $79.90 for the one variant still in stock.
The Axera AX630C is the interesting alternative at roughly `small`-class accuracy — but it
is $99.90 and out of stock everywhere checked.

**And the cheapest route to the best accuracy in this entire study is $53 with no NPU at
all** — record on a Pi Zero, transcribe on your desktop with `large-v3`, read the digest
later. If hours of latency are acceptable, that is the build.

**For a live readout, the Zero's ceiling is higher than it looks.** distil-small.en carries
a 2-layer decoder against `small.en`'s 12, which is what lets it both fit 512 MB and stay
ahead of a 15%-busy channel — roughly **88% callsign accuracy for $57**, five points off
the $166 board. Unverified, and one command settles it.

---

## 2. The metric: RTF, and why it decides everything

**RTF is real-time factor: how many seconds of audio the machine transcribes per second of
wall-clock time.** RTF 2x means a one-minute recording takes thirty seconds to transcribe.
RTF 0.5x means that same minute takes two. Higher is faster.

> **Careful — the convention flips.** Much of the ASR literature defines RTF the other way
> round, as *processing time / audio duration*, where lower is better and 0.5 means twice
> as fast as real time. The two are reciprocals. **Everything in this document uses the
> speed convention: bigger number, faster machine.** When you compare against a paper or a
> benchmark repo, check which way theirs runs before concluding anything.

The usual advice — "tiny and base run in real time on a Pi, small doesn't" — is written for
live dictation, where you need output as fast as someone speaks. It is the wrong test for a
radio that is silent most of the day. Two corrections follow.

### The two thresholds

```
1. Keeping up requires only   RTF > busy fraction,   not RTF > 1.
   At 15% channel activity, an RTF of 0.5x has 3x headroom.
   small.en on a Pi 5 "fails" the real-time test and passes this one easily.

2. Energy per second of audio = burst watts / RTF.
   This is what you actually pay. It is why a 7 W board at RTF 2x (3.5 J/s)
   beats the same 7 W board at RTF 0.2x (35 J/s) tenfold, on identical hardware.

   Average draw = idle + 0.1 W + busy x (burst - idle) / RTF
   Runtime      = 25.9 Wh / (average / 0.88)
```

### Worked example: one over on a Pi Zero 2 W

Someone keys up for **6 seconds**. Moonshine base on a Zero 2 W runs at RTF 2x, so
transcription takes **3 seconds**, during which the board pulls its 2.5 W burst figure
instead of its 0.45 W idle.

```
energy for this over   = 2.5 W x 3 s          = 7.5 J
per second of audio    = 7.5 J / 6 s          = 1.25 J/s
                       = burst / RTF = 2.5 / 2 = 1.25 J/s   (same thing)

in one hour at 15% busy:
  speech                = 3600 s x 0.15        = 540 s
  transcription energy  = 540 x 1.25           = 675 J
  added average power   = 675 J / 3600 s       = 0.19 W

  total average = 0.45 idle + 0.19 transcribe + 0.1 peripherals = 0.74 W
```

Now swap in `base.en` at RTF 0.4x. Energy per second of audio jumps to 2.5 / 0.4 =
**6.25 J/s**, the transcription term becomes 0.94 W, and total average roughly doubles to
1.49 W. The board is identical; only the model changed. That is the whole argument of this
study in one substitution.

> **Idle power sets the floor; energy-per-second sets the ceiling.** On a quiet band the
> idle term dominates and you want a cool-running board. On a busy band the transcription
> term dominates and you want a high RTF. The 15% assumption sits between those regimes,
> which is why both columns matter.

---

## 3. The price ladder

All-in system cost — board, cells, power electronics, storage and audio input — not just
the board price. Board prices verified against vendor pages September 2026; Amazon
resellers run 15–30% above these.

> **The cheapest path to the highest accuracy costs $53 and has no NPU in it.** Accuracy
> comes from the model, and the model does not have to run on the box. A $18 recorder that
> captures gated, timestamped Opus and hands it to your desktop reaches **95%** — better
> than anything in this study running on-board — because your desktop runs `large-v3`. You
> pay for it in latency, and in nothing else.

| All-in | Build | Callsign | Runtime | Latency | Availability |
|---:|---|---:|---:|---:|---|
| **$53** | **Pi Zero 2 W as a recorder** — one 21700, 32 GB card, $6 dongle. Transcribe on desktop. | **95%** | **29 h** | hours | Everything in stock. **Best accuracy per dollar** |
| $57 | Pi Zero 2 W + `base.en` — ggml, no ONNX memory risk | 80% | 12.2 h | 5–20 s | In stock everywhere. *Safest live build* |
| **$57** | **Pi Zero 2 W + distil-small.en** — 2-layer decoder makes it fit and keep up | **88%?** | **9.7 h** | 10–30 s | Unverified in 512 MB — one `free -m` settles it. *The bet worth making* |
| $70 | Orange Pi Zero 2W 1 GB + distil-small.en — same model, gamble removed | 88% | 9.9 h | 8–25 s | In stock. Armbian, not vendor image. *The fallback* |
| $67–84 | Pi Zero 2 W + I²S codec, no USB — PCM1808 $67, WM8960 $84 | 88%? | 10–13 h | 5–30 s | Buys mechanical integrity, not accuracy |
| $121 | Orange Pi 3B 4 GB — RK3566, Zipformer on 0.8 TOPS NPU | 80% | 16.5 h | 2–8 s | ~$35 board. Open bugs in the RKNN path |
| $140 | Pi 5 2 GB — Moonshine base at 25x RTF | 82% | 11.8 h | 1–3 s | Best CPU here, worst idle-to-capability ratio |
| $160 | M5Stack LLM630 — AX630C, SenseVoiceSmall, onboard mic + eMMC | 88%? | 14–37 h | <1 s | **$99.90 and out of stock.** *Supply risk* |
| **$166** | **Radxa ROCK 4D 6 GB** — RK3576, large-v3-turbo on 6 TOPS NPU | **93%** | **12 h** | 3–15 s | $79.90 direct. *Cheapest 93% on-box* |
| $174 | Orange Pi 5 4 GB — RK3588S, large-v3-turbo, mature tooling | 93% | 8.3 h | 2–10 s | Street ~$88. Barely clears target |

**Read the ladder as three decisions, not eight options.** Under $65 you are choosing what
to do with the same $18 board — 95% with hours of latency, 80% with seconds, or 88% if the
distil bet lands. Between $120 and $140 you buy responsiveness and headroom, not accuracy.
Only at **$166** does on-box 93% become available at all.

The honest shape is a **$57 rung and a $166 rung, with very little worth buying between
them.** The $121 and $140 builds exist, work, and are dominated.

> **Availability is currently a bigger constraint than price.** ROCK 4D has one variant
> left in stock, M5Stack's LLM630 is out at both M5Stack and Pi Hut, the Module LLM Kit
> that replaced the EOL'd $49.90 part is $79.90 and also out of stock, and the Radxa Zero
> 3W is out at ameriDroid.

> **Step zero costs nothing and beats every rung on this ladder.** The offline lexicon
> pass — phonetic expansion matched against the FCC ULS dump, the WWARA repeater list and
> the POTA park file — moves callsign extraction from about 40% to 80–90% on *any* of these
> builds. That is a larger gain than the entire $53-to-$174 span buys you. Write it before
> you buy anything.

---

## 4. The bottom rung: how cheap the Zero actually goes

Gated Opus at 15% duty writes about **1.1 MB per hour of wall clock** — roughly 9 MB a day.
A 32 GB card is a lifetime of storage.

> **There is no 1 GB or 2 GB Pi Zero, and there never has been.** Every Zero ever made —
> Zero, Zero W, Zero 2 W — ships 512 MB. The reason is packaging, not product strategy: no
> 1 GB monodie exists in the required package, and two 512 MB dies cannot be stacked in the
> package-on-package the BCM2710A1 uses.

### Minimum viable recorder BOM

| Item | ~$ | Notes |
|---|---:|---|
| Raspberry Pi Zero 2 W (SC1176 headerless) | 17 | $15 official, $17.25 PiShop US. Skip the +$3.50 header version |
| 1x Molicel INR-18650-M35A, 3500 mAh | 8 | 12.95 Wh. Authorised distributor only — marketplace 18650s are routinely counterfeit |
| TP4056 + MT3608 combo (USB-C charge + boost, DW01 protection) | 4 | 6-packs ~$12. **Set MT3608 to 5.05 V with a meter first.** 85–90% efficient |
| 18650 holder, 1-slot | 2 | Sprung holder avoids spot-welding |
| microSD 32 GB high endurance | 10 | Endurance matters, capacity does not |
| CM108 USB audio dongle | 6 | AC-couple the input — they put bias voltage on the pin |
| Micro-USB OTG adapter + 3.5 mm TRS cable | 5 | The Zero has micro-USB, not USB-A. Easy to forget |
| **Total — recorder, 1 cell** | **52** | ~20 h runtime, 95% accuracy once desktop transcribes |
| **Total — on-board ASR, 2 cells** | **62** | 13.8–17.3 h depending on model |

**Do not buy the original Pi Zero W.** Single-core ARM11; no one has run open-vocabulary
ASR on one. The economics have inverted: Pi Hut lists it at £14.40 and sold out, while
Pimoroni lists the far more capable Zero 2 W at £12.00.

### Zero-footprint boards with more than 512 MB

| Board | RAM | Price | Idle | Burst | Verdict |
|---|---|---:|---:|---:|---|
| **Orange Pi Zero 2W** — Allwinner H618, 4x A53 @ 1.5 GHz, no NPU | 1 / 1.5 / 2 / 4 GB | $18–30 | ~1.0 W | 2.4 W | Same A53 architecture at 1.5x the clock, ~2x idle. **The 1 GB variant is a real option** |
| Radxa Zero 3W — RK3566, 4x A55 @ 1.6 GHz, 0.8 TOPS NPU | 1 / 2 / 4 / 8 GB | $41.95 | 1.2–2.3 W | 4.0 W | NPU runs Zipformer — the same 80% you get from `base.en` on a $17 board |

> **Correction carried from an earlier draft.** The Orange Pi Zero 2W was previously put at
> 2.0–2.2 W idle and 4.0 W load, and dismissed on that basis. Those figures traced to
> machine-generated marketing content. An [independent blog running Linux
> 6.1](https://plati.ma/orange-pi-zero-2w/) measured **0.94 W on a minimal image, 1.12 W
> idle on Debian 12 with an XFCE desktop running, and 2.4 W maximum under full CPU stress**.
> Headless and tuned, idle is plausibly under 1 W.

### Does the extra RAM help? 1 GB yes, past that no

**The RAM does not buy you a better model — it buys you certainty that the model you
already wanted will fit.**

| Board + model | Needs | RTF | Runtime | Callsign | Memory risk |
|---|---:|---:|---:|---:|---|
| Pi Zero 2 W + `base.en` q5 | 220 MB | 0.4x | 17.3 h | 80% | None — proven |
| Pi Zero 2 W + distil-small.en | ~300 MB | 0.28x | 13.8 h | 88% | **Real — unverified in 512 MB** |
| **OPi Zero 2W 1 GB + distil-small.en** | ~300 MB | ~0.42x | 14.2 h | 88% | None — fits easily |
| OPi Zero 2W 1 GB + Moonshine base | ~350 MB + arena | ~3x | 19.5 h | 82% | None — arena stops mattering |
| OPi Zero 2W 1 GB + `small.en` q5 | ~450 MB | ~0.15x | 9.1 h | 88% | None, but zero RTF headroom |
| OPi Zero 2W 2–4 GB + distil-large-v3 | ~900 MB | ~0.15x | 9 h | 91% | Fits — but see below |
| OPi Zero 2W 4 GB + `large-v3-turbo` | ~1.0 GB | ~0.09x | — | 93% | Fits and cannot keep up |

> **1 GB removes the gamble at the same runtime. 2 GB and 4 GB buy nothing.** Everything
> needing more than ~500 MB runs at 0.15x or worse on these A53 cores — at or below the
> threshold a 15%-busy channel demands, with no margin.

The `distil-large-v3` row is instructive: it *fits* in 2 GB, it *technically* keeps up at
15% busy, and it reads as 91% accuracy for $25. It is not the recommendation because of
headroom — at 0.15x a single busy hour backs the queue up and it never recovers, and the
energy figure (2.4 / 0.15 = **16 J per second of audio**) means the transcription term
swamps idle entirely.

Two caveats: **Allwinner software support is materially worse than Raspberry Pi's** —
Armbian is the practical route — and **the H618 RTF figures are scaled from the Zero 2 W by
clock ratio**, defensible for identical A53 cores but ignoring memory-bandwidth
differences. Nobody has published whisper.cpp benchmarks on this board.

### The cell: one 21700 beats two 18650s

A single 21700 holds 5000 mAh against the 18650's 3500, runs about **300 Wh/kg against
250**, and costs up to **20% less per 1000 mAh**. It is 21x70 mm instead of 18x65.
One 21700 is **18.5 Wh**, enough to clear eight hours on every build in this study.

| Cell | Wh | ~$ | Recorder | Moonshine | `base.en` | distil-small |
|---|---:|---:|---:|---:|---:|---:|
| 1x 18650, 3500 mAh | 12.95 | 8 | 20.6 | 16.2 | 8.5 | 6.8 |
| **1x 21700, 5000 mAh** | **18.5** | **9** | **29.4** | **23.1** | **12.2** | **9.7** |
| 1x LiPo pouch, 5000 mAh | 18.5 | 16 | 29.4 | 23.1 | 12.2 | 9.7 |
| 1x LiFePO4 26650, ~3000 mAh | ~10 | 10 | 15.9 | 12.5 | 6.6 | 5.3 |
| 2x 18650 in parallel | 25.9 | 19 | 41.1 | 32.4 | 17.0 | 13.6 |
| 10,000 mAh USB power bank | ~33 | 18 | 60† | 47† | 25 | 20 |

**Switch the BOM to one 21700.** It is $7 cheaper than two 18650s plus a two-slot holder,
physically smaller, clears the target on every build, and — not trivially — **a single cell
cannot be wired in series by mistake.** That drops the on-box build to **$57** and the
recorder to **$53**.

> † **The power bank numbers carry a real trap.** Most power banks switch output off when
> draw falls below 50–100 mA — and a tuned Pi Zero idles at ~30 mA, with the recorder
> averaging ~110 mA. Anker's "trickle" mode times out after about five hours. The
> counterintuitive result: **a power bank is safest on the *busiest* build.** At 265–330 mA
> the distil-small.en config sits above any cutoff, while the low-power recorder is exactly
> the case that dies silently overnight. A USB KeepAlive dongle pulses current to hold it on.

### What fits and keeps up in 512 MB

| Model on the Zero 2 W | RAM | RTF | Runtime | Callsign | Verdict |
|---|---:|---:|---:|---:|---|
| `tiny.en` q5_1 | ~150 MB | 0.8x | 24 h | 70% | The proven floor — 267 MB peak measured, zero swap |
| **`base.en` q5_1** | ~220 MB | 0.4x | 17.3 h | 80% | ggml, no ONNX arena tax. Zero memory risk. *Safe default* |
| Moonshine base (ONNX) | ~350 MB? | 2x | 32.4 h | 82% | Best runtime by far, but ONNX Runtime's 2x arena makes 512 MB uncertain |
| **distil-small.en q5 (ggml)** | ~300 MB? | ~0.28x | 13.8 h | 88% | **The ceiling for this board.** Unverified in 512 MB. *Try this* |
| `small.en` q4_0 | ~350 MB | 0.1x | — | 86% | Q4_0 makes it fit, and it still cannot keep up |

Two things make the distil path more plausible than it looks. First, whisper.cpp's known
weakness with distil models is the missing chunk-based strategy — which matters for
long-form audio and **not for three-to-ten-second radio overs**. The workload sidesteps the
exact defect. Second, switching from Raspberry Pi OS Lite to Buildroot frees roughly
**100 MB**, the difference between "probably fits" and "fits".

### The rest of the Pi family

| Board | ~$ | RAM | Idle | Why it does not help |
|---|---:|---|---:|---|
| **Pi Zero 2 W** | 18 | 512 MB | 0.45 W | 4x A53 @ 1 GHz. The reference. *Stay here* |
| Pi 3A+ | 25 | 512 MB | ~1.4 W | 40% faster cores, same 512 MB, triple the idle |
| Pi 3B+ | 35 | 1 GB | 1.9–2.0 W | RAM doubles, but A53 runs `small.en` at ~0.15x |
| Pi 4 2GB | 45 | 2 GB | 2.7–3.4 W | **Strictly dominated by the Pi 5 2GB** |
| Pi 5 2GB | 50 | 2 GB | 2.4 W | Only worthwhile step up; `small.en` drops it to 6.6 h |

**Within the Pi family, more RAM always arrives bundled with more idle watts than the extra
model is worth.** The way past 512 MB is a different architecture — an NPU board where the
model runs at 10x instead of 0.3x — not a bigger Pi.

---

## 5. The NPU trap: TOPS is not the test, the compiler is

Nearly every accelerator under $30 was designed for vision — person detection, licence
plates, YOLO. Its toolchain was built to compile convolutions. Speech recognition is an
encoder-decoder transformer and needs three things a conv-net compiler often does not have:
**attention**, **layer normalisation**, and **variable sequence length with a KV cache**.
Miss any one and the model does not run at all, or silently falls back to CPU and the NPU
becomes decorative.

There is a reliable shortcut for spotting which vendors pass: **the ones that also ship
LLMs on the same silicon.** An LLM needs exactly the same three capabilities. If a vendor's
model zoo is all YOLO and MobileNet, its compiler will not run Whisper either.

### Disqualified — documented reasons

| SoC family | Board / price | NPU | RAM | Transformer verdict |
|---|---|---:|---|---|
| Rockchip RV1103 / RV1106 | Luckfox Pico $12–41 | 0.5–1 TOPS | 64–256 MB | Rockchip's changelog documents **no LayerNorm operator**. 64 MB would block it regardless |
| Sophgo SG2002 / CV1800B | Milk-V Duo $5–10 | 0.5–1 TOPS | 64–512 MB | **cvimodel does not support dynamic shapes** — vendor documented. Kills the autoregressive decoder |
| Arm Ethos-U65 (NXP i.MX 93) | EVK >$100 | — | varies | **Arm added transformer support only in Ethos-U85.** NXP runs Whisper/Moonshine on the A55 CPU; the NPU sits idle |
| Kendryte K230 | CanMV-K230 $50–62 | 6 TOPS | 512 MB–1 GB | Docs state the transformer is "significantly different from CNN" and redirect to RVV on CPU |
| Espressif ESP32-P4 | $10–20 | none | 768 KB | **Has no NPU.** "AI extensions" are DSP instructions. ESP-SR is ~200 fixed commands |
| Amlogic A311D / S905D3 | Khadas VIM3L $70–100 | 1.2–5 TOPS | 2–4 GB | VeriSilicon Acuity. Every published example is CNN vision |
| Allwinner V853 / T527 | $65–72 | 1–3 TOPS | varies | ACUITY/VIPLite. Zero demonstrated transformer ASR |
| TI AM67A | BeagleY-AI $70–75 | 4 TOPS | 4 GB | No ASR on TIDL. **Measured 4.7–4.9 W idle** — disqualifying on power before software matters |
| Bouffalo BL808 | Pine64 Ox64 $6–8 | 0.1 TOPS | 64 MB | 100 GOPS and 64 MB. Cannot host an encoder-decoder model |

### Passes the compiler test — ASR demonstrated

| SoC family | Board / price | NPU | RAM | Verdict |
|---|---|---:|---|---|
| Rockchip RK3566 | Orange Pi 3B $35, Radxa Zero 3W $42 | 0.8 TOPS | 1–8 GB | Cheapest chip with ASR actually demonstrated. Pre-converted Zipformer RKNN published. Rough — open bugs in endpointing and Split/Gather |
| Axera AX630C | M5Stack LLM630 $99.90 | 3.2 TOPS INT8 / 12.8 INT4 | 4 GB (~2 usable) | Pulsar2 has bucketed prefill graphs and first-class KV-cache. Pre-converted Whisper and SenseVoice with published RTF. Onboard mic. **Out of stock** |
| **Rockchip RK3576 / RK3588** | ROCK 4D $79.90, Orange Pi 5 ~$88 | 6 TOPS | 4–32 GB | The mature path, cheapest on-box 93%. SenseVoiceSmall reported ~20x real time on one RK3588 NPU core |
| Synaptics SL2610 (Torq) | no public price | 1 TOPS | 2 GB | Technically the best fit found: Moonshine V2 with *both* encoder and decoder on the NPU, 23.4 / 28.3 ms. No individual buy path |

> **The most damning evidence is behavioural.** Sipeed builds SG2002 boards *and* wrote the
> SG2002 software stack. When they shipped Whisper and SenseVoice, they shipped it on a
> different SoC — Axera's AX630C — and left their own Sophgo boards on a lightweight
> fixed-vocabulary library. Likewise, sherpa-onnx ships NPU backends for Rockchip, Axera and
> Ascend, and none for Sophgo. The ecosystem has already run this experiment.

One nuance: **Axera has the same static-shape constraint as Sophgo.** Pulsar2 requires
static ONNX. What Axera did was build the workaround — compiling multiple prefill subgraphs
at bucketed lengths (1, 512, 1024, 1536, 2048) with a preallocated ring KV cache, then
dispatching to the nearest one. The constraint is comparable; the execution is not.

---

## 6. Model catalogue

Disk sizes from the whisper.cpp model table; runtime memory is roughly the quantized file
plus 150–400 MB of working set. **Accuracy columns are extrapolated from the ATC ASR
literature onto weak off-air audio — bands, not measurements.**

| Model | Params | Disk | Runtime RAM | Overall | Calls + lex | Notes |
|---|---:|---:|---:|---:|---:|---|
| sherpa Zipformer int8 | ~70M | 80 MB | ~200 MB | 70% | 80% | Truly streaming. Partial text mid-over |
| Moonshine tiny | 34M | 190 MB | ~180 MB | 65% | 72% | Variable-length input; no 30 s padding tax |
| **Moonshine base** | ~60M | 400 MB | ~350 MB | 72% | 82% | ~5x faster than same-size Whisper, better WER |
| `tiny.en` q5_0 | 39M | 31 MB | ~150 MB | 62% | 70% | The floor |
| `base.en` q5_1 | 74M | 60 MB | ~220 MB | 70% | 80% | Fits everywhere. The accuracy reference |
| **distil-small.en** | 166M | ~120 MB | ~300 MB | 79% | 88% | Claims within 4% WER of `large-v3` at a seventh the size |
| **SenseVoiceSmall** | ~234M | ~230 MB | ~500 MB | 78%? | 88%? | Non-autoregressive, no decoder loop — why it flies on NPUs. **Headline benchmarks are Mandarin; English on noisy radio unverified** |
| `small.en` q5_1 | 244M | 190 MB | ~450 MB | 78% | 88% | Superseded by distil-small.en on every axis. Throttles a Pi 5 at 80 °C |
| distil-large-v3 q5 | 756M | ~600 MB | ~900 MB | 82% | 91% | 6.3x faster than `large-v3`, within 1% WER |
| `large-v3-turbo` q5_0 | 809M | 547 MB | ~1.0 GB | 83% | 93% | Best accuracy that runs on an NPU today |
| `large-v3` q5_0 | 1550M | 1.1 GB | ~1.6 GB | 85% | 94% | Nothing here can afford to run it. *Desktop only* |

One caveat on distil models: whisper.cpp has initial support but has not implemented the
chunk-based transcription strategy they were trained for. Use **sherpa-onnx** for
distil-whisper — its exporter explicitly supports `distil-small.en`, `distil-large-v2/v3`
and `v3.5`.

> **The runtime you pick changes the memory arithmetic.** ONNX Runtime's CPU memory arena
> costs roughly **2x the model file size** — a documented case shows a 230 MB int8 model
> consuming 450 MB+. whisper.cpp/ggml does not pay that tax. On anything with 512 MB this
> decides the build. Above 2 GB the distinction stops mattering.

---

## 7. Platforms, ordered by idle draw

Two findings worth pulling out: the **2 GB Pi 5 is a different chip** — the D0 stepping
draws about 2.4 W idle against 3.3 W for the 4 GB, a 30% saving, and it is the cheapest
Pi 5 at $50. And **tuning is worth 0.3–0.8 W** on any Pi 5: HDMI off, LEDs off,
`arm_freq_min=600`, `power_force_3v3_pwm=1`.

| Board · SoC | Cost | RAM | Idle stock | Idle tuned | Burst | NPU | CPU |
|---|---:|---|---:|---:|---:|---:|---|
| **Pi Zero 2 W · BCM2710A1** | $15 | 512 MB | 0.6 W | 0.45 W | 2.5 W | — | 4x A53 @ 1.0 GHz. Cores can be parked to halve idle again |
| M5Stack LLM630 · AX630C | $99.90 | 4 GB (~2 usable) | 0.5 W† | — | 1.5 W† | 3.2 TOPS | 2x A53 @ 1.2 GHz, 12 nm, onboard mic, 32 GB eMMC. **†Vendor claims, never independently measured** |
| Radxa Zero 3W · RK3566 | $42 | ≤8 GB | 1.2–2.3 W | ~1.2 W | 4.0 W | 0.8 TOPS | 4x A55 @ 1.6 GHz |
| **ROCK 4D · RK3576** | $79.90 | 6 GB | 1.5–2.7 W | ~1.5 W | 6.0 W | 6 TOPS | 4x A72 + 4x A53. Same NPU as RK3588 in lower power envelope |
| Orange Pi Zero 2W · H618 | $18–30 | 1–4 GB | ~1.1 W | ~1.0 W | 2.4 W | — | 4x A53 @ 1.5 GHz |
| Pi 5 2GB · BCM2712 D0 | $50 | 2 GB | 2.4 W | ~1.8 W | 7.0 W | — | 4x A76 @ 2.4 GHz. Fastest CPU, most expensive to use |
| Orange Pi 5 · RK3588S | $70 | ≤32 GB | 2.5 W | ~2.2 W | 8.0 W | 6 TOPS | Most mature RKNN tooling. Idle is the problem |
| Pi 5 8GB · BCM2712 C1 | $80 | 8 GB | 3.3 W | ~2.6 W | 9.0 W | — | Strictly worse than the 2 GB |
| Milk-V Duo 256M · SG2002 | $8 | 256 MB | — | — | — | 1 TOPS | **Disqualified** — cvimodel cannot do dynamic shapes |

---

## 8. What runs on what

Estimated RTF, CPU unless the column says NPU. A dash means it does not fit in RAM.
Below **0.15x** cannot keep up with a 15%-busy channel; below **0.45x** it keeps up with
less than 3x headroom.

| Model | Zero 2 W 512 MB | OPi Zero 2W 4 GB | Radxa Z3W 8 GB | Pi 5 2GB CPU | ROCK 4D NPU | RK3588S NPU |
|---|---:|---:|---:|---:|---:|---:|
| Moonshine tiny | 4x | 8x | 8x | 30x | 20x | 25x |
| Moonshine base | 2x | 4x | 4x | 25x | 12x | 15x |
| sherpa Zipformer | 1.5x | 3x | 3x | 12x | 8x | 10x |
| `tiny.en` q5 | 0.8x | 1.6x | 1.6x | 4x | 18x | 30x |
| `base.en` q5 | 0.4x | 0.8x | 0.8x | 2x | 10x | 15x |
| distil-small.en | 0.5x? | 1.0x | 1.0x | 2.5x | 7x | 9x |
| `small.en` q5 | — | 0.3x | 0.3x | 0.5x | 6x | 8x |
| distil-large-v3 | — | 0.1x | 0.1x | 0.35x | 3x | 4x |
| **`large-v3-turbo`** | — | 0.06x | 0.06x | 0.2x | **2x** | **2x** |
| `large-v3` q5 | — | 0.03x | 0.03x | 0.1x | 0.8x | 1x |

**The Radxa Zero 3W mismatch.** It carries up to 8 GB behind four A55 cores at 1.6 GHz.
Every model that would use that RAM runs at 0.1x or worse. The 1 GB version does the same
job for less.

**The Pi 5 inversion.** Its A76 cores are three to five times faster than anything else
here, making it the best CPU-only choice by a wide margin — and its 7 W burst makes every
one of those seconds expensive. Right board for mains, wrong one for a go-bag.

---

## 9. Non-gated builds, ranked

| Board + model | Cost | RTF | J/s audio | Avg W | Runtime | Calls | Verdict |
|---|---:|---:|---:|---:|---:|---:|---|
| **AX630C + SenseVoiceSmall (NPU)** | $100 | 11.4x | 0.13 | 0.61–1.6 | 14–37 h | 88%? | Onboard mic, upstream sherpa-onnx, published RTF. Two unknowns: real power, English accuracy |
| **Pi Zero 2 W + Moonshine base** | $15 | 2x | 1.25 | 0.70 | 32.4 h | 82% | Four times target on the cheapest board made — confirm ONNX fits 512 MB |
| AX630C + `whisper base` (NPU) | $100 | 2.9x | 0.52 | 0.65–1.65 | 14–35 h | 80% | Known-good fallback. RTF published for AX630C specifically |
| Pi Zero 2 W + distil-small.en | $15 | 0.5x | 5.0 | 1.17 | 19.5 h | 88% | Six accuracy points free — 512 MB genuinely doubtful |
| **Pi Zero 2 W + whisper.cpp `base.en`** | $15 | 0.4x | 6.25 | 1.32 | 17.3 h | 80% | The one Zero build with no memory risk at all |
| Orange Pi 3B + Zipformer (NPU) | $35 | ~5x | 0.8 | 1.38 | 16.5 h | 80% | Cheapest chip with ASR demonstrated. Open bugs — budget debugging time |
| OPi Zero 2W 1 GB + distil-small.en | $18 | 0.42x | 5.7 | 1.60 | 14.2 h | 88% | Same model and runtime, no 512 MB gamble. Worse software support |
| **ROCK 4D + `large-v3-turbo` (NPU)** | $80 | 2x | 3.0 | 1.94 | 12.0 h | 93% | Highest accuracy that still clears 8 h. RKNN on RK3576 unverified |
| Pi 5 2GB + Moonshine base | $50 | 25x | 0.28 | 1.93 | 11.8 h | 82% | Cheapest transcription per second of audio — all spent on idle |
| Orange Pi 5 + `large-v3-turbo` (NPU) | $70–88 | 2x | 4.0 | 2.74 | 8.3 h | 93% | Same accuracy for more money, barely clears target |
| Pi 5 2GB + `small.en` | $50 | 0.53–0.95x | 14 | 3.46 | 6.6 h | 88% | Misses target, throttles at 80 °C. **RTF measured, not estimated** |
| Pi 5 8GB + `small.en` | $80 | 0.5x | 18 | 4.62 | 4.9 h | 88% | Strictly dominated |

> **Every build that clears eight hours above 85% accuracy has an NPU.** Every CPU-only path
> to that band — Pi 5 with `small.en`, with distil-large-v3, in any RAM configuration —
> lands between 4.9 and 6.6 hours. The accelerator is not a nice-to-have; it is the
> difference between passing and failing.

### Sensitivity: what if the band is busier than 15%?

| Build | @15% | @40% | Change |
|---|---:|---:|---:|
| Pi Zero 2 W + Moonshine base | 32.4 h | 22.5 h | −31% |
| ROCK 4D + turbo (NPU) | 12.0 h | 8.9 h | −26% |
| Pi 5 2GB + Moonshine base | 11.8 h | 11.4 h | **−3%** |
| Pi 5 2GB + `small.en` | 6.6 h | 3.3 h | −50% |
| Pi Zero 2 W + distil-small.en | 19.5 h | 10.4 h | −47% |

High-RTF builds barely notice; low-RTF builds halve. A Pi 5 running Moonshine base is
almost perfectly flat, because at 25x RTF the transcription is nearly free.

---

## 10. Build sheets

### Configuration 0 — Zero as Recorder · $53 · 29 h · 95% · hours latency

The minimum viable build, and it beats every other configuration on accuracy. The box
captures squelch-gated Opus with timestamps and frequency metadata from CAT; the desktop
runs `large-v3` over the day's archive.

- **Best accuracy in the study** — limited by your desktop, not by 512 MB. Run `large-v3`,
  fine-tune, or re-run the whole archive when a better model ships. Nothing else can do
  that last one.
- **Longest runtime per dollar** — no inference on the box; average draw ~0.55 W.
- **No live readout.** This is the entire cost.
- **It upgrades into Configuration A for free.** Same hardware.

### Configuration A — Pi Zero 2 W · $57 · 9.7–12.2 h · 80–88% · 5–30 s

The cheapest configuration that transcribes on the box. Identical hardware to
Configuration 0.

| Item | Qty | Unit | Line | Notes |
|---|---:|---:|---:|---|
| Raspberry Pi Zero 2 W (SC1176) | 1 | 17 | 17 | 512 MB, the only size made |
| 21700 cell, 5000 mAh (Molicel P45B / Samsung 50S/50E) | 1 | 9 | 9 | **Not Amazon** — marketplace cells routinely counterfeit |
| 21700 holder, 1-slot | 1 | 3 | 3 | Will not fit an 18650 holder |
| TP4056 + MT3608 combo | 1 | 4 | 4 | **Set output to 5.05 V with a meter first** |
| microSD 32 GB high endurance | 1 | 10 | 10 | Endurance matters, capacity does not |
| CM108 USB audio dongle | 1 | 6 | 6 | **AC-couple the input** |
| Micro-USB OTG adapter | 1 | 3 | 3 | Easiest thing to forget to order |
| 3.5 mm TRS cable, male-male | 1 | 3 | 3 | Short is better |
| 10 k trimmer + 1 µF film cap (Bourns 3386P-1-103LF) | 1 | 2 | 2 | The attenuator pad |
| **Total** | | | **57** | 9.7 h distil-small.en, 12.2 h `base.en` |

**Software:** DietPi or Raspberry Pi OS Lite, 64-bit, headless. Moonshine base via ONNX
Runtime as default; keep whisper.cpp `ggml-base.en-q5_1.bin` as the accuracy reference.
Gate with a software RMS threshold before the model ever sees audio.

**Tuning for the 0.45 W idle figure:**
```
# /boot/firmware/config.txt
dtoverlay=disable-bt
disable_splash=1
dtparam=act_led_trigger=none
dtparam=act_led_activelow=off

# optional, roughly halves idle again on a 4-core A53
maxcpus=2                     # in cmdline.txt
```

**Gotchas:**
- **No clean shutdown — the build's one real weakness.** TP4056 + MT3608 has no power path
  and no fuel gauge, so when the cell runs down the Pi browns out mid-write and can corrupt
  the card. Mount the data partition `sync`, run a read-only rootfs, or spend $40 on a
  PiSugar.
- **512 MB is the ceiling on everything.** Enable zram, run headless, never swap to SD.
- **Set the audio level once, against noise.** Open squelch on noise and set the trimmer so
  full-scale hiss sits just under clipping. Level against a strong repeater instead and the
  next strong signal clips.

### Configuration A-alt — Orange Pi Zero 2W 1 GB · $70 · 9.9 h · 88%

Same distil-small.en build on a board with twice the RAM. About $13 more, mostly USB-C
adapters, and it buys certainty rather than capability.

**Can you skip the dongle and solder audio straight to the board? Not on this board.** The
Allwinner H616/H618 audio codec is **playback only** — a single stereo or differential-mono
line output, with no analog capture path anywhere in the SoC. There is no line-in pin to
solder to, because the ADC does not exist. That leaves an external I²S ADC on the 40-pin
header, which is a research project in practice: Armbian does not ship
`sun50i-h618-i2s.dtbo` at all, and the community is still failing to get I²S *output*
working.

> **This is the clearest argument in the whole study for the Raspberry Pi over the Orange
> Pi.** On a Pi, I²S capture is solved: the `wm8960-soundcard` overlay is merged into the
> Raspberry Pi kernel, so a WM8960 codec board is one line in `config.txt`. On the H618 the
> equivalent does not exist.

**Two ports is an advantage:** the board has two USB-C ports, one of which supports host
mode, so you can power on one and hang the audio dongle off the other with no OTG splitter.
Only the port *further from the board edge* is a host by default.

**Power it cleanly** by injecting 5 V directly onto the 40-pin header — pins 2 or 4 for
5 V, pin 6 for ground — which saves a cable and leaves both USB-C ports free.

### Configuration A-solder — Pi Zero + I²S Codec · $67–84 · 10–13 h · 88%

If the dangling adapter is what bothers you. Must be a Raspberry Pi for the driver reason
above. **It costs more, not less** — a $6 dongle plus a $3 adapter becomes a $10–25 codec
board. What you buy is mechanical integrity, ~50–70 mW less draw, and a freed USB port.

| Board | ~$ | Driver | Input type | Verdict |
|---|---:|---|---|---|
| **WM8960 Audio HAT** (Waveshare) | 25 | **In-kernel.** `dtoverlay=wm8960-soundcard` and reboot | Wired for its onboard MEMS mics — feed the mic path through the attenuator | Easiest to get working |
| PCM1808 breakout | 10 | Manual — `dtparam=i2s=on` plus your own overlay | **True line level**, ~2 V rms. Electrically correct for a radio tap | Cheaper and better matched, more setup. **Needs MCLK — check before ordering** |

### Configuration B — Radxa ROCK 4D · $166 · 12 h · 93% · 3–15 s

| Item | Qty | ~$ |
|---|---:|---:|
| Radxa ROCK 4D, 6 GB (RK3576, 6 TOPS NPU) | 1 | 80 |
| Molicel INR-18650-M35A | 2 | 16 |
| 1S2P holder + protection board | 1 | 12 |
| 5 V buck-boost, 3 A (TPS63020 / Pololu U3V50F5) | 1 | 18 |
| USB-C charger board (TP4056 USB-C or BQ25895) | 1 | 8 |
| microSD high endurance 128 GB | 1 | 20 |
| USB audio adapter + TRS cable | 1 | 12 |

**Software:** Radxa's Debian image, then `rknn-toolkit2` and the RKNN runtime. Convert
`large-v3-turbo` to RKNN, or start from `whisper-large-v3-turbo-RKNN2` and re-target
RK3576. Keep Moonshine base on the CPU as a fallback path.

**Gotchas:** verify the RKNN path before buying — this is the one build resting on an
unproven port. A 6 W burst in a sealed print needs a heat spreader. Radxa sells through
Arace, Allnet and ameriDroid, not DigiKey. The 16 GB variant is a waste at $100.

### Configuration C — Pi 5, 2 GB · $140 · 11.8 h · 82% · 1–3 s

Not for the battery. The Pi 5 running Moonshine base has the lowest
energy-per-second-of-audio figure in the entire study at **0.3 J/s** — transcription is
essentially free — and its 25x RTF means a busy band barely dents it. All of its 11.8 h is
spent on idle draw. Right board if it will usually sit on mains, wrong one for a go-bag.

```
# /boot/firmware/config.txt
arm_freq_min=600
power_force_3v3_pwm=1
dtparam=act_led_trigger=none
dtparam=pwr_led_trigger=none

# and at runtime, kill the display pipeline
wlr-randr --output HDMI-A-1 --off
```

**Do not buy the 8 GB.** C1 stepping, 0.8 W more idle, $30 more, for RAM you cannot afford
to fill. **Resist running `small.en` on it** — it fits, works, and drops you to 6.6 h.

### Configuration D — M5Stack LLM630 · $160 · 14–37 h · ~88% · <1 s

**Software:** sherpa-onnx built with `-DSHERPA_ONNX_ENABLE_AXERA=ON` via
`build-axera-linux-aarch64.sh`, which names `ax630c` as a target. Axera publishes
pre-converted `.axmodel` files for SenseVoice, Whisper, Zipformer and Silero VAD. The
Axera backend landed in sherpa-onnx v1.12.20 **with CI**.

SenseVoiceSmall is non-autoregressive — no decoder loop — which is exactly why it runs at
11.4x on a 3.2 TOPS part while Whisper needs bucketed prefill graphs to work at all.
Published AX630C figures: SenseVoice non-streaming RTF 0.088 (~224 ms), streaming ~41 ms
latency.

**Gotchas:** the $49.90 Module LLM is end-of-life. 4 GB is really ~2 GB (CMM carve-out for
the NPU). **`large-v3-turbo` is not demonstrated on AX630C** — it needs 2065 MB of CMM and
is only published for the larger AX650N. Small community; the Whisper-on-Axera work rests
substantially on one developer.

---

## 11. Getting audio in without wrecking it

The part most likely to quietly cost you ten points of accuracy.

| Source | Typical level | Handling |
|---|---:|---|
| **Fixed record / line out** | ~0.3–1 V pp | **Use this if the radio has one.** Level is independent of the volume knob |
| Speaker / ext-speaker out | 1–4 V pp | Far too hot for a mic input — needs roughly −30 to −40 dB. 10 k trimmer as variable divider with 1 µF series cap |
| Headphone out | volume-dependent | Transcript quality now follows the volume knob. Fine for a bench test, bad deployed |
| Discriminator tap | ~1 V pp | Unsquelched and unfiltered — you do your own noise gating. Best signal available; requires opening the radio |

Two details easy to get wrong. **Mic inputs on cheap USB adapters supply bias voltage** on
the ring or tip — always AC-couple through a capacitor. And **set the level once, with the
radio squelched open on noise**, so full-scale noise sits near but under clipping.

**Attenuator schematic:** radio audio → C1 (1 µF, blocks radio DC) → 10 k trimmer wired as
a divider to ground → wiper → C2 (1 µF, blocks mic bias) → dongle mic input. Common ground.
C2 is the one people leave out: without it the dongle's mic bias is shunted to ground
through the lower half of the pot.

---

## 12. Should you design a board?

"Design a board" hides three projects with wildly different difficulty.

| Tier | Difficulty | NRE | Time | What you are actually doing |
|---|---|---:|---:|---|
| **1 · Peripheral HAT** — no compute, analog front end + power management | Accessible | $150–250 | 20–40 h | 4-layer, largest part a QFN, no BGA, no high-speed routing. **A competent hobbyist finishes this** |
| 2 · CM0 carrier — reflow a Compute Module 0 | Hard | $400–600 | 60–100 h | 132 castellated pads at 1 mm pitch. **The DDR is on the module** so you never route LPDDR. Rarely worth it |
| 3 · Full custom SoC | Professional | $2k–10k | 6–12 mo | Sub-0.8 mm BGA needs HDI and microvias; LPDDR4 needs length-matched impedance-controlled routing. **Do not** |

> **The cost trap: a bare Compute Module 0 costs twice a complete Pi Zero 2 W.** CM0 is $18
> officially but China-only; through AliExpress and Tindie it lands at **$33–36**. A whole
> Zero 2 W — same RP3A0 silicon, plus PCB, connectors, antenna, regulators and a warranty —
> is **$17**. You cannot beat the Zero on cost with custom hardware.

| Dimension | $57 build | Custom HAT | Worth it? |
|---|---|---|---|
| Idle power | 0.45 W (75.5 mA with HDMI disabled) | ~0.25–0.30 W plausible | ~1.5x runtime |
| Power management | **Genuinely bad.** No power path, no fuel gauge, no clean shutdown | BQ25185-class charger with power path, MAX17048 fuel gauge, low-battery interrupt | **the real win** |
| Audio front end | $6 dongle + hand-wired trimmer. Bias voltage, unknown gain and noise floor | PCM1808 I²S ADC with op-amp buffer, defined gain, anti-alias filter | yes |
| Squelch detection | Software RMS or a jumper to GPIO | TLV3691 nanopower comparator to GPIO interrupt | marginal |
| Reliability | Dongle falls out, jumpers vibrate loose | One board, one connector, fits an enclosure | yes |
| Cost at qty 5 | $57 | ~$67 | a wash |
| Cost at qty 30 | $57 | ~$42 | cheaper |

**Sketch BOM for the peripheral HAT** (65x30 mm, 4-layer, everything 0402 or larger):
PCM1808PWR audio ADC ($3), OPA2377 input buffer ($2), BQ25185 or MCP73871 charger with
power path ($3), TPS63020DSJR buck-boost ($4), MAX17048 fuel gauge ($3), TLV3691 squelch
comparator ($1), passives and connectors ($6), PCB + assembly amortised at qty 5 ($28) —
**~$50 per board at qty 5**. Note JLCPCB economic assembly caps at 30 pieces and extended
parts cost $3 per type in manual loading fees.

> **Recommendation: design the HAT, never the computer.** Let Raspberry Pi sell you a $17
> quad-core board with a warranty, and spend your PCB effort on the parts of the $57 build
> that are actually bad — the power management and the audio input. And **do it after the
> software works, not before.**

---

## 13. Where these numbers are soft

**Nobody has measured power under ASR load on any of these boards.** *Systemic gap.* Not
one source across five research sweeps reports watts drawn while transcribing. Every burst
figure is inferred from general board benchmarks. The single exception found anywhere is an
independent teardown of an AX650N board — 5.4 W idle, 8.6 W under NPU load, of which the
NPU itself drew 2.71 W. **Most of the power is the SoC platform, not the accelerator.**

**The AX630C build rests on vendor-claimed power.** *The deciding unknown.* 0.5 W idle and
1.5 W load come from M5Stack's marketing. Sipeed quote 2.5 W under a VLM load on the same
silicon. If true idle is 1.5 W, this build drops from 37 h to about 14 h.

**SenseVoice is benchmarked in Mandarin.** Its published WER figures come from AIShell. It
is multilingual and English is supported, but English performance on noisy narrowband radio
audio is *unverified* — placed at `small`-class on parameter count and architecture, not
evidence.

**The CMM carve-out follows you across every NPU board.** Axera reserves memory outside the
OS allocation: 4 GB becomes ~2 GB usable on AX630C, 8 GB becomes 4 GB on AX650N. Size
models against the *usable* half.

**ONNX Runtime's memory arena costs ~2x the model size.** A documented case shows a 230 MB
int8 model consuming 450 MB+. This threatens both Moonshine base and distil-small.en on the
512 MB Zero.

**RKNN Whisper on RK3576 is unproven.** *Check first.* The 30x and 2x NPU figures come from
RK3588 ports. RK3576 shares the 6 TOPS NPU and rknn-toolkit2 chain, so the port should carry
over, but RK3588 has wider memory bandwidth. The turbo port's own author now recommends
Qwen3-ASR-RKNN2 instead.

**distil-small.en on 512 MB is a guess.** 166M parameters quantizes to ~120 MB with a
~300 MB working set, against maybe 350–420 MB usable on a headless Zero 2 W.

**Two traps that will contaminate your own research.** *Moonshine Micro is not
open-vocabulary ASR* despite coverage framing it as "voice AI on a $0.80 microcontroller" —
its source tree shows a SpellingCNN with a custom-word training flow, i.e. fixed commands.
And *one widely-cited blog claims Moonshine Base is faster than Tiny on a Pi 5*; that is
backwards and self-inconsistent.

**sherpa-onnx's embedded claims are oversold below 512 MB.** Its 32-bit ARM embedded page
carries build instructions and *no* performance or RAM figures at all. The one traceable
RV1106 port attempt failed with an unresolved build error, and the RISC-V page's only RTF
figure is from QEMU emulation.

**RTF figures are scaled, not benchmarked.** Derived from published Pi 5 and RK3588 results
by core architecture and clock. Treat them as ordering, not measurement.

**Whisper hallucinates on squelch tails.** Still the most common practical failure. Fed
noise or near-silence, Whisper confidently emits invented text — and on radio audio that
happens at every squelch open and close. VAD before the model, a `no_speech_prob` ceiling
after it, plus minimum-duration and repetition filters.

---

## 14. The accuracy layer beats the hardware layer

The gap between the two accuracy columns throughout this study is the offline lexicon pass,
and it is larger than the gap between a $15 board and a $70 one. `large-v3-turbo` raw gets
about 55% of callsigns; Moonshine base plus a lexicon gets 82%.

### Offline lexicons to ship

- **FCC ULS amateur weekly dump** — roughly 1.1M active US callsigns with class and region.
  Free, definitive, no network.
- **POTA park list** — every `K-nnnn` reference, for tagging activations.
- **The WWARA repeater list and SDS150 favorites** — already in this repo, and they give
  frequency → expected-callsign priors worth more than a model size.
- **Band plan tables** — so 14.150 resolves to 20 m SSB phone and constrains the phraseology
  you expect.

QRZ lookups need an XML subscription (~$36/yr) and a network. Do those on the phone, never
on the box.

### The phonetic-expansion decode pass

Hams spell callsigns, and the phonetic alphabet is a small closed vocabulary that ASR
handles well and that maps deterministically onto characters.

```
"kilo seven alpha bravo charlie"  -> K7ABC
"kaycee seven ay bee cee"         -> K7ABC   (letter-name variant)
"kilo seven able baker charlie"   -> K7ABC   (legacy phonetics)
```

Expand phonetic and letter-name tokens, emit candidates, fuzzy-match against the ULS index
with an edit distance weighted by known ASR confusion pairs (B/D/E/P/V/T all collapse under
noise). Store the raw hypothesis beside the resolved call so you can audit it later.

### Frequency tagging — three options, best first

1. **CAT / serial from the radio.** The TH-D75A has a CAT interface and Uniden's SDS remote
   protocol reports the active channel. Ground truth per transmission, no ASR involved.
   Verify against your firmware before committing a connector.
2. **Voice annotation.** "Tuning to fourteen one five zero" transcribed and regex-matched.
3. **Manual, in the phone app.** Always build this — it is the correction path for the other
   two.

### Appendix: the gated architecture, for reference

Set aside — a second $6 cell buys the same margin as a second processor. Recorded so the
option is not lost. An ESP32-S3 owns the power rail, buffers ADPCM audio in PSRAM (~17 min
of speech, ~2 h of wall clock at 15% busy), and wakes the SoC in bursts. Every platform then
converges to **0.22–0.33 W average and 78–118 h**, because faster boards finish their
backlog sooner. The costs are two toolchains, power sequencing, and SD-card arbitration
between two masters. Revisit only if you later want weeks of standby.

---

## 15. Original recommendation

**Build the $53 recorder. Do not put a model on the box.**

The original brief was asynchronous — capture what comes over the air, summarise it later,
read a digest on your phone. Live transcription was never the requirement.

The recorder wins on every axis at once: **highest accuracy in the study** (95%, because
your desktop runs `large-v3` unconstrained), **cheapest** ($53 against $166 for on-box 93%),
**longest runtime** (20 h on one cell, 40 h on two), **simplest** (no NPU toolchain, no RKNN
port to prove, no 512 MB gamble), and **re-runnable** — when a better model ships you
re-transcribe the entire archive.

### The order of work

1. **Record 2–3 hours off your own radios** with a $6 USB audio adapter and your laptop.
   Nothing in this study substitutes for that tape.
2. **Benchmark on a laptop** — Moonshine base, `base.en`, distil-small.en, SenseVoiceSmall
   and `large-v3-turbo` on your tape. Measure overall WER *and* callsign extraction
   separately; they will disagree.
3. **Write the lexicon resolver** against those transcripts. Biggest accuracy gain
   available, zero hardware.
4. **Settle the memory question** if leaning cheap: `free -m` under sustained load.
5. **Then buy one board.**
6. **Put a meter on it** and publish the number.

> **Do not design a board until the software has run for a month.**

---

## Sources

whisper.cpp and models: [model sizes and
quantizations](https://github.com/ggml-org/whisper.cpp/blob/master/models/README.md) ·
[Distil-Whisper](https://github.com/huggingface/distil-whisper) ·
[distil-large-v3 GGML](https://huggingface.co/distil-whisper/distil-large-v3-ggml) ·
[sherpa-onnx Whisper/distil ONNX
exporter](https://github.com/k2-fsa/sherpa-onnx/blob/master/scripts/whisper/export-onnx.py) ·
[Distil-Whisper paper](https://arxiv.org/pdf/2311.00430) ·
[Moonshine](https://arxiv.org/pdf/2410.15608) ·
[useful-transformers (RK3588 NPU
Whisper)](https://github.com/moonshine-ai/useful-transformers) ·
[whisper-large-v3-turbo-RKNN2](https://huggingface.co/happyme531/whisper-large-v3-turbo-RKNN2)

Power and boards: [2 GB Pi 5: 33% smaller die, 30% idle saving
(Geerling)](https://www.jeffgeerling.com/blog/2024/new-2gb-pi-5-has-33-smaller-die-30-idle-power-savings/) ·
[Pi 5 2 GB vs 8 GB
(CNX)](https://www.cnx-software.com/2024/08/27/comparison-of-raspberry-pi-5-with-2gb-and-8gb-ram-hardware-benchmarks-and-power-consumption/) ·
[Raspberry Pi power consumption 2026, all
models](https://raspberry.tips/en/raspberrypi-tutorials/raspberry-pi-power-consumption-update-2026-all-models-compared) ·
[Reducing Pi 5 standby
power](https://www.tomshardware.com/raspberry-pi/how-to-reduce-raspberry-pi-5-4-standby-power-consumption) ·
[Pi Zero 2 W power consumption
(CNX)](https://www.cnx-software.com/2021/12/09/raspberry-pi-zero-2-w-power-consumption/) ·
[Disabling cores to halve Zero 2 W
power](https://www.jeffgeerling.com/blog/2021/disabling-cores-reduce-pi-zero-2-ws-power-consumption-half/) ·
[Orange Pi Zero 2W independent power
measurements](https://plati.ma/orange-pi-zero-2w/) ·
[ROCK 4D power
measurements](https://www.linuxlinks.com/radxa-rock-4d-single-board-computer-running-linux-power-consumption/) ·
[Tuning the Zero 2 W for minimum
power](https://www.lo-tech.co.uk/wiki/Tuning_the_RaspberryPi_Zero2W_for_Minimum_Power_Consumption) ·
[Why the Zero 2 W is 512 MB
only](https://picockpit.com/raspberry-pi/everything-about-raspberry-pi-zero-2-w/)

NPU silicon: [rknn-toolkit2 changelog — RV1103/RV1106 operator
gaps](https://github.com/airockchip/rknn-toolkit2/blob/master/CHANGELOG.md) ·
[rknn_model_zoo Whisper — supported chip
list](https://raw.githubusercontent.com/airockchip/rknn_model_zoo/main/examples/whisper/README.md) ·
[TPU-MLIR CV18xx guide — "does not support dynamic
shape"](https://tpumlir.org/quick_start_en/Appx.02_cv18xx_guide.html) ·
[Arm Ethos-U architecture — transformers land in
U85](https://developer.arm.com/documentation/109267/0103/Arm-Ethos-U-NPU/Ethos-U-hardware-architecture) ·
[NXP Speech-to-Text — Whisper/Moonshine on
CPU](https://www.nxp.com/design/design-center/software/embedded-software/speech-to-text:STT) ·
[Synaptics SL2610 — Moonshine V2 on the Torq
NPU](https://developer.synaptics.com/blog/realtime-asr) ·
[Sipeed ASR support
matrix](https://wiki.sipeed.com/maixpy/doc/en/audio/recognize.html) ·
[AX650N teardown — the only measured NPU load power
found](http://jas-hacks.blogspot.com/2024/09/ax650n-sipeed-maix-iv-axerapi-pro-npu.html) ·
[AXERA-TECH Whisper axmodels + RTF
tables](https://huggingface.co/AXERA-TECH/Whisper) ·
[AXERA-TECH SenseVoice](https://huggingface.co/AXERA-TECH/SenseVoice) ·
[Pulsar2 KV-cache and bucketed
prefill](https://pulsar2-docs.readthedocs.io/en/latest/appendix/build_llm.html) ·
[Pulsar2 static-shape
requirement](https://pulsar2-docs.readthedocs.io/en/latest/user_guides_advanced/advanced_build_guides.html) ·
[sherpa-onnx Axera build
script](https://github.com/k2-fsa/sherpa-onnx/blob/master/build-axera-linux-aarch64.sh) ·
[whisper.axera](https://github.com/ml-inory/whisper.axera) ·
[SenseVoiceSmall on RK3588
NPU](https://huggingface.co/ThomasTheMaker/SenseVoiceSmall-RKNN2) ·
[sherpa-onnx #3032 — ONNX Runtime memory arena
overhead](https://github.com/k2-fsa/sherpa-onnx/issues/3032)

ASR accuracy: [Whisper-ATC: Open Models for ATC ASR (TU
Delft)](https://pure.tudelft.nl/ws/portalfiles/portal/218298256/ICRAT2024_paper_83.pdf) ·
[Fine-Tuning Whisper for American English
ATC](https://www.researchsquare.com/article/rs-8970162/v1) ·
[Improving Rare-Word Recognition of Whisper
Zero-Shot](https://arxiv.org/pdf/2502.11572) ·
[Measured Whisper RTF on Raspberry Pi 5
(peer-reviewed)](https://arxiv.org/html/2507.14451v1) ·
[Whisper on a Pi Zero 2 W — the $15
floor](https://gist.github.com/Gilzone/f558a6779f742f30cfcb9c83b912a8ff) ·
[Moonshine Micro — fixed-vocabulary, not
ASR](https://github.com/moonshine-ai/moonshine/tree/main/micro)

Audio and hardware: [ASoC: Allwinner H616 codec — playback
only](https://lwn.net/Articles/992167/) ·
[linux-sunxi H616/H618 hardware notes](https://linux-sunxi.org/H616) ·
[I²S not working on Orange Pi Zero
2W](https://www.diyaudio.com/community/threads/i2s-audio-not-working-on-orange-pi-zero-2w-allwinner-h618-with-max98357a.424744/) ·
[WM8960 Audio HAT — in-kernel
overlay](https://www.waveshare.com/wiki/WM8960_Audio_HAT) ·
[HOWTO: PCM1808 I²S ADC on a Raspberry
Pi](https://hardtux.tumblr.com/post/644047221052080128/howto-pcm1808-i2s-adc-on-a-raspberry-pi) ·
[Armbian device-tree overlays](https://docs.armbian.com/User-Guide_Armbian_overlays/) ·
[Attenuator pad design
reference](https://www.electronics-tutorials.ws/attenuators/pi-pad-attenuator.html)

Sourcing: [PiShop US](https://www.pishop.us/product/raspberry-pi-zero-2-w/) ·
[Radxa ROCK 4D](https://radxa.com/products/rock4/4d/) ·
[Radxa authorised distributors](https://wiki.radxa.com/Distributors) ·
[M5Stack LLM630 Compute
Kit](https://shop.m5stack.com/products/m5stack-llm630-compute-kit-ax630c) ·
[Molicel M35A, authorised
distributor](https://liionwholesale.com/products/molicel-npe-inr-18650-m35a-10a-3500mah-flat-top-18650-battery-authorized-distributor) ·
[Luckfox Core3576 — RK3576 SoM](https://www.luckfox.com/EN-Core3576) ·
[Raspberry Pi CM0
pricing](https://www.cnx-software.com/2026/04/27/raspberry-pi-cm0-system-on-module-is-now-sold-for-33-and-up-on-aliexpress/) ·
[JLCPCB assembly cost breakdown](https://jlcpcb.com/blog/pcba-cost-breakdown) ·
[QRZ XML Callbook Data Service](https://www.qrz.com/page/xml_data.html)
