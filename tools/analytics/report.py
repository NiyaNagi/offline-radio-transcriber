"""The report generator: runs every named query in `queries.py` against an already-loaded
`events` view and renders one plain-text report (D48). Generated reports are never committed
(`.gitignore`) — this module only ever writes to a caller-supplied path, never a fixed one inside
the repository.
"""
from __future__ import annotations

import duckdb

import queries


def _format_rate(value: float | None) -> str:
    return "n/a" if value is None else f"{value:.3f}"


def generate_report(con: duckdb.DuckDBPyConnection) -> str:
    lines: list[str] = []
    lines.append("# Analytics report (field fold)")
    lines.append("")

    crash = queries.crash_free_sessions(con)
    lines.append("## Crash-free sessions")
    lines.append(f"- sessions: {crash['total_sessions']}")
    lines.append(f"- crashed: {crash['crashed_sessions']}")
    lines.append(f"- crash-free rate: {_format_rate(crash['crash_free_rate'])}")
    lines.append("")

    lines.append("## Setup funnel")
    funnel = queries.setup_funnel(con)
    if not funnel:
        lines.append("- no setup-funnel events in this fold")
    for step, outcome, n in funnel:
        lines.append(f"- {step} / {outcome}: {n}")
    lines.append("")

    lines.append("## Real-time factor by pass")
    rtf = queries.real_time_factor(con)
    if not rtf:
        lines.append("- no performance events in this fold")
    for pass_id, avg_rtf, min_rtf, max_rtf, n in rtf:
        lines.append(f"- {pass_id}: avg={avg_rtf:.3f} min={min_rtf:.3f} max={max_rtf:.3f} n={n}")
    lines.append("")

    lines.append("## Correction rate by field (tier 1 aggregate)")
    correction = queries.correction_rate(con)
    if not correction:
        lines.append("- no quality-stats events in this fold")
    for field, rate in sorted(correction.items()):
        lines.append(f"- {field}: {_format_rate(rate)}")
    lines.append("")

    lines.append("## Field WER (tier 2, opt-in)")
    wer = queries.field_wer(con)
    if wer["pairs"] == 0:
        lines.append("- no tier-2 (ASR hypothesis, correction) pairs in this fold")
    else:
        lines.append(f"- pairs: {wer['pairs']}")
        lines.append(f"- WER: {_format_rate(wer['wer'])}")
    lines.append("")

    return "\n".join(lines)
