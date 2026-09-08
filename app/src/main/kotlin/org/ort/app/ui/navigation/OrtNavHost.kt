package org.ort.app.ui.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ort.app.status.StatusViewState
import org.ort.app.ui.audio.RealTransmissionAudioPlayer
import org.ort.app.ui.data.FrequencyListEntryViewState
import org.ort.app.ui.data.ModelActionResult
import org.ort.app.ui.data.ModelId
import org.ort.app.ui.data.ModelsController
import org.ort.app.ui.data.NowSummaryMapper
import org.ort.app.ui.data.NowSummaryViewState
import org.ort.app.ui.data.ReaderPolling
import org.ort.app.ui.data.ReaderTransmissionViewStateMapper
import org.ort.app.ui.data.SearchFilterInput
import org.ort.app.ui.data.SearchFilterParser
import org.ort.app.ui.data.SearchPolling
import org.ort.app.ui.data.SearchResult
import org.ort.app.ui.data.StationListEntryViewState
import org.ort.app.ui.data.ThreadGroupViewState
import org.ort.app.ui.data.ThreadPolling
import org.ort.app.ui.data.TransmissionDetail
import org.ort.app.ui.screens.FrequenciesListScreen
import org.ort.app.ui.screens.FrequencyDetailScreen
import org.ort.app.ui.screens.LogScreen
import org.ort.app.ui.screens.ModelsScreen
import org.ort.app.ui.screens.NowScreen
import org.ort.app.ui.screens.PlaceholderScreen
import org.ort.app.ui.screens.SearchScreen
import org.ort.app.ui.screens.StationDetailScreen
import org.ort.app.ui.screens.StationsListScreen
import org.ort.app.ui.screens.ThreadScreen
import org.ort.app.ui.screens.TransmissionDetailScreen
import org.ort.app.ui.theme.OrtSpacing
import org.ort.core.SystemClock
import java.io.IOException

private const val POLL_INTERVAL_MILLIS = 2_000L

/**
 * The navigation host (build-plan P13, extended by P14 and P17): the drawer `Menu.dc.html`
 * specifies, wrapping whichever destination is current. `Now` (`Main.dc.html`), `Log`
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
    var openTransmissionId by rememberSaveable { mutableStateOf<String?>(null) }
    var openStationId by rememberSaveable { mutableStateOf<String?>(null) }
    var openFrequencyHz by rememberSaveable { mutableStateOf<Long?>(null) }
    val drawerLive = rememberDrawerLiveState(sessionId, context)
    val audioPlayer = remember { RealTransmissionAudioPlayer(context) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ReaderDrawerContent(
                current = current,
                storage = drawerLive.storage,
                badges = drawerLive.badges,
                onSelect = { destination ->
                    current = destination
                    openTransmissionId = null
                    openStationId = null
                    openFrequencyHz = null
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        val transmissionId = openTransmissionId
        val stationId = openStationId
        val frequencyHz = openFrequencyHz
        if (transmissionId != null) {
            // The detail drill-in replaces the whole scaffold (including the top bar) — its own
            // back control is the way out, mirroring `Detail.dc.html`'s full-screen presentation.
            TransmissionDetailContent(
                context = context,
                transmissionId = transmissionId,
                player = audioPlayer,
                onBack = { openTransmissionId = null },
            )
            return@ModalNavigationDrawer
        }
        if (stationId != null) {
            StationDetailContent(context = context, stationId = stationId, onBack = { openStationId = null })
            return@ModalNavigationDrawer
        }
        if (frequencyHz != null) {
            FrequencyDetailContent(context = context, frequencyHz = frequencyHz, onBack = { openFrequencyHz = null })
            return@ModalNavigationDrawer
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(current.label) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text(
                                text = "=", // plain glyph stand-in for a hamburger icon (see class doc)
                                modifier = Modifier.semantics {
                                    contentDescription = "Open navigation drawer"
                                },
                            )
                        }
                    },
                )
            },
        ) { padding ->
            DestinationContent(
                current = current,
                sessionId = sessionId,
                context = context,
                onOpenTransmission = { openTransmissionId = it },
                onOpenStation = { openStationId = it },
                onOpenFrequency = { openFrequencyHz = it },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

/** [StorageFooterViewState] and [DrawerBadgeViewState] bundled, so [rememberDrawerLiveState] returns one value. */
private data class DrawerLiveState(val storage: StorageFooterViewState, val badges: DrawerBadgeViewState)

/**
 * FR-STO-5 / FR-UI-7 (audit F-020): the footer and the drawer's Log/Capture badges, polled the
 * same way `NowContent`/`LogContent` already poll status — real figures recomputed on the same
 * cadence, never a value fixed at the moment the drawer first composed. Extracted out of
 * [OrtNavHost] purely to keep that function under detekt's length limit; there is no other reason
 * this couldn't be inline.
 */
