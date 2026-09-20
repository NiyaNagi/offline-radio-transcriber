"""The `ort-analytics` CLI's `report` subcommand — the ingest server itself is exercised directly
by `test_server.py` (a `serve` invocation would block forever, which is not this test's job)."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import cli
import loader

FIXTURE_DATA_DIR = Path(__file__).resolve().parent / "fixtures" / "data"


def test_report_command_writes_the_report_to_the_given_file(tmp_path):
    out = tmp_path / "report.txt"

    cli.main(["report", "--data-dir", str(FIXTURE_DATA_DIR), "--out", str(out)])

    text = out.read_text(encoding="utf-8")
    assert "# Analytics report (field fold)" in text


def test_report_command_prints_to_stdout_when_no_out_is_given(capsys):
    cli.main(["report", "--data-dir", str(FIXTURE_DATA_DIR)])

    captured = capsys.readouterr()
    assert "# Analytics report (field fold)" in captured.out


def test_report_command_refuses_a_non_field_fold(tmp_path):
    out = tmp_path / "report.txt"

    with pytest.raises(loader.FoldViolation):
        cli.main(["report", "--data-dir", str(FIXTURE_DATA_DIR), "--fold", "eval", "--out", str(out)])

    assert not out.exists()
