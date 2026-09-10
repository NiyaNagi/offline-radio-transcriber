#!/usr/bin/env python3
"""Extracts release notes from RELEASES.md for the release workflow (.github/workflows/release.yml).

RELEASES.md is the human-readable counterpart to CHANGELOG.md: one `## vX.Y.Z — date` section
per released version, newest first, plus a `## Unreleased` section at the top that the rolling
"latest build" prerelease draws from. CHANGELOG.md stays the dense, per-commit engineering log
(scope / requirements / what changed / verified / left open) AGENTS.md requires it to be — this
script never reads it; see RELEASES.md's own header for the split, and RELEASING.md for the
procedure that keeps the two files in sync.

Usage:
    python tools/release_notes.py            # the "## Unreleased" section's body (rolling build)
    python tools/release_notes.py v0.1.1      # that version's section body (a versioned release)

An unknown version prints the list of sections RELEASES.md actually has and exits non-zero, so a
release can never be cut against notes that were never written.
"""
from __future__ import annotations

import pathlib
import re
import sys
from dataclasses import dataclass

HEADING = re.compile(r"(?m)^## (\S+)(?:\s+—\s+.*)?\s*$")
REPO_URL = "https://github.com/NiyaNagi/offline-radio-transcriber"
RELEASES_PATH = pathlib.Path("RELEASES.md")


@dataclass
class Section:
    """One `## ...` heading's own slice of RELEASES.md.

    `token` is the first word of the heading — `Unreleased`, or a bare `vX.Y.Z` — which is
    exactly what a caller passes on the command line to select it.
    """

    token: str
    body_start: int
    body_end: int


class UnknownVersionError(ValueError):
    def __init__(self, requested: str, known: list[str]) -> None:
        self.requested = requested
        self.known = known
        known_text = ", ".join(known) if known else "(none found)"
        super().__init__(f"no '## {requested}' section in RELEASES.md — known sections: {known_text}")


def parse_sections(text: str) -> list[Section]:
    """One Section per top-level heading, in document order (RELEASES.md is newest first)."""
    headings = list(HEADING.finditer(text))
    sections = []
    for i, m in enumerate(headings):
        body_end = headings[i + 1].start() if i + 1 < len(headings) else len(text)
        sections.append(Section(token=m.group(1), body_start=m.end(), body_end=body_end))
    return sections


def commit_range(sections: list[Section], index: int) -> str:
    """The range this section's own notes cover, as `<older>..<newer>` tag/ref notation.

    RELEASES.md orders sections newest first, so the section right after this one in the list is
    the one that was current immediately before this one was cut (or, for `Unreleased`, the most
    recently cut version).
    """
    token = sections[index].token
    newer_ref = "HEAD" if token == "Unreleased" else token
    if index + 1 < len(sections):
        return f"{sections[index + 1].token}..{newer_ref}"
    return f"up to {newer_ref}"


def notes_for(text: str, requested: str = "Unreleased") -> str:
    """The rendered notes body for one section of RELEASES.md, footer included.

    Raises UnknownVersionError if `requested` names no heading in `text` — including when there
    are no headings at all.
    """
    sections = parse_sections(text)
    index = next((i for i, s in enumerate(sections) if s.token == requested), None)
    if index is None:
        raise UnknownVersionError(requested, [s.token for s in sections])

    body = text[sections[index].body_start : sections[index].body_end].strip()
    if not body:
        body = "(no notes recorded for this version yet — see RELEASES.md)"

    footer = (
        f"\n\n---\nEngineering detail: [CHANGELOG.md]({REPO_URL}/blob/main/CHANGELOG.md) · "
        f"Commit range: {commit_range(sections, index)}"
    )
    return body + footer


def main(argv: list[str]) -> int:
    if not RELEASES_PATH.exists():
        print(f"error: {RELEASES_PATH} not found", file=sys.stderr)
        return 1

    text = RELEASES_PATH.read_text(encoding="utf-8")
    requested = argv[1] if len(argv) > 1 else "Unreleased"
    try:
        print(notes_for(text, requested))
    except UnknownVersionError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
