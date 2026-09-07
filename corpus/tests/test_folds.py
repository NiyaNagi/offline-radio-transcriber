"""The fold-assignment checker enforcing §14A.2: whole sessions only, at least one eval
session containing no train station, the two noise tapes separated."""
import pytest

from corpus.folds import FoldViolation, check_folds
from corpus.manifest import Manifest, Session, Source

SRC = [
    Source("hf", "Paderborn HF", "u", "CC-BY-4.0", "public", False, {"algo": "md5", "value": "x"}),
    Source("loc", "Local recordings", "local://", "own", "recorded", False, {"algo": "sha256", "value": "y"}),
]


def _s(sid, fold, kind, stations, source="hf"):
    return Session(sid, source, fold, kind, 60.0, stations, f"{sid}.flac",
                   {"algo": "sha256", "value": sid})


def _ok_manifest():
    return Manifest("0", SRC, [
        _s("hf/train-1", "train", "speech", ["W1AW", "K2ABC"]),
        _s("hf/dev-1", "dev", "speech", ["W1AW"]),
        _s("hf/eval-dx", "eval", "speech", ["DL8XYZ", "VK3ABC"]),  # no train station
        _s("loc/noise-eval", "eval", "noise", [], source="loc"),
        _s("loc/noise-dev", "dev", "noise", [], source="loc"),
    ])


def test_a_well_formed_corpus_passes():
    assert check_folds(_ok_manifest()) == []


def test_eval_with_only_train_stations_fails():
    m = _ok_manifest()
    m.sessions[2] = _s("hf/eval-dx", "eval", "speech", ["W1AW"])
    violations = check_folds(m)
    assert any("no train station" in v.message for v in violations)


def test_both_noise_tapes_in_eval_fails():
    m = _ok_manifest()
    m.sessions[4] = _s("loc/noise-dev", "eval", "noise", [], source="loc")
    violations = check_folds(m)
    assert any("noise" in v.message for v in violations)


def test_only_one_noise_tape_fails():
    m = _ok_manifest()
    m.sessions = [s for s in m.sessions if s.id != "loc/noise-dev"]
    assert any("noise" in v.message for v in check_folds(m))


def test_session_split_across_folds_fails():
    m = _ok_manifest()
    m.sessions.append(_s("hf/train-1", "dev", "speech", ["W1AW", "K2ABC"]))
    assert any("whole session" in v.message.lower() or "two fold" in v.message.lower()
               for v in check_folds(m))


def test_check_folds_raises_when_asked():
    m = _ok_manifest()
    m.sessions = [s for s in m.sessions if s.kind != "noise"]
    with pytest.raises(FoldViolation):
        check_folds(m, raise_on_violation=True)
