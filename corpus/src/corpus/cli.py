"""``corpus`` command line (implementation-plan M0).

    corpus build [--check]                 render / validate the committed manifest
    corpus check-folds [MANIFEST]          run the §14A.2 fold checker
    corpus acquire SOURCE [--dest DIR]     fetch + verify + normalise one public source
    corpus evaluate --fold F --transcripts FILE [--i-know-this-is-the-eval-fold]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .build import COMMITTED_MANIFEST, build_manifest, check_committed
from .folds import check_folds
from .gate import OPT_IN_FLAG, EvalFoldSealed
from .harness import evaluate
from .manifest import load_manifest
from .sources import source_by_id


def _cmd_build(args: argparse.Namespace) -> int:
    if args.check:
        if check_committed():
            print(f"OK: {COMMITTED_MANIFEST} is up to date and valid")
            return 0
        print(f"DRIFT: {COMMITTED_MANIFEST} does not match `corpus build` output", file=sys.stderr)
        return 1
    out = build_manifest(args.output or COMMITTED_MANIFEST)
    m = load_manifest(out)
    pub = [s.id for s in m.sources if s.kind == "public"]
    print(f"wrote {out} — {len(pub)} public sources {pub}, {len(m.sessions)} sessions, folds OK")
    return 0


def _cmd_check_folds(args: argparse.Namespace) -> int:
    m = load_manifest(args.manifest or COMMITTED_MANIFEST)
    violations = check_folds(m)
    if not violations:
        print("fold check: OK (§14A.2)")
        return 0
    for v in violations:
        print(f"[{v.rule}] {v.message}", file=sys.stderr)
    return 1


def _cmd_acquire(args: argparse.Namespace) -> int:
    src = source_by_id(args.source)
    if src.kind != "public":
        print(f"{src.id} is {src.kind}, not a fetchable public source", file=sys.stderr)
        return 2
    import urllib.request

    def fetcher(url: str, dest, resume_from: int = 0) -> None:
        req = urllib.request.Request(url)
        if resume_from:
            req.add_header("Range", f"bytes={resume_from}-")
        with urllib.request.urlopen(req) as resp, open(dest, "ab" if resume_from else "wb") as fh:
            while chunk := resp.read(1 << 20):
                fh.write(chunk)

    from .acquire import acquire_source

    record = acquire_source(src, args.dest, fetcher=fetcher)
    print(json.dumps(record, indent=2))
    return 0


def _cmd_evaluate(args: argparse.Namespace) -> int:
    m = load_manifest(args.manifest or COMMITTED_MANIFEST)
    transcripts = json.loads(Path(args.transcripts).read_text(encoding="utf-8-sig"))
    try:
        report = evaluate(m, args.fold, transcripts, allow_eval=args.allow_eval)
    except EvalFoldSealed as exc:
        print(f"refused: {exc}", file=sys.stderr)
        return 3
    if args.output:
        report.write(args.output)
        print(f"wrote {args.output} and {Path(args.output).with_suffix('.md')}")
    else:
        print(json.dumps(report.to_dict(), indent=2, sort_keys=True))
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="corpus", description=__doc__)
    sub = p.add_subparsers(dest="cmd", required=True)

    b = sub.add_parser("build", help="render/validate the committed manifest")
    b.add_argument("--check", action="store_true", help="fail if the committed file has drifted")
    b.add_argument("-o", "--output")
    b.set_defaults(func=_cmd_build)

    c = sub.add_parser("check-folds", help="run the §14A.2 fold checker")
    c.add_argument("manifest", nargs="?")
    c.set_defaults(func=_cmd_check_folds)

    a = sub.add_parser("acquire", help="fetch + verify + normalise one public source")
    a.add_argument("source")
    a.add_argument("--dest", default="corpus-data")
    a.set_defaults(func=_cmd_acquire)

    e = sub.add_parser("evaluate", help="run harness v0 over hand transcripts")
    e.add_argument("--manifest")
    e.add_argument("--fold", required=True)
    e.add_argument("--transcripts", required=True)
    e.add_argument(OPT_IN_FLAG, dest="allow_eval", action="store_true",
                   help="explicitly open the sealed eval fold (do this once, at M11)")
    e.add_argument("-o", "--output")
    e.set_defaults(func=_cmd_evaluate)
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
