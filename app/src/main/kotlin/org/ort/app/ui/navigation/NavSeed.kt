package org.ort.app.ui.navigation

import android.content.Intent
import org.ort.app.ui.data.FrequencyDetailView
import org.ort.app.ui.data.LogFilterSelection
import org.ort.app.ui.data.StationSubScreen
import org.ort.app.ui.settings.SettingsScreenId

/**
 * Round 13 (the coordinator's own seam for WP12's screenshot tour, under `app/src/debug`'s own
 * `tour` package):
 * every piece of "land directly here" state [OrtNavHost] owns beyond a destination or a Settings
 * sub-screen — both already reachable via [org.ort.app.ui.ReaderActivity.EXTRA_DESTINATION]/
 * `EXTRA_SETTINGS_SCREEN` — was, before this, private to `OrtNavHost.kt`'s own `NavHostNavState`
 * and reachable only by tapping a real row: the four drill-in ids (`openTransmissionId`,
 * `openStationId`, `openFrequencyHz`, `openThreadId`), `Log`'s own R-276 filter
 * (`pendingLogFilter`), `Capture`'s level meter (`openCaptureLevelMeter`), `Earlier nights`'s
 * Review-seeded detail (`pendingReviewSessionId`), which sub-screen a seeded frequency drill-in
 * lands on (`frequencyInitialView`) and, round 14, which sub-screen a seeded station drill-in
 * lands on (`openStationSubScreen`, ST03/ST04 for the tour). This is the public seam: every field defaults `null` (or
 * `false`/`Detail` — same shape those fields' own `NavHostNavState` defaults already use), so a
 * caller that never builds one at all (`seed = null`, the default on both
 * [rememberReaderNavigator] and [OrtNavHost]) sees exactly today's unseeded behaviour, and every
 * existing caller keeps compiling and behaving unchanged.
 *
 * Round 14: `Log`'s own filter sheet (`LogContent.kt`'s `sheetOpen`) and `Search`'s own
 * (`SearchContent.kt`'s `filtersSheetOpen`) — round 13's own report named neither as accepting an
 * initial-open parameter; both now do (`LogContent.initialSheetOpen`, WP5's own merge;
 * `SearchContent.initialFiltersOpen`, WP7's, already wired above) — [logSheetOpen] closes that gap.
 *
 * [settingsScreen] duplicates [rememberReaderNavigator]'s own `initialSettingsScreen` in shape —
 * kept here too so one `NavSeed` is the single thing a caller (the tour, [NavSeed.fromIntent])
 * needs to build, rather than a `NavSeed` for drill-ins plus a second, separate parameter for
 * `Settings`.
 */
/** R-840: the two screens `Earlier nights`' [NavSeed.pendingReviewSessionId] can land a seeded
 * session detail on — see [NavSeed.reviewSessionView]'s own doc comment. */
public enum class ReviewSessionView { SESSION, DIGEST }

