package org.ort.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ort.app.ui.audio.RealTransmissionAudioPlayer
import org.ort.app.ui.components.LiveBar
import org.ort.app.ui.components.LiveBarViewState
import org.ort.app.ui.components.ScreenHeader
import org.ort.app.ui.data.DrawerCounts
import org.ort.app.ui.data.DrawerCountsViewState
import org.ort.app.ui.data.LiveBarPolling
import org.ort.app.ui.data.ReaderPolling
import org.ort.app.ui.data.SearchFacetFilter
import org.ort.app.ui.data.SearchFilterInput
import org.ort.app.ui.data.SearchFilterParser
import org.ort.app.ui.data.SearchPolling
import org.ort.app.ui.data.SearchResult
import org.ort.app.ui.data.SearchTimeFilter
import org.ort.app.ui.data.ThreadDetailViewState
import org.ort.app.ui.data.ThreadPolling
import org.ort.app.ui.screens.CaptureStatusContent
import org.ort.app.ui.screens.FrequenciesContent
import org.ort.app.ui.screens.FrequencyDetailContent
import org.ort.app.ui.screens.LogContent
import org.ort.app.ui.screens.NowContent
import org.ort.app.ui.screens.SearchContent
import org.ort.app.ui.screens.StationDetailContent
import org.ort.app.ui.screens.StationsContent
import org.ort.app.ui.screens.ThreadContent
import org.ort.app.ui.screens.ThreadDetailScreen
import org.ort.app.ui.screens.TransmissionDetailContent
import org.ort.app.ui.settings.SettingsScreenId
import org.ort.app.ui.theme.OrtSpacing
import org.ort.core.AttributionState
import org.ort.core.SystemClock
import org.ort.data.Band
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.RigStatus

private const val POLL_INTERVAL_MILLIS = 2_000L

/** The delimiter [SearchFilterInputSaver] joins fields on - a control character no field's
 * own free text is expected to contain. */
private const val SEARCH_SAVER_DELIMITER = ""

/**
 * A [Saver] for [SearchFilterInput] built entirely from this package (no edit to WP7's
 * `ui/data/SearchViewData.kt`) — every field is already a Bundle-primitive (`String`, `Boolean`) or
 * a plain enum/enum-set encoded by name, joined into one delimited `String` (itself trivially
 * `Bundle`-saveable, and it sidesteps `listSaver`'s single-element-type constraint a genuinely
 * heterogeneous row like this one cannot satisfy). [SearchResult] gets no equivalent Saver — it
 * nests `TransmissionDetail`, which itself carries `Attribution` (a sealed core type) and
 * `InspectionViewState` (lattice slots, candidates, prior contributions) — hand-rolling a faithful
 * round trip for that graph is out of proportion to this package's row, so [SearchResult] stays a
 * plain `remember` (see `OrtNavHost`'s own doc comment on what that still fixes and what it does
 * not).
 */
private val SearchFilterInputSaver: Saver<SearchFilterInput, String> = Saver(
    save = { input ->
        listOf(
            input.text,
            input.callsign,
            input.frequencyMhz,
            input.band?.name.orEmpty(),
            input.timeFilter.name,
            input.rangeFromLocal,
            input.rangeToLocal,
            input.attributionStates.joinToString(",") { it.name },
            input.includeRejected.toString(),
            input.includeCorrected.toString(),
        ).joinToString(SEARCH_SAVER_DELIMITER)
    },
    restore = { saved ->
        val parts = saved.split(SEARCH_SAVER_DELIMITER)
        SearchFilterInput(
            text = parts[0],
            callsign = parts[1],
            frequencyMhz = parts[2],
            band = parts[3].takeIf { it.isNotEmpty() }?.let { Band.valueOf(it) },
            timeFilter = SearchTimeFilter.valueOf(parts[4]),
            rangeFromLocal = parts[5],
            rangeToLocal = parts[6],
            attributionStates = parts[7].split(",")
                .filter { it.isNotEmpty() }
                .map { AttributionState.valueOf(it) }
                .toSet(),
            includeRejected = parts[8].toBoolean(),
            includeCorrected = parts[9].toBoolean(),
        )
    },
)

