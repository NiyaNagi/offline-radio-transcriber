"""Deterministic normalisation to the harness's only input format: 16 kHz mono FLAC.

FR-STO-2a / §14A.3: the corpus chain is lossless end to end. FLAC, 16 kHz, one channel,
signed 16-bit — the same invocation every time so a re-run reproduces byte-for-byte.
"""
from __future__ import annotations

import subprocess
from pathlib import Path
from typing import Callable

Runner = Callable[[list[str]], object]


def ffmpeg_cmd(src: Path, dst: Path) -> list[str]:
    return [
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(src),
        "-ac", "1", "-ar", "16000", "-sample_fmt", "s16",
        "-c:a", "flac", "-compression_level", "8",
        str(dst),
    ]


def _default_runner(cmd: list[str]) -> object:
    return subprocess.run(cmd, check=True, capture_output=True)


def to_flac_16k_mono(src: str | Path, dst: str | Path, *, runner: Runner | None = None) -> Path:
    src, dst = Path(src), Path(dst)
    dst.parent.mkdir(parents=True, exist_ok=True)
    (runner or _default_runner)(ffmpeg_cmd(src, dst))
    return dst
