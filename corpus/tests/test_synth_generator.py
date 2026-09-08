"""The synthetic callsign generator (S1.4, D22).

ULS callsigns -> phonetic expansion -> TTS (+ spliced real letter/digit audio when supplied)
-> the learned channel -> labelled audio. Labels always match audio; output is structurally
barred from the eval fold (FR-TST-9).
"""
from __future__ import annotations

import numpy as np
import pytest

from corpus.synth.channel import ChannelModel
from corpus.synth.generator import (
    EvalFoldForbidden,
    FakeTtsVoice,
    generate_dataset,
    generate_utterance,
)


def test_label_always_matches_audio():
    voice = FakeTtsVoice()
    channel = ChannelModel(gain=1.0, noise_rms=0.0, tilt=0.0, seed=0)
    audio, label = generate_utterance("W1AW", voice, channel)

    assert label["callsign"] == "W1AW"
    assert label["tokens"] == ["WHISKEY", "ONE", "ALFA", "WHISKEY"]
    # every declared unit's [start, start+dur) window lies inside the audio
    for unit in label["units"]:
        start = int(unit["start_s"] * label["sample_rate"])
        end = int((unit["start_s"] + unit["dur_s"]) * label["sample_rate"])
        assert 0 <= start <= end <= len(audio)
    last = label["units"][-1]
    assert abs(len(audio) / label["sample_rate"] - (last["start_s"] + last["dur_s"])) < 1e-6


def test_spliced_units_land_at_declared_offsets_in_the_rendered_audio():
    voice = FakeTtsVoice()
    channel = ChannelModel(gain=1.0, noise_rms=0.0, tilt=0.0, seed=0)
    audio, label = generate_utterance("K2ABC", voice, channel)

    for unit in label["units"]:
        start = int(unit["start_s"] * label["sample_rate"])
        dur_samples = int(unit["dur_s"] * label["sample_rate"])
        window = audio[start : start + dur_samples]
        # the fake voice never renders silence for a real token, so the declared window
        # is not all-zero — proving the unit actually landed where the label says it did
        assert np.any(window != 0.0)


def test_phonetic_expansion_round_trips_through_the_label():
    from corpus.synth.phonetic import contract

    voice = FakeTtsVoice()
    channel = ChannelModel(gain=1.0, noise_rms=0.0, tilt=0.0, seed=0)
    _, label = generate_utterance("N7XYZ/P", voice, channel)
    assert contract(label["tokens"]) == "N7XYZ/P"


def test_generated_output_is_barred_from_the_eval_fold():
    voice = FakeTtsVoice()
    channel = ChannelModel(gain=1.0, noise_rms=0.0, tilt=0.0, seed=0)
    with pytest.raises(EvalFoldForbidden):
        generate_dataset(["W1AW"], fold="eval", voice=voice, channel=channel)


@pytest.mark.parametrize("fold", ["train", "dev"])
def test_generated_dataset_accepts_train_and_dev(fold):
    voice = FakeTtsVoice()
    channel = ChannelModel(gain=1.0, noise_rms=0.0, tilt=0.0, seed=0)
    items = generate_dataset(["W1AW", "K2ABC"], fold=fold, voice=voice, channel=channel)
    assert len(items) == 2
    assert all(label["fold"] == fold for _audio, label in items)
