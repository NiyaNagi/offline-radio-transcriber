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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ort.app.ui.audio.RealTransmissionAudioPlayer
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.LiveBar
import org.ort.app.ui.components.LiveBarViewState
import org.ort.app.ui.components.ScreenHeader
import org.ort.app.ui.data.DrawerCounts
import org.ort.app.ui.data.DrawerCountsViewState
import org.ort.app.ui.data.FrequencyListEntryViewState
import org.ort.app.ui.data.LiveBarPolling
import org.ort.app.ui.data.ReaderPolling
import org.ort.app.ui.data.ReaderTransmissionViewStateMapper
import org.ort.app.ui.data.SearchFacetFilter
import org.ort.app.ui.data.SearchFilterInput
import org.ort.app.ui.data.SearchFilterParser
import org.ort.app.ui.data.SearchPolling
import org.ort.app.ui.data.SearchResult
import org.ort.app.ui.data.SearchTimeFilter
import org.ort.app.ui.data.StationListEntryViewState
import org.ort.app.ui.data.ThreadGroupViewState
import org.ort.app.ui.data.ThreadPolling
import org.ort.app.ui.data.TransmissionDetail
import org.ort.app.ui.screens.CaptureStatusContent
import org.ort.app.ui.screens.FrequenciesListScreen
import org.ort.app.ui.screens.FrequencyDetailScreen
import org.ort.app.ui.screens.LogScreen
import org.ort.app.ui.screens.NowContent
import org.ort.app.ui.screens.PlaceholderScreen
import org.ort.app.ui.screens.SearchContent
import org.ort.app.ui.screens.StationDetailScreen
import org.ort.app.ui.screens.StationsListScreen
import org.ort.app.ui.screens.ThreadScreen
import org.ort.app.ui.screens.TransmissionDetailScreen
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
 * each screen owns its own) and, for a drill-in, [DrillInHeader] instead (R-016 — a chevron plus
 * the destination it was opened from). WP2's [LiveBar] is pinned to the bottom of every destination
 * and drill-in while a session runs (R-022's consumer). `Now` (`Main.dc.html`), `Log`
 * (`Log.dc.html`), `Stations` and `Frequencies` are real screens reading real `:data` state (see
 * `ui/data/ReaderPolling.kt`); tapping a Log row, a station row or a frequency row opens that
 * item's own full-screen drill-in over whichever destination was current. Every other destination
 * is a [PlaceholderScreen] until its own prompt builds it.
 *
 * [sessionId] is null when the host is opened with no active or prior capture session (for
 * example, opened directly rather than from the status flow) - `Now`/`Log` then show their
 * empty/idle state rather than polling a session that does not exist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun OrtNavHost(sessionId: String?) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var current by rememberSaveable { mutableStateOf(ReaderDestination.NOW) }
    // R-017: the destination a drill-in was opened from, so back returns there — set only when a
    // drill-in opens, read only while one is showing (`current` itself never changes meanwhile, see
    // `onOpenDrillIn` below, but this makes the origin an explicit, testable fact rather than an
    // implicit property of that invariant).
    var openedFrom by rememberSaveable { mutableStateOf(ReaderDestination.NOW) }
    var openTransmissionId by rememberSaveable { mutableStateOf<String?>(null) }
    var openStationId by rememberSaveable { mutableStateOf<String?>(null) }
    var openFrequencyHz by rememberSaveable { mutableStateOf<Long?>(null) }
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
                ids = NavHostIds(current, openedFrom, openTransmissionId, openStationId, openFrequencyHz),
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
) {
    Column(modifier = modifier) {
        val isDrillIn = ids.transmissionId != null || ids.stationId != null || ids.frequencyHz != null
        if (isDrillIn) {
            // R-016: one header for every drill-in — chevron + the origin destination's label —
            // rather than each screen's own "‹ Back" text row (WP5/6/8 remove those from
            // `TransmissionDetailScreen`/`StationDetailScreen`/`FrequencyDetailScreen` themselves;
            // `onBack` is still passed through unchanged so those screens keep working either way
            // in the meantime).
            DrillInHeader(parentLabel = ids.openedFrom.label, onBack = callbacks.onCloseDrillIns)
        } else {
            // R-003/R-004/R-015: drawer icon, live dot + elapsed while a session is capturing,
            // search icon — no title, the destination content below draws its own (`Main.dc.html`'s
            // 27sp title is `NowScreen`'s, not the header's).
            ScreenHeader(
                onDrawer = callbacks.onOpenDrawer,
                liveElapsedLabel = drawerLive.badges.captureElapsedLabel,
                onSearch = callbacks.onSearchDestination,
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                ids.transmissionId != null -> TransmissionDetailContent(
                    context = context,
                    transmissionId = ids.transmissionId,
                    player = audioPlayer,
                    onBack = callbacks.onCloseDrillIns,
                )

                ids.stationId != null -> StationDetailContent(
                    context = context,
                    stationId = ids.stationId,
                    onBack = callbacks.onCloseDrillIns,
                )

                ids.frequencyHz != null -> FrequencyDetailContent(
                    context = context,
                    frequencyHz = ids.frequencyHz,
                    onBack = callbacks.onCloseDrillIns,
                )

                else -> DestinationContent(
                    current = ids.current,
                    sessionId = sessionId,
                    context = context,
                    search = search,
                    onOpenTransmission = callbacks.onOpenTransmission,
                    onOpenStation = callbacks.onOpenStation,
                    onOpenFrequency = callbacks.onOpenFrequency,
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
    LaunchedEffect(sessionId) {
        while (true) {
            storage = StorageFooterViewState.fromAudioDirectory(context)
            badges = ReaderPolling.drawerBadges(context, sessionId)
            counts = DrawerCounts.current(context)
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
    return DrawerLiveState(storage, badges, counts, sessionHeader, liveBar)
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
    onOpenTransmission: (String) -> Unit,
    onOpenStation: (String) -> Unit,
    onOpenFrequency: (Long) -> Unit,
    modifier: Modifier,
) {
    val content = modifier
    when (current) {
        // Both dispatch to WP4/WP7's own real content composables now that they are on this
        // branch (confirmed by reading `ui/screens/NowContent.kt`/`ui/screens/SearchContent.kt`
        // before writing this) — this package's own inline copies are gone.
        ReaderDestination.NOW -> NowContent(
            context = context,
            sessionId = sessionId,
            onOpenTransmission = onOpenTransmission,
            onOpenStation = onOpenStation,
            modifier = content,
        )

        ReaderDestination.LOG ->
            LogContent(sessionId = sessionId, onOpen = onOpenTransmission, modifier = content)

        ReaderDestination.SEARCH -> SearchContent(
            input = search.input,
            result = search.result,
            onInputChange = search.onInputChange,
            onSearch = search.onSearch,
            onOpen = onOpenTransmission,
            modifier = content,
        )

        ReaderDestination.THREADS ->
            ThreadContent(sessionId = sessionId, onOpen = onOpenTransmission, modifier = content)

        ReaderDestination.STATIONS ->
            StationsContent(context = context, onOpen = onOpenStation, modifier = content)

        ReaderDestination.FREQUENCIES ->
            FrequenciesContent(context = context, onOpen = onOpenFrequency, modifier = content)

        ReaderDestination.CAPTURE ->
            CaptureStatusContent(context = context, sessionId = sessionId, modifier = content)

        ReaderDestination.SETTINGS ->
            org.ort.app.ui.settings.ModelsContent(context = context, modifier = content)

        else -> PlaceholderScreen(destinationLabel = current.label, modifier = content)
    }
}

@Composable
private fun LogContent(sessionId: String?, onOpen: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    var details by remember { mutableStateOf(emptyList<TransmissionDetail>()) }
    if (sessionId != null) {
        LaunchedEffect(sessionId) {
            while (true) {
                details = ReaderPolling.currentTransmissionDetails(context, sessionId)
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }
    val entries = details.map { ReaderTransmissionViewStateMapper.listEntry(it) }
    LogScreen(entries = entries, onOpen = onOpen, modifier = modifier)
}

@Composable
private fun ThreadContent(sessionId: String?, onOpen: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    var groups by remember { mutableStateOf(emptyList<ThreadGroupViewState>()) }
    if (sessionId != null) {
        LaunchedEffect(sessionId) {
            while (true) {
                groups = ThreadPolling.currentThreadGroups(context, sessionId)
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }
    ThreadScreen(groups = groups, onOpen = onOpen, modifier = modifier)
}

@Composable
private fun TransmissionDetailContent(
    context: android.content.Context,
    transmissionId: String,
    player: org.ort.app.ui.audio.TransmissionAudioPlayer,
    onBack: () -> Unit,
) {
    var detail by remember(transmissionId) { mutableStateOf<TransmissionDetail?>(null) }
    suspend fun refresh() {
        detail = ReaderPolling.transmissionDetail(context, transmissionId)
    }
    LaunchedEffect(transmissionId) { refresh() }
    val current = detail
    if (current != null) {
        TransmissionDetailScreen(
            state = ReaderTransmissionViewStateMapper.detailView(current),
            player = player,
            onBack = onBack,
            onCorrect = { request ->
                ReaderPolling.applyCorrection(context, request)
                refresh()
            },
            onSearchLexicon = { query -> ReaderPolling.searchLexicon(query) },
            onRecordLabel = { sample ->
                org.ort.app.ui.data.LabelledSampleWriter.append(
                    java.io.File(context.filesDir, "labelled-samples.tsv"),
                    sample,
                )
            },
        )
    } else {
        Text(
            text = "Loading…",
            modifier = Modifier
                .padding(OrtSpacing.lg)
                .semantics { contentDescription = "Loading transmission detail" },
        )
    }
}

@Composable
private fun StationsContent(context: android.content.Context, onOpen: (String) -> Unit, modifier: Modifier) {
    var stations by remember { mutableStateOf(emptyList<StationListEntryViewState>()) }
    LaunchedEffect(Unit) { stations = ReaderPolling.listStationSummaries(context) }
    StationsListScreen(stations = stations, onOpen = onOpen, modifier = modifier)
}

@Composable
private fun FrequenciesContent(context: android.content.Context, onOpen: (Long) -> Unit, modifier: Modifier) {
    var frequencies by remember { mutableStateOf(emptyList<FrequencyListEntryViewState>()) }
    LaunchedEffect(Unit) { frequencies = ReaderPolling.listFrequencySummaries(context) }
    FrequenciesListScreen(frequencies = frequencies, onOpen = onOpen, modifier = modifier)
}

@Composable
private fun StationDetailContent(context: android.content.Context, stationId: String, onBack: () -> Unit) {
    var detail by remember(stationId) {
        mutableStateOf<org.ort.app.ui.data.StationDetailViewState?>(null)
    }
    LaunchedEffect(stationId) {
        detail = ReaderPolling.stationDetail(context, stationId, nowMillis = SystemClock.wallMillis())
    }
    val current = detail
    if (current != null) {
        StationDetailScreen(state = current, onBack = onBack)
    } else {
        Text(
            text = "Loading…",
            modifier = Modifier.padding(OrtSpacing.lg).semantics { contentDescription = "Loading station detail" },
        )
    }
}

@Composable
private fun FrequencyDetailContent(context: android.content.Context, frequencyHz: Long, onBack: () -> Unit) {
    var detail by remember(frequencyHz) {
        mutableStateOf<org.ort.app.ui.data.FrequencyDetailViewState?>(null)
    }
    LaunchedEffect(frequencyHz) {
        detail = ReaderPolling.frequencyDetail(context, frequencyHz, nowMillis = SystemClock.wallMillis())
    }
    val current = detail
    if (current != null) {
        FrequencyDetailScreen(state = current, onBack = onBack)
    } else {
        Text(
            text = "Loading…",
            modifier = Modifier.padding(OrtSpacing.lg).semantics { contentDescription = "Loading frequency detail" },
        )
    }
}
