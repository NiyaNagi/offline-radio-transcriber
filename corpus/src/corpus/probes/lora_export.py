"""R1 — LoRA fine-tune -> merge_and_unload -> sherpa-onnx export -> load -> transcribe (S1.7).

Gates D13 (fine-tuning first class): if a LoRA-tuned ``distil-small.en`` cannot be merged and
exported into the form sherpa-onnx consumes, the runtime choice reopens. ``LoraTrainer``,
``Exporter`` and ``AsrRuntime`` are the seams the real HuggingFace/peft/sherpa-onnx toolchain
plugs in behind; the Fake* classes are the behavioural fakes proving the five-stage pipeline
composes correctly, independent of whether that toolchain is installed.

``RealLoraTrainer`` / ``RealSherpaOnnxExporter`` / ``RealSherpaOnnxRuntime`` are the real
implementations, run for real in this session — see results/r1-lora-export.md for the verdict,
package versions and transcript. They import torch/whisper/peft/sherpa-onnx lazily, inside their
methods, so importing this module (and running the fake-backed test suite) never requires the
real toolchain to be installed.
"""
from __future__ import annotations

import hashlib
import json
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Protocol


@dataclass(frozen=True)
class AdapterHandle:
    tag: str
    # Real trainers stash whatever the next stage needs here (a model object, etc.) — the
    # Fake* classes and the protocol contract only care about `tag`.
    payload: Any = None


@dataclass(frozen=True)
class MergedModelHandle:
    tag: str
    payload: Any = None


class LoraTrainer(Protocol):
    def finetune(self, base_model_id: str, minutes_of_audio: float) -> AdapterHandle: ...
    def merge_and_unload(self, adapter: AdapterHandle) -> MergedModelHandle: ...


class Exporter(Protocol):
    def export(self, merged: MergedModelHandle, out_dir: str) -> str: ...


class AsrEngine(Protocol):
    def transcribe(self, audio: Any) -> str: ...


class AsrRuntime(Protocol):
    def load(self, exported_path: str) -> AsrEngine: ...


class FakeLoraTrainer:
    """Records every handle it produces so a test can assert the pipeline order held."""

    def __init__(self) -> None:
        self.last_adapter: AdapterHandle | None = None
        self.last_merged: MergedModelHandle | None = None

    def finetune(self, base_model_id: str, minutes_of_audio: float) -> AdapterHandle:
        self.last_adapter = AdapterHandle(tag=f"adapter({base_model_id})")
        return self.last_adapter

    def merge_and_unload(self, adapter: AdapterHandle) -> MergedModelHandle:
        self.last_merged = MergedModelHandle(tag=f"merged({adapter.tag})")
        return self.last_merged


class FakeExporter:
    def __init__(self) -> None:
        self.last_export_dir: str | None = None

    def export(self, merged: MergedModelHandle, out_dir: str) -> str:
        self.last_export_dir = out_dir
        return f"{out_dir}/exported({merged.tag}).onnx"


class FakeAsrRuntime:
    class _Engine:
        def __init__(self, path: str):
            self.path = path

        def transcribe(self, audio: Any) -> str:
            return f"transcribed:{audio}"

    def load(self, exported_path: str) -> FakeAsrRuntime._Engine:
        return FakeAsrRuntime._Engine(exported_path)


def _load_librispeech_clips() -> list[tuple[Any, str]]:
    """The paired-transcript audio R1 runs against: ``hf-internal-testing/librispeech_asr_dummy``
    (clean/validation), 73 short clips drawn from LibriSpeech dev-clean — public-domain LibriVox
    audiobook readings, CC BY 4.0 corpus licence. Returns (float32 16kHz array, transcript) pairs,
    decoded via soundfile (not torchcodec — no system FFmpeg in this environment).
    """
    import io

    import soundfile as sf
    from datasets import Audio, load_dataset

    ds = load_dataset("hf-internal-testing/librispeech_asr_dummy", "clean", split="validation")
    ds = ds.cast_column("audio", Audio(decode=False))
    clips = []
    for row in ds:
        arr, sr = sf.read(io.BytesIO(row["audio"]["bytes"]), dtype="float32")
        assert sr == 16000
        clips.append((arr, row["text"]))
    return clips


