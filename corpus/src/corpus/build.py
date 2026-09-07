"""Assemble and validate the committed four-source manifest (implementation-plan M0.2/M0.5).

The manifest is rendered deterministically from the source registry and the planned
whole-session fold assignment below (§14A.2). ``make corpus`` renders it, validates it, and
runs the fold checker; CI additionally asserts the committed file has not drifted.
"""
from __future__ import annotations

import json
from pathlib import Path

from .folds import check_folds
from .manifest import from_dict, load_manifest
from .sources import SOURCES

PROJECT_DIR = Path(__file__).resolve().parents[2]
COMMITTED_MANIFEST = PROJECT_DIR / "manifest.json"

# Whole sessions only. ~50/20/30 train/dev/eval by duration (§14A.2). Every eval speech
# session's stations are absent from train; the two noise tapes are in different folds;
# HF/DX traffic (Paderborn) is in eval so FR-LEX-8's grammar path is exercised.
PLANNED_SESSIONS: list[dict] = [
    # -- Paderborn HF: eval only (real off-air amateur HF, non-US callsigns) --
    {"id": "paderborn-hf/eval-dx-01", "source": "paderborn-hf", "fold": "eval",
     "kind": "speech", "duration_s": 2400.0, "stations": ["DL8XYZ", "VK3ABC"]},
    {"id": "paderborn-hf/eval-dx-02", "source": "paderborn-hf", "fold": "eval",
     "kind": "speech", "duration_s": 2100.0, "stations": ["G4ABC", "JA1XYZ"]},
    # -- Fearless Steps: train + dev (degraded comms, diarization ground truth) --
    {"id": "fearless-steps/train-01", "source": "fearless-steps", "fold": "train",
     "kind": "speech", "duration_s": 3600.0, "stations": ["CAPCOM", "CDR"]},
    {"id": "fearless-steps/dev-01", "source": "fearless-steps", "fold": "dev",
     "kind": "speech", "duration_s": 900.0, "stations": ["CMP"]},
    # -- ATC merge: train + dev (ICAO phonetics, callsign grammar) --
    {"id": "atc-merge/train-01", "source": "atc-merge", "fold": "train",
     "kind": "speech", "duration_s": 3000.0, "stations": ["DLH456", "N1234A"]},
    {"id": "atc-merge/dev-01", "source": "atc-merge", "fold": "dev",
     "kind": "speech", "duration_s": 600.0, "stations": ["BAW77"]},
    # -- ISOLET: train only (letter-name pronunciation inventory, not real traffic) --
    {"id": "isolet/train-01", "source": "isolet", "fold": "train",
     "kind": "speech", "duration_s": 1800.0, "stations": []},
    # -- Synthetic: train only (FR-TST-9 bars it from eval) --
    {"id": "synth-callsigns/train-01", "source": "synth-callsigns", "fold": "train",
     "kind": "speech", "duration_s": 3600.0, "stations": ["W1AW", "K2ABC"]},
    # -- Local recordings: validation hour (dev) + two separately-recorded noise tapes --
    {"id": "local/validation-fm-01", "source": "local", "fold": "dev",
     "kind": "speech", "duration_s": 1800.0, "stations": ["KC1ABC", "W2DEF"]},
    {"id": "local/noise-eval", "source": "local", "fold": "eval",
     "kind": "noise", "duration_s": 1200.0, "stations": []},
    {"id": "local/noise-dev", "source": "local", "fold": "dev",
     "kind": "noise", "duration_s": 1200.0, "stations": []},
]


def _session_dict(s: dict) -> dict:
    sid = s["id"]
    return {
        "id": sid,
        "source": s["source"],
        "fold": s["fold"],
        "kind": s["kind"],
        "duration_s": s["duration_s"],
        "stations": s["stations"],
        "audio": f"sessions/{sid}/audio.flac",
        "checksum": {"algo": "sha256", "value": "pending"},
    }


def render() -> str:
    doc = {
        "version": "0",
        "sources": [s.to_source_dict() for s in SOURCES],
        "sessions": [_session_dict(s) for s in PLANNED_SESSIONS],
    }
    m = from_dict(doc)  # raises ManifestError on any contamination
    violations = check_folds(m)
    if violations:
        raise SystemExit("fold check failed:\n  " + "\n  ".join(v.message for v in violations))
    return json.dumps(m.to_dict(), indent=2, sort_keys=True) + "\n"


def build_manifest(out: str | Path = COMMITTED_MANIFEST) -> Path:
    out = Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(render(), encoding="utf-8", newline="\n")
    return out


def check_committed() -> bool:
    if not COMMITTED_MANIFEST.exists():
        return False
    current = COMMITTED_MANIFEST.read_text(encoding="utf-8").replace("\r\n", "\n")
    return current == render()


def validate_committed() -> list[str]:
    m = load_manifest(COMMITTED_MANIFEST)
    return [v.message for v in check_folds(m)]
