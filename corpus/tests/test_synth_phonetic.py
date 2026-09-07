"""Phonetic expansion of a callsign round-trips (S1.4, D22)."""
from __future__ import annotations

import pytest

from corpus.synth.phonetic import contract, expand


@pytest.mark.parametrize("callsign", ["W1AW", "K2ABC", "N7XYZ/P", "VE7/K7ABC", "DL8XYZ"])
def test_expand_then_contract_round_trips(callsign):
    tokens = expand(callsign)
    assert contract(tokens) == callsign.upper()


def test_expand_uses_one_token_per_character():
    tokens = expand("W1AW")
    assert tokens == ["WHISKEY", "ONE", "ALFA", "WHISKEY"]


def test_expand_rejects_unknown_characters():
    with pytest.raises(ValueError):
        expand("W1AW!")


def test_expand_handles_the_stroke_modifier():
    tokens = expand("K7ABC/P")
    assert "STROKE" in tokens
    assert contract(tokens) == "K7ABC/P"
