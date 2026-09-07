"""Metric primitives for the evaluation harness (FR-TST-5, AC-35).

Pure functions over hand transcripts. No model, no audio — harness v0 establishes the report
shape and the numbers it will carry.
"""
from __future__ import annotations

from collections import Counter
from typing import Iterable, Sequence


def _levenshtein(a: Sequence[str], b: Sequence[str]) -> int:
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def wer(ref: Sequence[str], hyp: Sequence[str]) -> float:
    if not ref:
        return 0.0 if not hyp else 1.0
    return _levenshtein(ref, hyp) / len(ref)


def _prf(tp: int, fp: int, fn: int) -> tuple[float, float, float]:
    p = tp / (tp + fp) if (tp + fp) else 0.0
    r = tp / (tp + fn) if (tp + fn) else 0.0
    f = 2 * p * r / (p + r) if (p + r) else 0.0
    return p, r, f


def callsign_pr(ref: Iterable[str], hyp: Iterable[str]) -> tuple[float, float, float]:
    rc, hc = Counter(ref), Counter(hyp)
    tp = sum((rc & hc).values())
    return _prf(tp, sum(hc.values()) - tp, sum(rc.values()) - tp)


def boundary_pr(
    ref: Sequence[tuple[float, float]],
    hyp: Sequence[tuple[float, float]],
    tol_ms: float = 250.0,
) -> tuple[float, float, float]:
    used = [False] * len(ref)
    tp = 0
    for hs, he in hyp:
        for i, (rs, re_) in enumerate(ref):
            if not used[i] and abs(hs - rs) <= tol_ms and abs(he - re_) <= tol_ms:
                used[i] = True
                tp += 1
                break
    return _prf(tp, len(hyp) - tp, len(ref) - tp)


def rejection_rate_by_reason(records: Iterable[dict]) -> dict:
    recs = list(records)
    total = len(recs)
    rejected = [r for r in recs if not r.get("accepted", False)]
    by_reason: dict[str, float] = {}
    counts = Counter(r.get("reason") or "unspecified" for r in rejected)
    for reason, n in counts.items():
        by_reason[reason] = n / total if total else 0.0
    return {
        "overall": len(rejected) / total if total else 0.0,
        "by_reason": by_reason,
        "n": total,
    }


def attribution_accuracy(records: Iterable[dict]) -> float:
    recs = list(records)
    if not recs:
        return 0.0
    correct = sum(1 for r in recs if r.get("predicted") == r.get("truth"))
    return correct / len(recs)
