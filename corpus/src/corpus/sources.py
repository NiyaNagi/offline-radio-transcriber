"""The public corpus source registry (functional-spec §14A.3, D21, implementation-plan M0.A).

Four public sources, fetched and verified, normalised to 16 kHz mono FLAC. Each entry records
its URL, licence and an upstream checksum. Checksums marked ``pending`` are pinned on the
first successful fetch and committed; a mismatch thereafter is a hard failure.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass
class SourceSpec:
    id: str
    name: str
    url: str
    licence: str
    kind: str  # public | synthetic | recorded
    covers: str
    fold_policy: str  # train | dev | eval | mixed — §14A.2/§14A.3 guidance
    synthetic: bool = False
    upstream_checksum: dict[str, str] = field(
        default_factory=lambda: {"algo": "sha256", "value": "pending"}
    )

    def to_source_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "url": self.url,
            "licence": self.licence,
            "kind": self.kind,
            "synthetic": self.synthetic,
            "upstream_checksum": self.upstream_checksum,
        }


SOURCES: list[SourceSpec] = [
    SourceSpec(
        id="paderborn-hf",
        name="Paderborn HF Ham Radio DB",
        url="https://zenodo.org/records/4247491",
        licence="CC-BY-4.0",
        kind="public",
        covers="Real off-air amateur HF, parallel clean+degraded; channel character, VAD/SAD",
        fold_policy="eval",  # §14A.2 step 6 — HF/DX traffic tests FR-LEX-8
    ),
    SourceSpec(
        id="fearless-steps",
        # NOT a plain HTTP GET target — `corpus acquire fearless-steps` will fail against this
        # URL. Fearless Steps requires a manual, human registration step before any file is
        # downloadable (challenge form + data-portal request, or full-corpus request form /
        # email); see docs/reference/fearless-steps-acquisition.md for the exact steps and the
        # local staging path this source expects once acquired.
        name="Fearless Steps (Apollo-11)",
        url="https://fearless-steps.github.io/ChallengePhase2/",
        licence=(
            "100h Challenge Corpus: CC-BY-4.0 (registration required to download). "
            "Full 19,000h corpus: NASA Media Usage Guidelines, request-form gated."
        ),
        kind="public",
        covers="Degraded analog comms loops with diarization labels; speaker separation",
        fold_policy="mixed",
    ),
    SourceSpec(
        id="atc-merge",
        name="ATC merge (ATCO2 test set + UWB-ATCC)",
        url="https://www.atco2.org/data",
        licence="ATCO2 test set CC-BY-NC-SA-4.0; UWB-ATCC CC-BY-NC-SA-3.0",
        kind="public",
        covers="ICAO phonetic alphabet, callsign grammar, procedural phraseology",
        fold_policy="mixed",
    ),
    SourceSpec(
        id="isolet",
        name="ISOLET spoken letter names",
        url="https://archive.ics.uci.edu/dataset/54/isolet",
        licence="CC-BY-4.0",
        kind="public",
        covers="Spoken letter-name forms (AC-12's second pronunciation path)",
        fold_policy="train",  # never eval — letter-name inventory, not real traffic
    ),
    SourceSpec(
        id="synth-callsigns",
        name="Synthetic callsign generator (D22)",
        url="local://corpus/M0.C",
        licence="generated",
        kind="synthetic",
        synthetic=True,
        covers="ULS callsigns -> phonetic expansion -> TTS + spliced ISOLET/ATC -> channel model",
        fold_policy="train",  # FR-TST-9 — synthetic never enters eval
    ),
    SourceSpec(
        id="local",
        name="Local recordings (validation hour + two noise tapes)",
        url="local://corpus/M0.3-M0.4",
        licence="own recordings, not redistributed",
        kind="recorded",
        covers="Real amateur conversational callsign exchanges; squelch-only noise",
        fold_policy="mixed",
        upstream_checksum={"algo": "sha256", "value": "n/a — recorded, per-session checksums"},
    ),
]


def source_by_id(sid: str) -> SourceSpec:
    for s in SOURCES:
        if s.id == sid:
            return s
    raise KeyError(sid)
