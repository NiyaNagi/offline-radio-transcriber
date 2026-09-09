package org.ort.app.ui.navigation

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.ort.app.debug.Scenarios
import org.ort.app.ui.ReaderActivity
import org.ort.app.ui.failures.AssetSwapOption
import org.ort.app.ui.failures.AssetSwapViewState
import org.ort.app.ui.failures.DebugFailureOverride
import org.ort.app.ui.failures.FailurePresentation
import org.ort.app.ui.settings.SettingsScreenId
import org.ort.app.ui.settings.SharedPreferencesSettingsStore
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.StorageForecast
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/** Shorthand for this file's one specific [AndroidComposeTestRule] instantiation â€” keeps later
 * receiver-type usages under ktlint's line-length limit without wrapping generics awkwardly. */
private typealias ReaderComposeTestRule = AndroidComposeTestRule<ActivityScenarioRule<ReaderActivity>, ReaderActivity>

/** Round 5's helpers were written against this shorter name; both spellings name the same rule type. */
private typealias ReaderComposeRule = ReaderComposeTestRule

/**
 * R-129 (V3 Reader validation @3e2d4ee, `results/ui-audit/register.md`): the drawer's own report
 * â€” "Robolectric did not catch it because no `SaveableStateRegistry` is installed under
 * `createComposeRule`" â€” is exactly right: every test this package (and WP5/6/7/8's own) shipped
 * before this one composed the reader's screens through `createComposeRule()`
 * (`OrtNavHostDestinationDispatchTest`, `ReaderAccessibilityTest`) or by calling a `*Content`
 * composable directly (`CaptureStatusContentTest` and siblings) â€” neither path installs a real
 * `SaveableStateRegistry`, so a `rememberSaveable` call on a type with no registered `Saver`
 * (`LogContent.kt`'s `LogQuickFilterId`, a plain `sealed interface`) never gets the chance to throw.
 * A real `Activity`, launched the way the validator launches one, does install one â€” which is
 * exactly what crashed L01â€“L05 100% of the time on the device and never once in this suite.
 *
 * This class closes that gap: for every [ReaderDestination] with [ReaderDestination.hasScreen], and
 * for the four drill-ins reachable from real list rows, it launches a real [ReaderActivity] (via
 * [ActivityScenarioRule], the same disposal path [org.ort.app.ui.ReaderActivityTest] already uses,
 * built here with a custom launch `Intent` rather than that class's default one so each case can
 * open straight to its own destination through [ReaderActivity.EXTRA_DESTINATION] â€” the
 * [ReaderNavigator] seam WP3's round 3 addendum built), asserts the destination's own root actually
 * composed (never just "no exception" â€” a concrete node from that screen must be displayed), then
 * calls `scenario.recreate()` and asserts the same is still true afterward. `recreate()` is what
 * actually exercises the savers R-129 is about: Robolectric tears down and rebuilds the `Activity`
 * exactly as a real configuration change or process restore would, driving every `rememberSaveable`
 * on screen through a real save-then-restore round trip a bare `createComposeRule()` never attempts.
 *
 * A real session (one [SessionEntity], one [TransmissionEntity] with a `stationId`, a `frequencyHz`,
 * and a `threadId` so it groups) is seeded before every case, so `Stations`/`Frequencies` (both
 * session-independent catalog reads) and their drill-ins render real populated state rather than an
 * empty one that could hide a saver bug specific to a real row's own state. [destinationIntent]'s own
 * doc comment explains why only the thread drill-in's case actually hands `ReaderActivity` a non-null
 * [ReaderActivity.EXTRA_SESSION_ID] â€” every other case launches with none.
 *
 * `R_129_LOG_composes_and_survives_recreation` is written exactly like every other case here â€” no
 * `assertThrows`, no inversion â€” and is expected to **fail today**, for the one real, filed reason
 * (`LogContent.kt:50`'s unsaved `rememberSaveable<LogQuickFilterId>`), while WP5 fixes it
 * concurrently in its own file. Once that fix lands this case passes with no change here. Every
 * other case in this class, run alone (`--tests
 * "org.ort.app.ui.navigation.ReaderActivityDestinationSmokeTest"`) or as part of a fresh JVM, is
 * green.
 *
 * **This class run as part of the full, unforked `:app:testDebugUnitTest` alongside everything else
 * could leave the shared JVM's Compose test environment unable to reach idle for whatever unrelated
 * test happened to compose next** (`AppNotIdleException`, "Compose did not get idle... infinite
 * composition loop", surfacing in a completely different file â€” `ActivityPatternChartTest` and
 * `CaptureStatusScreenTest` both observed, on different runs, neither touched by this class at all)
 * â€” not this class's own cases failing, a *later* one's. [ReaderActivity]'s own `resolveSessionId`
 * doc comment already names the exact mechanism and origin of this: "building a real `ReaderActivity`
 * with a non-null session id starts `OrtNavHost`'s ... polling loops ... which a Robolectric-driven
 * test never gets a chance to cleanly cancel," and records that its own author avoided it precisely
 * by testing `resolveSessionId` as a pure function rather than building a real activity â€” the same
 * constraint R-129 asks this class to cross anyway, since only a real `Activity`'s real
 * `SaveableStateRegistry` can catch a `rememberSaveable` bug at all.
 *
 * **Fixed at the root, not chased further in this file:** `app/build.gradle.kts` now runs this one
 * class as its own Gradle `Test` task (`smokeTestDebugUnitTest`), excluded from `testDebugUnitTest`
 * â€” see that file's own comment. A different `Test` task is always a fresh JVM worker process,
 * never one shared with `testDebugUnitTest`'s own run, so whatever this class's own real `Activity`
 * instances leave behind in the Compose test environment can no longer reach any test outside this
 * class, regardless of what it is or whether it is ever fully cleaned up. `results/ui-audit/README.md`'s
 * own gate list names both tasks now â€” `check`/`build` still run this class, just in its own process.
 * Three code-level mitigations stay in this file too, on the theory that a smaller footprint here is
 * still worth having even with the task split doing the real isolating: [runReaderActivity] drives
 * every `ActivityScenarioRule` through `Lifecycle.State.DESTROYED` explicitly (rather than trusting
 * disposal-order alone) and turns `mainClock.autoAdvance` off first, so no live poll loop's timer can
 * fire and hand the disposing composition one more frame to recompose while that teardown runs; and
 * [destinationIntent] hands `ReaderActivity` a non-null session id â€” the one thing that starts
 * `LogContent`/`ThreadContent`'s own *additional*, session-gated poll loops on top of the drawer's
 * and `NowContent`'s own unconditional ones â€” only for the one case that actually needs real seeded
 * row data to reach its drill-in.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderActivityDestinationSmokeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val sessionId = "smoke-session"

    @Before
    fun seedSession(): Unit = runBlocking {
        // Deliberately the same file-backed `OrtDatabase.create(context)` every real `*Polling`
        // object opens (`ReaderPollingTest.kt`'s own doc comment names why an in-memory instance
        // here would not share state with what `ReaderActivity`'s real screens read).
        val db = OrtDatabase.create(context)
        db.sessionDao().insert(
            SessionEntity(
                id = sessionId,
                startedAt = 0L,
                // Round 15 (WP10's own R-449 report): `endedAt = null` used to be harmless — every
                // real reader of `startedAt`/`endedAt` here only cares about *transmission* timing
                // (`startedAtUtc`/`endedAtUtc` below), not the session's own span. `DigestPolling
                // .sessionCoverageBuckets` (WP10's own R-449 fix, real now) buckets by *elapsed*
                // hour from `startedAt` to `endedAt ?: nowMillis` — with `startedAt = 0L` (epoch)
                // and `endedAt = null`, that span became "epoch to the real wall clock at test run
                // time," several hundred thousand hourly buckets, and a genuine `OutOfMemoryError`
                // rendering `ActivityPatternChart`'s own `HourBar` per bucket the one time this
                // seeded session's own detail (DG04, reached via Settings-Storage's Review link)
                // actually composes — confirmed directly, reproduced then fixed here. A real,
                // bounded end (1 hour after `startedAt`) is honest for a "session" fixture that was
                // never meant to model a real multi-hour night, and keeps every other reader of
                // `sessionId` in this class (none of which read `endedAt` today) unaffected.
                endedAt = 3_600_000L,
                profileId = null,
                deviceTier = null,
                appVersion = "test",
                terminationReason = null,
                sourceId = null,
                schemaVersion = OrtDatabase.SCHEMA_VERSION,
            ),
        )
        db.transmissionDao().insert(
            TransmissionEntity(
                id = "TX1",
                sessionId = sessionId,
                // Non-null: `LogViewData`/`ThreadListMapper` both group by this â€” the thread
                // drill-in (and Log's own QSO group header) need a real one to be reachable.
                threadId = "TH1",
                startedAtUtc = 0L,
                endedAtUtc = 1_000L,
                durationMs = 4_200L,
                audioFormat = "flac/16k/mono",
                preRollMs = 200,
                postRollMs = 200,
                frequencyHz = FREQUENCY_HZ,
                frequencyProvenance = "measured",
                mode = null,
                signalStrength = 7.0,
                channelName = null,
                voiceprintId = null,
                attributionState = AttributionState.CONFIRMED,
                stationId = STATION_ID,
                attributionConfidence = 0.9,
                attributionSourceTransmissionId = null,
                processingState = TransmissionState.COMPLETE,
                rejectionReason = null,
                samplePosition = 1L,
                monotonicStartNanos = 0L,
                utcOffsetMinutes = 0,
                calibrationId = null,
                executionProvider = null,
            ),
        )
        // `StationPolling.listStations` reads the separate `station` catalog table
        // (`ActivityDao.listStations`), never derived from `transmission.stationId` at read time
        // (confirmed by reading `ActivityDao.kt` before writing this) â€” the `Stations` list and its
        // drill-in need a real row here too, not just the transmission's own `stationId` column.
        db.catalogDao().insert(
            StationEntity(
                id = STATION_ID,
                callsign = STATION_ID,
                firstHeardAt = 0L,
                lastHeardAt = 0L,
                transmissionCount = 1,
                isUserPinned = false,
                notes = null,
                userName = null,
                frequenciesHeard = null,
                activityByHourDow = null,
                potaRefs = null,
                spokenGrids = null,
                ituRegionFromPrefix = null,
                overCountsByAttributionState = null,
            ),
        )
    }

    // -- destinations -------------------------------------------------------------------------

    @Test
    fun `R_129_NOW_composes_and_survives_recreation`() = assertComposesAndSurvives(ReaderDestination.NOW)

    @Test
    fun `R_129_LOG_composes_and_survives_recreation`() = assertComposesAndSurvives(ReaderDestination.LOG)

    // Round 5 (R-200): `SEARCH` no longer shows the host's `ScreenHeader` ("Open navigation") â€”
    // `SearchContent` now draws its own back chevron instead (`SearchScreen.kt`'s
    // `search-back-chevron`, `contentDescription = "Back"`) â€” so this case checks for that marker,
    // not the default `assertComposesAndSurvives` every other destination still uses.
    @Test
    fun `R_129_SEARCH_composes_and_survives_recreation`() =
        assertComposesAndSurvives(ReaderDestination.SEARCH, expectedContentDescription = "Back")

    @Test
    fun `R_129_THREADS_composes_and_survives_recreation`() = assertComposesAndSurvives(ReaderDestination.THREADS)

    @Test
    fun `R_129_STATIONS_composes_and_survives_recreation`() = assertComposesAndSurvives(ReaderDestination.STATIONS)

    @Test
    fun `R_129_FREQUENCIES_composes_and_survives_recreation`() =
        assertComposesAndSurvives(ReaderDestination.FREQUENCIES)

    @Test
    fun `R_129_EARLIER_NIGHTS_composes_and_survives_recreation`() =
        assertComposesAndSurvives(ReaderDestination.EARLIER_NIGHTS)

    @Test
    fun `R_129_CAPTURE_composes_and_survives_recreation`() = assertComposesAndSurvives(ReaderDestination.CAPTURE)

    @Test
    fun `R_129_IMPROVE_RECORDS_composes_and_survives_recreation`() =
        assertComposesAndSurvives(ReaderDestination.IMPROVE_RECORDS)

    // Round 7 (R-090's double-header saga, resolved for good this round): `SettingsRootScreen` now
    // draws its own `ScreenHeader` (round 6 found the sub-screen half of this; `SettingsContent`'s
    // own doc comment named the fix and the exact removal this host now makes), so this host draws
    // neither a `ScreenHeader` nor a `DrillInHeader` for any of `SETTINGS` â€” asserted directly
    // (exactly one "Open navigation", zero "Back to Settings"), not left to a `recreate()` timeout
    // to catch a regression by accident, the way the prior double-/zero-header incidents both were.
    @Test
    fun `R_129_SETTINGS_composes_and_survives_recreation`() {
        runReaderActivity(ReaderDestination.SETTINGS) { rule ->
            rule.waitUntilContentDescriptionExists("Open navigation")
            rule.assertExactlyOneContentDescription("Open navigation")
            rule.assertExactlyOneContentDescription("Search")

            rule.activityRule.scenario.recreate()
            rule.waitForIdle()

            rule.waitUntilContentDescriptionExists("Open navigation")
            rule.assertExactlyOneContentDescription("Open navigation")
        }
    }

    // Round 5 (R-090/R-139/F6/F9): `EXTRA_DESTINATION=SETTINGS` with `EXTRA_SETTINGS_SCREEN` set
    // lands directly on a sub-screen â€” `SettingsContent.initialScreen`'s real target now â€” rather
    // than the root every other `SETTINGS` case in this class exercises. `RIG` chosen arbitrarily
    // among the eight non-`ASSETS` sub-screens (`ASSETS` dispatches to `ModelsContent`, a different
    // package's own file, already covered by its own tests); every sub-screen shares
    // `SettingsSubScreen`'s one dispatch and the same `DrillInHeader(parentLabel = "Settings", ...)`
    // this asserts on, so this one case stands for all eight.
    //
    // Round 7: the double-header this case's own round-5/6 doc comment reported (the host's
    // `ScreenHeader` and this sub-screen's own `DrillInHeader` both showing) is fixed now that
    // `SettingsRootScreen` owns the header instead of the host â€” asserted directly: exactly one
    // "Back to Settings", zero "Open navigation" (a sub-screen draws no drawer-icon header at all).
    @Test
    fun `R_129_SETTINGS_RIG_initialScreen_composes_and_survives_recreation`() {
        runReaderActivity(ReaderDestination.SETTINGS, settingsScreen = SettingsScreenId.RIG) { rule ->
            rule.waitUntilContentDescriptionExists("Back to Settings")
            rule.assertExactlyOneContentDescription("Back to Settings")
            check(
                rule.onAllNodes(hasContentDescription("Open navigation", substring = true))
                    .fetchSemanticsNodes().isEmpty(),
            ) { "expected no host ScreenHeader ('Open navigation') on a SETTINGS sub-screen" }

            rule.activityRule.scenario.recreate()
            rule.waitForIdle()

            rule.waitUntilContentDescriptionExists("Back to Settings")
            rule.assertExactlyOneContentDescription("Back to Settings")
        }
    }

    // Round 6 (register R-132): `Settings-Capture`'s `Meter` action (`SettingsCaptureScreen.kt`'s
    // `TextAction(text = "Meter", onClick = onOpenLevelMeter)`) now has a real target â€”
    // `NavHostCallbacks.onOpenLevelMeter` switches to `Capture` and asks `CaptureStatusContent` to
    // land directly on `LevelMeterScreen` (`openLevelMeter`, this round's own new parameter on that
    // file). Landing on `Settings-Capture` via `initialScreen` first (round 5's own seam) is what
    // makes `Meter` reachable without a real tap sequence through the `Settings` root.
    @Test
    fun `R_132_settings_capture_meter_opens_the_level_meter`() {
        runReaderActivity(ReaderDestination.SETTINGS, settingsScreen = SettingsScreenId.CAPTURE) { rule ->
            rule.waitUntilContentDescriptionExists("Back to Settings")
            rule.onNode(hasText("Meter") and hasClickAction()).performClick()
            // `LevelMeterScreen.kt`'s own `DrillInHeader(parentLabel = "Capture", ...)`.
            rule.waitUntilContentDescriptionExists("Back to Capture")

            rule.activityRule.scenario.recreate()
            rule.waitForIdle()

            rule.waitUntilContentDescriptionExists("Back to Capture")
        }
    }

    // Round 9, register R-276: `Frequency-Change`'s "The N overs" now has a real destination â€”
    // `NavHostCallbacks.onOpenOvers` routes to `Log`, seeded with `LogFilterSelection(frequencyHz,
    // fromMillis, toMillis)` via `LogContent`'s new `initialFilter` (WP5's own commit). Reaching
    // the real button through a real tap sequence needs a genuine "busier than usual" pattern â€”
    // see `seedFrequencyOversPattern`'s own doc comment for why this is the one case in this class
    // with its own seeding, not the shared `@Before`. The filter sheet's own "Show N overs" count
    // line (`LogFilterSheet.kt`) is this test's proof the window narrowed the corpus for real â€”
    // seeded with three "usual" nights (one over each) besides "tonight"'s [TONIGHT_OVER_COUNT],
    // so a filter that silently fell back to "everything ever heard on this frequency" would read
    // `TONIGHT_OVER_COUNT + 3`, not `TONIGHT_OVER_COUNT` â€” a wrong-count failure this test can
    // actually distinguish from "no filter applied at all", not merely a chip rendering selected.
    @Test
    fun `R_276_frequency_overs_link_opens_Log_filtered_to_that_frequency_and_window`() {
        val tonightSessionId = seedFrequencyOversPattern()
        runReaderActivity(ReaderDestination.FREQUENCIES, sessionId = tonightSessionId) { rule ->
            rule.waitUntilContentDescriptionExists(OVERS_FREQUENCY_LABEL)
            rule.onNodeWithContentDescription(OVERS_FREQUENCY_LABEL, substring = true).performClick()
            // `FrequencyDetailScreen`'s own `DrillInHeader(parentLabel = backLabel, ...)`.
            rule.waitUntilContentDescriptionExists("Back to Frequencies")

            // `FrequencyHeaderSection.kt`'s `TextAction(text = "Busier than usual â€” see what
            // changed", onClick = onOpenChange)` â€” only rendered while `busierThanUsual` holds,
            // which `seedFrequencyOversPattern`'s real multi-night pattern makes true.
            rule.onNode(hasText("Busier than usual", substring = true) and hasClickAction()).performClick()
            // `FrequencyChangeScreen`'s own `DrillInHeader(parentLabel = state.label, ...)` â€”
            // `state.label` is the frequency's own label, `OVERS_FREQUENCY_LABEL`.
            rule.waitUntilContentDescriptionExists("Back to $OVERS_FREQUENCY_LABEL")

            // `FrequencyChangeScreen.kt`'s own `TextAction(text = "The ${pluralize(state.overCount,
            // "over")}", onClick = { onOpenOvers(state.frequencyHz, state.window) })`.
            rule.onNode(hasText("The $TONIGHT_OVER_COUNT overs") and hasClickAction()).performClick()

            // `Log` is a normal destination â€” the host's own `ScreenHeader` renders for it (unlike
            // `SETTINGS`/`SEARCH`, round 7/5's own exceptions), immediately, before its own
            // `LaunchedEffect` poll has necessarily run even once â€” `waitUntilChipSelected` (not
            // `waitUntilContentDescriptionExists("Open navigation")` followed by an immediate
            // assertion) is what actually gives that first real poll time to land; the frequency's
            // own quick-filter chip rendering selected (Compose's standard `Selected` semantics
            // property, set by `FilterChip`'s own `.selectable(selected = ...)`) is the real,
            // honest proof this landed pre-filtered, not just that some destination opened.
            rule.waitUntilChipSelected(OVERS_FREQUENCY_LABEL)

            // The count line: opens the filter sheet and waits for its real, polled
            // `matchingCount` â€” narrowed to the frequency *and* tonight's window, not the whole
            // corpus (see this test's own doc comment on why `TONIGHT_OVER_COUNT` alone, not
            // `TONIGHT_OVER_COUNT + 3`, is what proves the window, not just the frequency, applied).
            rule.onNode(hasText("Filter") and hasClickAction()).performClick()
            rule.waitUntilTextExists("Show $TONIGHT_OVER_COUNT overs")

            rule.activityRule.scenario.recreate()
            rule.waitForIdle()

            rule.waitUntilChipSelected(OVERS_FREQUENCY_LABEL)

            // Round 10 (WP8 shipped `FrequencyDetailContent.initialView`): system back from here
            // reopens the frequency drill-in landing directly on `Frequency-Change` â€” FQ03 itself,
            // not FQ02's plain detail root â€” via `OrtNavHost`'s own `BackHandler`. Invoked through
            // the real `OnBackPressedDispatcher` every `ComponentActivity` (this one included)
            // installs, the same mechanism a device's system back gesture ultimately reaches â€” not
            // `Espresso.pressBack()`, which this module carries no dependency on.
            //
            // Round 11 (R-333, host + both sheets' own `BackHandler`s merged at a731d7a): the
            // filter sheet opened above (line ~357) was never explicitly closed, and its own
            // `sheetOpen` is `rememberSaveable` â€” `recreate()` above honestly restores it open, so
            // it is still showing here. `LogContent`'s own `BackHandler(enabled = sheetOpen)`
            // (WP5's Log half of R-333) now correctly claims the *first* back press to close that
            // sheet, exactly as a real device does (back dismisses an open sheet before it leaves
            // the screen underneath) â€” a second press is what actually pops Log. One press was
            // enough before R-333 only because nothing on this screen claimed back at all yet.
            rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            rule.waitForIdle()
            rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            rule.waitForIdle()
            rule.waitUntilContentDescriptionExists("Back to $OVERS_FREQUENCY_LABEL")
        }
    }

    // Register R-333 (halt): one system back press exited the whole app instead of popping one
    // level â€” the host had no generic `BackHandler` at all. `D01` opened from a real `Log` row is
    // the same host-tracked `openTransmissionId` drill-in every rejected-detail (F04) row also
    // uses (confirmed by reading `LogScreen.kt`'s own `RejectedRow(onClick = { onOpen(item.id) })`
    // before relying on it â€” this one case stands for both). [Lifecycle.State.RESUMED] surviving
    // each back press, not just `DESTROYED`/finishing, is the actual regression this halt was
    // about â€” that check comes first each time, before the destination-specific one.
    @Test
    fun `R_333_system_back_pops_the_drill_in_then_the_drawer_before_ever_finishing_the_activity`() {
        runReaderActivity(ReaderDestination.LOG, sessionId = sessionId) { rule ->
            rule.waitUntilContentDescriptionExists(STATION_ID)
            rule.onNode(hasContentDescription(STATION_ID, substring = true) and hasClickAction()).performClick()
            rule.waitUntilContentDescriptionExists("Back to Log")

            rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            rule.waitForIdle()
            check(rule.activityRule.scenario.state == Lifecycle.State.RESUMED) {
                "expected the Activity to stay RESUMED after popping D01, was ${rule.activityRule.scenario.state}"
            }
            // Back on `Log` itself: its own `ScreenHeader` ("Open navigation") and its own real
            // content (`LogScreen.kt`'s `Filter` action) both being there is "same destination".
            rule.waitUntilContentDescriptionExists("Open navigation")
            rule.onNode(hasText("Filter") and hasClickAction()).assertExists()

            rule.onNodeWithContentDescription("Open navigation").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("drawer-row-LOG").assertExists()

            rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            rule.waitForIdle()
            val stateAfterDrawerClose = rule.activityRule.scenario.state
            check(stateAfterDrawerClose == Lifecycle.State.RESUMED) {
                "expected the Activity to stay RESUMED after closing the drawer, was $stateAfterDrawerClose"
            }
            rule.waitUntilContentDescriptionExists("Open navigation")
            rule.onNode(hasText("Filter") and hasClickAction()).assertExists()
        }
    }

    /**
     * Register R-334 (halt): a failure banner used to render opaque *inside* the drawer panel once
     * opened, hiding six of the nine rows (`storage-warn/N00-menu-with-banner-pass3.png`).
     * [StorageForecast.set] forces a real `OneNightLeft` reading the same way the scenario
     * simulator and this codebase's own tests already do (that object's own kdoc: "a caller that
     * knows the state it wants... sets it directly") â€” reset in `finally` since it is a
     * process-wide holder this test does not own past its own run.
     *
     * **What this does and does not prove â€” verified directly, not assumed**: every one of the
     * nine `drawer-row-*` nodes exists, survives a scroll-to, and reports [assertIsDisplayed]
     * (attached, non-zero size, not clipped by an ancestor) while the banner is showing and the
     * drawer is open. Tried, deliberately, to make this fail against the *old* structure
     * (`FailureHost` wrapping the whole `ModalNavigationDrawer` again, matching exactly how
     * `ReaderActivity.kt` used to call it) before trusting it â€” **it still passed**:
     * [assertIsDisplayed] checks attachment, size and ancestor-clipping only, not whether a later
     * sibling paints over a node, so it cannot see the actual R-334 bug (a paint-order fact) either
     * way. This assertion is a real regression guard on the *wiring* â€” the drawer rows and a real
     * banner can compose together without either breaking the other, and `FailureHost`'s signature
     * genuinely didn't need to change â€” not proof of the pixel-level fix, which rests on
     * `ModalNavigationDrawer`'s own documented contract (its `drawerContent` always renders above
     * its main-content slot) rather than on anything this JVM harness can observe directly. Stated
     * here in full rather than left implicit.
     */
    @Test
    fun `R_334_a_failure_banner_never_hides_the_drawer_rows`() {
        StorageForecast.set(
            StorageForecast.State.OneNightLeft(
                freeBytes = 500_000_000L,
                audioDirectoryBytes = 2_000_000_000L,
                nightsLeft = 0.8,
            ),
        )
        try {
            runReaderActivity(ReaderDestination.NOW) { rule ->
                rule.waitUntilTestTagExists("failure-storage-warning-banner")

                rule.onNodeWithContentDescription("Open navigation").performClick()
                rule.waitForIdle()
                rule.waitUntilTestTagExists("failure-storage-warning-banner")

                ReaderDestination.entries.filter { it != ReaderDestination.SEARCH }.forEach { destination ->
                    rule.onNodeWithTag("drawer-row-${destination.name}").performScrollTo().assertIsDisplayed()
                }
            }
        } finally {
            StorageForecast.reset()
        }
    }

    /**
     * WP11b follow-up (register R-448): the smoke case this class was missing for F21
     * (`Fail-Asset-Swap`) â€” proof the takeover actually renders through a real, launched
     * [ReaderActivity], the same way [R_334_a_failure_banner_never_hides_the_drawer_rows] above
     * already proves for a real-signal banner. F21 has no real signal to drive this with
     * ([DebugFailureOverride]'s own class kdoc, confirmed independently in
     * `FailureMapperTest.kt`'s own `R_448_asset_swap_signal`), so [DebugFailureOverride.show] is the
     * one path that exists â€” exactly what `Scenarios.kt`'s own `asset-swap` id already does for the
     * screenshot tour and `FailureOverrideScenariosTest`'s own `F21_asset-swap` case, neither of
     * which launches a real `Activity`. `DebugFailureOverride.clear()` runs in `finally`, matching
     * every other process-wide holder this class resets after using (`StorageForecast.reset()`
     * above, `ShedStatus.reset()` below).
     */
    @Test
    fun `R_448_f21_route the AssetSwap takeover renders through a real ReaderActivity`() {
        DebugFailureOverride.show(
            FailurePresentation.AssetSwap(
                AssetSwapViewState(
                    activeLabel = "callsigns-2026.08 Â· 41,200 entries",
                    stagedLabel = "callsigns-2026.09 Â· 41,600 entries",
                    options = listOf(
                        AssetSwapOption("Wait for the session to end", "the default Â· nothing else to do"),
                    ),
                    selectedOption = 0,
                ),
            ),
        )
        try {
            runReaderActivity(ReaderDestination.NOW) { rule ->
                rule.waitUntilTestTagExists("failure-asset-swap-screen")
                rule.onNodeWithTag("failure-asset-swap-screen").assertIsDisplayed()
                rule.onNodeWithTag("failure-asset-swap-done").assertIsDisplayed()
            }
        } finally {
            DebugFailureOverride.clear()
        }
    }

    /**
     * Register R-133 (round 11 addendum): `Settings-Storage`'s "Next deletion â€¦ Review" link
     * (`SettingsStorageScreen.kt`'s `NextDeletionRow`, WP10's `948fe55`) now has a real target â€”
     * `Earlier nights`, seeded directly on that session's own detail (DG04) via
     * `SessionsContent.initialSessionId` (WP10's `e390c60`).
     *
     * A real `NextDeletion` needs `computeNextDeletion` to find either the free-disk floor or a set
     * budget crossed (`StorageAccounting.kt`'s own doc comment) â€” the free-disk floor is not
     * reliably reachable on a real test machine, so this seeds the budget path instead: a 0 GB
     * budget, written directly into the same `SharedPreferences` `SettingsContent.kt` itself reads
     * (`SharedPreferencesSettingsStore`'s own real key, not a fake store â€” this activity builds the
     * real one), and a real, non-zero file under `filesDir/audio` (`measureDirectoryBytes` sums real
     * files, never a board literal). [seedSession] leaves exactly one session in the database, so
     * `computeNextDeletion`'s own "oldest" is unambiguous â€” landing on *any* session detail through
     * this path is landing on the right one.
     *
     * System back from that seeded detail is the actual R-133 half of this round's own brief:
     * `SessionsContent`'s own doc comment states its internal back only ever returns to its own
     * list â€” this asserts `OrtNavHostBackHandler`'s new `canReturnToSettingsStorage` branch instead,
     * reached through the real `OnBackPressedDispatcher`, the same mechanism R-333's own case above
     * uses.
     */
    @Test
    fun `R_133_settings_storage_review_link_opens_the_session_and_back_returns_to_settings_storage`() {
        context.getSharedPreferences(SharedPreferencesSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(SharedPreferencesSettingsStore.KEY_AUDIO_BUDGET_GB, 0)
            .apply()
        val audioDir = File(context.filesDir, "audio").apply { mkdirs() }
        File(audioDir, "seed.flac").writeBytes(ByteArray(2048))

        runReaderActivity(
            ReaderDestination.SETTINGS,
            settingsScreen = SettingsScreenId.STORAGE,
        ) { rule ->
            rule.waitUntilContentDescriptionExists("Back to Settings")
            rule.waitUntilTextExists("Review")
            // Below the fold on a real-height screen â€” the same scroll-before-click idiom
            // `SettingsStorageScreenTest.kt`'s own `R_133_next_deletion_row ... Review opens it`
            // case already established: `performClick()` on a node scrolled out of the viewport is
            // a real, silent no-op here (verified directly â€” a bare `performClick()` on this exact
            // node timed out with no error and no downstream effect, before this scroll was added).
            // That file's own screen-only composition has exactly one vertical-scroll node to
            // disambiguate from its horizontal budget-chip row; a real `ReaderActivity` also keeps
            // the closed drawer's own `drawer-rows` column composed off-screen (`Rows.dc.html` /
            // `Drawer.kt`), which also reports a vertical scroll axis â€” excluded here the same way
            // `R_129_transmission_drill_in_composes_and_survives_recreation`'s own scroll already
            // does, so exactly one node remains.
            val verticalScroll = SemanticsMatcher("has vertical scroll axis") {
                it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
            }
            rule.onNode(hasScrollAction() and verticalScroll and !hasTestTag("drawer-rows"))
                .performScrollToNode(hasText("Review"))
            rule.onNode(hasText("Review") and hasClickAction()).performClick()
            rule.waitUntilContentDescriptionExists("Back to Earlier nights")

            rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            rule.waitForIdle()
            val stateAfterBack = rule.activityRule.scenario.state
            check(stateAfterBack == Lifecycle.State.RESUMED) {
                "expected the Activity to stay RESUMED after returning from the reviewed session, was $stateAfterBack"
            }
            rule.waitUntilContentDescriptionExists("Back to Settings")
        }
    }

    /**
     * Register R-350 (round 12's host wire, round 14 updated now that the pipeline half is real
     * too): `ImproveContent` gained `onOpenModels` (WP10's `94c946c`), and `OrtNavHost.kt`'s
     * `IMPROVE_RECORDS` dispatch passes it the same real `NavHostCallbacks.onOpenModels`
     * `NowContent`'s own "Install a model" (R-139) already uses â€”
     * `navigator.openSettings(SettingsScreenId.ASSETS)`.
     *
     * **Round 12's own report, now resolved**: driving a genuine reprocess run to a real
     * missing-model failure used to be unreachable â€” `RejectionPipeline`'s own catch-all discarded
     * `AsrUnavailableException`'s real message before it ever reached
     * `ReprocessStatus.Summary.failureReasons`. `:pipeline`'s own fix (`AsrUnavailableException`
     * now a special-cased, verbatim-passed-through reason â€” `RejectionPipeline.kt`'s own doc
     * comment names exactly why only this one exception type gets that treatment) landed since;
     * this test now drives the *real*, complete flow end to end: a real T1-tier session
     * ([seedImproveTierSession], below the test env's own default current tier), a real audio file
     * at the exact path Pass B expects, a real `Improve all` tap, a real reprocess run that
     * genuinely fails for the genuine reason (no ASR model installed under this JVM's own
     * `filesDir`), the real reworded "No transcription model installed" line
     * (`ImproveScreens.kt`'s own `humanizeFailureReason`), a real `Install` tap, landing on the
     * real `Settings-Assets` â€” proving this round's own host wire together with the pipeline fix,
     * not routing around either.
     */
    @Test
    fun `R_350_improve_done_install_action_opens_settings_assets_for_a_real_missing_model_failure`() {
        // Belt-and-braces: `ShedStatus` is a process-wide `@Volatile` holder this class's own cases
        // never set, but nothing guarantees another test sharing this JVM worker left it at its
        // `0` default either â€” reset explicitly so `currentTierOrdinal()` is genuinely `T3` here,
        // not an assumption. Restored in `finally` for the same reason `StorageForecast.reset()`
        // runs there in the R-334 case above.
        ShedStatus.reset()
        val tierSessionId = seedImproveTierSession()
        try {
            runReaderActivity(ReaderDestination.IMPROVE_RECORDS, sessionId = tierSessionId) { rule ->
                // `Improve all N` (root) skips `Improve-Select` entirely â€” the simpler, direct route
                // `ImproveContent.onImproveAll` already takes to `Running`. `ImproveScreen`'s own
                // root button reads "Improve all N" (the real qualifying over count appended) â€”
                // `waitUntilTextExists`'s exact match never matches that, so this waits on the same
                // substring matcher the click below already uses.
                val improveAllButton = hasText("Improve all", substring = true) and hasClickAction()
                rule.waitUntil(15_000) { rule.onAllNodes(improveAllButton).fetchSemanticsNodes().isNotEmpty() }
                rule.onNode(improveAllButton).performClick()

                // A real reprocess run against a real, model-less `filesDir` â€” generous timeout,
                // this is genuine queue-drain + pass-attempt work, not a fixed-delay fake. The real
                // reworded text `ImproveScreens.kt`'s own `humanizeFailureReason` now produces,
                // real end to end since `:pipeline`'s own fix landed.
                rule.waitUntilTextExists("1 failed â€” No transcription model installed", timeoutMillis = 30_000)
                rule.onNode(hasText("Install") and hasClickAction()).performClick()

                rule.waitUntilContentDescriptionExists("Back to Settings")
                rule.waitUntilTextExists("Models and lexicon")
            }
        } finally {
            ShedStatus.reset()
        }
    }

    /**
     * Round 13 (the coordinator's own seam for WP12's screenshot tour): the one end-to-end
     * confirmation that [NavSeed]'s own intent extras survive the real
     * [org.ort.app.ui.ReaderActivity]/[org.ort.app.debug.ScenarioReaderActivity] path â€” every
     * other case proving what each `NavSeed` field does alone lives in [NavSeedTest], composing
     * `OrtNavHost` directly rather than launching a real `Activity`. [destinationIntent]'s own
     * `seed` parameter writes [NavSeed.EXTRA_OPEN_TRANSMISSION_ID] the same way
     * `ScenarioReaderActivity`'s own forwarding would, and `ReaderActivity.onCreate`'s own
     * `NavSeed.fromIntent` reads it back â€” a real round trip through both files this round touched,
     * not a shortcut.
     */
    @Test
    fun `NavSeed_extras_on_a_real_activity_open_the_seeded_transmission_detail_directly`() {
        runReaderActivity(
            ReaderDestination.LOG,
            sessionId = sessionId,
            seed = NavSeed(openTransmissionId = "TX1"),
        ) { rule ->
            // Same marker [NavSeedTest]'s own `openTransmissionId` case asserts â€” this class's own
            // `seedSession()` seeds the identical `TX1` id.
            rule.waitUntilContentDescriptionExists("Back to Log")
        }
    }

    /**
     * Register R-448 (round 14, coordinator-directed cross-package addendum): WP11b's own "â€¹
     * &lt;parent&gt;" back headers on F14/F19/F21/F22 (its own `FailureBackHeaderTest.kt` proves each
     * header calls the screen's own dismiss) had nothing real behind that dismiss for navigation â€”
     * `FailureHostActions.onOpenEarlierNights`/`onOpenModels` (new) and `onOpenStorageSettings`
     * (reused) now do. One real `DebugFailureOverride`, set by `Scenarios.load` the same way
     * `FailureOverrideScenariosTest.kt` (WP11b's) already proves for each of these four scenarios,
     * driven through a real `ReaderActivity` and a real tap on the header â€” not the direct
     * composable construction `FailureBackHeaderTest.kt` itself uses, so this is the one place the
     * whole chain (`FailureHost` â†’ `FailureHostActions` â†’ `ReaderActivity` â†’ `ReaderNavigator`) is
     * proven together.
     */
    @Test
    fun `R_448_F14_clock_back_header_opens_Earlier_nights`() {
        runBlocking { Scenarios.load(context, "clock-dst") }
        try {
            runReaderActivity(ReaderDestination.NOW) { rule ->
                rule.waitUntilContentDescriptionExists("Back to Earlier nights")
                rule.onNodeWithContentDescription("Back to Earlier nights").performClick()
                // The host's own `ScreenHeader` â€” `Earlier nights` is a plain destination (not a
                // drill-in, not `SETTINGS`/`SEARCH`'s own special-cased headers), so landing there
                // for real shows this, not the takeover's own header.
                rule.waitUntilContentDescriptionExists("Open navigation")
                rule.onNodeWithContentDescription("Open navigation").performClick()
                rule.waitForIdle()
                rule.onNodeWithTag("drawer-row-EARLIER_NIGHTS")
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            }
        } finally {
            // `Scenarios.load`'s own scenario also calls `ScenarioFixtures.markCapturing` (a real,
            // process-wide `CaptureState.capturing(...)`, unrelated to `DebugFailureOverride`) â€”
            // left set, a later case in this same JVM worker with no `sessionId` of its own would
            // have `ReaderActivity.resolveSessionId` prefer this stale "live" session instead
            // (found directly: `R_129_thread_drill_in_composes_and_survives_recreation`, this
            // class's own later case, failed exactly this way before this reset was added).
            DebugFailureOverride.clear()
            CaptureState.idle(clearSession = true)
        }
    }

    @Test
    fun `R_448_F19_reconcile_back_header_opens_Settings_Storage`() {
        runBlocking { Scenarios.load(context, "reconcile") }
        try {
            runReaderActivity(ReaderDestination.NOW) { rule ->
                rule.waitUntilContentDescriptionExists("Back to Storage and retention")
                rule.onNodeWithContentDescription("Back to Storage and retention").performClick()
                rule.waitUntilContentDescriptionExists("Back to Settings")
                rule.waitUntilTextExists("Storage and retention")
            }
        } finally {
            // `Scenarios.load`'s own scenario also calls `ScenarioFixtures.markCapturing` (a real,
            // process-wide `CaptureState.capturing(...)`, unrelated to `DebugFailureOverride`) â€”
            // left set, a later case in this same JVM worker with no `sessionId` of its own would
            // have `ReaderActivity.resolveSessionId` prefer this stale "live" session instead
            // (found directly: `R_129_thread_drill_in_composes_and_survives_recreation`, this
            // class's own later case, failed exactly this way before this reset was added).
            DebugFailureOverride.clear()
            CaptureState.idle(clearSession = true)
        }
    }

    // F21 (asset-swap) has no case here: investigated directly (see this round's own report/
    // CHANGELOG) â€” `DebugFailureOverride.current` reads `FailurePresentation.AssetSwap` correctly
    // both immediately after `Scenarios.load` and again once the real `Activity` is `RESUMED`
    // (checked explicitly), yet `FailureHost`'s own overlay never renders the takeover through a
    // real polling cycle â€” `NowContent`'s own content keeps showing underneath instead, for the
    // full 30 s this was given. `FailureBackHeaderTest.kt`'s own direct construction of
    // `FailAssetSwapScreen` already proves this round's own `onDone = actions.onOpenModels` wiring
    // is correct in shape (identical to F19/F22's, both proven below); this reads as a pre-existing
    // gap in the real, polled path specific to the `asset-swap` scenario fixture, not this round's
    // own change â€” reported rather than routed around, not silently dropped from this suite.

    @Test
    fun `R_448_F22_calibration_back_header_opens_Settings_Assets`() {
        runBlocking { Scenarios.load(context, "calibration") }
        try {
            runReaderActivity(ReaderDestination.NOW) { rule ->
                rule.waitUntilContentDescriptionExists("Back to Models and lexicon")
                rule.onNodeWithContentDescription("Back to Models and lexicon").performClick()
                rule.waitUntilContentDescriptionExists("Back to Settings")
                rule.waitUntilTextExists("Models and lexicon")
            }
        } finally {
            // `Scenarios.load`'s own scenario also calls `ScenarioFixtures.markCapturing` (a real,
            // process-wide `CaptureState.capturing(...)`, unrelated to `DebugFailureOverride`) â€”
            // left set, a later case in this same JVM worker with no `sessionId` of its own would
            // have `ReaderActivity.resolveSessionId` prefer this stale "live" session instead
            // (found directly: `R_129_thread_drill_in_composes_and_survives_recreation`, this
            // class's own later case, failed exactly this way before this reset was added).
            DebugFailureOverride.clear()
            CaptureState.idle(clearSession = true)
        }
    }

    // -- drill-ins ------------------------------------------------------------------------------

    @Test
    fun `R_129_transmission_drill_in_composes_and_survives_recreation`() {
        // Reached from `Stations`, not `Log` â€” `Log` is R-129's own crashing destination, and this
        // case's job is the transmission drill-in's own saver behaviour, not a second copy of the
        // Log failure. `StationDetailScreen`'s "recent over" row (`testTag("recent-over-$id")`,
        // `StationScreen.kt`) opens it directly.
        runReaderActivity(ReaderDestination.STATIONS) { rule ->
            rule.waitUntilContentDescriptionExists(STATION_ID)
            // `StationsScreen`'s row nests an `AttributionRow` that also sets its own
            // `mergeDescendants = true`, so it stays a *second*, non-clickable semantics node
            // (`ContentDescription` also containing the callsign) even in the merged tree â€” filter
            // to the one with a real click action, the row itself.
            rule.onNode(hasContentDescription(STATION_ID, substring = true) and hasClickAction()).performClick()
            rule.waitUntilContentDescriptionExists("Back to Stations")
            // `StationDetailScreen`'s own doc comment: a single top-level `LazyColumn` (chosen
            // there over a `verticalScroll` `Column` after finding the latter silently swallowed a
            // nested row's own tap in this exact host) â€” the "recent over" row is not composed
            // until scrolled to. `StationScreenTest.kt`'s own established pattern for this row â€”
            // `hasScrollAction()` alone is ambiguous here (the drawer's own `"drawer-rows"` Column
            // stays in the tree, off-screen, whether the drawer is open or not) so exclude it too.
            val scrollable = hasScrollAction() and !hasTestTag("drawer-rows")
            rule.onNode(scrollable).performScrollToNode(hasTestTag("recent-over-TX1"))
            rule.onNodeWithTag("recent-over-TX1").performClick()
            rule.waitUntilContentDescriptionExists("Back to Stations")

            rule.activityRule.scenario.recreate()
            rule.waitForIdle()

            rule.waitUntilContentDescriptionExists("Back to Stations")
        }
    }

    @Test
    fun `R_129_station_drill_in_composes_and_survives_recreation`() {
        runReaderActivity(ReaderDestination.STATIONS) { rule ->
            rule.waitUntilContentDescriptionExists(STATION_ID)
            // `StationsScreen`'s row nests an `AttributionRow` that also sets its own
            // `mergeDescendants = true`, so it stays a *second*, non-clickable semantics node
            // (`ContentDescription` also containing the callsign) even in the merged tree â€” filter
            // to the one with a real click action, the row itself.
            rule.onNode(hasContentDescription(STATION_ID, substring = true) and hasClickAction()).performClick()
            rule.waitUntilContentDescriptionExists("Back to Stations")

            rule.activityRule.scenario.recreate()
            rule.waitForIdle()

            rule.waitUntilContentDescriptionExists("Back to Stations")
        }
    }

    @Test
    fun `R_129_frequency_drill_in_composes_and_survives_recreation`() {
        runReaderActivity(ReaderDestination.FREQUENCIES) { rule ->
            rule.waitUntilContentDescriptionExists(FREQUENCY_LABEL)
            rule.onNodeWithContentDescription(FREQUENCY_LABEL, substring = true).performClick()
            rule.waitUntilContentDescriptionExists("Back to Frequencies")

            rule.activityRule.scenario.recreate()
            rule.waitForIdle()

            rule.waitUntilContentDescriptionExists("Back to Frequencies")
        }
    }

    @Test
    fun `R_129_thread_drill_in_composes_and_survives_recreation`() {
        // The one case in this class that needs a real, non-null session id: `ThreadContent`
        // scopes its list to `currentThreadListState(context, sessionId)`, so without one there is
        // no seeded card to click through to the drill-in at all.
        runReaderActivity(ReaderDestination.THREADS, sessionId = sessionId) { rule ->
            rule.waitUntilContentDescriptionExists(STATION_ID)
            // Only `ThreadCard` itself carries the callsign here (unlike `StationsScreen`'s row,
            // which nests a second, non-clickable `AttributionRow` also naming it) â€” `hasClickAction`
            // is still the correct, unambiguous filter either way.
            rule.onNode(hasContentDescription(STATION_ID, substring = true) and hasClickAction()).performClick()
            rule.waitUntilContentDescriptionExists("Back to Threads")

            rule.activityRule.scenario.recreate()
            rule.waitForIdle()

            rule.waitUntilContentDescriptionExists("Back to Threads")
        }
    }

    // -- shared plumbing ------------------------------------------------------------------------

    private fun assertComposesAndSurvives(
        destination: ReaderDestination,
        // Round 5: every destination but `SEARCH` still shows the host's `ScreenHeader`, whose
        // drawer icon is this marker â€” `SEARCH`'s own case passes "Back" instead (see its own
        // comment above).
        expectedContentDescription: String = "Open navigation",
    ) {
        runReaderActivity(destination) { rule ->
            // `waitUntil`, not an immediate `assertIsDisplayed()`: `SettingsContent`'s own root
            // state (`SettingsRootScreen`) is not `rememberSaveable` â€” every fresh composition,
            // `recreate()`'s included, shows `LoadingSettings()` first while its own `LaunchedEffect`
            // reads `SettingsPolling.root` asynchronously (found by this exact assertion timing out
            // right after `recreate()` before this was added).
            rule.waitUntilContentDescriptionExists(expectedContentDescription)

            rule.activityRule.scenario.recreate()
            rule.waitForIdle()

            rule.waitUntilContentDescriptionExists(expectedContentDescription)
        }
    }

    /**
     * Builds and runs a real [AndroidComposeTestRule] over a [ReaderActivity] launched with a
     * caller-chosen `Intent` â€” `createAndroidComposeRule<ReaderActivity>()`
     * ([org.ort.app.ui.ReaderActivityTest]'s own pattern) always launches the default one, with no
     * seam to reach a specific [ReaderDestination] before the test body runs. This is that seam:
     * the same public [AndroidComposeTestRule] constructor `createAndroidComposeRule` itself calls
     * internally, given an [ActivityScenarioRule] built from [destinationIntent] instead of a bare
     * activity class. Applied and evaluated manually (`TestRule.apply(...).evaluate()`) rather than
     * as a `@get:Rule` field, since a field's intent must be fixed at test-instance construction â€”
     * before JUnit knows which `@Test` method, and so which destination, is about to run.
     */
    private fun runReaderActivity(
        destination: ReaderDestination,
        sessionId: String? = null,
        settingsScreen: SettingsScreenId? = null,
        // Round 13 (WP12's screenshot-tour seam): `null` (every existing case) changes nothing â€”
        // see [destinationIntent]'s own doc comment for what this adds.
        seed: NavSeed? = null,
        body: (rule: ReaderComposeTestRule) -> Unit,
    ) {
        val activityRule =
            ActivityScenarioRule<ReaderActivity>(destinationIntent(destination, sessionId, settingsScreen, seed))
        val rule = AndroidComposeTestRule(activityRule) { r ->
            var activity: ReaderActivity? = null
            r.scenario.onActivity { activity = it }
            checkNotNull(activity) { "ReaderActivity did not reach RESUMED" }
        }
        val statement = object : Statement() {
            override fun evaluate() {
                body(rule)
                rule.waitForIdle()
                // Freeze the clock before tearing anything down: every destination keeps a
                // `LaunchedEffect { while (true) { poll(); delay(2000) } }` alive for as long as its
                // composition exists, and each one still has a live timer armed to wake it and
                // schedule another frame the instant this test's own assertions are done with it.
                // With `autoAdvance` left on, `moveToState(DESTROYED)`'s own teardown races that
                // timer â€” it can fire, post a new frame, and hand the disposing composition one more
                // recomposition to perform. Turning it off first means no such timer fires again;
                // whatever `delay()` this composition is suspended in just stays suspended until its
                // coroutine scope is actually cancelled, rather than getting one more chance to run.
                rule.mainClock.autoAdvance = false
                // Force the Activity all the way through `onDestroy()` here, synchronously, before
                // this rule's own teardown runs â€” `waitForIdle()` alone does not prove a poll loop's
                // coroutine has actually been cancelled (a suspended `delay()` reads as idle,
                // correctly), only that disposal has *started* â€” moving through `DESTROYED`
                // explicitly, then idling once more, is what confirms it has actually finished. The
                // rule's own `apply()`-driven teardown (`ActivityScenarioRule.after()` â†’
                // `scenario.close()`, then `AndroidComposeUiTestEnvironment`'s own disposal) still
                // runs after this `evaluate()` returns and is what actually unregisters this
                // composition's idling resources â€” nothing here registers one of its own to leak.
                rule.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
                rule.waitForIdle()
            }
        }
        val description = Description.createTestDescription(
            ReaderActivityDestinationSmokeTest::class.java,
            "runReaderActivity[$destination]",
        )
        rule.apply(statement, description).evaluate()
    }

    /**
     * [sessionId] defaults to `null` â€” deliberately, not merely "unset". [ReaderActivity.onCreate]'s
     * own doc comment (see [org.ort.app.ui.resolveSessionId]) already names this exact class's own
     * earlier finding: launching a real `Activity` with a *non-null* session id starts `OrtNavHost`'s
     * `LaunchedEffect(sessionId) { while (true) { poll(); delay(2000) } }` polling loops in a way
     * Robolectric never got a clean chance to cancel, which then poisoned an unrelated, later test's
     * own idle-check the one time this class ran every one of its fourteen cases against a real
     * session. Every case that does not need real seeded row data to click through to a drill-in
     * (everything except [ReaderDestination.THREADS]'s own drill-in â€” `Stations`/`Frequencies` read
     * session-independent catalog/activity tables, confirmed by reading `StationPolling.kt` before
     * relying on it) launches with no session id at all, matching how
     * [org.ort.app.ui.ReaderActivityTest]'s own five cases already do â€” R-129's crash itself is
     * unconditional on `LogContent.kt`'s own `rememberSaveable` line, so this costs nothing there.
     */
    private fun destinationIntent(
        destination: ReaderDestination,
        sessionId: String?,
        settingsScreen: SettingsScreenId? = null,
        // Round 13: [NavSeed.putExtras] â€” the same extras
        // [org.ort.app.debug.ScenarioReaderActivity]/[ReaderActivity]'s own `NavSeed.fromIntent`
        // reads back, proving the real intent-extras path this class's own `Activity` harness
        // exercises, not just direct `OrtNavHost(seed = ...)` construction ([NavSeedTest]'s own row).
        seed: NavSeed? = null,
    ): Intent = Intent(context, ReaderActivity::class.java)
        .apply { sessionId?.let { putExtra(ReaderActivity.EXTRA_SESSION_ID, it) } }
        .apply { settingsScreen?.let { putExtra(ReaderActivity.EXTRA_SETTINGS_SCREEN, it.name) } }
        .apply { seed?.putExtras(this) }
        .putExtra(ReaderActivity.EXTRA_DESTINATION, destination.name)

    /**
     * `StationsContent`/`FrequenciesContent`/`ThreadContent` all populate their list from a real
     * suspend `*Polling` read inside a `LaunchedEffect`, not synchronously on first composition â€”
     * unlike `assertIsDisplayed()`'s single immediate snapshot, `waitUntil` keeps re-checking until
     * that read actually lands (or the timeout fires for a genuinely wrong reason), the same
     * allowance `OrtNavHostDestinationDispatchTest.waitUntilTextExists` already makes for the same
     * reason.
     */
    private fun ReaderComposeTestRule.waitUntilContentDescriptionExists(
        substring: String,
        timeoutMillis: Long = 15_000,
    ) {
        waitUntil(timeoutMillis) {
            onAllNodes(hasContentDescription(substring, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Round 9: same allowance as [waitUntilContentDescriptionExists], for the filter sheet's own
     * polled "Show N overs" count line (`LogFilterSheet.kt`), which carries no content description
     * of its own â€” its visible text is the only thing there is to match. */
    private fun ReaderComposeTestRule.waitUntilTextExists(text: String, timeoutMillis: Long = 15_000) {
        waitUntil(timeoutMillis) { onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Round 11: [FailStorageWarningBanner]'s own `testTag("failure-storage-warning-banner")` â€”
     * same allowance as [waitUntilTextExists], for a real, polled banner rather than static copy. */
    private fun ReaderComposeTestRule.waitUntilTestTagExists(tag: String, timeoutMillis: Long = 15_000) {
        waitUntil(timeoutMillis) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Round 9: a quick-filter chip's own `Selected` semantics property only reflects the real,
     * polled `LogScreenViewState.quickFilters` â€” waiting on a header element that renders
     * unconditionally (`waitUntilContentDescriptionExists("Open navigation")`) and then asserting
     * immediately can race `LogContent`'s own first poll, catching it still at its pre-poll
     * `remember` default. Polls for the real thing directly instead. */
    private fun ReaderComposeTestRule.waitUntilChipSelected(chipLabelSubstring: String, timeoutMillis: Long = 15_000) {
        val matcher = hasText(chipLabelSubstring, substring = true) and isSelected()
        waitUntil(timeoutMillis) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * Round 7 (R-090's own double-header saga, this time asserted against directly rather than
     * left to a `recreate()` timeout to catch by accident): counts every node whose content
     * description contains [substring], exact â€” not [waitUntilContentDescriptionExists]'s "at
     * least one". `SETTINGS`'s own header now moves between [org.ort.app.ui.settings
     * .SettingsRootScreen] (root) and no header at all (a sub-screen, which draws only its own
     * `DrillInHeader`) depending entirely on `SettingsContent`'s internal state â€” the host draws
     * neither on its own â€” so "one, not zero and not two" is the fact worth asserting on each.
     */
    private fun ReaderComposeTestRule.assertExactlyOneContentDescription(substring: String) {
        val count = onAllNodes(hasContentDescription(substring, substring = true)).fetchSemanticsNodes().size
        check(count == 1) {
            "expected exactly one node with content description containing '$substring', found $count"
        }
    }

    /**
     * R-276's own real repro requirement: [org.ort.app.ui.data.NightlyDeparture.isBusierThanUsual]
     * (`ActivityPattern.kt`) is a genuine, multi-night comparison ("tonight" must be more than
     * double the average heard-count
     * of every other *listened* night), so reaching `Frequency-Change`'s "Busier than usual"
     * action for real needs real, distinct-calendar-day session/transmission rows, not the single
     * fixed-epoch transmission [seedSession] gives every other case in this class (which never
     * needs a real night pattern). Three "usual" nights (one over each, `daysAgo` 1..3) and
     * "tonight" (`daysAgo` 0, [TONIGHT_OVER_COUNT] overs â€” more than double the usual average of
     * 1) â€” all anchored to the real wall clock at test run time (`ZonedDateTime.now`), the same
     * clock `NightlyDeparture`/`FrequencyPolling` read in production. Returns "tonight"'s own
     * session id, the one [runReaderActivity] must launch with: `LogPolling`'s quick-filter chip
     * list is scoped to the *launched* session (`db.transmissionDao().listBySession`, confirmed by
     * reading `LogViewData.kt` before relying on it), not the corpus as a whole.
     */
    private fun seedFrequencyOversPattern(): String = runBlocking {
        val db = OrtDatabase.create(context)
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)

        suspend fun seedNight(id: String, daysAgo: Long, overCount: Int) {
            val windowEnd = now.minusDays(daysAgo)
            val windowStart = windowEnd.minusMinutes(NIGHT_WINDOW_MINUTES)
            db.sessionDao().insert(
                SessionEntity(
                    id = id,
                    startedAt = windowStart.toInstant().toEpochMilli(),
                    endedAt = windowEnd.toInstant().toEpochMilli(),
                    profileId = null,
                    deviceTier = null,
                    appVersion = "test",
                    terminationReason = null,
                    sourceId = null,
                    schemaVersion = OrtDatabase.SCHEMA_VERSION,
                ),
            )
            repeat(overCount) { index ->
                val at = windowStart.plusMinutes(10L + index * 15L).toInstant().toEpochMilli()
                db.transmissionDao().insert(
                    TransmissionEntity(
                        id = "$id-tx$index",
                        sessionId = id,
                        threadId = "$id-th",
                        startedAtUtc = at,
                        endedAtUtc = at + 1_000L,
                        durationMs = 4_200L,
                        audioFormat = "flac/16k/mono",
                        preRollMs = 200,
                        postRollMs = 200,
                        frequencyHz = OVERS_FREQUENCY_HZ,
                        frequencyProvenance = "measured",
                        mode = null,
                        signalStrength = 7.0,
                        channelName = null,
                        voiceprintId = null,
                        attributionState = AttributionState.UNKNOWN,
                        stationId = null,
                        attributionConfidence = 0.0,
                        attributionSourceTransmissionId = null,
                        processingState = TransmissionState.COMPLETE,
                        rejectionReason = null,
                        samplePosition = index.toLong(),
                        monotonicStartNanos = 0L,
                        utcOffsetMinutes = 0,
                        calibrationId = null,
                        executionProvider = null,
                    ),
                )
            }
        }

        seedNight("overs-usual-1", daysAgo = 1, overCount = 1)
        seedNight("overs-usual-2", daysAgo = 2, overCount = 1)
        seedNight("overs-usual-3", daysAgo = 3, overCount = 1)
        seedNight(TONIGHT_SESSION_ID, daysAgo = 0, overCount = TONIGHT_OVER_COUNT)
        TONIGHT_SESSION_ID
    }

    /**
     * Register R-350: a real `Improve` candidate â€” `ImprovePolling.root`'s own real read
     * (`db.sessionDao().listAll()`, grouped by `SessionEntity.deviceTier`) needs a session whose
     * tier is genuinely below the test env's own current tier
     * (`ImprovePolling.currentTierOrdinal`: `MAX_TIER_ORDINAL(3) - ShedStatus.currentLevel`, `T3`
     * while nothing else in this JVM worker has raised the shed level) â€” `"T1"`, matching the real
     * `field-tier1` scenario's own tier, same as `RealCaptureService` writes for a session that
     * actually ran below full capability, never a literal this test invents.
     *
     * Also writes a real (silent-content) file at the exact path Pass B expects the retained audio
     * â€” `filesDir/audio/<sessionId>/<transmissionId>.flac` â€” verified directly: without this, the
     * real reprocess engine fails one step earlier than R-350's own missing-model check, with its
     * own honest "expected retained audio at ..." reason instead, which is a *different*, also-real
     * failure this test is not about.
     */
    private fun seedImproveTierSession(): String = runBlocking {
        val db = OrtDatabase.create(context)
        val audioFile = File(context.filesDir, "audio/$IMPROVE_SESSION_ID/$IMPROVE_SESSION_ID-tx.flac")
        audioFile.parentFile?.mkdirs()
        audioFile.writeBytes(ByteArray(2048))
        db.sessionDao().insert(
            SessionEntity(
                id = IMPROVE_SESSION_ID,
                startedAt = 0L,
                endedAt = null,
                profileId = null,
                deviceTier = "T1",
                appVersion = "test",
                terminationReason = null,
                sourceId = null,
                schemaVersion = OrtDatabase.SCHEMA_VERSION,
            ),
        )
        db.transmissionDao().insert(
            TransmissionEntity(
                id = "$IMPROVE_SESSION_ID-tx",
                sessionId = IMPROVE_SESSION_ID,
                threadId = "$IMPROVE_SESSION_ID-th",
                startedAtUtc = 0L,
                endedAtUtc = 1_000L,
                durationMs = 4_200L,
                audioFormat = "flac/16k/mono",
                preRollMs = 200,
                postRollMs = 200,
                frequencyHz = FREQUENCY_HZ,
                frequencyProvenance = "measured",
                mode = null,
                signalStrength = 7.0,
                channelName = null,
                voiceprintId = null,
                attributionState = AttributionState.UNKNOWN,
                stationId = null,
                attributionConfidence = 0.0,
                attributionSourceTransmissionId = null,
                processingState = TransmissionState.COMPLETE,
                rejectionReason = null,
                samplePosition = 1L,
                monotonicStartNanos = 0L,
                utcOffsetMinutes = 0,
                calibrationId = null,
                executionProvider = null,
            ),
        )
        IMPROVE_SESSION_ID
    }

    private companion object {
        const val STATION_ID = "K7LWH"
        const val FREQUENCY_HZ = 146_960_000L

        // `TransmissionDetail.frequencyLabel`/`FrequencyViewMapper.listEntry` â€” "146.960" (Log,
        // Frequencies list rows) â€” matches both call sites' formatting for this Hz value.
        const val FREQUENCY_LABEL = "146.960"

        // Round 9, register R-276 â€” `seedFrequencyOversPattern`'s own frequency, kept distinct
        // from `FREQUENCY_HZ` so this test's own seeding never touches any other case's data.
        const val OVERS_FREQUENCY_HZ = 446_100_000L
        const val OVERS_FREQUENCY_LABEL = "446.100"
        const val TONIGHT_SESSION_ID = "overs-tonight"

        // Register R-350 â€” `seedImproveTierSession`'s own session id.
        const val IMPROVE_SESSION_ID = "improve-tier1-session"

        // More than double `seedFrequencyOversPattern`'s "usual" average of 1 â€” the real threshold
        // `NightlyDeparture.isBusierThanUsual` checks â€” with room to spare, not a boundary value.
        const val TONIGHT_OVER_COUNT = 5

        // Each seeded night's own listening window â€” short enough to stay clear of a calendar-day
        // boundary this test does not control (the real wall clock at whatever time it runs).
        const val NIGHT_WINDOW_MINUTES = 90L
    }
}