class RealLoraTrainer:
    """peft LoRA fine-tune of an openai-whisper-format checkpoint (e.g. distil-small.en's
    ``original-model.bin``, downloaded from the HF hub), trained directly against
    ``whisper.model.Whisper`` — not the HF ``transformers`` Whisper class — so that
    ``merge_and_unload()`` hands back a plain ``whisper.model.Whisper`` instance the sherpa-onnx
    export script can consume with no format conversion.

    ``n_eval_clips`` clips are reserved from the end of the corpus and never trained on; the
    caller (``probe_r1``) draws its held-out transcription clip from that same reservation.
    """

    def __init__(self, *, n_train_clips: int = 20, n_eval_clips: int = 2, epochs: int = 3, rank: int = 4, lr: float = 1e-3):
        self.n_train_clips = n_train_clips
        self.n_eval_clips = n_eval_clips
        self.epochs = epochs
        self.rank = rank
        self.lr = lr
        self.last_train_seconds: float = 0.0
        self.last_steps: int = 0
        self.last_loss_first: float = 0.0
        self.last_loss_last: float = 0.0

    def finetune(self, base_model_id: str, minutes_of_audio: float) -> AdapterHandle:
        import torch
        import whisper
        from huggingface_hub import hf_hub_download
        from peft import LoraConfig, get_peft_model

        ckpt_path = hf_hub_download(repo_id=base_model_id, filename="original-model.bin")
        model = whisper.load_model(ckpt_path)
        tokenizer = whisper.tokenizer.get_tokenizer(model.is_multilingual, language="en", task="transcribe")

        clips = _load_librispeech_clips()
        train_clips = clips[: len(clips) - self.n_eval_clips][: self.n_train_clips]

        lora_cfg = LoraConfig(r=self.rank, lora_alpha=4 * self.rank, lora_dropout=0.0, target_modules=["query", "value"], bias="none")
        peft_model = get_peft_model(model, lora_cfg)

        examples = []
        for arr, text in train_clips:
            audio_t = whisper.pad_or_trim(torch.from_numpy(arr))
            mel = whisper.log_mel_spectrogram(audio_t, n_mels=model.dims.n_mels).unsqueeze(0)
            sot_seq = list(tokenizer.sot_sequence) + [tokenizer.no_timestamps]
            tokens = sot_seq + tokenizer.encode(" " + text.strip().lower()) + [tokenizer.eot]
            tokens_t = torch.tensor([tokens])
            labels = tokens_t.clone()
            labels[:, : len(sot_seq) - 1] = -100
            examples.append((mel, tokens_t, labels))

        opt = torch.optim.AdamW([p for p in peft_model.parameters() if p.requires_grad], lr=self.lr)
        import time

        t0 = time.time()
        losses: list[float] = []
        for _epoch in range(self.epochs):
            for mel, tokens_t, labels in examples:
                opt.zero_grad()
                logits = peft_model(mel, tokens_t[:, :-1])
                loss = torch.nn.functional.cross_entropy(
                    logits.reshape(-1, logits.shape[-1]), labels[:, 1:].reshape(-1), ignore_index=-100
                )
                loss.backward()
                opt.step()
                losses.append(loss.item())
        self.last_train_seconds = sum(len(arr) for arr, _ in train_clips) / 16000
        self.last_steps = len(losses)
        self.last_loss_first = losses[0]
        self.last_loss_last = losses[-1]
        elapsed = time.time() - t0

        return AdapterHandle(
            tag=f"lora-r{self.rank}-steps{len(losses)}({base_model_id})",
            payload={
                "peft_model": peft_model,
                "tokenizer": tokenizer,
                "base_model_id": base_model_id,
                "train_seconds": self.last_train_seconds,
                "steps": len(losses),
                "loss_first": losses[0],
                "loss_last": losses[-1],
                "train_elapsed_s": elapsed,
            },
        )

    def merge_and_unload(self, adapter: AdapterHandle) -> MergedModelHandle:
        peft_model = adapter.payload["peft_model"]
        merged_model = peft_model.merge_and_unload()
        merged_model.eval()
        payload = dict(adapter.payload)
        payload["merged_model"] = merged_model
        return MergedModelHandle(tag=f"merged({adapter.tag})", payload=payload)


