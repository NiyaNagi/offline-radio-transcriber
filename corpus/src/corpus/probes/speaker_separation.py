"""R4 — speaker separation on degraded narrowband voice (S1.6, FR-SPK-14).

A WeSpeaker/3D-Speaker embedder over Fearless Steps, clustered, measured against its own
diarization labels (Fearless Steps carries its own split — constitution VI: third-party
corpora keep their own splits, reported as such, never folded into this project's train/
dev/eval). ``Embedder`` is the seam the real model plugs in behind; ``FakeEmbedder`` is the
behavioural fake that proves the clustering and metric math independent of any model or
dataset being present.
"""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

import numpy as np


class Embedder(Protocol):
    def embed(self, utterance: dict[str, Any]) -> np.ndarray: ...


class FakeEmbedder:
    """Deterministic per speaker id: same speaker -> identical vector, different -> distinct."""

    dim = 8

    def embed(self, utterance: dict[str, Any]) -> np.ndarray:
        speaker = utterance["speaker"]
        h = int(hashlib.sha256(speaker.encode("utf-8")).hexdigest(), 16)
        rng = np.random.default_rng(h % (2**32))
        v = rng.normal(size=self.dim)
        return v / (np.linalg.norm(v) + 1e-12)


def _cosine(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.dot(a, b) / ((np.linalg.norm(a) * np.linalg.norm(b)) + 1e-12))


def cluster(embeddings: list[np.ndarray], *, threshold: float) -> list[int]:
    """Simple union-find clustering: link any pair whose cosine similarity clears threshold."""
    n = len(embeddings)
    parent = list(range(n))

    def find(x: int) -> int:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(x: int, y: int) -> None:
        rx, ry = find(x), find(y)
        if rx != ry:
            parent[rx] = ry

    for i in range(n):
        for j in range(i + 1, n):
            if _cosine(embeddings[i], embeddings[j]) >= threshold:
                union(i, j)

    roots = [find(i) for i in range(n)]
    remap: dict[int, int] = {}
    labels = []
    for r in roots:
        labels.append(remap.setdefault(r, len(remap)))
    return labels


def false_match_rate(embeddings: list[np.ndarray], truth: list[str], *, threshold: float) -> float:
    """Fraction of different-speaker pairs whose similarity still clears ``threshold``."""
    n = len(embeddings)
    diff_pairs = 0
    false_matches = 0
    for i in range(n):
        for j in range(i + 1, n):
            if truth[i] == truth[j]:
                continue
            diff_pairs += 1
            if _cosine(embeddings[i], embeddings[j]) >= threshold:
                false_matches += 1
    return false_matches / diff_pairs if diff_pairs else 0.0


def separation(embeddings: list[np.ndarray], truth: list[str]) -> float:
    """Mean same-speaker similarity minus mean different-speaker similarity."""
    n = len(embeddings)
    same, diff = [], []
    for i in range(n):
        for j in range(i + 1, n):
            sim = _cosine(embeddings[i], embeddings[j])
            (same if truth[i] == truth[j] else diff).append(sim)
    mean_same = sum(same) / len(same) if same else 0.0
    mean_diff = sum(diff) / len(diff) if diff else 0.0
    return mean_same - mean_diff


@dataclass
class R4Result:
    status: str  # "ran" | "dry_run" | "not_run"
    reason: str = ""
    corpus: str = ""
    split: str = ""
    model: str = ""
    fingerprint: str = ""
    separation: float = 0.0
    false_match_rate: float = 0.0
    thresholds: dict[str, float] | None = None


def _fingerprint(payload: dict) -> str:
    blob = json.dumps(payload, sort_keys=True, default=str).encode("utf-8")
    return hashlib.sha256(blob).hexdigest()


def probe_r4(
    *,
    fearless_steps_dir: Path | str | None,
    embedder: Embedder | None = None,
    dry_run_fixture: list[dict[str, Any]] | None = None,
    thresholds: tuple[float, ...] = (0.5, 0.7, 0.9),
) -> R4Result:
    """Run R4 if the real corpus is present; otherwise report why it could not run.

    ``dry_run_fixture`` runs the exact same embed/cluster/measure code path against a small
    labelled fixture instead of Fearless Steps — proving the pipeline mechanics work without
    claiming anything about real speaker separation. It is never a substitute for the real
    probe and is reported as ``status="dry_run"``, never ``"ran"``.
    """
    embedder = embedder or FakeEmbedder()

    if dry_run_fixture is not None:
        embeddings = [embedder.embed(u) for u in dry_run_fixture]
        truth = [u["speaker"] for u in dry_run_fixture]
        fmr = {str(t): false_match_rate(embeddings, truth, threshold=t) for t in thresholds}
        sep = separation(embeddings, truth)
        return R4Result(
            status="dry_run",
            reason="fixture dry run only — Fearless Steps was not supplied",
            corpus="fixture (not Fearless Steps)",
            split="n/a",
            model=type(embedder).__name__,
            fingerprint=_fingerprint({"fixture": dry_run_fixture, "thresholds": thresholds}),
            separation=sep,
            false_match_rate=fmr[str(thresholds[0])],
            thresholds=fmr,
        )

    path = Path(fearless_steps_dir) if fearless_steps_dir is not None else None
    if path is None or not path.exists():
        return R4Result(
            status="not_run",
            reason=(
                "Fearless Steps (Apollo-11) is not present in this environment — the corpus "
                "is ~19,000 h and is distributed under a research-use EULA that requires a "
                "request/acceptance step this session did not perform, and no WeSpeaker/"
                "3D-Speaker embedder package is installed here either. See "
                "results/r4-speaker-separation.md."
            ),
            corpus="Fearless Steps (Apollo-11)",
        )

    # Real path: would load Fearless Steps sessions + diarization labels from `path`, embed
    # every utterance with `embedder`, cluster, and measure against the labels per its own
    # split. Not exercised in this session — no dataset directory ever exists here to enter
    # this branch, so it is not a place fabricated numbers could hide.
    raise NotImplementedError(
        "real Fearless Steps ingestion is not implemented — this branch is unreachable in "
        "this environment; see results/r4-speaker-separation.md"
    )
