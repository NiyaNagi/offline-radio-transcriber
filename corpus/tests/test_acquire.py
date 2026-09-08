"""Acquisition: fetch and verify, normalise to 16 kHz mono FLAC, record source/licence/
checksum per entry, resumable and idempotent (implementation-plan M0.A)."""
import hashlib

import pytest

from corpus.acquire import acquire_source
from corpus.sources import SOURCES, source_by_id


def test_the_four_public_sources_are_registered():
    ids = {s.id for s in SOURCES if s.kind == "public"}
    assert ids == {"paderborn-hf", "fearless-steps", "atc-merge", "isolet"}
    for s in SOURCES:
        assert s.licence
        assert s.url


class FakeFetcher:
    """Writes `payload` to the destination; records call count and resume offsets."""

    def __init__(self, payload: bytes, fail_after: int | None = None):
        self.payload = payload
        self.calls: list[int] = []
        self.fail_after = fail_after

    def __call__(self, url: str, dest, resume_from: int = 0) -> None:
        self.calls.append(resume_from)
        data = self.payload[resume_from:]
        if self.fail_after is not None:
            data = data[: self.fail_after]
        with open(dest, "ab" if resume_from else "wb") as fh:
            fh.write(data)


def _fake_normaliser(src, dst):
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_bytes(b"FLAC" + src.read_bytes())


def test_acquire_fetches_verifies_and_records(tmp_path, monkeypatch):
    payload = b"raw-archive-bytes" * 100
    digest = hashlib.sha256(payload).hexdigest()
    src = source_by_id("isolet")
    monkeypatch.setattr(src, "upstream_checksum", {"algo": "sha256", "value": digest})

    record = acquire_source(
        src, tmp_path, fetcher=FakeFetcher(payload), normaliser=_fake_normaliser,
        members=["a.wav", "b.wav"],
    )
    assert record["source"] == "isolet"
    assert record["licence"] == src.licence
    assert record["upstream_checksum"]["value"] == digest
    for entry in record["entries"]:
        assert entry["audio"].endswith(".flac")
        assert entry["checksum"]["algo"] == "sha256"


def test_acquire_is_idempotent(tmp_path, monkeypatch):
    payload = b"bytes" * 50
    digest = hashlib.sha256(payload).hexdigest()
    src = source_by_id("isolet")
    monkeypatch.setattr(src, "upstream_checksum", {"algo": "sha256", "value": digest})
    fetcher = FakeFetcher(payload)

    acquire_source(src, tmp_path, fetcher=fetcher, normaliser=_fake_normaliser, members=["a.wav"])
    acquire_source(src, tmp_path, fetcher=fetcher, normaliser=_fake_normaliser, members=["a.wav"])
    assert len(fetcher.calls) == 1  # second run downloads nothing


def test_acquire_resumes_a_partial_download(tmp_path, monkeypatch):
    payload = b"0123456789" * 20
    digest = hashlib.sha256(payload).hexdigest()
    src = source_by_id("isolet")
    monkeypatch.setattr(src, "upstream_checksum", {"algo": "sha256", "value": digest})

    broken = FakeFetcher(payload, fail_after=50)
    with pytest.raises(ValueError, match="checksum mismatch"):
        acquire_source(src, tmp_path, fetcher=broken, normaliser=_fake_normaliser, members=["a.wav"])

    good = FakeFetcher(payload)
    acquire_source(src, tmp_path, fetcher=good, normaliser=_fake_normaliser, members=["a.wav"])
    assert good.calls == [50]  # resumed from the partial offset


def test_checksum_mismatch_raises(tmp_path, monkeypatch):
    src = source_by_id("isolet")
    monkeypatch.setattr(src, "upstream_checksum", {"algo": "sha256", "value": "d" * 64})
    with pytest.raises(ValueError, match="checksum"):
        acquire_source(src, tmp_path, fetcher=FakeFetcher(b"x" * 10),
                       normaliser=_fake_normaliser, members=["a.wav"])
