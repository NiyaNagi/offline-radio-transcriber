package org.ort.app.ui.data

import android.content.Context
import android.database.SQLException
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.Band
import org.ort.data.OrtDatabase
import org.ort.data.entity.TransmissionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * The Search destination's data model (ui-conformance-plan WP7, R-060..R-065; build-plan P15,
 * FR-UI-3). This is a full replacement of the pre-audit version: [SearchFilterInput]'s band,
 * attribution and rejected/corrected controls were tap-to-cycle text (R-061, a `halt` finding —
 * "the operator cannot see the options, cannot go back, and cannot tell it is interactive") and
 * its only time control was a single `Date (YYYY-MM-DD)` field where FR-UI-3 requires a range
 * (R-062). Every closed set here is now a value the *screen* renders as a visible list (chips or
 * checkboxes) — this file only carries the values and the query logic, never a "current option"
 * cycling scheme (guide §6.11).
 */

// -------------------------------------------------------------------------------------------
// FR-UI-3's time filter — a closed set of four options (R-062), never a single raw date field.
// -------------------------------------------------------------------------------------------

/** `Search-Filters.dc.html`'s four time chips. [RANGE] is the only one that reads the two range fields. */
public enum class SearchTimeFilter { TONIGHT, LAST_7_NIGHTS, RANGE, ALL }

/** Prose for [SearchTimeFilter] (guide §9: enum values are prose, never surfaced raw — R-064). */
public fun SearchTimeFilter.label(): String = when (this) {
    SearchTimeFilter.TONIGHT -> "Tonight"
    SearchTimeFilter.LAST_7_NIGHTS -> "Last 7 nights"
    SearchTimeFilter.RANGE -> "Range"
    SearchTimeFilter.ALL -> "All"
}

/** Prose for [AttributionState] (guide §9 — R-064: never `CONFIRMED`, always `Confirmed`). */
public fun AttributionState.prose(): String = when (this) {
    AttributionState.CONFIRMED -> "Confirmed"
    AttributionState.INFERRED -> "Inferred"
    AttributionState.AMBIGUOUS -> "Ambiguous"
    AttributionState.UNKNOWN -> "Unknown"
}

/** A human label for [band]: `HF_160M` -> `"160M"`, `VHF_1_25M` -> `"1.25M"`. */
public fun Band.prose(): String = name.substringAfter('_').replace('_', '.')

// -------------------------------------------------------------------------------------------
// Raw screen state.
// -------------------------------------------------------------------------------------------

/**
 * The search screen's raw, unvalidated fields (FR-UI-3). Every closed-set field ([band],
 * [timeFilter], [attributionStates]) already carries a typed value chosen from a visible list —
 * never free text standing in for a selection (R-061).
 */
public data class SearchFilterInput(
    val text: String = "",
    val callsign: String = "",
    val frequencyMhz: String = "",
    /** `null` means "all bands" — no band filter. */
    val band: Band? = null,
    val timeFilter: SearchTimeFilter = SearchTimeFilter.ALL,
    /** Free text, `yyyy-MM-dd'T'HH:mm`, read only when [timeFilter] is [SearchTimeFilter.RANGE]. */
    val rangeFromLocal: String = "",
    val rangeToLocal: String = "",
    /** The attribution states to include. All four is "no attribution filter". */
    val attributionStates: Set<AttributionState> = AttributionState.entries.toSet(),
    val includeRejected: Boolean = false,
    val includeCorrected: Boolean = true,
) {
    /** Whether every closed-set field is at its default — nothing to show as a dismissable chip. */
    public fun hasNoActiveFilters(): Boolean = band == null &&
        timeFilter == SearchTimeFilter.ALL &&
        attributionStates == AttributionState.entries.toSet() &&
        !includeRejected &&
        includeCorrected &&
        callsign.isBlank() &&
        frequencyMhz.isBlank()
}

/** Parsed, validated filter values sent to `:data`'s `SearchDao` — `null` means "no filter". */
public data class SearchQueryParams(
    val text: String? = null,
    val callsign: String? = null,
    val frequencyHz: Long? = null,
    val fromUtcMillis: Long? = null,
    val toUtcMillis: Long? = null,
    val band: Band? = null,
)

