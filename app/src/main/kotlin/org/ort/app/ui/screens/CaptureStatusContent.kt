package org.ort.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ort.app.ui.data.CaptureStatusMapper
import org.ort.app.ui.data.CaptureStatusViewState
import org.ort.app.ui.data.CaptureStoragePolling
import org.ort.app.ui.data.CaptureStorageViewState
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

/**
 * The Capture destination's polling wrapper (ui-conformance-plan WP4, R-031/R-032/R-034/R-035/
 * R-038/R-039; N08/WPCAP) — reachable from the drawer's Capture row (`OrtNavHost.kt`, WP3,
 * dispatches `ReaderDestination.CAPTURE` here).
 *
 * **N08 (design-intent, `Capture.dc.html`): renders [CaptureScreen], the one merged surface that
 * supersedes [CaptureStatusScreen] (N04), [LevelMeterScreen] (N06) and [LiveMonitorScreen] (N07) as
 * *separate* destinations** — every fact those three screens polled (status, level, this session's
 * overs) is still polled here, at the identical 2 s cadence, and handed to the one screen instead
 * of switched between three. [LevelMeterScreen]/[LiveMonitorScreen] and their own tests are
 * untouched (P9 — nothing deleted quietly); they are simply no longer reachable from this
 * composable's own internal state.
 *
 * [openLevelMeter]/[openLiveMonitor] are kept as accepted parameters purely so
 * `OrtNavHost.kt`'s existing call site (`Settings-Capture`'s `Meter` action, the pinned live bar's
 * tap) keeps compiling unchanged — landing on `ReaderDestination.CAPTURE` at all already puts the
 * operator on the one surface that carries both the level and the live overs inline, so neither
 * flag changes what renders any more.
 *
 * R-172: [sessionId] is a fallback only, and the poll loop is unconditional (never gated on
 * [sessionId] being non-null) — see [ReaderPolling.effectiveSessionId]'s own kdoc. Before this
 * fix, a `null` [sessionId] at first composition meant this screen would never start polling at
 * all, even once a session genuinely started capturing later in the same composition (R-171's
 * `overnight-live` scenario, broadcast while the reader is already open); a non-null [sessionId]
 * that later stopped matching the live session meant it kept polling the *wrong* one forever
 * instead of falling back to the honest idle facts. Both are fixed by resolving the session to
 * read fresh, every tick, rather than once.
 */
@Composable
public fun CaptureStatusContent(
    context: Context,
    sessionId: String?,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") openLevelMeter: Boolean = false,
    @Suppress("UNUSED_PARAMETER") openLiveMonitor: Boolean = false,
    // R-1007 (WPL): `Live-Monitor.dc.html`'s own "Over row → D01–D04" — defaulted to a no-op so
    // every existing caller (this file's own tests included) keeps compiling; `OrtNavHost.kt`
    // wires it to its real cross-destination navigation. `onOpenFullLog` (N07's own `Full log`)
    // has no seam left to wire on N08's merged surface — see this package's own report.
    onOpenOver: (String) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onOpenFullLog: () -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()
    // Register R-1051 (halt, constitution I/IV): the real "not yet known" seed — before this fix,
    // this was `idleCaptureStatus()` directly, a real "Not capturing" claim that read false on a
    // cold start whose session (per `ReaderPolling.effectiveSessionId`, resolved fresh every tick
    // below) turns out to already be capturing. `loadingCaptureStatus()` is the honest placeholder
    // the first poll's `state = ...` assignment below replaces.
    var state by remember { mutableStateOf(loadingCaptureStatus()) }
    var liveMonitorOvers by remember { mutableStateOf(LiveMonitorOversViewState.EMPTY) }
    var hearingText by remember { mutableStateOf<String?>(null) }
    var storageState by remember { mutableStateOf(CaptureStorageViewState.LOADING) }
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
            // R-039: read alongside the status, at the same 2 s cadence — LevelStatus is a
            // process-wide holder (like every other capture signal here), not session-scoped.
            levelState = currentLevelViewState(context, effectiveSessionId)
            // R-1007 (WPL): the same cadence again — this session's overs are now inline on N08,
            // not a second poll loop gated on a sub-screen being open.
            liveMonitorOvers = LiveMonitorOversPolling.current(context, effectiveSessionId)
            hearingText = effectiveSessionId?.let { LiveBarPolling.newestPassAPartial(context, it) }
            // D40/D39 (FR-STO-3e/3f, AC-156..160): the over-audio warning and the archive
            // disclosure, at the same 2 s cadence — real, recomputed every read, never cached
            // past a single poll (constitution VI/AC-157).
            storageState = CaptureStoragePolling.current(context)
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    CaptureScreen(
        status = state,
        level = levelState,
        hearingText = hearingText,
        overs = liveMonitorOvers,
        storage = storageState,
        modifier = modifier,
        onStop = { stopCapture(context) },
        onOpenOver = onOpenOver,
        onTurnOffArchive = {
            coroutineScope.launch { CaptureStoragePolling.setArchiveEnabled(context, !storageState.archive.enabled) }
        },
    )
}

/** `Stop` (R-032): the same `ACTION_STOP` [RealCaptureService.onStartCommand] already handles —
 * no new capture-control path, just the existing one reached from this screen. */
private fun stopCapture(context: Context) {
    val intent = Intent(context, RealCaptureService::class.java).setAction(RealCaptureService.ACTION_STOP)
    context.startService(intent)
}

/**
 * Register R-1051 (halt, constitution I/IV): the real "not yet known" seed — `idleCaptureStatus()`
 * itself is a genuine, honest claim ("nothing is capturing"), which is exactly why it must not
 * double as this composable's own pre-first-poll placeholder (see [CaptureStatusViewState.loading]'s
 * own kdoc). Built from [idleCaptureStatus] purely for a valid, inert set of field values — every
 * one of them is replaced by the first real poll before [CaptureStatusScreen] ever reads them,
 * since that screen renders [LoadingState] instead of its normal body while [loading] is `true`.
 */
private fun loadingCaptureStatus(): CaptureStatusViewState = idleCaptureStatus().copy(loading = true)

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
