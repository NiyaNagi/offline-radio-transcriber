# `corpus/` — corpus pipeline

Desktop tooling for the offline-radio-transcriber evaluation and training corpus. Python, its
own `pyproject.toml`, its own CI job. Governed by [`AGENTS.md`](../AGENTS.md); bound by the
fold discipline in [functional-spec §14A.2/§14A.3](../spec/functional-spec.md) and
[implementation-plan M0](../spec/implementation-plan.md).

> **You cannot un-see the eval fold.** The gate is a tool, not a habit — see below.

## Layout

| Path | What |
|---|---|
| `src/corpus/manifest.py` | Manifest model + validation. A session in two folds, or synthetic audio in `eval`, is a load-time error (FR-TST-7/9) |
| `src/corpus/folds.py` | The §14A.2 fold checker — whole sessions, an eval session with no train station, the two noise tapes separated, HF/DX in eval |
| `src/corpus/gate.py` | `load_fold_sessions` — refuses `eval` without `allow_eval=True` / `--i-know-this-is-the-eval-fold` (AC-100) |
| `src/corpus/sources.py` | The four public sources (Paderborn HF, Fearless Steps, ATC merge, ISOLET) + synthetic + local, each with URL, licence, checksum |
| `src/corpus/acquire.py` | Fetch → verify → normalise → record. Resumable (`.part` offset) and idempotent (`.acquired.json` marker) |
| `src/corpus/audio.py` | Deterministic normalisation to 16 kHz mono FLAC via ffmpeg |
| `src/corpus/metrics.py` | WER, callsign P/R, boundary P/R, rejection rate by reason, attribution accuracy |
| `src/corpus/harness.py` | Harness v0 — metrics over hand transcripts, per source **and** aggregate (FR-TST-8), stamped with fold + run fingerprint |
| `src/corpus/report.py` | Report writer. Refuses to exist without a fold; refuses to serialise without a fingerprint |
| `manifest.json` | The committed, validated four-source manifest. Rendered by `corpus build`; CI asserts no drift |

## Commands

```bash
make corpus          # render + validate the manifest, then run the fold gate
make acquire         # fetch + verify + normalise every public source
make test            # pytest (strict TDD)
make lint            # ruff

python -m corpus build [--check]
python -m corpus check-folds [MANIFEST]
python -m corpus acquire paderborn-hf --dest corpus-data
python -m corpus evaluate --fold dev --transcripts hand.json -o results/dev
python -m corpus evaluate --fold eval --transcripts hand.json --i-know-this-is-the-eval-fold
```

## The eval fold

`train` and `dev` are open. `eval` is sealed until M11 and every entry point into it refuses
without an explicit, loud opt-in. Opening it happens exactly once, and it is not recoverable.
The `dev` noise tape (`local/noise-dev`) is unsealed so AC-6 is testable from M3 (§14A.2).
