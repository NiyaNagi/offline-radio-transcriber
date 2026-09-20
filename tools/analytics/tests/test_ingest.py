"""AC-182 (fold), AC-181 (purge): the reference ingest server's request logic."""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import ingest


def event(install_id="install-1", schema_version=1, tier="TIER_1", fold_claim=None):
    payload = {
        "tier": tier,
        "provenance": {
            "installId": install_id,
            "sessionId": "session-1",
            "overId": None,
            "appVersion": "0.1.1",
            "buildHash": "abc123",
            "modelIds": [],
            "modelShas": [],
            "executionProvider": "cpu",
            "deviceModel": "Pixel 7",
            "soc": "Tensor G2",
            "detectedTier": "T2",
            "captureMode": "LOCAL_MICROPHONE",
            "rigModule": None,
            "band": None,
            "schemaVersion": schema_version,
        },
        "payload": {"payloadType": "tier1_usage", "screen": "SETTINGS", "action": "OPEN"},
    }
    if fold_claim is not None:
        payload["fold"] = fold_claim
    return payload


def ndjson(*events) -> bytes:
    return "\n".join(json.dumps(e) for e in events).encode("utf-8")


def read_all_rows(data_dir: Path) -> list[dict]:
    rows = []
    for path in sorted(data_dir.glob("schema-*/*.ndjson")):
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                rows.append(json.loads(line))
    return rows


def test_AC_182_every_ingested_row_is_stamped_field_regardless_of_client_claim(tmp_path):
    app = ingest.IngestApp(tmp_path)

    result = app.ingest_ndjson(ndjson(event(fold_claim="eval"), event(fold_claim="dev")))

    rows = read_all_rows(tmp_path)
    assert result.accepted == 2
    assert all(row["fold"] == "field" for row in rows), rows


def test_AC_182_rows_never_land_tagged_dev_or_eval(tmp_path):
    app = ingest.IngestApp(tmp_path)
    app.ingest_ndjson(ndjson(event()))

    rows = read_all_rows(tmp_path)
    assert {row["fold"] for row in rows} == {"field"}


def test_rows_partition_by_schema_version_and_date(tmp_path):
    app = ingest.IngestApp(tmp_path)
    app.ingest_ndjson(ndjson(event(schema_version=1)), today="2026-09-19")
    app.ingest_ndjson(ndjson(event(schema_version=2)), today="2026-09-19")

    assert (tmp_path / "schema-1" / "2026-09-19.ndjson").exists()
    assert (tmp_path / "schema-2" / "2026-09-19.ndjson").exists()


def test_a_malformed_line_is_skipped_without_losing_the_rest_of_the_batch(tmp_path):
    app = ingest.IngestApp(tmp_path)
    body = json.dumps(event()).encode("utf-8") + b"\nnot json at all\n" + json.dumps(event()).encode("utf-8")

    result = app.ingest_ndjson(body)

    assert result.accepted == 2
    assert result.skipped_malformed == 1


def test_AC_181_purge_removes_every_row_for_the_install_id(tmp_path):
    app = ingest.IngestApp(tmp_path)
    app.ingest_ndjson(ndjson(event(install_id="old-id"), event(install_id="other-id")))

    result = app.purge_install_id("old-id")

    rows = read_all_rows(tmp_path)
    assert result.rows_removed == 1
    assert all(row["provenance"]["installId"] != "old-id" for row in rows)
    assert any(row["provenance"]["installId"] == "other-id" for row in rows)


def test_AC_181_a_purged_install_id_is_refused_on_a_later_ingest_too(tmp_path):
    app = ingest.IngestApp(tmp_path)
    app.ingest_ndjson(ndjson(event(install_id="old-id")))
    app.purge_install_id("old-id")

    result = app.ingest_ndjson(ndjson(event(install_id="old-id")))

    rows = read_all_rows(tmp_path)
    assert result.skipped_purged == 1
    assert all(row["provenance"]["installId"] != "old-id" for row in rows)


def test_purging_an_id_with_no_rows_yet_is_a_harmless_no_op():
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        app = ingest.IngestApp(tmp)
        result = app.purge_install_id("never-seen")
        assert result.rows_removed == 0
