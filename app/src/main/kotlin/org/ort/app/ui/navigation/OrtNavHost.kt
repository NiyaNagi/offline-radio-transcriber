package org.ort.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ort.app.fieldreport.recorder.FieldReportRecorder
import org.ort.app.fieldreport.recorder.RecorderDestination
import org.ort.app.ui.audio.RealTransmissionAudioPlayer
import org.ort.app.ui.audio.TransportPlaybackController
import org.ort.app.ui.components.LiveBarViewState
import org.ort.app.ui.components.ScreenHeader
import org.ort.app.ui.components.TransportBar
import org.ort.app.ui.components.TransportBarActions
import org.ort.app.ui.components.TransportBarViewState
import org.ort.app.ui.components.formatTransportBarTime
import org.ort.app.ui.components.safeAreaBottomPadding
import org.ort.app.ui.data.DrawerCounts
import org.ort.app.ui.data.DrawerCountsViewState
import org.ort.app.ui.data.FrequencyDetailView
import org.ort.app.ui.data.LiveBarPolling
import org.ort.app.ui.data.LogFilterSelection
import org.ort.app.ui.data.ReaderPolling
import org.ort.app.ui.data.SearchFacetFilter
import org.ort.app.ui.data.SearchFilterInput
import org.ort.app.ui.data.SearchFilterParser
import org.ort.app.ui.data.SearchPolling
import org.ort.app.ui.data.SearchResult
import org.ort.app.ui.data.SearchTimeFilter
import org.ort.app.ui.data.StationSubScreen
import org.ort.app.ui.data.ThreadDetailViewState
import org.ort.app.ui.data.ThreadPolling
import org.ort.app.ui.data.TimeWindow
import org.ort.app.ui.failures.FailureHost
import org.ort.app.ui.failures.FailureHostActions
import org.ort.app.ui.screens.CaptureStatusContent
import org.ort.app.ui.screens.FrequenciesContent
import org.ort.app.ui.screens.FrequencyDetailContent
import org.ort.app.ui.screens.LogContent
import org.ort.app.ui.screens.NowContent
import org.ort.app.ui.screens.SearchContent
import org.ort.app.ui.screens.StationDetailContent
import org.ort.app.ui.screens.StationsContent
import org.ort.app.ui.screens.ThreadContent
import org.ort.app.ui.screens.ThreadDetailScreen
import org.ort.app.ui.screens.TransmissionDetailContent
import org.ort.app.ui.settings.SettingsScreenId
import org.ort.app.ui.theme.OrtSpacing
import org.ort.core.AttributionState
import org.ort.core.SystemClock
import org.ort.data.Band
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.RigStatus

private const val POLL_INTERVAL_MILLIS = 2_000L

/** C10: the transport bar's own playback-position poll cadence — matches
 * `TransmissionDetailScreen.kt`'s former per-screen `POSITION_POLL_MILLIS` (now hoisted here,
 * `transportPlayback`'s own doc comment) rather than [POLL_INTERVAL_MILLIS]'s coarser 2s cadence,
 * which would make the scrub bar visibly stutter. */
private const val TRANSPORT_POLL_INTERVAL_MILLIS = 150L

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
 * IA-3 (information-architecture review, approved — WPNAV): the one shape every filtered-Log
 * entry point now shares — which drill-in, if any, to reopen when the operator backs out of the
 * filtered Log, generalising the R-276 mechanism that already worked for [Frequency] alone to
 * [Station] (ST02) and [Capture] (N07, `Live-Monitor.dc.html`'s own `Full log`) too, rather than
 * building a second, parallel mechanism for each. [None] is a plain, ordinary reach (the drawer
 * row) — back behaves exactly as leaving any other destination does, no restore at all.
 */
private sealed interface LogFilterOrigin {
    data object None : LogFilterOrigin
    data class Frequency(val frequencyHz: Long) : LogFilterOrigin
    data class Station(val stationId: String) : LogFilterOrigin
    data object Capture : LogFilterOrigin

    // R-1041 (N01): `Main.dc.html`'s chart bar — back returns to `Now`, the one destination this
    // origin is ever opened from.
    data object Now : LogFilterOrigin

    // R-1041 (D11): `Detail-Propagated.dc.html`'s "View the N affected overs" — back reopens the
    // transmission drill-in itself (its own `Main` sub-state, the same "root, not the exact
    // sub-screen it was opened from" precedent [Frequency]'s own doc comment already establishes),
    // never the momentary `Propagated` sub-state, which is this package's own private,
    // reconstructed-on-demand `PropagationOutcome` and not worth threading back through a Bundle.
    data class Transmission(val transmissionId: String) : LogFilterOrigin

    // R-1041 (R04): `Improve-Done`'s "Review the N changes" — back reopens `Improve records` at
    // its own list root, the same "root, not the exact sub-screen" precedent as [Transmission]
    // (`ui/improve`'s own `ImprovePage.Done` is that package's private, unexported state).
    data object Improve : LogFilterOrigin

    // WPRC02 (RC02, `Recording-Session.dc.html`): the session's own "Log" link — back reopens that
    // one session's own drill-in, the same "root of the thing this came from" precedent every other
    // case here already follows.
    data class RecordingSession(val sessionId: String) : LogFilterOrigin
}

/**
 * R-129's own crash class, again: [LogFilterOrigin] is a plain sealed type, not directly
 * Bundle-saveable under a real `SaveableStateRegistry` — the identical reason this file's own
 * [SearchFilterInputSaver] and `LogContent.kt`'s `LogQuickFilterIdSaver` each need one. Encoded the
 * same way `LogQuickFilterIdSaver` already is (a plain, delimiter-free discriminator string, since
 * every payload here is itself delimiter-safe — a `Long` or a station id, never free text).
 */
private val LogFilterOriginSaver: Saver<LogFilterOrigin, String> = Saver(
    save = { origin ->
        when (origin) {
            LogFilterOrigin.None -> "None"
            LogFilterOrigin.Capture -> "Capture"
            LogFilterOrigin.Now -> "Now"
            LogFilterOrigin.Improve -> "Improve"
            is LogFilterOrigin.Frequency -> "Frequency:${origin.frequencyHz}"
            is LogFilterOrigin.Station -> "Station:${origin.stationId}"
            is LogFilterOrigin.Transmission -> "Transmission:${origin.transmissionId}"
            is LogFilterOrigin.RecordingSession -> "RecordingSession:${origin.sessionId}"
        }
    },
    restore = { encoded ->
        when {
            encoded == "None" -> LogFilterOrigin.None
            encoded == "Capture" -> LogFilterOrigin.Capture
            encoded == "Now" -> LogFilterOrigin.Now
            encoded == "Improve" -> LogFilterOrigin.Improve
            encoded.startsWith("Frequency:") ->
                encoded.removePrefix("Frequency:").toLongOrNull()?.let { LogFilterOrigin.Frequency(it) }
                    ?: LogFilterOrigin.None
            encoded.startsWith("Station:") -> LogFilterOrigin.Station(encoded.removePrefix("Station:"))
            encoded.startsWith("Transmission:") ->
                LogFilterOrigin.Transmission(encoded.removePrefix("Transmission:"))
            encoded.startsWith("RecordingSession:") ->
                LogFilterOrigin.RecordingSession(encoded.removePrefix("RecordingSession:"))
            else -> LogFilterOrigin.None // an unrecognised saved value never crashes restore.
        }
    },
)

/**
 * The navigation host (build-plan P13, extended by P14, P17 and ui-conformance-plan WP3): the
 * drawer `Menu.dc.html` specifies, wrapping whichever destination is current, under WP2's
 * [ScreenHeader] (R-003/R-004/R-015 — drawer icon, live dot + elapsed, search icon; no title text,
 * each screen owns its own). A drill-in draws its own header instead (R-016) — every real
 * drill-in content composable WP5/6/8 shipped already renders WP2's `DrillInHeader` internally,
 * so this host does not add a second one (see [NavHostBody]'s own comment). WP2's [LiveBar] is
 * pinned to the bottom of every destination and drill-in while a session runs (R-022's consumer),
 * except `Now`/`Capture`, which pin their own the same way. Every real destination and drill-in
 * dispatches to the package that owns it (`Now`/`Capture`/live-bar feed → WP4; `Search` → WP7;
 * `Log`/`Threads`/transmission and thread drill-ins → WP5/WP6; `Stations`/`Frequencies` and their
 * drill-ins → WP8; `Settings`/`Earlier nights`/`Improve records` → WP10).
 *
 * [sessionId] is null when the host is opened with no active or prior capture session (for
 * example, opened directly rather than from the status flow) - `Now`/`Log` then show their
 * empty/idle state rather than polling a session that does not exist.
 *
 * [failureActions] (round 11, register R-334: [org.ort.app.ui.failures.FailureHost] now mounts
 * *inside* this composable — see the `Scaffold` block's own doc comment for why): the recovery
 * callbacks `ReaderActivity.kt` used to build and hand straight to its own `FailureHost` call.
 * `FailureHost`'s own signature is unchanged; only where it is called from moved.
 *
 * [seed] (round 13, WP12's screenshot-tour seam — see [NavSeed]'s own doc comment for the full
 * rationale and what is deliberately left out): seeds [NavHostNavState]'s own drill-in ids and
 * companion fields exactly once, on first composition, the same `rememberSaveable`-first-read
 * contract every one of those fields already had for a real tap. `null` (the default) changes
 * nothing — every existing caller keeps compiling and behaving unchanged. Also flows into
 * [navigator]'s own default construction (`rememberReaderNavigator(seed = seed)`) so a caller that
 * hands this composable a `seed` without building its own `navigator` still gets the right
 * destination/Settings-screen, not only the drill-in ids — a caller that builds its own
 * `navigator` (`ReaderActivity.kt`, which never passes `seed` here at all) is unaffected either
 * way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun OrtNavHost(
    sessionId: String?,
    seed: NavSeed? = null,
    navigator: ReaderNavigator = rememberReaderNavigator(seed = seed),
    failureActions: FailureHostActions = FailureHostActions(),
) {
    val context = LocalContext.current
    // WP12 (screenshot-tour gap, register R-010..R-014/R-334): `seed.openDrawer` starts the drawer
    // open on first composition — `NavSeed.openDrawer`'s own doc comment for why this, not a
    // synthesised tap on the header's drawer icon.
    val drawerState = rememberDrawerState(if (seed?.openDrawer == true) DrawerValue.Open else DrawerValue.Closed)
    // R-803 (halt, coordinator-approved cross-boundary — screenshot-tour package, this round):
    // mirrors the drawer's real open/closed state into `navigator.drawerOpenState` so a caller with
    // no composition of its own (`ScreenshotTourActivity`, which only ever gets `navigator` back,
    // never `drawerState`) can poll whether a just-composed screen's drawer has actually settled to
    // the state it asked for, instead of capturing on elapsed time alone (the exact bug class behind
    // `mode-local-mic/ST01`/`mode-change-pending/CF11`/... capturing the open drawer over the right
    // destination).
    LaunchedEffect(drawerState, navigator) {
        snapshotFlow { drawerState.isOpen }.collect { navigator.drawerOpenState.value = it }
    }
    val scope = rememberCoroutineScope()
    val navState = rememberNavHostNavState(seed)
    // R-840: mirrors `NavHostNavState.reviewSessionView` the same way the drawer's own open/closed
    // state is mirrored above — the second live fact `ScreenshotTourActivity`'s settle-wait needs
    // once a step's own seed carries `pendingReviewSessionId`.
    LaunchedEffect(navState, navigator) {
        snapshotFlow { navState.reviewSessionView.value }.collect { navigator.reviewSessionViewState.value = it }
    }
    // Hoisted into `navigator` (round 3) so `ReaderActivity`'s `FailureHostActions`, mounted above
    // this composable, can also switch destinations — see `ReaderNavigator.kt`'s own doc comment.
    var current by navigator.currentState
    val drawerLive = rememberDrawerLiveState(sessionId, context)
    // R-910/R-911: mirrors `drawerLive.liveBar` — the same real `LiveBarPolling` fact `Now`/`Capture`
    // read again through their own, separately-polled embedded bar (`embedsOwnLiveBar` below leaves
    // this host's own *rendered* bar `null` there, but the underlying "is there a live bar's worth of
    // real state" fact this mirrors is destination-independent), the same way the drawer's own
    // open/closed state is mirrored above. `ScreenshotTourActivity`'s settle-wait reads this on a
    // step whose scenario is genuinely live, so a capture never lands on the one frame before the bar
    // has appeared — the exact race class `drawerOpenState` already closed for the drawer.
    LaunchedEffect(drawerLive.liveBar, navigator) {
        navigator.liveBarState.value = drawerLive.liveBar
    }
    val transportPlayback = rememberTransportPlayback(context, drawerLive)

    OrtNavHostBackHandler(current, navigator, navState, drawerState, scope)

    // Register R-1061 (round 2): the host's own pinned live/transport bar's real, laid-out height
    // — never a guessed constant (round 1's own `LIVE_BAR_RESERVE_HEIGHT = 96.dp` was exactly that
    // guess, replaced this round after the coordinator's own review named it one) — reported by
    // [NavHostBody] itself via `onLiveBarHeightChanged` the moment the bar's own `Box` is measured
    // (the same `onGloballyPositioned` mechanism [org.ort.app.ui.failures.FailureHost] already uses
    // for the banner's own height, R-178), and reset to `0.dp` the instant the bar stops showing —
    // see [NavHostBody]'s own doc comment on exactly where. Zero on the very first frame before
    // anything has been measured, matching the same one-frame lag `contentTopPadding` itself
    // already has for the banner.
    var liveBarHeight by remember { mutableStateOf(0.dp) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ReaderDrawerContent(
                current = current,
                sessionHeader = drawerLive.sessionHeader,
                storage = drawerLive.storage,
                badges = drawerLive.badges,
                counts = drawerLive.counts,
                improveRecordsCount = drawerLive.improveRecordsCount,
                onSelect = drawerOnSelect(navigator.currentState, navState, drawerState, scope),
            )
        },
    ) {
        Scaffold { padding ->
            // Register R-334 (halt): `FailureHost` used to mount in `ReaderActivity.kt`, wrapping
            // this entire composable — `ModalNavigationDrawer` included — so its own banner overlay
            // painted *above* the drawer's own content slot: opaque, inside the drawer panel itself
            // once open, hiding six of its nine rows (`storage-warn/N00-menu-with-banner-pass3.png`).
            // Moved to wrap only this `Scaffold`'s own content slot instead — *inside*
            // `ModalNavigationDrawer`'s main-content lambda, never its `drawerContent` — so
            // Material3's own drawer scrim (no custom dim value needed; it already sits above every
            // other destination's content the identical way) now sits above the banner too, and the
            // drawer panel stays crisp on top of both. `org.ort.app.ui.failures.FailureHost`'s own
            // signature did not need to change — only where this file calls it from did;
            // `ReaderActivity.kt`'s own doc comment records the matching half of this move.
            FailureHost(
                sessionId = sessionId,
                actions = failureActions,
                // Register R-1061: see this file's own `liveBarHeight` doc comment.
                reservedBottomHeight = liveBarHeight,
                // R-1003 (halt): `padding` already reflects this `Scaffold`'s own default
                // `contentWindowInsets` (`WindowInsets.systemBars` — confirmed by decompiling
                // `ScaffoldDefaults`/`SystemBarsDefaultInsets_androidKt` for this row; this
                // package's own report has the exact bytecode evidence), applied here as plain
                // numeric padding, which does **not** register with Compose's own window-insets
                // consumption tracking (`ModifierLocalConsumedWindowInsets`, internal to
                // `androidx.compose.foundation.layout`) the way `Modifier.windowInsetsPadding` does.
                // `consumeWindowInsets(padding)` marks that same span consumed for the subtree
                // below, so `NavHostBody`'s own `safeAreaBottomPadding()` (its own doc comment)
                // reserves real space only where none has already been reserved, never doubling on
                // top of what this padding already draws.
                modifier = Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize(),
            ) { contentTopPadding ->
                NavHostBody(
                    layout = NavHostLayout(
                        modifier = Modifier.fillMaxSize().safeAreaBottomPadding(),
                        contentTopPadding = contentTopPadding,
                        onLiveBarHeightChanged = { liveBarHeight = it },
                    ),
                    ids = NavHostIds(
                        current,
                        navState.openedFrom.value,
                        navState.openTransmissionId.value,
                        navState.openStationId.value,
                        navState.openFrequencyHz.value,
                        navState.openThreadId.value,
                        DestinationInitialState(
                            navigator.settingsScreenState.value,
                            navState.openCaptureLevelMeter.value,
                            navState.openCaptureLiveMonitor.value,
                            navState.pendingLogFilter.value,
                            navState.pendingReviewSessionId.value,
                            seed?.logSheetOpen ?: false,
                            navState.reviewSessionView.value,
                            navState.openRecordingSessionId.value,
                        ),
                        navState.frequencyInitialView.value,
                        navState.openStationSubScreen.value,
                        seed?.openTransmissionRevisions ?: false,
                    ),
                    callbacks = navHostCallbacks(navigator, scope, drawerState, navState),
                    sessionId = sessionId,
                    context = context,
                    drawerLive = drawerLive,
                    transportPlayback = transportPlayback,
                    search = searchHostState(navigator, context, scope, navState, seed),
                )
            }
        }
    }
}

/**
 * Register R-333 (halt): the host had no generic `BackHandler` at all — system back exited the
 * whole app instead of popping one level, reproduced from the open drawer, any drill-in (including
 * the rejected-detail case, F04 — the same `openTransmissionId` drill-in every other `Log` row
 * already uses, confirmed by reading `LogScreen.kt`'s own `RejectedRow(onClick = { onOpen(item.id)
 * })` before relying on it), and round 9's own `Log`-via-`Frequency-Change` route. Priority matches
 * the coordinator's own brief exactly: close the drawer, then pop the drill-in, only then let the
 * platform's own back behavior (finishing the Activity) proceed — one `when`, not two competing
 * `BackHandler`s, so there is no ambiguity about which wins when more than one condition holds at
 * once (the drawer open *over* a `Log` reached this way, say). Split out of [OrtNavHost] purely to
 * keep that function under detekt's `LongMethod` limit — the same reason [NavHostBody]/
 * [DestinationContent] were themselves split out before this round.
 *
 * Round 11 addendum, register R-133: [navigator] is a new parameter, needed only for
 * [canReturnToSettingsStorage] below — `Settings-Storage`'s "Next deletion … Review" link
 * (`SettingsContent.onReviewSession`, WP10's `948fe55`) opens `Earlier nights` seeded on one
 * session (WP10's `e390c60`, `SessionsContent.initialSessionId`); that composable's own doc comment
 * states plainly it has no way to express "back goes to `Settings-Storage`, not my own list" —
 * "a host that needs 'back to `Settings-Storage`' instead wraps this composable with its own
 * header/back at the call site" — so this handler owns that restore the same way it already owns
 * [canReturnToLogOrigin]'s.
 *
 * **Known gap, reported, not fixed here**: the `Log` filter sheet (`LogContent.kt`, L02) and
 * `Search`'s own filter sheet (`SearchContent.kt`) are each a private, un-exported boolean
 * (`sheetOpen`/`filtersSheetOpen`) this host cannot see or close, and neither is built on Compose's
 * `ModalBottomSheet` — confirmed by grepping the whole `ui` tree for it, which found zero uses in this
 * codebase, so neither already auto-handles system back either (the L02 halt reproduction itself is
 * direct proof: `ModalBottomSheet` installs its own `BackHandler` when shown, and system back there
 * still exited the app). System back while either is open still reaches this handler, finds nothing
 * here it can see to pop, and still exits the app. Each needs its own small, local
 * `BackHandler(enabled = sheetOpen) { sheetOpen = false }` inside its own file — WP5's and WP7's
 * respectively, not this row's to add — see this round's own report.
 */
@Composable
private fun OrtNavHostBackHandler(
    current: ReaderDestination,
    navigator: ReaderNavigator,
    navState: NavHostNavState,
    drawerState: DrawerState,
    scope: CoroutineScope,
) {
    val isDrillInOpen = navState.openTransmissionId.value != null ||
        navState.openStationId.value != null ||
        navState.openFrequencyHz.value != null ||
        navState.openThreadId.value != null ||
        navState.openRecordingSessionId.value != null
    // IA-3 (generalises round 9/10's own register R-276): `Log` reached via [NavHostNavState
    // .openLogFiltered] — a drill-in-shaped pop in every way that matters here, kept as its own
    // named condition only because it is not one of the four ids [isDrillInOpen] already checks.
    val canReturnToLogOrigin = current == ReaderDestination.LOG &&
        navState.logFilterOrigin.value != LogFilterOrigin.None
    // Round 11, register R-133: `Earlier nights` reached via `Settings-Storage`'s `Review` link —
    // see this function's own doc comment above.
    val canReturnToSettingsStorage = current == ReaderDestination.EARLIER_NIGHTS &&
        navState.pendingReviewSessionId.value != null

    BackHandler(
        enabled = drawerState.isOpen || isDrillInOpen || canReturnToLogOrigin || canReturnToSettingsStorage,
    ) {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            isDrillInOpen -> navState.closeDrillIns()
            canReturnToLogOrigin -> {
                val origin = navState.logFilterOrigin.value
                navState.logFilterOrigin.value = LogFilterOrigin.None
                navState.pendingLogFilter.value = null
                restoreLogFilterOrigin(origin, navigator, navState)
            }
            canReturnToSettingsStorage -> {
                navState.pendingReviewSessionId.value = null
                navigator.openSettings(SettingsScreenId.STORAGE)
            }
        }
    }
}