/**
 * The navigation host (build-plan P13, extended by P14, P17 and ui-conformance-plan WP3): the
 * drawer `Menu.dc.html` specifies, wrapping whichever destination is current, under WP2's
 * [ScreenHeader] (R-003/R-004/R-015 — drawer icon, live dot + elapsed, search icon; no title text,
 * each screen owns its own). A drill-in draws its own header instead (R-016) — every real
 * drill-in content composable WP5/6/8 shipped already renders WP2's `DrillInHeader` internally,
 * so this host does not add a second one (see [NavHostBody]'s own comment). WP2's [LiveBar] is
 * pinned to the bottom of every destination and drill-in while a session runs (R-022's consumer),
 * except `Now`/`Capture`, which pin their own the same way. Every real destination and drill-in
 * dispatches to the package that owns it (`Now`/`Capture`/live-bar feed → WP4; `Search` → WP7;
 * `Log`/`Threads`/transmission and thread drill-ins → WP5/WP6; `Stations`/`Frequencies` and their
 * drill-ins → WP8; `Settings`/`Earlier nights`/`Improve records` → WP10).
 *
 * [sessionId] is null when the host is opened with no active or prior capture session (for
 * example, opened directly rather than from the status flow) - `Now`/`Log` then show their
 * empty/idle state rather than polling a session that does not exist.
 *
 * [contentTopPadding] (ui-conformance-plan register R-178, WP11b's follow-up — WP3 idle this
 * round, one line touched here): [org.ort.app.ui.failures.FailureHost] mounts above this host and
 * reports the currently-showing banner's own real, measured height through it (`0.dp` while none
 * shows), so a banner that grows taller — font scale 2.0 wraps its copy onto more lines — pushes
 * the destination content down to clear itself, rather than just covering more of it. Applied only
 * to the destination/drill-in content ([NavHostBody]'s inner `Box`), never to [ScreenHeader] or
 * [LiveBar] — both stay exactly where they already were.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun OrtNavHost(
    sessionId: String?,
    navigator: ReaderNavigator = rememberReaderNavigator(),
    contentTopPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Hoisted into `navigator` (round 3) so `ReaderActivity`'s `FailureHostActions`, mounted above
    // this composable, can also switch destinations — see `ReaderNavigator.kt`'s own doc comment.
    var current by navigator.currentState
    val navState = rememberNavHostNavState()
    val drawerLive = rememberDrawerLiveState(sessionId, context)
    val audioPlayer = remember { RealTransmissionAudioPlayer(context) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ReaderDrawerContent(
                current = current,
                sessionHeader = drawerLive.sessionHeader,
                storage = drawerLive.storage,
                badges = drawerLive.badges,
                counts = drawerLive.counts,
                improveRecordsCount = drawerLive.improveRecordsCount,
                onSelect = { destination ->
                    current = destination
                    navState.closeDrillIns()
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Scaffold { padding ->
            NavHostBody(
                layout = NavHostLayout(
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentTopPadding = contentTopPadding,
                ),
                ids = NavHostIds(
                    current,
                    navState.openedFrom.value,
                    navState.openTransmissionId.value,
                    navState.openStationId.value,
                    navState.openFrequencyHz.value,
                    navState.openThreadId.value,
                    navigator.settingsScreenState.value,
                    navState.openCaptureLevelMeter.value,
                ),
                callbacks = navHostCallbacks(navigator, scope, drawerState, navState),
                sessionId = sessionId,
                context = context,
                drawerLive = drawerLive,
                audioPlayer = audioPlayer,
                search = searchHostState(navigator, context, scope, navState),
            )
        }
    }
}

/**
 * Every piece of navigation state [OrtNavHost] owns beyond `navigator`'s own `current`/
 * `settingsScreenState` — drill-in ids, each one's `openedFrom`/`searchOpenedFrom` origin, and
 * WP7's lifted `Search` input/result (see that field's own doc comment on [OrtNavHost] for why it
 * lives here and not inside `SearchContent`). Bundled into one [MutableState]-holding object,
 * rather than left as separate `var ... by remember` locals in [OrtNavHost] itself, purely so
 * [navHostCallbacks] and [searchHostState] can be pulled out to their own top-level functions —
 * the same reason [DrawerLiveState] and [NavHostIds]/[NavHostCallbacks] already exist — and so
 * keep [OrtNavHost] itself under detekt's `LongMethod` limit.
 */
private data class NavHostNavState(
    val openedFrom: MutableState<ReaderDestination>,
    val searchOpenedFrom: MutableState<ReaderDestination>,
    val openTransmissionId: MutableState<String?>,
    val openStationId: MutableState<String?>,
    val openFrequencyHz: MutableState<Long?>,
    val openThreadId: MutableState<String?>,
    val searchInput: MutableState<SearchFilterInput>,
    val searchResult: MutableState<SearchResult?>,
    // Round 6, register R-132: whether `Capture` should land directly on `LevelMeterScreen` the
    // next time it is freshly composed — see `CaptureStatusContent.openLevelMeter`'s own doc
    // comment for the "opens there on launch" contract this mirrors.
    val openCaptureLevelMeter: MutableState<Boolean>,
) {
    fun closeDrillIns() {
        openTransmissionId.value = null
        openStationId.value = null
        openFrequencyHz.value = null
        openThreadId.value = null
        // Reset here too, not only where it is set true: this runs on every ordinary way of
        // reaching `Capture` (the drawer row, the live bar's own tap target) and must not leave a
        // stale `true` from an earlier `Settings-Capture` "Meter" tap open the meter again.
        openCaptureLevelMeter.value = false
    }

    /** R-017: records which destination a drill-in opened from before running [setter], so a
     * drill-in header can say "Back to <that destination>" instead of an implicit invariant. */
    fun onOpenDrillIn(current: ReaderDestination, setter: () -> Unit) {
        openedFrom.value = current
        setter()
    }
}

