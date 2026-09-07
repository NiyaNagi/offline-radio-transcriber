"""Callsign <-> ITU/NATO phonetic token expansion (S1.4, D22).

A one-token-per-character mapping so expansion round-trips exactly: every letter, digit and
the portable-operation stroke has exactly one phonetic word, and no two characters share one.
"""
from __future__ import annotations

NATO_ALPHABET = {
    "A": "ALFA", "B": "BRAVO", "C": "CHARLIE", "D": "DELTA", "E": "ECHO", "F": "FOXTROT",
    "G": "GOLF", "H": "HOTEL", "I": "INDIA", "J": "JULIETT", "K": "KILO", "L": "LIMA",
    "M": "MIKE", "N": "NOVEMBER", "O": "OSCAR", "P": "PAPA", "Q": "QUEBEC", "R": "ROMEO",
    "S": "SIERRA", "T": "TANGO", "U": "UNIFORM", "V": "VICTOR", "W": "WHISKEY", "X": "XRAY",
    "Y": "YANKEE", "Z": "ZULU",
}

DIGIT_WORDS = {
    "0": "ZERO", "1": "ONE", "2": "TWO", "3": "THREE", "4": "FOUR",
    "5": "FIVE", "6": "SIX", "7": "SEVEN", "8": "EIGHT", "9": "NINE",
}

STROKE = "STROKE"

_CHAR_TO_TOKEN: dict[str, str] = {**NATO_ALPHABET, **DIGIT_WORDS, "/": STROKE}
_TOKEN_TO_CHAR: dict[str, str] = {v: k for k, v in _CHAR_TO_TOKEN.items()}


def expand(callsign: str) -> list[str]:
    """One phonetic token per character. Raises ValueError on any character with no mapping."""
    upper = callsign.upper()
    tokens: list[str] = []
    for ch in upper:
        if ch not in _CHAR_TO_TOKEN:
            raise ValueError(f"no phonetic token for character {ch!r} in callsign {callsign!r}")
        tokens.append(_CHAR_TO_TOKEN[ch])
    return tokens


def contract(tokens: list[str]) -> str:
    """The exact inverse of expand: one character per token."""
    chars = []
    for tok in tokens:
        if tok not in _TOKEN_TO_CHAR:
            raise ValueError(f"unknown phonetic token {tok!r}")
        chars.append(_TOKEN_TO_CHAR[tok])
    return "".join(chars)
