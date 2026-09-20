"""AC-182/FR-ANL-12: the DuckDB loader refuses to load anything but the `field` fold."""
import sys
from pathlib import Path

import duckdb
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import loader

FIXTURE_DATA_DIR = Path(__file__).resolve().parent / "fixtures" / "data"


def test_load_events_registers_a_queryable_view():
    con = duckdb.connect(":memory:")
    loader.load_events(con, FIXTURE_DATA_DIR)

    count = con.execute("SELECT COUNT(*) FROM events").fetchone()[0]
    assert count == 8


def test_AC_182_every_loaded_row_carries_the_field_fold():
    con = duckdb.connect(":memory:")
    loader.load_events(con, FIXTURE_DATA_DIR)

    folds = {row[0] for row in con.execute("SELECT DISTINCT fold FROM events").fetchall()}
    assert folds == {"field"}


@pytest.mark.parametrize("bad_fold", ["dev", "eval", "train", ""])
def test_AC_182_loading_a_non_field_fold_is_refused(bad_fold):
    con = duckdb.connect(":memory:")

    with pytest.raises(loader.FoldViolation):
        loader.load_events(con, FIXTURE_DATA_DIR, fold=bad_fold)


def test_assert_only_field_fold_passes_on_the_real_fixture():
    con = duckdb.connect(":memory:")
    loader.load_events(con, FIXTURE_DATA_DIR)

    loader.assert_only_field_fold(con)  # must not raise


def test_assert_only_field_fold_catches_a_row_that_should_never_exist(tmp_path):
    partition = tmp_path / "schema-1"
    partition.mkdir()
    (partition / "2026-09-19.ndjson").write_text(
        '{"fold": "eval", "tier": "TIER_1", "provenance": {"schemaVersion": 1}, "payload": {}}\n',
        encoding="utf-8",
    )
    con = duckdb.connect(":memory:")
    # load_events itself would refuse fold="eval" -- this proves the independent runtime check
    # catches contamination even when data was written outside this loader's own control.
    con.execute(
        f"CREATE VIEW events AS SELECT * FROM read_ndjson_auto('{tmp_path}/schema-*/*.ndjson', union_by_name=true)",
    )

    with pytest.raises(loader.FoldViolation):
        loader.assert_only_field_fold(con)
