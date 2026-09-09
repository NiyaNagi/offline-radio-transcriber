package org.ort.app.debug.tour

import android.content.Context
import org.ort.app.ui.data.FrequencyDetailView
import org.ort.app.ui.data.LogFilterSelection
import org.ort.app.ui.data.StationSubScreen
import org.ort.app.ui.navigation.NavSeed
import org.ort.app.ui.settings.SettingsScreenId
import org.ort.core.AttributionState
import org.ort.data.OrtDatabase

/**
 * spec/ui-conformance-plan.md WP12 v2 — resolves a [TourStep.drillIn] map into a real
 * [org.ort.app.ui.navigation.NavSeed] by reading the *just-loaded* scenario's own data, so
 * `tools/ui-audit/tour.json` can say `{"drillIn": {"transmission": "ambiguous"}}` rather than
 * hard-coding a fixture's ULIDs (the coordinator's own brief, verbatim) — a fixture can be
 * re-seeded with different ids at any time; a symbolic name survives that, a copied ULID does not.
 *
 * Every key here maps to exactly one [NavSeed] field:
 *
 * - `transmission`: `confirmed` | `inferred` | `ambiguous` | `unknown` (the first transmission in
 *   [sessionId] carrying that [AttributionState]), or a literal transmission id as a fallback for a
 *   case this table does not name.
 * - `station`: a callsign (looked up against every station this database knows, not scoped to one
 *   session — stations persist across sessions), or a literal station id.
 * - `frequency`: a literal Hz value (`"145230000"`, the coordinator's own example — a frequency is
 *   not a fixture row with its own id the way a transmission/station/thread is, so there is nothing
 *   to look up beyond parsing the number).
 * - `thread`: `any` (the first transmission in [sessionId] carrying a non-null `threadId`), or a
 *   literal thread id.
 * - `logFilterFrequency` / `logFilterFromMillis` / `logFilterToMillis`: literal values assembled
 *   into one [LogFilterSelection] (R-276's own shape — see [NavSeed.pendingLogFilter]'s own doc
 *   comment for why only these three fields are seedable at all).
 * - `captureLevelMeter`: `true` (anything else is `false`/absent).
 * - `reviewSession`: `self` (this step's own loaded [sessionId] — `Settings-Storage`'s "Review" link
 *   always seeds *some* session, and the one this step just loaded is the only one a step naming no
 *   other scenario could sensibly mean), or a literal session id.
 * - `frequencyInitialView`: `Detail` | `Change`.
 * - `stationSubScreen` (WP8's own seam, round 14): a [StationSubScreen] name — `PATTERN` (ST03) or
 *   `IDENTITY` (ST04) — a companion to `station` the same way `frequencyInitialView` companions
 *   `frequency`: meaningful only alongside a `station` key in the same step (a `stationSubScreen`
 *   named with no `station` resolves a real [NavSeed.openStationSubScreen] that `OrtNavHost` never
 *   reads, since it only seeds a station's sub-screen once [NavSeed.openStationId] itself is set).
 * - `settingsScreen`: a [SettingsScreenId] name — unchanged from v1, carried through [NavSeed] now
 *   instead of a separate parameter.
 * - `searchQuery` / `searchSubmit` / `searchFiltersOpen` (v4, round 14's `NavSeed` fields): a literal
 *   query string, whether to run it (`SearchContent.submitOnStart`, the same two calls the query
 *   field's own keyboard search action makes — real results, a real empty state, `search-unavailable`'s
 *   own real override, never faked), and whether the filters sheet starts open.
 * - `logSheetOpen` (v4): `true` opens `Log`'s own filter sheet (L02) on first composition.
 * - `revisionsOpen` (v4): a companion to `transmission` (the same relationship `frequencyInitialView`
 *   has to `frequency`) — `true` opens the revisions list (D07) on the resolved transmission.
 *
 * A key naming a symbolic value this table does not recognise, and that also does not resolve as a
 * literal id/number, throws — caught by [TourRunner] the same as any other per-step failure, never
 * silently producing an empty/wrong seed.
 */
public object TourIds {

