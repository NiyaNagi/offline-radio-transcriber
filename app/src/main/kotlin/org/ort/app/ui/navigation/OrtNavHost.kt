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
import org.ort.app.transmissions.TransmissionRow
import org.ort.app.ui.data.ReaderPolling
import org.ort.app.ui.screens.PlaceholderScreen
import org.ort.app.ui.screens.StatusScreen
import org.ort.app.ui.screens.TransmissionListScreen
import org.ort.core.SystemClock

private const val POLL_INTERVAL_MILLIS = 2_000L

/**
 * The navigation host (build-plan P13): the drawer `Menu.dc.html` specifies, wrapping whichever
 * destination is current. `Now` and `Log` are real screens over the same v0 smoke-test data path
 * [org.ort.app.status.StatusActivity]/[org.ort.app.transmissions.TransmissionListActivity]
 * already poll (see `ui/data/ReaderPolling.kt`); every other destination is a
 * [PlaceholderScreen] until its own prompt builds it.
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
    val storage = remember { StorageFooterViewState.fromDeviceStorage(context) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ReaderDrawerContent(
                current = current,
                storage = storage,
                onSelect = { destination ->
                    current = destination
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
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
                    LogContent(sessionId = sessionId, modifier = content)

                else -> PlaceholderScreen(destinationLabel = current.label, modifier = content)
            }
        }
    }
}

@Composable
private fun NowContent(sessionId: String?, modifier: Modifier) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(idleStatus()) }
    if (sessionId != null) {
        LaunchedEffect(sessionId) {
            val startedAt = SystemClock.wallMillis()
            while (true) {
                state = ReaderPolling.currentStatus(context, sessionId, startedAt)
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }
    StatusScreen(state = state, modifier = modifier)
}

@Composable
private fun LogContent(sessionId: String?, modifier: Modifier) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf(emptyList<TransmissionRow>()) }
    if (sessionId != null) {
        LaunchedEffect(sessionId) {
            while (true) {
                rows = ReaderPolling.currentTransmissions(context, sessionId)
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }
    TransmissionListScreen(rows = rows, modifier = modifier)
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
