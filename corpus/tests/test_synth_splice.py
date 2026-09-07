"""Spliced units land at their declared offsets (S1.4, D22)."""
from __future__ import annotations

from corpus.synth.splice import place_units


def test_offsets_are_the_cumulative_sum_of_declared_durations():
    tokens = ["WHISKEY", "ONE", "ALFA", "WHISKEY"]
    durations = {"WHISKEY": 0.4, "ONE": 0.3, "ALFA": 0.35}
    gap_s = 0.05

    units = place_units(tokens, lambda tok: durations[tok], gap_s=gap_s)

    assert [u.text for u in units] == tokens
    expected_start = 0.0
    for tok, u in zip(tokens, units):
        assert u.start_s == expected_start
        assert u.dur_s == durations[tok]
        expected_start += durations[tok] + gap_s


def test_total_duration_matches_last_unit_end_plus_trailing_gap_removed():
    tokens = ["ALFA", "BRAVO"]
    durations = {"ALFA": 0.3, "BRAVO": 0.3}
    units = place_units(tokens, lambda tok: durations[tok], gap_s=0.1)
    total = units[-1].start_s + units[-1].dur_s
    assert total == 0.3 + 0.1 + 0.3
