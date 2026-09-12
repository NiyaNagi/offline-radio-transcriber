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
import org.ort.app.ui.data.LevelViewState
import org.ort.app.ui.data.LevelViewStateMapper
import org.ort.app.ui.data.LiveBarPolling
import org.ort.app.ui.data.LiveMonitorOversPolling
import org.ort.app.ui.data.LiveMonitorOversViewState
import org.ort.app.ui.data.ReaderPolling
import org.ort.app.ui.data.RoomSessionRouteFactsReader
import org.ort.app.ui.data.SessionRouteFacts
import org.ort.core.SystemClock
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RealCaptureService
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.pipeline.capture.VadAvailability

private const val POLL_INTERVAL_MILLIS = 2_000L

private enum class CaptureStatusSubScreen { NONE, LEVEL_METER, LIVE_MONITOR }

/**
 * The Capture destination's polling wrapper (ui-conformance-plan WP4, R-031/R-032/R-034/R-035/
 * R-038/R-039) — reachable from the drawer's Capture row (`OrtNavHost.kt`, WP3, dispatches
 * `ReaderDestination.CAPTURE` here — confirmed post-merge, R-035 closed).
 *
 * Owns an internal "which sub-screen" state for `Level-Meter.dc.html` (design-intent N04 → N06),
 * the same pattern [StationDetailContent] (WP8) uses for its own drill-ins: tapping the Level row
 * shows [LevelMeterScreen] full-screen over this same destination rather than a separate drawer
 * entry (N06 is never a standalone destination — see that screen's own kdoc), polled at the same
 * cadence as the status; its `DrillInHeader` back returns here.
 *
 * R-172: [sessionId] is a fallback only, and the poll loop is unconditional (never gated on
 * [sessionId] being non-null) — see [ReaderPolling.effectiveSessionId]'s own kdoc. Before this
 * fix, a `null` [sessionId] at first composition meant this screen would never start polling at
 * all, even once a session genuinely started capturing later in the same composition (R-171's
 * `overnight-live` scenario, broadcast while the reader is already open); a non-null [sessionId]
 * that later stopped matching the live session meant it kept polling the *wrong* one forever
 * instead of falling back to the honest idle facts. Both are fixed by resolving the session to
 * read fresh, every tick, rather than once.
 *
 * [openLevelMeter] (round 6, register R-132, lead-approved single-parameter addition — WP4 is
 * idle): lets a caller land directly on [LevelMeterScreen] instead of always starting at the
 * status root — the same "opens there on launch" contract
 * [org.ort.app.ui.settings.SettingsContent]'s own `initialScreen` already has (a fresh `remember`
 * seeded once; re-entering `Capture` is what gives a later change to this parameter effect, since
 * that disposes and rebuilds this composition — see `OrtNavHost.kt`'s own doc comment on the
 * identical reasoning for `initialScreen`). `false` (the default, so every existing caller keeps
 * compiling unchanged) starts at the status root, exactly as before this parameter existed. Its
 * one caller today is `OrtNavHost`, routing `Settings-Capture`'s `Meter` action here.
 *
 * [openLiveMonitor] (R-1007, WPL, the same "opens there on launch" contract [openLevelMeter]
 * already has): lets a caller land directly on [LiveMonitorScreen] — `OrtNavHost.kt`'s own route
 * for the pinned bar's tap, on every destination that shows its shared copy. `false` by default so
 * every existing caller keeps compiling unchanged; this composable's own embedded live bar
 * (`onOpenLive` below) is the other, in-package way to reach it.
 */
