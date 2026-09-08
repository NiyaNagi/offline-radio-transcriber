package org.ort.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import org.ort.app.ui.components.LiveBarViewState
import org.ort.app.ui.data.CaptureStatusMapper
import org.ort.app.ui.data.CaptureStatusViewState
import org.ort.app.ui.data.LiveBarPolling
import org.ort.app.ui.data.ReaderPolling
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.RealCaptureService
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.pipeline.capture.VadAvailability

private const val POLL_INTERVAL_MILLIS = 2_000L

/**
 * The Capture destination's polling wrapper (ui-conformance-plan WP4, R-031/R-032/R-034/R-035/
 * R-038) — reachable from the drawer's Capture row once WP3 dispatches
 * `ReaderDestination.CAPTURE` here (see this package's report: `ReaderDestination.kt`'s
 * `hasScreen = false` and the nav host's `PlaceholderScreen` fallback are both WP3's file, out of
 * this package's row).
 *
 * [sessionId] `null` (no active or prior session) renders the idle facts honestly rather than
 * polling a session that does not exist — the same rule [NowContent] follows.
 */
@Composable
public fun CaptureStatusContent(context: Context, sessionId: String?, modifier: Modifier = Modifier) {
    var state by remember { mutableStateOf(idleCaptureStatus()) }
    var liveBar by remember { mutableStateOf<LiveBarViewState?>(null) }

    if (sessionId != null) {
        LaunchedEffect(sessionId) {
            while (true) {
                state = ReaderPolling.captureStatus(context, sessionId)
                liveBar = if (CaptureState.isCapturing) LiveBarPolling.current(context, sessionId) else null
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    CaptureStatusScreen(
        state = state,
        modifier = modifier,
        liveBar = liveBar,
        onStop = { stopCapture(context) },
        onOpenLive = {},
    )
}

/** `Stop` (R-032): the same `ACTION_STOP` [RealCaptureService.onStartCommand] already handles —
 * no new capture-control path, just the existing one reached from this screen. */
private fun stopCapture(context: Context) {
    val intent = Intent(context, RealCaptureService::class.java).setAction(RealCaptureService.ACTION_STOP)
    context.startService(intent)
}

/** The honest idle facts — every process-wide holder read directly, since none of them requires a
 * session id to read (they simply report their own honest defaults when idle). */
private fun idleCaptureStatus(): CaptureStatusViewState = CaptureStatusMapper.from(
    captureState = CaptureState.State.Idle,
    shedLevel = null,
    backlog = null,
    thermal = ThermalStatus.state,
    rig = RigStatus.state,
    storage = StorageForecast.state,
    asr = AsrAvailability.state,
    vad = VadAvailability.state,
    sinceLabel = null,
    elapsedLabel = "0:00",
    heartbeatSecondsAgo = null,
    isAlive = false,
    transmissionCount = 0,
    rejectedCount = 0,
    failedCount = 0,
    gapCount = 0,
    batteryPercent = null,
    batteryCharging = false,
    batteryExemptionReportsIgnoring = false,
)