@Composable
private fun rememberNavHostNavState(): NavHostNavState {
    // R-017: the destination a drill-in was opened from, so back returns there — set only when a
    // drill-in opens, read only while one is showing.
    val openedFrom = rememberSaveable { mutableStateOf(ReaderDestination.NOW) }
    // R-200 (round 5): the destination `Search` was opened from, so `SearchContent`'s own
    // back-chevron (`SearchScreen.kt`'s `search-back-chevron`) returns there instead of stranding
    // the operator with no way back except the drawer, now that the host no longer draws
    // `ScreenHeader` for `SEARCH` (see `NavHostBody`). Kept separate from `openedFrom` because
    // `Search` is not a drill-in (it has its own drawer row) and reusing `openedFrom` here would
    // wrongly imply a drill-in opened under `Search` should say "Back to <wherever Search itself
    // came from>" rather than "Back to Search".
    val searchOpenedFrom = rememberSaveable { mutableStateOf(ReaderDestination.NOW) }
    val openTransmissionId = rememberSaveable { mutableStateOf<String?>(null) }
    val openStationId = rememberSaveable { mutableStateOf<String?>(null) }
    val openFrequencyHz = rememberSaveable { mutableStateOf<Long?>(null) }
    // R-017: WP5's `ThreadDetailScreen` (its own file's doc comment: "not yet reachable ... ready
    // for whichever package wires that route") — this is that route, ready to open once WP5/WP7
    // expose a callback into it (see this package's report on why nothing does yet).
    val openThreadId = rememberSaveable { mutableStateOf<String?>(null) }
    // R-017 / `Flow-Search.dc.html`: Search's input and results live here, in the host, not inside
    // WP7's `SearchContent` — that composable is skipped entirely while a drill-in is showing (see
    // `NavHostBody`), and a skipped composable's own `remember` state does not survive being
    // skipped; lifting it here is what makes "back from a detail opened from Search returns to
    // Search with its filters intact" true rather than aspirational. `searchInput` is
    // `rememberSaveable` via [SearchFilterInputSaver] (built in this file, no edit to WP7's own
    // types); `searchResult` stays plain `remember` — see [SearchFilterInputSaver]'s own doc
    // comment for why `SearchResult` gets no equivalent Saver.
    val searchInput = rememberSaveable(stateSaver = SearchFilterInputSaver) { mutableStateOf(SearchFilterInput()) }
    val searchResult = remember { mutableStateOf<SearchResult?>(null) }
    val openCaptureLevelMeter = rememberSaveable { mutableStateOf(false) }
    return NavHostNavState(
        openedFrom,
        searchOpenedFrom,
        openTransmissionId,
        openStationId,
        openFrequencyHz,
        openThreadId,
        searchInput,
        searchResult,
        openCaptureLevelMeter,
    )
}

/** Builds [NavHostBody]'s [NavHostCallbacks] — split out of [OrtNavHost] purely to keep that
 * function under detekt's `LongMethod` limit, the same reason [NavHostBody]/[DestinationContent]
 * were themselves split out before this round. */
