# Accuracy, Lexicon and Identity

**Research supporting the core accuracy architecture · September 2026**

This is the research that decides whether the product is good. The hardware and platform
studies establish that transcription is affordable; this one establishes how to make it
*correct* for the thing that actually matters — callsigns, frequencies, and who said what.

---

## 1. The two accuracy numbers, restated

From the hardware study, and unchanged by any platform decision:

- **Overall WER** is set by the model and tops out around 86% on weak off-air audio.
- **Callsign / frequency extraction** is set by the lexicon layer and moves from ~40% to
  90%+ regardless of model.

Anchors come from air-traffic-control ASR, the closest published analogue — noisy off-air
voice, rigid phraseology, heavy rare-word content:

| Condition | WER | Source |
|---|---:|---|
| Fine-tuned Whisper, real off-air VHF (ATCO2) | 13.5–16.7% | Whisper-ATC (TU Delft) |
| Fine-tuned Whisper, clean studio ATC (ATCOSIM) | 1.2–3.9% | Whisper-ATC (TU Delft) |
| Zero-shot out-of-vocabulary error | ~60% | Rare-word recognition literature |
| Same, with prompt biasing | ~37% | Rare-word recognition literature |

Note that the good numbers are **fine-tuned**. A stock model on a phone starts worse.

---

## 2. Running the lexicon against the audio

The product requirement is explicit: match the lexicon against the *audio*, not the
transcribed text, because that is more accurate. The literature supports this strongly.

### 2.1 CB-Whisper — the strongest published evidence

