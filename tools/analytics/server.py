"""The thin `http.server` wrapper around `ingest.IngestApp` (D48).

Deliberately stdlib-only — this is a reference/self-hosted destination, not a product the shipped
Android app depends on, and adding a web framework dependency here would be one more thing to keep
patched for a component the app itself never talks to except by plain HTTPS POST/DELETE.

Routes:
  POST   /ingest           body: NDJSON, one `:telemetry` `AnalyticsEvent` per line
  DELETE /install/<id>     FR-ANL-11: purge every row for that install id

TLS: pass `--cert`/`--key` to terminate HTTPS directly; without them this serves plain HTTP,
suitable for a reverse proxy (nginx, Caddy) to terminate TLS in front of, or for local development
only. `ORT_ANALYTICS_ENDPOINT` on the Android side is always an `https://` URL in any real
deployment (D48) — this module does not enforce that itself, since a local dev loop over plain
HTTP is a legitimate, common use of this same code path.
"""
from __future__ import annotations

import argparse
import json
import ssl
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote

from ingest import IngestApp


def make_handler(app: IngestApp) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        def _write_json(self, status: int, body: dict) -> None:
            payload = json.dumps(body).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def do_POST(self):
            if self.path != "/ingest":
                self._write_json(404, {"error": "not found"})
                return
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length) if length else b""
            result = app.ingest_ndjson(body)
            self._write_json(
                200,
                {
                    "accepted": result.accepted,
                    "skippedMalformed": result.skipped_malformed,
                    "skippedPurged": result.skipped_purged,
                },
            )

        def do_DELETE(self):
            prefix = "/install/"
            if not self.path.startswith(prefix):
                self._write_json(404, {"error": "not found"})
                return
            install_id = unquote(self.path[len(prefix) :])
            if not install_id:
                self._write_json(400, {"error": "missing install id"})
                return
            result = app.purge_install_id(install_id)
            self._write_json(200, {"installId": result.install_id, "rowsRemoved": result.rows_removed})

        def log_message(self, format: str, *args) -> None:
            pass  # quiet by default; the reference server is not the product's own log surface.

    return Handler


def serve(data_dir: str | Path, host: str = "0.0.0.0", port: int = 8443, cert: str | None = None, key: str | None = None) -> None:
    app = IngestApp(data_dir)
    httpd = ThreadingHTTPServer((host, port), make_handler(app))
    if cert and key:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(certfile=cert, keyfile=key)
        httpd.socket = context.wrap_socket(httpd.socket, server_side=True)
    httpd.serve_forever()


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Reference analytics ingest server (D48).")
    parser.add_argument("--data-dir", required=True, help="Directory to write NDJSON partitions under.")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8443)
    parser.add_argument("--cert", default=None, help="TLS certificate file (optional).")
    parser.add_argument("--key", default=None, help="TLS private key file (optional).")
    args = parser.parse_args(argv)
    serve(args.data_dir, host=args.host, port=args.port, cert=args.cert, key=args.key)


if __name__ == "__main__":
    main()
