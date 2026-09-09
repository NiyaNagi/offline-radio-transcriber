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

Comparison is a coarse perceptual check, not a pixel-exact diff (screenshots carry a live clock/
elapsed timer in the header on some screens, and PNG re-encoding is not guaranteed byte-identical
run to run): each image is downsampled to a 4x4 grid of per-cell mean RGB, the top
``--status-bar-fraction`` of the image (the OS status bar - clock, battery, never app content) is
excluded from every cell before averaging, and the two grids' mean absolute difference is compared
against ``--threshold``.

Every step in the manifest lands in exactly one of four lists:

- ``new``     - no ``--before`` image exists for this id at all.
- ``missing`` - a ``--before`` image exists but this run's manifest has no ``ok`` entry for it
                (the step errored, or was dropped from a ``tour.ps1 -Only`` run).
- ``changed`` / ``unchanged`` - both images exist; the perceptual check decided.

Writes ``<after>/tour-diff.md`` and also prints the four lists to stdout. Uses Pillow if the
running interpreter has it (more robust PNG decoding - palettes, interlacing, 16-bit); otherwise
falls back to a small stdlib-only decoder (zlib + struct) that covers the common case this tool's
own screenshots are actually encoded as: non-interlaced 8-bit RGB/RGBA, which is what
``android.graphics.Bitmap.compress(PNG, ...)`` produces (confirmed by reading
``TourRunner.kt``'s own `writePng` before writing this) - a PNG outside that case fails loudly with
a message naming Pillow as the fix, never a silent wrong answer.
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


def grid_signature(image: DecodedImage, status_bar_fraction: float, grid: int = 4) -> list[tuple[float, float, float]]:
    """Mean (R, G, B) per cell of a `grid` x `grid` downsample, skipping the top
    `status_bar_fraction` of rows (the OS status bar, never app content)."""
    skip_rows = int(image.height * status_bar_fraction)
    usable_height = max(1, image.height - skip_rows)
    cell_w = max(1, image.width // grid)
    cell_h = max(1, usable_height // grid)
    channels = image.channels
    stride = image.width * channels
    signature: list[tuple[float, float, float]] = []
    for gy in range(grid):
        for gx in range(grid):
            y0 = skip_rows + gy * cell_h
            y1 = image.height if gy == grid - 1 else skip_rows + (gy + 1) * cell_h
            x0 = gx * cell_w
            x1 = image.width if gx == grid - 1 else (gx + 1) * cell_w
            r_sum = g_sum = b_sum = count = 0
            for y in range(y0, min(y1, image.height)):
                row_off = y * stride
                for x in range(x0, min(x1, image.width)):
                    p = row_off + x * channels
                    r_sum += image.pixels[p]
                    g_sum += image.pixels[p + 1]
                    b_sum += image.pixels[p + 2]
                    count += 1
            if count == 0:
                signature.append((0.0, 0.0, 0.0))
            else:
                signature.append((r_sum / count, g_sum / count, b_sum / count))
    return signature


def mean_abs_diff(a: list[tuple[float, float, float]], b: list[tuple[float, float, float]]) -> float:
    total = 0.0
    for (ar, ag, ab), (br, bg, bb) in zip(a, b):
        total += abs(ar - br) + abs(ag - bg) + abs(ab - bb)
    return total / (len(a) * 3)


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
    parser.add_argument("--threshold", type=float, default=6.0, help="mean abs per-channel diff (0-255) to call 'changed'")
    parser.add_argument("--status-bar-fraction", type=float, default=0.04, help="top fraction of each image to ignore")
    args = parser.parse_args()

    after_dir = Path(args.after)
    manifest_path = Path(args.manifest)
    if not manifest_path.exists():
        print(f"manifest not found: {manifest_path}", file=sys.stderr)
        return 2

    entries = read_manifest(manifest_path)
    is_git_ref = looks_like_git_ref(args.before)

    new_list: list[str] = []
    missing_list: list[str] = []
    changed_list: list[str] = []
    unchanged_list: list[str] = []
    errored_ids = {e["id"] for e in entries if not e.get("ok")}

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

        before_sig = grid_signature(before_image, args.status_bar_fraction)
        after_sig = grid_signature(after_image, args.status_bar_fraction)
        diff = mean_abs_diff(before_sig, after_sig)
        (changed_list if diff >= args.threshold else unchanged_list).append(step_id)

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
        f"Before: `{args.before}`  After: `{args.after}`  Threshold: {args.threshold}",
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
