"""Named, reproducible queries over the `events` view `loader.load_events` registers (D48).

Every function here takes a live `duckdb.DuckDBPyConnection` with `events` already loaded — never
a fold, never a raw directory — so calling one of these *is* stating "against whatever fold is
currently loaded," and `loader.load_events`'s own refusal is what already guaranteed that fold is
`field` (constitution VI: "no number without its provenance" — the fold is provenance, and it is
fixed before any of these run, not decided per-query).

Every tier/event-type-specific field is read through `json_extract_string(to_json(payload), ...)`
rather than a direct `payload.someField` struct access. DuckDB's `read_ndjson_auto` infers the
`payload` STRUCT's fields from whatever event *shapes actually appear in the loaded data* — a
directory holding only tier 1 usage events has no `asrHypothesis` field in its inferred struct at
all, and a direct `payload.asrHypothesis` reference fails to bind in that case (found by this
module's own `test_field_wer_with_no_tier2_data_is_honestly_empty`, which is exactly the case a
production destination with tier 2 universally disabled would hit). A JSON-path extraction never
fails to bind; a genuinely absent field just reads back `NULL`, the same "not present, not an
error" contract `payload.payloadType` — a field every event shape does carry — gets for free
through ordinary struct access.
"""
from __future__ import annotations

import json

import duckdb


def crash_free_sessions(con: duckdb.DuckDBPyConnection) -> dict:
    """The fraction of sessions (identified by provenance.sessionId) that produced zero
    tier1_crash events. `None` when there are no sessions to divide by."""
    total, crashed = con.execute(
        """
        WITH sessions AS (
            SELECT DISTINCT provenance.sessionId AS session_id
            FROM events
            WHERE provenance.sessionId IS NOT NULL
        ),
        crashed AS (
            SELECT DISTINCT provenance.sessionId AS session_id
            FROM events
            WHERE payload.payloadType = 'tier1_crash' AND provenance.sessionId IS NOT NULL
        )
        SELECT (SELECT COUNT(*) FROM sessions), (SELECT COUNT(*) FROM crashed)
        """,
    ).fetchone()
    rate = None if total == 0 else (total - crashed) / total
    return {"total_sessions": total, "crashed_sessions": crashed, "crash_free_rate": rate}


def crash_anr_breakdown(con: duckdb.DuckDBPyConnection) -> dict:
    """FR-ANL-2, constitution I: `tier1_crash`'s `isAnr` is a genuine tri-state on the wire --
    `true` (a measured ANR), `false` (a measured non-ANR crash), or `null` ("no ANR-detection
    mechanism ran" -- `:app`'s `CrashPayloads.fromUncaughtException` writes `null` for every event
    this build has ever produced, since none exists yet). This reads that state back with the
    identical three-way split rather than folding `null` into `false` -- doing so would report
    every one of today's crashes as a measured "not an ANR," which is not what happened.

    Uses ``json_extract`` (this module's own top doc comment explains why) so a directory with no
    ``tier1_crash`` events at all -- or one where every crash row happens to omit ``isAnr``
    entirely -- reads back as an honest zero in every bucket rather than failing to bind.
    """
    rows = con.execute(
        "SELECT json_extract(to_json(payload), '$.isAnr') FROM events "
        "WHERE payload.payloadType = 'tier1_crash'",
    ).fetchall()
    anr = not_anr = unmeasured = 0
    for (raw,) in rows:
        if raw is None or raw == "null":
            unmeasured += 1
        elif raw == "true":
            anr += 1
        elif raw == "false":
            not_anr += 1
        else:
            # A malformed or unrecognised value is not a confirmed measurement either -- counted
            # as unmeasured rather than silently treated as `false` (constitution I).
            unmeasured += 1
    return {"anr": anr, "not_anr": not_anr, "unmeasured": unmeasured, "total": len(rows)}


