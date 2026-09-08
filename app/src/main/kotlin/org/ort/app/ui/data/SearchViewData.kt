package org.ort.app.ui.data

import android.content.Context
import android.database.sqlite.SQLiteException
import org.ort.data.OrtDatabase
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/** The search screen's raw, unvalidated text fields (FR-UI-3). */
public data class SearchFilterInput(
    val text: String = "",
    val callsign: String = "",
    val frequencyMhz: String = "",
    /** ISO-8601 `yyyy-MM-dd`, interpreted as one UTC day. */
    val dateUtc: String = "",
)

/** Parsed, validated filter values — `null` fields mean "no filter", never a value that matches nothing. */
public data class SearchQueryParams(
    val text: String? = null,
    val callsign: String? = null,
    val frequencyHz: Long? = null,
    val fromUtcMillis: Long? = null,
    val toUtcMillis: Long? = null,
)

/**
 * Turns [SearchFilterInput]'s raw text into [SearchQueryParams] (FR-UI-3). Every field is
 * independently optional and a field that fails to parse is dropped rather than crashing the
 * screen or silently asserting a filter that can never match — the user sees no results for a
 * different, honest reason (their text/callsign filters, if any, still apply) rather than the
 * screen breaking outright.
 */
public object SearchFilterParser {
    public fun parse(input: SearchFilterInput): SearchQueryParams {
        val (from, to) = parseDateRange(input.dateUtc)
        return SearchQueryParams(
            text = input.text.trim().ifBlank { null },
            callsign = input.callsign.trim().ifBlank { null },
            frequencyHz = parseFrequencyHz(input.frequencyMhz),
            fromUtcMillis = from,
            toUtcMillis = to,
        )
    }

    private fun parseFrequencyHz(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val mhz = trimmed.toDoubleOrNull() ?: return null
        return Math.round(mhz * 1_000_000.0)
    }

    private fun parseDateRange(raw: String): Pair<Long?, Long?> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null to null
        return try {
            val day = LocalDate.parse(trimmed)
            val from = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val to = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            from to to
        } catch (e: DateTimeParseException) {
            null to null
        }
    }
}

/** The result of one search: the matches, and whether the free-text term was actually applied. */
public data class SearchResult(
    val details: List<TransmissionDetail>,
    /**
     * `true` when a non-blank [SearchQueryParams.text] could not be searched (the fts5 index is
     * unavailable) and the result reflects [SearchQueryParams]'s other filters only. Constitution
     * I: an unmet part of a query is content, never silently dropped.
     */
    val textSearchUnavailable: Boolean,
)

/**
 * The real read path for the Search destination (build-plan P15, FR-UI-3) — over
 * [org.ort.data.dao.SearchDao], the FTS5 index build-plan P5 built and nothing queried until now.
 */
public object SearchPolling {
    public suspend fun search(context: Context, params: SearchQueryParams): SearchResult {
        val db = OrtDatabase.create(context.applicationContext)
        return try {
            val entities = db.searchDao().search(
                text = params.text,
                callsign = params.callsign,
                frequencyHz = params.frequencyHz,
                fromUtc = params.fromUtcMillis,
                toUtc = params.toUtcMillis,
            )
            SearchResult(entities.map { ReaderPolling.detailFromEntity(context, it) }, textSearchUnavailable = false)
        } catch (e: SQLiteException) {
            // Only degrade for the specific, known fts5-missing case (this project's own
            // Robolectric host — see data/src/test/kotlin/org/ort/data/SearchDaoFullTextTest.kt);
            // any other database error is a real bug and must not be hidden behind a silent
            // fallback.
            val fts5Missing = e.message?.contains("fts5", ignoreCase = true) == true ||
                e.message?.contains("transcript_fts", ignoreCase = true) == true
            if (!fts5Missing || params.text == null) throw e
            val entities = db.searchDao().search(
                text = null,
                callsign = params.callsign,
                frequencyHz = params.frequencyHz,
                fromUtc = params.fromUtcMillis,
                toUtc = params.toUtcMillis,
            )
            SearchResult(entities.map { ReaderPolling.detailFromEntity(context, it) }, textSearchUnavailable = true)
        }
    }
}