private fun navHostCallbacks(
    navigator: ReaderNavigator,
    scope: CoroutineScope,
    drawerState: DrawerState,
    navState: NavHostNavState,
): NavHostCallbacks {
    val currentState = navigator.currentState
    return NavHostCallbacks(
        onOpenDrawer = { scope.launch { drawerState.open() } },
        onSearchDestination = {
            navState.searchOpenedFrom.value = currentState.value
            currentState.value = ReaderDestination.SEARCH
        },
        onCloseDrillIns = navState::closeDrillIns,
        onOpenCapture = {
            currentState.value = ReaderDestination.CAPTURE
            navState.closeDrillIns()
        },
        onOpenTransmission = { id ->
            navState.onOpenDrillIn(currentState.value) { navState.openTransmissionId.value = id }
        },
        onOpenStation = { id ->
            navState.onOpenDrillIn(currentState.value) { navState.openStationId.value = id }
        },
        onOpenFrequency = { hz ->
            navState.onOpenDrillIn(currentState.value) { navState.openFrequencyHz.value = hz }
        },
        onOpenThread = { id ->
            navState.onOpenDrillIn(currentState.value) { navState.openThreadId.value = id }
        },
        onOpenStations = { currentState.value = ReaderDestination.STATIONS },
        // R-139 (round 5): `SettingsContent.initialScreen` is real now (WP10 merged it —
        // confirmed by reading `ui/settings/SettingsContent.kt` before wiring this), so `Now`'s
        // "Install a model" lands directly on `Assets` instead of the root the operator used to
        // have to tap through themselves.
        onOpenModels = { navigator.openSettings(SettingsScreenId.ASSETS) },
        // Round 6, register R-132: `SettingsContent`'s own `onOpenLevelMeter` (its doc comment:
        // "the host is expected to wire it the same way it wires every other cross-package
        // drill-in") — `Settings-Capture`'s `Meter` action switches to `Capture` and asks
        // `CaptureStatusContent` to land directly on `LevelMeterScreen`.
        onOpenLevelMeter = {
            currentState.value = ReaderDestination.CAPTURE
            navState.openCaptureLevelMeter.value = true
        },
    )
}

/** Builds [DestinationContent]'s [SearchHostState] — same reason as [navHostCallbacks]. */
private fun searchHostState(
    navigator: ReaderNavigator,
    context: android.content.Context,
    scope: CoroutineScope,
    navState: NavHostNavState,
): SearchHostState = SearchHostState(
    input = navState.searchInput.value,
    onInputChange = { navState.searchInput.value = it },
    result = navState.searchResult.value,
    onSearch = {
        scope.launch {
            val params = SearchFilterParser.parse(navState.searchInput.value, SystemClock.wallMillis())
            val facetFilter = SearchFacetFilter.from(navState.searchInput.value)
            navState.searchResult.value = SearchPolling.search(context, params, facetFilter)
        }
    },
    // R-200: `SearchContent`'s own back-chevron returns to wherever `Search` was opened from —
    // see `NavHostNavState.searchOpenedFrom`'s own doc comment.
    onBack = { navigator.currentState.value = navState.searchOpenedFrom.value },
)

/** [NavHostBody]'s own `modifier` (from [OrtNavHost]'s `Scaffold` inner padding) and, register
 * R-178, the banner-height top padding [org.ort.app.ui.failures.FailureHost] reports — bundled,
 * same reason as [NavHostIds]/[NavHostCallbacks], so [NavHostBody] stays under detekt's
 * `LongParameterList` rather than growing a parameter for the R-178 addition. */
private data class NavHostLayout(val modifier: Modifier, val contentTopPadding: Dp = 0.dp)

/** [NavHostBody]'s destination/drill-in identity, bundled to keep that composable's own parameter count down. */
private data class NavHostIds(
    val current: ReaderDestination,
    val openedFrom: ReaderDestination,
    val transmissionId: String?,
    val stationId: String?,
    val frequencyHz: Long?,
    val threadId: String?,
    // Round 5: the sub-screen `SettingsContent` should land on the next time it is freshly
    // composed — see `settingsInitialScreen`'s own doc comment in `OrtNavHost`.
    val settingsInitialScreen: SettingsScreenId?,
    // Round 6, register R-132: whether `Capture` should land on `LevelMeterScreen` the next time
    // it is freshly composed — see `NavHostNavState.openCaptureLevelMeter`'s own doc comment.
    val openCaptureLevelMeter: Boolean,
)

/** [NavHostBody]'s navigation actions, bundled for the same reason as [NavHostIds]. */
private data class NavHostCallbacks(
    val onOpenDrawer: () -> Unit,
    val onSearchDestination: () -> Unit,
    val onCloseDrillIns: () -> Unit,
    val onOpenCapture: () -> Unit,
    val onOpenTransmission: (String) -> Unit,
    val onOpenStation: (String) -> Unit,
    val onOpenFrequency: (Long) -> Unit,
    val onOpenThread: (String) -> Unit,
    val onOpenStations: () -> Unit,
    val onOpenModels: () -> Unit,
    val onOpenLevelMeter: () -> Unit,
)

/**
 * R-017: WP7's [SearchContent]'s `input`/`result`, owned by [OrtNavHost] — see that function's doc
 * comment. [onSearch] is the host's own trigger (WP7's `SearchContent` takes `onSearch: () ->
 * Unit`, not a result setter — the host runs the query and writes [result] itself). [onBack]
 * (round 5, R-200) is the second half of that same file's own doc comment on its `onBack`
 * parameter — see `searchOpenedFrom` in `OrtNavHost`.
 */
