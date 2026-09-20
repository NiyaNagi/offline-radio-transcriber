"""The report generator — every section present, real numbers, honest empty states."""
import sys
from pathlib import Path

import duckdb

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import loader
import report

FIXTURE_DATA_DIR = Path(__file__).resolve().parent / "fixtures" / "data"


def test_report_contains_every_section_with_real_numbers():
    con = duckdb.connect(":memory:")
    loader.load_events(con, FIXTURE_DATA_DIR)

    text = report.generate_report(con)

    assert "# Analytics report (field fold)" in text
    assert "## Crash-free sessions" in text
    assert "crash-free rate: 0.500" in text
    assert "## Setup funnel" in text
    assert "MODELS / SUCCESS: 1" in text
    assert "## Real-time factor by pass" in text
    assert "B_OFFLINE" in text
    assert "## Correction rate by field (tier 1 aggregate)" in text
    assert "callsign: 0.120" in text
    assert "## Field WER (tier 2, opt-in)" in text
    assert "pairs: 1" in text


def test_report_states_honest_empty_sections_when_nothing_is_loaded(tmp_path):
    # One lone tier-1 usage event -- not empty (an empty NDJSON file gives DuckDB no columns to
    # infer at all, which is its own, unrelated failure mode) -- just nothing any section below
    # "Crash-free sessions" has anything to report from.
    partition = tmp_path / "schema-1"
    partition.mkdir()
    (partition / "2026-09-19.ndjson").write_text(
        '{"fold": "field", "tier": "TIER_1", '
        '"provenance": {"appVersion": "0.1.1", "band": null, "buildHash": "abc123", '
        '"captureMode": null, "deviceModel": "Pixel 7", "detectedTier": "T2", '
        '"executionProvider": "cpu", "installId": "install-1", "modelIds": [], '
        '"modelShas": [], "overId": null, "rigModule": null, "schemaVersion": 1, '
        '"sessionId": "session-x", "soc": "Tensor G2"}, '
        '"payload": {"payloadType": "tier1_usage", "screen": "X", "action": "Y"}}\n',
        encoding="utf-8",
    )
    con = duckdb.connect(":memory:")
    loader.load_events(con, tmp_path)

    text = report.generate_report(con)

    assert "no setup-funnel events" in text
    assert "no performance events" in text
    assert "no quality-stats events" in text
    assert "no tier-2" in text
