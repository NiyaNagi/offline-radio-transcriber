# R4 — speaker separation on degraded narrowband voice

**Gate:** functional-spec §14A.3, R4 (spec/build-plan.md S1.6). Decides whether M6 (persistent
voice library, D28) and D29 (station knowledge, which leans on identity) proceed as specified.

## Verdict: NOT RUN

The real probe — a WeSpeaker or 3D-Speaker embedder over Fearless Steps, clustered and measured
against its own diarization labels — **was not executed** in this session. This is stated
plainly rather than hedged, per the instruction that a poor *or absent* result here must not be
papered over with an invented number (constitution VI: "No number without its provenance").

### Why it could not run here

- **Corpus.** Fearless Steps (Apollo-11) is ~19,000 hours, 600+ speakers, distributed under a
  NASA/Mendeley research-use agreement (see `corpus/src/corpus/sources.py`, id
  `fearless-steps`). Obtaining it requires a request/acceptance step and a multi-hundred-GB
  download that this session did not perform. No copy of it, or any subset of it, exists
  anywhere in this environment.
- **Model.** Neither `wespeaker` nor `speechbrain`/`3D-Speaker`-equivalent packages are
  installed (checked via `importlib.util.find_spec`; both return `False`). No GPU is present
  (`nvidia-smi` is not found). Installing and running a pretrained embedder over a corpus this
  size was out of scope for what this session could respect the fold discipline and the
  no-fabrication rule while attempting.
- **Network** was reachable in this sandbox (confirmed via a plain HTTPS request), so the
  blocker is the dataset's access terms and the missing embedder toolchain, not connectivity.

### What was built instead (test-first, in this change)

`corpus/src/corpus/probes/speaker_separation.py` — the full clustering and metric pipeline the
real probe would run: an `Embedder` protocol (the seam a real WeSpeaker/3D-Speaker model plugs
in behind), union-find cosine-similarity clustering, `false_match_rate` and `separation`
(mean same-speaker minus mean different-speaker cosine similarity). `FakeEmbedder` is the
behavioural fake shipped in the same change (constitution II) — deterministic per speaker id,
distinct across speakers.

`corpus/tests/test_probe_speaker_separation.py` proves this math is correct: clustering
recovers the right speaker groups, the false-match rate is zero at a strict threshold on
well-separated fake embeddings, and separation is positive when speakers differ. This
establishes that when the real corpus and embedder are available, the probe's *measurement
code* will produce a trustworthy number — it is the model and data that are missing, not the
harness.

### Mechanical dry run (not a measurement of R4)

`probe_r4(fearless_steps_dir=None, dry_run_fixture=<5-utterance, 3-speaker fixture>)` was run
against `FakeEmbedder` to prove the pipeline composes end to end. This is **not** a measurement
of real speaker separation — it says nothing about WeSpeaker/3D-Speaker on Fearless Steps.

| Field | Value |
|---|---|
| Status | `dry_run` (fixture, not Fearless Steps) |
| Corpus | fixture (5 utterances, 3 synthetic speaker ids) — **not** Fearless Steps |
| Split | n/a — fixture has no train/dev/eval structure |
| Model | `FakeEmbedder` (deterministic per speaker id) — **not** WeSpeaker/3D-Speaker |
| Separation | 1.065 (mean same-speaker cosine similarity − mean different-speaker) |
| False-match rate @ 0.5 / 0.7 / 0.9 | 0.0 / 0.0 / 0.0 |
| Fingerprint | `1b3e52738b1f4ad16054b18779ae0797dd9802ed3a3740361133aefeebc2c911` |
| Machine | Windows-10-10.0.19045-SP0, Python 3.13.1 |
| Code version | `2eec8a7d2bd39bef8a4a0f6f514798caecfd13ce` |

A false-match rate of exactly 0.0 here reflects `FakeEmbedder` being maximally separable by
construction (different speaker ids map to statistically independent random unit vectors in
8 dimensions) — it is not evidence about real narrowband voice.

## Consequence

Per the prompt: *"A poor result deletes much of M6 and part of D29, so say so plainly rather
than hedging."* No result — poor or good — exists yet, so **M6 and D29 remain provisionally
as specified, unconfirmed.** This probe is a hard prerequisite before M6 begins:

1. Acquire Fearless Steps (accept its research-use terms; budget for its size).
2. Install a WeSpeaker or 3D-Speaker embedder (or an equivalent open pretrained speaker
   embedding model) — CPU inference is likely adequate for a clustering probe of this shape,
   so the missing GPU is not expected to block it once the model is installed.
3. Re-run `corpus.probes.speaker_separation.probe_r4` against the real dataset path with a real
   `Embedder`, and replace this document's verdict with a real `status="ran"` result reporting
   separation and false-match rate at multiple thresholds, **against Fearless Steps's own
   diarization split**, stated as such (constitution VI: third-party corpora keep their own
   splits; a Fearless Steps number is never folded into this project's train/dev/eval).