/** [OrtNavHostBackHandler]'s own `canReturnToLogOrigin` restore, split out purely to keep that
 * function under detekt's `CyclomaticComplexMethod` limit — a plain data move, not a behaviour
 * change. Each branch is "reopen the one destination or drill-in [origin] was reached from," at
 * its own root/`Main` sub-state, never the exact sub-screen it opened from (see each
 * [LogFilterOrigin] case's own doc comment for why). */
private fun restoreLogFilterOrigin(origin: LogFilterOrigin, navigator: ReaderNavigator, navState: NavHostNavState) {
    when (origin) {
        is LogFilterOrigin.Frequency -> {
            navState.openedFrom.value = ReaderDestination.LOG
            navState.frequencyInitialView.value = FrequencyDetailView.Change
            navState.openFrequencyHz.value = origin.frequencyHz
        }
        is LogFilterOrigin.Station -> {
            navState.openedFrom.value = ReaderDestination.LOG
            navState.openStationId.value = origin.stationId
        }
        LogFilterOrigin.Capture -> {
            navigator.currentState.value = ReaderDestination.CAPTURE
            navState.openCaptureLiveMonitor.value = true
        }
        // R-1041 (N01): back to `Now` itself — the one destination this origin is ever opened from.
        LogFilterOrigin.Now -> navigator.currentState.value = ReaderDestination.NOW
        // R-1041 (D11): reopens the transmission drill-in at its own `Main` sub-state — see
        // [LogFilterOrigin.Transmission]'s own doc comment for why not the exact `Propagated`
        // sub-state.
        is LogFilterOrigin.Transmission -> {
            navState.openedFrom.value = ReaderDestination.LOG
            navState.openTransmissionId.value = origin.transmissionId
        }
        // R-1041 (R04): reopens `Improve records` at its own list root — see
        // [LogFilterOrigin.Improve]'s own doc comment for why not the exact `Done` sub-state.
        LogFilterOrigin.Improve -> navigator.currentState.value = ReaderDestination.IMPROVE_RECORDS
        // WPRC02: reopens the one session's own drill-in — `EARLIER_NIGHTS` is where RC02 lives
        // (RC01 is that destination's own ordinary root; see `EarlierNightsDestinationContent`'s
        // own doc comment for why the constant itself was not renamed).
        is LogFilterOrigin.RecordingSession -> {
            navState.openedFrom.value = ReaderDestination.LOG
            navigator.currentState.value = ReaderDestination.EARLIER_NIGHTS
            navState.openRecordingSessionId.value = origin.sessionId
        }
        LogFilterOrigin.None -> Unit
    }
}

/**
 * Every piece of navigation state [OrtNavHost] owns beyond `navigator`'s own `current`/
 * `settingsScreenState` — drill-in ids, each one's `openedFrom`/`searchOpenedFrom` origin, and
 * WP7's lifted `Search` input/result (see that field's own doc comment on [OrtNavHost] for why it
 * lives here and not inside `SearchContent`). Bundled into one [MutableState]-holding object,
 * rather than left as separate `var ... by remember` locals in [OrtNavHost] itself, purely so
 * [navHostCallbacks] and [searchHostState] can be pulled out to their own top-level functions —
 * the same reason [DrawerLiveState] and [NavHostIds]/[NavHostCallbacks] already exist — and so
 * keep [OrtNavHost] itself under detekt's `LongMethod` limit.
 */
