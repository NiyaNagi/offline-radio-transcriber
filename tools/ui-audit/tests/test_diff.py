"""Tests for tools/ui-audit/diff.py (R-1116, register - constitution VIII: the tour diff is the
tool that makes a screenshot comparison count as evidence, and it used to be unable to fail).

Follows the same shape as tools/analytics/tests/test_cli.py and tools/tests/test_release_notes.py:
import the script directly by inserting its directory on sys.path, build tiny synthetic PNGs with
a stdlib-only encoder (no Pillow dependency for the test suite itself - diff.py's own stdlib
fallback decoder is what reads them back, so these tests exercise the same path every CI runner
without Pillow installed actually takes), and exercise `main()` end to end through its CLI
surface, the same way `tour.ps1` invokes it.

Each test is named for what it establishes, per AGENTS.md's convention (`R_1116_...`). Per
constitution II, each of these was watched to fail for the right reason against the pre-fix
`diff.py` before the fix landed - see CHANGELOG.md's R-1116 entry for the discrimination
transcript (revert, red; restore, green) for each one.
"""
from __future__ import annotations

import json
import struct
import sys
import zlib
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import diff  # noqa: E402


def _chunk(tag: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)


def make_png(width: int, height: int, pixel_fn) -> bytes:
    """A minimal stdlib-only 8-bit RGB PNG encoder (filter type 0 on every scanline) - the mirror
    image of diff.py's own `_decode_png_stdlib`, so tests never need Pillow to build fixtures."""
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter type: none
        for x in range(width):
            r, g, b = pixel_fn(x, y)
            raw += bytes((r, g, b))
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)  # color type 2 = RGB
    idat = zlib.compress(bytes(raw), 9)
    return b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", ihdr) + _chunk(b"IDAT", idat) + _chunk(b"IEND", b"")


def solid(width: int, height: int, rgb: tuple[int, int, int]) -> bytes:
    return make_png(width, height, lambda x, y: rgb)


def solid_with_band(width: int, height: int, base: tuple[int, int, int], band_y: int, band_h: int, band_rgb: tuple[int, int, int]) -> bytes:
    """`base` everywhere except one horizontal band [band_y, band_y+band_h) - a stand-in for one
    changed line of text in an otherwise identical screenshot."""

    def pix(x: int, y: int) -> tuple[int, int, int]:
        if band_y <= y < band_y + band_h:
            return band_rgb
        return base

    return make_png(width, height, pix)


def write_manifest(path: Path, entries: list[dict]) -> None:
    lines = [json.dumps(e) for e in entries]
    lines.append(json.dumps({"done": True}))
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def run_diff(monkeypatch, args: list[str]) -> int:
    monkeypatch.setattr(sys, "argv", ["diff.py"] + args)
    return diff.main()


# ---------------------------------------------------------------------------
# R-1116: the manifest's own runId/apkHash are read and must agree with themselves.
# ---------------------------------------------------------------------------


def test_R_1116_manifest_with_two_run_ids_exits_nonzero(tmp_path, monkeypatch):
    """The literal 'run-A manifest compared against a run-B directory' case: a manifest whose
    entries were assembled from two different tour invocations (mixed runId), pointed at one
    --after directory. Before this fix, diff.py never read `runId` at all and would happily
    compare pixels and exit 0."""
    after = tmp_path / "after"
    after.mkdir()
    png_bytes = solid(20, 20, (10, 10, 10))
    (after / "N01-now.png").write_bytes(png_bytes)
    manifest = after / "tour-manifest.json"
    write_manifest(
        manifest,
        [
            {"id": "N01-now", "ok": True, "apkHash": "hashA", "runId": "run-A"},
            {"id": "N02-log", "ok": True, "apkHash": "hashA", "runId": "run-B"},
        ],
    )
    before = tmp_path / "before-empty"
    before.mkdir()

    rc = run_diff(monkeypatch, ["--before", str(before), "--after", str(after), "--manifest", str(manifest)])

    assert rc != 0


