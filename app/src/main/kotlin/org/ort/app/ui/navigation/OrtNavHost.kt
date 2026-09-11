package org.ort.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ort.app.ui.audio.RealTransmissionAudioPlayer
import org.ort.app.ui.components.LiveBar
import org.ort.app.ui.components.LiveBarViewState
import org.ort.app.ui.components.ScreenHeader
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
    val audioPlayer = remember { RealTransmissionAudioPlayer(context) }

    OrtNavHostBackHandler(current, navigator, navState, drawerState, scope)

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
                onSelect = { destination ->
                    current = destination
                    navState.closeDrillIns()
                    scope.launch { drawerState.close() }
                },
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
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) { contentTopPadding ->
                NavHostBody(
                    layout = NavHostLayout(modifier = Modifier.fillMaxSize(), contentTopPadding = contentTopPadding),
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
                            navState.pendingLogFilter.value,
                            navState.pendingReviewSessionId.value,
                            seed?.logSheetOpen ?: false,
                            navState.reviewSessionView.value,
                        ),
                        navState.frequencyInitialView.value,
                        navState.openStationSubScreen.value,
                        seed?.openTransmissionRevisions ?: false,
                    ),
                    callbacks = navHostCallbacks(navigator, scope, drawerState, navState),
                    sessionId = sessionId,
                    context = context,
                    drawerLive = drawerLive,
                    audioPlayer = audioPlayer,
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
 * [canReturnToFrequency]'s.
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
        navState.openThreadId.value != null
    // Round 9/10, register R-276: `Log` reached via `Frequency-Change`'s "The N overs" — a
    // drill-in-shaped pop in every way that matters here, kept as its own named condition only
    // because it is not one of the four ids [isDrillInOpen] already checks.
    val canReturnToFrequency = current == ReaderDestination.LOG && navState.logFrequencyOrigin.value != null
    // Round 11, register R-133: `Earlier nights` reached via `Settings-Storage`'s `Review` link —
    // see this function's own doc comment above.
    val canReturnToSettingsStorage = current == ReaderDestination.EARLIER_NIGHTS &&
        navState.pendingReviewSessionId.value != null

    BackHandler(
        enabled = drawerState.isOpen || isDrillInOpen || canReturnToFrequency || canReturnToSettingsStorage,
    ) {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            isDrillInOpen -> navState.closeDrillIns()
            canReturnToFrequency -> {
                val frequencyHz = navState.logFrequencyOrigin.value
                navState.logFrequencyOrigin.value = null
                navState.pendingLogFilter.value = null
                if (frequencyHz != null) {
                    navState.openedFrom.value = ReaderDestination.LOG
                    navState.frequencyInitialView.value = FrequencyDetailView.Change
                    navState.openFrequencyHz.value = frequencyHz
                }
            }
            canReturnToSettingsStorage -> {
                navState.pendingReviewSessionId.value = null
                navigator.openSettings(SettingsScreenId.STORAGE)
            }
        }
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
    // Round 9, register R-276: the filter `LogContent` should seed on its own next fresh
    // composition — see [openLogFilteredByFrequency]. A plain `remember`, not `rememberSaveable`:
    // it only has to survive until `LogContent`'s own `initialFilter` read, which happens
    // synchronously within the same process this tap fired in — by the time a `recreate()` could
    // ever observe this, `LogContent`'s *own* `rememberSaveable` `selection` has already captured
    // the value into the `SaveableStateRegistry` (the same reasoning `searchResult` below rests
    // on: WP7's own doc comment on why `SearchResult` needs no Saver of its own).
    val pendingLogFilter: MutableState<LogFilterSelection?>,
    // Round 9, register R-276: the frequency drill-in to reopen when the operator backs out of a
    // `Log` reached via `openLogFilteredByFrequency` — `rememberSaveable` (a plain `Long?`, no
    // custom Saver needed) since, unlike [pendingLogFilter], this is read only by a later user
    // action (the back gesture), which can genuinely happen after a `recreate()`.
    val logFrequencyOrigin: MutableState<Long?>,
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
    // the same way [logFrequencyOrigin] doubles as both `Frequency-Change`'s return target and its
    // own "did we come this way" check. `rememberSaveable`, not a plain `remember`: unlike
    // [pendingLogFilter] (read synchronously by `LogContent`'s own first composition),
    // `SessionsContent.initialSessionId` is read on ITS first composition and this value is read
    // again later by the back gesture — the same reasoning [logFrequencyOrigin] itself rests on.
    val pendingReviewSessionId: MutableState<String?>,
    // R-840: which of `Earlier nights`' two screens [pendingReviewSessionId] should land on — see
    // [NavSeed.reviewSessionView]'s own doc comment. Only meaningful alongside
    // [pendingReviewSessionId], the same companion relationship [frequencyInitialView] already has
    // to [openFrequencyHz]; `rememberSaveable`, the same reasoning [frequencyInitialView] rests on.
    val reviewSessionView: MutableState<ReviewSessionView>,
) {
    fun closeDrillIns() {
        openTransmissionId.value = null
        openStationId.value = null
        openFrequencyHz.value = null
        openThreadId.value = null
        // Reset here too, not only where it is set true: this runs on every ordinary way of
        // reaching `Capture` (the drawer row, the live bar's own tap target) and must not leave a
        // stale `true` from an earlier `Settings-Capture` "Meter" tap open the meter again.
        openCaptureLevelMeter.value = false
        // Same reasoning, round 9: any ordinary way of reaching `Log` (the drawer row) must not
        // silently reapply a stale filter or resurrect a "back to the frequency" promise a normal
        // navigation never made.
        pendingLogFilter.value = null
        logFrequencyOrigin.value = null
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
     * R-276 (`Frequency-Change.dc.html`'s "The N overs"): routes to `Log`, filtered to
     * [frequencyHz] and [window], from the frequency drill-in currently showing at
     * [currentFrequencyHz] (always non-null in practice — this only ever fires from inside
     * `FrequencyDetailContent`, which only composes while `openFrequencyHz` already holds one).
     * [closeDrillIns] first clears the live frequency drill-in (its id has absolute priority over
     * `current` in `NavHostBody`'s own dispatch — leaving it set would keep showing the frequency
     * screen no matter what `current` became) and any stale pending filter from a previous trip;
     * [currentFrequencyHz] is saved into [logFrequencyOrigin] *after* that clear so back can reopen
     * the same frequency's drill-in — landing on `FrequencyDetailContent`'s own root/`NONE`
     * sub-screen, not the `Frequency-Change` sub-screen this was opened from (that sub-screen is
     * `FrequencyDetailContent`'s own internal, unexported state — restoring it exactly would need a
     * change to that file, WP8's, not lead-approved this round; reported in this round's own
     * report/CHANGELOG, not silently pretended).
     */
    fun openLogFilteredByFrequency(currentFrequencyHz: Long?, frequencyHz: Long, window: TimeWindow) {
        closeDrillIns()
        logFrequencyOrigin.value = currentFrequencyHz
        pendingLogFilter.value = LogFilterSelection(
            frequencyHz = frequencyHz,
            fromMillis = window.startMillis,
            toMillis = window.endMillis,
        )
    }

    /**
     * Round 11, register R-133 (`Settings-Storage`'s "Next deletion … Review" link,
     * `SettingsContent.onReviewSession`): routes to `Earlier nights`, seeded on [sessionId]'s own
     * detail (DG04) — the same shape as [openLogFilteredByFrequency]. [closeDrillIns] first clears
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
    // Round 9, register R-276 — see `NavHostNavState.pendingLogFilter`/`logFrequencyOrigin`'s own
    // doc comments for why one is a plain `remember` and the other `rememberSaveable`.
    val pendingLogFilter = remember { mutableStateOf(seed?.pendingLogFilter) }
    val logFrequencyOrigin = rememberSaveable { mutableStateOf<Long?>(null) }
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
        pendingLogFilter,
        logFrequencyOrigin,
        frequencyInitialView,
        openStationSubScreen,
        pendingReviewSessionId,
        reviewSessionView,
    )
}

/** Builds [NavHostBody]'s [NavHostCallbacks] — split out of [OrtNavHost] purely to keep that
 * function under detekt's `LongMethod` limit, the same reason [NavHostBody]/[DestinationContent]
 * were themselves split out before this round. */
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
        onOpenCapture = {
            currentState.value = ReaderDestination.CAPTURE
            navState.closeDrillIns()
        },
        onOpenTransmission = { id ->
            navState.onOpenDrillIn(currentState.value) { navState.openTransmissionId.value = id }
        },
        onOpenStation = { id ->
            navState.onOpenDrillIn(currentState.value) { navState.openStationId.value = id }
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
        // Round 6, register R-132: `SettingsContent`'s own `onOpenLevelMeter` (its doc comment:
        // "the host is expected to wire it the same way it wires every other cross-package
        // drill-in") — `Settings-Capture`'s `Meter` action switches to `Capture` and asks
        // `CaptureStatusContent` to land directly on `LevelMeterScreen`.
        onOpenLevelMeter = {
            currentState.value = ReaderDestination.CAPTURE
            navState.openCaptureLevelMeter.value = true
        },
        // Round 9, register R-276: `Frequency-Change`'s "The N overs" — real now that WP5 merged
        // `LogContent.initialFilter` (confirmed by reading `ui/screens/LogContent.kt` before wiring
        // this). See `NavHostNavState.openLogFilteredByFrequency`'s own doc comment for exactly
        // what this does and does not restore on back.
        onOpenOvers = { hz, window ->
            navState.openLogFilteredByFrequency(navState.openFrequencyHz.value, hz, window)
            currentState.value = ReaderDestination.LOG
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
 * `fillMaxSize()`) and, register R-178, the banner-height top padding
 * [org.ort.app.ui.failures.FailureHost] reports — bundled, same reason as
 * [NavHostIds]/[NavHostCallbacks], so [NavHostBody] stays under detekt's `LongParameterList`
 * rather than growing a parameter for the R-178 addition. */
private data class NavHostLayout(val modifier: Modifier, val contentTopPadding: Dp = 0.dp)

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
    val logInitialFilter: LogFilterSelection?,
    val reviewSessionId: String?,
    // Round 14 (after WP5 merged `LogContent.initialSheetOpen`) — see `NavSeed.logSheetOpen`'s own
    // doc comment.
    val logInitialSheetOpen: Boolean,
    // R-840 — see `NavHostNavState.reviewSessionView`'s own doc comment.
    val reviewSessionView: ReviewSessionView,
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

/** [NavHostBody]'s navigation actions, bundled for the same reason as [NavHostIds]. */
private data class NavHostCallbacks(
    val onOpenDrawer: () -> Unit,
    val onSearchDestination: () -> Unit,
    val onCloseDrillIns: () -> Unit,
    val onOpenCapture: () -> Unit,
    val onOpenTransmission: (String) -> Unit,
    val onOpenStation: (String) -> Unit,
    val onOpenFrequency: (Long) -> Unit,
    val onOpenThread: (String) -> Unit,
    val onOpenStations: () -> Unit,
    val onOpenModels: () -> Unit,
    val onOpenLevelMeter: () -> Unit,
    val onOpenOvers: (Long, TimeWindow) -> Unit,
    // Round 11, register R-133: see `navHostCallbacks`'s own construction site.
    val onReviewSession: (String) -> Unit,
    // Round 17, register R-432: see `navHostCallbacks`'s own construction site and
    // `ActivationThreadRouting`'s own doc comment for what resolves the id this expects.
    val onOpenActivationThread: (String) -> Unit,
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
    audioPlayer: org.ort.app.ui.audio.TransmissionAudioPlayer,
    search: SearchHostState,
) {
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
            NavHostDispatch(ids, callbacks, sessionId, context, audioPlayer, search)
        }

        // R-022: pinned to the bottom of every destination and drill-in alike, while a session
        // runs — `null` (nothing pinned) covers both "no session" and "session idle", never a bar
        // with nothing real to show. Now and Capture are the two destinations that already pin
        // their own (`NowScreen`/`CaptureStatusScreen`, WP4's — confirmed by reading both before
        // assuming otherwise); rendering the host's bar there too would stack two.
        val embedsOwnLiveBar = !isDrillIn &&
            (ids.current == ReaderDestination.NOW || ids.current == ReaderDestination.CAPTURE)
        val shownLiveBar = if (!embedsOwnLiveBar) drawerLive.liveBar else null
        if (shownLiveBar != null) {
            // Wrapped, not passed as `LiveBar`'s own `modifier` (that param reaches only its
            // inner clickable `Row`, not the 1dp top-edge strip above it — `ui/components/
            // LiveBar.kt` is outside this row to edit for a more precise seam) — a plain `Column`
            // sibling below the weighted content box above; Compose's own layout already gives
            // that box exactly the remaining height, no measured-height state needed (R-957).
            // `testTag` (register R-262): a stable node for a test to read this wrapper's own
            // `boundsInRoot`, independent of `LiveBar`'s own runtime-varying label text.
            Box(modifier = Modifier.testTag("live-bar-clearance")) {
                LiveBar(state = shownLiveBar, onClick = callbacks.onOpenCapture)
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
) {
    val scope = rememberCoroutineScope()
    when {
        ids.transmissionId != null -> TransmissionDetailContent(
            context = context,
            transmissionId = ids.transmissionId,
            player = audioPlayer,
            onBack = callbacks.onCloseDrillIns,
            onOpenTransmission = callbacks.onOpenTransmission,
            backLabel = ids.openedFrom.label,
            initialRevisionsOpen = ids.transmissionInitialRevisionsOpen,
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

        else -> DestinationContent(
            current = ids.current,
            initialState = ids.contentInitialState,
            sessionId = sessionId,
            context = context,
            search = search,
            callbacks = callbacks,
            modifier = Modifier.fillMaxSize(),
        )
    }
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
    modifier: Modifier,
) {
    val settingsInitialScreen = initialState.settingsInitialScreen
    val openCaptureLevelMeter = initialState.openCaptureLevelMeter
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

        ReaderDestination.SEARCH -> SearchDestinationContent(search, onOpenTransmission, content)

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
        ReaderDestination.CAPTURE -> CaptureStatusContent(
            context = context,
            sessionId = sessionId,
            modifier = content,
            openLevelMeter = openCaptureLevelMeter,
        )

        // ui-conformance-plan WP10 (register R-090/R-091/R-092/R-107): all three dispatch to
        // WP10's own real content composables now that they are on this branch. Round 5
        // (R-090/R-139/F6/F9): `initialScreen` is real now — `navigator.openSettings` writes
        // `settingsInitialScreen`, read fresh here every time `SETTINGS` becomes `current`. Round
        // 7: `onSearch` is real now — `SettingsRootScreen` draws its own header (see `NavHostBody`'s
        // own header-skip comment for why this host draws none of its own for `SETTINGS`).
        ReaderDestination.SETTINGS ->
            org.ort.app.ui.settings.SettingsContent(
                context = context,
                onDrawer = onOpenDrawer,
                modifier = content,
                initialScreen = settingsInitialScreen,
                // Round 6, register R-132: real now — see `NavHostCallbacks.onOpenLevelMeter`'s
                // own comment above.
                onOpenLevelMeter = callbacks.onOpenLevelMeter,
                onSearch = callbacks.onSearchDestination,
                // Round 11, register R-133: real now — WP10 merged
                // `SettingsContent.onReviewSession` (confirmed by reading `ui/settings/
                // SettingsContent.kt` before wiring this) — see `NavHostCallbacks.onReviewSession`'s
                // own comment above.
                onReviewSession = callbacks.onReviewSession,
            )

        // Round 5 (R-092/R-107): `onOpenTransmission` is real now — WP10 merged it (confirmed by
        // reading `ui/digest/SessionsContent.kt` before wiring this). `onOpenDrillIn` (this file's
        // `OrtNavHost`, where `callbacks.onOpenTransmission` is built) records `openedFrom = current`
        // at the moment the tap fires, which is `EARLIER_NIGHTS` for every tap this dispatch can
        // ever produce — so the transmission drill-in's `backLabel` reads `ReaderDestination
        // .EARLIER_NIGHTS.label`, "Earlier nights", with no extra state needed here.
        // Round 11, register R-133: `initialSessionId` is real now — WP10 merged it (`e390c60`,
        // confirmed by reading `ui/digest/SessionsContent.kt` before wiring this) — seeds the
        // `Review` link's own session detail; `null` (every ordinary way of reaching this
        // destination) is that composable's own existing default, its own list root.
        ReaderDestination.EARLIER_NIGHTS ->
            org.ort.app.ui.digest.SessionsContent(
                context = context,
                onDrawer = onOpenDrawer,
                modifier = content,
                onOpenTransmission = onOpenTransmission,
                initialSessionId = reviewSessionId,
                // R-840: real now — WP10 merged `SessionsContent.openDigest` (confirmed by reading
                // `ui/digest/SessionsContent.kt` before wiring this) — lands `Earlier nights` on
                // that session's own `Digest` (DG01/DG05) instead of its `Session` (DG04) detail,
                // seeded via `NavSeed.reviewSessionView = DIGEST`.
                openDigest = reviewSessionView == ReviewSessionView.DIGEST,
            )

        // Round 12, R-350: `onOpenModels` real now (WP10's `94c946c`) — same callback as `Now`'s
        // above. Call itself extracted to [ImproveRecordsContent] purely to keep this function
        // under detekt's `LongMethod` limit.
        ReaderDestination.IMPROVE_RECORDS ->
            ImproveRecordsContent(context, onOpenDrawer, content, callbacks.onOpenModels)
    }
}

/** [DestinationContent]'s `IMPROVE_RECORDS` branch, split out purely to keep that function under
 * detekt's length limit — the same reason [ThreadDetailContent] below was already split out. */
@Composable
private fun ImproveRecordsContent(
    context: android.content.Context,
    onDrawer: () -> Unit,
    modifier: Modifier,
    onOpenModels: () -> Unit,
) {
    org.ort.app.ui.improve.ImproveContent(
        context = context,
        onDrawer = onDrawer,
        modifier = modifier,
        onOpenModels = onOpenModels,
    )
}

/** [DestinationContent]'s `SEARCH` branch, split out purely to keep that function under detekt's
 * length limit — the same reason [ImproveRecordsContent] above already was. Round 14:
 * `initialQuery`/`submitOnStart`/`initialFiltersOpen` real now — WP7 merged them (confirmed by
 * reading `ui/screens/SearchContent.kt` before wiring this). */
@Composable
private fun SearchDestinationContent(search: SearchHostState, onOpen: (String) -> Unit, modifier: Modifier) {
    SearchContent(
        input = search.input,
        result = search.result,
        onInputChange = search.onInputChange,
        onSearch = search.onSearch,
        onOpen = onOpen,
        modifier = modifier,
        onBack = search.onBack,
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
