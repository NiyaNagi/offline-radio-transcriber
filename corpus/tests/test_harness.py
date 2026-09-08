"""Harness v0: metrics computed over hand transcripts, reported per source as well as
aggregate (FR-TST-8), with a run fingerprint. Every report carries its fold (AC-100)."""
import json

import pytest

from corpus.gate import EvalFoldSealed
from corpus.harness import evaluate
from corpus.manifest import Manifest, Session, Source
from corpus.report import Report, ReportError

SRC = [
    Source("hf", "Paderborn HF", "u", "CC-BY-4.0", "public", False, {"algo": "md5", "value": "x"}),
    Source("atc", "ATC merge", "u", "free", "public", False, {"algo": "md5", "value": "z"}),
]


def _manifest():
    return Manifest("0", SRC, [
        Session("hf/dev-1", "hf", "dev", "speech", 60.0, ["W1AW"], "a.flac",
                {"algo": "sha256", "value": "1"}),
        Session("atc/dev-1", "atc", "dev", "speech", 60.0, ["N1XY"], "b.flac",
                {"algo": "sha256", "value": "2"}),
        Session("hf/eval-1", "hf", "eval", "speech", 60.0, ["DL8XYZ"], "c.flac",
                {"algo": "sha256", "value": "3"}),
    ])


def _transcripts():
    return {
        "hf/dev-1": {
            "ref_tokens": ["w1aw", "this", "is", "kilo"], "hyp_tokens": ["w1aw", "this", "is", "kilo"],
            "ref_callsigns": ["W1AW"], "hyp_callsigns": ["W1AW"],
            "ref_boundaries": [[0, 1000]], "hyp_boundaries": [[10, 1010]],
            "segments": [{"accepted": True, "reason": None}],
            "attributions": [{"predicted": "W1AW", "truth": "W1AW"}],
        },
        "atc/dev-1": {
            "ref_tokens": ["n1xy", "cleared"], "hyp_tokens": ["n1xy", "blocked"],
            "ref_callsigns": ["N1XY"], "hyp_callsigns": [],
            "ref_boundaries": [[0, 500]], "hyp_boundaries": [],
            "segments": [{"accepted": False, "reason": "low-confidence"}],
            "attributions": [{"predicted": None, "truth": "N1XY"}],
        },
    }


def test_FR_TST_8_evaluate_reports_per_source_and_aggregate():
    report = evaluate(_manifest(), "dev", _transcripts())
    assert report.fold == "dev"
    assert set(report.per_source) == {"hf", "atc"}
    assert "wer" in report.aggregate
    assert report.fingerprint
    # aggregate WER pools tokens: 1 sub over 6 ref tokens
    assert round(report.aggregate["wer"], 3) == round(1 / 6, 3)


def test_evaluate_refuses_eval_without_flag():
    with pytest.raises(EvalFoldSealed):
        evaluate(_manifest(), "eval", {})


def test_evaluate_allows_eval_with_flag():
    report = evaluate(_manifest(), "eval", {
        "hf/eval-1": {"ref_tokens": ["a"], "hyp_tokens": ["a"], "ref_callsigns": [],
                      "hyp_callsigns": [], "ref_boundaries": [], "hyp_boundaries": [],
                      "segments": [], "attributions": []},
    }, allow_eval=True)
    assert report.fold == "eval"


def test_report_cannot_be_written_without_a_fold():
    with pytest.raises(ReportError):
        Report(fold="", fingerprint="abc", per_source={}, aggregate={})


def test_report_roundtrips_to_disk(tmp_path):
    report = evaluate(_manifest(), "dev", _transcripts())
    out = tmp_path / "report.json"
    report.write(out)
    doc = json.loads(out.read_text())
    assert doc["fold"] == "dev"
    assert doc["fingerprint"] == report.fingerprint
    assert (tmp_path / "report.md").exists()
