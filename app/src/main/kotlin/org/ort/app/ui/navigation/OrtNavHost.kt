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
import org.ort.app.ui.data.NowSummaryMapper
import org.ort.app.ui.data.NowSummaryViewState
import org.ort.app.ui.data.ReaderPolling
import org.ort.app.ui.data.ReaderTransmissionViewStateMapper
import org.ort.app.ui.data.SearchFilterInput
import org.ort.app.ui.data.SearchFilterParser
import org.ort.app.ui.data.SearchPolling
import org.ort.app.ui.data.SearchResult
import org.ort.app.ui.data.ThreadGroupViewState
import org.ort.app.ui.data.ThreadPolling
import org.ort.app.ui.data.TransmissionDetail
import org.ort.app.ui.screens.LogScreen
import org.ort.app.ui.screens.NowScreen
import org.ort.app.ui.screens.PlaceholderScreen
import org.ort.app.ui.screens.SearchScreen
import org.ort.app.ui.screens.ThreadScreen
import org.ort.app.ui.screens.TransmissionDetailScreen
import org.ort.app.ui.theme.OrtSpacing
import org.ort.core.SystemClock

private const val POLL_INTERVAL_MILLIS = 2_000L

/**
 * The navigation host (build-plan P13, extended by P14): the drawer `Menu.dc.html` specifies,
 * wrapping whichever destination is current. `Now` (`Main.dc.html`) and `Log` (`Log.dc.html`) are
 * real screens reading real `:data` state (see `ui/data/ReaderPolling.kt`); tapping a Log row
 * opens `Detail.dc.html`'s drill-in over whichever destination was current. Every other
 * destination is a [PlaceholderScreen] until its own prompt builds it.
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
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        val transmissionId = openTransmissionId
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

                current == ReaderDestination.SEARCH ->
                    SearchContent(onOpen = { openTransmissionId = it }, modifier = content)

                current == ReaderDestination.THREADS ->
                    ThreadContent(sessionId = sessionId, onOpen = { openTransmissionId = it }, modifier = content)

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
    LaunchedEffect(transmissionId) {
        detail = ReaderPolling.transmissionDetail(context, transmissionId)
    }
    val current = detail
    if (current != null) {
        TransmissionDetailScreen(
            state = ReaderTransmissionViewStateMapper.detailView(current),
            player = player,
            onBack = onBack,
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
