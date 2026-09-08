#!/usr/bin/env python3
"""Spec-integrity checks for the specification set (test-plan §8.1).

Seven checks, all cheap, run on every push (constitution: "Spec-integrity checks are part of
CI"). Four of the last six defects found by hand would have been caught here.

    1. AC ids are contiguous from 1 and each is defined exactly once.
    2. No dangling FR- / AC- / NFR- / CON- / Q reference.
    3. Every decision Dn has a §16 traceability row.
    4. Every requirement group (FR-<AREA>) has at least one acceptance criterion.
    5. Every acceptance criterion names at least one requirement.
    6. Every closed question maps to a decision, and no open question does.
    7. No mojibake and no tab characters in any spec file.

Exit code 0 iff every check passes. Usage: ``python spec_check.py [SPEC_DIR]``.
"""
from __future__ import annotations

import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SPEC_DIR = REPO_ROOT / "spec"

FUNCTIONAL = "functional-spec.md"

# ---------------------------------------------------------------------------------------------
# id vocabulary
# ---------------------------------------------------------------------------------------------

RE_AC = re.compile(r"\bAC-(\d+)\b")
RE_AC_DEF = re.compile(r"\*\*AC-(\d+)\*\*")
RE_FR = re.compile(r"\bFR-([A-Z0-9]+)-(\d+[a-z]?)\b")
RE_NFR = re.compile(r"\bNFR-(\d+[a-z]?)\b")
RE_CON = re.compile(r"\bCON-([A-Z]+)-(\d+)\b")
RE_Q = re.compile(r"\bQ(\d+)\b")
RE_D = re.compile(r"\bD(\d+)\b")

# a range such as FR-RUN-1..18, AC-104..110, FR-SPK-11..26
RE_FR_RANGE = re.compile(r"\bFR-([A-Z0-9]+)-(\d+)\.\.(\d+)\b")
RE_AC_RANGE = re.compile(r"\bAC-(\d+)\.\.(\d+)\b")
RE_Q_RANGE = re.compile(r"\bQ(\d+)[–—-]Q?(\d+)\b")
RE_D_RANGE = re.compile(r"\bD(\d+)[–—-]D?(\d+)\b")

# something that reads as "this criterion names a requirement"
RE_NAMES_REQUIREMENT = re.compile(
    r"(FR-[A-Z0-9]+-\d+|NFR-\d+|CON-[A-Z]+-\d+|\bP\d+\b|\bF\d+\b|\bAC-\d+\b|§\d+)"
)

MOJIBAKE = [
    "�",
    "Ã¢", "Ã©", "Ã¨", "Ã¯", "Ã´", "Ã¶", "Ã¼", "Ã±", "Ã‚",
    "â€™", "â€œ", "â€\x9d", "â€”", "â€“", "â€¦", "Â·", "Â ", "Â»", "Â«",
]


@dataclass
class Result:
    checks: list[tuple[str, bool, list[str]]] = field(default_factory=list)

    def record(self, name: str, problems: list[str]) -> None:
        self.checks.append((name, not problems, problems))

    @property
    def ok(self) -> bool:
        return all(passed for _, passed, _ in self.checks)

    def render(self) -> str:
        out = []
        for name, passed, problems in self.checks:
            out.append(f"[{'PASS' if passed else 'FAIL'}] {name}")
            for p in problems:
                out.append(f"       - {p}")
        out.append("")
        out.append("spec-check: OK" if self.ok else f"spec-check: {sum(1 for _, p, _ in self.checks if not p)} check(s) failed")
        return "\n".join(out)


# ---------------------------------------------------------------------------------------------

def _read(spec_dir: Path) -> dict[str, str]:
    """Just the spec set — the structural checks reason only about these."""
    return {md.name: md.read_text(encoding="utf-8") for md in sorted(spec_dir.glob("*.md"))}


def _read_for_encoding(spec_dir: Path) -> dict[str, str]:
    """A wider net for the encoding check: specs plus the checked-in prose around them."""
    files = _read(spec_dir)
    for extra in [REPO_ROOT / "README.md", REPO_ROOT / "AGENTS.md", REPO_ROOT / "CLAUDE.md",
                  REPO_ROOT / ".specify" / "memory" / "constitution.md",
                  REPO_ROOT / "docs" / "reference" / "th-d75a-cat.md"]:
        if extra.exists():
            files[str(extra.relative_to(REPO_ROOT))] = extra.read_text(encoding="utf-8")
    return files


def _section(text: str, header_regex: str) -> str:
    """Return the text from a `## N. ...` style header to the next same-or-higher header."""
    m = re.search(header_regex, text, re.MULTILINE)
    if not m:
        return ""
    start = m.start()
    level = len(re.match(r"#+", text[start:]).group(0))
    tail = text[m.end():]
    nxt = re.search(rf"^#{{1,{level}}}\s", tail, re.MULTILINE)
    return text[start:] if not nxt else text[start: m.end() + nxt.start()]


