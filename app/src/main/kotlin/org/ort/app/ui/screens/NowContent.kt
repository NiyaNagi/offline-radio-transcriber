package org.ort.app.ui.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import org.ort.app.ui.components.LiveBarViewState
import org.ort.app.ui.data.LiveBarPolling
import org.ort.app.ui.data.NowViewState
import org.ort.app.ui.data.ReaderPolling

private const val POLL_INTERVAL_MILLIS = 2_000L

/**
 * The "Now" destination's polling wrapper (ui-conformance-plan WP4) — `OrtNavHost.kt` (WP3's
 * file) dispatches `ReaderDestination.NOW` here once WP3 deletes its own inline `NowContent` and
 * calls this one instead (this package's brief's own words; see `NowScreen`'s deprecated overload
 * for what keeps the build green until then).
 */
@Composable
public fun NowContent(
    context: Context,
    sessionId: String?,
    // Not yet called by anything on this screen: no WORTH KNOWING or STATIONS HEARD row drills
    // into a single transmission today (only a station drill-in, via onOpenStation) — kept because
    // this package's brief names it explicitly as part of NowContent's required signature, for a
    // caller (WP3) that already wires an onOpenTransmission handler through every other
    // *Content composable this reader has.
    @Suppress("UNUSED_PARAMETER") onOpenTransmission: (String) -> Unit,
    onOpenStation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeSessionId by remember(sessionId) { mutableStateOf(sessionId) }
    var state by remember { mutableStateOf<NowViewState>(NowViewState.Idle(null, null, null, null, emptyList(), null)) }
    var liveBar by remember { mutableStateOf<LiveBarViewState?>(null) }

    LaunchedEffect(activeSessionId) {
        while (true) {
            state = ReaderPolling.nowViewState(context, activeSessionId)
            liveBar = if (state is NowViewState.Active) LiveBarPolling.current(context, activeSessionId) else null
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    NowScreen(
        state = state,
        modifier = modifier,
        liveBar = liveBar,
        onOpenLive = {},
        onStartCapture = { activeSessionId = ReaderPolling.startCapture(context) },
        onOpenStation = onOpenStation,
    )
}