class RealSherpaOnnxExporter:
    """Exports via ``corpus.probes._sherpa_whisper_export.run_export`` — the official
    k2-fsa/sherpa-onnx whisper export script, adapted to accept an in-memory model instead of
    loading one by name from disk. See that module's docstring for exact provenance.
    """

    def __init__(self, *, export_name: str = "r1-lora-distil-small-en"):
        self.export_name = export_name

    def export(self, merged: MergedModelHandle, out_dir: str) -> str:
        from corpus.probes import _sherpa_whisper_export as sherpa_export

        model = merged.payload["merged_model"]
        sherpa_export.run_export(model, name=self.export_name, out_dir=out_dir)
        return out_dir


class RealSherpaOnnxRuntime:
    """Loads the exported tokens/encoder/decoder int8 ONNX files with sherpa-onnx's
    OfflineRecognizer and transcribes real audio through it.
    """

    def __init__(self, *, export_name: str = "r1-lora-distil-small-en"):
        self.export_name = export_name

    class _Engine:
        def __init__(self, recognizer):
            self._recognizer = recognizer

        def transcribe(self, audio: Any) -> str:
            waveform, sample_rate = audio
            stream = self._recognizer.create_stream()
            stream.accept_waveform(sample_rate, waveform)
            self._recognizer.decode_stream(stream)
            return stream.result.text

    def load(self, exported_path: str) -> RealSherpaOnnxRuntime._Engine:
        import sherpa_onnx

        name = self.export_name
        recognizer = sherpa_onnx.OfflineRecognizer.from_whisper(
            encoder=f"{exported_path}/{name}-encoder.int8.onnx",
            decoder=f"{exported_path}/{name}-decoder.int8.onnx",
            tokens=f"{exported_path}/{name}-tokens.txt",
            language="en",
            task="transcribe",
            num_threads=2,
        )
        return RealSherpaOnnxRuntime._Engine(recognizer)


def run_pipeline(
    *,
    base_model_id: str,
    minutes_of_audio: float,
    trainer: LoraTrainer,
    exporter: Exporter,
    runtime: AsrRuntime,
    transcribe_audio: Any,
    out_dir: str = "out",
) -> str:
    """finetune -> merge_and_unload -> export -> load -> transcribe, in that order."""
    adapter = trainer.finetune(base_model_id, minutes_of_audio)
    merged = trainer.merge_and_unload(adapter)
    exported_path = exporter.export(merged, out_dir)
    engine = runtime.load(exported_path)
    return engine.transcribe(transcribe_audio)


def dependencies_available(*, probe: Callable[[str], bool] | None = None) -> bool:
    """True only if every real-toolchain package this probe needs is importable."""
    import importlib.util

    def _default_probe(name: str) -> bool:
        return importlib.util.find_spec(name) is not None

    check = probe or _default_probe
    required = ("transformers", "peft", "accelerate", "sherpa_onnx", "whisper", "onnxruntime", "onnx")
    return all(check(name) for name in required)


@dataclass
class R1Result:
    status: str  # "ran" | "dry_run" | "not_run"
    reason: str = ""
    model: str = ""
    fingerprint: str = ""
    transcript: str = ""


def _fingerprint(payload: dict) -> str:
    blob = json.dumps(payload, sort_keys=True, default=str).encode("utf-8")
    return hashlib.sha256(blob).hexdigest()


