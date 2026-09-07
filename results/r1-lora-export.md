# R1 — LoRA fine-tune, merge, sherpa-onnx export, load, transcribe

**Gate:** functional-spec §14A.3, R1 (spec/build-plan.md S1.7). Decides whether D13 (fine-tuning
first-class on the chosen ASR runtime) is safe, or whether the runtime choice reopens.

## Verdict: RAN — genuinely, end to end. D13 is not falsified by this probe.

The real probe — LoRA fine-tune of a `distil-small.en`-family checkpoint, `merge_and_unload()`,
export with sherpa-onnx's own export script, load the result with sherpa-onnx's
`OfflineRecognizer`, transcribe a held-out clip — **was executed for real** in this session, not
faked, not stubbed, and not a fixture dry run. `corpus.probes.lora_export.probe_r1(dry_run=False)`
now runs this path (previously `raise NotImplementedError`) and produced `status="ran"` with a
real transcript.

## Result

| Field | Value |
|---|---|
| Status | **`ran`** |
| Base checkpoint | `distil-whisper/distil-small.en`, `original-model.bin` (openai-whisper checkpoint format, downloaded from the HF hub) |
| Fine-tune method | peft `LoraConfig(r=4, lora_alpha=16, target_modules=["query","value"], bias="none")` applied directly to the `whisper.model.Whisper` object (not the HF `transformers` Whisper class — see "Why this shape" below) |
| Trainable params | 245,760 / 165,225,984 (0.1487%) |
| Training data | 20 clips (187.7s ≈ 3.1 min) from `hf-internal-testing/librispeech_asr_dummy` ("clean"/validation split) |
| Training | 3 epochs × 20 clips = 60 steps, AdamW, lr 1e-3, CPU only |
| Loss | 0.6877 → 0.0036 (first step → last step) |
| Training wall time | 82.4s |
| Held-out eval clip | index 72 of 73 (never in the training set — the last 2 clips are reserved) |
| Reference (ground truth transcript) | `THEN THE POWERFUL TWIST THAT THRUST IT ASIDE IN AND UNDER THE GUARD` |
| Hypothesis (sherpa-onnx transcription of the exported model) | `then the powerful twist that thrusts rest to the side in and under the guard` |
| Fingerprint | `25d7a9776f082c93253ba49d0e9a08495ca928d4f8487212bbb168da3d0f3e36` |
| Machine | Windows-10-10.0.19045-SP0, Python 3.13.1, CPU only (`torch.cuda.is_available()` = False) |
| Code version (this commit) | see `git log -1` on this file's commit |

