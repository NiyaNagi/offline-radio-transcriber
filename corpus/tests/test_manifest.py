"""The manifest is the enforcement point (implementation-plan M0, §14A.2, FR-TST-7/8/9).

Write first: a manifest placing one session in two folds is rejected; a synthetic entry in
eval is rejected outright (FR-TST-9); every source carries source, licence and checksum.
"""
import json

import pytest

from corpus.manifest import ManifestError, load_manifest


def _base():
    return {
        "version": "0",
        "sources": [
            {
                "id": "paderborn-hf",
                "name": "Paderborn HF Ham Radio DB",
                "url": "https://zenodo.org/records/4247491",
                "licence": "CC-BY-4.0",
                "kind": "public",
                "synthetic": False,
                "upstream_checksum": {"algo": "md5", "value": "0" * 32},
            },
            {
                "id": "synth-callsigns",
                "name": "Synthetic callsign generator (D22)",
                "url": "local://corpus/M0.C",
                "licence": "generated",
                "kind": "synthetic",
                "synthetic": True,
                "upstream_checksum": {"algo": "sha256", "value": "1" * 64},
            },
        ],
        "sessions": [
            {
                "id": "paderborn-hf/eval-dx-01",
                "source": "paderborn-hf",
                "fold": "eval",
                "kind": "speech",
                "duration_s": 1800.0,
                "stations": ["DL8XYZ"],
                "audio": "sessions/paderborn-hf/eval-dx-01/audio.flac",
                "checksum": {"algo": "sha256", "value": "a" * 64},
            },
            {
                "id": "synth-callsigns/train-01",
                "source": "synth-callsigns",
                "fold": "train",
                "kind": "speech",
                "duration_s": 600.0,
                "stations": ["W1AW"],
                "audio": "sessions/synth-callsigns/train-01/audio.flac",
                "checksum": {"algo": "sha256", "value": "b" * 64},
            },
        ],
    }


def _write(tmp_path, doc):
    p = tmp_path / "manifest.json"
    p.write_text(json.dumps(doc), encoding="utf-8")
    return p


def test_valid_manifest_loads(tmp_path):
    m = load_manifest(_write(tmp_path, _base()))
    assert {s.id for s in m.sources} == {"paderborn-hf", "synth-callsigns"}
    assert m.session("paderborn-hf/eval-dx-01").fold == "eval"


def test_session_in_two_folds_is_rejected(tmp_path):
    doc = _base()
    dup = dict(doc["sessions"][0])
    dup["fold"] = "train"
    doc["sessions"].append(dup)
    with pytest.raises(ManifestError, match="two folds|more than one fold"):
        load_manifest(_write(tmp_path, doc))


def test_synthetic_session_in_eval_is_rejected(tmp_path):
    doc = _base()
    doc["sessions"][1]["fold"] = "eval"  # synth-callsigns -> eval
    with pytest.raises(ManifestError, match="synthetic.*eval|FR-TST-9"):
        load_manifest(_write(tmp_path, doc))


def test_source_missing_licence_is_rejected(tmp_path):
    doc = _base()
    del doc["sources"][0]["licence"]
    with pytest.raises(ManifestError, match="licence"):
        load_manifest(_write(tmp_path, doc))


def test_source_missing_checksum_is_rejected(tmp_path):
    doc = _base()
    del doc["sources"][0]["upstream_checksum"]
    with pytest.raises(ManifestError, match="checksum"):
        load_manifest(_write(tmp_path, doc))


def test_unknown_fold_is_rejected(tmp_path):
    doc = _base()
    doc["sessions"][0]["fold"] = "holdout"
    with pytest.raises(ManifestError, match="fold"):
        load_manifest(_write(tmp_path, doc))


def test_session_references_unknown_source(tmp_path):
    doc = _base()
    doc["sessions"][0]["source"] = "nope"
    with pytest.raises(ManifestError, match="unknown source|source"):
        load_manifest(_write(tmp_path, doc))
