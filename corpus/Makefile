# Corpus pipeline (functional-spec §14A.2/§14A.3, implementation-plan M0).
PY ?= python

.PHONY: corpus acquire check-folds test lint

## corpus: render + validate the committed four-source manifest, then run the fold gate
corpus:
	$(PY) -m corpus build
	$(PY) -m corpus check-folds

## acquire: fetch + verify + normalise every public source (resumable, idempotent)
acquire:
	$(PY) -m corpus acquire paderborn-hf
	$(PY) -m corpus acquire fearless-steps
	$(PY) -m corpus acquire atc-merge
	$(PY) -m corpus acquire isolet

check-folds:
	$(PY) -m corpus check-folds

test:
	$(PY) -m pytest

lint:
	$(PY) -m ruff check .