def test_R_1116_manifest_with_two_apk_hashes_exits_nonzero(tmp_path, monkeypatch):
    """Same defect class, the other stamped field: two entries claim the same runId but different
    apkHash - the manifest still cannot be one coherent build's capture."""
    after = tmp_path / "after"
    after.mkdir()
    (after / "N01-now.png").write_bytes(solid(20, 20, (10, 10, 10)))
    manifest = after / "tour-manifest.json"
    write_manifest(
        manifest,
        [
            {"id": "N01-now", "ok": True, "apkHash": "hash-old", "runId": "run-X"},
            {"id": "N02-log", "ok": True, "apkHash": "hash-new", "runId": "run-X"},
        ],
    )
    before = tmp_path / "before-empty"
    before.mkdir()

    rc = run_diff(monkeypatch, ["--before", str(before), "--after", str(after), "--manifest", str(manifest)])

    assert rc != 0


def test_R_1116_before_directorys_own_mixed_manifest_exits_nonzero(tmp_path, monkeypatch):
    """--before, when it is a directory, carries its own tour-manifest.json (tour.ps1 always
    writes one) - if THAT one disagrees with itself, the before side is not trustworthy evidence
    either, even though the --after manifest passed via --manifest is perfectly self-consistent."""
    after = tmp_path / "after"
    after.mkdir()
    (after / "N01-now.png").write_bytes(solid(20, 20, (10, 10, 10)))
    manifest = after / "tour-manifest.json"
    write_manifest(manifest, [{"id": "N01-now", "ok": True, "apkHash": "hashA", "runId": "run-A"}])

    before = tmp_path / "before"
    before.mkdir()
    (before / "N01-now.png").write_bytes(solid(20, 20, (10, 10, 10)))
    write_manifest(
        before / "tour-manifest.json",
        [
            {"id": "N01-now", "ok": True, "apkHash": "hash-old", "runId": "run-old"},
            {"id": "N02-log", "ok": True, "apkHash": "hash-old", "runId": "run-STALE"},
        ],
    )

    rc = run_diff(monkeypatch, ["--before", str(before), "--after", str(after), "--manifest", str(manifest)])

    assert rc != 0


def test_R_1116_self_consistent_manifest_with_unknown_run_fields_is_accepted(tmp_path, monkeypatch):
    """Backward compatibility: a manifest that predates R-1083's stamping has no `runId`/`apkHash`
    keys at all on any entry (`.optString(field, "unknown")` on the Kotlin side). Every entry
    agreeing on the same absence is self-consistent, just uninformative, and must not be rejected."""
    after = tmp_path / "after"
    after.mkdir()
    (after / "N01-now.png").write_bytes(solid(20, 20, (10, 10, 10)))
    manifest = after / "tour-manifest.json"
    write_manifest(manifest, [{"id": "N01-now", "ok": True}])
    before = tmp_path / "before-empty"
    before.mkdir()

    rc = run_diff(monkeypatch, ["--before", str(before), "--after", str(after), "--manifest", str(manifest)])

    assert rc == 0


# ---------------------------------------------------------------------------
# R-1116: the comparison itself must be able to see a small, real, localized change.
# ---------------------------------------------------------------------------


def test_R_1116_one_line_text_change_is_reported_changed(tmp_path, monkeypatch):
    """The defect's headline claim: a one-line text difference between two otherwise identical
    screenshots must land in `changed`, not be diluted away. Simulated as a solid background with
    one horizontal band recolored (the pixel-level stand-in for a caption's text pixels changing) -
    the old 4x4 mean-RGB signature spread that band's contribution across a 16-cell average and
    landed under its own default threshold; see this test's discrimination note in CHANGELOG.md."""
    width, height = 200, 400
    before_png = solid(width, height, (10, 10, 10))
    # One ~2px "text line" changed out of 400 rows (0.04% of pixels before status-bar exclusion,
    # a hair under 0.5% of the post-exclusion image - well above the measured re-encoding noise
    # floor of 0.0% and the chosen --threshold default of 0.05%).
    after_png = solid_with_band(width, height, (10, 10, 10), band_y=200, band_h=2, band_rgb=(230, 230, 230))

    after = tmp_path / "after"
    after.mkdir()
    (after / "S01-screen.png").write_bytes(after_png)
    manifest = after / "tour-manifest.json"
    write_manifest(manifest, [{"id": "S01-screen", "ok": True, "apkHash": "h", "runId": "r"}])

    before = tmp_path / "before"
    before.mkdir()
    (before / "S01-screen.png").write_bytes(before_png)

    rc = run_diff(monkeypatch, ["--before", str(before), "--after", str(after), "--manifest", str(manifest)])

    assert rc == 0
    report = (after / "tour-diff.md").read_text(encoding="utf-8")
    assert "`S01-screen`" in report.split("## Changed", 1)[1].split("## ", 1)[0]


