"""The TTS interface the generator renders phonetic words through (S1.4, D22).

``TtsVoice`` is the seam a real local neural TTS engine plugs in behind. No such engine is
bundled in this sandbox (no network-free local TTS binary is installed — see
results/r1-lora-export.md and results/r4-speaker-separation.md for the broader inventory of
what is and is not available here), so ``FakeTtsVoice`` is the behavioural fake that ships in
the same change (constitution II): deterministic per word, distinguishable across words, and
able to be told to fail on a declared word so error paths are testable too.
"""
from __future__ import annotations

from typing import Protocol

import numpy as np


class TtsError(ValueError):
    """The voice could not render a word (constitution II — a fake that can be told to fail)."""


class TtsVoice(Protocol):
    sample_rate: int

    def synth(self, word: str) -> np.ndarray:
        """Render one phonetic word to mono float64 PCM at ``sample_rate``."""
        ...

    def duration_of(self, word: str) -> float:
        """Seconds ``synth(word)`` will render, without rendering it."""
        ...


class FakeTtsVoice:
    """Deterministic per word: a short tone whose frequency is derived from the word's hash."""

    sample_rate = 16000

    def __init__(self, *, dur_s: float = 0.3, fail_on: frozenset[str] = frozenset()):
        self.dur_s = dur_s
        self.fail_on = fail_on

    def duration_of(self, word: str) -> float:
        return self.dur_s

    def synth(self, word: str) -> np.ndarray:
        if word in self.fail_on:
            raise TtsError(f"FakeTtsVoice: told to fail on {word!r}")
        n = int(self.dur_s * self.sample_rate)
        t = np.arange(n) / self.sample_rate
        freq = 200.0 + (hash(word) % 50) * 20.0  # distinct, deterministic, audible-range tone
        return 0.5 * np.sin(2 * np.pi * freq * t)