private data class SearchHostState(
    val input: SearchFilterInput,
    val onInputChange: (SearchFilterInput) -> Unit,
    val result: SearchResult?,
    val onSearch: () -> Unit,
    val onBack: () -> Unit,
)

/**
 * The header (R-003/R-004/R-015/R-016), the current destination or drill-in's content, and the
 * live bar (R-022), stacked in one column filling [OrtNavHost]'s `Scaffold`. Extracted out of
 * [OrtNavHost] purely to keep that function under detekt's length limit — the same reason
 * [DestinationContent] was already extracted before this prompt.
 */
@Composable
private fun NavHostBody(
    layout: NavHostLayout,
    ids: NavHostIds,
    callbacks: NavHostCallbacks,
    sessionId: String?,
    context: android.content.Context,
    drawerLive: DrawerLiveState,
    audioPlayer: org.ort.app.ui.audio.TransmissionAudioPlayer,
    search: SearchHostState,
) {
    // Register R-262 (accessibility validator): the same real-measured-height mechanism
    // [org.ort.app.ui.failures.FailureHost] built for the banner's `contentTopPadding` (that
    // file's own R-178 doc comment), mirrored for [LiveBar]'s own pinned bottom placement — without
    // this, a destination's own scrollable content (`ModelsContent`/`Settings-Assets`, where this
    // was found) sizes itself to the full content column height and the live bar then pins on top
    // of its last row rather than the column making room for it. `0.dp` (density-converted from
    // the measured px) whenever the live bar is not currently shown — mirrors `FailurePresentation
    // .None -> onBannerHeightChanged(0.dp)` in that same file.
    var liveBarHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    Column(modifier = layout.modifier) {
        val drillInIds = listOf(ids.transmissionId, ids.stationId, ids.frequencyHz, ids.threadId)
        val isDrillIn = drillInIds.any { it != null }
        // ui-conformance WP3 round 4 (R-129 smoke coverage found this, real at the time): this host
        // used to skip its own header for `SETTINGS` because `SettingsRootScreen` drew a second,
        // duplicate one — the same double-header defect R-016 already fixed once for the four real
        // drill-ins. Superseded once already (round 4→5: R-130 had `SettingsRootScreen` draw no
        // header of its own, so this host went back to always rendering one for the whole `SETTINGS`
        // destination) and **superseded again this round (round 7)**: once `initialScreen` (round 5)
        // let a caller land directly on a sub-screen, the host's one destination-wide header and that
        // sub-screen's own `DrillInHeader` rendered stacked — the double-header bug one level down,
        // round 6's own smoke test coverage found it. `SettingsRootScreen.kt` now draws its own
        // `ScreenHeader` again (`SettingsContent`'s own doc comment states this outright, including
        // the instruction that this host's copy "must be removed to match"), so `SETTINGS` gets the
        // same treatment as `SEARCH` below — this host renders neither a header nor a
        // `DrillInHeader` for the whole destination, root or sub-screen alike; `onSearch` is wired
        // through to `SettingsContent` the same way `ScreenHeader.onSearch` always was.
        //
        // Round 5 (register R-200): `SEARCH` gets the identical treatment, for a different reason
        // than `SETTINGS`'s own — `Search.dc.html`'s header is a back chevron plus the inline query
        // field, not the generic drawer/search bar every other destination gets, and `SearchContent`
        // (WP7's file, confirmed by reading its own doc comment before this) now draws exactly that
        // itself. Unlike `SETTINGS`'s pre-round-7 root, `SEARCH` never had a state where nothing drew
        // a header of its own — it always does — so there was never an analogous "zero-header"
        // failure mode to guard there.
        val isSearch = ids.current == ReaderDestination.SEARCH
        val isSettings = ids.current == ReaderDestination.SETTINGS
        if (!isDrillIn && !isSearch && !isSettings) {
            // R-003/R-004/R-015: drawer icon, live dot + elapsed while a session is capturing,
            // search icon — no title, the destination content below draws its own (`Main.dc.html`'s
            // 27sp title is `NowScreen`'s, not the header's).
            ScreenHeader(
                onDrawer = callbacks.onOpenDrawer,
                liveElapsedLabel = drawerLive.badges.captureElapsedLabel,
                onSearch = callbacks.onSearchDestination,
            )
        }
        // R-016: no generic host-level `DrillInHeader` when a drill-in is open — every real
        // drill-in content composable WP5/6/8 shipped (`TransmissionDetailContent`,
        // `StationDetailContent`→`StationDetailScreen`, `FrequencyDetailContent`→
        // `FrequencyDetailScreen`, `ThreadDetailScreen`) already draws its own `DrillInHeader`
        // internally (confirmed by reading all four before writing this) — rendering a second one
        // here would stack two, the same double-render this package's WP4 live-bar reconciliation
        // already found and fixed once. Round 3: all four now accept a real `backLabel` — this host
        // passes `ids.openedFrom.label`, so the header names the true origin (R-017), e.g. "Back to
        // Search" for a transmission opened from a search result.

        Box(modifier = Modifier.weight(1f).padding(top = layout.contentTopPadding, bottom = liveBarHeight)) {
            when {
                ids.transmissionId != null -> TransmissionDetailContent(
                    context = context,
                    transmissionId = ids.transmissionId,
                    player = audioPlayer,
                    onBack = callbacks.onCloseDrillIns,
                    onOpenTransmission = callbacks.onOpenTransmission,
                    backLabel = ids.openedFrom.label,
                )

                ids.stationId != null -> StationDetailContent(
                    context = context,
                    stationId = ids.stationId,
                    onBack = callbacks.onCloseDrillIns,
                    onOpenTransmission = callbacks.onOpenTransmission,
                    backLabel = ids.openedFrom.label,
                )

                ids.frequencyHz != null -> FrequencyDetailContent(
                    context = context,
                    frequencyHz = ids.frequencyHz,
                    onBack = callbacks.onCloseDrillIns,
                    onOpenStation = callbacks.onOpenStation,
                    backLabel = ids.openedFrom.label,
                )

                ids.threadId != null -> ThreadDetailContent(
                    context = context,
                    sessionId = sessionId,
                    threadId = ids.threadId,
                    onBack = callbacks.onCloseDrillIns,
                    onOpenOver = callbacks.onOpenTransmission,
                    backLabel = ids.openedFrom.label,
                )

                else -> DestinationContent(
                    current = ids.current,
                    settingsInitialScreen = ids.settingsInitialScreen,
                    openCaptureLevelMeter = ids.openCaptureLevelMeter,
                    sessionId = sessionId,
                    context = context,
                    search = search,
                    callbacks = callbacks,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // R-022: pinned to the bottom of every destination and drill-in alike, while a session
        // runs — `null` (nothing pinned) covers both "no session" and "session idle", never a bar
        // with nothing real to show. Now and Capture are the two destinations that already pin
        // their own (`NowScreen`/`CaptureStatusScreen`, WP4's — confirmed by reading both before
        // assuming otherwise); rendering the host's bar there too would stack two.
        val embedsOwnLiveBar = !isDrillIn &&
            (ids.current == ReaderDestination.NOW || ids.current == ReaderDestination.CAPTURE)
        val shownLiveBar = if (!embedsOwnLiveBar) drawerLive.liveBar else null
        if (shownLiveBar != null) {
            // Wrapped, not passed as `LiveBar`'s own `modifier` (that param reaches only its
            // inner clickable `Row`, not the 1dp top-edge strip above it — `ui/components/
            // LiveBar.kt` is outside this row to edit for a more precise seam) — measures this
            // bar's whole real footprint as pinned in this `Column`. `testTag` (register R-262):
            // a stable node for a test to read this wrapper's own `boundsInRoot`, independent of
            // `LiveBar`'s own runtime-varying label text.
            Box(
                modifier = Modifier
                    .testTag("live-bar-clearance")
                    .onGloballyPositioned { coordinates ->
                        liveBarHeight = with(density) { coordinates.size.height.toDp() }
                    },
            ) {
                LiveBar(state = shownLiveBar, onClick = callbacks.onOpenCapture)
            }
        } else {
            liveBarHeight = 0.dp
        }
    }
}

/** Everything [rememberDrawerLiveState] polls, bundled so it returns one value. */
private data class DrawerLiveState(
    val storage: StorageFooterViewState,
    val badges: DrawerBadgeViewState,
    val counts: DrawerCountsViewState,
    val sessionHeader: DrawerSessionHeaderViewState,
    val liveBar: LiveBarViewState?,
    val improveRecordsCount: Int?,
)

/**
 * FR-STO-5 / FR-UI-7 (audit F-020, extended by ui-conformance-plan WP3's R-010/R-022): the footer,
 * the drawer's Log/Threads/Stations/Frequencies/Capture badges, the session/rig header and the
 * live bar's fallback state, polled the same way `NowContent`/`LogContent` already poll status —
 * real figures recomputed on the same cadence, never a value fixed at the moment the drawer first
 * composed. Extracted out of [OrtNavHost] purely to keep that function under detekt's length
 * limit; there is no other reason this couldn't be inline.
 */
@Composable
private fun rememberDrawerLiveState(sessionId: String?, context: android.content.Context): DrawerLiveState {
    var storage by remember { mutableStateOf(StorageFooterViewState.fromAudioDirectory(context)) }
    var badges by remember { mutableStateOf(DrawerBadgeViewState.NONE) }
    var counts by remember { mutableStateOf(DrawerCountsViewState.ZERO) }
    var sessionHeader by remember {
        mutableStateOf(DrawerSessionHeaderViewState.from(sessionLabel = null, rigState = RigStatus.state))
    }
    var liveBar by remember { mutableStateOf<LiveBarViewState?>(null) }
    var improveRecordsCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(sessionId) {
        while (true) {
            storage = StorageFooterViewState.fromAudioDirectory(context)
            badges = ReaderPolling.drawerBadges(context, sessionId)
            counts = DrawerCounts.current(context)
            // R-091/guide §6.4's count-pill, now that WP10's real read path is on this branch.
            improveRecordsCount = org.ort.app.ui.improve.ImproveCounts.canGetBetterCount(context)
            // No prompt has ever given `SessionEntity` a label field (see
            // `DrawerSessionHeaderViewState`'s own doc comment) — `sessionLabel` stays `null`
            // until one exists, rather than this fabricating one.
            sessionHeader = DrawerSessionHeaderViewState.from(sessionLabel = null, rigState = RigStatus.state)
            // R-022: WP4's real read path, now that it is on this branch. Gated the same way
            // `NowContent`/`CaptureStatusContent` (WP4's own callers) gate it — only while this
            // exact session is actually capturing, never a bar built for a session that has ended
            // or one that never started.
            liveBar = if (sessionId != null && CaptureState.isCapturing && CaptureState.sessionId == sessionId) {
                LiveBarPolling.current(context, sessionId)
            } else {
                null
            }
            delay(POLL_INTERVAL_MILLIS)
        }
    }
    return DrawerLiveState(storage, badges, counts, sessionHeader, liveBar, improveRecordsCount)
}

/**
 * Dispatches the current drawer destination to its screen.
 *
 * Extracted from [OrtNavHost] when P15's and P17's destinations merged: with both sessions' real
 * screens in one `when`, the host crossed detekt's method-length limit. Raising the limit would
 * have been the wrong fix — the dispatch is a genuinely separate concern from the drawer and
 * scaffold that surround it, and it is the part that grows with every future destination.
 */
@Composable
private fun DestinationContent(
    current: ReaderDestination,
    settingsInitialScreen: SettingsScreenId?,
    openCaptureLevelMeter: Boolean,
    sessionId: String?,
    context: android.content.Context,
    search: SearchHostState,
    callbacks: NavHostCallbacks,
    modifier: Modifier,
) {
    val content = modifier
    val onOpenTransmission = callbacks.onOpenTransmission
    val onOpenStation = callbacks.onOpenStation
    val onOpenFrequency = callbacks.onOpenFrequency
    val onOpenThread = callbacks.onOpenThread
    val onOpenDrawer = callbacks.onOpenDrawer
    when (current) {
        // Both dispatch to WP4/WP7's own real content composables now that they are on this
        // branch (confirmed by reading `ui/screens/NowContent.kt`/`ui/screens/SearchContent.kt`
        // before writing this) — this package's own inline copies are gone. Round 3: `NowContent`
        // gained `onOpenStations`/`onOpenModels` — see `NavHostCallbacks`'s own construction site.
        ReaderDestination.NOW -> NowContent(
            context = context,
            sessionId = sessionId,
            onOpenTransmission = onOpenTransmission,
            onOpenStation = onOpenStation,
            modifier = content,
            onOpenStations = callbacks.onOpenStations,
            onOpenModels = callbacks.onOpenModels,
        )

        // Round 3: `LogContent` gained a real `onOpenThread` — a QSO group header now opens the
        // real thread-detail drill-in (`Rows.dc.html`: "Tapping opens the thread").
        ReaderDestination.LOG -> LogContent(
            context = context,
            sessionId = sessionId,
            onOpen = onOpenTransmission,
            modifier = content,
            onOpenThread = onOpenThread,
        )

        ReaderDestination.SEARCH -> SearchContent(
            input = search.input,
            result = search.result,
            onInputChange = search.onInputChange,
            onSearch = search.onSearch,
            onOpen = onOpenTransmission,
            modifier = content,
            onBack = search.onBack,
        )

        // Round 3: `ThreadContent` gained a real `onOpenThread` too — a thread card now opens the
        // same drill-in.
        ReaderDestination.THREADS -> ThreadContent(
            context = context,
            sessionId = sessionId,
            onOpen = onOpenTransmission,
            modifier = content,
            onOpenThread = onOpenThread,
        )

        ReaderDestination.STATIONS ->
            StationsContent(context = context, onOpen = onOpenStation, modifier = content)

        ReaderDestination.FREQUENCIES ->
            FrequenciesContent(context = context, onOpen = onOpenFrequency, modifier = content)

        // Round 6, register R-132: `openLevelMeter` is real now — `Settings-Capture`'s `Meter`
        // action (via `NavHostCallbacks.onOpenLevelMeter`) lands here directly on the level meter.
        ReaderDestination.CAPTURE -> CaptureStatusContent(
            context = context,
            sessionId = sessionId,
            modifier = content,
            openLevelMeter = openCaptureLevelMeter,
        )

        // ui-conformance-plan WP10 (register R-090/R-091/R-092/R-107): all three dispatch to
        // WP10's own real content composables now that they are on this branch. `Earlier nights`
        // and `Improve records` no longer fall through to `PlaceholderScreen` — see
        // `ReaderDestination`'s own doc comment for what each one now is.
        // Round 5 (R-090/R-139/F6/F9): `initialScreen` is real now — `navigator.openSettings`
        // (`NavHostCallbacks.onOpenModels` above, and `ReaderActivity.kt`'s `FailureHostActions`)
        // writes `settingsInitialScreen`, read fresh here every time `SETTINGS` becomes `current`.
        // Round 7: `onSearch` is real now — `SettingsContent`'s own doc comment states the root
        // draws its own `ScreenHeader` again, with a real search icon this host must wire the same
        // way `ScreenHeader.onSearch` always was elsewhere (see `NavHostBody`'s own header-skip
        // comment for why this host no longer draws one of its own for `SETTINGS`).
        ReaderDestination.SETTINGS ->
            org.ort.app.ui.settings.SettingsContent(
                context = context,
                onDrawer = onOpenDrawer,
                modifier = content,
                initialScreen = settingsInitialScreen,
                // Round 6, register R-132: real now — see `NavHostCallbacks.onOpenLevelMeter`'s
                // own comment above.
                onOpenLevelMeter = callbacks.onOpenLevelMeter,
                onSearch = callbacks.onSearchDestination,
            )

        // Round 5 (R-092/R-107): `onOpenTransmission` is real now — WP10 merged it (confirmed by
        // reading `ui/digest/SessionsContent.kt` before wiring this). `onOpenDrillIn` (this file's
        // `OrtNavHost`, where `callbacks.onOpenTransmission` is built) records `openedFrom = current`
        // at the moment the tap fires, which is `EARLIER_NIGHTS` for every tap this dispatch can
        // ever produce — so the transmission drill-in's `backLabel` reads `ReaderDestination
        // .EARLIER_NIGHTS.label`, "Earlier nights", with no extra state needed here.
        ReaderDestination.EARLIER_NIGHTS ->
            org.ort.app.ui.digest.SessionsContent(
                context = context,
                onDrawer = onOpenDrawer,
                modifier = content,
                onOpenTransmission = onOpenTransmission,
            )

        ReaderDestination.IMPROVE_RECORDS ->
            org.ort.app.ui.improve.ImproveContent(context = context, onDrawer = onOpenDrawer, modifier = content)
    }
}

/**
 * R-017: a poll-and-render wrapper for WP5's [ThreadDetailScreen] — that screen is a pure function
 * of [ThreadDetailViewState] with no poller of its own (its own doc comment: "ready for whichever
 * package wires that route"), so this package supplies the same shape every other drill-in's
 * `*Content` composable has, feeding it from [ThreadPolling.threadDetail]. Round 3: now reachable
 * from `LogContent`'s group headers and `ThreadContent`'s own thread cards (both gained a real
 * `onOpenThread` this round — see [DestinationContent]).
 */
@Composable
private fun ThreadDetailContent(
    context: android.content.Context,
    sessionId: String?,
    threadId: String,
    onBack: () -> Unit,
    onOpenOver: (String) -> Unit,
    backLabel: String,
    modifier: Modifier = Modifier,
) {
    var detail by remember(threadId, sessionId) { mutableStateOf<ThreadDetailViewState?>(null) }
    LaunchedEffect(threadId, sessionId) {
        val session = sessionId
        if (session != null) {
            detail = ThreadPolling.threadDetail(context, session, threadId)
        }
    }
    val current = detail
    if (current != null) {
        ThreadDetailScreen(
            state = current,
            onBack = onBack,
            onOpenOver = onOpenOver,
            onOpenSourceOver = onOpenOver,
            modifier = modifier,
            backLabel = backLabel,
        )
    } else {
        Text(
            text = "Loading…",
            modifier = modifier.padding(OrtSpacing.lg).semantics { contentDescription = "Loading thread detail" },
        )
    }
}
