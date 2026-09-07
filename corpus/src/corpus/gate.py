"""The eval-fold gate (FR-TST-7, AC-100).

"Sealing by discipline alone does not survive a debugging session at 2 a.m." — so the tool
refuses. Opening ``eval`` needs an explicit, loud opt-in; there is no default that reads it.
"""
from __future__ import annotations

from .manifest import Manifest, Session

OPT_IN_FLAG = "--i-know-this-is-the-eval-fold"


class EvalFoldSealed(PermissionError):
    """Something tried to read the eval fold without the explicit opt-in (§14A.2 step 8)."""

    def __init__(self) -> None:
        super().__init__(
            "the eval fold is sealed until M11. Pass allow_eval=True / "
            f"{OPT_IN_FLAG} only when you mean to open it — this happens exactly once, "
            "and it is not recoverable (constitution: you cannot un-see the eval fold)."
        )


def load_fold_sessions(m: Manifest, fold: str, *, allow_eval: bool = False) -> list[Session]:
    if fold == "eval" and not allow_eval:
        raise EvalFoldSealed()
    return m.sessions_in(fold)
