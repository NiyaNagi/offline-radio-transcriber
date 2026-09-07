"""The evaluation report.

Constitution: "Every reported figure carries fold, machine, execution provider, thread count,
model version and run fingerprint. A number whose fold is unstated is not evidence." The
report refuses to exist without a fold, and refuses to serialise without a fingerprint.
"""
from __future__ import annotations

import json
import platform
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


class ReportError(ValueError):
    pass


@dataclass
class Report:
    fold: str
    fingerprint: str
    per_source: dict[str, dict[str, Any]]
    aggregate: dict[str, Any]
    provider: str = "cpu-local"
    machine: str = field(default_factory=platform.node)
    model_version: str = "none (harness v0, hand transcripts)"
    config: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not self.fold:
            raise ReportError("a report must state the fold that produced it (AC-100)")
        if not self.fingerprint:
            raise ReportError("a report must carry its run fingerprint (FR-TST-4)")

    def to_dict(self) -> dict[str, Any]:
        return {
            "fold": self.fold,
            "fingerprint": self.fingerprint,
            "provider": self.provider,
            "machine": self.machine,
            "model_version": self.model_version,
            "config": self.config,
            "per_source": self.per_source,
            "aggregate": self.aggregate,
        }

    def to_markdown(self) -> str:
        lines = [
            f"# Corpus harness report — fold `{self.fold}`",
            "",
            f"- fingerprint: `{self.fingerprint}`",
            f"- provider: {self.provider} · machine: {self.machine}",
            f"- model: {self.model_version}",
            "",
            "## Aggregate",
            "",
            _table(self.aggregate),
            "",
            "## Per source (FR-TST-8)",
            "",
        ]
        for src, metrics in sorted(self.per_source.items()):
            lines += [f"### {src}", "", _table(metrics), ""]
        return "\n".join(lines)

    def write(self, path: str | Path) -> None:
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(self.to_dict(), indent=2, sort_keys=True), encoding="utf-8")
        path.with_suffix(".md").write_text(self.to_markdown(), encoding="utf-8")


def _table(metrics: dict[str, Any]) -> str:
    rows = ["| metric | value |", "|---|---|"]
    for k, v in metrics.items():
        rows.append(f"| {k} | {json.dumps(v)} |")
    return "\n".join(rows)