private data class NavHostNavState(
    val openedFrom: MutableState<ReaderDestination>,
    val searchOpenedFrom: MutableState<ReaderDestination>,
    val openTransmissionId: MutableState<String?>,
    val openStationId: MutableState<String?>,
    val openFrequencyHz: MutableState<Long?>,
    val openThreadId: MutableState<String?>,
    val searchInput: MutableState<SearchFilterInput>,
    val searchResult: MutableState<SearchResult?>,
    // Round 6, register R-132: whether `Capture` should land directly on `LevelMeterScreen` the
    // next time it is freshly composed — see `CaptureStatusContent.openLevelMeter`'s own doc
    // comment for the "opens there on launch" contract this mirrors.
    val openCaptureLevelMeter: MutableState<Boolean>,
    // R-1007 (WPL, register): whether `Capture` should land directly on `LiveMonitorScreen` the
    // next time it is freshly composed — the identical "opens there on launch" contract
    // [openCaptureLevelMeter] already has, set by the pinned live bar's own tap
    // (`navHostCallbacks`'s `onOpenCapture`) rather than a Settings action.
    val openCaptureLiveMonitor: MutableState<Boolean>,
    // Round 9, register R-276: the filter `LogContent` should seed on its own next fresh
    // composition — see [openLogFiltered]. A plain `remember`, not `rememberSaveable`:
    // it only has to survive until `LogContent`'s own `initialFilter` read, which happens
    // synchronously within the same process this tap fired in — by the time a `recreate()` could
    // ever observe this, `LogContent`'s *own* `rememberSaveable` `selection` has already captured
    // the value into the `SaveableStateRegistry` (the same reasoning `searchResult` below rests
    // on: WP7's own doc comment on why `SearchResult` needs no Saver of its own).
    val pendingLogFilter: MutableState<LogFilterSelection?>,
    // IA-3 (generalises round 9's own register R-276): the drill-in, if any, to reopen when the
    // operator backs out of a `Log` reached via [openLogFiltered] — `rememberSaveable` (via
    // [LogFilterOriginSaver]) since, unlike [pendingLogFilter], this is read only by a later user
    // action (the back gesture), which can genuinely happen after a `recreate()`.
    val logFilterOrigin: MutableState<LogFilterOrigin>,
    // Round 10, register R-276 (WP8 shipped `FrequencyDetailContent.initialView`): which of that
    // composable's own two sub-screens a freshly-opened frequency drill-in should land on —
    // `Detail` for every ordinary open (`onOpenFrequency`, this default), `Change` only when the
    // `BackHandler` above is what reopened it, so system back from the filtered `Log` lands the
    // operator on `Frequency-Change` again, not the drill-in's plain root.
    val frequencyInitialView: MutableState<FrequencyDetailView>,
    // Round 14, register R-276 follow-on (WP12 screenshot tour, coordinator round 2026-09-08,
    // WP8 shipped `StationDetailContent.initialSubScreen`): which sub-screen a freshly-opened
    // station drill-in lands on — `NONE` for every ordinary open, seeded only by [NavSeed
    // .openStationSubScreen] so the tour can capture ST03/ST04 without a real tap through
    // Overview. `rememberSaveable`, the same reasoning [frequencyInitialView] rests on.
    val openStationSubScreen: MutableState<StationSubScreen>,
    // Round 11, register R-133: the session `Earlier nights` should seed
    // `SessionsContent.initialSessionId` with, set by [openReviewSession] and read back by
    // [OrtNavHostBackHandler]'s own `canReturnToSettingsStorage` — non-null doubles as that flag
    // the same way [logFilterOrigin] doubles as both a filtered Log's return target and its
    // own "did we come this way" check. `rememberSaveable`, not a plain `remember`: unlike
    // [pendingLogFilter] (read synchronously by `LogContent`'s own first composition),
    // `SessionsContent.initialSessionId` is read on ITS first composition and this value is read
    // again later by the back gesture — the same reasoning [logFilterOrigin] itself rests on.
    val pendingReviewSessionId: MutableState<String?>,
    // R-840: which of `Earlier nights`' two screens [pendingReviewSessionId] should land on — see
    // [NavSeed.reviewSessionView]'s own doc comment. Only meaningful alongside
    // [pendingReviewSessionId], the same companion relationship [frequencyInitialView] already has
    // to [openFrequencyHz]; `rememberSaveable`, the same reasoning [frequencyInitialView] rests on.
    val reviewSessionView: MutableState<ReviewSessionView>,
    // WPRC02 (`Recording-Session.dc.html`, RC02): the session id a real tap on an RC01 row, or a
    // restored [LogFilterOrigin.RecordingSession], opened — mirrors [openStationId]'s own shape
    // exactly; RC02 is dispatched from inside `EarlierNightsDestinationContent`, not this file's
    // own four-id `NavHostDispatch` `when`, because it only ever applies while `current ==
    // ReaderDestination.EARLIER_NIGHTS` already holds (the same reason [pendingReviewSessionId]
    // is read there and not in that `when` either).
    val openRecordingSessionId: MutableState<String?>,
) {
    fun closeDrillIns() {
        openTransmissionId.value = null
        openStationId.value = null
        openFrequencyHz.value = null
        openThreadId.value = null
        // WPRC02: same reasoning as every other drill-in id here — an ordinary way of reaching
        // `Earlier nights` (the drawer row) must not resurrect a previously-opened session.
        openRecordingSessionId.value = null
        // Reset here too, not only where it is set true: this runs on every ordinary way of
        // reaching `Capture` (the drawer row, the live bar's own tap target) and must not leave a
        // stale `true` from an earlier `Settings-Capture` "Meter" tap open the meter again.
        openCaptureLevelMeter.value = false
        // R-1007: same reasoning again — an ordinary way of reaching `Capture` must not leave a
        // stale live-monitor landing behind for a later, unrelated visit.
        openCaptureLiveMonitor.value = false
        // Same reasoning, round 9 (generalised by IA-3): any ordinary way of reaching `Log` (the
        // drawer row) must not silently reapply a stale filter or resurrect a "back to the
        // frequency/station/capture" promise a normal navigation never made.
        pendingLogFilter.value = null
        logFilterOrigin.value = LogFilterOrigin.None
        // Round 10: same reasoning again — an ordinary drill-in close must not leave a stale
        // `Change` behind for whatever frequency is opened next.
        frequencyInitialView.value = FrequencyDetailView.Detail
        // Round 14: same reasoning again — an ordinary way of opening a station must not leave a
        // stale seeded sub-screen behind for whatever station is opened next.
        openStationSubScreen.value = StationSubScreen.NONE
        // Round 11, register R-133: same reasoning again — an ordinary way of reaching `Earlier
        // nights` (the drawer row) must not silently reseed a stale session or resurrect a "back
        // goes to Settings-Storage" promise a normal navigation never made.
        pendingReviewSessionId.value = null
        // R-840: same reasoning again — an ordinary way of reaching `Earlier nights` must not
        // silently reseed a stale `Digest` landing for whatever session is opened next.
        reviewSessionView.value = ReviewSessionView.SESSION
    }

    /** R-017: records which destination a drill-in opened from before running [setter], so a
     * drill-in header can say "Back to <that destination>" instead of an implicit invariant. */
    fun onOpenDrillIn(current: ReaderDestination, setter: () -> Unit) {
        openedFrom.value = current
        setter()
    }

    /**
     * IA-3 (information-architecture review, approved — WPNAV): the one filter model every one of
     * the seven Log entry points now shares — routes to `Log` filtered by [filter], recording
     * [origin] so a later back gesture can reopen whatever this was opened from, with its own state
     * intact. [closeDrillIns] first clears any live drill-in (a drill-in's id has absolute priority
     * over `current` in `NavHostBody`'s own dispatch — leaving one set would keep showing that
     * screen no matter what `current` became) and any stale pending filter from a previous trip;
     * [origin] is set *after* that clear, the same ordering [openReviewSession] below already uses.
     *
     * Generalises round 9's own R-276 mechanism (`Frequency-Change.dc.html`'s "The N overs", the
     * only one of the seven that already worked end to end and the only one whose back already
     * returned) rather than building a second, parallel one for [LogFilterOrigin.Station] (ST02) or
     * [LogFilterOrigin.Capture] (N07). [LogFilterOrigin.Frequency]'s own back still lands on
     * `FrequencyDetailContent`'s own root/`NONE` sub-screen, not the `Frequency-Change` sub-screen
     * it was opened from — that sub-screen is `FrequencyDetailContent`'s own internal, unexported
     * state, unchanged by this round, reported the same way the original R-276 round already did.
     */
    fun openLogFiltered(filter: LogFilterSelection, origin: LogFilterOrigin) {
        closeDrillIns()
        logFilterOrigin.value = origin
        pendingLogFilter.value = filter
    }

    /**
     * Round 11, register R-133 (`Settings-Storage`'s "Next deletion … Review" link,
     * `SettingsContent.onReviewSession`): routes to `Earlier nights`, seeded on [sessionId]'s own
     * detail (DG04) — the same shape as [openLogFiltered]. [closeDrillIns] first clears
     * any stale drill-in or filter state before [pendingReviewSessionId] is set, so the value it
     * leaves behind is only ever this call's own.
     */
    fun openReviewSession(sessionId: String) {
        closeDrillIns()
        pendingReviewSessionId.value = sessionId
    }
}

@Composable
private fun rememberNavHostNavState(seed: NavSeed?): NavHostNavState {
    // R-017: the destination a drill-in was opened from, so back returns there — set only when a
    // drill-in opens, read only while one is showing. Round 13: seeded from [NavSeed
    // .openedFromDestination] for a drill-in reached by seed rather than a real tap — see that
    // function's own doc comment (this is what makes a seeded D01's header read "Back to Log").
    val openedFrom = rememberSaveable { mutableStateOf(seed?.openedFromDestination() ?: ReaderDestination.NOW) }
    // R-200 (round 5): the destination `Search` was opened from, so `SearchContent`'s own
    // back-chevron (`SearchScreen.kt`'s `search-back-chevron`) returns there instead of stranding
    // the operator with no way back except the drawer, now that the host no longer draws
    // `ScreenHeader` for `SEARCH` (see `NavHostBody`). Kept separate from `openedFrom` because
    // `Search` is not a drill-in (it has its own drawer row) and reusing `openedFrom` here would
    // wrongly imply a drill-in opened under `Search` should say "Back to <wherever Search itself
    // came from>" rather than "Back to Search".
    val searchOpenedFrom = rememberSaveable { mutableStateOf(ReaderDestination.NOW) }
    // Round 13: each seeded from the matching `NavSeed` field exactly once, on first composition
    // — the same `rememberSaveable`-first-read contract a real tap already had, see [NavSeed]'s
    // own doc comment.
    val openTransmissionId = rememberSaveable { mutableStateOf(seed?.openTransmissionId) }
    val openStationId = rememberSaveable { mutableStateOf(seed?.openStationId) }
    val openFrequencyHz = rememberSaveable { mutableStateOf(seed?.openFrequencyHz) }
    // R-017: WP5's `ThreadDetailScreen` (its own file's doc comment: "not yet reachable ... ready
    // for whichever package wires that route") — this is that route, ready to open once WP5/WP7
    // expose a callback into it (see this package's report on why nothing does yet).
    val openThreadId = rememberSaveable { mutableStateOf(seed?.openThreadId) }
    // R-017 / `Flow-Search.dc.html`: Search's input and results live here, in the host, not inside
    // WP7's `SearchContent` — that composable is skipped entirely while a drill-in is showing (see
    // `NavHostBody`), and a skipped composable's own `remember` state does not survive being
    // skipped; lifting it here is what makes "back from a detail opened from Search returns to
    // Search with its filters intact" true rather than aspirational. `searchInput` is
    // `rememberSaveable` via [SearchFilterInputSaver] (built in this file, no edit to WP7's own
    // types); `searchResult` stays plain `remember` — see [SearchFilterInputSaver]'s own doc
    // comment for why `SearchResult` gets no equivalent Saver.
    val searchInput = rememberSaveable(stateSaver = SearchFilterInputSaver) { mutableStateOf(SearchFilterInput()) }
    val searchResult = remember { mutableStateOf<SearchResult?>(null) }
    val openCaptureLevelMeter = rememberSaveable { mutableStateOf(seed?.openCaptureLevelMeter ?: false) }
    // R-1007 (WPL): no `NavSeed` field yet — the screenshot tour cannot deep-link a step directly
    // onto `LiveMonitorScreen` today (this package's own report names the scenario/step it needs,
    // routed to whichever package owns `app/src/debug/**`); every ordinary reach (the pinned bar's
    // own tap) still works, seeded `false` only.
    val openCaptureLiveMonitor = rememberSaveable { mutableStateOf(false) }
    // Round 9, register R-276 — see `NavHostNavState.pendingLogFilter`/`logFilterOrigin`'s own
    // doc comments for why one is a plain `remember` and the other `rememberSaveable`.
    val pendingLogFilter = remember { mutableStateOf(seed?.pendingLogFilter) }
    // IA-3: [LogFilterOriginSaver], the same R-129 crash class every plain-sealed-type
    // `rememberSaveable` in this file already needs a custom Saver for.
    val logFilterOrigin = rememberSaveable(stateSaver = LogFilterOriginSaver) {
        mutableStateOf<LogFilterOrigin>(LogFilterOrigin.None)
    }
    // Round 10, register R-276 — a plain two-value enum, Bundle-saveable via the default Saver the
    // same way `ReaderDestination` (above) already is, no custom Saver needed.
    val frequencyInitialView = rememberSaveable {
        mutableStateOf(seed?.frequencyInitialView ?: FrequencyDetailView.Detail)
    }
    // Round 14, register R-276 follow-on — a plain four-value enum, Bundle-saveable via the
    // default Saver the same way `frequencyInitialView` above already is.
    val openStationSubScreen = rememberSaveable {
        mutableStateOf(seed?.openStationSubScreen ?: StationSubScreen.NONE)
    }
    // Round 11, register R-133 — see `NavHostNavState.pendingReviewSessionId`'s own doc comment for
    // why this is `rememberSaveable`.
    val pendingReviewSessionId = rememberSaveable { mutableStateOf(seed?.pendingReviewSessionId) }
    // R-840 — see [NavHostNavState.reviewSessionView]'s own doc comment.
    val reviewSessionView = rememberSaveable { mutableStateOf(seed?.reviewSessionView ?: ReviewSessionView.SESSION) }
    // WPRC02 — see [NavHostNavState.openRecordingSessionId]'s own doc comment. `rememberSaveable`,
    // the same reasoning [openStationId] above already rests on: a real id, plain-`String`-saveable.
    // Seeded from [NavSeed.pendingRecordingSessionId] exactly once, on first composition — the
    // same "seed once" contract every other drill-in id here already has.
    val openRecordingSessionId = rememberSaveable { mutableStateOf(seed?.pendingRecordingSessionId) }
    return NavHostNavState(
        openedFrom,
        searchOpenedFrom,
        openTransmissionId,
        openStationId,
        openFrequencyHz,
        openThreadId,
        searchInput,
        searchResult,
        openCaptureLevelMeter,
        openCaptureLiveMonitor,
        pendingLogFilter,
        logFilterOrigin,
        frequencyInitialView,
        openStationSubScreen,
        pendingReviewSessionId,
        reviewSessionView,
        openRecordingSessionId,
    )
}