def test_R_1116_identical_screenshots_are_reported_unchanged(tmp_path, monkeypatch):
    """Sanity/regression companion to the test above: two byte-identical captures must not be
    flagged, so the new, more sensitive comparison has not simply become trigger-happy."""
    width, height = 200, 400
    png_bytes = solid(width, height, (10, 10, 10))

    after = tmp_path / "after"
    after.mkdir()
    (after / "S01-screen.png").write_bytes(png_bytes)
    manifest = after / "tour-manifest.json"
    write_manifest(manifest, [{"id": "S01-screen", "ok": True, "apkHash": "h", "runId": "r"}])

    before = tmp_path / "before"
    before.mkdir()
    (before / "S01-screen.png").write_bytes(png_bytes)

    rc = run_diff(monkeypatch, ["--before", str(before), "--after", str(after), "--manifest", str(manifest)])

    assert rc == 0
    report = (after / "tour-diff.md").read_text(encoding="utf-8")
    assert "- unchanged: 1" in report


def test_R_1116_dimension_mismatch_is_reported_changed_never_unchanged(tmp_path, monkeypatch):
    """A screenshot that changed size is never 'unchanged', whatever a resampled pixel comparison
    might have said - the old 4x4 signature approach silently normalized away a size difference by
    downsampling both images to the same grid regardless of their actual dimensions."""
    after = tmp_path / "after"
    after.mkdir()
    (after / "S01-screen.png").write_bytes(solid(200, 400, (10, 10, 10)))
    manifest = after / "tour-manifest.json"
    write_manifest(manifest, [{"id": "S01-screen", "ok": True, "apkHash": "h", "runId": "r"}])

    before = tmp_path / "before"
    before.mkdir()
    (before / "S01-screen.png").write_bytes(solid(200, 380, (10, 10, 10)))

    rc = run_diff(monkeypatch, ["--before", str(before), "--after", str(after), "--manifest", str(manifest)])

    assert rc == 0
    report = (after / "tour-diff.md").read_text(encoding="utf-8")
    assert "- changed: 1" in report
    assert "- unchanged: 0" in report


# ---------------------------------------------------------------------------
# R-1149: two runs captured at different `wm size` geometry are not comparable, even when their
# screenshots happen to be the same pixel dimensions (a wm size override shifts content within a
# same-sized canvas rather than resizing it - see diff.py's own R-1149 docstring section). The
# manifest's own `width`/`height` fields (the captured bitmap's real pixel dimensions,
# `TourManifestEntry.width`/`.height`) are compared the same way runId/apkHash already are:
# self-consistency within one manifest, and - new for this row - agreement across the two runs,
# because unlike runId/apkHash, two runs are never allowed to disagree on capture geometry.
# ---------------------------------------------------------------------------


def test_R_1149_after_manifest_geometry_self_inconsistent_exits_nonzero(tmp_path, monkeypatch):
    """Two `ok` entries in the SAME manifest disagree on width - not one coherent capture
    geometry, the same class of problem `check_manifest_run_identity` catches for
    `runId`/`apkHash`."""
    after = tmp_path / "after"
    after.mkdir()
    (after / "N01-now.png").write_bytes(solid(20, 20, (10, 10, 10)))
    (after / "N02-log.png").write_bytes(solid(20, 20, (10, 10, 10)))
    manifest = after / "tour-manifest.json"
    write_manifest(
        manifest,
        [
            {"id": "N01-now", "ok": True, "apkHash": "h", "runId": "r", "width": 1080, "height": 2400},
            {"id": "N02-log", "ok": True, "apkHash": "h", "runId": "r", "width": 1260, "height": 2772},
        ],
    )
    before = tmp_path / "before-empty"
    before.mkdir()

    rc = run_diff(monkeypatch, ["--before", str(before), "--after", str(after), "--manifest", str(manifest)])

    assert rc != 0


