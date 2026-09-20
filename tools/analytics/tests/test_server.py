"""The real `http.server` wiring, over an actual loopback socket on an ephemeral port — proves
the routing, not just `IngestApp`'s own already-covered decision logic."""
import http.client
import json
import sys
import threading
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from http.server import ThreadingHTTPServer

from ingest import IngestApp
from server import make_handler


@pytest.fixture()
def running_server(tmp_path):
    app = IngestApp(tmp_path)
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(app))
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    try:
        yield httpd, tmp_path
    finally:
        httpd.shutdown()
        thread.join(timeout=5)


def sample_event(install_id="install-1"):
    return {
        "tier": "TIER_1",
        "provenance": {
            "installId": install_id,
            "sessionId": None,
            "overId": None,
            "appVersion": "0.1.1",
            "buildHash": "abc123",
            "modelIds": [],
            "modelShas": [],
            "executionProvider": "cpu",
            "deviceModel": "Pixel 7",
            "soc": "Tensor G2",
            "detectedTier": "T2",
            "captureMode": None,
            "rigModule": None,
            "band": None,
            "schemaVersion": 1,
        },
        "payload": {"payloadType": "tier1_usage", "screen": "SETTINGS", "action": "OPEN"},
    }


def test_post_ingest_accepts_ndjson_and_writes_a_partition(running_server):
    httpd, data_dir = running_server
    body = json.dumps(sample_event()).encode("utf-8")

    conn = http.client.HTTPConnection("127.0.0.1", httpd.server_address[1])
    conn.request("POST", "/ingest", body=body, headers={"Content-Type": "application/x-ndjson"})
    response = conn.getresponse()
    payload = json.loads(response.read())
    conn.close()

    assert response.status == 200
    assert payload["accepted"] == 1
    assert any(data_dir.glob("schema-1/*.ndjson"))


def test_delete_install_purges_a_previously_ingested_row(running_server):
    httpd, _data_dir = running_server
    body = json.dumps(sample_event(install_id="to-purge")).encode("utf-8")
    conn = http.client.HTTPConnection("127.0.0.1", httpd.server_address[1])
    conn.request("POST", "/ingest", body=body)
    conn.getresponse().read()
    conn.close()

    conn = http.client.HTTPConnection("127.0.0.1", httpd.server_address[1])
    conn.request("DELETE", "/install/to-purge")
    response = conn.getresponse()
    payload = json.loads(response.read())
    conn.close()

    assert response.status == 200
    assert payload["rowsRemoved"] == 1


def test_an_unknown_path_is_404(running_server):
    httpd, _ = running_server
    conn = http.client.HTTPConnection("127.0.0.1", httpd.server_address[1])
    conn.request("POST", "/not-a-real-endpoint", body=b"{}")
    response = conn.getresponse()
    response.read()
    conn.close()

    assert response.status == 404