    public suspend fun resolveSeed(context: Context, sessionId: String?, drillIn: Map<String, String>): NavSeed? {
        if (drillIn.isEmpty()) return null
        val db = OrtDatabase.create(context)
        return NavSeed(
            openTransmissionId = drillIn["transmission"]?.let { resolveTransmissionId(db, sessionId, it) },
            openStationId = drillIn["station"]?.let { resolveStationId(db, it) },
            openFrequencyHz = drillIn["frequency"]?.let { resolveFrequencyHz(it) },
            openThreadId = drillIn["thread"]?.let { resolveThreadId(db, sessionId, it) },
            openStationSubScreen = drillIn["stationSubScreen"]?.let { name ->
                StationSubScreen.entries.firstOrNull { it.name == name }
                    ?: error("unknown stationSubScreen '$name' — expected PATTERN, IDENTITY or SPLIT")
            },
            pendingLogFilter = resolveLogFilter(drillIn),
            openCaptureLevelMeter = drillIn["captureLevelMeter"]?.let { it.equals("true", ignoreCase = true) },
            pendingReviewSessionId = drillIn["reviewSession"]?.let { if (it == "self") sessionId else it },
            frequencyInitialView = drillIn["frequencyInitialView"]?.let { name ->
                FrequencyDetailView.entries.firstOrNull { it.name == name }
                    ?: error("unknown frequencyInitialView '$name' — expected Detail or Change")
            },
            settingsScreen = drillIn["settingsScreen"]?.let { name ->
                SettingsScreenId.entries.firstOrNull { it.name == name }
                    ?: error("unknown settingsScreen '$name'")
            },
            searchQuery = drillIn["searchQuery"],
            searchSubmit = drillIn["searchSubmit"]?.let { it.equals("true", ignoreCase = true) },
            searchFiltersOpen = drillIn["searchFiltersOpen"]?.let { it.equals("true", ignoreCase = true) },
            logSheetOpen = drillIn["logSheetOpen"]?.let { it.equals("true", ignoreCase = true) },
            openTransmissionRevisions = drillIn["revisionsOpen"]?.let { it.equals("true", ignoreCase = true) },
        )
    }

    private suspend fun resolveTransmissionId(db: OrtDatabase, sessionId: String?, value: String): String {
        val state = when (value) {
            "confirmed" -> AttributionState.CONFIRMED
            "inferred" -> AttributionState.INFERRED
            "ambiguous" -> AttributionState.AMBIGUOUS
            "unknown" -> AttributionState.UNKNOWN
            else -> null
        }
        if (state == null) return value // literal fallback
        val session = requireNotNull(sessionId) { "transmission:'$value' needs a session, but this step loaded none" }
        val match = db.transmissionDao().listBySession(session).firstOrNull { it.attributionState == state }
        return requireNotNull(match?.id) {
            "no $state transmission found in session '$session' for drillIn transmission:'$value'"
        }
    }

    private suspend fun resolveThreadId(db: OrtDatabase, sessionId: String?, value: String): String {
        if (value != "any") return value // literal fallback
        val session = requireNotNull(sessionId) { "thread:'any' needs a session, but this step loaded none" }
        val match = db.transmissionDao().listBySession(session).firstOrNull { it.threadId != null }
        return requireNotNull(match?.threadId) { "no transmission with a threadId found in session '$session'" }
    }

    private suspend fun resolveStationId(db: OrtDatabase, value: String): String {
        val match = db.activityDao().listStations().firstOrNull { it.callsign == value }
        return match?.id ?: value // literal fallback when no station carries this callsign
    }

    private fun resolveFrequencyHz(value: String): Long =
        value.toLongOrNull() ?: error("drillIn frequency '$value' is not a Hz integer")

    private fun resolveLogFilter(drillIn: Map<String, String>): LogFilterSelection? {
        val frequencyHz = drillIn["logFilterFrequency"]?.let { resolveFrequencyHz(it) } ?: return null
        return LogFilterSelection(
            frequencyHz = frequencyHz,
            fromMillis = drillIn["logFilterFromMillis"]?.toLongOrNull(),
            toMillis = drillIn["logFilterToMillis"]?.toLongOrNull(),
        )
    }
}
