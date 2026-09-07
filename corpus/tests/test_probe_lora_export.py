"""R1 probe plumbing — LoRA fine-tune, merge_and_unload, sherpa-onnx export, load, transcribe.

transformers/peft/accelerate/sherpa_onnx are not installed and no GPU is present in this
sandbox (see results/r1-lora-export.md). These tests exercise the pipeline's *wiring* against
fakes for each stage — the behavioural fakes the real trainer/exporter/runtime plug in behind.
"""
from __future__ import annotations

from corpus.probes.lora_export import (
    FakeAsrRuntime,
    FakeExporter,
    FakeLoraTrainer,
    dependencies_available,
    probe_r1,
    run_pipeline,
)


def test_fake_pipeline_composes_end_to_end():
    trainer = FakeLoraTrainer()
    exporter = FakeExporter()
    runtime = FakeAsrRuntime()

    text = run_pipeline(
        base_model_id="distil-whisper/distil-small.en",
        minutes_of_audio=10,
        trainer=trainer,
        exporter=exporter,
        runtime=runtime,
        transcribe_audio="fixture-audio",
    )

    assert text == "transcribed:fixture-audio"
    # every stage's handle carries a marker proving it passed through in order
    assert trainer.last_adapter.tag == "adapter(distil-whisper/distil-small.en)"
    assert trainer.last_merged.tag == "merged(adapter(distil-whisper/distil-small.en))"
    assert exporter.last_export_dir is not None


def test_dependencies_available_is_false_without_the_real_packages():
    # in this sandbox none of these are installed; the check must say so rather than guess
    assert dependencies_available(
        probe=lambda name: False
    ) is False


def test_probe_reports_not_run_when_dependencies_are_missing():
    result = probe_r1(available_check=lambda: False)
    assert result.status == "not_run"
    assert "not installed" in result.reason or "GPU" in result.reason


def test_probe_runs_the_fixture_dry_run_when_asked_explicitly():
    result = probe_r1(available_check=lambda: False, dry_run=True)
    assert result.status == "dry_run"
    assert result.fingerprint
    assert result.transcript == "transcribed:fixture-audio"