def setup_funnel(con: duckdb.DuckDBPyConnection) -> list[tuple[str, str, int]]:
    """FR-AST-11: setup-funnel outcomes by step, most granular first — the report generator rolls
    this up further; this query stays a plain count so nothing about how "success" is defined is
    baked into the SQL itself."""
    return con.execute(
        """
        SELECT
            json_extract_string(to_json(payload), '$.step') AS step,
            json_extract_string(to_json(payload), '$.outcome') AS outcome,
            COUNT(*) AS n
        FROM events
        WHERE payload.payloadType = 'tier1_setup_funnel'
        GROUP BY 1, 2
        ORDER BY 1, 2
        """,
    ).fetchall()


def real_time_factor(con: duckdb.DuckDBPyConnection) -> list[tuple[str, float, float, float, int]]:
    """Per-pass real-time factor: (pass_id, avg, min, max, n)."""
    return con.execute(
        """
        SELECT
            json_extract_string(to_json(payload), '$.passId') AS pass_id,
            AVG(json_extract(to_json(payload), '$.realTimeFactor')::DOUBLE) AS avg_rtf,
            MIN(json_extract(to_json(payload), '$.realTimeFactor')::DOUBLE) AS min_rtf,
            MAX(json_extract(to_json(payload), '$.realTimeFactor')::DOUBLE) AS max_rtf,
            COUNT(*) AS n
        FROM events
        WHERE payload.payloadType = 'tier1_performance'
        GROUP BY 1
        ORDER BY 1
        """,
    ).fetchall()


def correction_rate(con: duckdb.DuckDBPyConnection) -> dict[str, float]:
    """FR-ANL-2's aggregate correction-rate-by-field, averaged across every tier1_quality_stats
    event. `correctionRateByField` is a per-event map of field name -> rate — read back as JSON
    and aggregated in Python, since its keys (which transcript field was corrected) vary with the
    data and are not a fixed SQL column set."""
    rows = con.execute(
        "SELECT json_extract(to_json(payload), '$.correctionRateByField') FROM events "
        "WHERE payload.payloadType = 'tier1_quality_stats'",
    ).fetchall()
    totals: dict[str, list[float]] = {}
    for (blob,) in rows:
        if blob is None:
            continue
        for field, rate in json.loads(blob).items():
            totals.setdefault(field, []).append(rate)
    return {field: sum(values) / len(values) for field, values in totals.items()}


def _word_edit_distance(hypothesis_words: list[str], reference_words: list[str]) -> int:
    """Plain word-level Levenshtein distance — no external dependency for one small DP table."""
    n, m = len(hypothesis_words), len(reference_words)
    previous = list(range(m + 1))
    for i in range(1, n + 1):
        current = [i] + [0] * m
        for j in range(1, m + 1):
            cost = 0 if hypothesis_words[i - 1] == reference_words[j - 1] else 1
            current[j] = min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
        previous = current
    return previous[m]


def field_wer(con: duckdb.DuckDBPyConnection) -> dict:
    """Word error rate over tier 2's `(ASR hypothesis, user correction)` pairs — the user's
    correction is the reference. Only ever non-empty when tier 2 is opted in (FR-ANL-3); tier 1
    alone has no transcript text at all to compute this from, by construction — including the
    honest empty case where the loaded data has no tier2_correction event at all, which a direct
    struct-field query would fail to even bind against (see this module's own top doc comment)."""
    rows = con.execute(
        """
        SELECT
            json_extract_string(to_json(payload), '$.asrHypothesis') AS hypothesis,
            json_extract_string(to_json(payload), '$.userCorrection') AS correction
        FROM events
        WHERE payload.payloadType = 'tier2_correction'
        """,
    ).fetchall()
    rows = [(h, c) for h, c in rows if h is not None and c is not None]
    if not rows:
        return {"pairs": 0, "wer": None}
    total_edits = 0
    total_reference_words = 0
    for hypothesis, correction in rows:
        reference_words = correction.split()
        total_edits += _word_edit_distance(hypothesis.split(), reference_words)
        total_reference_words += len(reference_words)
    wer = None if total_reference_words == 0 else total_edits / total_reference_words
    return {"pairs": len(rows), "wer": wer}