@Composable
public fun CaptureStatusContent(
    context: Context,
    sessionId: String?,
    modifier: Modifier = Modifier,
    openLevelMeter: Boolean = false,
    openLiveMonitor: Boolean = false,
    // R-1007 (WPL): `Live-Monitor.dc.html`'s own "Over row → D01–D04" / "`Full log` → L01" —
    // defaulted to no-ops so every existing caller (this file's own tests included) keeps
    // compiling; `OrtNavHost.kt` wires both to its real cross-destination navigation.
    onOpenOver: (String) -> Unit = {},
    onOpenFullLog: () -> Unit = {},
) {
    var sub by remember { mutableStateOf(initialSubScreen(openLevelMeter, openLiveMonitor)) }
    var state by remember { mutableStateOf(idleCaptureStatus()) }
    var liveBar by remember { mutableStateOf<LiveBarViewState?>(null) }
    var liveMonitorOvers by remember { mutableStateOf(LiveMonitorOversViewState.EMPTY) }
    var hearingText by remember { mutableStateOf<String?>(null) }
    // Not [currentLevelViewState] (suspend, R-175 reads the session's overs) — the first frame
    // renders the same honest LevelStatus-only snapshot it always has; the LaunchedEffect below
    // fills in the weakest-over label and band-state sentence on its very first tick.
    var levelState by remember {
        mutableStateOf(
            LevelViewStateMapper.from(
                level = LevelStatus.state,
                history = LevelStatus.peakHistoryDbfs,
                inputLabel = levelInputLabel(),
                clippedSamplesThisSession = LevelStatus.clippedSamplesThisSession,
            ),
        )
    }

    LaunchedEffect(sessionId) {
        val routeFactsReader = RoomSessionRouteFactsReader(context)
        while (true) {
            val effectiveSessionId = ReaderPolling.effectiveSessionId(sessionId)
            val baseState = if (effectiveSessionId != null) {
                ReaderPolling.captureStatus(context, effectiveSessionId)
            } else {
                idleCaptureStatus()
            }
            // E2-G01 (N04, FR-CAP-13): `ReaderPolling.captureStatus` (WP4's file, out of this
            // package's row) does not know about the session's own v7 mode/route columns —
            // re-derive the Input/Radio rows here, against the same live `InputStatus`/`RigStatus`
            // holders that call already read, once this session's own `SessionRouteFacts` are
            // known, rather than editing a file outside this package's ownership.
            val routeFacts = routeFactsReader.forSession(effectiveSessionId)
            state = if (routeFacts == SessionRouteFacts.NOT_TRACKED) {
                baseState
            } else {
                baseState.copy(
                    input = CaptureStatusMapper.inputFacts(InputStatus.state, SystemClock.wallMillis(), routeFacts),
                    radio = CaptureStatusMapper.radioFacts(RigStatus.state, routeFacts),
                )
            }
            liveBar = if (CaptureState.isCapturing) LiveBarPolling.current(context, effectiveSessionId) else null
            // R-039: read alongside the status, at the same 2 s cadence — LevelStatus is a
            // process-wide holder (like every other capture signal here), not session-scoped,
            // so it is safe (and cheap) to sample every tick regardless of which sub-screen is
            // showing, rather than starting a second poll loop when the meter opens.
            levelState = currentLevelViewState(context, effectiveSessionId)
            // R-1007 (WPL): the same cadence again — [LiveMonitorScreen] is a sub-screen of this
            // same destination (like the level meter above it), not a second poll loop.
            liveMonitorOvers = LiveMonitorOversPolling.current(context, effectiveSessionId)
            hearingText = effectiveSessionId?.let { LiveBarPolling.newestPassAPartial(context, it) }
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    when (sub) {
        CaptureStatusSubScreen.LEVEL_METER ->
            LevelMeterScreen(
                state = levelState,
                modifier = modifier,
                liveBar = liveBar,
                onBack = { sub = CaptureStatusSubScreen.NONE },
            )

        // R-1007 (WPL): design-intent N07 — reached from this screen's own embedded live bar
        // (`onOpenLive` below, the seam this row landed on) and, via `openLiveMonitor`, from the
        // pinned bar's host-level copy on every other destination (`OrtNavHost.kt`'s own route).
        CaptureStatusSubScreen.LIVE_MONITOR ->
            LiveMonitorScreen(
                status = state,
                level = levelState,
                hearingText = hearingText,
                overs = liveMonitorOvers,
                localMicrophone = liveBar?.localMicrophone ?: false,
                modifier = modifier,
                actions = LiveMonitorActions(
                    onBack = { sub = CaptureStatusSubScreen.NONE },
                    onOpenOver = onOpenOver,
                    onOpenFullLog = onOpenFullLog,
                    onStop = { stopCapture(context) },
                ),
            )

        CaptureStatusSubScreen.NONE ->
            CaptureStatusScreen(
                state = state,
                modifier = modifier,
                liveBar = liveBar,
                onStop = { stopCapture(context) },
                onOpenLive = { sub = CaptureStatusSubScreen.LIVE_MONITOR },
                onOpenLevel = { sub = CaptureStatusSubScreen.LEVEL_METER },
            )
    }
}

/** R-1007 (WPL): [CaptureStatusContent]'s own initial `sub` value — split out purely to keep that
 * composable under detekt's `LongMethod` limit. `openLevelMeter` wins over `openLiveMonitor` when
 * (implausibly) both are ever true at once, matching the order the two `Boolean` parameters are
 * declared in — neither caller in this codebase ever sets both. */
private fun initialSubScreen(openLevelMeter: Boolean, openLiveMonitor: Boolean): CaptureStatusSubScreen = when {
    openLevelMeter -> CaptureStatusSubScreen.LEVEL_METER
    openLiveMonitor -> CaptureStatusSubScreen.LIVE_MONITOR
    else -> CaptureStatusSubScreen.NONE
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
    input = InputStatus.state,
    level = LevelStatus.state,
    nowMillis = SystemClock.wallMillis(),
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

/** [LevelStatus.state]/[LevelStatus.peakHistoryDbfs] read together, matching that object's own
 * "always read alongside each other" rule (see its kdoc), with the same device-name convention
 * the Input row uses. [weakestOverLabel] (R-175) is `null` — an honest absence, not a query
 * failure — whenever there is no live session to read tonight's overs from. */
private suspend fun currentLevelViewState(context: Context, sessionId: String?): LevelViewState =
    LevelViewStateMapper.from(
        level = LevelStatus.state,
        history = LevelStatus.peakHistoryDbfs,
        inputLabel = levelInputLabel(),
        weakestOverLabel = sessionId?.let { ReaderPolling.weakestOverLabel(context, it) },
        clippedSamplesThisSession = LevelStatus.clippedSamplesThisSession,
    )

private fun levelInputLabel(): String {
    val deviceName = when (val input = InputStatus.state) {
        InputStatus.State.None -> null
        is InputStatus.State.Opened -> input.descriptor.label
        is InputStatus.State.Mismatch -> input.expected.label
        is InputStatus.State.Lost -> input.lastKnown.descriptor.label
    }
    return if (deviceName != null) "$deviceName · last 60 s" else "last 60 s"
}
