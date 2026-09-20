"""`ort-analytics` — the one entry point for the ingest server and the report generator (D48)."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import duckdb

import loader
import report
import server


def _cmd_serve(args: argparse.Namespace) -> None:
    server.serve(args.data_dir, host=args.host, port=args.port, cert=args.cert, key=args.key)


def _cmd_report(args: argparse.Namespace) -> None:
    con = duckdb.connect(":memory:")
    loader.load_events(con, args.data_dir, fold=args.fold)
    text = report.generate_report(con)
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8")
    else:
        print(text)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="ort-analytics", description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    serve_parser = subparsers.add_parser("serve", help="Run the reference ingest server.")
    serve_parser.add_argument("--data-dir", required=True)
    serve_parser.add_argument("--host", default="0.0.0.0")
    serve_parser.add_argument("--port", type=int, default=8443)
    serve_parser.add_argument("--cert", default=None)
    serve_parser.add_argument("--key", default=None)
    serve_parser.set_defaults(func=_cmd_serve)

    report_parser = subparsers.add_parser("report", help="Generate a text report from ingested data.")
    report_parser.add_argument("--data-dir", required=True)
    report_parser.add_argument("--fold", default="field", help="Must be 'field' (FR-ANL-12); any other value is refused.")
    report_parser.add_argument("--out", default=None, help="Write the report here instead of stdout.")
    report_parser.set_defaults(func=_cmd_report)

    args = parser.parse_args(argv)
    args.func(args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