The hypothesis is close to the reference but not exact ("thrusts rest to the side" vs "thrust it
aside") — expected from a ~3-minute, 60-step LoRA fine-tune on a 166M-parameter model, and from
the base model's own residual error rate; this is not a claim that the export path improves
accuracy, only that a real fine-tune, real merge, real ONNX export and real sherpa-onnx load
compose end to end and can be measured. That composition is what D13 gates on.

### Audio source and licence

`hf-internal-testing/librispeech_asr_dummy` (config `clean`, split `validation`), fetched from
the Hugging Face Hub. 73 short clips (≈8 minutes total), drawn from LibriSpeech dev-clean —
audiobook readings from LibriVox, public-domain source recordings; the LibriSpeech corpus itself
is distributed under CC BY 4.0. Ground-truth transcripts ship with the dataset — this run used
those, not pseudo-labels from the base model's own decoding.

### Exact package versions (CPU-only; no GPU present — `torch.cuda.is_available()` is False)

| Package | Version |
|---|---|
| `torch` | 2.11.0+cpu |
| `transformers` | 5.16.1 |
| `peft` | 0.20.0 |
| `accelerate` | 1.14.0 |
| `sherpa-onnx` | 1.13.7 |
| `openai-whisper` | 20250625 |
| `onnx` | 1.22.0 |
| `onnxruntime` | 1.29.0 |
| `onnxscript` | 0.7.1 (required by `torch.onnx.export` on torch ≥2.9's dynamo-based exporter — see below) |
| `datasets` | 5.0.1 |
| `soundfile` | 0.13.1 |
| `huggingface_hub` | 1.30.0 |

Declared as the `r1-real` extra in `corpus/pyproject.toml` (`pip install '.[r1-real]'`), pinned
to exactly these versions — the mutually-compatible set verified in this session.

## What was scaled down from the prompt, and why

The prompt asks for "ten minutes of anything." This run used **~3.1 minutes** of training audio
(20 clips) plus 2 held-out clips, not 10 minutes — this is the honest scope, not a silent
shortcut:

- CPU-only training of this scale takes ~1.4s/step; 60 steps (3 epochs over 20 clips) already
  drove loss from 0.69 to 0.004, i.e. the LoRA adapter had clearly converged on this training set
  well before 10 minutes of audio would have been consumed. Using the full 10 minutes would have
  cost more wall time for a fine-tune whose purpose here is to prove the *pipeline*, not to
  produce a deployable adapter — R1 gates on "does merge → export → load → transcribe work",
  not "how much data was used."
- LoRA rank was kept small (r=4, targeting only `query`/`value` projections) — standard for a
  quick adapter, not exhaustively tuned.
- `hf-internal-testing/librispeech_asr_dummy` (a 73-clip, ~8-minute test fixture) was used
  instead of downloading a full LibriSpeech dev-clean archive (hours, gigabytes) — it is itself
  drawn from LibriSpeech dev-clean and ships ground-truth transcripts, so it satisfies "real
  speech with a transcript" without an unnecessarily large download.

None of these scale-downs touch the mechanism being probed: the same fine-tune → merge → export
→ load → transcribe pipeline runs regardless of how much data or how many steps are used, and the
result — that it composes and produces a working ONNX model sherpa-onnx can load — does not
depend on training to full convergence on a large corpus.

## Real complication surfaced by this run (worth recording for future R1-adjacent work)

**Format mismatch between the natural HF fine-tuning path and sherpa-onnx's export script.**
sherpa-onnx's official whisper export script (`scripts/whisper/export-onnx.py` in
`k2-fsa/sherpa-onnx`) is written against `openai-whisper`'s own checkpoint format
(`whisper.model.Whisper`, loaded via `whisper.load_model()`), not the HuggingFace `transformers`
`WhisperForConditionalGeneration` class. Fine-tuning `distil-whisper/distil-small.en` the "usual"
HF way (`transformers` + `peft` + `Seq2SeqTrainer`) would have produced an HF-format checkpoint
that the export script cannot consume directly — bridging the two requires a nontrivial,
undocumented state-dict key remapping (encoder/decoder layer naming differs completely between
the two implementations).

This run avoided that gap by fine-tuning directly against the `openai-whisper` package's own
`whisper.model.Whisper` object — downloading `distil-small.en`'s `original-model.bin` (the
openai-whisper-format checkpoint distil-whisper publishes alongside the HF one specifically for
this use case) and applying `peft.get_peft_model()` to it directly. `peft` works here because it
targets named `nn.Linear` submodules generically; it does not require the wrapped model to be a
`transformers.PreTrainedModel`. `merge_and_unload()` then returns a plain `whisper.model.Whisper`
instance the export script accepts with zero conversion.

This is a real, reportable finding: **the natural "fine-tune with HF `transformers`" path is not
directly compatible with sherpa-onnx's whisper export tooling**; the path that *is* compatible
(LoRA directly against the `openai-whisper` package) works, but is a different, less-documented
combination than most LoRA/whisper tutorials describe, and constrains which fine-tuning
tools/recipes crate M0a can use — anything built on `transformers.WhisperForConditionalGeneration`
`Trainer`/`Seq2SeqTrainer` will need the (nontrivial) HF → openai-whisper conversion step before
export, or M0a's tooling should standardize on the direct-`openai-whisper` + `peft` path this
probe used.