def test_R_1149_before_after_geometry_mismatch_exits_nonzero_before_comparing_pixels(tmp_path, monkeypatch):
    """The discriminating case this row exists for: a pair of runs whose entries agree on
    `runId` and `apkHash` (so the pre-existing R-1116 guard has nothing to say) but disagree on
    `width`/`height` - the exact 5554-override-vs-5560-physical shape of the finding. The two
    screenshots are deliberately given identical PIXEL dimensions (1080x2400 both), reproducing
    the reported symptom that `DimensionMismatch` cannot see this on its own: a wm size override
    shifts content inside a same-sized canvas, it does not resize the canvas. Must refuse before
    comparing a single pixel - no report is written."""
    after = tmp_path / "after"
    after.mkdir()
    (after / "N01-now.png").write_bytes(solid(1080, 2400, (10, 10, 10)))
    manifest = after / "tour-manifest.json"
    write_manifest(
        manifest,
        [{"id": "N01-now", "ok": True, "apkHash": "h", "runId": "r", "width": 1080, "height": 2400}],
    )

    before = tmp_path / "before"
    before.mkdir()
    (before / "N01-now.png").write_bytes(solid(1080, 2400, (10, 10, 10)))
    write_manifest(
        before / "tour-manifest.json",
        [{"id": "N01-now", "ok": True, "apkHash": "h", "runId": "r", "width": 1260, "height": 2772}],
    )

    rc = run_diff(monkeypatch, ["--before", str(before), "--after", str(after), "--manifest", str(manifest)])

    assert rc != 0
    assert not (after / "tour-diff.md").exists()


def test_R_1149_matching_geometry_across_runs_is_accepted(tmp_path, monkeypatch):
    """Sanity/regression companion: two runs that genuinely agree on capture geometry must not be
    refused, so the new guard has not simply become trigger-happy."""
    after = tmp_path / "after"
    after.mkdir()
    (after / "N01-now.png").write_bytes(solid(1080, 2400, (10, 10, 10)))
    manifest = after / "tour-manifest.json"
    write_manifest(
        manifest,
        [{"id": "N01-now", "ok": True, "apkHash": "h", "runId": "r", "width": 1080, "height": 2400}],
    )

    before = tmp_path / "before"
    before.mkdir()
    (before / "N01-now.png").write_bytes(solid(1080, 2400, (10, 10, 10)))
    write_manifest(
        before / "tour-manifest.json",
        [{"id": "N01-now", "ok": True, "apkHash": "h", "runId": "r", "width": 1080, "height": 2400}],
    )

    rc = run_diff(monkeypatch, ["--before", str(before), "--after", str(after), "--manifest", str(manifest)])

    assert rc == 0
    assert (after / "tour-diff.md").exists()


def test_R_1149_failed_entries_zero_geometry_does_not_trip_self_consistency(tmp_path, monkeypatch):
    """A failed step (`ok: false`) always carries `width: 0, height: 0`
    (`TourManifestEntry.failure`) - that must not be compared against a real capture's own
    width/height, or every manifest with even one failed step would falsely refuse."""
    after = tmp_path / "after"
    after.mkdir()
    (after / "N01-now.png").write_bytes(solid(1080, 2400, (10, 10, 10)))
    manifest = after / "tour-manifest.json"
    write_manifest(
        manifest,
        [
            {"id": "N01-now", "ok": True, "apkHash": "h", "runId": "r", "width": 1080, "height": 2400},
            {
                "id": "N02-broken",
                "ok": False,
                "apkHash": "h",
                "runId": "r",
                "width": 0,
                "height": 0,
                "errorMessage": "boom",
            },
        ],
    )
    before = tmp_path / "before-empty"
    before.mkdir()

    rc = run_diff(monkeypatch, ["--before", str(before), "--after", str(after), "--manifest", str(manifest)])

    assert rc == 0