/**
 * The closed-set facets [SearchQueryParams] cannot express as a DAO bind parameter (`SearchDao`
 * takes one nullable [AttributionState] and one nullable rejected flag — R-061 needs a *set* of
 * states and two independent include toggles), applied client-side after the base query runs.
 */
public data class SearchFacetFilter(
    val attributionStates: Set<AttributionState>,
    val includeRejected: Boolean,
    val includeCorrected: Boolean,
) {
    public fun matches(entity: TransmissionEntity): Boolean {
        if (entity.attributionState !in attributionStates) return false
        if (!includeRejected && entity.processingState == TransmissionState.REJECTED) return false
        if (!includeCorrected && entity.corrected) return false
        return true
    }

    public companion object {
        public fun from(input: SearchFilterInput): SearchFacetFilter = SearchFacetFilter(
            attributionStates = input.attributionStates,
            includeRejected = input.includeRejected,
            includeCorrected = input.includeCorrected,
        )
    }
}

/**
 * Turns [SearchFilterInput] into [SearchQueryParams] (FR-UI-3). Every field is independently
 * optional and a field that fails to parse is dropped rather than crashing the screen or silently
 * asserting a filter that can never match. [nowUtcMillis] anchors [SearchTimeFilter.TONIGHT]/
 * [SearchTimeFilter.LAST_7_NIGHTS] — passed in rather than read from a clock so this stays a pure
 * function (constitution II — no hidden clock read inside `:app`'s only pure-parsing seam).
 *
 * R-371: [SearchFilterInput.text] — the free-text query box, not the Filters sheet's own
 * dedicated [SearchFilterInput.callsign]/[SearchFilterInput.frequencyMhz] fields — is routed by
 * shape before it reaches FTS. A frequency-shaped token ("146.960"/"146.96", the app's own TRY
 * hint) never matched anything as literal transcript prose — nobody speaks a frequency into a
 * transmission — so it is pulled out and filtered against the structured `frequencyHz` column
 * instead. A callsign-shaped token is pulled out and matched against the attribution/candidate
 * column ([org.ort.data.dao.SearchDao]'s own `callsign` bind, already a `station.callsign`
 * comparison — no `:data` change needed). Whatever tokens are left, if any, still go to FTS —
 * `"146.96 park"` finds every "park" over on 146.960MHz, not nothing. The Filters sheet's own
 * dedicated fields, if set, always win over a shape match in the free-text box (typing a
 * frequency into the box is a shortcut for the same field, not a second, competing filter).
 */
public object SearchFilterParser {
    private val RANGE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    public fun parse(input: SearchFilterInput, nowUtcMillis: Long): SearchQueryParams {
        val (from, to) = timeRange(input, nowUtcMillis)
        val routed = TextQueryRouter.route(input.text)
        return SearchQueryParams(
            text = routed.remainingText,
            callsign = input.callsign.trim().ifBlank { null } ?: routed.callsign,
            frequencyHz = parseFrequencyHz(input.frequencyMhz) ?: routed.frequencyHz,
            fromUtcMillis = from,
            toUtcMillis = to,
            band = input.band,
        )
    }

    private fun parseFrequencyHz(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val mhz = trimmed.toDoubleOrNull() ?: return null
        return Math.round(mhz * 1_000_000.0)
    }

