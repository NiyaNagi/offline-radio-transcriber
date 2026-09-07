"""R1 — LoRA fine-tune -> merge_and_unload -> sherpa-onnx export -> load -> transcribe (S1.7).

Gates D13 (fine-tuning first class): if a LoRA-tuned ``distil-small.en`` cannot be merged and
exported into the form sherpa-onnx consumes, the runtime choice reopens. ``LoraTrainer``,
``Exporter`` and ``AsrRuntime`` are the seams the real HuggingFace/peft/sherpa-onnx toolchain
plugs in behind; the Fake* classes are the behavioural fakes proving the five-stage pipeline
composes correctly, independent of whether that toolchain is installed.
"""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from typing import Any, Callable, Protocol


@dataclass(frozen=True)
class AdapterHandle:
    tag: str


@dataclass(frozen=True)
class MergedModelHandle:
    tag: str


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

    def load(self, exported_path: str) -> "FakeAsrRuntime._Engine":
        return FakeAsrRuntime._Engine(exported_path)


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
    required = ("transformers", "peft", "accelerate", "sherpa_onnx")
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

    # Real path: would finetune distil-small.en with peft, merge_and_unload(), export with
    # sherpa-onnx's script, load the result, and transcribe. Not exercised in this
    # environment — `available` is never True here, so this branch is unreachable and cannot
    # hide a fabricated number.
    raise NotImplementedError(
        "real LoRA fine-tune/export is not implemented — this branch is unreachable in this "
        "environment; see results/r1-lora-export.md"
    )
