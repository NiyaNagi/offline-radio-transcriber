"""`make corpus` produces a validated four-source manifest with licences, and the fold gate
refuses eval without the flag (implementation-plan M0 exit criteria)."""
import json

import pytest

from corpus.build import COMMITTED_MANIFEST, build_manifest, check_committed
from corpus.folds import check_folds
from corpus.gate import EvalFoldSealed, load_fold_sessions
from corpus.manifest import load_manifest


def test_committed_manifest_exists_and_is_valid():
    m = load_manifest(COMMITTED_MANIFEST)
    assert check_folds(m) == []


def test_committed_manifest_has_the_four_public_sources_with_licences():
    m = load_manifest(COMMITTED_MANIFEST)
    public = {s.id: s for s in m.sources if s.kind == "public"}
    assert set(public) == {"paderborn-hf", "fearless-steps", "atc-merge", "isolet"}
    for s in public.values():
        assert s.licence
        assert s.upstream_checksum


def test_build_is_deterministic_and_matches_the_committed_file():
    assert check_committed() is True


def test_build_writes_a_manifest(tmp_path):
    out = tmp_path / "manifest.json"
    build_manifest(out)
    doc = json.loads(out.read_text())
    assert doc["sessions"]
    assert {s["id"] for s in doc["sources"] if s["kind"] == "public"} == {
        "paderborn-hf", "fearless-steps", "atc-merge", "isolet"
    }


def test_fold_gate_refuses_eval_on_the_committed_manifest():
    m = load_manifest(COMMITTED_MANIFEST)
    with pytest.raises(EvalFoldSealed):
        load_fold_sessions(m, "eval")
    assert load_fold_sessions(m, "eval", allow_eval=True)