    private fun timeRange(input: SearchFilterInput, nowUtcMillis: Long): Pair<Long?, Long?> {
        val today = Instant.ofEpochMilli(nowUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()
        return when (input.timeFilter) {
            SearchTimeFilter.ALL -> null to null
            SearchTimeFilter.TONIGHT -> startOfUtcDay(today) to startOfUtcDay(today.plusDays(1))
            SearchTimeFilter.LAST_7_NIGHTS -> startOfUtcDay(today.minusDays(6)) to startOfUtcDay(today.plusDays(1))
            SearchTimeFilter.RANGE -> parseRangeInstant(input.rangeFromLocal) to parseRangeInstant(input.rangeToLocal)
        }
    }

    private fun startOfUtcDay(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun parseRangeInstant(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return try {
            LocalDateTime.parse(trimmed, RANGE_FORMAT).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }
}

/**
 * R-371: splits a free-text query into its frequency-shaped token, its callsign-shaped token, and
 * whatever plain-prose tokens are left — see [SearchFilterParser.parse]'s own doc comment for why.
 * At most one token of each shape is pulled out (the first one found, left to right); a second
 * token that happens to share a shape falls through to the remaining FTS text unchanged — this is
 * a query-routing convenience, not a general-purpose multi-value filter the rest of this file
 * (one `frequencyHz`, one `callsign`) does not support either.
 */
internal object TextQueryRouter {
    private val WHITESPACE: Regex = Regex("\\s+")

    /** MHz with 2–3 decimals — "146.96"/"146.960", the app's own TRY hint shapes. A bare integer
     * ("7") or an over-precise value is left as plain text: neither is a frequency an operator
     * would type into this box the way "146.96" is. */
    private val FREQUENCY_TOKEN: Regex = Regex("^\\d{1,3}\\.\\d{2,3}$")

    /** The same shape `SearchScreen.kt`'s own (private, UI-only) `isCallsignLike` mono-styling
     * check uses — 1–2 letters, a digit, 1–4 more letters/digits. */
    private val CALLSIGN_TOKEN: Regex = Regex("^[A-Za-z]{1,2}[0-9][A-Za-z0-9]{1,4}$")

    internal data class Routed(val remainingText: String?, val callsign: String?, val frequencyHz: Long?)

    internal fun route(rawText: String): Routed {
        val tokens = rawText.trim().split(WHITESPACE).filter { it.isNotBlank() }
        var frequencyToken: String? = null
        var callsignToken: String? = null
        val remaining = mutableListOf<String>()
        for (token in tokens) {
            when {
                frequencyToken == null && FREQUENCY_TOKEN.matches(token) -> frequencyToken = token
                callsignToken == null && CALLSIGN_TOKEN.matches(token) -> callsignToken = token
                else -> remaining += token
            }
        }
        return Routed(
            remainingText = remaining.joinToString(" ").ifBlank { null },
            callsign = callsignToken,
            frequencyHz = frequencyToken?.let { Math.round(it.toDouble() * 1_000_000.0) },
        )
    }
}

// -------------------------------------------------------------------------------------------
// Results.
// -------------------------------------------------------------------------------------------

/** One (state, rejected, corrected) fact — enough to count every facet without re-querying. */
public data class SearchFacetRow(val attributionState: AttributionState, val rejected: Boolean, val corrected: Boolean)

/**
 * Counts over every transmission [SearchQueryParams] matched — **before** [SearchFacetFilter] is
 * applied, so the filter sheet can show "how many if I include this too" (R-061's "checkboxes
 * with counts") without a second query per checkbox tap.
 */
public data class SearchFacetCounts(val rows: List<SearchFacetRow>) {
    public val total: Int get() = rows.size
    public val confirmed: Int get() = rows.count { it.attributionState == AttributionState.CONFIRMED }
    public val inferred: Int get() = rows.count { it.attributionState == AttributionState.INFERRED }
    public val ambiguous: Int get() = rows.count { it.attributionState == AttributionState.AMBIGUOUS }
    public val unknown: Int get() = rows.count { it.attributionState == AttributionState.UNKNOWN }
    public val rejectedCount: Int get() = rows.count { it.rejected }
    public val correctedCount: Int get() = rows.count { it.corrected }

    /** The exact count [filter] would leave — what `Show N overs` reports (never a fabricated number). */
    public fun countMatching(filter: SearchFacetFilter): Int = rows.count { row ->
        row.attributionState in filter.attributionStates &&
            (filter.includeRejected || !row.rejected) &&
            (filter.includeCorrected || !row.corrected)
    }

    public companion object {
        public val EMPTY: SearchFacetCounts = SearchFacetCounts(emptyList())
    }
}

/** The result of one search: the matches, whether free text was actually applied, and the facet breakdown. */
public data class SearchResult(
    val details: List<TransmissionDetail>,
    /**
     * `true` when a non-blank [SearchQueryParams.text] could not be searched (the fts5 index is
     * unavailable) and the result reflects [SearchQueryParams]'s other filters only. Constitution
     * I: an unmet part of a query is content, never silently dropped.
     */
    val textSearchUnavailable: Boolean,
    val facetCounts: SearchFacetCounts,
)

/**
 * `search-unavailable` (`app/src/debug/kotlin/org/ort/app/debug/Scenarios.kt`, WP4's own row)'s
 * entry point for forcing [SearchPolling.search]'s real fts5-unavailable degrade path
 * ([SearchResult.textSearchUnavailable]) — the exact same debug-override-receiver pattern
 * [org.ort.app.ui.failures.DebugFailureOverride] already established for six failure boards with
 * no real signal (WP11b's own file, see its own kdoc) and [DebugLexiconImportOverride] established
 * for F12 (this file's own sibling, `ModelsViewData.kt`).
 *
 * **Why a flag, not real corruption of `transcript_fts`.** A real SQL-level break was tried first
 * and rejected: [org.ort.data.OrtDatabase.create]'s own `ensureFtsIndex` touches `transcript_fts`
 * unconditionally on every single call it makes (an fts5 `'rebuild'`, once the virtual table's own
 * `CREATE VIRTUAL TABLE IF NOT EXISTS` step succeeds — register R-204), so any schema-level
 * corruption of its shadow tables survives past that self-heal and breaks the fts5 virtual table's
 * own *construction*, not merely a query against it — every later `OrtDatabase.create()` call
 * anywhere in the app, not just Search, then fails outright (`vtable constructor failed:
 * transcript_fts`, confirmed directly against real `BundledSQLiteDriver` SQLite in this package's
 * own Robolectric test before this object existed, not assumed from FTS5's own documentation).
 * [consumeForcedUnavailable] is one-shot rather than a sticky flag specifically so `Retry` — which
 * only ever calls [SearchPolling.search] again — genuinely recovers to real results on its second
 * call, the same "index was rebuilding, now it is ready" story `Search-Unavailable.dc.html`'s own
 * `Retry` action tells.
 *
 * **Read gated on `BuildConfig.DEBUG`**, same as every sibling override in this codebase: a release
 * build must never consult this even in the already-impossible case that something set it.
 */
public object DebugSearchOverride {

    @Volatile
    private var pending: Boolean = false

    /** Test seam (see class kdoc) — production code never assigns this. */
    @Volatile
    internal var isDebugBuild: () -> Boolean = { org.ort.app.BuildConfig.DEBUG }

    /** The scenario simulator's own entry point (debug-sourceset-only caller — see class kdoc). */
    public fun forceNextTextSearchUnavailable() {
        pending = true
    }

    public fun clear() {
        pending = false
    }

    /** [SearchPolling.search]'s own read — consumes the pending override so it fires at most once
     * per [forceNextTextSearchUnavailable] call. `false` outright in any non-debug build. */
    public fun consumeForcedUnavailable(): Boolean {
        if (!isDebugBuild() || !pending) return false
        pending = false
        return true
    }
}

/**
 * The real read path for the Search destination (build-plan P15, FR-UI-3) — over
 * [org.ort.data.dao.SearchDao], reads through [OrtDatabase] directly (never through
 * [org.ort.app.ui.data.ReaderPolling]'s own query wrappers — those are WP4's; only the shared
 * entity->[TransmissionDetail] mapper [ReaderPolling.detailFromEntity] is reused, exactly as its
 * own doc comment says it exists for).
 */
public object SearchPolling {
    public suspend fun search(
        context: Context,
        params: SearchQueryParams,
        facetFilter: SearchFacetFilter,
    ): SearchResult {
        val db = OrtDatabase.create(context.applicationContext)
        // register (round-eleven tooling): DebugSearchOverride's own one-shot forced-unavailable
        // check, ahead of the real query — see that object's own kdoc for why a flag, not real
        // corruption of transcript_fts. Consulted only when there is real free text to degrade
        // (a filters-only search never touches transcript_fts either way, forced or real).
        if (params.text != null && DebugSearchOverride.consumeForcedUnavailable()) {
            val entities = rawSearch(db, params, text = null)
            return buildResult(context, entities, facetFilter, textSearchUnavailable = true)
        }
        return try {
            val entities = rawSearch(db, params, params.text)
            buildResult(context, entities, facetFilter, textSearchUnavailable = false)
        } catch (e: SQLException) {
            // Only degrade for the specific, known fts5-missing case. Register R-204: on every
            // supported SQLite build (`OrtDatabase.create` always installs `BundledSQLiteDriver`)
            // this branch should now be unreachable — kept, not deleted, as the honest fallback
            // this file has always promised rather than a silent crash if that ever regresses.
            // `SQLException` (not `SQLiteException`) because the driver throws its base Android-
            // compatible type for a failed prepare, not always the narrower subclass — confirmed
            // empirically (see `data/src/main/kotlin/org/ort/data/OrtDatabase.kt`'s `createFtsIndex`).
            // Any other database error is a real bug and must not be hidden behind a silent fallback.
            val fts5Missing = e.message?.contains("fts5", ignoreCase = true) == true ||
                e.message?.contains("transcript_fts", ignoreCase = true) == true
            if (!fts5Missing || params.text == null) throw e
            val entities = rawSearch(db, params, text = null)
            buildResult(context, entities, facetFilter, textSearchUnavailable = true)
        }
    }

    /**
     * R-202: the same facet breakdown [search] computes, for a caller (the filters sheet) that
     * only needs live counts for the current text/callsign/band/time filters — not the results
     * themselves. Runs the identical base query and the identical fts5-unavailable degrade path
     * as [search], just without building the full `TransmissionDetail` list. The filter sheet
     * calls this itself, on open and whenever the non-facet filters change, rather than relying on
     * whatever `SearchResult.facetCounts` a *previous* `onSearch` happened to have produced (which
     * is `SearchFacetCounts.EMPTY` before any search has ever run, at default filters, on a
     * non-empty corpus — the exact "every count reads 0" bug this fixes).
     */
    public suspend fun facetCounts(context: Context, params: SearchQueryParams): SearchFacetCounts {
        val db = OrtDatabase.create(context.applicationContext)
        val entities = try {
            rawSearch(db, params, params.text)
        } catch (e: SQLException) {
            val fts5Missing = e.message?.contains("fts5", ignoreCase = true) == true ||
                e.message?.contains("transcript_fts", ignoreCase = true) == true
            if (!fts5Missing || params.text == null) throw e
            rawSearch(db, params, text = null)
        }
        return SearchFacetCounts(entities.map { it.toFacetRow() })
    }

    private suspend fun rawSearch(db: OrtDatabase, params: SearchQueryParams, text: String?): List<TransmissionEntity> =
        db.searchDao().search(
            text = text,
            callsign = params.callsign,
            frequencyHz = params.frequencyHz,
            fromUtc = params.fromUtcMillis,
            toUtc = params.toUtcMillis,
            band = params.band,
        )

    private suspend fun buildResult(
        context: Context,
        entities: List<TransmissionEntity>,
        facetFilter: SearchFacetFilter,
        textSearchUnavailable: Boolean,
    ): SearchResult {
        val facetCounts = SearchFacetCounts(entities.map { it.toFacetRow() })
        val filtered = entities.filter { facetFilter.matches(it) }
        val details = filtered.map { ReaderPolling.detailFromEntity(context, it) }
        return SearchResult(details, textSearchUnavailable, facetCounts)
    }

    private fun TransmissionEntity.toFacetRow(): SearchFacetRow = SearchFacetRow(
        attributionState = attributionState,
        rejected = processingState == TransmissionState.REJECTED,
        corrected = corrected,
    )
}

/** R-203: the exact frequencies actually heard in this corpus — the choices
 * `SearchFiltersSheet`'s frequency/band chips offer, never a generic fixed band table
 * (`Search-Filters.dc.html`'s "145.230 / 146.960" chips, derived from real data). */
public object HeardFrequencies {
    public suspend fun list(context: Context): List<Long> {
        val db = OrtDatabase.create(context.applicationContext)
        return db.activityDao().listDistinctFrequencies()
    }
}

// -------------------------------------------------------------------------------------------
// No-results widening (Search-Empty.dc.html, R-063) — every count here is real, never fabricated
// (guide §9: "never fabricate a number").
// -------------------------------------------------------------------------------------------

/** One "loosen this filter" suggestion, with the real count it would give. */
public data class SearchWidenOption(val id: String, val label: String, val detail: String)

/** `Search-Empty.dc.html`'s state: why nothing matched, what would widen it, and near-miss callsigns. */
public data class SearchWidenViewState(
    val narrowingSummary: String,
    val options: List<SearchWidenOption>,
    val similarCallsigns: List<String>,
)

public object SearchWidenSuggestions {

    public suspend fun build(
        context: Context,
        input: SearchFilterInput,
        params: SearchQueryParams,
        facetFilter: SearchFacetFilter,
        facetCounts: SearchFacetCounts,
    ): SearchWidenViewState {
        val options = mutableListOf<SearchWidenOption>()

        suspend fun addDropOption(id: String, label: String, widerParams: SearchQueryParams) {
            val count = countMatching(context, widerParams, facetFilter)
            if (count > 0) {
                options += SearchWidenOption(id = id, label = label, detail = "would show $count ${overWord(count)}")
            }
        }

        if (input.timeFilter != SearchTimeFilter.ALL) {
            addDropOption("all_nights", "All nights", params.copy(fromUtcMillis = null, toUtcMillis = null))
        }
        // R-372: "drop each active filter" — band/frequency/callsign each get their own honest-
        // count widen row exactly like the time filter already did, not just the two that existed
        // before this fix. `params.callsign` (not `input.callsign`) so a callsign the free-text box
        // routed here (R-371) is offered a drop option too, not only one typed into the dedicated
        // Filters-sheet field.
        if (params.band != null) {
            addDropOption("drop_band", "Any band", params.copy(band = null))
        }
        if (params.frequencyHz != null) {
            addDropOption("drop_frequency", "Any frequency", params.copy(frequencyHz = null))
        }
        if (params.callsign != null) {
            addDropOption("drop_callsign", "Any callsign", params.copy(callsign = null))
        }

        val everyState = SearchFacetFilter(
            attributionStates = AttributionState.entries.toSet(),
            includeRejected = true,
            includeCorrected = true,
        )
        val widerFacetCount = facetCounts.countMatching(everyState)
        val currentFacetCount = facetCounts.countMatching(facetFilter)
        if (widerFacetCount > currentFacetCount) {
            options += SearchWidenOption(
                id = "include_all_states",
                label = "Include every attribution state, rejected and corrected",
                detail = "would show $widerFacetCount ${overWord(widerFacetCount)}",
            )
        }

        // R-372: sourced from `params.callsign`, not `input.callsign` — a callsign the free-text
        // box routed here by shape (R-371, e.g. a typo'd "KE7QRT") reaches this exactly the same
        // way a dedicated Filters-sheet callsign field does; before this fix a typo typed into the
        // *search box* found nothing and got no suggestion at all ("KE7QRT should offer KE7QRS").
        val similar = if (params.callsign != null) {
            SimilarCallsigns.near(context, params.callsign)
        } else {
            emptyList()
        }

        return SearchWidenViewState(
            narrowingSummary = narrowingSummary(input),
            options = options,
            similarCallsigns = similar,
        )
    }

    private suspend fun countMatching(
        context: Context,
        params: SearchQueryParams,
        facetFilter: SearchFacetFilter,
    ): Int {
        val db = OrtDatabase.create(context.applicationContext)
        val entities = try {
            db.searchDao().search(
                text = params.text,
                callsign = params.callsign,
                frequencyHz = params.frequencyHz,
                fromUtc = params.fromUtcMillis,
                toUtc = params.toUtcMillis,
                band = params.band,
            )
        } catch (e: SQLException) {
            val fts5Missing = e.message?.contains("fts5", ignoreCase = true) == true ||
                e.message?.contains("transcript_fts", ignoreCase = true) == true
            if (!fts5Missing || params.text == null) throw e
            db.searchDao().search(
                text = null,
                callsign = params.callsign,
                frequencyHz = params.frequencyHz,
                fromUtc = params.fromUtcMillis,
                toUtc = params.toUtcMillis,
                band = params.band,
            )
        }
        return entities.count { facetFilter.matches(it) }
    }

    private fun overWord(count: Int): String = if (count == 1) "over" else "overs"

    /** `Search-Empty.dc.html`'s "The two filters above are doing the narrowing" line. */
    private fun narrowingSummary(input: SearchFilterInput): String {
        val narrowing = mutableListOf<String>()
        if (input.callsign.isNotBlank()) narrowing += "the callsign filter"
        if (input.text.isNotBlank()) narrowing += "the text search"
        if (input.band != null) narrowing += "the band filter"
        if (input.frequencyMhz.isNotBlank()) narrowing += "the frequency filter"
        if (input.timeFilter != SearchTimeFilter.ALL) narrowing += "the time filter"
        if (input.attributionStates != AttributionState.entries.toSet()) narrowing += "the attribution filter"
        return when (narrowing.size) {
            0 -> "Nothing narrowed this — there is genuinely nothing recorded yet."
            1 -> "${narrowing[0].replaceFirstChar { it.uppercase() }} is doing the narrowing."
            else -> "${narrowing.dropLast(1).joinToString(", ")} and ${narrowing.last()} are doing the narrowing."
        }
    }
}

/** Callsigns one edit away from a searched callsign that found nothing (`Search-Empty.dc.html`). */
public object SimilarCallsigns {
    public suspend fun near(context: Context, callsign: String, maxResults: Int = 3): List<String> {
        val target = callsign.trim().uppercase()
        if (target.isEmpty()) return emptyList()
        val db = OrtDatabase.create(context.applicationContext)
        return db.activityDao().listStations()
            .mapNotNull { it.callsign?.uppercase() }
            .distinct()
            .filter { it != target && editDistanceAtMost1(target, it) }
            .sorted()
            .take(maxResults)
    }

    /** True when [a] and [b] differ by at most one insertion, deletion or substitution. */
    private fun editDistanceAtMost1(a: String, b: String): Boolean {
        if (a == b) return false
        val lengthDiff = a.length - b.length
        if (lengthDiff !in -1..1) return false
        if (a.length == b.length) {
            // Same length: exactly one substitution allowed.
            return a.indices.count { a[it] != b[it] } == 1
        }
        // One insertion/deletion apart: walk both, allow exactly one skip on the longer string.
        val (shorter, longer) = if (a.length < b.length) a to b else b to a
        var i = 0
        var j = 0
        var skipped = false
        while (i < shorter.length && j < longer.length) {
            if (shorter[i] == longer[j]) {
                i++
                j++
            } else if (!skipped) {
                skipped = true
                j++
            } else {
                return false
            }
        }
        return true
    }
}

/**
 * R-065 (`Search-Results.dc.html`'s `.hit` spans, `LogRowViewState.highlightRanges`): the
 * character ranges of a transcript that match a word in the search query — computed here, in the
 * data layer, once per result row, rather than in the screen, so `SearchScreen`'s own mapping to
 * `LogRowViewState` stays a plain, untested-logic-free field copy.
 */
public object MatchHighlighter {
    private val WHITESPACE: Regex = Regex("\\s+")

    /**
     * Every non-overlapping, case-insensitive occurrence of a whitespace-separated token from
     * [query] inside [transcript], sorted by position. This mirrors what the operator searched
     * for closely enough to be a useful reading aid — it is not a re-implementation of FTS5's own
     * match semantics (stemming, operators), which is not this function's job. Empty for a blank
     * query or transcript, never "highlight everything".
     */
    public fun rangesFor(transcript: String, query: String): List<IntRange> {
        val tokens = query.trim().split(WHITESPACE).filter { it.isNotBlank() }
        if (tokens.isEmpty() || transcript.isEmpty()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        for (token in tokens) {
            var from = 0
            while (from <= transcript.length - token.length) {
                val idx = transcript.indexOf(token, from, ignoreCase = true)
                if (idx < 0) break
                ranges += idx..(idx + token.length - 1)
                from = idx + token.length
            }
        }
        return ranges.sortedBy { it.first }
    }
}