/** [ReaderDrawerContent]'s own `onSelect` — split out of [OrtNavHost] purely to keep that
 * function under detekt's `LongMethod` limit; IA-5's own `Search`-row addition is what pushed it
 * over, the same reason [NavHostBody]/[DestinationContent] were themselves split out before this
 * round.
 *
 * IA-5 (information-architecture review, approved — WPNAV): `Search` now has a drawer row too
 * (`Drawer.kt`'s own doc comment) — the same `searchOpenedFrom`-before-switching invariant
 * [navHostCallbacks]'s own `onSearchDestination` (the header magnifier) already keeps, so
 * `SearchContent`'s own back-chevron returns here too, not wherever it was last left pointing.
 */
private fun drawerOnSelect(
    currentState: MutableState<ReaderDestination>,
    navState: NavHostNavState,
    drawerState: DrawerState,
    scope: CoroutineScope,
): (ReaderDestination) -> Unit = { destination ->
    if (destination == ReaderDestination.SEARCH) {
        navState.searchOpenedFrom.value = currentState.value
    }
    currentState.value = destination
    navState.closeDrillIns()
    scope.launch { drawerState.close() }
}

/** Builds [NavHostBody]'s [NavHostCallbacks] — split out of [OrtNavHost] purely to keep that
 * function under detekt's `LongMethod` limit, the same reason [NavHostBody]/[DestinationContent]
 * were themselves split out before this round. */
/** The repeated shape behind every "filter the Log and switch to it" callback below — pulled out
 * once so each call site is the one line that differs (its own [filter]/[origin]), the same
 * "extract the duplication" reason this file's own [ImproveRecordsContent] etc. already exist for,
 * and (WPREC) what keeps [navHostCallbacks] itself under detekt's `LongMethod` limit now that it
 * has six of these. */
private fun openLogAndNavigate(
    navState: NavHostNavState,
    currentState: MutableState<ReaderDestination>,
    filter: LogFilterSelection,
    origin: LogFilterOrigin,
) {
    navState.openLogFiltered(filter, origin)
    currentState.value = ReaderDestination.LOG
}

private fun navHostCallbacks(
    navigator: ReaderNavigator,
    scope: CoroutineScope,
    drawerState: DrawerState,
    navState: NavHostNavState,
): NavHostCallbacks {
    val currentState = navigator.currentState
    return NavHostCallbacks(
        onOpenDrawer = { scope.launch { drawerState.open() } },
        onSearchDestination = {
            navState.searchOpenedFrom.value = currentState.value
            currentState.value = ReaderDestination.SEARCH
        },
        onCloseDrillIns = navState::closeDrillIns,
        // R-1007 (WPL, register): the operator's own words — "clicking on it should let me see the
        // entire recording" — so the pinned bar's tap now lands on `LiveMonitorScreen` directly,
        // not the status root a second tap used to be needed to leave. `closeDrillIns()` first (its
        // own reset now includes `openCaptureLiveMonitor`, so order matters here), the flag set
        // true only after.
        onOpenCapture = {
            currentState.value = ReaderDestination.CAPTURE
            navState.closeDrillIns()
            navState.openCaptureLiveMonitor.value = true
        },
        // R-1007 (N07, generalised by IA-3): `Live-Monitor.dc.html`'s own `Full log` action — the
        // plain, unfiltered `Log`, now through the one filter model so back reopens `Capture` on
        // its own live monitor instead of discarding that context entirely (register: N07 used to
        // be the one link of the seven with no origin to restore at all).
        onOpenLog = { openLogAndNavigate(navState, currentState, LogFilterSelection(), LogFilterOrigin.Capture) },
        onOpenTransmission = { id ->
            navState.onOpenDrillIn(currentState.value) { navState.openTransmissionId.value = id }
        },
        onOpenStation = { id ->
            navState.onOpenDrillIn(currentState.value) { navState.openStationId.value = id }
        },
        // IA-3 (ST02, `Station.dc.html`'s own "Overs · Log"/"Recent overs · All N"): the station
        // drill-in's own "the rest of my overs" action — real now that `StationDetailContent`
        // wires `onViewAllOvers` through (this package's own row); `closeDrillIns()` inside
        // [NavHostNavState.openLogFiltered] clears the live station drill-in first, [stationId]
        // itself is saved as the origin so back reopens the same station.
        onOpenStationOvers = { stationId ->
            val filter = LogFilterSelection(stationId = stationId)
            openLogAndNavigate(navState, currentState, filter, LogFilterOrigin.Station(stationId))
        },
        // IA-6 (information-architecture review, approved — WPNAV): a transmission's own
        // attributed station, one tap away — mirrors [onOpenActivationThread] below's own shape
        // for "open a *different* drill-in kind from inside one already open": the live
        // transmission drill-in is closed first, so `NavHostDispatch`'s own `when` does not keep
        // matching the now-stale `transmissionId` branch ahead of the new `stationId` one.
        onOpenAttributedStation = { stationId ->
            navState.closeDrillIns()
            navState.onOpenDrillIn(currentState.value) { navState.openStationId.value = stationId }
        },
        onOpenFrequency = { hz ->
            navState.onOpenDrillIn(currentState.value) { navState.openFrequencyHz.value = hz }
        },
        onOpenThread = { id ->
            navState.onOpenDrillIn(currentState.value) { navState.openThreadId.value = id }
        },
        onOpenStations = { currentState.value = ReaderDestination.STATIONS },
        // R-139 (round 5): `SettingsContent.initialScreen` is real now (WP10 merged it —
        // confirmed by reading `ui/settings/SettingsContent.kt` before wiring this), so `Now`'s
        // "Install a model" lands directly on `Assets` instead of the root the operator used to
        // have to tap through themselves.
        onOpenModels = { navigator.openSettings(SettingsScreenId.ASSETS) },
        // WPREC: `Recordings.dc.html`'s storage card — `NavHostCallbacks.onOpenSettingsStorage`'s own doc comment.
        onOpenSettingsStorage = { navigator.openSettings(SettingsScreenId.STORAGE) },
        // Round 6, register R-132: `SettingsContent`'s own `onOpenLevelMeter` (its doc comment:
        // "the host is expected to wire it the same way it wires every other cross-package
        // drill-in") — `Settings-Capture`'s `Meter` action switches to `Capture` and asks
        // `CaptureStatusContent` to land directly on `LevelMeterScreen`.
        onOpenLevelMeter = {
            currentState.value = ReaderDestination.CAPTURE
            navState.openCaptureLevelMeter.value = true
        },
        // Round 9, register R-276 (generalised by IA-3): `Frequency-Change`'s "The N overs" — real
        // now that WP5 merged `LogContent.initialFilter` (confirmed by reading
        // `ui/screens/LogContent.kt` before wiring this). See `NavHostNavState.openLogFiltered`'s
        // own doc comment for exactly what this does and does not restore on back. `hz` is the
        // frequency drill-in this fires from in every real call (this only ever fires from inside
        // `FrequencyDetailContent`, which only composes while `openFrequencyHz` already holds it).
        onOpenOvers = { hz, window ->
            val filter =
                LogFilterSelection(frequencyHz = hz, fromMillis = window.startMillis, toMillis = window.endMillis)
            openLogAndNavigate(navState, currentState, filter, LogFilterOrigin.Frequency(hz))
        },
        // Round 11, register R-133: `Settings-Storage`'s "Next deletion … Review" link
        // (`SettingsContent.onReviewSession`, WP10's `948fe55`) — see
        // `NavHostNavState.openReviewSession`'s own doc comment for what this seeds and
        // `OrtNavHostBackHandler`'s own doc comment for how back returns to `Settings-Storage`.
        onReviewSession = { sessionId ->
            navState.openReviewSession(sessionId)
            currentState.value = ReaderDestination.EARLIER_NIGHTS
        },
        // Round 17, register R-432: `Frequency-Change`'s "The activation thread" pill
        // (`ActivationThreadRouting.resolveActivationThread`, called from `NavHostDispatch`) opens
        // this once it has a real `threadId` — the live frequency drill-in is closed first (unlike
        // `onOpenThread` above, built for the LOG/THREADS list rows, where no other drill-in id is
        // ever already set) so `NavHostDispatch`'s own `when` does not keep matching the now-stale
        // `frequencyHz` branch ahead of the new `threadId` one. `onOpenDrillIn` then records
        // `currentState.value` (`FREQUENCIES`, in every real case this fires from) as `openedFrom`,
        // so T02's own header reads "Back to Frequencies" — the true origin, not the frequency
        // drill-in this was reached through, which no longer exists to return to.
        onOpenActivationThread = { threadId ->
            navState.closeDrillIns()
            navState.onOpenDrillIn(currentState.value) { navState.openThreadId.value = threadId }
        },
        // R-1041 (N01, information-architecture finding — register R-1041): `Main.dc.html`'s
        // chart bar, real now — filters to the tapped bar's own real `[fromMillis, toMillis]` hour
        // window (`NowScreen`'s own `hourFilterWindow`), origin `Now` so back returns there.
        onOpenHour = { fromMillis, toMillis ->
            val filter = LogFilterSelection(fromMillis = fromMillis, toMillis = toMillis)
            openLogAndNavigate(navState, currentState, filter, LogFilterOrigin.Now)
        },
        // R-1041 (D11, register R-1041): `Detail-Propagated.dc.html`'s own "View the N affected
        // overs" — filters to exactly the real affected transmission ids (never a fabricated or
        // wider set), origin `Transmission` so back reopens the same transmission's own drill-in.
        onViewAffectedOvers = { transmissionId, overIds ->
            val filter = LogFilterSelection(transmissionIds = overIds)
            openLogAndNavigate(navState, currentState, filter, LogFilterOrigin.Transmission(transmissionId))
        },
        // R-1041 (R04, register R-1041): `Improve-Done`'s own "Review the N changes" — filters to
        // exactly the real, revised over ids (`ReprocessStatus.Summary.changedTransmissionIds`,
        // never the whole attempted batch), origin `Improve` so back reopens `Improve records`.
        onOpenChangedOvers = { overIds ->
            val filter = LogFilterSelection(transmissionIds = overIds)
            openLogAndNavigate(navState, currentState, filter, LogFilterOrigin.Improve)
        },
        // WPRC02 (RC01's row tap, `Recordings.dc.html` -> RC02): a drill-in id in every way that
        // matters here (`NavHostNavState.openRecordingSessionId`'s own doc comment), so this uses
        // the identical `onOpenDrillIn` shape `onOpenStation`/`onOpenTransmission` above already do.
        onOpenRecordingSession = { sessionId ->
            navState.onOpenDrillIn(currentState.value) { navState.openRecordingSessionId.value = sessionId }
        },
        // WPRC02: RC02's own "Log" link — filters to exactly this session's own real over ids
        // (`LogFilterSelection` carries no session-scoped filter — see
        // `RecordingSessionContent.onOpenLog`'s own doc comment for the R-1055 dependency this
        // shares with `onViewAffectedOvers`/`onOpenChangedOvers` above), origin `RecordingSession`
        // so back reopens this same session.
        onOpenRecordingSessionLog = { sessionId, overIds ->
            val filter = LogFilterSelection(transmissionIds = overIds)
            openLogAndNavigate(navState, currentState, filter, LogFilterOrigin.RecordingSession(sessionId))
        },
    )
}

/** Builds [DestinationContent]'s [SearchHostState] — same reason as [navHostCallbacks]. */
private fun searchHostState(
    navigator: ReaderNavigator,
    context: android.content.Context,
    scope: CoroutineScope,
    navState: NavHostNavState,
    seed: NavSeed?,
): SearchHostState = SearchHostState(
    input = navState.searchInput.value,
    onInputChange = { navState.searchInput.value = it },
    result = navState.searchResult.value,
    onSearch = {
        scope.launch {
            val params = SearchFilterParser.parse(navState.searchInput.value, SystemClock.wallMillis())
            val facetFilter = SearchFacetFilter.from(navState.searchInput.value)
            navState.searchResult.value = SearchPolling.search(context, params, facetFilter)
        }
    },
    // R-200: `SearchContent`'s own back-chevron returns to wherever `Search` was opened from —
    // see `NavHostNavState.searchOpenedFrom`'s own doc comment.
    onBack = { navigator.currentState.value = navState.searchOpenedFrom.value },
    // Round 14 (WP12's screenshot-tour seam, real now — WP7 merged `SearchContent.initialQuery`/
    // `submitOnStart`/`initialFiltersOpen`): straight pass-through, read only on `SearchContent`'s
    // own first composition (its own internal `remember`s), the same "seed once" contract every
    // other `NavSeed` field already has.
    initialQuery = seed?.searchQuery,
    submitOnStart = seed?.searchSubmit ?: false,
    initialFiltersOpen = seed?.searchFiltersOpen ?: false,
)

