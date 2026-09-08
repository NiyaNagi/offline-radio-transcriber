package org.ort.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    // R-017: the destination a drill-in was opened from, so back returns there — set only when a
    // drill-in opens, read only while one is showing (`current` itself never changes meanwhile, see
    // `onOpenDrillIn` below, but this makes the origin an explicit, testable fact rather than an
    // implicit property of that invariant).
    var openedFrom by rememberSaveable { mutableStateOf(ReaderDestination.NOW) }
    var openTransmissionId by rememberSaveable { mutableStateOf<String?>(null) }
    var openStationId by rememberSaveable { mutableStateOf<String?>(null) }
    var openFrequencyHz by rememberSaveable { mutableStateOf<Long?>(null) }
    // R-017: WP5's `ThreadDetailScreen` (its own file's doc comment: "not yet reachable ... ready
    // for whichever package wires that route") — this is that route, ready to open once WP5/WP7
    // expose a callback into it (see this package's report on why nothing does yet).
    var openThreadId by rememberSaveable { mutableStateOf<String?>(null) }
    // R-017 / `Flow-Search.dc.html`: Search's input and results live here, in the host, not inside
    // WP7's `SearchContent` — that composable is skipped entirely while a drill-in is showing (see
    // `NavHostBody` below), and a skipped composable's own `remember` state does not survive being
    // skipped; lifting it here is what makes "back from a detail opened from Search returns to
    // Search with its filters intact" true rather than aspirational. `searchInput` is
    // `rememberSaveable` via [SearchFilterInputSaver] (built in this file, no edit to WP7's own
    // types); `searchResult` stays plain `remember` — see [SearchFilterInputSaver]'s own doc
    // comment for why `SearchResult` gets no equivalent Saver.
    var searchInput by rememberSaveable(stateSaver = SearchFilterInputSaver) { mutableStateOf(SearchFilterInput()) }
    var searchResult by remember { mutableStateOf<SearchResult?>(null) }
    val drawerLive = rememberDrawerLiveState(sessionId, context)
    val audioPlayer = remember { RealTransmissionAudioPlayer(context) }

    fun closeDrillIns() {
        openTransmissionId = null
        openStationId = null
        openFrequencyHz = null
        openThreadId = null
    }

    fun onOpenDrillIn(setter: () -> Unit) {
        openedFrom = current
        setter()
    }

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
                    closeDrillIns()
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Scaffold { padding ->
            NavHostBody(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentTopPadding = contentTopPadding,
                ids = NavHostIds(current, openedFrom, openTransmissionId, openStationId, openFrequencyHz, openThreadId),
                callbacks = NavHostCallbacks(
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onSearchDestination = { current = ReaderDestination.SEARCH },
                    onCloseDrillIns = ::closeDrillIns,
                    onOpenCapture = {
                        current = ReaderDestination.CAPTURE
                        closeDrillIns()
                    },
                    onOpenTransmission = { id -> onOpenDrillIn { openTransmissionId = id } },
                    onOpenStation = { id -> onOpenDrillIn { openStationId = id } },
                    onOpenFrequency = { hz -> onOpenDrillIn { openFrequencyHz = hz } },
                    onOpenThread = { id -> onOpenDrillIn { openThreadId = id } },
                    onOpenStations = { current = ReaderDestination.STATIONS },
                    // R-090's `Assets` sub-screen has no external "land here" entry (WP10's
                    // `SettingsContent` own its `screen` state entirely internally — confirmed by
                    // reading that file before wiring this) — this opens the `Settings` root, same
                    // limitation as `ReaderNavigator.openSettingsStorage`; reported, not silently
                    // pretended to be the real Assets screen.
                    onOpenModels = { current = ReaderDestination.SETTINGS },
                ),
                sessionId = sessionId,
                context = context,
                drawerLive = drawerLive,
                audioPlayer = audioPlayer,
                search = SearchHostState(
                    input = searchInput,
                    onInputChange = { searchInput = it },
                    result = searchResult,
                    onSearch = {
                        scope.launch {
                            val params = SearchFilterParser.parse(searchInput, SystemClock.wallMillis())
                            val facetFilter = SearchFacetFilter.from(searchInput)
                            searchResult = SearchPolling.search(context, params, facetFilter)
                        }
                    },
                ),
            )
        }
    }
}

