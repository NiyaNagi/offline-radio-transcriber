"""Tests for tools/ui-audit/tour_manifest.py (R-1103, register - constitution II, VIII:
`tour.ps1` is the tool the constitution makes the arbiter of visual evidence, and its own
run-id-matching and fewer-screenshots-than-steps logic had no automated test at all, because the
repository has no PowerShell test harness).

This module exists because R-1083's fix (a stale or foreign tour manifest reported as a
successful run) lived entirely inline in `tour.ps1`'s polling loop and post-pull checks - pure
decision logic (given the on-device manifest text and this invocation's own run id, is the run
actually done, and did it produce as many results as were asked for) tangled up with the
PowerShell orchestration (adb, base64 pulls, sleeps) that cannot run outside a real device.
Following the pattern `tools/ui-audit/diff.py` already established the same day for the same
reason (R-1116): the decision logic is pulled out into a plain Python module with no device
dependency, `tour.ps1` now shells out to it for every decision point instead of re-implementing
the same JSON parsing twice, and this suite is what the existing `ui-audit-diff` CI job
(`.github/workflows/ci.yml`, `python -m pytest tools/ui-audit -q`) now also exercises.

Each test is named for what it establishes, per AGENTS.md's convention (`R_1103_...`). Per
constitution II, the discriminating case is the one the register row names as what was actually
broken: a stale or foreign manifest's own "done": true line reported as success. That case
(`test_R_1103_a_foreign_run_ids_done_line_is_not_reported_done`) was watched to fail for the
right reason against a version of `check_done_marker` that only checked for the substring
`"done": true` and never compared `runId` at all (the exact pre-R-1083 shape) - see
CHANGELOG.md's R-1103 entry for the revert/red/restore/green transcript.
"""
from __future__ import annotations

import io
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import tour_manifest


class _FakeStdin:
    """A stand-in for `sys.stdin` exposing only the `.buffer.read()` surface `main()` uses -
    mirrors how PowerShell actually delivers manifest text to this script's stdin (bytes, not
    pre-decoded text; see the BOM test below for why that distinction matters)."""

    def __init__(self, data: bytes) -> None:
        self.buffer = io.BytesIO(data)


def run_cli(monkeypatch, args: list[str], stdin_bytes: bytes = b"") -> int:
    monkeypatch.setattr(sys, "argv", ["tour_manifest.py"] + args)
    monkeypatch.setattr(sys, "stdin", _FakeStdin(stdin_bytes))
    return tour_manifest.main()


# ---------------------------------------------------------------------------
# check_done_marker: the run-id matching logic from tour.ps1's polling loop.
# ---------------------------------------------------------------------------


def test_R_1103_no_lines_at_all_is_not_done():
    result = tour_manifest.check_done_marker("", "run-A")
    assert result.done is False
    assert result.foreign_run_id is None


def test_R_1103_a_step_line_with_no_done_marker_is_not_done():
    text = '{"id": "N01-now", "ok": true, "runId": "run-A"}\n'
    result = tour_manifest.check_done_marker(text, "run-A")
    assert result.done is False


def test_R_1103_a_done_line_matching_this_runs_own_id_is_done():
    text = (
        '{"id": "N01-now", "ok": true, "runId": "run-A"}\n'
        '{"done": true, "runId": "run-A"}\n'
    )
    result = tour_manifest.check_done_marker(text, "run-A")
    assert result.done is True
    assert result.foreign_run_id is None


def test_R_1103_a_foreign_run_ids_done_line_is_not_reported_done():
    """The literal defect R-1083 fixed and this row says has no test: a completed run belonging
    to a *different* invocation (a stale manifest left on disk, or two overlapping tours) must
    never be mistaken for this run's own success."""
    text = '{"done": true, "runId": "run-FOREIGN"}\n'
    result = tour_manifest.check_done_marker(text, "run-THIS-INVOCATION")
    assert result.done is False
    assert result.foreign_run_id == "run-FOREIGN"


def test_R_1103_only_the_last_non_empty_line_is_consulted():
    """A concurrent append caught mid-write can leave a malformed or unrelated line after the
    real done marker in transit - but tour.ps1's own contract (and TourRunner's) is that `done`
    is always the trailing line once truly finished, so a done line that is *not* last must not
    be treated as the run having finished."""
    text = (
        '{"done": true, "runId": "run-A"}\n'
        '{"id": "N02-log", "ok": true, "runId": "run-A"}\n'
    )
    result = tour_manifest.check_done_marker(text, "run-A")
    assert result.done is False


def test_R_1103_a_malformed_trailing_line_is_treated_as_not_yet_not_a_crash():
    """A concurrent append caught mid-write (tour.ps1's own comment: "a malformed/partial line")
    must be treated as 'not yet', never raise - the next poll tick reads the completed file."""
    text = '{"done": true, "runId": "run-A"'  # truncated, no closing brace
    result = tour_manifest.check_done_marker(text, "run-A")
    assert result.done is False
    assert result.foreign_run_id is None


def test_R_1103_a_done_line_with_no_run_id_at_all_is_not_done_and_not_foreign():
    """A manifest predating R-1083's stamping (no `runId` key on any entry) must not crash and
    must not falsely claim to be this run's own success either."""
    text = '{"done": true}\n'
    result = tour_manifest.check_done_marker(text, "run-A")
    assert result.done is False
    assert result.foreign_run_id is None


