from corpus.metrics import (
    attribution_accuracy,
    boundary_pr,
    callsign_pr,
    rejection_rate_by_reason,
    wer,
)


def test_wer_counts_subs_ins_del():
    assert wer(["the", "quick", "brown", "fox"], ["the", "quick", "brown", "fox"]) == 0.0
    assert wer(["a", "b", "c", "d"], ["a", "x", "c"]) == 0.5  # 1 sub + 1 del over 4


def test_callsign_pr_is_multiset():
    p, r, f = callsign_pr(["W1AW", "K2ABC", "W1AW"], ["W1AW", "K2ABC"])
    assert (round(p, 3), round(r, 3)) == (1.0, round(2 / 3, 3))
    assert 0.0 < f < 1.0


def test_boundary_pr_uses_tolerance():
    ref = [(1000, 2000), (5000, 6000)]
    hyp = [(1050, 1980), (9000, 9500)]
    p, r, _ = boundary_pr(ref, hyp, tol_ms=100)
    assert (p, r) == (0.5, 0.5)


def test_rejection_rate_by_reason():
    recs = [
        {"accepted": True, "reason": None},
        {"accepted": False, "reason": "squelch-noise"},
        {"accepted": False, "reason": "squelch-noise"},
        {"accepted": False, "reason": "low-confidence"},
    ]
    out = rejection_rate_by_reason(recs)
    assert out["overall"] == 0.75
    assert out["by_reason"]["squelch-noise"] == 0.5


def test_attribution_accuracy():
    recs = [
        {"predicted": "W1AW", "truth": "W1AW"},
        {"predicted": "K2ABC", "truth": "W1AW"},
        {"predicted": None, "truth": None},
    ]
    assert round(attribution_accuracy(recs), 3) == round(2 / 3, 3)
