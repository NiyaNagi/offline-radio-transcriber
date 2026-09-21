#!/usr/bin/env python3
"""spec/ui-conformance-plan.md WP12 - the decision logic `tools/ui-audit/tour.ps1` polls and
validates against, pulled out so it can be tested (R-1103, register).

R-1083 fixed a stale-or-foreign tour manifest being reported as a successful run, entirely inline
in `tour.ps1`'s polling loop and its post-pull step/screenshot counts - decision logic tangled up
with PowerShell orchestration (adb, base64 pulls, sleeps) that cannot run without a real device.
R-1103 is that this repository has no PowerShell test harness, so that fix (and the
fewer-screenshots-than-steps check next to it) was covered only by a manual reproduction. Rather
than stand up a second test framework (Pester) for two functions' worth of pure JSON-parsing
logic, this follows the pattern `tools/ui-audit/diff.py` already established the same night for
the same reason (R-1116): move the decision into a plain Python module with no device dependency,
have `tour.ps1` shell out to it for every decision point instead of re-implementing the same
parsing twice inline, and let the existing `ui-audit-diff` CI job
(`.github/workflows/ci.yml`, `python -m pytest tools/ui-audit -q`) exercise it. `tour.ps1` still
owns everything that genuinely cannot run off-device (adb, `am start`, the base64 pull loop,
sleeping between polls) - only the three decisions this row names are here.

Three entry points, each also reachable from `tour.ps1` as a CLI subcommand (see `main()` below)
so the script never re-implements the parsing PowerShell-side once "moved":

- ``check_done_marker(manifest_text, run_id)`` - given one poll's raw manifest text and this
  invocation's own run id, decide whether the run is actually finished, distinguishing a
  stale-or-foreign completed run (some *other* run's own "done" line) from "not yet".
- ``summarize(manifest_text)`` - parse every non-"done" line into ok/error counts and the list of
  ok step ids to pull, once, rather than the two independent inline parses `tour.ps1` used to do
  (one for the summary print, one for the pull loop).
- ``verify_step_count`` / ``verify_pulled_count`` - the two "fewer results than asked for" guards:
  a manifest reporting fewer step results than were requested, and fewer screenshots actually
  pulled to disk than the manifest claimed were `ok`. Both used to print a quiet warning and exit
  0; `tour.ps1` (R-1083) now throws on either, and these are the same throw-worthy decisions in
  Python so they can be tested off-device.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, field
from typing import Any

_DONE_MARKER = re.compile(r'"done"\s*:\s*true')


@dataclass(frozen=True)
class DoneResult:
    done: bool
    foreign_run_id: str | None = None


@dataclass(frozen=True)
class ManifestSummary:
    step_count: int
    ok_count: int
    error_count: int
    ok_ids: list[str]
    errors: list[dict[str, Any]] = field(default_factory=list)


class StepCountMismatch(Exception):
    """R-1103/R-1083: the manifest reported fewer (or more) step results than this invocation
    actually requested - the device produced a different number of results than asked for."""


class PulledCountMismatch(Exception):
    """R-1103/R-1083: the manifest claimed N screenshots were `ok`, but fewer than N were
    actually pulled to disk - a base64 pull failure that only warned, one too many times."""


def _non_empty_lines(manifest_text: str) -> list[str]:
    return [line for line in manifest_text.split("\n") if line.strip()]


def check_done_marker(manifest_text: str, run_id: str) -> DoneResult:
    """Mirrors tour.ps1's own pre-R-1103 inline polling logic exactly (the .NOTES section of that
    script describes the shape this replicates): look only at the *last* non-empty line - a
    concurrent append can leave earlier lines mid-write, but `done` is always the trailing line
    once TourRunner truly finishes - and only if it looks like a done marker at all. A malformed
    trailing line (caught mid-write) is "not yet", never a crash: the next poll tick reads the
    completed file. A done line naming a *different* run's own id is reported as foreign, never as
    this run having finished - the exact R-1083 defect this module exists to keep fixed."""
    lines = _non_empty_lines(manifest_text)
    if not lines:
        return DoneResult(done=False)
    last_line = lines[-1]
    if not _DONE_MARKER.search(last_line):
        return DoneResult(done=False)
    try:
        done_line = json.loads(last_line)
    except json.JSONDecodeError:
        return DoneResult(done=False)
    line_run_id = done_line.get("runId")
    if line_run_id == run_id:
        return DoneResult(done=True)
    if line_run_id:
        return DoneResult(done=False, foreign_run_id=line_run_id)
    return DoneResult(done=False)


def summarize(manifest_text: str) -> ManifestSummary:
    """Parse every non-"done" line once. Malformed lines are skipped rather than raised - the
    manifest is only ever summarized after `check_done_marker` has already confirmed this run's
    own trailing "done" line parsed cleanly, so a malformed *earlier* line here is the same
    mid-write race `check_done_marker` already tolerates, not a new failure mode to invent."""
    ok_ids: list[str] = []
    errors: list[dict[str, Any]] = []
    step_count = 0
    for line in _non_empty_lines(manifest_text):
        try:
            entry = json.loads(line)
        except json.JSONDecodeError:
            continue
        if entry.get("done"):
            continue
        step_count += 1
        if entry.get("ok") is True:
            ok_ids.append(entry["id"])
        elif entry.get("ok") is False:
            errors.append({"id": entry.get("id"), "errorMessage": entry.get("errorMessage")})
    return ManifestSummary(
        step_count=step_count,
        ok_count=len(ok_ids),
        error_count=len(errors),
        ok_ids=ok_ids,
        errors=errors,
    )


def verify_step_count(step_count: int, requested_count: int, run_id: str) -> None:
    if step_count != requested_count:
        raise StepCountMismatch(
            f"run '{run_id}' reported {step_count} step result(s) in its manifest but "
            f"{requested_count} step(s) were requested - the device produced a different number "
            "of results than asked for. Check 'adb -s <serial> logcat -d' for a crash partway "
            "through the run."
        )


def verify_pulled_count(pulled_count: int, ok_count: int, run_id: str, out_dir: str) -> None:
    if pulled_count != ok_count:
        raise PulledCountMismatch(
            f"run '{run_id}' reported {ok_count} ok screenshot(s) but only {pulled_count} were "
            f"actually pulled to {out_dir} - see the 'could not pull' warning(s) for which "
            "step(s) and why."
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    poll_p = sub.add_parser("poll", help="check one poll's manifest text (read from stdin) for this run's own done marker")
    poll_p.add_argument("--run-id", required=True)

    sub.add_parser("summarize", help="parse the full manifest text (read from stdin) into step/ok/error counts and ok ids")

    verify_p = sub.add_parser("verify", help="the two fewer-than-requested checks")
    verify_p.add_argument("--run-id", required=True)
    verify_p.add_argument("--out-dir", required=True)
    verify_p.add_argument("--requested-count", type=int, required=True)
    verify_p.add_argument("--step-count", type=int, required=True)
    verify_p.add_argument("--ok-count", type=int, required=True)
    verify_p.add_argument("--pulled-count", type=int, required=True)

    args = parser.parse_args()
    manifest_text = ""
    if args.command in ("poll", "summarize"):
        # Windows PowerShell 5.1 prepends a UTF-8 BOM when it pipes a string to a native
        # process's stdin (`$manifestText | python ...`) - found by actually piping a real
        # manifest string through and reading it back on the Python side, not by inspection: the
        # first byte on stdin was `\xef\xbb\xbf`. `utf-8-sig` strips a leading BOM if present and
        # is a no-op otherwise, so this is correct whether or not the caller's shell adds one.
        manifest_text = sys.stdin.buffer.read().decode("utf-8-sig")

    if args.command == "poll":
        result = check_done_marker(manifest_text, args.run_id)
        print(json.dumps({"done": result.done, "foreignRunId": result.foreign_run_id}))
        return 0

    if args.command == "summarize":
        summary = summarize(manifest_text)
        print(json.dumps({
            "stepCount": summary.step_count,
            "okCount": summary.ok_count,
            "errorCount": summary.error_count,
            "okIds": summary.ok_ids,
            "errors": summary.errors,
        }))
        return 0

    # verify
    try:
        verify_step_count(args.step_count, args.requested_count, args.run_id)
        verify_pulled_count(args.pulled_count, args.ok_count, args.run_id, args.out_dir)
    except StepCountMismatch as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    except PulledCountMismatch as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
