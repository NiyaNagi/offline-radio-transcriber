"""The named, reproducible queries — each checked against the fixture data set."""
import sys
from pathlib import Path

import duckdb
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import loader
import queries

FIXTURE_DATA_DIR = Path(__file__).resolve().parent / "fixtures" / "data"


@pytest.fixture()
def con():
    connection = duckdb.connect(":memory:")
    loader.load_events(connection, FIXTURE_DATA_DIR)
    return connection


def test_crash_free_sessions(con):
    result = queries.crash_free_sessions(con)

    assert result["total_sessions"] == 2  # session-1, session-2
    assert result["crashed_sessions"] == 1  # session-2's tier1_crash row
    assert result["crash_free_rate"] == pytest.approx(0.5)


def test_setup_funnel(con):
    result = queries.setup_funnel(con)

    assert ("MODELS", "SUCCESS", 1) in result
    assert ("MODELS", "DOWNLOAD_FAILED", 1) in result


def test_real_time_factor(con):
    result = queries.real_time_factor(con)

    assert len(result) == 1
    pass_id, avg_rtf, min_rtf, max_rtf, n = result[0]
    assert pass_id == "B_OFFLINE"
    assert n == 2
    assert min_rtf == pytest.approx(0.35)
    assert max_rtf == pytest.approx(0.55)
    assert avg_rtf == pytest.approx(0.45)


def test_correction_rate(con):
    result = queries.correction_rate(con)

    assert result["callsign"] == pytest.approx(0.12)
    assert result["frequency"] == pytest.approx(0.02)


def test_field_wer(con):
    result = queries.field_wer(con)

    # hypothesis "en nine ay bee cee" (5 words) vs reference "n9abc" (1 word): every hypothesis
    # word differs from the single reference word, so the word-level edit distance is exactly the
    # hypothesis length (substitute one, delete the other four) -> wer = 5 / 1 reference word.
    assert result["pairs"] == 1
    assert result["wer"] == pytest.approx(5.0)


def test_field_wer_with_no_tier2_data_is_honestly_empty(tmp_path):
    partition = tmp_path / "schema-1"
    partition.mkdir()
    (partition / "2026-09-19.ndjson").write_text(
        '{"fold": "field", "tier": "TIER_1", '
        '"provenance": {"schemaVersion": 1}, '
        '"payload": {"payloadType": "tier1_usage", "screen": "X", "action": "Y"}}\n',
        encoding="utf-8",
    )
    connection = duckdb.connect(":memory:")
    loader.load_events(connection, tmp_path)

    result = queries.field_wer(connection)

    assert result == {"pairs": 0, "wer": None}


@pytest.mark.parametrize(
    "hyp,ref,expected",
    [
        (["a", "b", "c"], ["a", "b", "c"], 0),
        ([], ["a"], 1),
        (["a"], [], 1),
        (["a", "b"], ["b", "a"], 2),
    ],
)
def test_word_edit_distance(hyp, ref, expected):
    assert queries._word_edit_distance(hyp, ref) == expected
