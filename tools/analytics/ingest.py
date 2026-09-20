"""The reference analytics ingest server's request logic (D48, FR-ANL-1..14).

Deliberately separated from any socket/HTTP framework (`server.py` is the thin wrapper) so this
module is testable with plain function calls — no server process, no port, no flakiness.

Every row this module ever writes is stamped ``fold: "field"`` itself, unconditionally
(FR-ANL-12, constitution VI): the fold a row lands in is never read from the client's payload, so
a buggy or malicious client cannot claim to be ``dev`` or ``eval`` and contaminate either — those
folds simply never exist in data this server produces.

Storage is append-only NDJSON, partitioned by schema version and UTC date
(``schema-<version>/<date>.ndjson``), matching the wire format `:telemetry`'s own
``AnalyticsEventCodec`` produces on the Android side line-for-line. A malformed line is skipped,
never fatal to the rest of the batch — one corrupt event must not lose the batch that shares its
socket connection.
"""
from __future__ import annotations

import json
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

FOLD = "field"
PURGE_LOG_NAME = "purged_install_ids.txt"


@dataclass(frozen=True)
class IngestResult:
    accepted: int
    skipped_malformed: int
    skipped_purged: int


@dataclass(frozen=True)
class PurgeResult:
    install_id: str
    rows_removed: int


def _partition_dir(data_dir: Path, schema_version: Any) -> Path:
    return Path(data_dir) / f"schema-{schema_version}"


def _today() -> str:
    return datetime.now(timezone.utc).date().isoformat()


class IngestApp:
    """The whole reference server's decision logic. One instance per data directory."""

    def __init__(self, data_dir: str | Path):
        self.data_dir = Path(data_dir)
        self.data_dir.mkdir(parents=True, exist_ok=True)

    def purge_log_path(self) -> Path:
        return self.data_dir / PURGE_LOG_NAME

    def purged_install_ids(self) -> set[str]:
        path = self.purge_log_path()
        if not path.exists():
            return set()
        return {line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip()}

    def ingest_ndjson(self, body: bytes, *, today: str | None = None) -> IngestResult:
        """FR-ANL-7's destination side: the request body is already `:telemetry`'s own NDJSON wire
        form (`AnalyticsEventCodec`), one event per line. Never raises on a malformed line."""
        text = body.decode("utf-8", errors="replace")
        purged = self.purged_install_ids()
        today = today or _today()
        accepted = 0
        skipped_malformed = 0
        skipped_purged = 0
        for line in text.splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                skipped_malformed += 1
                continue
            if not isinstance(event, dict):
                skipped_malformed += 1
                continue
            install_id = (event.get("provenance") or {}).get("installId")
            if install_id is not None and install_id in purged:
                # AC-181: an install id that was reset must never let a late-arriving, queued
                # event re-populate rows the operator already asked to be erased.
                skipped_purged += 1
                continue
            # FR-ANL-12: stamped here, server-side, unconditionally — never trusted from the
            # client, so `dev`/`eval` can never appear in data this server ever produces.
            event["fold"] = FOLD
            event.setdefault("ingestedAtUtc", datetime.now(timezone.utc).isoformat())
            schema_version = (event.get("provenance") or {}).get("schemaVersion", "unknown")
            self._append(schema_version, today, event)
            accepted += 1
        return IngestResult(accepted=accepted, skipped_malformed=skipped_malformed, skipped_purged=skipped_purged)

    def _append(self, schema_version: Any, day: str, event: dict) -> None:
        partition = _partition_dir(self.data_dir, schema_version) / f"{day}.ndjson"
        partition.parent.mkdir(parents=True, exist_ok=True)
        with partition.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(event, sort_keys=True))
            handle.write("\n")

    def purge_install_id(self, install_id: str) -> PurgeResult:
        """FR-ANL-11/D48: erase every row already on disk for [install_id], and remember it so a
        late-arriving queued event for the same id is refused by [ingest_ndjson] above too."""
        removed = 0
        for partition in self._all_partitions():
            lines = partition.read_text(encoding="utf-8").splitlines()
            kept: list[str] = []
            for line in lines:
                if not line.strip():
                    continue
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    kept.append(line)
                    continue
                if (event.get("provenance") or {}).get("installId") == install_id:
                    removed += 1
                else:
                    kept.append(line)
            partition.write_text("".join(f"{line}\n" for line in kept), encoding="utf-8")
        with self.purge_log_path().open("a", encoding="utf-8") as handle:
            handle.write(f"{install_id}\n")
        return PurgeResult(install_id=install_id, rows_removed=removed)

    def _all_partitions(self) -> Iterable[Path]:
        return sorted(self.data_dir.glob("schema-*/*.ndjson"))