/** [NavHostBody]'s destination/drill-in identity, bundled to keep that composable's own parameter count down. */
private data class NavHostIds(
    val current: ReaderDestination,
    val openedFrom: ReaderDestination,
    val transmissionId: String?,
    val stationId: String?,
    val frequencyHz: Long?,
    val threadId: String?,
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
)

/**
 * R-017: WP7's [SearchContent]'s `input`/`result`, owned by [OrtNavHost] — see that function's doc
 * comment. [onSearch] is the host's own trigger (WP7's `SearchContent` takes `onSearch: () ->
 * Unit`, not a result setter — the host runs the query and writes [result] itself).
 */
private data class SearchHostState(
    val input: SearchFilterInput,
    val onInputChange: (SearchFilterInput) -> Unit,
    val result: SearchResult?,
    val onSearch: () -> Unit,
)

/**
 * The header (R-003/R-004/R-015/R-016), the current destination or drill-in's content, and the
 * live bar (R-022), stacked in one column filling [OrtNavHost]'s `Scaffold`. Extracted out of
 * [OrtNavHost] purely to keep that function under detekt's length limit — the same reason
 * [DestinationContent] was already extracted before this prompt.
 */
@Composable
private fun NavHostBody(
    modifier: Modifier,
    ids: NavHostIds,
    callbacks: NavHostCallbacks,
    sessionId: String?,
    context: android.content.Context,
    drawerLive: DrawerLiveState,
    audioPlayer: org.ort.app.ui.audio.TransmissionAudioPlayer,
    search: SearchHostState,
    contentTopPadding: Dp = 0.dp,
) {
    Column(modifier = modifier) {
        val drillInIds = listOf(ids.transmissionId, ids.stationId, ids.frequencyHz, ids.threadId)
        val isDrillIn = drillInIds.any { it != null }
        // ui-conformance WP3 round 4 (R-129 smoke coverage found this, real at the time): this host
        // used to skip its own header for `SETTINGS` because `SettingsRootScreen` drew a second,
        // duplicate one — the same double-header defect R-016 already fixed once for the four real
        // drill-ins. **Superseded, not re-added**: register R-130 (System validator, landed in the
        // same merge that brought this file's own `contentTopPadding` change) fixed the identical
        // finding from the *other* side — `SettingsRootScreen.kt`'s own doc comment now states
        // plainly that it draws no header at all, "`OrtNavHost`'s `NavHostBody` already renders one
        // for the whole `SETTINGS` destination... before dispatching to `SettingsContent`". Keeping
        // both fixes after that merge left `SETTINGS` with *zero* headers — `R_129_SETTINGS_composes
        // _and_survives_recreation`'s own `recreate()` case is what caught it, timing out waiting for
        // "Open navigation" to ever exist. `SETTINGS` is an ordinary destination again, exactly like
        // every other non-drill-in one; the two fixes cannot both stand, and the later, more specific
        // one (`ui/settings/**`'s own file, stating outright what it now expects the host to do) wins.
        if (!isDrillIn) {
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

        Box(modifier = Modifier.weight(1f).padding(top = contentTopPadding)) {
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
        if (!embedsOwnLiveBar) {
            drawerLive.liveBar?.let { liveBarState ->
                LiveBar(state = liveBarState, onClick = callbacks.onOpenCapture)
            }
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

        ReaderDestination.CAPTURE ->
            CaptureStatusContent(context = context, sessionId = sessionId, modifier = content)

        // ui-conformance-plan WP10 (register R-090/R-091/R-092/R-107): all three dispatch to
        // WP10's own real content composables now that they are on this branch. `Earlier nights`
        // and `Improve records` no longer fall through to `PlaceholderScreen` — see
        // `ReaderDestination`'s own doc comment for what each one now is.
        ReaderDestination.SETTINGS ->
            org.ort.app.ui.settings.SettingsContent(context = context, onDrawer = onOpenDrawer, modifier = content)

        ReaderDestination.EARLIER_NIGHTS ->
            org.ort.app.ui.digest.SessionsContent(context = context, onDrawer = onOpenDrawer, modifier = content)

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
