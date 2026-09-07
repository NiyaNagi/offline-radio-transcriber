"""The fold-assignment checker enforcing functional-spec §14A.2.

Whole sessions only; at least one eval session with no station present in train (the only
measurement of true generalisation, step 4); the two noise tapes separated across folds
(step 5 — one seals ``eval`` for AC-6 at M11, one is the unsealed dev tape); HF/DX traffic
in eval (step 6).
"""
from __future__ import annotations

from dataclasses import dataclass

from .manifest import Manifest


class FoldViolation(AssertionError):
    """A fold assignment breaks §14A.2 — the eval fold cannot be trusted."""


@dataclass
class Violation:
    rule: str
    message: str


def check_folds(m: Manifest, *, raise_on_violation: bool = False) -> list[Violation]:
    violations: list[Violation] = []

    # 1. whole sessions only — no id in more than one fold
    folds_by_id: dict[str, set[str]] = {}
    for s in m.sessions:
        folds_by_id.setdefault(s.id, set()).add(s.fold)
    for sid, folds in folds_by_id.items():
        if len(folds) > 1:
            violations.append(Violation(
                "whole-sessions",
                f"session {sid!r} is split across folds {sorted(folds)} — assign whole sessions",
            ))

    train_stations = {
        st for s in m.sessions if s.fold == "train" for st in s.stations
    }
    eval_speech = [s for s in m.sessions if s.fold == "eval" and s.kind == "speech"]

    # 4. at least one eval session with no train station
    if not any(s.stations and not (set(s.stations) & train_stations) for s in eval_speech):
        violations.append(Violation(
            "generalisation",
            "no eval session contains no train station — true generalisation is unmeasured "
            "(§14A.2 step 4)",
        ))

    # 5. the two noise tapes, separated
    noise_folds = sorted({s.fold for s in m.sessions if s.kind == "noise"})
    noise_count = sum(1 for s in m.sessions if s.kind == "noise")
    if noise_count < 2:
        violations.append(Violation(
            "noise-tapes",
            f"expected two separately-recorded noise tapes, found {noise_count} "
            "(§14A.2 step 5) — AC-6 is unrunnable before M11 without the dev tape",
        ))
    elif "eval" not in noise_folds or "dev" not in noise_folds:
        violations.append(Violation(
            "noise-tapes",
            f"noise tapes are all in {noise_folds} — one must be eval, one dev (§14A.2 step 5)",
        ))

    # 6. HF/DX traffic in eval — non-US callsigns test FR-LEX-8
    hf_ids = {s.id for s in m.sources if "hf" in s.id or "HF" in s.name}
    if hf_ids and not any(s.source in hf_ids for s in m.sessions if s.fold == "eval"):
        violations.append(Violation(
            "hf-in-eval",
            "no HF/DX session is in eval (§14A.2 step 6) — the grammar path FR-LEX-8 is untested",
        ))

    if raise_on_violation and violations:
        raise FoldViolation("; ".join(v.message for v in violations))
    return violations
