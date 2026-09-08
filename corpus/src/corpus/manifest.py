"""The corpus manifest and its validation (functional-spec §14A.2, FR-TST-7/8/9).

The manifest is the enforcement point: it refuses to load a corpus that could contaminate a
measurement. A session in two folds, or synthetic audio in ``eval``, is a load-time error, not
a warning — the failure is silent and permanent otherwise.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

FOLDS = ("train", "dev", "eval")
SOURCE_KINDS = ("public", "synthetic", "recorded")
SESSION_KINDS = ("speech", "noise")


class ManifestError(ValueError):
    """The manifest describes a corpus that cannot be trusted to measure anything."""


@dataclass
class Source:
    id: str
    name: str
    url: str
    licence: str
    kind: str
    synthetic: bool
    upstream_checksum: dict[str, str]

    @staticmethod
    def from_dict(d: dict[str, Any]) -> Source:
        for key in ("id", "name", "url", "licence", "kind"):
            if not d.get(key):
                raise ManifestError(f"source {d.get('id', '?')!r}: missing {key}")
        if d["kind"] not in SOURCE_KINDS:
            raise ManifestError(f"source {d['id']!r}: unknown kind {d['kind']!r}")
        checksum = d.get("upstream_checksum")
        if not checksum or not checksum.get("algo") or not checksum.get("value"):
            raise ManifestError(f"source {d['id']!r}: missing upstream_checksum")
        return Source(
            id=d["id"], name=d["name"], url=d["url"], licence=d["licence"], kind=d["kind"],
            synthetic=bool(d.get("synthetic", d["kind"] == "synthetic")),
            upstream_checksum=checksum,
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id, "name": self.name, "url": self.url, "licence": self.licence,
            "kind": self.kind, "synthetic": self.synthetic,
            "upstream_checksum": self.upstream_checksum,
        }


@dataclass
class Session:
    id: str
    source: str
    fold: str
    kind: str
    duration_s: float
    stations: list[str]
    audio: str
    checksum: dict[str, str]

    @staticmethod
    def from_dict(d: dict[str, Any]) -> Session:
        if not d.get("id"):
            raise ManifestError("session: missing id")
        if d.get("fold") not in FOLDS:
            raise ManifestError(f"session {d.get('id')!r}: unknown fold {d.get('fold')!r}")
        if d.get("kind") not in SESSION_KINDS:
            raise ManifestError(f"session {d['id']!r}: unknown kind {d.get('kind')!r}")
        if not d.get("checksum"):
            raise ManifestError(f"session {d['id']!r}: missing checksum")
        return Session(
            id=d["id"], source=d["source"], fold=d["fold"], kind=d["kind"],
            duration_s=float(d.get("duration_s", 0.0)),
            stations=list(d.get("stations", [])), audio=d.get("audio", ""),
            checksum=d["checksum"],
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id, "source": self.source, "fold": self.fold, "kind": self.kind,
            "duration_s": self.duration_s, "stations": self.stations, "audio": self.audio,
            "checksum": self.checksum,
        }


@dataclass
class Manifest:
    version: str
    sources: list[Source]
    sessions: list[Session]

    def source(self, sid: str) -> Source:
        for s in self.sources:
            if s.id == sid:
                return s
        raise KeyError(sid)

    def session(self, sid: str) -> Session:
        for s in self.sessions:
            if s.id == sid:
                return s
        raise KeyError(sid)

    def sessions_in(self, fold: str) -> list[Session]:
        return [s for s in self.sessions if s.fold == fold]

    def to_dict(self) -> dict[str, Any]:
        return {
            "version": self.version,
            "sources": [s.to_dict() for s in self.sources],
            "sessions": [s.to_dict() for s in self.sessions],
        }


def _validate(m: Manifest) -> None:
    known_sources = {s.id for s in m.sources}
    synthetic_sources = {s.id for s in m.sources if s.synthetic}

    fold_by_session: dict[str, str] = {}
    for s in m.sessions:
        if s.source not in known_sources:
            raise ManifestError(f"session {s.id!r}: unknown source {s.source!r}")
        if s.id in fold_by_session and fold_by_session[s.id] != s.fold:
            raise ManifestError(
                f"session {s.id!r} is placed in two folds "
                f"({fold_by_session[s.id]!r} and {s.fold!r}) — assign whole sessions (§14A.2)"
            )
        if s.id in fold_by_session:
            raise ManifestError(f"session {s.id!r} appears more than once in the manifest")
        fold_by_session[s.id] = s.fold
        if s.fold == "eval" and s.source in synthetic_sources:
            raise ManifestError(
                f"session {s.id!r}: synthetic data must never appear in the eval fold "
                f"(FR-TST-9)"
            )


def load_manifest(path: str | Path) -> Manifest:
    doc = json.loads(Path(path).read_text(encoding="utf-8"))
    return from_dict(doc)


def from_dict(doc: dict[str, Any]) -> Manifest:
    m = Manifest(
        version=str(doc.get("version", "0")),
        sources=[Source.from_dict(s) for s in doc.get("sources", [])],
        sessions=[Session.from_dict(s) for s in doc.get("sessions", [])],
    )
    _validate(m)
    return m