/** [NavHostBody]'s own `modifier` (round 11: no longer `OrtNavHost`'s `Scaffold` inner padding
 * directly — that now lands on the `FailureHost` wrapper one level up, register R-334 — just
 * `fillMaxSize()`; register R-1003 adds `.safeAreaBottomPadding()` — see the call site's own doc
 * comment for why that does not double-reserve against the `Scaffold`'s own padding above it) and,
 * register R-178, the banner-height top padding [org.ort.app.ui.failures.FailureHost] reports —
 * bundled, same reason as [NavHostIds]/[NavHostCallbacks], so [NavHostBody] stays under detekt's
 * `LongParameterList` rather than growing a parameter for the R-178 addition. Register R-1061
 * (round 2): [onLiveBarHeightChanged] joins this bundle for the identical reason — [OrtNavHost]'s
 * own `liveBarHeight` doc comment has the full account of what this reports and why. */
private data class NavHostLayout(
    val modifier: Modifier,
    val contentTopPadding: Dp = 0.dp,
    val onLiveBarHeightChanged: (Dp) -> Unit = {},
)

/** The "land here fresh, not at the root" seeds three different destinations each take, bundled
 * purely to keep [NavHostIds] and [DestinationContent] under detekt's `LongParameterList` — the
 * same reason [NavHostIds]/[NavHostCallbacks] themselves exist. Round 5:
 * [settingsInitialScreen] (`SettingsContent`'s own doc comment on the same "opens there on
 * launch" contract). Round 6, register R-132: [openCaptureLevelMeter]
 * (`NavHostNavState.openCaptureLevelMeter`'s own doc comment). Round 9, register R-276:
 * [logInitialFilter] (`NavHostNavState.pendingLogFilter`'s own doc comment) — the field whose
 * addition is what pushed the previous three-scalar-parameter shape over the limit. Round 11,
 * register R-133: [reviewSessionId] (`NavHostNavState.pendingReviewSessionId`'s own doc comment) —
 * `SessionsContent.initialSessionId`'s seed. */
private data class DestinationInitialState(
    val settingsInitialScreen: SettingsScreenId?,
    val openCaptureLevelMeter: Boolean,
    // R-1007 (WPL): see `NavHostNavState.openCaptureLiveMonitor`'s own doc comment.
    val openCaptureLiveMonitor: Boolean,
    val logInitialFilter: LogFilterSelection?,
    val reviewSessionId: String?,
    // Round 14 (after WP5 merged `LogContent.initialSheetOpen`) — see `NavSeed.logSheetOpen`'s own
    // doc comment.
    val logInitialSheetOpen: Boolean,
    // R-840 — see `NavHostNavState.reviewSessionView`'s own doc comment.
    val reviewSessionView: ReviewSessionView,
    // WPRC02 — see `NavHostNavState.openRecordingSessionId`'s own doc comment.
    val recordingSessionId: String?,
)

/** [NavHostBody]'s destination/drill-in identity, bundled to keep that composable's own parameter count down. */
private data class NavHostIds(
    val current: ReaderDestination,
    val openedFrom: ReaderDestination,
    val transmissionId: String?,
    val stationId: String?,
    val frequencyHz: Long?,
    val threadId: String?,
    val contentInitialState: DestinationInitialState,
    // Round 10, register R-276: which sub-screen a freshly-opened frequency drill-in lands on —
    // see `NavHostNavState.frequencyInitialView`'s own doc comment.
    val frequencyInitialView: FrequencyDetailView,
    // Round 14, register R-276 follow-on (WP12 screenshot tour, coordinator round 2026-09-08):
    // which sub-screen a freshly-opened station drill-in lands on — see
    // `NavHostNavState.openStationSubScreen`'s own doc comment.
    val stationInitialSubScreen: StationSubScreen,
    // Round 14 (WP12's screenshot-tour seam) — see `NavSeed.openTransmissionRevisions`'s own doc
    // comment. Only meaningful alongside `transmissionId`, the same companion relationship
    // `frequencyInitialView` already has to `frequencyHz`.
    val transmissionInitialRevisionsOpen: Boolean,
)

/**
 * FR-OBS-6: the closed [RecorderDestination] this [NavHostIds] currently resolves to — a drill-in
 * id wins over [NavHostIds.current] exactly the way [NavHostDispatch]'s own `when` already
 * prioritises them, so this always names whatever destination is actually on screen. Never
 * forwards a drill-in's own id (see `FieldReportRecorder`'s own package doc comment on why not).
 */
private fun NavHostIds.recorderDestination(): RecorderDestination = when {
    transmissionId != null -> RecorderDestination.TRANSMISSION_DETAIL
    stationId != null -> RecorderDestination.STATION_DETAIL
    frequencyHz != null -> RecorderDestination.FREQUENCY_DETAIL
    threadId != null -> RecorderDestination.THREAD_DETAIL
    else -> RecorderDestination.forReaderDestination(current)
}

/** [NavHostBody]'s navigation actions, bundled for the same reason as [NavHostIds]. */
private data class NavHostCallbacks(
    val onOpenDrawer: () -> Unit,
    val onSearchDestination: () -> Unit,
    val onCloseDrillIns: () -> Unit,
    val onOpenCapture: () -> Unit,
    // R-1007 (WPL): see `navHostCallbacks`'s own construction site.
    val onOpenLog: () -> Unit,
    val onOpenTransmission: (String) -> Unit,
    val onOpenStation: (String) -> Unit,
    val onOpenFrequency: (Long) -> Unit,
    val onOpenThread: (String) -> Unit,
    val onOpenStations: () -> Unit,
    val onOpenModels: () -> Unit,
    // WPREC: `Recordings.dc.html`'s own storage card — `Settings-Storage` (CF03), the same
    // `navigator.openSettings` shape `onOpenModels` above already uses.
    val onOpenSettingsStorage: () -> Unit,
    val onOpenLevelMeter: () -> Unit,
    val onOpenOvers: (Long, TimeWindow) -> Unit,
    // Round 11, register R-133: see `navHostCallbacks`'s own construction site.
    val onReviewSession: (String) -> Unit,
    // Round 17, register R-432: see `navHostCallbacks`'s own construction site and
    // `ActivationThreadRouting`'s own doc comment for what resolves the id this expects.
    val onOpenActivationThread: (String) -> Unit,
    // IA-3 (ST02): see `navHostCallbacks`'s own construction site.
    val onOpenStationOvers: (String) -> Unit,
    // IA-6: see `navHostCallbacks`'s own construction site.
    val onOpenAttributedStation: (String) -> Unit,
    // R-1041 (N01): `Main.dc.html`'s chart bar — see `navHostCallbacks`'s own construction site.
    val onOpenHour: (fromMillis: Long, toMillis: Long) -> Unit,
    // R-1041 (D11): `Detail-Propagated.dc.html`'s "View the N affected overs" — see
    // `navHostCallbacks`'s own construction site.
    val onViewAffectedOvers: (transmissionId: String, overIds: Set<String>) -> Unit,
    // R-1041 (R04): `Improve-Done`'s "Review the N changes" — see `navHostCallbacks`'s own
    // construction site.
    val onOpenChangedOvers: (Set<String>) -> Unit,
    // WPRC02: `Recordings.dc.html`'s own "Session -> RC02" (RC01's row tap) — see
    // `navHostCallbacks`'s own construction site.
    val onOpenRecordingSession: (String) -> Unit,
    // WPRC02: `Recording-Session.dc.html`'s own "Log" link — see `navHostCallbacks`'s own
    // construction site and `RecordingSessionContent.onOpenLog`'s own doc comment for the R-1055
    // dependency this filter shape carries.
    val onOpenRecordingSessionLog: (sessionId: String, overIds: Set<String>) -> Unit,
)

/**
 * R-017: WP7's [SearchContent]'s `input`/`result`, owned by [OrtNavHost] — see that function's doc
 * comment. [onSearch] is the host's own trigger (WP7's `SearchContent` takes `onSearch: () ->
 * Unit`, not a result setter — the host runs the query and writes [result] itself). [onBack]
 * (round 5, R-200) is the second half of that same file's own doc comment on its `onBack`
 * parameter — see `searchOpenedFrom` in `OrtNavHost`.
 */
private data class SearchHostState(
    val input: SearchFilterInput,
    val onInputChange: (SearchFilterInput) -> Unit,
    val result: SearchResult?,
    val onSearch: () -> Unit,
    val onBack: () -> Unit,
    // Round 14 — see `searchHostState`'s own construction site.
    val initialQuery: String?,
    val submitOnStart: Boolean,
    val initialFiltersOpen: Boolean,
)

/** Round 16, register R-541: mirrors `FailureHost.kt`'s own private `HEADER_HEIGHT` — the guide's
 * `ScreenHeader`/`DrillInHeader` shared `Box(...).heightIn(min = 44.dp)`, the token that actually
 * governs their height (cited here rather than silently re-derived, the same reason that file's
 * own copy gives). See [NavHostBody]'s `bannerClearance` for why this host needs its own copy: the
 * banner slot's real bottom edge on a headerless destination, not `FailureHost`'s own internal
 * positioning, which this host has no access to and does not need. */
private val HOST_HEADER_HEIGHT: Dp = 44.dp

/**
 * Round 16, register R-541 (halt): `FailureHost`'s own `BannerOverlay` always positions itself
 * [HOST_HEADER_HEIGHT] below the top of the viewport — it assumes a host `ScreenHeader` already
 * occupies that space, and reports `contentTopPadding` (`FailureHost`'s own R-178 `bannerHeight`)
 * as only the *extra* room a banner needs beyond that (see `FailureHost.kt`'s
 * `BannerOverlay`/`HEADER_HEIGHT` kdoc, read before writing this). That holds whenever
 * [hostHeaderShown] is `true` — the header really does occupy the first [HOST_HEADER_HEIGHT], so
 * `contentTopPadding` alone lands the content column right at the banner's real bottom edge. It
 * silently broke for every destination that owns its own header instead (a drill-in's
 * `DrillInHeader`, `SearchContent`'s own header, every Settings sub-screen's `DrillInHeader`) —
 * this host renders *no* header at all above them, so their content started at `contentTopPadding`
 * alone, [HOST_HEADER_HEIGHT] short of the banner's real bottom edge: `CF06-settings-rig.png`'s
 * "‹ Settings" row sat clipped under `Fail-Rig`'s banner, unreachable. This restores the missing
 * [HOST_HEADER_HEIGHT] for exactly those headerless destinations, and only while a banner is
 * actually showing (`contentTopPadding > 0.dp` — otherwise this would push every headerless
 * destination's own header down by 44dp for no reason whenever no banner is up, a regression the
 * existing capture-set would have caught immediately).
 */
private fun bannerClearance(hostHeaderShown: Boolean, contentTopPadding: Dp): Dp =
    if (!hostHeaderShown && contentTopPadding > 0.dp) contentTopPadding + HOST_HEADER_HEIGHT else contentTopPadding

/**
 * C10 (`design/canvas/Transport-Bar.dc.html`): which [TransportBarViewState] [NavHostBody] should
 * render, pulled out to a plain function — the same "pull the decision out of the composable" this
 * file's own [bannerClearance] already established — so it is directly unit-testable
 * (`TransportBarStateResolutionTest.kt`) without composing this whole host.
 *
 * The rules, in the order they are checked: **playback wins over live** ("one mode at a time" —
 * checked first, before [embedsOwnLiveBar] even applies, because the artboard's "hidden on the
 * screen it expands to" rule for playback is a *different* screen than the one it is for live:
 * [openTransmissionId] naming the loaded transmission's own drill-in, not `Now`/`Capture`); a
 * screen with no live session and nothing loaded shows nothing; a destination that embeds its own
 * copy of the live bar (`Now`/`Capture`, [embedsOwnLiveBar]) never gets the host's live bar too
 * (IA-2 — unchanged from R-022's own original rule); and the playing transmission's own detail
 * screen never shows the bar that would just reopen itself.
 */
internal fun resolveTransportBarState(
    transportPlayback: TransportPlaybackController,
    liveBar: LiveBarViewState?,
    embedsOwnLiveBar: Boolean,
    openTransmissionId: String?,
): TransportBarViewState {
    val loadedTransmissionId = transportPlayback.loadedTransmissionId
    return when {
        loadedTransmissionId == null && embedsOwnLiveBar -> TransportBarViewState.Hidden
        loadedTransmissionId == null ->
            liveBar?.let { TransportBarViewState.Live(it) } ?: TransportBarViewState.Hidden
        loadedTransmissionId == openTransmissionId -> TransportBarViewState.Hidden
        else -> TransportBarViewState.Playback(
            transmissionId = loadedTransmissionId,
            callsignLabel = transportPlayback.callsignLabel,
            isPlaying = transportPlayback.isPlayingState,
            positionFraction = transportPlayback.positionFractionState,
            elapsedLabel = formatTransportBarTime(
                transportPlayback.positionFractionState * transportPlayback.durationSeconds,
            ),
            totalLabel = formatTransportBarTime(transportPlayback.durationSeconds),
            capturingDotVisible = transportPlayback.isPlayingState && transportPlayback.capturingNow,
        )
    }
}

