#!/usr/bin/env python3
"""spec/ui-conformance-plan.md WP12 - compares two screenshot-tour runs and reports which screens
changed, without a device or an emulator.

    python tools/ui-audit/diff.py --before <dir-or-git-ref> --after results/ui-audit \
        --manifest results/ui-audit/tour-manifest.json

``--after`` is a directory laid out the way ``tour.ps1`` already writes it: ``<id>.png`` per step
(``<id>`` may itself contain ``/``, e.g. ``overnight/N01-now.png``), plus the JSONL manifest
``tour.ps1`` renames to ``tour-manifest.json``. ``--before`` is either a second such directory (an
earlier ``tour.ps1`` run, pulled to a different path) or a git ref (``main``, a commit hash, a tag)
- in the git-ref form, each file is read with ``git show <ref>:<path>``, where ``<path>`` is that
PNG's path *relative to the repository root*, computed from ``--after``'s own location (this only
works when ``--after``'s screenshots are the ones committed under ``results/ui-audit/`` at that
ref, which is the case this tool exists for: "did WP<n>'s change move this screen").

R-1116 (register; constitution VIII, and the second half of R-1083/R-1103's own finding): this
tool used to return exit 0 on every outcome except a missing manifest file, never looked at the
``runId``/``apkHash`` fields ``tour.ps1`` (R-1083) already stamps onto every manifest entry, and
compared screenshots with a 4x4 mean-RGB signature at ``--threshold 6.0`` - coarse enough that a
one-line text difference (measured below) moved the signature by roughly 1.0, an order of
magnitude under the old default. A tool that cannot fail, and cannot see the exact class of defect
(a wrong word, a missing label, a changed caption) this register is mostly made of, made every
"unchanged" verdict it ever printed worthless as evidence. Fixed two ways:

1. **The comparison is now a per-pixel difference fraction, not a coarse signature.** Every pixel
   outside the excluded status-bar band is compared channel-by-channel (R+G+B summed, alpha
   ignored); a pixel counts as "differing" when that sum exceeds ``--pixel-diff-threshold`` (default
   24 of a possible 765 - roughly 8 per channel, which a lossless PNG re-decode never crosses on its
   own: see below). ``--threshold`` is now the *percentage of pixels* (0-100) that must differ this
   way before two screenshots are called ``changed`` - a different unit than the old 4x4 signature's
   0-255 mean, so an old invocation's ``--threshold`` value means something different now; this is a
   deliberate, documented break, not an oversight (see CHANGELOG.md, R-1116).

   Measured on real committed captures under ``results/ui-audit/`` (git history of
   ``overnight/CF02-settings-capture.png`` and ``overnight/N01-now.png`` - see CHANGELOG.md for the
   exact commits and numbers): a pure decode-then-re-encode round trip of the same PNG (the "PNG
   re-encoding is not guaranteed byte-identical" case this docstring already warned about) produced
   **0.0%** differing pixels at every ``--pixel-diff-threshold`` tried - re-encoding noise lives
   entirely in the compressed bytes, never in the decoded pixels, so comparing decoded pixels
   (which this tool always did, Pillow or the stdlib fallback) is already immune to it. The smallest
   *genuine* content difference measured - two captures of the same screen id with only its seeded
   date/time strings differing - produced **0.19%-0.21%** differing pixels across
   ``--pixel-diff-threshold`` 16-80. A one-line text reflow (a label's wrap point moved by a few
   characters, still the same words) produced **0.81%-0.95%**. Real multi-feature revisions of the
   same screen across a larger commit span produced **8.0%-11.0%**. The chosen default,
   ``--threshold 0.05`` (0.05%), sits under the smallest genuine signal by a factor of about 4 and
   over the measured re-encoding noise floor (0.0%) by construction; ``--pixel-diff-threshold``'s
   exact value in the 16-80 range barely moved any of these numbers (all four measured points stayed
   within 0.15 percentage points of each other across that whole range), so 24 was picked near the
   middle rather than tuned to one case.

   This project's screenshots come from one deterministic emulator/build per invocation (no
   compositor jitter, no real wall clock in a debug fixture's seeded data), so no same-build,
   same-scenario pair exists in git history to measure a true "genuinely identical, re-captured
   twice" noise floor beyond the re-encoding round trip above; if a future run surfaces one (a
   blinking cursor, a live timer that was not supposed to be in a captured frame), record its
   measured fraction here and raise the default if it exceeds the current margin - never lower it
   without a matching measurement.

2. **A manifest's own ``runId``/``apkHash`` are read and checked for internal consistency before
   any image is compared.** ``tour.ps1`` (R-1083) stamps a single run's id and APK hash onto every
   entry it writes for that invocation; a manifest whose entries disagree - two different ``runId``
   or ``apkHash`` values among its own entries - is not one coherent, evidenced capture, whatever its
   pixels say, and this tool now refuses to compare it: exit non-zero, before writing any report,
   naming the field and the conflicting values. This is deliberately a *self-consistency* check
   within one manifest, not a cross-check between ``--before`` and ``--after`` - those are legitimately
   different runs by design (that is the whole point of a before/after diff) and are expected to
   disagree. When ``--before`` is a directory, its own ``tour-manifest.json`` (if present alongside
   its screenshots, as ``tour.ps1`` always writes one) is read and checked the same way.

Every step in the manifest lands in exactly one of four lists:

- ``new``     - no ``--before`` image exists for this id at all.
- ``missing`` - a ``--before`` image exists but this run's manifest has no ``ok`` entry for it
                (the step errored, or was dropped from a ``tour.ps1 -Only`` run).
- ``changed`` / ``unchanged`` - both images exist; the perceptual check decided. A dimension
                mismatch (different width or height) is always ``changed`` - two images that are not
                even the same shape are not "unchanged" by any reading of the word.

Writes ``<after>/tour-diff.md`` and also prints the four lists to stdout. Uses Pillow if the
running interpreter has it (more robust PNG decoding - palettes, interlacing, 16-bit); otherwise
falls back to a small stdlib-only decoder (zlib + struct) that covers the common case this tool's
own screenshots are actually encoded as: non-interlaced 8-bit RGB/RGBA, which is what
``android.graphics.Bitmap.compress(PNG, ...)`` produces (confirmed by reading
``TourRunner.kt``'s own `writePng` before writing this) - a PNG outside that case fails loudly with
a message naming Pillow as the fix, never a silent wrong answer. The per-pixel comparison itself is
plain Python (no numpy dependency) - about a second per full-size (1080x2400) screenshot pair,
measured directly; acceptable for a periodic verification tool, not a hot path.
"""
from __future__ import annotations

