"""Reading eval without an explicit flag is refused (AC-100); every emitted report carries
the fold that produced it (FR-TST-7, constitution "No number without its provenance")."""
import pytest

from corpus.gate import EvalFoldSealed, load_fold_sessions
from corpus.manifest import Manifest, Session, Source


def _manifest():
    src = [Source("s", "S", "u", "CC-BY-4.0", "public", False, {"algo": "md5", "value": "x"})]
    sess = [
        Session("s/t1", "s", "train", "speech", 60.0, ["A"], "a.flac", {"algo": "sha256", "value": "1"}),
        Session("s/d1", "s", "dev", "speech", 60.0, ["B"], "b.flac", {"algo": "sha256", "value": "2"}),
        Session("s/e1", "s", "eval", "speech", 60.0, ["C"], "c.flac", {"algo": "sha256", "value": "3"}),
    ]
    return Manifest("0", src, sess)


def test_dev_fold_opens_without_a_flag():
    got = load_fold_sessions(_manifest(), "dev")
    assert [s.id for s in got] == ["s/d1"]


def test_FR_TST_7_eval_fold_is_refused_without_the_flag():
    with pytest.raises(EvalFoldSealed):
        load_fold_sessions(_manifest(), "eval")


def test_FR_TST_7_eval_fold_opens_only_with_explicit_opt_in():
    got = load_fold_sessions(_manifest(), "eval", allow_eval=True)
    assert [s.id for s in got] == ["s/e1"]