/**
 * The header (R-003/R-004/R-015/R-016), the current destination or drill-in's content, and the
 * live bar (R-022), stacked in one column filling [OrtNavHost]'s `Scaffold`. Extracted out of
 * [OrtNavHost] purely to keep that function under detekt's length limit — the same reason
 * [DestinationContent] was already extracted before this prompt.
 */
@Composable
private fun NavHostBody(
    layout: NavHostLayout,
    ids: NavHostIds,
    callbacks: NavHostCallbacks,
    sessionId: String?,
    context: android.content.Context,
    drawerLive: DrawerLiveState,
    transportPlayback: TransportPlaybackController,
    search: SearchHostState,
) {
    // Register R-1061 (round 2): `layout.onLiveBarHeightChanged` — see [OrtNavHost]'s own
    // `liveBarHeight` doc comment. This is *not* R-262's own reverted mechanism (this function's
    // next doc comment paragraph). That one fed a measured height back into *this same column's
    // own* content-box padding, a genuine second reservation on top of what `Column`'s own weight
    // distribution already gives for free (R-957's fix). This is a different consumer entirely:
    // [org.ort.app.ui.failures.FailureHost], a composable *above* this one in the tree, which has
    // no other way to know how tall the bar *will* be before it decides how much room its own
    // banner may claim — nothing here changes how this column itself lays out the bar against the
    // content box below it.
    val onLiveBarHeightChanged = layout.onLiveBarHeightChanged
    // Register R-262 (accessibility validator), superseded by R-957 (root cause, WPI's host
    // comparison on 5558/5556): R-262 added an explicit `padding(bottom = liveBarHeight)` to the
    // content box below, real-measuring [LiveBar]'s own height via `onGloballyPositioned` the same
    // way [org.ort.app.ui.failures.FailureHost] does for the banner's `contentTopPadding` (that
    // file's own R-178 doc comment) — believing a destination's own scrollable content otherwise
    // sized itself to the full column height with the bar pinning on top of its last row. Proven
    // wrong on-device (`uiautomator dump`, 5558/5556): the content box below is *already* a
    // `weight(1f)` sibling of [LiveBar]'s own box in this same `Column` — Compose measures every
    // non-weighted sibling (the header, the live bar) first and gives the weighted box exactly
    // what remains, with no extra padding needed at all, on the very first frame the live bar
    // exists (confirmed by a real device dump and a Robolectric test asserting the content box's
    // own bottom lands exactly at the live bar's own top, `OrtNavHostDestinationDispatchTest
    // .R_957`'s own four cases). The removed padding was a genuine, reproducible *second*
    // reservation stacked on the natural one — a 125px/45dp dead band on every live-session
    // capture at 1.0 that read as the last row "cut with blank space", never an overlap (nothing
    // was ever clipped, which is why `R_262`'s own `<=` check below never caught it). One
    // mechanism only now: plain `Column` sibling spacing, no measured-height state at all.
    // FR-OBS-6/FR-OBS-7 (field-report session recorder, `app/.../fieldreport/recorder/**`, this
    // round's own addition — see that package's report for the full vocabulary and what else it
    // does not yet wire): the one destination-change observation hook this round adds, kept to the
    // smallest possible change per this round's own brief. `ids.recorderDestination()` (below,
    // this file) turns the four drill-in ids plus `ids.current` into the closed
    // `RecorderDestination` FR-OBS-6's vocabulary requires — never the drill-in's own id, which
    // this host otherwise treats as an opaque `String`/`Long` throughout. Keyed on that closed
    // value, not on the raw ids, so a `LaunchedEffect` restart happens once per logical
    // destination change, matching "on each destination change the recorder observes" (FR-OBS-7)
    // exactly.
    val recorderDestination = ids.recorderDestination()
    LaunchedEffect(recorderDestination) {
        FieldReportRecorder.onDestinationChanged(recorderDestination)
    }
    // Register R-1041 (R04) / R-1055: [DestinationContent]'s own `when(current)` (`NavHostDispatch`,
    // below) fully disposes whichever destination is not the current one — correct for a plain
    // `remember`, but it also throws away any `rememberSaveable` state a destination's own content
    // keeps (`org.ort.app.ui.improve.ImproveContent`'s own `page`, R-1063), because that state's
    // *value* has nowhere to live once its owning composable leaves the tree entirely; a real
    // Activity recreation is a different mechanism (the `SaveableStateRegistry` bundle survives
    // that one on its own) and untouched by this. `rememberSaveableStateHolder()`, created once
    // here — a level `NavHostDispatch`'s own `when` (below) does *not* dispose when a drill-in
    // opens over the current destination, so a destination's own saved state also survives that,
    // not only an ordinary destination-to-destination switch (this is what "across drill-in and
    // back" in this round's own brief means) — remembers every keyed subtree's `rememberSaveable`
    // values across being dropped from composition and given back the moment the same key (this
    // file's own [DestinationContent] call site below keys by [ReaderDestination.name]) is composed
    // again, exactly the "switch tabs, keep each tab's own state" use its own package doc names.
    val destinationStateHolder = rememberSaveableStateHolder()
    Column(modifier = layout.modifier) {
        val isDrillIn = listOf(ids.transmissionId, ids.stationId, ids.frequencyHz, ids.threadId).any { it != null }
        // ui-conformance WP3 round 4 (R-129 smoke coverage found this, real at the time): this host
        // used to skip its own header for `SETTINGS` because `SettingsRootScreen` drew a second,
        // duplicate one — the same double-header defect R-016 already fixed once for the four real
        // drill-ins. Superseded once already (round 4→5: R-130 had `SettingsRootScreen` draw no
        // header of its own, so this host went back to always rendering one for the whole `SETTINGS`
        // destination) and **superseded again this round (round 7)**: once `initialScreen` (round 5)
        // let a caller land directly on a sub-screen, the host's one destination-wide header and that
        // sub-screen's own `DrillInHeader` rendered stacked — the double-header bug one level down,
        // round 6's own smoke test coverage found it. `SettingsRootScreen.kt` now draws its own
        // `ScreenHeader` again (`SettingsContent`'s own doc comment states this outright, including
        // the instruction that this host's copy "must be removed to match"), so `SETTINGS` gets the
        // same treatment as `SEARCH` below — this host renders neither a header nor a
        // `DrillInHeader` for the whole destination, root or sub-screen alike; `onSearch` is wired
        // through to `SettingsContent` the same way `ScreenHeader.onSearch` always was.
        //
        // Round 5 (register R-200): `SEARCH` gets the identical treatment, for a different reason
        // than `SETTINGS`'s own — `Search.dc.html`'s header is a back chevron plus the inline query
        // field, not the generic drawer/search bar every other destination gets, and `SearchContent`
        // (WP7's file, confirmed by reading its own doc comment before this) now draws exactly that
        // itself. Unlike `SETTINGS`'s pre-round-7 root, `SEARCH` never had a state where nothing drew
        // a header of its own — it always does — so there was never an analogous "zero-header"
        // failure mode to guard there.
        val isSearch = ids.current == ReaderDestination.SEARCH
        val isSettings = ids.current == ReaderDestination.SETTINGS
        // Register R-930 (Reviewer D2, run 3): `EARLIER_NIGHTS` gets the identical `SETTINGS`/
        // `SEARCH` treatment, for the identical `SETTINGS` reason — `SessionsContent`'s own
        // sub-screens (`Detail`/`Digest`/`DigestItem`/`Log`, all reached either by a real tap or by
        // `NavSeed.pendingReviewSessionId`/`reviewSessionView`) each already draw their own
        // `DrillInHeader`; only the plain `Sessions` list (`SessionsScreen`) had none of its own,
        // relying on this host's one destination-wide header — correct only for that one state,
        // exactly `SETTINGS`'s own pre-round-7 shape before `SettingsRootScreen` grew its own
        // header (round 7's doc comment above). `SessionsScreen.kt` now draws its own `ScreenHeader`
        // (WPF's file — a small, reported, minimal addition, the same shape [R-840]'s own
        // `SessionsContent.openDigest` already was) so this host renders neither a header nor a
        // `DrillInHeader` for the whole destination, root or sub-screen alike, matching `SETTINGS`.
        val isEarlierNights = ids.current == ReaderDestination.EARLIER_NIGHTS
        val hostDrawsNoHeader = isSearch || isSettings || isEarlierNights
        if (!isDrillIn && !hostDrawsNoHeader) {
            // R-003/R-004/R-015: drawer icon, live dot + elapsed while a session is capturing,
            // search icon — no title, the destination content below draws its own (`Main.dc.html`'s
            // 27sp title is `NowScreen`'s, not the header's).
            ScreenHeader(
                onDrawer = callbacks.onOpenDrawer,
                liveElapsedLabel = drawerLive.badges.captureElapsedLabel,
                onSearch = callbacks.onSearchDestination,
            )
        }
        // R-016: no generic host-level `DrillInHeader` when a drill-in is open — every real
        // drill-in content composable WP5/6/8 shipped (`TransmissionDetailContent`,
        // `StationDetailContent`→`StationDetailScreen`, `FrequencyDetailContent`→
        // `FrequencyDetailScreen`, `ThreadDetailScreen`) already draws its own `DrillInHeader`
        // internally (confirmed by reading all four before writing this) — rendering a second one
        // here would stack two, the same double-render this package's WP4 live-bar reconciliation
        // already found and fixed once. Round 3: all four now accept a real `backLabel` — this host
        // passes `ids.openedFrom.label`, so the header names the true origin (R-017), e.g. "Back to
        // Search" for a transmission opened from a search result.
        // Round 16, register R-541: see `bannerClearance`'s own doc comment — no `ScreenHeader`
        // above a drill-in/`SEARCH`/`SETTINGS` (the `if` above) means their content needs the
        // extra clearance that comment explains.
        val clearance = bannerClearance(!isDrillIn && !isSearch && !isSettings, layout.contentTopPadding)
        Box(modifier = Modifier.weight(1f).padding(top = clearance)) {
            NavHostDispatch(ids, callbacks, sessionId, context, transportPlayback, search, destinationStateHolder)
        }

        // C10 (`design/canvas/Transport-Bar.dc.html`): replaces the plain pinned [LiveBar] this
        // block used to render directly — see [resolveTransportBarState]'s own doc comment for the
        // decision itself, pulled out to a plain function the same way [bannerClearance] above
        // already is, so it is directly unit-testable without composing this whole host.
        val embedsOwnLiveBar = !isDrillIn &&
            (ids.current == ReaderDestination.NOW || ids.current == ReaderDestination.CAPTURE)
        val transportState = resolveTransportBarState(
            transportPlayback = transportPlayback,
            liveBar = drawerLive.liveBar,
            embedsOwnLiveBar = embedsOwnLiveBar,
            openTransmissionId = ids.transmissionId,
        )
        // Register R-1061 (round 2): the bar's real height must reach [onLiveBarHeightChanged] as
        // `0.dp` the instant it stops showing — a stale non-zero value here would leave
        // `FailureHost`'s own banner cap permanently shrunk for a bar that is no longer pinned.
        // Keyed on `transportState` itself (not just whether it is `Hidden`) so this fires again on
        // every transition into `Hidden`, from whichever state preceded it.
        LaunchedEffect(transportState) {
            if (transportState == TransportBarViewState.Hidden) onLiveBarHeightChanged(0.dp)
        }
        if (transportState != TransportBarViewState.Hidden) {
            val density = LocalDensity.current
            // Wrapped, not passed as a `modifier` (the same reasoning this block's own prior form
            // had for `LiveBar`) — a plain `Column` sibling below the weighted content box above;
            // Compose's own layout already gives that box exactly the remaining height, no
            // measured-height state needed for *this column's own* layout (R-957). `testTag`
            // (register R-262): a stable node for a test to read this wrapper's own `boundsInRoot`,
            // independent of the bar's own runtime-varying label/callsign text. `onGloballyPositioned`
            // (register R-1061, round 2): the *real*, laid-out height of this exact box, reported
            // upward via [onLiveBarHeightChanged] — see this function's own parameter doc comment
            // for why this is a different consumer than the one R-957 already removed the mechanism
            // for.
            Box(
                modifier = Modifier
                    .testTag("live-bar-clearance")
                    .onGloballyPositioned { coordinates ->
                        onLiveBarHeightChanged(with(density) { coordinates.size.height.toDp() })
                    },
            ) {
                TransportBar(
                    state = transportState,
                    actions = TransportBarActions(
                        onTapLive = callbacks.onOpenCapture,
                        onTapPlaying = callbacks.onOpenTransmission,
                        onPlayPauseToggle = {
                            if (transportPlayback.isPlayingState) {
                                transportPlayback.pause()
                            } else {
                                transportPlayback.resume()
                            }
                        },
                        onScrub = transportPlayback::seekToFraction,
                        onClear = transportPlayback::clear,
                    ),
                )
            }
        }
    }
}

