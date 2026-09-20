"""The DuckDB loader (constitution VI): loads the reference ingest server's own NDJSON partitions
into a queryable view — and refuses, structurally, to load anything but the `field` fold.

FR-ANL-12: "Analytics data collected in the field SHALL form its own `field` fold... which SHALL
NEVER be mixed with `dev` and SHALL NEVER touch `eval`." This module is the one place that
guarantee is enforced on the analysis side, mirroring `corpus`' own eval-fold seal
(`corpus.cli`'s `--fold eval` refusal) for the identical reason: a number derived from the wrong
fold is not evidence, and the refusal has to live in code, not in a convention someone remembers.
"""
from __future__ import annotations

from pathlib import Path

import duckdb

FIELD_FOLD = "field"


class FoldViolation(Exception):
    """Raised when anything but the `field` fold is requested — this loader has no other fold to
    give, by design: `dev`/`eval` are corpus-side folds this analytics pipeline never touches."""


def load_events(
    con: duckdb.DuckDBPyConnection,
    data_dir: str | Path,
    *,
    fold: str = FIELD_FOLD,
    view_name: str = "events",
) -> str:
    """Registers a `view_name` view over every NDJSON partition under [data_dir]. Returns the view
    name for convenience chaining."""
    if fold != FIELD_FOLD:
        raise FoldViolation(
            f"analytics data has only the '{FIELD_FOLD}' fold (FR-ANL-12) — refusing to load '{fold}'",
        )
    pattern = str(Path(data_dir) / "schema-*" / "*.ndjson")
    con.execute(
        f"CREATE OR REPLACE VIEW {view_name} AS "
        f"SELECT * FROM read_ndjson_auto('{pattern}', union_by_name=true, ignore_errors=true)",
    )
    return view_name


def assert_only_field_fold(con: duckdb.DuckDBPyConnection, view_name: str = "events") -> None:
    """AC-182's own runtime check, for a caller that wants to verify what actually landed rather
    than trust [load_events] alone — e.g. after loading a directory this module did not itself
    write (a hand-rolled fixture, a copied production export)."""
    rows = con.execute(f"SELECT DISTINCT fold FROM {view_name}").fetchall()
    folds = {row[0] for row in rows}
    if folds - {FIELD_FOLD}:
        raise FoldViolation(f"loaded rows carry a fold other than '{FIELD_FOLD}': {folds}")
