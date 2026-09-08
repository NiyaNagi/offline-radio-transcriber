#!/usr/bin/env python3
"""Extracts release notes from CHANGELOG.md for the release workflow (.github/workflows/release.yml).

CHANGELOG.md is grouped under "## YYYY-MM-DD (...)" headings, newest first (its own documented
format — see the file's own header). A release published on a given day should show everything
written under that day so far, not just the single most-recent heading — this project's own
convention is many small dated sub-sections per day (morning/afternoon/evening), and a rolling
"latest build" release is republished many times a day, so grouping by calendar date gives a
complete, stable "what's new since this day started" note rather than one arbitrarily thin slice.
"""
from __future__ import annotations

import pathlib
import re
import sys

HEADING = re.compile(r"(?m)^## (\d{4}-\d{2}-\d{2})")
REPO_URL = "https://github.com/NiyaNagi/offline-radio-transcriber"


def extract(text: str) -> str:
    headings = list(HEADING.finditer(text))
    if not headings:
        return "(no dated changelog entries yet — see CHANGELOG.md)"

    newest_date = headings[0].group(1)
    end = len(text)
    for m in headings[1:]:
        if m.group(1) != newest_date:
            end = m.start()
            break
    return text[headings[0].start():end].rstrip()


def main() -> int:
    path = pathlib.Path("CHANGELOG.md")
    if not path.exists():
        print("(CHANGELOG.md not found)")
        return 0
    section = extract(path.read_text(encoding="utf-8"))
    print(section)
    print()
    print(f"Full history: [CHANGELOG.md]({REPO_URL}/blob/main/CHANGELOG.md)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