[CB-Whisper](https://arxiv.org/html/2309.09552v3) inserts an open-vocabulary keyword-spotting
module between the Whisper encoder and decoder:

1. **Entity representation.** Pre-defined entity words are converted to speech by TTS,
   pushed through the Whisper encoder, and stored as acoustic representations.
2. **Keyword detection.** At inference, cosine similarity matrices compare input utterance
   features against stored entity features. A **0.2M-parameter, 4-layer CNN** identifies
   entities by recognising diagonal patterns in those matrices. Uses a weighted sum of
   multi-layer encoder features rather than a single layer.
3. **Prompt construction.** Detected entities become decoder prompts. "Spoken form prompts"
   that mimic conversational transcripts with disfluencies reduce hallucination versus naive
   entity concatenation.

**Measured results, frozen Whisper-large-v2:**

| Metric | Baseline | CB-Whisper |
|---|---:|---:|
| Entity recall, Internal-1 | 87.3% | **97.7%** |
| Entity recall, Internal-2 | 79.9% | **96.9%** |
| MER reduction, Internal-1 | — | 2.9% |
| MER reduction, Internal-2 | — | 3.6% |

On Aishell hot-word subsets, entity recall improved by **up to 80 percentage points**.

This is the single most important finding for this product. Entity recall is exactly the
metric that matters — a callsign is an entity — and the improvement dwarfs anything
available from changing model size.

### 2.2 sherpa-onnx hotwords — decode-time contextual biasing

[sherpa-onnx implements hotwords](https://k2-fsa.github.io/sherpa/onnx/hotwords/index.html)
via an Aho-Corasick automaton built from tokenized hotwords.

**Mechanism.** The automaton carries goto arcs (direct transitions with token and boost
score), failure arcs (fallback when matching fails), and output arcs (matched hotwords).
Critically: *the path is boosted when any partial sequence matches, and if the path finally
fails to fully match any hotword, the boosted score is cancelled.* Boost scores distribute
evenly across arcs along the matching path, applied at token level.

**Hard constraints:**

- **Only transducer models support hotwords.** Whisper and Moonshine are encoder-decoder and
  cannot use this feature. Zipformer transducers can.
- **Decoding must be `modified_beam_search`.** The default `greedy_search` does not support
  hotwords.
- Four required parameters: `hotwords-file`, `hotwords-score`, `modeling-unit`
  (bpe / cjkchar / cjkchar+bpe), `bpe-vocab`.
- Hotwords file is one entry per line with optional per-entry score after a colon.
- **No documented maximum hotword count**, but the automaton is built at inference time and
  its size scales with the tokenized hotword set.

### 2.3 Whisper `initial_prompt` — real but severely limited

Whisper's `initial_prompt` biases decoding by initializing encoder layers with prompt token
embeddings ([arXiv 2410.18363](https://arxiv.org/pdf/2410.18363)). Limits:

- **Maximum 224 tokens**, and only the last 224 are consumed.
- The attention mechanism weights later tokens more heavily, so importance is unequal.
- Effective prompts must be compact, prioritize rare/domain-specific words, and place the
  highest-value tokens near the end.

**You cannot inject a repeater list this way, let alone the ULS dump.** The ~60% → ~37% OOV
improvement in the literature comes from a handful of terms.

### 2.4 sherpa-onnx open-vocabulary keyword spotting

sherpa-onnx ships [keyword
spotting](https://k2-fsa.github.io/sherpa/onnx/kws/index.html) implemented as *a tiny ASR
system that can only decode words/phrases in given keywords*. Open-vocabulary customization
means keywords can be specified without retraining. Pretrained models exist
(`sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20`, a 3M-parameter zh-en transducer).

This is directly usable for running a lexicon against audio, subject to the same
transducer-only constraint.

### 2.5 The synthesis: spot units, not callsigns

Every audio-level biasing mechanism above scales badly with lexicon size. The FCC ULS dump
is ~1.1M active US callsigns, and the product must handle worldwide traffic. An
Aho-Corasick automaton over a million tokenized callsigns is not viable, and CB-Whisper's
TTS-derived acoustic database cannot be built for a million entries either.

**But callsigns are not arbitrary strings — they are spoken as phonetic alphabet
sequences.** The spoken vocabulary is:

- 26 NATO phonetic letters (alpha … zulu), plus common legacy and regional variants
  (able/baker for A/B, "nancy" for N, etc.)
- 10 digits, plus spoken variants ("niner", "fife", "tree")
- A small set of separators and modifiers ("stroke", "portable", "mobile", "slash")

That is roughly **36 core units plus variants** — well under 100. Both hotword biasing and
acoustic KWS are entirely tractable at that scale.

So the architecture is:

```
audio ──> spot the ~36 phonetic units acoustically
      ──> confidence-weighted phonetic lattice
      ──> parse against the callsign GRAMMAR
      ──> rank surviving candidates using priors
      ──> emit ranked candidates with confidence
```

This is "lexicon against audio" done at a scale that works, and it is **unbounded** — it
handles a callsign never seen before, from any country, because it never needed a list.

---

## 3. Callsign structure as a grammar

Because the product must handle worldwide traffic, the resolver cannot be a lookup against a
US-only list. Callsigns have a universal structure defined by ITU allocation.

**General form:** `prefix + separator digit + suffix`, where the prefix is one or two
characters (letter, or letter-digit, or digit-letter) allocated to a country by the ITU,
followed by a digit, followed by one to four letters.

**US amateur pattern:** `[AKNW][A-Z]?[0-9][A-Z]{1,3}`

**Modifiers:** `/P` portable, `/M` mobile, `/MM` maritime mobile, `/AM` aeronautical mobile,
`/QRP`, and reciprocal-operation forms like `K7ABC/VE7` or `VE7/K7ABC`.

The consequence: **the ITU prefix table is the structural filter, and the ULS dump is only a
prior.** A parse that yields a valid structure for an allocated prefix is a legitimate
candidate even with zero database hits. A parse that yields an unallocated prefix is almost
certainly a mis-hearing and should be re-ranked or rejected.

This is what makes worldwide operation work without an unbounded active set.

### Ranking priors, applied after the grammar

None of these may act as a hard filter — they only reorder candidates:

| Prior | Source | Strength |
|---|---|---|
| Frequency / repeater match | CAT-reported frequency → WWARA repeater list, SDS150 favorites | Very strong when available |
| Band plausibility | Band plan tables — is worldwide DX physically plausible on this frequency? | Strong. 2 m FM simplex is local; 20 m SSB is not |
| Database presence | FCC ULS (US), and any other national database bundled | Moderate — absence is weak evidence |
| Recency | Heard before, in this session or historically | Strong after warm-up, useless cold |
| Geographic | Prefix region vs. operator's own location | Weak alone, useful combined with band |
| Conversation context | The other station in this QSO already identified | Strong |

**ASR confusion weighting.** Edit distance against candidates must be weighted by known
acoustic confusion pairs — B/D/E/P/V/T collapse under noise, M/N, S/F. This is worth more
than raw Levenshtein.

---

## 4. Speaker identity and conversation threading

The product requirement is to associate text with a call by reasoning over an entire
conversation, with a feedback mechanism that determines what text belongs to what call.

### 4.1 Available capability

[sherpa-onnx supports speaker
diarization](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/index.html) using
pyannote-3.0 segmentation with a speaker-embedding extractor and fast clustering. It also
supports [speaker
identification](https://k2-fsa.github.io/sherpa/onnx/speaker-identification/apk.html)
separately. Embedding models come from **WeSpeaker** (prefix `wespeaker-`), **3D-Speaker**
(prefix `3dspeaker-`) and NeMo. Android APKs are published for both diarization and
identification.

Note the shape difference: classic diarization segments *one recording* into speakers.
This product needs the inverse — a persistent embedding per transmission, clustered across
a whole session and across sessions. That means using the **embedding extractor directly**
rather than the packaged diarization pipeline.

### 4.2 Why radio makes this unusually tractable

Radio is a far friendlier diarization problem than a meeting:

- **Transmissions never overlap.** One station keys at a time. There is no overlapped-speech
  problem, which is the hardest part of general diarization.
- **VAD boundaries are already transmission boundaries.** Squelch gives near-perfect
  segmentation for free.
- **Amateur operators must identify.** FCC §97.119 requires identification at least every
  10 minutes and at the end of a communication. That is a **guaranteed periodic ground-truth
  anchor** — a hard label arriving on a schedule.
- **Turn-taking is structured.** QSOs alternate; nets have a controlled order.

### 4.3 The back-propagation mechanism

```
For each transmission:
  extract speaker embedding
  assign to nearest existing cluster (cosine similarity above threshold)
      or create a new cluster

When a callsign is CONFIRMED in any transmission:
  bind that callsign to the transmission's cluster
  back-propagate to every other transmission in that cluster
    -> marking them INFERRED, with the cluster confidence as the score

When the user corrects an attribution:
  rebind the cluster
  re-propagate to all members
  optionally split the cluster if the correction implies a mis-merge
```

The 10-minute ID rule means a cluster in an amateur QSO acquires a confirmed label within a
bounded time. That is the "enough context to determine what text is associated with what
call" mechanism the product asks for.

**Caveats that must be designed around:**

- Embeddings are trained on clean speech. Narrowband, noisy, compressed off-air audio will
  degrade separation, and **no published evaluation of speaker embeddings on off-air radio
  was found.** Cluster thresholds must be tunable and confidence must be surfaced, not
  hidden.
- Short transmissions ("roger", "seven three") may be too brief for a reliable embedding.
  A minimum-duration floor is required, below which no clustering is attempted.
- Cluster identity must not persist indefinitely without evidence. Two different operators
  on different days can collide.

---

## 5. Streaming versus offline

| Approach | Latency | Accuracy | Biasing | Notes |
|---|---|---|---|---|
| Streaming Zipformer transducer | 160 ms reported on mobile; 500 ms chunks typical | Lower WER ceiling | **Yes — hotwords supported** | Endpointing supported. Text visibly revises itself |
| Offline Whisper / Moonshine on complete segment | Segment duration + inference | Best | No hotwords | Full context available at decode |

The product wants both: live streaming for feel, complete-transmission accuracy for the
record. This resolves cleanly because they are complementary rather than competing —
the streaming pass provides the biased lexicon-aware hypothesis, and the offline pass
provides the verbatim text.

Note the pleasing consequence: **the pass that can be lexicon-biased is the streaming one,
and the pass that has best verbatim accuracy is the offline one.** Running both means
getting the biasing benefit and the accuracy benefit rather than choosing.

---

## 6. On-device LLM — optional, deferred, never in the callsign path

The product owner confirmed a local LLM is **not required** if offline transcription is
accurate. It remains valuable for the digest.

### Options

| Option | Memory | Notes |
|---|---:|---|
| **Gemma 3n E2B / E4B** | 2 GB / 3 GB | Actual parameter counts 5B and 8B, reduced by Per-Layer Embeddings which keep embeddings on CPU. **Has a USM-based audio encoder** — 1 token per 160 ms of audio, ~6 tokens/sec — so it can transcribe directly |
| Gemma 3 1B, 4-bit | ~1 GB | The MediaPipe LLM Inference API's primary documented example |
| Llama 3.2 1B / 3B, Q4_K_M via llama.cpp | ~1 / ~2 GB | Mature, portable, supports GBNF grammars |

### MediaPipe LLM Inference API constraints

From [the Android
guide](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android):

- Optimized for high-end devices (Pixel 8, Galaxy S23 or later). **Does not reliably support
  emulators.**
- `maxTokens` **defaults to 512** — input plus output combined.
- Audio input supported, but must be **mono `.wav`**.
- LoRA inference is **GPU-only**. No NPU support documented in the guide.
- No published latency or throughput numbers in the documentation.

### Gemma 3n as a single multimodal model — rejected

Attractive in principle: one model doing ASR and reasoning. Rejected because:

- Whisper's architecture and training are optimized for verbatim transcription; Gemma 3n is
  a general multimodal LLM whose audio encoder feeds a language backbone. Reported as
  weaker at verbatim transcription, with poor multilingual speech transcription noted.
- 2–3 GB resident conflicts directly with the "must run on anything" requirement.
- Callsign fidelity on radio audio is entirely unproven, and an LLM backbone is exactly the
  component most likely to *plausibly invent* a callsign.

### Structured output — solved, if an LLM is used

[GBNF grammar-constrained
decoding](https://github.com/ggml-org/llama.cpp/blob/master/grammars/README.md) in
llama.cpp masks invalid tokens at the logit layer, guaranteeing structurally valid JSON with
no retry loop. Reported testing on a Pixel 8 with a 3B quantized model across ~1,000
structured extraction calls: **constrained decoding eliminated runtime failures with ~5–8%
overhead.** A generic `json.gbnf` guarantees valid JSON but not valid *responses* —
schema-specific grammars give both, and should be written permissively for value types to
avoid token-boundary issues under aggressive quantization.

**Design rule regardless:** the LLM never emits a callsign. Callsigns come from the
deterministic resolver, and the LLM is handed already-resolved entities to write prose
about. This makes hallucinated callsigns structurally impossible rather than merely
unlikely.

---

## 7. Hallucination control

Unchanged as the #1 practical failure mode. Whisper fed noise or near-silence confidently
emits invented text, and on radio audio that happens at **every squelch open and close**.

Required mitigations, all of them:

1. **VAD before the model.** Silero VAD (or TEN-VAD) gates every segment. Non-negotiable.
2. **`no_speech_prob` ceiling** after the model. Reject above threshold.
3. **Minimum duration floor.** Discard segments below a configured length.
4. **Repetition detection.** Whisper loops on degenerate input; detect n-gram repetition.
5. **Known-hallucination blocklist.** Whisper emits training-data artifacts such as
   "Thank you for watching", "Subtitles by …", "Please subscribe". These are well documented
   and should be filtered explicitly.
6. **Compression-ratio check.** Degenerate output compresses abnormally well.

A segment failing any check is retained as audio with a `rejected` transcription state — not
deleted — so a later reprocessing pass can revisit it.

---

## 8. Open technical unknowns

| Unknown | Why it matters | How to settle |
|---|---|---|
| Speaker embedding separation on narrowband off-air audio | Determines whether QSO threading works at all | Record 2 h of real traffic, cluster, hand-label, measure |
| Phonetic-unit KWS accuracy on radio audio | The entire lexicon-against-audio thesis rests on it | Same tape; measure per-unit precision/recall |
| Whether CB-Whisper's approach ports to a phone | 0.2M-param CNN is trivially small, but requires encoder hidden-state access | Prototype against sherpa-onnx Whisper encoder outputs |
| Hotword automaton size limits at ~100 units | Should be fine, unverified | Benchmark on target device |
| ONNX export of a fine-tuned Whisper for sherpa-onnx | Gates any future fine-tuning on ATC-style data | Try the exporter on a fine-tune |
| SDS150 / TH-D75A USB serial enumeration under Android | Gates frequency ground truth | Plug it in |
| Real per-transmission latency budget on a mid-tier phone | Determines tier boundaries | Measure once a prototype exists |

---

## Sources

[CB-Whisper: contextual biasing and open-vocabulary keyword
spotting](https://arxiv.org/html/2309.09552v3) ·
[Contextual Biasing without Fine-Tuning Whisper (initial_prompt
limits)](https://arxiv.org/pdf/2410.18363) ·
[Improving Rare-Word Recognition of Whisper in Zero-Shot
Settings](https://arxiv.org/pdf/2502.11572) ·
[sherpa-onnx hotwords / contextual
biasing](https://k2-fsa.github.io/sherpa/onnx/hotwords/index.html) ·
[sherpa-onnx keyword spotting](https://k2-fsa.github.io/sherpa/onnx/kws/index.html) ·
[sherpa-onnx speaker
diarization](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/index.html) ·
[sherpa-onnx speaker
identification](https://k2-fsa.github.io/sherpa/onnx/speaker-identification/apk.html) ·
[sherpa-onnx streaming ASR](https://k2-fsa.github.io/sherpa/python/streaming_asr/) ·
[Unifying Streaming and Non-streaming Zipformer-based
ASR](https://arxiv.org/pdf/2506.14434) ·
[WeSpeaker toolkit](https://arxiv.org/pdf/2210.17016) ·
[Gemma 3n developer
guide](https://developers.googleblog.com/en/introducing-gemma-3n-developer-guide/) ·
[MediaPipe LLM Inference for
Android](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android) ·
[llama.cpp GBNF
grammars](https://github.com/ggml-org/llama.cpp/blob/master/grammars/README.md) ·
[Whisper-ATC: Open Models for ATC ASR (TU
Delft)](https://pure.tudelft.nl/ws/portalfiles/portal/218298256/ICRAT2024_paper_83.pdf) ·
[FCC ULS amateur license data](https://www.fcc.gov/uls/transactions/daily-weekly) ·
[Moonshine](https://arxiv.org/html/2410.15608v1)
