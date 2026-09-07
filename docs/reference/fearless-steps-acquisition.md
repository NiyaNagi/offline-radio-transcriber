# Acquiring Fearless Steps (R4's corpus)

**Why this is manual.** `corpus acquire <source>` (build-plan P2) does a plain authenticated-free
HTTP GET against `SourceSpec.url`. Fearless Steps has no such URL — every path to it, including
the smaller Creative-Commons subset, sits behind a human registration step at the University of
Texas at Dallas (CRSS). No amount of automation collapses that; someone has to actually register.
This is R4's blocker as of 2026-09-07 (see `results/r4-speaker-separation.md`).

There are two tiers. **Start with the smaller one** — it's CC-licensed, much smaller, and it's
the one R4 actually needs (a diarization-labelled dev/eval split, not the whole mission archive).

## Tier 1 — the 100-hour Challenge Corpus (recommended, do this first)

Diarization labels for exactly R4's use case ship as the Challenge's Task 3 (Speaker
Diarization, "SD") stream.

1. Go to <https://fearless-steps.github.io/ChallengePhase2/> (Phase 2 is the SD-bearing phase;
   check for a newer phase first — the site listed Phase 4 as of 2026-09-07, and later phases may
   have superseded or re-hosted the same data).
2. Register via the challenge sign-up form linked from that page.
3. Once registered, follow the **"FSC-2 Data"** link in the site navigation to the data portal
   and request/download the SD track: the 100-hour audio (8 kHz) plus its diarization metadata.
4. Licence: **CC-BY-4.0** for the audio + metadata once downloaded. Keep the licence file/text
   that ships with the download — `corpus`'s manifest records licence per source (§14A.3) and
   this is what gets cited.
5. Place the downloaded archive at:
   ```
   corpus-data/raw/fearless-steps/fearless-steps-challenge-sd.<ext>
   ```
   (or wherever you ran `corpus acquire --dest`; the convention is `<dest>/raw/<source-id>/`).
6. Since this didn't come through the automated fetcher, don't run `corpus acquire
   fearless-steps` as-is — it'll try to GET the info page above and fail. Either:
   - hand-write the `<dest>/fearless-steps.acquired.json` marker the way `acquire_source()`
     would have (see `corpus/src/corpus/acquire.py` for the exact shape: `source`, `name`, `url`,
     `licence`, `upstream_checksum`, `entries[]` with `member`/`audio`/`checksum`), normalising
     the audio to 16 kHz mono FLAC under `<dest>/sessions/fearless-steps/...` first
     (`corpus.audio.to_flac_16k_mono`); or
   - ask whoever picks this up next to extend `acquire_source`/`_cmd_acquire` with a
     `--local-archive PATH` option that skips the HTTP fetch and normalises a file already on
     disk — not built yet because no real archive existed to test it against.
7. Re-run `corpus build` so the committed manifest's checksum/entries reflect the real files
   (currently `corpus/manifest.json`'s `fearless-steps` entries are placeholders).

## Tier 2 — the full ~19,000-hour corpus (only if R4's dev-fold result demands more, or M6 needs
broader coverage later)

1. Submit the request form linked from the Challenge Phase-01 page, or email
   `FearlessSteps@utdallas.edu` directly, requesting full-corpus access.
2. This is reviewed by CRSS, not instant — budget real turnaround time (days), not a session.
3. Licence: **NASA Media Usage Guidelines** (NASA-origin audio content is generally not
   copyrighted, but the corpus packaging/annotations carry their own terms — read what UT Dallas
   sends with the grant).
4. Budget storage: reported as ~19,000 hours across 600+ speakers and 30+ channels — plan for
   multiple terabytes, not the tens-of-GB Tier 1 needs.
5. Same placement/marker/manifest steps as Tier 1, once it's on disk.

## After either tier lands

Re-run the R4 probe for a real verdict:

```
python -c "from corpus.probes.speaker_separation import probe_r4; ..."
```

(or whatever entry point exists by then — `corpus/src/corpus/probes/speaker_separation.py`'s
`probe_r4(fearless_steps_dir=...)` is the seam; a real `Embedder` — WeSpeaker or 3D-Speaker,
still not installed anywhere in this environment — needs wiring in alongside it.) Replace
`results/r4-speaker-separation.md`'s `NOT RUN` verdict with the real separation and false-match
numbers, reported against Fearless Steps's own diarization split, not folded into this project's
train/dev/eval (constitution VI).
