package org.ort.app.ui.navigation

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
import org.ort.app.ui.data.NowSummaryMapper
import org.ort.app.ui.data.NowSummaryViewState
import org.ort.app.ui.data.ReaderPolling
import org.ort.app.ui.data.ReaderTransmissionViewStateMapper
import org.ort.app.ui.data.StationListEntryViewState
import org.ort.app.ui.data.TransmissionDetail
import org.ort.app.ui.screens.FrequenciesListScreen
import org.ort.app.ui.screens.FrequencyDetailScreen
import org.ort.app.ui.screens.LogScreen
import org.ort.app.ui.screens.NowScreen
import org.ort.app.ui.screens.PlaceholderScreen
import org.ort.app.ui.screens.StationDetailScreen
import org.ort.app.ui.screens.StationsListScreen
import org.ort.app.ui.screens.TransmissionDetailScreen
import org.ort.app.ui.theme.OrtSpacing
import org.ort.core.SystemClock

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
    val storage = remember { StorageFooterViewState.fromDeviceStorage(context) }
    val audioPlayer = remember { RealTransmissionAudioPlayer(context) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ReaderDrawerContent(
                current = current,
                storage = storage,
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
            val content = Modifier.padding(padding)
            when {
                current == ReaderDestination.NOW ->
                    NowContent(sessionId = sessionId, modifier = content)

                current == ReaderDestination.LOG ->
                    LogContent(sessionId = sessionId, onOpen = { openTransmissionId = it }, modifier = content)

                current == ReaderDestination.STATIONS ->
                    StationsContent(context = context, onOpen = { openStationId = it }, modifier = content)

                current == ReaderDestination.FREQUENCIES ->
                    FrequenciesContent(context = context, onOpen = { openFrequencyHz = it }, modifier = content)

                else -> PlaceholderScreen(destinationLabel = current.label, modifier = content)
            }
        }
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