import argparse
import json
import struct
import subprocess
import sys
import zlib
from dataclasses import dataclass
from pathlib import Path

try:
    from PIL import Image  # type: ignore

    _HAVE_PIL = True
except ImportError:  # pragma: no cover - exercised only when Pillow is absent
    _HAVE_PIL = False


@dataclass
class DecodedImage:
    width: int
    height: int
    channels: int  # 3 (RGB) or 4 (RGBA)
    pixels: bytes  # row-major, `channels` bytes per pixel, no padding


class DimensionMismatch(Exception):
    """Raised by `pixel_diff_fraction` when the two images are not the same width and height."""


class RunIdentityError(Exception):
    """R-1116: raised when a manifest's own entries disagree about which run produced them -
    more than one distinct `runId` or `apkHash` among entries that are supposed to be one
    invocation's output. Never raised for a manifest that simply lacks the fields (all entries
    agreeing on the "unknown" default is self-consistent, just uninformative)."""


def _paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    if pb <= pc:
        return b
    return c


def _decode_png_stdlib(data: bytes) -> DecodedImage:
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG file (bad signature)")
    pos = 8
    width = height = bit_depth = color_type = interlace = None
    idat = bytearray()
    while pos < len(data):
        length = struct.unpack(">I", data[pos : pos + 4])[0]
        ctype = data[pos + 4 : pos + 8]
        chunk = data[pos + 8 : pos + 8 + length]
        if ctype == b"IHDR":
            width, height, bit_depth, color_type, _comp, _filt, interlace = struct.unpack(">IIBBBBB", chunk)
        elif ctype == b"IDAT":
            idat.extend(chunk)
        elif ctype == b"IEND":
            break
        pos += 8 + length + 4  # length + type + data + crc
    if width is None:
        raise ValueError("PNG had no IHDR chunk")
    if interlace != 0:
        raise ValueError("interlaced PNG not supported by the stdlib fallback decoder - install Pillow")
    if bit_depth != 8:
        raise ValueError(f"bit depth {bit_depth} not supported by the stdlib fallback decoder - install Pillow")
    if color_type == 2:
        channels = 3
    elif color_type == 6:
        channels = 4
    else:
        raise ValueError(f"color type {color_type} not supported by the stdlib fallback decoder - install Pillow")

    raw = zlib.decompress(bytes(idat))
    stride = width * channels
    out = bytearray(stride * height)
    prev_row = bytearray(stride)
    src_pos = 0
    for y in range(height):
        filter_type = raw[src_pos]
        src_pos += 1
        row = bytearray(raw[src_pos : src_pos + stride])
        src_pos += stride
        for x in range(stride):
            a = row[x - channels] if x >= channels else 0
            b = prev_row[x]
            c = prev_row[x - channels] if x >= channels else 0
            if filter_type == 0:
                pass
            elif filter_type == 1:
                row[x] = (row[x] + a) & 0xFF
            elif filter_type == 2:
                row[x] = (row[x] + b) & 0xFF
            elif filter_type == 3:
                row[x] = (row[x] + (a + b) // 2) & 0xFF
            elif filter_type == 4:
                row[x] = (row[x] + _paeth(a, b, c)) & 0xFF
            else:
                raise ValueError(f"unknown PNG filter type {filter_type}")
        out[y * stride : (y + 1) * stride] = row
        prev_row = row
    return DecodedImage(width=width, height=height, channels=channels, pixels=bytes(out))


def decode_png(data: bytes) -> DecodedImage:
    if _HAVE_PIL:
        image = Image.open(__import__("io").BytesIO(data)).convert("RGBA")
        return DecodedImage(width=image.width, height=image.height, channels=4, pixels=image.tobytes())
    return _decode_png_stdlib(data)


def pixel_diff_fraction(
    before: DecodedImage,
    after: DecodedImage,
    status_bar_fraction: float,
    pixel_channel_threshold: int,
) -> float:
    """R-1116: the fraction (0.0-1.0) of pixels, outside the top `status_bar_fraction` of the
    image, whose summed |R-R|+|G-G|+|B-B| exceeds `pixel_channel_threshold`. Alpha is ignored -
    these are opaque app screenshots. Replaces the old 4x4 mean-RGB signature, which diluted a
    small, real, localized change (a line of text) below any sane threshold - see this module's
    own docstring for the measurements that picked the defaults.

    Raises `DimensionMismatch` if the two images are not the same width and height - a caller
    should treat that as `changed`, never as `unchanged` or a decode failure.
    """
    if before.width != after.width or before.height != after.height:
        raise DimensionMismatch(
            f"{before.width}x{before.height} vs {after.width}x{after.height}"
        )
    width, height = before.width, before.height
    skip_rows = int(height * status_bar_fraction)
    b_channels, a_channels = before.channels, after.channels
    b_stride = width * b_channels
    a_stride = width * a_channels
    b_pixels, a_pixels = before.pixels, after.pixels

    total = 0
    differing = 0
    for y in range(skip_rows, height):
        b_row = b_pixels[y * b_stride : (y + 1) * b_stride]
        a_row = a_pixels[y * a_stride : (y + 1) * a_stride]
        for x in range(width):
            bp = x * b_channels
            ap = x * a_channels
            d = (
                abs(b_row[bp] - a_row[ap])
                + abs(b_row[bp + 1] - a_row[ap + 1])
                + abs(b_row[bp + 2] - a_row[ap + 2])
            )
            if d > pixel_channel_threshold:
                differing += 1
            total += 1
    return (differing / total) if total else 0.0


def read_manifest(manifest_path: Path) -> list[dict]:
    entries = []
    for line in manifest_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        obj = json.loads(line)
        if obj.get("done"):
            continue
        entries.append(obj)
    return entries


def check_manifest_run_identity(entries: list[dict], label: str) -> tuple[str | None, str | None]:
    """R-1116: `tour.ps1`/`TourRunner` (R-1083) stamp a single `runId` and `apkHash` onto every
    entry one invocation writes. A manifest whose own entries disagree - mixed together from more
    than one run, the exact "run-A manifest, run-B directory" class of defect this register is
    named for - is not evidence of anything, however its pixels compare. Raises `RunIdentityError`
    naming the field and the conflicting values; returns the agreed `(runId, apkHash)` (either may
    be `None` if the manifest has no entries at all, or "unknown" if it predates R-1083's stamping
    and every entry agrees on that absence, which is self-consistent, just uninformative)."""
    agreed: dict[str, str] = {}
    for field in ("runId", "apkHash"):
        distinct = {entry.get(field, "unknown") for entry in entries}
        if len(distinct) > 1:
            raise RunIdentityError(
                f"{label}: entries disagree on '{field}' - found {sorted(distinct)}. This manifest "
                "carries more than one tour run's worth of entries and cannot be used as evidence "
                "for a single run (R-1116)."
            )
        agreed[field] = next(iter(distinct)) if distinct else None
    return agreed["runId"], agreed["apkHash"]


def load_before_bytes(before: str, rel_path: Path, after_dir: Path, is_git_ref: bool) -> bytes | None:
    if is_git_ref:
        # The path git tracks is `<after>/<rel_path>` relative to the repository root - computed
        # from `after_dir` itself so this works regardless of where the caller pointed `--after`.
        repo_relative = (after_dir / rel_path).as_posix()
        result = subprocess.run(
            ["git", "show", f"{before}:{repo_relative}"],
            capture_output=True,
            check=False,
        )
        if result.returncode != 0:
            return None
        return result.stdout
    before_file = Path(before) / rel_path
    if not before_file.exists():
        return None
    return before_file.read_bytes()


def looks_like_git_ref(before: str) -> bool:
    path = Path(before)
    return not path.exists() or not path.is_dir()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--before", required=True, help="a directory of a previous tour run, or a git ref")
    parser.add_argument("--after", required=True, help="the current tour run's directory (tour.ps1's -Out)")
    parser.add_argument("--manifest", required=True, help="the current run's tour-manifest.json")
    parser.add_argument(
        "--threshold",
        type=float,
        default=0.05,
        help=(
            "R-1116: percentage of pixels (0-100), outside the status bar, that must differ by "
            "more than --pixel-diff-threshold before two screenshots are called 'changed'. NOTE: "
            "this replaced a 0-255 mean-signature-diff unit of the same name - an old invocation's "
            "--threshold value means something different now (see this module's docstring)."
        ),
    )
    parser.add_argument(
        "--pixel-diff-threshold",
        type=int,
        default=24,
        help=(
            "R-1116: a pixel counts as differing when |dR|+|dG|+|dB| (0-765) exceeds this. Measured "
            "insensitive in the 16-80 range on real captures (see this module's docstring); 24 is "
            "the unremarkable middle of that range, not a value tuned to one case."
        ),
    )
    parser.add_argument("--status-bar-fraction", type=float, default=0.04, help="top fraction of each image to ignore")
    args = parser.parse_args()

    after_dir = Path(args.after)
    manifest_path = Path(args.manifest)
    if not manifest_path.exists():
        print(f"manifest not found: {manifest_path}", file=sys.stderr)
        return 2

    entries = read_manifest(manifest_path)
    is_git_ref = looks_like_git_ref(args.before)

    # R-1116: refuse before comparing a single pixel if either manifest is not one coherent run.
    try:
        after_run_id, after_apk_hash = check_manifest_run_identity(entries, f"--manifest {manifest_path}")
    except RunIdentityError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 3
    print(f"after run: runId={after_run_id} apkHash={after_apk_hash}")

    before_manifest_path: Path | None = None
    if not is_git_ref:
        candidate = Path(args.before) / "tour-manifest.json"
        if candidate.exists():
            before_manifest_path = candidate
            try:
                before_entries = read_manifest(candidate)
                before_run_id, before_apk_hash = check_manifest_run_identity(
                    before_entries, f"--before's own manifest {candidate}"
                )
            except RunIdentityError as exc:
                print(f"error: {exc}", file=sys.stderr)
                return 3
            print(f"before run: runId={before_run_id} apkHash={before_apk_hash}")

    new_list: list[str] = []
    missing_list: list[str] = []
    changed_list: list[str] = []
    unchanged_list: list[str] = []

    for entry in entries:
        step_id = entry["id"]
        rel_path = Path(f"{step_id}.png")
        after_file = after_dir / rel_path
        before_bytes = load_before_bytes(args.before, rel_path, after_dir, is_git_ref)

        if not entry.get("ok") or not after_file.exists():
            if before_bytes is not None:
                missing_list.append(step_id)
            continue
        if before_bytes is None:
            new_list.append(step_id)
            continue

        try:
            before_image = decode_png(before_bytes)
            after_image = decode_png(after_file.read_bytes())
        except ValueError as exc:
            print(f"warning: could not decode '{step_id}': {exc}", file=sys.stderr)
            continue

        try:
            frac = pixel_diff_fraction(
                before_image, after_image, args.status_bar_fraction, args.pixel_diff_threshold
            )
        except DimensionMismatch as exc:
            # Not even the same shape is never "unchanged", whatever the pixels would have said.
            print(f"note: '{step_id}' changed size ({exc}) - counted as changed", file=sys.stderr)
            changed_list.append(step_id)
            continue

        (changed_list if (frac * 100.0) >= args.threshold else unchanged_list).append(step_id)

    # A step whose --before exists but that this manifest never mentions at all (dropped from a
    # `tour.ps1 -Only` run) is also `missing`, not silently absent from every list.
    known_ids = {e["id"] for e in entries}
    if not is_git_ref:
        before_root = Path(args.before)
        if before_root.is_dir():
            for png in before_root.rglob("*.png"):
                step_id = png.relative_to(before_root).with_suffix("").as_posix()
                if step_id not in known_ids and step_id not in missing_list:
                    missing_list.append(step_id)

    for name, items in (("new", new_list), ("missing", missing_list), ("changed", changed_list), ("unchanged", unchanged_list)):
        print(f"{name} ({len(items)}):")
        for item in sorted(items):
            print(f"  {item}")

    report_lines = [
        "# Tour diff",
        "",
        f"Before: `{args.before}`  After: `{args.after}`  Threshold: {args.threshold}%"
        f"  Pixel-diff-threshold: {args.pixel_diff_threshold}",
        "",
        f"- after run: runId={after_run_id} apkHash={after_apk_hash}",
    ]
    if before_manifest_path is not None:
        report_lines.append(f"- before run ({before_manifest_path}): runId={before_run_id} apkHash={before_apk_hash}")
    report_lines += [
        "",
        f"- new: {len(new_list)}",
        f"- missing: {len(missing_list)}",
        f"- changed: {len(changed_list)}",
        f"- unchanged: {len(unchanged_list)}",
        "",
    ]
    for name, items in (("New", new_list), ("Missing", missing_list), ("Changed", changed_list)):
        report_lines.append(f"## {name}")
        report_lines.append("")
        if items:
            for item in sorted(items):
                report_lines.append(f"- `{item}`")
        else:
            report_lines.append("(none)")
        report_lines.append("")

    (after_dir / "tour-diff.md").write_text("\n".join(report_lines), encoding="utf-8")
    print(f"\nwrote {after_dir / 'tour-diff.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
