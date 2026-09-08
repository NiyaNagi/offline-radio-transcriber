"""The learned channel model (S1.3, D22).

Fit from Paderborn's parallel clean/degraded pairs (176 h of the same speech clean *and*
received) and exposed as a deterministic transform, so the synthetic generator degrades TTS
and spliced audio the way the real channel measurably does, rather than by guessing filter
and noise parameters.

The fit is closed-form (RMS ratio for gain, an FFT energy ratio for spectral tilt, and the
residual RMS after removing the shaped signal for the noise floor) — no iterative optimiser,
so refitting the same pair is byte-identical given the same numpy version (constitution:
"determinism is bounded and the bound is stated").
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np


@dataclass(frozen=True)
class ChannelModel:
    """A degradation transform: gain, one-pole spectral tilt, and an additive noise floor."""

    gain: float
    noise_rms: float
    tilt: float  # one-pole low-pass coefficient in [0, 1) — higher loses more high frequency
    seed: int = 0

    def to_dict(self) -> dict:
        return {"gain": self.gain, "noise_rms": self.noise_rms, "tilt": self.tilt, "seed": self.seed}

    @staticmethod
    def from_dict(d: dict) -> ChannelModel:
        return ChannelModel(gain=d["gain"], noise_rms=d["noise_rms"], tilt=d["tilt"], seed=d.get("seed", 0))


def _one_pole_lowpass(x: np.ndarray, tilt: float) -> np.ndarray:
    if tilt <= 0.0:
        return x.copy()
    y = np.empty_like(x)
    prev = 0.0
    for i, v in enumerate(x):
        prev = (1 - tilt) * v + tilt * prev
        y[i] = prev
    return y


def _hf_energy_ratio(x: np.ndarray) -> float:
    spec = np.abs(np.fft.rfft(x.astype(np.float64)))
    half = max(len(spec) // 2, 1)
    lo = float(np.sum(spec[:half]) + 1e-9)
    hi = float(np.sum(spec[half:]) + 1e-9)
    return hi / lo


def fit_channel(clean: np.ndarray, degraded: np.ndarray, *, seed: int = 0) -> ChannelModel:
    """Fit gain, spectral tilt and noise floor from one parallel clean/degraded pair."""
    n = min(len(clean), len(degraded))
    clean, degraded = np.asarray(clean[:n], dtype=np.float64), np.asarray(degraded[:n], dtype=np.float64)

    clean_rms = float(np.sqrt(np.mean(clean**2)) + 1e-12)
    degraded_rms = float(np.sqrt(np.mean(degraded**2)) + 1e-12)
    gain = degraded_rms / clean_rms

    tilt_ratio = _hf_energy_ratio(degraded) / _hf_energy_ratio(clean)
    tilt = float(np.clip(1.0 - tilt_ratio, 0.0, 0.95))

    shaped = _one_pole_lowpass(clean * gain, tilt)
    residual = degraded - shaped
    noise_rms = float(np.sqrt(np.mean(residual**2)))

    return ChannelModel(gain=gain, noise_rms=noise_rms, tilt=tilt, seed=seed)


def apply_channel(clean: np.ndarray, model: ChannelModel) -> np.ndarray:
    """Apply the channel to a clean source. Same input + same model -> identical output."""
    clean = np.asarray(clean, dtype=np.float64)
    rng = np.random.default_rng(model.seed)
    shaped = _one_pole_lowpass(clean * model.gain, model.tilt)
    noise = rng.normal(0.0, model.noise_rms, size=shaped.shape) if model.noise_rms > 0 else 0.0
    return shaped + noise