public data class NavSeed(
    val openTransmissionId: String? = null,
    val openStationId: String? = null,
    val openFrequencyHz: Long? = null,
    val openThreadId: String? = null,
    val pendingLogFilter: LogFilterSelection? = null,
    val openCaptureLevelMeter: Boolean? = null,
    val pendingReviewSessionId: String? = null,
    val frequencyInitialView: FrequencyDetailView? = null,
    val settingsScreen: SettingsScreenId? = null,
    // Round 14, register R-276 follow-on (WP12 screenshot tour, coordinator round 2026-09-08,
    // WP8 shipped `StationDetailContent.initialSubScreen`): which sub-screen a seeded station
    // drill-in opens on — `null`/`NONE` for every ordinary seed, `PATTERN`/`IDENTITY`/`SPLIT` only
    // for the tour's own ST03/ST04 steps.
    val openStationSubScreen: StationSubScreen? = null,
    // Round 14 (after WP7 merged `SearchContent(initialQuery, submitOnStart, initialFiltersOpen)`
    // — confirmed by reading `ui/screens/SearchContent.kt` before wiring this): the tour's own
    // gap on `SEARCH` — no real keyboard/tap to reach results, the empty state, or the open
    // filters sheet. `searchSubmit`/`searchFiltersOpen` are nullable `Boolean` (not plain
    // `Boolean = false`, unlike `SearchContent`'s own params) for the same reason
    // [openCaptureLevelMeter] already is: `null` here means "say nothing," distinct from an
    // explicit `false` a caller could still choose to pass.
    val searchQuery: String? = null,
    val searchSubmit: Boolean? = null,
    val searchFiltersOpen: Boolean? = null,
    // Round 14 (after WP5 merged `LogContent(initialSheetOpen)` — confirmed by reading
    // `ui/screens/LogContent.kt` before wiring this): the L02 half of round 13's own reported gap.
    val logSheetOpen: Boolean? = null,
    // Round 14 (after WP6 merged `TransmissionDetailContent(initialRevisionsOpen)` — confirmed by
    // reading `ui/screens/TransmissionDetailContent.kt` before wiring this, which shipped only
    // this one seam, no sibling why/lattice flag). Only meaningful alongside [openTransmissionId],
    // the same companion relationship [frequencyInitialView] already has to [openFrequencyHz].
    val openTransmissionRevisions: Boolean? = null,
    // WP12 (screenshot-tour gap, register R-010..R-014/R-334: N00 `Menu.dc.html` had no tour step
    // at all — every other designed screen did) — `true` opens the drawer on first composition,
    // over whichever [destination] the step's own `ReaderDestination` names (`OrtNavHost`'s own
    // `rememberDrawerState` initial value, not a synthesised tap on the header's drawer icon,
    // which this seam exists precisely to avoid). `null`/`false` (every existing caller) changes
    // nothing — same "unseeded behaviour by default" contract every other field here already has.
    val openDrawer: Boolean? = null,
    // R-840 (tour finding, round 3): no seed field opened the Digest for a session at all — the
    // tour landed on `Earlier nights`'s own `Session` (DG04) detail every time, via
    // [pendingReviewSessionId], never DG01/DG05. Only meaningful alongside [pendingReviewSessionId]
    // (the same companion relationship [frequencyInitialView] already has to [openFrequencyHz]) —
    // `null`/`SESSION` (every existing caller) is `SessionsContent`'s own existing `Detail` default,
    // unchanged; `DIGEST` lands on the same `Digest` screen that session's own `Digest` button
    // opens (`SessionsContent.openDigest`, WP10's own small addition to accept this seed — confirmed
    // by reading `ui/digest/SessionsContent.kt` before wiring this).
    val reviewSessionView: ReviewSessionView? = null,
) {
    /**
     * The [ReaderDestination] this seed's own state is actually read under. The four drill-in ids
     * render regardless of `current` ([NavHostBody]'s own dispatch checks them before it ever
     * looks at `current` at all) — this exists for the *other* fields, each read only while its
     * own destination is current (`pendingLogFilter`/`Log`, `openCaptureLevelMeter`/`Capture`,
     * `pendingReviewSessionId`/`Earlier nights`, `settingsScreen`/`Settings`) — and, for the four
     * drill-ins too, so the drawer's own row highlight and a screenshot's overall state stay
     * coherent rather than leaving `current` at whatever it would otherwise default to. `null`
     * when nothing in this seed implies a destination at all (every field `null`/`false`).
     */
    internal fun initialDestination(): ReaderDestination? = when {
        openTransmissionId != null -> openedFromDestination()
        openStationId != null -> openedFromDestination()
        openFrequencyHz != null -> openedFromDestination()
        openThreadId != null -> openedFromDestination()
        pendingLogFilter != null -> ReaderDestination.LOG
        logSheetOpen == true -> ReaderDestination.LOG
        openCaptureLevelMeter == true -> ReaderDestination.CAPTURE
        pendingReviewSessionId != null -> ReaderDestination.EARLIER_NIGHTS
        settingsScreen != null -> ReaderDestination.SETTINGS
        searchQuery != null || searchSubmit == true || searchFiltersOpen == true -> ReaderDestination.SEARCH
        else -> null
    }

    /**
     * R-017's "Back to <origin>" header needs a real origin even for a drill-in reached by seed,
     * never a real tap — the same destination each drill-in's own real list row lives on
     * ([StationsContent]/[FrequenciesContent]/[ThreadContent] each open their own drill-in
     * directly; `Log`'s own rows are this row's own convention for the transmission detail,
     * matching R-333's own smoke case). `null` when no drill-in id is set.
     */
    internal fun openedFromDestination(): ReaderDestination? = when {
        openTransmissionId != null -> ReaderDestination.LOG
        openStationId != null -> ReaderDestination.STATIONS
        openFrequencyHz != null -> ReaderDestination.FREQUENCIES
        openThreadId != null -> ReaderDestination.THREADS
        else -> null
    }

    /** Writes this seed's own non-null fields onto [intent] as the same extras [fromIntent]
     * reads back — [org.ort.app.debug.ScenarioReaderActivity]'s own forwarding half. Split into
     * [putExtras]/[putRemainingExtras] purely to keep this under detekt's `CyclomaticComplexMethod`
     * limit (R-840's own new [reviewSessionView] field is what pushed the single-function shape
     * over it) — no change to what either half actually writes. */
    public fun putExtras(intent: Intent) {
        openTransmissionId?.let { intent.putExtra(EXTRA_OPEN_TRANSMISSION_ID, it) }
        openStationId?.let { intent.putExtra(EXTRA_OPEN_STATION_ID, it) }
        openFrequencyHz?.let { intent.putExtra(EXTRA_OPEN_FREQUENCY_HZ, it) }
        openThreadId?.let { intent.putExtra(EXTRA_OPEN_THREAD_ID, it) }
        pendingLogFilter?.frequencyHz?.let { intent.putExtra(EXTRA_LOG_FILTER_FREQUENCY_HZ, it) }
        pendingLogFilter?.fromMillis?.let { intent.putExtra(EXTRA_LOG_FILTER_FROM_MILLIS, it) }
        pendingLogFilter?.toMillis?.let { intent.putExtra(EXTRA_LOG_FILTER_TO_MILLIS, it) }
        openCaptureLevelMeter?.let { intent.putExtra(EXTRA_OPEN_CAPTURE_LEVEL_METER, it) }
        pendingReviewSessionId?.let { intent.putExtra(EXTRA_PENDING_REVIEW_SESSION_ID, it) }
        frequencyInitialView?.let { intent.putExtra(EXTRA_FREQUENCY_INITIAL_VIEW, it.name) }
        putRemainingExtras(intent)
    }

    private fun putRemainingExtras(intent: Intent) {
        openStationSubScreen?.let { intent.putExtra(EXTRA_OPEN_STATION_SUB_SCREEN, it.name) }
        settingsScreen?.let { intent.putExtra(EXTRA_SETTINGS_SCREEN, it.name) }
        searchQuery?.let { intent.putExtra(EXTRA_SEARCH_QUERY, it) }
        searchSubmit?.let { intent.putExtra(EXTRA_SEARCH_SUBMIT, it) }
        searchFiltersOpen?.let { intent.putExtra(EXTRA_SEARCH_FILTERS_OPEN, it) }
        logSheetOpen?.let { intent.putExtra(EXTRA_LOG_SHEET_OPEN, it) }
        openTransmissionRevisions?.let { intent.putExtra(EXTRA_OPEN_TRANSMISSION_REVISIONS, it) }
        openDrawer?.let { intent.putExtra(EXTRA_OPEN_DRAWER, it) }
        reviewSessionView?.let { intent.putExtra(EXTRA_REVIEW_SESSION_VIEW, it.name) }
    }

    public companion object {
        // Register R-133 addendum, round 13: one named extra per field, following the exact
        // pattern `ReaderActivity.EXTRA_SESSION_ID`/`EXTRA_DESTINATION`/`EXTRA_SETTINGS_SCREEN`
        // already established — `adb shell am start -n org.ort.app/.debug.ScenarioReaderActivity
        // --es nav_open_transmission_id <id>` opens that transmission's own detail directly, for
        // example. `pendingLogFilter` is only its own `frequencyHz`/`fromMillis`/`toMillis` here —
        // the real, only shape `NavHostNavState.openLogFilteredByFrequency` (R-276) itself ever
        // constructs; `attributionStates`/`showRejected`/`showGaps` stay at `LogFilterSelection`'s
        // own defaults, not worth a seventh/eighth/ninth extra for a seam this narrow.
        public const val EXTRA_OPEN_TRANSMISSION_ID: String = "nav_open_transmission_id"
        public const val EXTRA_OPEN_STATION_ID: String = "nav_open_station_id"
        public const val EXTRA_OPEN_FREQUENCY_HZ: String = "nav_open_frequency_hz"
        public const val EXTRA_OPEN_THREAD_ID: String = "nav_open_thread_id"
        public const val EXTRA_LOG_FILTER_FREQUENCY_HZ: String = "nav_log_filter_frequency_hz"
        public const val EXTRA_LOG_FILTER_FROM_MILLIS: String = "nav_log_filter_from_millis"
        public const val EXTRA_LOG_FILTER_TO_MILLIS: String = "nav_log_filter_to_millis"
        public const val EXTRA_OPEN_CAPTURE_LEVEL_METER: String = "nav_open_capture_level_meter"
        public const val EXTRA_PENDING_REVIEW_SESSION_ID: String = "nav_pending_review_session_id"
        public const val EXTRA_FREQUENCY_INITIAL_VIEW: String = "nav_frequency_initial_view"
        public const val EXTRA_OPEN_STATION_SUB_SCREEN: String = "nav_open_station_sub_screen"

        // Deliberately the same key `ReaderActivity.EXTRA_SETTINGS_SCREEN` already uses (not a
        // new, second name for the same fact) — a caller seeding `Settings` reaches it exactly
        // the way `EXTRA_DESTINATION=SETTINGS` + this extra already does today.
        public const val EXTRA_SETTINGS_SCREEN: String = "settings_screen"

        // Round 14 — see [searchQuery]/[searchSubmit]/[searchFiltersOpen]'s own doc comment.
        public const val EXTRA_SEARCH_QUERY: String = "nav_search_query"
        public const val EXTRA_SEARCH_SUBMIT: String = "nav_search_submit"
        public const val EXTRA_SEARCH_FILTERS_OPEN: String = "nav_search_filters_open"

        // Round 14 — see [logSheetOpen]'s own doc comment.
        public const val EXTRA_LOG_SHEET_OPEN: String = "nav_log_sheet_open"

        // Round 14 — see [openTransmissionRevisions]'s own doc comment.
        public const val EXTRA_OPEN_TRANSMISSION_REVISIONS: String = "nav_open_transmission_revisions"

        // WP12 — see [openDrawer]'s own doc comment.
        public const val EXTRA_OPEN_DRAWER: String = "nav_open_drawer"

        // R-840 — see [ReviewSessionView]/[NavSeed.reviewSessionView]'s own doc comments. Named
        // extra value is the enum's own name, `SESSION` or `DIGEST` — `adb shell am start -n
        // org.ort.app/.debug.ScenarioReaderActivity --es nav_pending_review_session_id <id> --es
        // nav_review_session_view DIGEST` lands directly on that session's Digest.
        public const val EXTRA_REVIEW_SESSION_VIEW: String = "nav_review_session_view"

        /**
         * Parses [intent]'s own seed extras (any subset, including none) into a [NavSeed] — `null`
         * only when *no* seed extra at all is present, so a caller that never seeded anything gets
         * exactly today's unseeded `null` behaviour, not an all-defaults [NavSeed] that would (via
         * [initialDestination]) still be non-null-but-inert. An unparsable/absent field on an
         * otherwise-present seed falls back to that field's own default, the same "honest until
         * wired" treatment `ReaderActivity.resolveInitialDestination` already gives a bad
         * `EXTRA_DESTINATION`.
         */
        public fun fromIntent(intent: Intent): NavSeed? {
            val frequencyHz = intent.getLongExtraOrNull(EXTRA_OPEN_FREQUENCY_HZ)
            val logFilterFrequencyHz = intent.getLongExtraOrNull(EXTRA_LOG_FILTER_FREQUENCY_HZ)
            val logFilterFromMillis = intent.getLongExtraOrNull(EXTRA_LOG_FILTER_FROM_MILLIS)
            val logFilterToMillis = intent.getLongExtraOrNull(EXTRA_LOG_FILTER_TO_MILLIS)
            val pendingLogFilter = if (logFilterFrequencyHz != null) {
                LogFilterSelection(
                    frequencyHz = logFilterFrequencyHz,
                    fromMillis = logFilterFromMillis,
                    toMillis = logFilterToMillis,
                )
            } else {
                null
            }
            val seed = NavSeed(
                openTransmissionId = intent.getStringExtra(EXTRA_OPEN_TRANSMISSION_ID),
                openStationId = intent.getStringExtra(EXTRA_OPEN_STATION_ID),
                openFrequencyHz = frequencyHz,
                openThreadId = intent.getStringExtra(EXTRA_OPEN_THREAD_ID),
                pendingLogFilter = pendingLogFilter,
                openCaptureLevelMeter = intent.getBooleanExtraOrNull(EXTRA_OPEN_CAPTURE_LEVEL_METER),
                pendingReviewSessionId = intent.getStringExtra(EXTRA_PENDING_REVIEW_SESSION_ID),
                frequencyInitialView = intent.getStringExtra(EXTRA_FREQUENCY_INITIAL_VIEW)
                    ?.let { name -> FrequencyDetailView.entries.firstOrNull { it.name == name } },
                openStationSubScreen = intent.getStringExtra(EXTRA_OPEN_STATION_SUB_SCREEN)
                    ?.let { name -> StationSubScreen.entries.firstOrNull { it.name == name } },
                settingsScreen = intent.getStringExtra(EXTRA_SETTINGS_SCREEN)
                    ?.let { name -> SettingsScreenId.entries.firstOrNull { it.name == name } },
                searchQuery = intent.getStringExtra(EXTRA_SEARCH_QUERY),
                searchSubmit = intent.getBooleanExtraOrNull(EXTRA_SEARCH_SUBMIT),
                searchFiltersOpen = intent.getBooleanExtraOrNull(EXTRA_SEARCH_FILTERS_OPEN),
                logSheetOpen = intent.getBooleanExtraOrNull(EXTRA_LOG_SHEET_OPEN),
                openTransmissionRevisions = intent.getBooleanExtraOrNull(EXTRA_OPEN_TRANSMISSION_REVISIONS),
                openDrawer = intent.getBooleanExtraOrNull(EXTRA_OPEN_DRAWER),
                reviewSessionView = intent.getStringExtra(EXTRA_REVIEW_SESSION_VIEW)
                    ?.let { name -> ReviewSessionView.entries.firstOrNull { it.name == name } },
            )
            return if (seed == NavSeed()) null else seed
        }

        private fun Intent.getLongExtraOrNull(key: String): Long? = if (hasExtra(key)) getLongExtra(key, 0L) else null

        private fun Intent.getBooleanExtraOrNull(key: String): Boolean? =
            if (hasExtra(key)) getBooleanExtra(key, false) else null
    }
}
