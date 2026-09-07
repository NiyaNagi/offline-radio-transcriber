"""Evaluation harness v0 (implementation-plan M0.8, FR-TST-5/8, AC-35).

Runs over hand transcripts before any model exists. Computes WER, callsign precision/recall,
boundary precision/recall, rejection rate by reason and attribution accuracy, reported per
source as well as in aggregate, stamped with the fold and a run fingerprint.
"""
from __future__ import annotations

from typing import Any

from .fingerprint import run_fingerprint
from .gate import load_fold_sessions
from .manifest import Manifest
from .metrics import (
    attribution_accuracy,
    boundary_pr,
    callsign_pr,
    rejection_rate_by_reason,
    wer,
)
from .report import Report


def _metrics_for(transcripts: list[dict[str, Any]]) -> dict[str, Any]:
    ref_tokens: list[str] = []
    hyp_tokens: list[str] = []
    ref_calls: list[str] = []
    hyp_calls: list[str] = []
    ref_bounds: list[tuple[float, float]] = []
    hyp_bounds: list[tuple[float, float]] = []
    segments: list[dict] = []
    attributions: list[dict] = []
    for t in transcripts:
        ref_tokens += list(t.get("ref_tokens", []))
        hyp_tokens += list(t.get("hyp_tokens", []))
        ref_calls += list(t.get("ref_callsigns", []))
        hyp_calls += list(t.get("hyp_callsigns", []))
        ref_bounds += [tuple(b) for b in t.get("ref_boundaries", [])]
        hyp_bounds += [tuple(b) for b in t.get("hyp_boundaries", [])]
        segments += list(t.get("segments", []))
        attributions += list(t.get("attributions", []))

    cp, cr, cf = callsign_pr(ref_calls, hyp_calls)
    bp, br, bf = boundary_pr(ref_bounds, hyp_bounds)
    return {
        "sessions": len(transcripts),
        "wer": wer(ref_tokens, hyp_tokens),
        "callsign_precision": cp,
        "callsign_recall": cr,
        "callsign_f1": cf,
        "boundary_precision": bp,
        "boundary_recall": br,
        "boundary_f1": bf,
        "rejection": rejection_rate_by_reason(segments),
        "attribution_accuracy": attribution_accuracy(attributions),
    }


def evaluate(
    m: Manifest,
    fold: str,
    transcripts: dict[str, dict[str, Any]],
    *,
    allow_eval: bool = False,
    config: dict[str, Any] | None = None,
) -> Report:
    config = config or {}
    sessions = load_fold_sessions(m, fold, allow_eval=allow_eval)

    by_source: dict[str, list[dict[str, Any]]] = {}
    scored: list[dict[str, Any]] = []
    for s in sessions:
        t = transcripts.get(s.id)
        if t is None:
            continue
        by_source.setdefault(s.source, []).append(t)
        scored.append(t)

    per_source = {src: _metrics_for(ts) for src, ts in sorted(by_source.items())}
    aggregate = _metrics_for(scored)

    return Report(
        fold=fold,
        fingerprint=run_fingerprint(m.to_dict(), fold, config),
        per_source=per_source,
        aggregate=aggregate,
        config=config,
    )