def _expand_ranges(text: str) -> set[str]:
    ids: set[str] = set()
    for area, lo, hi in RE_FR_RANGE.findall(text):
        ids.update(f"FR-{area}-{n}" for n in range(int(lo), int(hi) + 1))
    for lo, hi in RE_AC_RANGE.findall(text):
        ids.update(f"AC-{n}" for n in range(int(lo), int(hi) + 1))
    return ids


# ---------------------------------------------------------------------------------------------
# the seven checks
# ---------------------------------------------------------------------------------------------

def check_ac_contiguous(files: dict[str, str], r: Result) -> None:
    text = files[FUNCTIONAL]
    section = _section(text, r"^## 14\. Acceptance criteria")
    defined = [int(n) for n in RE_AC_DEF.findall(section)]
    problems: list[str] = []
    seen: set[int] = set()
    for n in defined:
        if n in seen:
            problems.append(f"AC-{n} is defined more than once in §14")
        seen.add(n)
    if seen:
        hi = max(seen)
        missing = sorted(set(range(1, hi + 1)) - seen)
        if missing:
            problems.append(f"§14 is not contiguous: missing {', '.join(f'AC-{m}' for m in missing)}")
    else:
        problems.append("no `**AC-n**` definitions found in §14")
    r.record("1. AC ids contiguous and unique", problems)


def _defined_ids(files: dict[str, str]) -> set[str]:
    defined: set[str] = set()
    for name, text in files.items():
        for area, num in RE_FR.findall(text):
            # an FR is "defined" wherever it appears bold or with a MoSCoW tag
            if re.search(rf"\*\*FR-{area}-{num}\b|\bFR-{area}-{num} \((M|S|C|D)\)", text):
                defined.add(f"FR-{area}-{num}")
        for num in RE_AC_DEF.findall(text):
            defined.add(f"AC-{num}")
        for num in RE_NFR.findall(text):
            if re.search(rf"\*\*NFR-{num}\b", text):
                defined.add(f"NFR-{num}")
        for area, num in RE_CON.findall(text):
            if re.search(rf"\*\*CON-{area}-{num}\b", text):
                defined.add(f"CON-{area}-{num}")
    # questions: defined by open-questions.md (a heading, a table row, or bold)
    oq = files.get("open-questions.md", "")
    for num in set(RE_Q.findall(oq)):
        defined.add(f"Q{num}")
    for lo, hi in RE_Q_RANGE.findall(oq):
        defined.update(f"Q{n}" for n in range(int(lo), int(hi) + 1))
    return defined


def check_no_dangling(files: dict[str, str], r: Result) -> None:
    defined = _defined_ids(files)
    problems: list[str] = []
    for name, text in files.items():
        referenced: set[str] = set()
        referenced.update(f"AC-{n}" for n in RE_AC.findall(text))
        referenced.update(f"FR-{a}-{n}" for a, n in RE_FR.findall(text))
        referenced.update(f"NFR-{n}" for n in RE_NFR.findall(text))
        referenced.update(f"CON-{a}-{n}" for a, n in RE_CON.findall(text))
        referenced.update(f"Q{n}" for n in RE_Q.findall(text))
        referenced.update(_expand_ranges(text))
        for lo, hi in RE_Q_RANGE.findall(text):
            referenced.update(f"Q{n}" for n in range(int(lo), int(hi) + 1))
        for ident in sorted(referenced - defined):
            problems.append(f"{name}: reference to {ident} which is defined nowhere")
    r.record("2. no dangling FR/AC/NFR/CON/Q reference", problems)


def check_decisions_traced(files: dict[str, str], r: Result) -> None:
    text = files[FUNCTIONAL]
    decisions_section = _section(text, r"^## 3\. ")
    trace_section = _section(text, r"^## 16\. Traceability")
    declared = {int(n) for n in re.findall(r"^\| D(\d+) \|", decisions_section, re.MULTILINE)}
    traced = {int(n) for n in re.findall(r"\| D(\d+) ", trace_section)}
    problems = [f"D{n} has no row in §16 Traceability" for n in sorted(declared - traced)]
    if not declared:
        problems.append("no decisions found in §3")
    r.record("3. every decision Dn has a §16 traceability row", problems)


def check_groups_have_criteria(files: dict[str, str], r: Result) -> None:
    text = files[FUNCTIONAL]
    ac_section = _section(text, r"^## 14\. Acceptance criteria")
    req_section = text.split("## 14.")[0]
    groups = {a for a, _ in RE_FR.findall(req_section)
              if re.search(rf"\*\*FR-{a}-\d", req_section)}
    covered = {a for a, _ in RE_FR.findall(ac_section)}
    covered.update(a for a, _lo, _hi in RE_FR_RANGE.findall(ac_section))
    problems = [f"requirement group FR-{a} has no acceptance criterion in §14"
                for a in sorted(groups - covered)]
    r.record("4. every requirement group has ≥1 criterion", problems)


