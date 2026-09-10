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
import org.ort.app.ui.data.RoomSessionRouteFactsReader

private const val POLL_INTERVAL_MILLIS = 2_000L

/**
 * The "Now" destination's polling wrapper (ui-conformance-plan WP4) — `OrtNavHost.kt` (WP3's
 * file) dispatches `ReaderDestination.NOW` here once WP3 deletes its own inline `NowContent` and
 * calls this one instead (this package's brief's own words; see `NowScreen`'s deprecated overload
 * for what keeps the build green until then).
 *
 * R-172: [sessionId] is a fallback only — every poll re-derives the session actually worth
 * showing via [ReaderPolling.effectiveSessionId] (`CaptureState.sessionId` whenever it is
 * genuinely capturing), so a session that starts *after* this screen is already composed — a
 * fresh `Start capture` tap here, or a scenario broadcast setting `CaptureState` directly while
 * the reader is already open (R-171) — is picked up on the very next tick, never stuck on
 * whichever session was live (or not) when this composable first ran.
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
    // Defaulted, not required: the host (`OrtNavHost.kt`, WP3's file) is being edited concurrently
    // by WP10 this round and wires these afterwards — a default keeps this composable callable,
    // unchanged, from whatever call site exists in the meantime.
    onOpenStations: () -> Unit = {},
    onOpenModels: () -> Unit = {},
    // E2-G02 (N01b): the room-audio chip's tap target — the settings Capture-mode screen (CF11).
    // Defaulted so every existing caller (`OrtNavHost.kt`, out of this package's row) keeps
    // compiling unchanged until it wires the real navigation.
    onOpenCaptureMode: () -> Unit = {},
) {
    var state by remember { mutableStateOf<NowViewState>(NowViewState.Idle(null, null, null, null, emptyList(), null)) }
    var liveBar by remember { mutableStateOf<LiveBarViewState?>(null) }
    // Bumped after `Start capture` so the next tick fires immediately rather than waiting up to
    // POLL_INTERVAL_MILLIS — a snappier UX only, not what makes the new session show up at all
    // (that is `effectiveSessionId`'s job, and it works with or without this).
    var pollGeneration by remember { mutableStateOf(0) }

    LaunchedEffect(sessionId, pollGeneration) {
        val routeFactsReader = RoomSessionRouteFactsReader(context)
        while (true) {
            // Two calls, one resolution: `nowViewState` already resolves `sessionId` through
            // `effectiveSessionId` internally (its own kdoc), so passing the raw, unresolved
            // `sessionId` here is correct, not a shortcut. `effectiveSessionId` is computed again,
            // locally, only because `LiveBarPolling.current` — unlike `nowViewState` — takes the
            // session id it should read directly and does no resolution of its own.
            val effectiveSessionId = ReaderPolling.effectiveSessionId(sessionId)
            val baseState = ReaderPolling.nowViewState(context, sessionId)
            // E2-G02 (FR-CAP-3a/FR-CAP-10): `ReaderPolling.nowViewState` (WP4's file, out of this
            // package's row) does not know about the session's own v7 `captureMode` column —
            // re-derive `isLocalMicrophone` here from the WPF seam, the same pattern
            // `CaptureStatusContent` already uses for its own Input/Radio rows.
            state = if (baseState is NowViewState.Active) {
                val routeFacts = routeFactsReader.forSession(effectiveSessionId)
                baseState.copy(isLocalMicrophone = routeFacts.isLocalMicrophone)
            } else {
                baseState
            }
            liveBar = if (state is NowViewState.Active) {
                LiveBarPolling.current(context, effectiveSessionId)
            } else {
                null
            }
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    NowScreen(
        state = state,
        modifier = modifier,
        liveBar = liveBar,
        onOpenLive = {},
        onStartCapture = {
            ReaderPolling.startCapture(context)
            pollGeneration++
        },
        onOpenStation = onOpenStation,
        onOpenStations = onOpenStations,
        onOpenModels = onOpenModels,
        onOpenCaptureMode = onOpenCaptureMode,
    )
}