# ---------------------------------------------------------------------------
# summarize: the manifest-line parsing that used to happen twice, in PowerShell, in two places.
# ---------------------------------------------------------------------------


def test_R_1103_summarize_counts_ok_and_error_steps_and_skips_the_done_marker():
    text = (
        '{"id": "N01-now", "ok": true, "runId": "run-A"}\n'
        '{"id": "N02-log", "ok": false, "errorMessage": "boom", "runId": "run-A"}\n'
        '{"done": true, "runId": "run-A"}\n'
    )
    summary = tour_manifest.summarize(text)
    assert summary.step_count == 2
    assert summary.ok_count == 1
    assert summary.error_count == 1
    assert summary.ok_ids == ["N01-now"]
    assert summary.errors == [{"id": "N02-log", "errorMessage": "boom"}]


def test_R_1103_summarize_of_empty_manifest_is_all_zero():
    summary = tour_manifest.summarize("")
    assert summary.step_count == 0
    assert summary.ok_count == 0
    assert summary.error_count == 0
    assert summary.ok_ids == []


# ---------------------------------------------------------------------------
# verify_step_count / verify_pulled_count: the "fewer screenshots than steps" check.
# ---------------------------------------------------------------------------


def test_R_1103_step_count_matching_requested_does_not_raise():
    tour_manifest.verify_step_count(step_count=3, requested_count=3, run_id="run-A")


def test_R_1103_fewer_step_results_than_requested_raises():
    """The exact R-1083 third symptom this row's own text quotes: `-Only "vad-fallback/*"`
    reported `steps: 2 ok: 2 errors: 0` while the manifest had produced fewer results than the
    two steps actually requested would require - a false green a summary print alone would not
    have caught."""
    with pytest.raises(tour_manifest.StepCountMismatch):
        tour_manifest.verify_step_count(step_count=0, requested_count=2, run_id="run-A")


def test_R_1103_more_step_results_than_requested_also_raises():
    with pytest.raises(tour_manifest.StepCountMismatch):
        tour_manifest.verify_step_count(step_count=3, requested_count=2, run_id="run-A")


def test_R_1103_pulled_count_matching_ok_count_does_not_raise():
    tour_manifest.verify_pulled_count(pulled_count=2, ok_count=2, run_id="run-A", out_dir="out")


def test_R_1103_fewer_pulled_screenshots_than_ok_steps_raises():
    """The other half of the same finding: `"ok: 2"` in the manifest while zero PNGs had actually
    been written to disk."""
    with pytest.raises(tour_manifest.PulledCountMismatch):
        tour_manifest.verify_pulled_count(pulled_count=0, ok_count=2, run_id="run-A", out_dir="out")


# ---------------------------------------------------------------------------
# main(): the CLI surface tour.ps1 actually shells out to.
# ---------------------------------------------------------------------------


def test_R_1103_cli_poll_strips_a_leading_utf8_bom_from_stdin(monkeypatch, capsys):
    """Found by actually piping a real manifest string from Windows PowerShell 5.1 into this
    script and reading the raw bytes back on the Python side, not by inspection: PowerShell
    prepends a UTF-8 BOM (`\\xef\\xbb\\xbf`) when it pipes a string to a native process's stdin
    (`$manifestText | python tour_manifest.py ...`, exactly how tour.ps1 calls this script). A
    BOM before the opening `{` is not valid JSON and `json.loads` on a `str` does not strip it,
    so without this, the very first poll of every real tour run would silently fail to parse and
    be treated as 'not yet' - never a crash, just permanently-wrong 'not done' until timeout."""
    text = '{"done": true, "runId": "run-A"}\n'
    bom_bytes = b"\xef\xbb\xbf" + text.encode("utf-8")
    rc = run_cli(monkeypatch, ["poll", "--run-id", "run-A"], stdin_bytes=bom_bytes)
    assert rc == 0
    out = json.loads(capsys.readouterr().out)
    assert out == {"done": True, "foreignRunId": None}


def test_R_1103_cli_summarize_round_trips_through_json(monkeypatch, capsys):
    text = (
        '{"id": "N01-now", "ok": true, "runId": "run-A"}\n'
        '{"done": true, "runId": "run-A"}\n'
    )
    rc = run_cli(monkeypatch, ["summarize"], stdin_bytes=text.encode("utf-8"))
    assert rc == 0
    out = json.loads(capsys.readouterr().out)
    assert out["stepCount"] == 1
    assert out["okCount"] == 1
    assert out["okIds"] == ["N01-now"]


def test_R_1103_cli_verify_exits_2_on_step_count_mismatch(monkeypatch):
    rc = run_cli(monkeypatch, [
        "verify", "--run-id", "run-A", "--out-dir", "out",
        "--requested-count", "5", "--step-count", "1", "--ok-count", "1", "--pulled-count", "1",
    ])
    assert rc == 2


def test_R_1103_cli_verify_exits_3_on_pulled_count_mismatch(monkeypatch):
    rc = run_cli(monkeypatch, [
        "verify", "--run-id", "run-A", "--out-dir", "out",
        "--requested-count", "1", "--step-count", "1", "--ok-count", "2", "--pulled-count", "0",
    ])
    assert rc == 3


def test_R_1103_cli_verify_exits_0_when_everything_agrees(monkeypatch):
    rc = run_cli(monkeypatch, [
        "verify", "--run-id", "run-A", "--out-dir", "out",
        "--requested-count", "1", "--step-count", "1", "--ok-count", "1", "--pulled-count", "1",
    ])
    assert rc == 0