def check_criteria_name_requirements(files: dict[str, str], r: Result) -> None:
    text = files[FUNCTIONAL]
    section = _section(text, r"^## 14\. Acceptance criteria")
    outside = "\n".join(
        (t.replace(section, "") if name == FUNCTIONAL else t) for name, t in files.items()
    )
    linked_from_elsewhere = {int(n) for n in RE_AC.findall(outside)}
    problems: list[str] = []
    # each bullet: from `- **AC-n**` to the next `- **AC-` or blank-line-then-header
    for m in re.finditer(r"- \*\*AC-(\d+)\*\*(.*?)(?=\n- \*\*AC-|\n#|\Z)", section, re.DOTALL):
        n, body = int(m.group(1)), m.group(2)
        names_one = bool(RE_NAMES_REQUIREMENT.search(body))
        if not names_one and n not in linked_from_elsewhere:
            problems.append(
                f"AC-{n} is an orphan: it names no requirement and no requirement, "
                f"section or test names it"
            )
    r.record("5. every criterion is linked to a requirement", problems)


def check_questions_and_decisions(files: dict[str, str], r: Result) -> None:
    oq = files.get("open-questions.md", "")
    functional = files[FUNCTIONAL]
    problems: list[str] = []

    closed: set[int] = set()
    m = re.search(r"\*\*Closed:\*\*\s*(.+)", oq)
    if m:
        line = m.group(1)
        closed.update(int(x) for x in RE_Q.findall(line))
        for lo, hi in RE_Q_RANGE.findall(line):
            closed.update(range(int(lo), int(hi) + 1))

    open_qs: set[int] = set()
    for row in re.finditer(r"^\| \*\*Q(\d+)\*\* \|", oq, re.MULTILINE):
        open_qs.add(int(row.group(1)))

    for q in sorted(closed):
        if not re.search(rf"\bQ{q}\b", functional):
            problems.append(f"Q{q} is marked closed but is referenced nowhere in {FUNCTIONAL}")
    for q in sorted(open_qs & closed):
        problems.append(f"Q{q} is listed as both open and closed")
    if not closed and not open_qs:
        problems.append("could not parse the open/closed question register")
    r.record("6. closed questions map to decisions, open ones do not", problems)


def check_encoding(files: dict[str, str], r: Result) -> None:
    problems: list[str] = []
    for name, text in files.items():
        if "\t" in text:
            lines = [i + 1 for i, ln in enumerate(text.splitlines()) if "\t" in ln]
            problems.append(f"{name}: tab character on line(s) {lines[:10]}")
        for token in MOJIBAKE:
            if token in text:
                problems.append(f"{name}: mojibake sequence {token!r} present")
    r.record("7. no mojibake, no tab characters", problems)


# Exactly seven `<`/`=`/`>` — git's own marker width — and nothing wider. The width matters:
# a first draft of this check used "starts with seven `=`" and immediately flagged
# constitution.md's markdown setext heading underline (`==================`), which is precisely
# the kind of false positive that gets a check switched off rather than fixed. `<<<<<<<` and
# `>>>>>>>` carry a branch label after a space; `=======` stands alone on its line.
# Built at runtime from repeated characters so this file cannot match itself.
_CONFLICT_MARKER = re.compile(
    rf"^(?:{'<' * 7}|{'>' * 7})(?: .*)?$|^{'=' * 7}$",
)


def check_no_conflict_markers(files: dict[str, str], r: Result) -> None:
    """No unresolved git merge-conflict marker survives into a spec document.

    Added 2026-09-08 after one did: a hand-resolved merge left a stray `>>>>>>>` line in
    build-plan.md that survived four commits on `main` before another merge happened to remove
    it. Every other check here passed the whole time, because none of them look at the text as
    text. A conflict marker in a spec is a small, purely mechanical defect that is invisible in a
    rendered diff and obvious to a machine — exactly the kind this checker exists to catch, and
    the structural fix for a mistake that "be more careful next time" does not prevent.
    """
    problems: list[str] = []
    for name, text in files.items():
        for i, line in enumerate(text.splitlines(), start=1):
            if _CONFLICT_MARKER.match(line):
                problems.append(f"{name}: unresolved merge-conflict marker on line {i}: {line[:40]!r}")
    r.record("8. no unresolved merge-conflict markers", problems)


CHECKS = [
    check_ac_contiguous,
    check_no_dangling,
    check_decisions_traced,
    check_groups_have_criteria,
    check_criteria_name_requirements,
    check_questions_and_decisions,
]


def run_checks(spec_dir: Path = DEFAULT_SPEC_DIR) -> Result:
    files = _read(spec_dir)
    if FUNCTIONAL not in files:
        result = Result()
        result.record("0. functional spec present", [f"{FUNCTIONAL} not found in {spec_dir}"])
        return result
    result = Result()
    for check in CHECKS:
        check(files, result)
    check_encoding(_read_for_encoding(spec_dir), result)
    check_no_conflict_markers(_read_for_encoding(spec_dir), result)
    return result


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    spec_dir = Path(argv[0]) if argv else DEFAULT_SPEC_DIR
    result = run_checks(spec_dir)
    print(result.render())
    return 0 if result.ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