def probe_r1(
    *,
    available_check: Callable[[], bool] | None = None,
    dry_run: bool = False,
    base_model_id: str = "distil-whisper/distil-small.en",
    minutes_of_audio: float = 10.0,
    trainer: LoraTrainer | None = None,
    exporter: Exporter | None = None,
    runtime: AsrRuntime | None = None,
    out_dir: str = "out",
) -> R1Result:
    """Run R1 if the real toolchain is available; otherwise report why it could not run.

    ``dry_run`` exercises the exact same ``run_pipeline`` call against the Fake* classes,
    proving the five-stage wiring composes correctly. It is reported as ``status="dry_run"``,
    never ``"ran"`` — it says nothing about whether a real LoRA export actually works.
    """
    available = (available_check or dependencies_available)()

    if dry_run:
        trainer, exporter, runtime = FakeLoraTrainer(), FakeExporter(), FakeAsrRuntime()
        text = run_pipeline(
            base_model_id=base_model_id,
            minutes_of_audio=minutes_of_audio,
            trainer=trainer,
            exporter=exporter,
            runtime=runtime,
            transcribe_audio="fixture-audio",
        )
        return R1Result(
            status="dry_run",
            reason="fixture dry run only — the real LoRA/peft/sherpa-onnx toolchain was not used",
            model=base_model_id,
            fingerprint=_fingerprint({
                "base_model_id": base_model_id, "minutes_of_audio": minutes_of_audio,
                "trainer": "FakeLoraTrainer", "exporter": "FakeExporter", "runtime": "FakeAsrRuntime",
            }),
            transcript=text,
        )

    if not available:
        return R1Result(
            status="not_run",
            reason=(
                "transformers/peft/accelerate/sherpa_onnx are not installed in this "
                "environment, and no GPU is present (nvidia-smi absent) — a CPU-only LoRA "
                "fine-tune plus a from-scratch sherpa-onnx export toolchain install was not "
                "attempted in this session. See results/r1-lora-export.md."
            ),
            model=base_model_id,
        )

    # Real path: finetune distil-small.en with peft, merge_and_unload(), export with
    # sherpa-onnx's script, load the result, and transcribe a held-out clip. Exercised for real
    # in this session — see results/r1-lora-export.md for the run this produced.
    real_trainer = trainer or RealLoraTrainer(n_train_clips=20, n_eval_clips=2, epochs=3, rank=4)
    real_exporter = exporter or RealSherpaOnnxExporter()
    real_runtime = runtime or RealSherpaOnnxRuntime()

    clips = _load_librispeech_clips()
    held_out_arr, held_out_text = clips[-1]  # last clip: never trained on (n_eval_clips=2 reserved)

    text = run_pipeline(
        base_model_id=base_model_id,
        minutes_of_audio=minutes_of_audio,
        trainer=real_trainer,
        exporter=real_exporter,
        runtime=real_runtime,
        transcribe_audio=(held_out_arr, 16000),
        out_dir=out_dir,
    )

    train_seconds = getattr(real_trainer, "last_train_seconds", 0.0)
    steps = getattr(real_trainer, "last_steps", 0)
    loss_first = getattr(real_trainer, "last_loss_first", 0.0)
    loss_last = getattr(real_trainer, "last_loss_last", 0.0)

    return R1Result(
        status="ran",
        reason=(
            f"real peft LoRA fine-tune of {base_model_id} (rank=4, {steps} steps over "
            f"{train_seconds:.1f}s of LibriSpeech dev-clean audio, loss {loss_first:.4f} -> "
            f"{loss_last:.4f}), merge_and_unload(), sherpa-onnx export, OfflineRecognizer load, "
            f"transcribed a held-out clip (reference: {held_out_text!r})"
        ),
        model=base_model_id,
        fingerprint=_fingerprint({
            "base_model_id": base_model_id, "steps": steps, "train_seconds": train_seconds,
            "loss_first": loss_first, "loss_last": loss_last, "held_out_reference": held_out_text,
            "held_out_hypothesis": text,
        }),
        transcript=text,
    )
