"""Meta-guard for the spec-integrity checker (build-plan P1: "write first").

The checker must FAIL on a deliberately introduced dangling reference before it is trusted
to protect the spec set from real ones.
"""
import shutil
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import spec_check  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[3]
SPEC_DIR = REPO_ROOT / "spec"


def test_the_real_spec_set_passes_every_check():
    result = spec_check.run_checks(SPEC_DIR)
    failed = [name for name, ok, _ in result.checks if not ok]
    assert result.ok, f"spec-check regressed on the committed specs: {failed}\n{result.render()}"


def test_a_deliberate_dangling_reference_is_caught(tmp_path):
    work = tmp_path / "spec"
    shutil.copytree(SPEC_DIR, work)
    poisoned = work / "functional-spec.md"
    poisoned.write_text(
        poisoned.read_text(encoding="utf-8")
        + "\n\nDeliberate defect for the meta-guard: see AC-9999 and FR-GHOST-1.\n",
        encoding="utf-8",
    )

    result = spec_check.run_checks(work)

    assert not result.ok
    dangling = next(problems for name, _, problems in result.checks if name.startswith("2."))
    assert any("AC-9999" in p for p in dangling)
    assert any("FR-GHOST-1" in p for p in dangling)


def test_a_tab_character_is_caught(tmp_path):
    work = tmp_path / "spec"
    shutil.copytree(SPEC_DIR, work)
    (work / "test-plan.md").write_text("col1\tcol2\n", encoding="utf-8")

    result = spec_check.run_checks(work)
    assert not result.ok


@pytest.mark.parametrize("marker", ["<" * 7 + " HEAD", "=" * 7, ">" * 7 + " some-branch"])
def test_an_unresolved_conflict_marker_is_caught(tmp_path, marker):
    """The meta-guard for check 8, which exists because a real one reached `main`.

    A hand-resolved merge left a stray `>>>>>>>` line in build-plan.md that survived four commits;
    every other check passed throughout, because none of them read the text as text.
    """
    work = tmp_path / "spec"
    shutil.copytree(SPEC_DIR, work)
    target = work / "build-plan.md"
    target.write_text(target.read_text(encoding="utf-8") + f"\n{marker}\n", encoding="utf-8")

    result = spec_check.run_checks(work)

    assert not result.ok
    problems = next(problems for name, _, problems in result.checks if name.startswith("8."))
    assert any("build-plan.md" in p for p in problems), problems


def test_a_markdown_heading_underline_is_not_a_conflict_marker(tmp_path):
    """Seven-plus `=` under a heading is setext markdown, not a marker — the false positive that
    a first draft of check 8 produced against constitution.md, and the reason it matches an
    exactly-seven-character `=======` alone on its line rather than a prefix."""
    work = tmp_path / "spec"
    shutil.copytree(SPEC_DIR, work)
    target = work / "build-plan.md"
    target.write_text(
        target.read_text(encoding="utf-8") + "\nA setext heading\n" + "=" * 18 + "\n",
        encoding="utf-8",
    )

    result = spec_check.run_checks(work)

    problems = next(problems for name, _, problems in result.checks if name.startswith("8."))
    assert problems == [], f"a markdown heading underline must not be flagged: {problems}"


def test_a_missing_ac_number_breaks_contiguity(tmp_path):
    work = tmp_path / "spec"
    shutil.copytree(SPEC_DIR, work)
    fs = work / "functional-spec.md"
    fs.write_text(fs.read_text(encoding="utf-8").replace("**AC-2**", "**AC-2000**", 1), encoding="utf-8")

    result = spec_check.run_checks(work)
    contiguity = next(problems for name, _, problems in result.checks if name.startswith("1."))
    assert contiguity


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-q"]))
