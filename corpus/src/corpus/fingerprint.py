"""Run fingerprint (constitution: "No number without its provenance").

Every emitted report records a fingerprint of exactly what produced it: the manifest content,
the fold, the config, the code version, the interpreter, the machine, the execution provider and
the thread count. Two runs that agree on all of these must agree on the number (FR-TST-4).

R-1122 (register; constitution VI - "every reported figure carries fold, machine, execution
provider, thread count, model version and run fingerprint"): before this fix, the fingerprint
hashed only the manifest, fold, config, git revision and Python version. Two runs on different
machines, with different execution providers, or with different thread counts - exactly the four
things constitution VI's determinism bound names ("machine, provider, thread count, runtime
version") - produced the identical fingerprint as long as those five agreed, so the one thing this
module exists to guarantee ("two runs that agree on all of these must agree on the number") could
not actually be checked, because the hash could not tell such runs apart in the first place. Every
accuracy number the project could produce today would therefore have been unreportable under its
own rule (no fold-carrying number is evidence without a fingerprint that actually distinguishes
runs) - fixed here, before the first dev-fold measurement, because a number already published
without this cannot be retrofitted.

`machine_descriptor()` deliberately never uses `platform.node()` (the hostname): this project's
output is meant to be publishable, and a hostname routinely carries the operator's own name or
account. Instead it records what actually changes a number and is safe to publish - OS, CPU
architecture, processor description and core count.
"""
from __future__ import annotations

import hashlib
import json
import os
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


def machine_descriptor() -> str:
    """A description of this machine that two runs can be told apart by, without naming the
    operator: OS and release, CPU architecture, processor description, and core count. Never the
    hostname (`platform.node()`) - see this module's docstring (R-1122)."""
    system = platform.system() or "unknown-os"
    release = platform.release() or "unknown-release"
    machine = platform.machine() or "unknown-arch"
    processor = platform.processor() or machine
    cores = os.cpu_count() or 0
    return f"{system} {release} ({machine}, {processor}, {cores} cores)"


def thread_count() -> int:
    """The number of threads available to this process - the bound constitution VI names
    ("determinism is bounded... within a fixed (machine, provider, thread count, runtime
    version)"). The corpus harness runs no model yet (harness.py's own docstring: "before any
    model exists") and configures no thread pool of its own, so this is simply what the OS
    reports available; an execution provider that later pins its own intra-op thread count
    should report that value here instead of this default."""
    return os.cpu_count() or 1


def execution_provider() -> str:
    """The inference execution provider a run used, or an honest statement that none ran. The
    harness has no model to run yet (harness.py: "before any model exists"), so naming a provider
    like "cpu-local" would be a number without a fold behind it - the same discipline
    `Report.model_version`'s "none (harness v0, hand transcripts)" already applies."""
    return "none (harness v0, no model runs)"


def run_fingerprint(
    manifest_doc: dict[str, Any],
    fold: str,
    config: dict[str, Any],
    *,
    machine: str | None = None,
    provider: str | None = None,
    threads: int | None = None,
) -> str:
    payload = {
        "manifest": manifest_doc,
        "fold": fold,
        "config": config,
        "code": code_version(),
        "python": platform.python_version(),
        "machine": machine if machine is not None else machine_descriptor(),
        "provider": provider if provider is not None else execution_provider(),
        "threads": threads if threads is not None else thread_count(),
    }
    blob = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(blob).hexdigest()