@Composable
private fun rememberDrawerLiveState(sessionId: String?, context: android.content.Context): DrawerLiveState {
    var storage by remember { mutableStateOf(StorageFooterViewState.fromAudioDirectory(context)) }
    var badges by remember { mutableStateOf(DrawerBadgeViewState.NONE) }
    LaunchedEffect(sessionId) {
        while (true) {
            storage = StorageFooterViewState.fromAudioDirectory(context)
            badges = ReaderPolling.drawerBadges(context, sessionId)
            delay(POLL_INTERVAL_MILLIS)
        }
    }
    return DrawerLiveState(storage, badges)
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
    onOpenTransmission: (String) -> Unit,
    onOpenStation: (String) -> Unit,
    onOpenFrequency: (Long) -> Unit,
    modifier: Modifier,
) {
    val content = modifier
    when (current) {
        ReaderDestination.NOW ->
            NowContent(sessionId = sessionId, modifier = content)

        ReaderDestination.LOG ->
            LogContent(sessionId = sessionId, onOpen = onOpenTransmission, modifier = content)

        ReaderDestination.SEARCH ->
            SearchContent(onOpen = onOpenTransmission, modifier = content)

        ReaderDestination.THREADS ->
            ThreadContent(sessionId = sessionId, onOpen = onOpenTransmission, modifier = content)

        ReaderDestination.STATIONS ->
            StationsContent(context = context, onOpen = onOpenStation, modifier = content)

        ReaderDestination.FREQUENCIES ->
            FrequenciesContent(context = context, onOpen = onOpenFrequency, modifier = content)

        ReaderDestination.SETTINGS ->
            ModelsContent(context = context, modifier = content)

        else -> PlaceholderScreen(destinationLabel = current.label, modifier = content)
    }
}

@Composable
private fun NowContent(sessionId: String?, modifier: Modifier) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(idleStatus()) }
    var summary by remember { mutableStateOf(NowSummaryViewState(overCount = 0, stationCount = 0)) }
    if (sessionId != null) {
        LaunchedEffect(sessionId) {
            val startedAt = SystemClock.wallMillis()
            while (true) {
                status = ReaderPolling.currentStatus(context, sessionId, startedAt)
                summary = NowSummaryMapper.from(ReaderPolling.currentTransmissionDetails(context, sessionId))
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }
    NowScreen(status = status, summary = summary, modifier = modifier)
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

/**
 * FR-UI-3 (build-plan P15): search runs on an explicit action, not on every keystroke — a search
 * screen has no session-tied poll, unlike Now/Log, since it queries on demand rather than showing
 * a live session's state.
 */
@Composable
private fun SearchContent(onOpen: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf(SearchFilterInput()) }
    var result by remember { mutableStateOf<SearchResult?>(null) }
    SearchScreen(
        input = input,
        result = result,
        onInputChange = { input = it },
        onSearch = {
            val params = SearchFilterParser.parse(input)
            scope.launch { result = SearchPolling.search(context, params) }
        },
        onOpen = onOpen,
        modifier = modifier,
    )
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
            onSearchStations = { query -> ReaderPolling.searchKnownStations(context, query) },
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

/**
 * Audit F-008: the "Models" destination (`Settings` in the drawer). Owns its own busy/last-message
 * state — [ModelsController] itself is stateless — and drives [ModelsController.download]/
 * [ModelsController.sideload] from a tap, off the main dispatcher (both already hop to
 * [kotlinx.coroutines.Dispatchers.IO] internally), refreshing [ModelsController.currentState]
 * after either finishes so the row's installed/not-installed fact always reflects what
 * `ModelAcquisition` itself verified, not an optimistic guess.
 */
@Composable
private fun ModelsContent(context: android.content.Context, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ModelsController.currentState(context)) }
    var busy by remember { mutableStateOf(emptySet<ModelId>()) }
    var lastMessage by remember { mutableStateOf<String?>(null) }
    var pendingSideloadId by remember { mutableStateOf<ModelId?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = pendingSideloadId
        pendingSideloadId = null
        if (uri == null || id == null) return@rememberLauncherForActivityResult
        busy = busy + id
        scope.launch {
            val source = copyPickedFileToCache(context, uri, id)
            val result = if (source != null) {
                ModelsController.sideload(context, id, source)
            } else {
                ModelActionResult.Failure("could not read the picked file")
            }
            busy = busy - id
            lastMessage = messageFor(id, result)
            state = ModelsController.currentState(context)
        }
    }

    ModelsScreen(
        state = state,
        busy = busy,
        lastMessage = lastMessage,
        onDownload = { id ->
            busy = busy + id
            scope.launch {
                val result = ModelsController.download(context, id)
                busy = busy - id
                lastMessage = messageFor(id, result)
                state = ModelsController.currentState(context)
            }
        },
        onSideload = { id ->
            pendingSideloadId = id
            filePicker.launch(arrayOf("*/*"))
        },
        modifier = modifier,
    )
}

private fun messageFor(id: ModelId, result: ModelActionResult): String = when (result) {
    is ModelActionResult.Success ->
        "${id.label}: installed, checksum verified. Requeued ${result.requeuedCount} previously failed transmission(s)."
    is ModelActionResult.Failure -> "${id.label}: ${result.reason}"
}

/**
 * [ModelAcquisition][org.ort.net.ModelAcquisition].sideload takes a [java.io.File], not a content
 * [android.net.Uri] — the system picker only ever hands back the latter, so this copies the picked
 * document into app-private cache storage first. No network call either way (constitution V).
 */
private fun copyPickedFileToCache(context: android.content.Context, uri: android.net.Uri, id: ModelId): java.io.File? =
    try {
        val dest = java.io.File(context.cacheDir, "sideload-${id.name}.tmp")
        val opened = context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
            true
        }
        if (opened == true) dest else null
    } catch (e: IOException) {
        null
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

private fun idleStatus(): StatusViewState = StatusViewState(
    stateLabel = "Idle",
    elapsedLabel = "00:00:00",
    transmissionCount = 0,
    gapCount = 0,
    shedLevel = 0,
    shedLevelLabel = "Nominal",
    livenessLabel = "No session",
    uncleanEndBanner = null,
)
