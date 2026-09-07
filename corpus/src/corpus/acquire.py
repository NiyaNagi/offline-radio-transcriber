"""Acquisition: fetch, verify, normalise, record (implementation-plan M0.A).

Resumable (a partial download continues from the ``.part`` offset) and idempotent (a source
already fetched and normalised is not touched again). Every entry records its source, licence
and checksum.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Callable

from .audio import to_flac_16k_mono
from .sources import SourceSpec

Fetcher = Callable[..., None]
Normaliser = Callable[[Path, Path], None]


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _download(url: str, archive: Path, fetcher: Fetcher, expected: dict[str, str]) -> None:
    part = archive.parent / (archive.name + ".part")
    resume_from = part.stat().st_size if part.exists() else 0
    fetcher(url, part, resume_from=resume_from)

    algo, value = expected.get("algo", "sha256"), expected.get("value", "pending")
    if value not in ("pending", "n/a", "") and algo == "sha256":
        got = _sha256(part)
        if got != value:
            raise ValueError(
                f"checksum mismatch for {url}: expected {value}, got {got} "
                f"(partial file kept at {part.name} for resume)"
            )
    part.replace(archive)


def acquire_source(
    src: SourceSpec,
    dest_dir: str | Path,
    *,
    fetcher: Fetcher,
    normaliser: Normaliser | None = None,
    members: list[str] | None = None,
) -> dict:
    dest_dir = Path(dest_dir)
    dest_dir.mkdir(parents=True, exist_ok=True)
    normaliser = normaliser or (lambda s, d: to_flac_16k_mono(s, d))

    archive = dest_dir / f"{src.id}.archive"
    marker = dest_dir / f"{src.id}.acquired.json"
    if marker.exists() and archive.exists():
        return json.loads(marker.read_text(encoding="utf-8"))  # idempotent

    if not archive.exists():
        _download(src.url, archive, fetcher, src.upstream_checksum)

    pinned = dict(src.upstream_checksum)
    if pinned.get("value") in ("pending", "", None):
        pinned = {"algo": "sha256", "value": _sha256(archive)}

    entries = []
    raw_dir = dest_dir / "raw" / src.id
    raw_dir.mkdir(parents=True, exist_ok=True)
    for member in members or [f"{src.id}.wav"]:
        raw = raw_dir / member
        raw.write_bytes(archive.read_bytes())  # stand-in for archive extraction
        out = dest_dir / "sessions" / src.id / (Path(member).stem + ".flac")
        normaliser(raw, out)
        entries.append({
            "member": member,
            "audio": str(out.relative_to(dest_dir)).replace("\\", "/"),
            "checksum": {"algo": "sha256", "value": _sha256(out)},
        })

    record = {
        "source": src.id,
        "name": src.name,
        "url": src.url,
        "licence": src.licence,
        "upstream_checksum": pinned,
        "entries": entries,
    }
    marker.write_text(json.dumps(record, indent=2, sort_keys=True), encoding="utf-8")
    return record
