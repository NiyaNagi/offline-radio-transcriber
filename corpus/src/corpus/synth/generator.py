"""The synthetic callsign generator (S1.4, D22, FR-TST-9).

ULS callsigns -> phonetic expansion (``synth.phonetic``) -> TTS or spliced real audio
(``synth.tts``) placed at declared offsets (``synth.splice``) -> the learned channel
(``synth.channel``) -> labelled audio. The label is derived from the same placement the audio
was rendered from, so it cannot drift from what was actually rendered — labels always match
audio by construction, not by a separate bookkeeping step that could fall out of sync.

Real ULS callsigns and real spliced ISOLET/ATC clips are not substituted here: the fixture
list below stands in for "ULS callsigns" and ``FakeTtsVoice`` stands in for both local neural
TTS and spliced real audio (a splice is, from this module's point of view, just another
``TtsVoice`` that happens to return a real recorded clip instead of a synthesised one — the
same interface, so wiring a real splice source in later requires no change here).
"""
from __future__ import annotations

from typing import Any

import numpy as np

from .channel import ChannelModel, apply_channel
from .phonetic import expand
from .splice import place_units
from .tts import FakeTtsVoice, TtsVoice

__all__ = ["EvalFoldForbidden", "FakeTtsVoice", "generate_dataset", "generate_utterance"]


class EvalFoldForbidden(ValueError):
    """Synthetic output must never enter the eval fold (FR-TST-9) — structural, not a habit."""


# A handful of ULS-style callsigns standing in for a real ULS extract, per the design in
# functional-spec §14A.3 — "ULS callsigns -> phonetic expansion -> ...". Widening this to the
# full ULS database is acquisition work, not generator logic, and belongs alongside the other
# `corpus acquire` sources when that data is fetched.
ULS_CALLSIGN_FIXTURE = ["W1AW", "K2ABC", "N7XYZ/P", "VE7/K7ABC", "DL8XYZ"]


def generate_utterance(
    callsign: str, voice: TtsVoice, channel: ChannelModel, *, gap_s: float = 0.05
) -> tuple[np.ndarray, dict[str, Any]]:
    """Render one callsign's phonetic spelling through ``voice`` and ``channel``.

    Returns ``(audio, label)`` where ``label`` records the callsign, its token sequence, and
    each unit's declared start/duration — computed from the exact same placement used to
    render the audio, so the label cannot disagree with what is actually in the buffer.
    """
    tokens = expand(callsign)
    placed = place_units(tokens, voice.duration_of, gap_s=gap_s)

    sr = voice.sample_rate
    total_s = placed[-1].start_s + placed[-1].dur_s if placed else 0.0
    buf = np.zeros(round(total_s * sr), dtype=np.float64)
    for unit in placed:
        clip = voice.synth(unit.text)
        start = round(unit.start_s * sr)
        buf[start : start + len(clip)] = clip[: len(buf) - start]

    audio = apply_channel(buf, channel) if (channel.gain != 1.0 or channel.tilt or channel.noise_rms) else buf

    label = {
        "callsign": callsign.upper(),
        "tokens": tokens,
        "sample_rate": sr,
        "units": [{"text": u.text, "start_s": u.start_s, "dur_s": u.dur_s} for u in placed],
        "channel": channel.to_dict(),
    }
    return audio, label


def generate_dataset(
    callsigns: list[str],
    *,
    fold: str,
    voice: TtsVoice,
    channel: ChannelModel,
    gap_s: float = 0.05,
) -> list[tuple[np.ndarray, dict[str, Any]]]:
    """Generate labelled utterances for a whole fold. Refuses ``eval`` outright (FR-TST-9)."""
    if fold == "eval":
        raise EvalFoldForbidden(
            "synthetic data must never appear in the eval fold (FR-TST-9) — "
            "the generator refuses to produce eval output at all, not just at manifest time"
        )
    items = []
    for cs in callsigns:
        audio, label = generate_utterance(cs, voice, channel, gap_s=gap_s)
        label["fold"] = fold
        items.append((audio, label))
    return items