/**
 * The four drill-in ids or, failing all of them, the current destination — extracted out of
 * [NavHostBody] purely to keep that function under detekt's length limit (round 17: WP8's
 * `onOpenThread` addition to [FrequencyDetailContent] pushed [NavHostBody] itself to the limit),
 * the same reason [DestinationContent] was extracted out of [OrtNavHost] before it.
 *
 * Round 17, register R-432: the `ids.frequencyHz != null` branch's own `onOpenThread` resolves
 * `Frequency-Change`'s "The activation thread" pill — see [ActivationThreadRouting]'s own doc
 * comment for what it looks up and why it lives in this package rather than `ui/data/`. `scope` is
 * scoped to this composable (not hoisted to [OrtNavHost] itself) since only this one branch ever
 * launches anything from it.
 */
@Composable
private fun NavHostDispatch(
    ids: NavHostIds,
    callbacks: NavHostCallbacks,
    sessionId: String?,
    context: android.content.Context,
    audioPlayer: org.ort.app.ui.audio.TransmissionAudioPlayer,
    search: SearchHostState,
    // Register R-1041 (R04) / R-1055: see [NavHostBody]'s own `destinationStateHolder` doc comment
    // — threaded through this `when`'s drill-in branches untouched (a drill-in id always wins over
    // [NavHostIds.current] here, so those branches never even reach the `else` below), used only by
    // the `else` branch's own [DestinationContent] call.
    destinationStateHolder: SaveableStateHolder,
) {
    val scope = rememberCoroutineScope()
    when {
        ids.transmissionId != null -> TransmissionDetailContent(
            context = context,
            transmissionId = ids.transmissionId,
            player = audioPlayer,
            onBack = callbacks.onCloseDrillIns,
            onOpenTransmission = callbacks.onOpenTransmission,
            // IA-6: real now — the transmission's own attributed station, one tap away.
            onOpenStation = callbacks.onOpenAttributedStation,
            backLabel = ids.openedFrom.label,
            initialRevisionsOpen = ids.transmissionInitialRevisionsOpen,
            // R-1041 (D11): real now — `Detail-Propagated.dc.html`'s "View the N affected overs".
            onViewAffectedOvers = { overIds -> callbacks.onViewAffectedOvers(ids.transmissionId, overIds) },
        )

        ids.stationId != null -> StationDetailContent(
            context = context,
            stationId = ids.stationId,
            onBack = callbacks.onCloseDrillIns,
            onOpenTransmission = callbacks.onOpenTransmission,
            backLabel = ids.openedFrom.label,
            // Round 14, register R-276 follow-on: `NONE` for every ordinary open; only the
            // WP12 tour's own `NavSeed.openStationSubScreen` ever sets this to `PATTERN`/
            // `IDENTITY`/`SPLIT`.
            initialSubScreen = ids.stationInitialSubScreen,
            // IA-3 (ST02): real now — `Station.dc.html`'s own "Overs · Log"/"Recent overs · All N",
            // dead taps before this round (`StationDetailContent`'s own doc comment on
            // `onViewAllOvers`).
            onViewAllOvers = { callbacks.onOpenStationOvers(ids.stationId) },
        )

        ids.frequencyHz != null -> {
            val frequencyHz = ids.frequencyHz
            FrequencyDetailContent(
                context = context,
                frequencyHz = frequencyHz,
                onBack = callbacks.onCloseDrillIns,
                onOpenStation = callbacks.onOpenStation,
                onOpenOvers = callbacks.onOpenOvers, // R-276, real now — see NavHostCallbacks.onOpenOvers
                // Round 17, register R-432: real now — resolves the thread containing the first
                // over on this frequency after tonight's change (`ActivationThreadRouting`), opens
                // it (`NavHostCallbacks.onOpenActivationThread`), or falls through to the Log
                // filtered to that same window (`onOpenOvers`) when no thread exists yet — never a
                // no-op.
                onOpenThread = {
                    scope.launch {
                        val resolution = ActivationThreadRouting.resolveActivationThread(context, frequencyHz)
                        val threadId = resolution.threadId
                        if (threadId != null) {
                            callbacks.onOpenActivationThread(threadId)
                        } else {
                            callbacks.onOpenOvers(frequencyHz, resolution.window)
                        }
                    }
                },
                // Round 10: real now — WP8 merged it. `Detail` for every ordinary open; `Change`
                // only when the `BackHandler` above set it, restoring `Frequency-Change` itself.
                initialView = ids.frequencyInitialView,
                backLabel = ids.openedFrom.label,
            )
        }

        ids.threadId != null -> ThreadDetailContent(
            context = context,
            sessionId = sessionId,
            threadId = ids.threadId,
            onBack = callbacks.onCloseDrillIns,
            onOpenOver = callbacks.onOpenTransmission,
            backLabel = ids.openedFrom.label,
        )

        else -> {
            val destinationContent: @Composable () -> Unit = {
                DestinationContent(
                    current = ids.current,
                    initialState = ids.contentInitialState,
                    sessionId = sessionId,
                    context = context,
                    search = search,
                    callbacks = callbacks,
                    // WPRC02: threaded to `EarlierNightsDestinationContent` ->
                    // `RecordingSessionContent` (its own doc comment) — the identical `audioPlayer`
                    // this branch already hands `TransmissionDetailContent` above, never a second
                    // player instance.
                    player = audioPlayer,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Register R-1041 (R04) / R-1055: `IMPROVE_RECORDS` only — see [NavHostBody]'s own
            // `destinationStateHolder` doc comment for the mechanism, and this doc comment for why
            // it is scoped this narrowly rather than wrapping every destination this `else` branch
            // reaches. Tried wrapping all of them first, keyed by `ids.current.name`: it broke
            // `LOG` outright (`ReaderActivityDestinationSmokeTest`'s own `R_1041_D11` case, caught
            // by this round's own smoke run) — `LogContent.initialFilter` (like `CaptureStatusContent
            // .openLevelMeter`/`openLiveMonitor`, `SettingsContent.initialScreen`,
            // `SessionsContent.initialSessionId`/`openDigest`) is a *fresh-seed* contract, read only
            // on that composable's own first composition, exactly the way `NavHostNavState`'s own
            // seed fields document it ("a later change to this parameter after first composition
            // has no effect"). A blanket holder keeps a destination's *previous* internal state
            // alive under its own name and hands it straight back on the next visit, which is
            // correct for `Improve` (no per-visit seed at all — `page`'s only two lifetimes are "a
            // real Activity recreation", already R-1063's own guarantee, and "this round's own plain
            // destination switch") and wrong for every seeded one: a second, differently-filtered
            // `Log` reached this way silently kept the *first* visit's own unfiltered rows instead
            // of ever reading the new `initialFilter` — the discriminating failure was a stale
            // `N0OTHR` row a fresh Log filter should have excluded, still present after `View the N
            // affected overs` -> `Log` -> back -> reopen. Not needed for any other destination
            // either way: `NOW`/`THREADS`/`STATIONS`/`FREQUENCIES` keep no internal `rememberSaveable`
            // of their own at all (grepped before writing this), and `EARLIER_NIGHTS`'s own
            // `SessionsContent.page` never needs this — its own "Digest -> Log -> back" case is
            // handled entirely *inside* that package (`ui/digest/SessionsContent.kt`'s own
            // `SessionsPage.Log`/`returnTo`), never by this host switching `current` away from
            // `EARLIER_NIGHTS` at all.
            if (ids.current == ReaderDestination.IMPROVE_RECORDS) {
                destinationStateHolder.SaveableStateProvider(
                    key = ReaderDestination.IMPROVE_RECORDS.name,
                    content = destinationContent,
                )
            } else {
                destinationContent()
            }
        }
    }
}

/**
 * C10 (`design/canvas/Transport-Bar.dc.html`): the single [RealTransmissionAudioPlayer] instance
 * this host has always owned (this function's own `remember` block's prior form, inline in
 * [OrtNavHost] before this) is now wrapped in [TransportPlaybackController] rather than passed
 * raw — the controller delegates every [org.ort.app.ui.audio.TransmissionAudioPlayer] method (so
 * every existing call site typed to that plain interface, all the way down to
 * `TransmissionDetailScreen`'s own `PlaybackSection`, keeps compiling and behaving unchanged)
 * while also tracking what is loaded, so the bar and whichever screen is on top never disagree
 * about what is playing (R-1006 reversal — that class's own doc comment). Extracted out of
 * [OrtNavHost] purely to keep that function under detekt's length limit, the same reason
 * [rememberDrawerLiveState] already is.
 *
 * The one poll loop for playback (was `TransmissionDetailScreen`'s own, per-screen, before this
 * round) lives in the `LaunchedEffect` below — hoisted here so end-of-track detection and
 * position refresh happen whether or not the loaded transmission's own detail screen is currently
 * on screen, the same reason [drawerLive]'s own status polling already lives at this level rather
 * than per-destination. The second `LaunchedEffect` is C10 state 5 ("Playing while capturing"):
 * the bar's own live dot persists during playback exactly when a session is genuinely live — the
 * same fact [DrawerLiveState.liveBar] already being non-null means (constitution IV: capture is
 * never out of sight, even while the bar reads playback).
 */
@Composable
private fun rememberTransportPlayback(
    context: android.content.Context,
    drawerLive: DrawerLiveState,
): TransportPlaybackController {
    val transportPlayback = remember { TransportPlaybackController(RealTransmissionAudioPlayer(context)) }
    LaunchedEffect(transportPlayback) {
        while (true) {
            transportPlayback.poll()
            delay(TRANSPORT_POLL_INTERVAL_MILLIS)
        }
    }
    LaunchedEffect(drawerLive.liveBar, transportPlayback) {
        transportPlayback.capturingNow = drawerLive.liveBar != null
    }
    return transportPlayback
}

/** Everything [rememberDrawerLiveState] polls, bundled so it returns one value. */
private data class DrawerLiveState(
    val storage: StorageFooterViewState,
    val badges: DrawerBadgeViewState,
    val counts: DrawerCountsViewState,
    val sessionHeader: DrawerSessionHeaderViewState,
    val liveBar: LiveBarViewState?,
    val improveRecordsCount: Int?,
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
    var improveRecordsCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(sessionId) {
        while (true) {
            storage = StorageFooterViewState.fromAudioDirectory(context)
            badges = ReaderPolling.drawerBadges(context, sessionId)
            counts = DrawerCounts.current(context)
            // R-091/guide §6.4's count-pill, now that WP10's real read path is on this branch.
            improveRecordsCount = org.ort.app.ui.improve.ImproveCounts.canGetBetterCount(context)
            // No prompt has ever given `SessionEntity` a label field (see
            // `DrawerSessionHeaderViewState`'s own doc comment) — `sessionLabel` stays `null`
            // until one exists, rather than this fabricating one.
            sessionHeader = DrawerSessionHeaderViewState.from(sessionLabel = null, rigState = RigStatus.state)
            // R-022/R-910 (register, halt): resolved through `ReaderPolling.effectiveSessionId`,
            // the same seam `NowContent`/`CaptureStatusContent` already read through (that
            // function's own kdoc) — never a bare `CaptureState.sessionId == sessionId` equality.
            // That equality used to mean a host-rendered destination (`Threads`, `Stations`, every
            // id other than `NOW`/`CAPTURE`, which embed their own bar via this same seam already)
            // went dark the moment a scenario re-broadcast without restarting the process
            // (`-NoRestart`) moved `CaptureState.sessionId` on to a *new* session this argument was
            // never told about — a session genuinely, currently live, reported as if none were.
            val liveSessionId = ReaderPolling.effectiveSessionId(sessionId)
            liveBar = if (liveSessionId != null && CaptureState.isCapturing) {
                LiveBarPolling.current(context, liveSessionId)
            } else {
                null
            }
            delay(POLL_INTERVAL_MILLIS)
        }
    }
    return DrawerLiveState(storage, badges, counts, sessionHeader, liveBar, improveRecordsCount)
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
    initialState: DestinationInitialState,
    sessionId: String?,
    context: android.content.Context,
    search: SearchHostState,
    callbacks: NavHostCallbacks,
    // WPRC02: threaded to RC02, the identical single hoisted player instance.
    player: org.ort.app.ui.audio.TransmissionAudioPlayer,
    modifier: Modifier,
) {
    val settingsInitialScreen = initialState.settingsInitialScreen
    val openCaptureLevelMeter = initialState.openCaptureLevelMeter
    val openCaptureLiveMonitor = initialState.openCaptureLiveMonitor
    val logInitialFilter = initialState.logInitialFilter
    val reviewSessionId = initialState.reviewSessionId
    val logInitialSheetOpen = initialState.logInitialSheetOpen
    val reviewSessionView = initialState.reviewSessionView
    val content = modifier
    val onOpenTransmission = callbacks.onOpenTransmission
    val onOpenStation = callbacks.onOpenStation
    val onOpenFrequency = callbacks.onOpenFrequency
    val onOpenThread = callbacks.onOpenThread
    val onOpenDrawer = callbacks.onOpenDrawer
    when (current) {
        // Both dispatch to WP4/WP7's own real content composables now that they are on this
        // branch (confirmed by reading `ui/screens/NowContent.kt`/`ui/screens/SearchContent.kt`
        // before writing this) — this package's own inline copies are gone. Round 3: `NowContent`
        // gained `onOpenStations`/`onOpenModels` — see `NavHostCallbacks`'s own construction site.
        ReaderDestination.NOW -> NowContent(
            context = context,
            sessionId = sessionId,
            onOpenTransmission = onOpenTransmission,
            onOpenStation = onOpenStation,
            modifier = content,
            onOpenStations = callbacks.onOpenStations,
            onOpenModels = callbacks.onOpenModels,
            // R-1041 (N01): real now — `Main.dc.html`'s chart bar.
            onOpenHour = callbacks.onOpenHour,
        )

        // Round 3: `LogContent` gained a real `onOpenThread` — a QSO group header now opens the
        // real thread-detail drill-in (`Rows.dc.html`: "Tapping opens the thread").
        // Round 9, register R-276: `initialFilter` is real now — WP5 merged it (confirmed by
        // reading `ui/screens/LogContent.kt` before wiring this) — see `NavHostCallbacks
        // .onOpenOvers`'s own comment.
        ReaderDestination.LOG -> LogContent(
            context = context,
            sessionId = sessionId,
            onOpen = onOpenTransmission,
            modifier = content,
            onOpenThread = onOpenThread,
            initialFilter = logInitialFilter,
            initialSheetOpen = logInitialSheetOpen,
        )

        ReaderDestination.SEARCH -> SearchDestinationContent(search, onOpenTransmission, onOpenDrawer, content)

        // Round 3: `ThreadContent` gained a real `onOpenThread` too — a thread card now opens the
        // same drill-in.
        ReaderDestination.THREADS -> ThreadContent(
            context = context,
            sessionId = sessionId,
            onOpen = onOpenTransmission,
            modifier = content,
            onOpenThread = onOpenThread,
        )

        ReaderDestination.STATIONS ->
            StationsContent(context = context, onOpen = onOpenStation, modifier = content)

        ReaderDestination.FREQUENCIES ->
            FrequenciesContent(context = context, onOpen = onOpenFrequency, modifier = content)

        // Round 6, register R-132: `openLevelMeter` is real now — `Settings-Capture`'s `Meter`
        // action (via `NavHostCallbacks.onOpenLevelMeter`) lands here directly on the level meter.
        // R-1007 (WPL): `openLiveMonitor` the same way — the pinned bar's own tap
        // (`NavHostCallbacks.onOpenCapture`) lands here directly on `LiveMonitorScreen`.
        // `onOpenOver` reuses the identical transmission drill-in every other destination already
        // opens through; `onOpenFullLog` is `Live-Monitor.dc.html`'s own `Full log` action.
        ReaderDestination.CAPTURE -> CaptureStatusContent(
            context = context,
            sessionId = sessionId,
            modifier = content,
            openLevelMeter = openCaptureLevelMeter,
            openLiveMonitor = openCaptureLiveMonitor,
            onOpenOver = onOpenTransmission,
            onOpenFullLog = callbacks.onOpenLog,
        )

        // ui-conformance-plan WP10: dispatches to WP10's own real `SettingsContent` — see
        // [SettingsBranch]'s own doc comment for `initialScreen`/`onSearch`/`onReviewSession`.
        // Extracted purely to keep this function under detekt's `LongMethod` limit.
        ReaderDestination.SETTINGS ->
            SettingsBranch(context, onOpenDrawer, content, settingsInitialScreen, callbacks)

        // WPREC (design-intent row RC01): `EARLIER_NIGHTS` now means `Recordings.dc.html` (RC01)
        // for an ordinary reach — see `ReaderDestination.kt`'s own doc comment, and
        // [EarlierNightsDestinationContent]'s for the review-link split. Extracted purely to keep
        // this function under detekt's `LongMethod` limit — the same reason [ImproveRecordsContent]
        // below already is.
        ReaderDestination.EARLIER_NIGHTS -> EarlierNightsDestinationContent(
            context = context,
            onOpenDrawer = onOpenDrawer,
            modifier = content,
            onOpenTransmission = onOpenTransmission,
            reviewSessionId = reviewSessionId,
            reviewSessionView = reviewSessionView,
            onOpenSettingsStorage = callbacks.onOpenSettingsStorage,
            recordingSession =
            recordingSessionRouting(initialState.recordingSessionId, player, onOpenStation, callbacks),
        )

        // Round 12, R-350: `onOpenModels` real now (WP10's `94c946c`) — same callback as `Now`'s
        // above. Call itself extracted to [ImproveRecordsContent] purely to keep this function
        // under detekt's `LongMethod` limit.
        ReaderDestination.IMPROVE_RECORDS ->
            ImproveRecordsContent(context, onOpenDrawer, content, callbacks.onOpenModels, callbacks.onOpenChangedOvers)
    }
}

/**
 * [DestinationContent]'s `SETTINGS` branch, split out purely to keep that function under detekt's
 * `LongMethod` limit — the same reason [ImproveRecordsContent]/[EarlierNightsDestinationContent]
 * already are. Round 5 (R-090/R-139/F6/F9): [settingsInitialScreen] is real — `navigator
 * .openSettings` writes it, read fresh every time `SETTINGS` becomes current. Round 7:
 * `onSearch` is real — `SettingsRootScreen` draws its own header. Round 11, register R-133:
 * `onReviewSession` is real — WP10's own `SettingsContent.onReviewSession`.
 */
@Composable
private fun SettingsBranch(
    context: android.content.Context,
    onOpenDrawer: () -> Unit,
    modifier: Modifier,
    settingsInitialScreen: SettingsScreenId?,
    callbacks: NavHostCallbacks,
) {
    org.ort.app.ui.settings.SettingsContent(
        context = context,
        onDrawer = onOpenDrawer,
        modifier = modifier,
        initialScreen = settingsInitialScreen,
        onOpenLevelMeter = callbacks.onOpenLevelMeter,
        onSearch = callbacks.onSearchDestination,
        onReviewSession = callbacks.onReviewSession,
    )
}

/**
 * [DestinationContent]'s `EARLIER_NIGHTS` branch, in the order it is actually checked:
 * [recordingSessionId] (WPRC02, `Recording-Session.dc.html` — RC01's own row tap, or a restored
 * [LogFilterOrigin.RecordingSession]) first, then [reviewSessionId] (non-null *only* when
 * `Settings-Storage`'s "Next deletion … Review" link seeded it via `NavHostNavState
 * .openReviewSession` — the one path that still needs the old `SessionsContent`, DG04's `Session`
 * review or its own `Digest` — DG01/DG05 — per [reviewSessionView]), else `Recordings.dc.html`
 * (RC01) — see `ReaderDestination.kt`'s own doc comment for why the enum constant itself was not
 * renamed. The two ids are never both non-null in practice ([org.ort.app.ui.navigation
 * .NavHostNavState.closeDrillIns] clears both before either is set), but [recordingSessionId] is
 * checked first regardless, the same defensive ordering this file's own drill-in `when` blocks use.
 */
@Composable
private fun EarlierNightsDestinationContent(
    context: android.content.Context,
    onOpenDrawer: () -> Unit,
    modifier: Modifier,
    onOpenTransmission: (String) -> Unit,
    reviewSessionId: String?,
    reviewSessionView: ReviewSessionView,
    onOpenSettingsStorage: () -> Unit,
    recordingSession: RecordingSessionRouting,
) {
    when {
        recordingSession.sessionId != null -> org.ort.app.ui.recordings.RecordingSessionContent(
            context = context,
            sessionId = recordingSession.sessionId,
            player = recordingSession.player,
            onBack = recordingSession.onClose,
            onOpenTransmission = onOpenTransmission,
            onOpenStation = recordingSession.onOpenStation,
            onOpenLog = recordingSession.onOpenLog,
            modifier = modifier,
        )
        reviewSessionId != null -> org.ort.app.ui.digest.SessionsContent(
            context = context,
            onDrawer = onOpenDrawer,
            modifier = modifier,
            onOpenTransmission = onOpenTransmission,
            initialSessionId = reviewSessionId,
            openDigest = reviewSessionView == ReviewSessionView.DIGEST,
        )
        else -> org.ort.app.ui.recordings.RecordingsContent(
            context = context,
            onDrawer = onOpenDrawer,
            modifier = modifier,
            onOpenSession = recordingSession.onOpen,
            onOpenOverAudioBudget = onOpenSettingsStorage,
        )
    }
}

/** [EarlierNightsDestinationContent]'s own RC02-specific parameters, bundled (detekt
 * `LongParameterList`) — the same "bundle the request" shape this file's own [NavHostIds]/
 * [NavHostCallbacks] already establish. [sessionId] is `null` for every ordinary reach ([RecordingsContent]
 * renders instead); [player], [onOpen] ([NavHostCallbacks.onOpenRecordingSession]), [onClose]
 * (`NavHostCallbacks.onCloseDrillIns`), [onOpenStation] and [onOpenLog]
 * ([NavHostCallbacks.onOpenRecordingSessionLog]) are otherwise plain pass-throughs. */
private data class RecordingSessionRouting(
    val sessionId: String?,
    val player: org.ort.app.ui.audio.TransmissionAudioPlayer,
    val onOpen: (String) -> Unit,
    val onClose: () -> Unit,
    val onOpenStation: (String) -> Unit,
    val onOpenLog: (sessionId: String, overIds: Set<String>) -> Unit,
)

/** Builds [RecordingSessionRouting] — pulled out to a one-line call site purely to keep
 * [DestinationContent] under detekt's `LongMethod` limit, the same reason every other `*Content`
 * extraction in this file exists. */
private fun recordingSessionRouting(
    sessionId: String?,
    player: org.ort.app.ui.audio.TransmissionAudioPlayer,
    onOpenStation: (String) -> Unit,
    callbacks: NavHostCallbacks,
): RecordingSessionRouting = RecordingSessionRouting(
    sessionId,
    player,
    callbacks.onOpenRecordingSession,
    callbacks.onCloseDrillIns,
    onOpenStation,
    callbacks.onOpenRecordingSessionLog,
)

/** [DestinationContent]'s `IMPROVE_RECORDS` branch, split out purely to keep that function under
 * detekt's length limit — the same reason [ThreadDetailContent] below was already split out. */
@Composable
private fun ImproveRecordsContent(
    context: android.content.Context,
    onDrawer: () -> Unit,
    modifier: Modifier,
    onOpenModels: () -> Unit,
    // R-1041 (R04): `Improve-Done`'s "Review the N changes", real now.
    onOpenChangedOvers: (Set<String>) -> Unit,
) {
    org.ort.app.ui.improve.ImproveContent(
        context = context,
        onDrawer = onDrawer,
        modifier = modifier,
        onOpenModels = onOpenModels,
        onOpenChangedOvers = onOpenChangedOvers,
    )
}

/** [DestinationContent]'s `SEARCH` branch, split out purely to keep that function under detekt's
 * length limit — the same reason [ImproveRecordsContent] above already was. Round 14:
 * `initialQuery`/`submitOnStart`/`initialFiltersOpen` real now — WP7 merged them (confirmed by
 * reading `ui/screens/SearchContent.kt` before wiring this). */
@Composable
private fun SearchDestinationContent(
    search: SearchHostState,
    onOpen: (String) -> Unit,
    // R-1042 (IA-5, register): real now — `Search` reopens the drawer through the same
    // `NavHostCallbacks.onOpenDrawer` every other destination's header already uses.
    onDrawer: () -> Unit,
    modifier: Modifier,
) {
    SearchContent(
        input = search.input,
        result = search.result,
        onInputChange = search.onInputChange,
        onSearch = search.onSearch,
        onOpen = onOpen,
        modifier = modifier,
        onBack = search.onBack,
        onDrawer = onDrawer,
        initialQuery = search.initialQuery,
        submitOnStart = search.submitOnStart,
        initialFiltersOpen = search.initialFiltersOpen,
    )
}

/**
 * R-017: a poll-and-render wrapper for WP5's [ThreadDetailScreen] — that screen is a pure function
 * of [ThreadDetailViewState] with no poller of its own (its own doc comment: "ready for whichever
 * package wires that route"), so this package supplies the same shape every other drill-in's
 * `*Content` composable has, feeding it from [ThreadPolling.threadDetail]. Round 3: now reachable
 * from `LogContent`'s group headers and `ThreadContent`'s own thread cards (both gained a real
 * `onOpenThread` this round — see [DestinationContent]).
 */
@Composable
private fun ThreadDetailContent(
    context: android.content.Context,
    sessionId: String?,
    threadId: String,
    onBack: () -> Unit,
    onOpenOver: (String) -> Unit,
    backLabel: String,
    modifier: Modifier = Modifier,
) {
    var detail by remember(threadId, sessionId) { mutableStateOf<ThreadDetailViewState?>(null) }
    LaunchedEffect(threadId, sessionId) {
        val session = sessionId
        if (session != null) {
            detail = ThreadPolling.threadDetail(context, session, threadId)
        }
    }
    val current = detail
    if (current != null) {
        ThreadDetailScreen(
            state = current,
            onBack = onBack,
            onOpenOver = onOpenOver,
            onOpenSourceOver = onOpenOver,
            modifier = modifier,
            backLabel = backLabel,
        )
    } else {
        Text(
            text = "Loading…",
            modifier = modifier.padding(OrtSpacing.lg).semantics { contentDescription = "Loading thread detail" },
        )
    }
}
