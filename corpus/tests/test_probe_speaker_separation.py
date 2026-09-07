"""R4 probe plumbing — WeSpeaker/3D-Speaker style embed + cluster + measure (S1.6).

Fearless Steps is not present in this sandbox (see results/r4-speaker-separation.md for why).
These tests exercise the clustering and metric math against a FakeEmbedder, which is the
behavioural fake the real embedder plugs in behind (constitution II).
"""
from __future__ import annotations

from corpus.probes.speaker_separation import (
    FakeEmbedder,
    cluster,
    false_match_rate,
    probe_r4,
    separation,
)


def _labelled_fixture():
    # three speakers, several utterances each; FakeEmbedder is keyed on speaker id so
    # same-speaker embeddings are identical and different-speaker embeddings are distinct
    return [
        {"speaker": "A", "utterance": "a1"},
        {"speaker": "A", "utterance": "a2"},
        {"speaker": "B", "utterance": "b1"},
        {"speaker": "B", "utterance": "b2"},
        {"speaker": "C", "utterance": "c1"},
    ]


def test_fake_embedder_is_deterministic_per_speaker():
    e = FakeEmbedder()
    v1 = e.embed({"speaker": "A", "utterance": "a1"})
    v2 = e.embed({"speaker": "A", "utterance": "a2"})
    v3 = e.embed({"speaker": "B", "utterance": "b1"})
    assert (v1 == v2).all()
    assert not (v1 == v3).all()


def test_cluster_recovers_speaker_groups_on_well_separated_embeddings():
    items = _labelled_fixture()
    e = FakeEmbedder()
    embeddings = [e.embed(i) for i in items]
    labels = cluster(embeddings, threshold=0.5)
    # items 0,1 (speaker A) must cluster together; item 2,3 (B) together; distinct from A and C
    assert labels[0] == labels[1]
    assert labels[2] == labels[3]
    assert len({labels[0], labels[2], labels[4]}) == 3


def test_false_match_rate_is_zero_at_a_strict_threshold_on_separated_speakers():
    items = _labelled_fixture()
    e = FakeEmbedder()
    embeddings = [e.embed(i) for i in items]
    truth = [i["speaker"] for i in items]
    assert false_match_rate(embeddings, truth, threshold=0.99) == 0.0


def test_separation_is_positive_when_speakers_are_distinguishable():
    items = _labelled_fixture()
    e = FakeEmbedder()
    embeddings = [e.embed(i) for i in items]
    truth = [i["speaker"] for i in items]
    assert separation(embeddings, truth) > 0.0


def test_probe_reports_not_run_when_the_dataset_is_absent(tmp_path):
    result = probe_r4(fearless_steps_dir=tmp_path / "does-not-exist")
    assert result.status == "not_run"
    assert "Fearless Steps" in result.reason


def test_probe_runs_the_fixture_dry_run_when_asked_explicitly():
    result = probe_r4(fearless_steps_dir=None, dry_run_fixture=_labelled_fixture())
    assert result.status == "dry_run"
    assert result.fingerprint
    assert 0.0 <= result.false_match_rate <= 1.0
