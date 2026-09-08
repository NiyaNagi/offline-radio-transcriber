"""S1.3 — the learned channel model (D22).

Applying the fitted channel to a clean source must reproduce the *measured* degradation
within tolerance, and the transform must be deterministic (same clean + same model -> the
same output, byte for byte).

The real Paderborn parallel clean/degraded pairs are not present in this sandbox (see
results/r4-speaker-separation.md and results/r1-lora-export.md for what could and could not be
run here). These tests fit the model against a *known*, fixture-constructed degradation so the
fitting algorithm itself is verified independently of the corpus being acquired.
"""
from __future__ import annotations

import numpy as np

from corpus.synth.channel import ChannelModel, _hf_energy_ratio, apply_channel, fit_channel


def _clean_fixture(n: int = 16000, seed: int = 1) -> np.ndarray:
    rng = np.random.default_rng(seed)
    t = np.arange(n) / 16000.0
    tone = 0.3 * np.sin(2 * np.pi * 440.0 * t)
    return (tone + 0.05 * rng.normal(size=n)).astype(np.float64)


def test_fit_then_apply_reproduces_measured_degradation_within_tolerance():
    clean = _clean_fixture()
    reference = ChannelModel(gain=0.6, noise_rms=0.02, tilt=0.4, seed=7)
    degraded = apply_channel(clean, reference)

    fitted = fit_channel(clean, degraded, seed=7)
    reconstructed = apply_channel(clean, fitted)

    # measured degradation = RMS level and spectral tilt of the real degraded signal
    def rms(x: np.ndarray) -> float:
        return float(np.sqrt(np.mean(x**2)))

    # the measured degradation is the RMS level and spectral tilt of the real degraded
    # signal — reproducing those, not recovering the internal fit parameters exactly, is
    # what "applying the channel reproduces the measured degradation" means
    assert rms(reconstructed) == _approx(rms(degraded), rel=0.15)
    assert fitted.gain == _approx(reference.gain, rel=0.2)
    assert _hf_energy_ratio(reconstructed) == _approx(_hf_energy_ratio(degraded), rel=0.3)


def test_apply_channel_is_deterministic():
    clean = _clean_fixture()
    model = ChannelModel(gain=0.8, noise_rms=0.01, tilt=0.2, seed=42)
    a = apply_channel(clean, model)
    b = apply_channel(clean, model)
    assert np.array_equal(a, b)


def test_apply_channel_differs_from_input_when_degraded():
    clean = _clean_fixture()
    model = ChannelModel(gain=0.5, noise_rms=0.03, tilt=0.5, seed=1)
    degraded = apply_channel(clean, model)
    assert not np.array_equal(clean, degraded)
    assert degraded.shape == clean.shape


def test_channel_model_round_trips_through_dict():
    m = ChannelModel(gain=0.7, noise_rms=0.02, tilt=0.3, seed=3)
    assert ChannelModel.from_dict(m.to_dict()) == m


def _approx(value: float, rel: float = 0.0, abs_tol: float = 0.0):
    class _Approx:
        def __eq__(self, other):
            tol = max(rel * abs(value), abs_tol)
            return abs(other - value) <= tol

        def __repr__(self):
            return f"approx({value}, rel={rel}, abs_tol={abs_tol})"

    return _Approx()
