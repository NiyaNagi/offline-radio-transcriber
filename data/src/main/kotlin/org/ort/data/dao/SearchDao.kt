package org.ort.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.SkipQueryVerification
import org.ort.data.entity.TransmissionEntity

/**
 * FR-UI-3 — full-text search over `transcript_fts` (the external-content index build-plan P5
 * built and nothing queried until now) with filters for callsign, frequency and date, plus a
 * filter-only browse path that never touches the index at all.
 *
 * Kept as its own DAO file, per build-plan P15's own instruction, rather than added to
 * [TransmissionDao]/[TranscriptDao] — a concurrent session (P17) is adding aggregate queries to
 * this module at the same time, and a shared file would conflict.
 *
 * [searchText] needs [SkipQueryVerification] for the same reason [TranscriptDao]'s own FTS
 * queries do: Room's compile-time query verifier cannot see a table it did not generate.
 */
@Dao
public interface SearchDao {

    /**
     * Filters only — no full-text term, so this never touches `transcript_fts` and works
     * identically whether or not the fts5 module is present (relevant only to test hosts; a real
     * minSdk-26 device always has it — see [org.ort.data.OrtDatabase]'s own comment). A null
     * filter matches everything; [callsign] compares case-insensitively against the joined
     * station's callsign.
     */
    @Query(
        "SELECT transmission.* FROM transmission " +
            "LEFT JOIN station ON station.id = transmission.stationId " +
            "WHERE (:callsign IS NULL OR UPPER(station.callsign) = UPPER(:callsign)) " +
            "AND (:frequencyHz IS NULL OR transmission.frequencyHz = :frequencyHz) " +
            "AND (:fromUtc IS NULL OR transmission.startedAtUtc >= :fromUtc) " +
            "AND (:toUtc IS NULL OR transmission.startedAtUtc <= :toUtc) " +
            "ORDER BY transmission.startedAtUtc DESC",
    )
    public suspend fun filterOnly(
        callsign: String?,
        frequencyHz: Long?,
        fromUtc: Long?,
        toUtc: Long?,
    ): List<TransmissionEntity>

    /**
     * Full-text search, restricted to each transmission's *current* transcript (a superseded
     * version is reachable from the detail screen, not surfaced twice here), plus the same
     * callsign/frequency/date filters as [filterOnly]. [matchQuery] must already be a valid FTS5
     * MATCH expression — see [FtsMatchQuery.build].
     */
    @SkipQueryVerification
    @Query(
        "SELECT transmission.* FROM transmission " +
            "JOIN transcript ON transcript.transmissionId = transmission.id AND transcript.isCurrent = 1 " +
            "JOIN transcript_fts ON transcript_fts.rowid = transcript.rowid " +
            "LEFT JOIN station ON station.id = transmission.stationId " +
            "WHERE transcript_fts MATCH :matchQuery " +
            "AND (:callsign IS NULL OR UPPER(station.callsign) = UPPER(:callsign)) " +
            "AND (:frequencyHz IS NULL OR transmission.frequencyHz = :frequencyHz) " +
            "AND (:fromUtc IS NULL OR transmission.startedAtUtc >= :fromUtc) " +
            "AND (:toUtc IS NULL OR transmission.startedAtUtc <= :toUtc) " +
            "ORDER BY transmission.startedAtUtc DESC",
    )
    public suspend fun searchText(
        matchQuery: String,
        callsign: String?,
        frequencyHz: Long?,
        fromUtc: Long?,
        toUtc: Long?,
    ): List<TransmissionEntity>

    /**
     * The one entry point callers should use: blank/null [text] degrades to [filterOnly] (so a
     * filters-only browse never has to fabricate a match-everything FTS query, which MATCH has no
     * clean syntax for anyway); non-blank text is sanitised by [FtsMatchQuery] and routed to
     * [searchText]. A caller on a host where fts5 is genuinely unavailable (this project's own
     * Robolectric tests — see [org.ort.data.SearchDaoFullTextTest]) gets whatever
     * [android.database.sqlite.SQLiteException] SQLite raises; it is not caught here; degrading
     * gracefully when a text query cannot run is a `:app`-level UI decision, not a data-layer one.
     */
    public suspend fun search(
        text: String?,
        callsign: String? = null,
        frequencyHz: Long? = null,
        fromUtc: Long? = null,
        toUtc: Long? = null,
    ): List<TransmissionEntity> {
        val trimmed = text?.trim()
        return if (trimmed.isNullOrEmpty()) {
            filterOnly(callsign, frequencyHz, fromUtc, toUtc)
        } else {
            searchText(FtsMatchQuery.build(trimmed), callsign, frequencyHz, fromUtc, toUtc)
        }
    }
}

/**
 * Turns free-form user search text into a safe FTS5 MATCH expression (FR-UI-3). Each
 * whitespace-separated token is individually double-quoted — embedded quotes doubled, per
 * SQLite's string-literal escaping rule — and tokens are ANDed together, so a token containing an
 * FTS5 operator character (`-`, `*`, `:`, …) is matched literally rather than parsed as query
 * syntax: a user searching for `K7ABC-2` must find that text, not trigger a `NOT` clause.
 */
public object FtsMatchQuery {
    public fun build(rawText: String): String = rawText
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .joinToString(separator = " AND ") { token -> "\"${token.replace("\"", "\"\"")}\"" }
}
