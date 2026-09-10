"""Tests for tools/release_notes.py (RELEASING.md's own gate before a release is cut).

Follows the same shape as tools/spec-check/tests/test_spec_check.py: import the script directly
by inserting its directory on sys.path, exercise its pure functions against synthetic text, and
separately prove the real, committed RELEASES.md actually satisfies the contract.
"""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_notes  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]

SAMPLE = """# Releases

## Unreleased

### New
- a thing that isn't out yet

## v0.1.2 — 2026-10-01

### Fixed
- squashed a bug

## v0.1.1 — 2026-09-09

### New
- the first release
"""


def test_the_real_releases_file_has_unreleased_and_v0_1_1():
    text = (REPO_ROOT / "RELEASES.md").read_text(encoding="utf-8")

    unreleased = release_notes.notes_for(text, "Unreleased")
    versioned = release_notes.notes_for(text, "v0.1.1")

    assert "CHANGELOG.md" in unreleased
    assert "CHANGELOG.md" in versioned
    # the human-readable file must never leak the engineering register's own vocabulary
    for leaked in ("R-", "FR-", "AC-", "WP1", "WP2", "WP3"):
        assert leaked not in versioned, f"{leaked!r} leaked into the v0.1.1 release notes"


def test_unreleased_is_the_default_when_no_version_is_given():
    assert release_notes.notes_for(SAMPLE) == release_notes.notes_for(SAMPLE, "Unreleased")


def test_a_versioned_section_returns_only_its_own_body():
    body = release_notes.notes_for(SAMPLE, "v0.1.2")

    assert "squashed a bug" in body
    assert "the first release" not in body
    assert "a thing that isn't out yet" not in body


def test_commit_range_chains_to_the_next_older_version():
    assert "v0.1.2..HEAD" in release_notes.notes_for(SAMPLE, "Unreleased")
    assert "v0.1.1..v0.1.2" in release_notes.notes_for(SAMPLE, "v0.1.2")
    assert "up to v0.1.1" in release_notes.notes_for(SAMPLE, "v0.1.1")


def test_an_unknown_version_raises_and_lists_what_exists():
    with pytest.raises(release_notes.UnknownVersionError) as exc_info:
        release_notes.notes_for(SAMPLE, "v9.9.9")

    message = str(exc_info.value)
    assert "v9.9.9" in message
    assert "v0.1.1" in message
    assert "v0.1.2" in message
    assert "Unreleased" in message


def test_an_empty_section_still_renders_a_footer_not_a_crash():
    text = "## Unreleased\n\n## v0.1.0 — 2026-01-01\n\nnothing\n"

    body = release_notes.notes_for(text, "Unreleased")

    assert "no notes recorded" in body
    assert "CHANGELOG.md" in body


def test_main_exits_nonzero_and_prints_known_versions_for_a_bogus_version(tmp_path, monkeypatch, capsys):
    (tmp_path / "RELEASES.md").write_text(SAMPLE, encoding="utf-8")
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(release_notes, "RELEASES_PATH", Path("RELEASES.md"))

    exit_code = release_notes.main(["release_notes.py", "v9.9.9"])

    assert exit_code == 1
    assert "v9.9.9" in capsys.readouterr().err


def test_main_with_no_argument_prints_unreleased(tmp_path, monkeypatch, capsys):
    (tmp_path / "RELEASES.md").write_text(SAMPLE, encoding="utf-8")
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(release_notes, "RELEASES_PATH", Path("RELEASES.md"))

    exit_code = release_notes.main(["release_notes.py"])

    out = capsys.readouterr().out
    assert exit_code == 0
    assert "a thing that isn't out yet" in out


def test_main_reports_a_missing_releases_file_instead_of_crashing(tmp_path, monkeypatch, capsys):
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(release_notes, "RELEASES_PATH", Path("RELEASES.md"))

    exit_code = release_notes.main(["release_notes.py"])

    assert exit_code == 1
    assert "not found" in capsys.readouterr().err
