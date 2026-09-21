"""Generate results/backlog.md: one prioritised view of every open item.

The backlog is *generated*, never hand-edited, so it cannot drift from the surfaces
that own the truth:

  results/ui-audit/register.md          every finding, one row, lead-owned
  results/e2e-audit/checklist.md        the capture-modes checklist
  results/e2e-audit/hardware-checklist.md  H1-H15, closed by a file under hardware/
  spec/build-plan.md                    the units, closed by a ticked checkbox
  spec/open-questions.md                the questions still open
  results/coverage-matrix.md            requirement ids with no test

Usage:
  python tools/backlog/backlog.py            # rewrite results/backlog.md
  python tools/backlog/backlog.py --check    # fail if the file is stale (CI)
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "results" / "backlog.md"

REGISTER = ROOT / "results" / "ui-audit" / "register.md"
E2E = ROOT / "results" / "e2e-audit" / "checklist.md"
HARDWARE = ROOT / "results" / "e2e-audit" / "hardware-checklist.md"
HARDWARE_DIR = ROOT / "results" / "e2e-audit" / "hardware"
BUILD_PLAN = ROOT / "spec" / "build-plan.md"
QUESTIONS = ROOT / "spec" / "open-questions.md"
COVERAGE = ROOT / "results" / "coverage-matrix.md"

# Units that close the beta gate. Kept here, beside the generator, because the
# build plan states the wave and this states the priority.
BETA_UNITS = {"P33", "P34", "P35", "P36", "P37", "P38"}
DONE_STATUSES = {"closed", "rejected", "superseded", "tombstone"}


@dataclass
class Item:
    ident: str
    title: str
    source: str
    state: str
    priority: int
    note: str = ""
    refs: str = ""


@dataclass
class Source:
    name: str
    items: list[Item] = field(default_factory=list)
    warning: str = ""


def read(path: Path) -> list[str]:
    if not path.exists():
        return []
    return path.read_text(encoding="utf-8", errors="replace").splitlines()


def cells(line: str) -> list[str]:
    return [c.strip() for c in line.strip().strip("|").split("|")]


def is_done(state: str) -> bool:
    """The status cell often carries prose: 'closed - fixed - WPD abc123; run 3 confirmed'.

    A cell that reopens the row says so, and outranks the 'closed' it starts with.
    """
    state = state.strip().lower()
    if "reopen" in state:
        return False
    return state.startswith(tuple(DONE_STATUSES))


def register() -> Source:
    src = Source("UI / defect register")
    rows = 0
    for line in read(REGISTER):
        if not re.match(r"^\|\s*R-\d+\s*\|", line):
            continue
        rows += 1
        c = cells(line)
        if len(c) < 6:
            continue
        ident, title, refs, severity, state = c[0], c[1], c[2], c[-2].lower(), c[-1].lower()
        if is_done(state):
            continue
        # The register's own header: `fixed` means a builder reported it closed and the
        # lead has not confirmed on evidence. That is open, and worth saying so.
        state = "fixed / awaiting capture" if state.startswith("fixed") else state.split(" - ")[0]
        # halt = wrong or misleading to the operator; that is always first.
        priority = 0 if severity == "halt" else (1 if severity == "spec" else 2)
        src.items.append(Item(ident, title, "register", f"{severity}/{state}", priority, refs=refs))
    if not rows:
        src.warning = "no rows matched; the register's row shape may have changed"
    return src


def checklist() -> Source:
    src = Source("Capture-modes checklist")
    rows = 0
    for line in read(E2E):
        if not re.match(r"^\|\s*E2-[A-Z]?\d+", line):
            continue
        rows += 1
        c = cells(line)
        if len(c) < 3:
            continue
        state = c[-1].lower()
        if state in DONE_STATUSES or state.startswith("closed"):
            continue
        src.items.append(Item(c[0], c[1], "e2e", state or "open", 1))
    if not rows:
        src.warning = "no rows matched; the checklist's row shape may have changed"
    return src


def hardware() -> Source:
    """H rows are closed by a result file, never by a tick in the checklist."""
    src = Source("Hardware protocol")
    ids: list[str] = []
    for line in read(HARDWARE):
        for m in re.finditer(r"\bH(\d{1,2})\b", line):
            ident = f"H{m.group(1)}"
            if ident not in ids:
                ids.append(ident)
    for ident in sorted(ids, key=lambda s: int(s[1:])):
        result = HARDWARE_DIR / f"{ident}.md"
        if result.exists():
            continue
        src.items.append(
            Item(ident, "protocol step never run", "hardware", "unrun", 0,
                 note="no results/e2e-audit/hardware/%s.md" % ident))
    if not ids:
        src.warning = "no H ids found in the hardware checklist"
    return src


def build_plan() -> Source:
    src = Source("Build-plan units")
    seen: set[str] = set()
    for line in read(BUILD_PLAN):
        m = re.match(r"^- \[( |x)\] \*\*(P\d+)\s*[·.]\s*(.+?)\*\*", line)
        if not m:
            continue
        ticked, ident, title = m.group(1) == "x", m.group(2), m.group(3)
        if ident in seen:
            continue
        seen.add(ident)
        if ticked:
            continue
        priority = 0 if ident in BETA_UNITS else 1
        note = "beta gate" if ident in BETA_UNITS else ""
        src.items.append(Item(ident, title, "build-plan", "not started", priority, note=note))
    if not seen:
        src.warning = "no units matched; the build plan's checkbox shape may have changed"
    return src


def questions() -> Source:
    src = Source("Open questions")
    for line in read(QUESTIONS):
        m = re.match(r"^\|\s*(Q\d+|T\d+)\s*\|", line)
        if not m:
            continue
        c = cells(line)
        blob = " ".join(c).lower()
        if "closed" in blob or "answered" in blob or "decided" in blob:
            continue
        title = c[1] if len(c) > 1 else ""
        blocking = "blocking" in blob
        src.items.append(Item(c[0], title, "questions", "blocking" if blocking else "open",
                              0 if blocking else 2))
    return src


def coverage() -> Source:
    src = Source("Coverage matrix")
    uncovered = orphans = 0
    for line in read(COVERAGE):
        m = re.match(r"^\|\s*Not yet covered\s*\|\s*(\d+)\s*\|", line, re.I)
        if m:
            uncovered = int(m.group(1))
        m = re.match(r"^\|\s*Tests naming a requirement id not in the spec\s*\|\s*(\d+)\s*\|", line, re.I)
        if m:
            orphans = int(m.group(1))
    if uncovered:
        src.items.append(
            Item("coverage-uncovered", f"{uncovered} requirement ids have no test", "coverage",
                 "open", 2, note="./gradlew coverageMatrix"))
    if orphans:
        src.items.append(
            Item("coverage-orphans", f"{orphans} tests name a requirement id the spec does not have",
                 "coverage", "open", 2, note="./gradlew coverageMatrix"))
    if not uncovered and not orphans:
        src.warning = "no summary rows matched; the coverage matrix's shape may have changed"
    return src


PRIORITY_NAMES = {
    0: "P0 — blocks the beta, or is wrong in front of the operator",
    1: "P1 — must be true for 1.0",
    2: "P2 — after the beta, or polish",
}


def render(sources: list[Source]) -> str:
    items = [i for s in sources for i in s.items]
    lines: list[str] = []
    a = lines.append
    a("# Backlog — every open item, one view")
    a("")
    a("**Generated** by `tools/backlog/backlog.py`. Do not edit by hand: file the item on the")
    a("surface that owns it (the register, a checklist, the build plan, the questions) and")
    a("regenerate. `--check` fails when this file is stale, so the view cannot drift.")
    a("")
    a("Priority is derived, not assigned: a register row of severity `halt`, an unrun hardware")
    a("step and a beta-gate build unit are P0; `spec` rows and remaining units are P1; design,")
    a("process, polish, open questions and coverage debt are P2.")
    a("")
    a("| Source | Open | P0 | P1 | P2 |")
    a("|---|---:|---:|---:|---:|")
    for s in sources:
        n = len(s.items)
        a(f"| {s.name} | {n} | {sum(1 for i in s.items if i.priority == 0)} "
          f"| {sum(1 for i in s.items if i.priority == 1)} "
          f"| {sum(1 for i in s.items if i.priority == 2)} |")
    a(f"| **Total** | **{len(items)}** "
      f"| **{sum(1 for i in items if i.priority == 0)}** "
      f"| **{sum(1 for i in items if i.priority == 1)}** "
      f"| **{sum(1 for i in items if i.priority == 2)}** |")
    a("")
    warnings = [s for s in sources if s.warning]
    if warnings:
        a("**Parser warnings** — a surface changed shape and may be under-reported:")
        a("")
        for s in warnings:
            a(f"- {s.name}: {s.warning}")
        a("")
    for priority in (0, 1, 2):
        chosen = [i for i in items if i.priority == priority]
        if not chosen:
            continue
        a(f"## {PRIORITY_NAMES[priority]}")
        a("")
        a("| id | what | source | state | refs / note |")
        a("|---|---|---|---|---|")
        for i in sorted(chosen, key=lambda x: (x.source, _sort_key(x.ident))):
            tail = i.note or i.refs
            a(f"| {i.ident} | {i.title} | {i.source} | {i.state} | {tail} |")
        a("")
    return "\n".join(lines).rstrip() + "\n"


def _sort_key(ident: str):
    m = re.match(r"^([A-Za-z-]*?)(\d+)", ident)
    return (m.group(1), int(m.group(2))) if m else (ident, 0)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="fail if results/backlog.md is stale")
    args = ap.parse_args()

    sources = [register(), hardware(), build_plan(), checklist(), questions(), coverage()]
    rendered = render(sources)

    if args.check:
        current = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if current != rendered:
            print("backlog: results/backlog.md is stale — run python tools/backlog/backlog.py",
                  file=sys.stderr)
            return 1
        print("backlog: up to date")
        return 0

    OUT.write_text(rendered, encoding="utf-8", newline="\n")
    total = sum(len(s.items) for s in sources)
    print(f"backlog: wrote {OUT.relative_to(ROOT)} — {total} open items")
    for s in sources:
        if s.warning:
            print(f"  warning: {s.name}: {s.warning}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