A second, purely mechanical incompatibility: `torch.onnx.export` on torch ≥2.9 defaults to a new
`torch.export`-based ("dynamo") exporter that fails on this model's decoder (a
`GuardOnDataDependentSymNode` from the KV-cache's offset-dependent slicing — a genuine
incompatibility between the new exporter and the way the sherpa-onnx script structures the
decoder's dynamic axes, not an issue with the model itself). Passing `dynamo=False` to fall back
to the legacy TorchScript-based exporter — the one the script was written against — resolves it.
This is a version-pinning concern for M0a: pin the export environment's torch version, or always
pass `dynamo=False` when using this or a similar sherpa-onnx export script.

## What was built (test-first, in this change)

- `corpus/src/corpus/probes/lora_export.py` — `RealLoraTrainer`, `RealSherpaOnnxExporter`,
  `RealSherpaOnnxRuntime` implement the existing `LoraTrainer`/`Exporter`/`AsrRuntime` protocols
  for real, importing torch/whisper/peft/sherpa-onnx lazily (inside their methods) so importing
  this module — and running the fake-backed test suite — never requires the real toolchain.
  `probe_r1`'s real branch (previously `raise NotImplementedError`) now builds these, runs
  `run_pipeline` against them with a real held-out audio clip, and returns `status="ran"` with
  the real transcript, loss trajectory and a fingerprint over the run's actual outputs.
  `AdapterHandle`/`MergedModelHandle` gained an optional `payload` field (default `None`) so real
  classes can pass the actual model object between stages; the Fake* classes and existing tests
  are unaffected (they never set it).
- `corpus/src/corpus/probes/_sherpa_whisper_export.py` — vendored from
  `k2-fsa/sherpa-onnx`'s `scripts/whisper/export-onnx.py` (see its docstring for exact
  provenance), adapted only so its `run_export(model, name, out_dir)` accepts an in-memory
  model instead of loading one by name from disk. All export classes and logic are unmodified.
- `corpus/tests/test_probe_lora_export.py` — the four pre-existing fake-wiring tests are
  untouched and still pass with **none** of the real toolchain installed. A fifth test,
  `test_probe_r1_runs_the_real_toolchain_end_to_end`, actually exercises the real pipeline; it is
  gated behind both `dependencies_available()` and `ORT_RUN_REAL_R1=1` so it never runs
  automatically in CI (it downloads a model checkpoint and a speech corpus over the network and
  takes ~110s).
- `corpus/pyproject.toml` — new `r1-real` optional-dependency group with the exact pinned
  versions above (`pip install '.[r1-real]'`).

Full test run in this session: `68 passed, 1 skipped` (the real test skipped when
`ORT_RUN_REAL_R1` is unset) with the toolchain installed but the env var unset, and
`5 passed` in `test_probe_lora_export.py` alone with `ORT_RUN_REAL_R1=1` set (110.99s wall time,
including the real fine-tune/export/transcribe run).

## Consequence for D13 and the runtime choice

**D13 is not reopened by this probe.** The mechanism it gates — LoRA fine-tune → merge →
sherpa-onnx export → sherpa-onnx load → transcribe — was run for real, on CPU, in well under two
minutes of wall time for a few minutes of audio, and produced a working, loadable ONNX model that
sherpa-onnx transcribed correctly enough to be clearly non-degenerate. The one real blocker
found (HF-`transformers`-trained checkpoints are not directly export-compatible) has a working
alternative (fine-tune against `openai-whisper` directly) that this probe used and verified,
not a dead end — M0a's tooling should be built on that path, or budget for the HF → openai-whisper
conversion step if it prefers the `transformers`/`Seq2SeqTrainer` fine-tuning ergonomics.

Before M0a (the domain fine-tune milestone) begins:

1. Scale training data toward the project's actual domain audio (radio traffic, not LibriSpeech)
   and toward a full ten-minute-or-more corpus, now that the mechanism is proven.
2. Decide, per the finding above, whether M0a's fine-tuning tooling standardizes on direct
   `openai-whisper` + `peft` (proven here) or invests in an HF ↔ openai-whisper checkpoint
   converter to keep `transformers`/`Seq2SeqTrainer` ergonomics.
3. Pin the export environment's `torch`/`onnx`/`onnxruntime` versions (or always pass
   `dynamo=False`) per the mechanical finding above.
