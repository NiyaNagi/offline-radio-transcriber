"""Placing phonetic units at declared offsets (S1.4, D22).

The generator needs to know, before any audio is rendered, exactly where each unit (a TTS
word or a spliced real ISOLET/ATC letter/digit clip) will land — so the label can assert it
and a test can verify the rendered audio actually matches the declared offset.
"""
from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass


@dataclass(frozen=True)
class PlacedUnit:
    text: str
    start_s: float
    dur_s: float


def place_units(
    tokens: list[str], duration_of: Callable[[str], float], *, gap_s: float = 0.05
) -> list[PlacedUnit]:
    """Lay tokens end to end with a fixed gap, offsets as the cumulative sum of durations."""
    units: list[PlacedUnit] = []
    cursor = 0.0
    for tok in tokens:
        dur = duration_of(tok)
        units.append(PlacedUnit(text=tok, start_s=cursor, dur_s=dur))
        cursor += dur + gap_s
    return units
