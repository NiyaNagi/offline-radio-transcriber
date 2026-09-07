# What a Flagship Budget Buys

**Research question: if the design ceiling is no longer capped by the weakest device, how
much better does this get? · September 2026**

Short answer: **materially, and by more than the entire hardware ladder in the SBC study.**
The single largest gain has nothing to do with the phone at all.

---

## 1. The levers, ranked by measured gain

| # | Lever | Measured effect | Evidence quality | Cost |
|---|---|---|---|---|
| **L1** | **Domain fine-tuning (LoRA)** | ATC: **55.2% → 6.8% WER**. distil-Whisper + LoRA: **3.86% WER** on the 70 h ATC corpus. **13.7% WER from 55 hand-transcribed clips** | Strong, multiple independent papers | One-time offline training. Ships as a model file |
| **L2** | **`large-v3-turbo` on the NPU** | ~88% → ~93% callsign band, at **~22x real time** on Snapdragon 8 Elite Gen 5 | Strong — vendor-published per-device benchmarks | Per-SoC AOT compilation, vendor toolchain |
| **L3** | **LLM n-best rescoring** | **5–25% relative WER reduction** | Moderate — published, not on radio audio | LLM resident, rescore per segment |
| **L4** | **Multi-model ensemble (ROVER)** | Gain scales with model *diversity*, not count | Moderate — classical, well understood | 2x inference |
| **L5** | **Speech enhancement front-end** | CHiME-4: >30% relative WER reduction. **But see §5 — it can make things worse** | **Contested** | Cheap (48.2 K params) |
| **L6** | **Larger active biasing lexicon** | Unmeasured | Weak | Cheap |

---

## 2. L1 — Fine-tuning is the biggest lever in the project

This is the finding that reframes everything.

