"""Run fingerprint (constitution: "No number without its provenance").

Every emitted report records a fingerprint of exactly what produced it: the manifest content,
the fold, the config, the code version and the interpreter. Two runs that agree on all of
these must agree on the number (FR-TST-4).
"""
from __future__ import annotations

import hashlib
import json
import platform
import subprocess
from pathlib import Path
from typing import Any

_REPO = Path(__file__).resolve().parents[3]


def code_version() -> str:
    try:
        out = subprocess.run(
            ["git", "-C", str(_REPO), "rev-parse", "HEAD"],
            capture_output=True, text=True, timeout=5, check=True,
        )
        rev = out.stdout.strip()
        dirty = subprocess.run(
            ["git", "-C", str(_REPO), "status", "--porcelain", "corpus"],
            capture_output=True, text=True, timeout=5, check=True,
        ).stdout.strip()
        return f"{rev}{'+dirty' if dirty else ''}"
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
        # No git binary, not a git checkout, or it hung — any of these are equally "unknown"
        # to a caller that only wants a fingerprint, never a crash.
        return "unknown"


def run_fingerprint(manifest_doc: dict[str, Any], fold: str, config: dict[str, Any]) -> str:
    payload = {
        "manifest": manifest_doc,
        "fold": fold,
        "config": config,
        "code": code_version(),
        "python": platform.python_version(),
    }
    blob = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(blob).hexdigest()
