# R1 — LoRA fine-tune, merge, sherpa-onnx export, load, transcribe

**Gate:** functional-spec §14A.3, R1 (spec/build-plan.md S1.7). Decides whether D13 (fine-tuning
first-class on the chosen ASR runtime) is safe, or whether the runtime choice reopens.

## Verdict: NOT RUN

The real probe — LoRA fine-tune `distil-small.en` on ten minutes of anything, `merge_and_unload()`,
export with sherpa-onnx's script, load the result, transcribe — **was not executed**. As with
R4, this is stated plainly rather than papered over with an invented number.

### Why it could not run here

Checked via `importlib.util.find_spec` in this environment:

| Package | Available |
|---|---|
| `transformers` | No |
| `peft` | No |
| `accelerate` | No |
| `sherpa_onnx` | No |
| `onnxruntime` | No |
| `onnx` | Yes (present, but insufficient alone — no training or sherpa-onnx export tooling) |

No GPU is present (`nvidia-smi` returns not-found). A CPU-only LoRA fine-tune of
`distil-small.en` is plausible in principle even for ten minutes of audio, but the sherpa-onnx
export *script* is a separate, unvetted tool this session has no local copy of, and installing
the full HuggingFace + `peft` + `accelerate` + `sherpa-onnx` toolchain plus downloading
`distil-small.en`'s weights was judged out of scope for a probe whose entire purpose is to be
run and reported honestly rather than attempted under time pressure and left ambiguous.
Network connectivity itself was confirmed working in this sandbox — the blocker is the missing
toolchain and model weights, not connectivity.

### What was built instead (test-first, in this change)

`corpus/src/corpus/probes/lora_export.py` — the five-stage pipeline as protocols:
`LoraTrainer.finetune` / `.merge_and_unload`, `Exporter.export`, `AsrRuntime.load` /
`AsrEngine.transcribe`, plus `dependencies_available()` which checks for the real packages by
name. `FakeLoraTrainer`, `FakeExporter`, `FakeAsrRuntime` are the behavioural fakes shipped in
the same change (constitution II) — each records the handle it received and produced, so a
test can assert the five stages actually ran in order and data actually flowed between them.

`corpus/tests/test_probe_lora_export.py` proves the wiring: `run_pipeline` composes
`finetune -> merge_and_unload -> export -> load -> transcribe` correctly end to end, and
`probe_r1` reports `not_run` (not a fabricated pass) when the dependency check fails.

### Mechanical dry run (not a measurement of R1)

`probe_r1(available_check=lambda: False, dry_run=True)` was run against the Fake* classes to
prove the pipeline composes. This is **not** a measurement of whether a real LoRA-tuned
`distil-small.en` actually merges and exports into a form sherpa-onnx can load — it only proves
this module's orchestration code calls each stage correctly and passes handles through
unmodified.

| Field | Value |
|---|---|
| Status | `dry_run` (fakes, not the real toolchain) |
| Model | `distil-whisper/distil-small.en` (named, not actually loaded or trained) |
| Trainer / Exporter / Runtime | `FakeLoraTrainer` / `FakeExporter` / `FakeAsrRuntime` |
| Transcript | `transcribed:fixture-audio` (the fake's fixed echo, not a real transcription) |
| Fingerprint | `274bf634123fd8f56b8ba76b1e1da4d164abec74032b1a1806e56e1213394d61` |
| Machine | Windows-10-10.0.19045-SP0, Python 3.13.1 |
| Code version | `2eec8a7d2bd39bef8a4a0f6f514798caecfd13ce` |

## Consequence

Per the prompt: *"A failure reopens the runtime choice."* No failure — and no success — has
been measured, so **D13's safety on the chosen runtime is unconfirmed, not validated.**
Before M0a (the domain fine-tune milestone) begins:

1. Install `transformers`, `peft`, `accelerate`, and sherpa-onnx's export tooling (per
   `research/03` §8 T5, referenced from implementation-plan M0a).
2. Obtain ten minutes of any speech audio (constitution: "anything" — this need not be domain
   audio for the mechanical probe).
3. Run the real pipeline through `corpus.probes.lora_export.run_pipeline` with real
   `LoraTrainer`/`Exporter`/`AsrRuntime` implementations satisfying the same protocols used
   here, and replace this document's verdict with a real `status="ran"` result stating the
   base model, adapter config, export path, and a transcript sample, with its run fingerprint.
4. If that fails, D13 and the runtime choice (D15) must be reopened before M0a proceeds, per
   the build plan.