| Study | Baseline | Fine-tuned | Data used |
|---|---:|---:|---|
| Domain adaptation of Whisper for ATC (AIAA SciTech 2026) | 55.2% | **6.8%** | ATC benchmark + FAA corpus |
| distil-Whisper + LoRA for aviation ([arXiv 2503.22692](https://arxiv.org/abs/2503.22692)) | not stated | **3.86%** (5-fold avg) | ~70 h LDC ATC Corpus. LoRA alpha 64, rank 32 |
| Data-efficient pipeline for US English ATC ([Research Square](https://www.researchsquare.com/article/rs-8970162/v1)) | European-trained baseline | **13.7%** (−54.8% relative) | **55 manually transcribed clips** from three airports |

**Read the third row again.** Fifty-five clips produced a 54.8% relative WER reduction.

The consequence for this project is direct: **the M0 evaluation set is also the fine-tuning
set.** The work of recording and hand-labelling real off-air traffic — already the first
milestone, already unavoidable, already needing no code — turns out to also unlock the
largest accuracy gain available. Two deliverables from one afternoon of labelling.

And critically: **a fine-tuned model is a file.** Fine-tuning happens once, on a desktop or
a rented GPU. The output is a model that runs at the same speed as the one it replaced, on
every tier. **L1 is not a flagship-only lever** — it is the one improvement that lifts the
floor and the ceiling simultaneously.

Amateur radio is an unusually good fine-tuning target for the same reasons ATC is: rigid
phraseology, a closed procedural vocabulary (`QSL`, `QSY`, `73`, `clear`, `monitoring`,
`standing by`), the phonetic alphabet, and a consistent channel character.

**Caveat.** No one has fine-tuned on amateur radio audio and published it. The ATC numbers
are the closest analogue and are the basis for expecting this to work. Also unverified:
whether a fine-tuned checkpoint exports cleanly to the ONNX form sherpa-onnx consumes
(carried forward from `research/03` §8, T5).

---

## 3. L2 — The NPU verdict reverses

`research/02` §4 concluded the NPU was reachable and irrelevant, because throughput already
had 16x surplus. **That conclusion was correct for the must-run-on-anything framing and is
wrong for a flagship-first one.** The surplus argument says "you don't need more speed." It
does not say "you can't spend speed on a better model" — and that is exactly the trade
available.

[Qualcomm AI Hub publishes per-device benchmarks for
`Whisper-Large-V3-Turbo`](https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo), across
40+ chipset/runtime combinations, all executing on the **NPU**:

| Device | Encoder (30 s window) | Decoder / token | Peak memory |
|---|---:|---:|---:|
| Snapdragon 8 Elite Gen 5 | 267–278 ms | ~6.3 ms | enc ≤72 MB, dec 22–32 MB |
| Snapdragon 8 Gen 3 | — | ~8 ms | 42–55 MB |
| Snapdragon X2 Elite | 269–273 ms | ~5–5.3 ms | 33 MB |
| Qualcomm SA8295P | — | ~11.7 ms | 25–30 MB |

The model is a pruned `large-v3` with decoder layers cut 32 → 4, with Multi-Head Attention
replaced by Single-Head Attention and linear layers replaced by convolutions for edge
inference.

### Working the number for this workload

A 10-second over, roughly 30 decoded tokens, on a Snapdragon 8 Elite Gen 5:

```
encoder  = 0.272 s   (fixed 30 s window)
decoder  = 30 tokens x 6.3 ms = 0.189 s
total   ~= 0.46 s for 10 s of audio

RTF (speed convention) ~= 22x
```

**`large-v3-turbo` on a flagship NPU runs roughly nine times faster than Whisper small does
on a 2019 phone CPU, at five percentage points higher callsign accuracy.** That is the whole
argument for the reframing in one line.

Note also the memory figures: tens of megabytes of activation memory, because the NPU
runtime streams weights rather than holding the graph resident the way a CPU runtime does.
The `~1.0 GB runtime RAM` figure for turbo in the SBC study's model catalogue is a *CPU*
figure and does not transfer.

### The costs, stated plainly

- **Per-SoC ahead-of-time compilation.** A QNN context binary is built for a chipset family.
  Supporting Qualcomm, Google Tensor and MediaTek means three toolchains or acceptance of
  LiteRT's abstraction over them.
- **Vendor lock at the top tier.** A phone change in two years is a porting task.
- **A CPU fallback path must exist anyway** for every device without a supported NPU, which
  is most of them.

This is affordable *precisely because* the CPU path is already required and already good
enough. The NPU becomes an accelerator that unlocks a bigger model, not a dependency.

---

## 4. L3 and L4 — Rescoring and ensembles

Both exploit the same structural fact: **ASR errors are not random, and different systems
make different mistakes.**

### LLM n-best rescoring

[ProGRes](https://arxiv.org/html/2409.00217v1) extends the ASR n-best list with LLM-generated
hypotheses, then reranks the extended set using both linguistic scores (from an open-weight
LLM) and acoustic scores (from the ASR). Reported **relative WER improvements of 5–25%**,
with larger gains where the ASR hypotheses are only slightly wrong — which is exactly the
regime this product operates in.

**Why this is safe here despite D5 (no LLM in the callsign path).** Rescoring is *selection
among existing hypotheses*, not generation. Constrained to reranking a fixed candidate set
with acoustic scores in the objective, the LLM cannot introduce a callsign that was never
hypothesised. That is a categorically different risk profile from asking an LLM to read a
transcript and report who was talking, and the spec must encode the distinction rather than
banning the technique.

### Multi-model ensemble

[ROVER](https://www.researchgate.net/figure/The-combination-of-multiple-systems-for-speech-recognition-using-ROVER_fig1_281774772)
does N-way dynamic-programming alignment across system outputs and votes with confidence.
The literature is explicit that **diversity, not count, drives the gain** — the WER of the
combined output approximately decomposes into the average individual WER minus the average
disagreement with the combined output.

For this product that means the right ensemble is **architecturally diverse**, not two sizes
of the same model:

- an encoder-decoder (Whisper / distil-Whisper),
- a transducer (Zipformer) — which the design already runs as Pass A for streaming,
- optionally a non-autoregressive model (SenseVoice).

The pleasing consequence: **Pass A already produces a second, architecturally different
hypothesis for free.** The design currently discards it once Pass B finishes. On a flagship
it should be fused instead.

---

## 5. L5 — Speech enhancement, and why it is not a free win

The obvious move is a denoiser in front of the ASR. sherpa-onnx ships
[GTCRN](https://github.com/Xiaobin-Rong/gtcrn) — 48.2 K parameters, 33.0 MMACs/s, beating
RNNoise and competitive with far heavier baselines — plus DPDFNet, at trivial cost. And the
enhancement literature is encouraging: a single-channel time-domain denoiser produced **over
30% relative WER reduction on CHiME-4**.

**But the counter-evidence is direct and recent.** ["When Denoising Hinders: Revisiting
Zero-Shot ASR with SAM-Audio and Whisper"](https://arxiv.org/pdf/2603.04710) finds that
separation/enhancement preprocessing can **degrade** zero-shot Whisper rather than improve
it. The mechanism is plausible: Whisper was trained on vast quantities of noisy real-world
audio, so it is already robust to noise, while enhancement introduces artifacts outside its
training distribution and can strip acoustic cues the model relies on.

**Recommendation: implement it, default it off, and let the evaluation harness decide.**
This is a per-profile, measurable switch — not an architectural commitment. It is also
plausible that the answer differs between a strong local repeater and a weak HF DX signal,
which argues for making it a profile setting rather than a global one.

**A separate and stronger argument for enhancement:** it may help the *speaker embedding*
and *phonetic spotting* passes even where it hurts Whisper, because those models were not
trained on noisy web audio. The design should therefore allow enhancement to be applied
**per pass**, not globally — a distinction that costs nothing to build in now and is
expensive to retrofit.

---

## 6. What this means for the tier model

The original framing had one baseline and degraded from it. Inverting it produces a
different and better structure, with one genuinely new property.

### Tier is a property of processing, not of the record

Because every pass is a pure function of retained audio (`research/03`, and the spec's
Principle 1), **a record captured on a weak device can be reprocessed on a strong one
later.** Capture on a $60 phone in the field; reprocess at home on the flagship; same app,
same database, same records, better answers.

That reconciles the two halves of the requirement completely. A low tier is not a
permanently degraded record — it is a **provisional** one. And it gives the reprocessing
extensibility point (already required by D2) a second, immediate, on-device payoff that has
nothing to do with a desktop.

### Expected accuracy by tier

Bands, extrapolated from the SBC study's model tiers plus the levers above. **All of these
are provisional until the evaluation set exists** — no published WER figure for any of these
models on off-air amateur radio audio exists.

| Tier | ASR | Callsign band | Overall WER band | Levers active |
|---|---|---:|---:|---|
| Reference (flagship, NPU) | fine-tuned `large-v3-turbo` | **95%+** | 10–20% | L1 L2 L3 L4 L5 L6 |
| Full (recent mid-tier, CPU) | fine-tuned distil-small.en | 90–93% | 15–25% | L1 L4 L5 L6 |
| Standard | distil-small.en or Whisper small | 88% | 20–30% | L6 |
| Minimal | Moonshine tiny | 72–80% | 30–45% | — |

Note that L1 (fine-tuning) is available at **every** tier. It is the reason the Full tier
band above is higher than the SBC study's ceiling for equivalent models.

---

## 7. Honest accounting of what is unproven

| Claim | Status |
|---|---|
| Fine-tuning transfers from ATC to amateur radio | **Plausible, unproven.** Strong structural analogy — rigid phraseology, closed vocabulary, noisy narrowband channel |
| 22x RTF for turbo on Snapdragon 8 Elite Gen 5 | **Computed from vendor-published component latencies**, not measured end-to-end in an app. Excludes app overhead and thermal effects |
| +5pp callsign from turbo over small | Carried from the SBC study's estimated bands, which were themselves extrapolated from ATC literature |
| ProGRes 5–25% transfers to radio audio | Unverified. Published on conversational speech |
| ROVER gain magnitude here | Unquantified. Directionally reliable; magnitude depends on model diversity on *this* audio |
| Enhancement helps or hurts | **Genuinely contested.** Must be measured per profile and per pass |
| Whether a LoRA fine-tune exports to sherpa-onnx ONNX | Unverified. Gates L1 entirely on the sherpa-onnx path |

The last row is the one to check first. **If a fine-tuned checkpoint cannot be exported into
the runtime, L1 — the largest lever — is unavailable, and the runtime choice itself comes
back into question.**

---

## Sources

[Efficient Domain Adaptation of Whisper for ATC (AIAA SciTech
2026)](https://arc.aiaa.org/doi/10.2514/6.2026-1765) ·
[Enhancing Aviation Communication Transcription: Fine-Tuning Distil-Whisper with
LoRA](https://arxiv.org/abs/2503.22692) ·
[Fine-Tuning Whisper for American English ATC: A Data-Efficient
Pipeline](https://www.researchsquare.com/article/rs-8970162/v1) ·
[Adapting ASR for Accented Air Traffic Control](https://arxiv.org/pdf/2502.20311) ·
[fine-tuning-whisper-on-atc-data](https://github.com/jack-tol/fine-tuning-whisper-on-atc-data) ·
[Qualcomm Whisper-Large-V3-Turbo device
benchmarks](https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo) ·
[Qualcomm AI Hub — Whisper-Large-V3-Turbo-Quantized](https://aihub.qualcomm.com/compute/models/whisper_large_v3_turbo_quantized) ·
[Unlocking Peak Performance on Qualcomm NPU with
LiteRT](https://developers.googleblog.com/unlocking-peak-performance-on-qualcomm-npu-with-litert/) ·
[ProGRes: Prompted Generative Rescoring on ASR
N-Best](https://arxiv.org/html/2409.00217v1) ·
[ASR Error Correction using Large Language Models](https://arxiv.org/html/2409.09554v2) ·
[Theoretical Analysis of Diversity in an Ensemble of ASR
Systems](https://ieeexplore.ieee.org/document/6727393/) ·
[GTCRN — ultra-lightweight speech
enhancement](https://github.com/Xiaobin-Rong/gtcrn) ·
[sherpa-onnx speech enhancement
models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speech-enhancement-models) ·
[sherpa-onnx DPDFNet](https://k2-fsa.github.io/sherpa/onnx/speech-enhancement/dpdfnet.html) ·
[When Denoising Hinders: Revisiting Zero-Shot ASR with SAM-Audio and
Whisper](https://arxiv.org/pdf/2603.04710)
