package org.ort.app.ui.navigation

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import org.ort.app.ui.ReaderActivity
import org.ort.app.ui.settings.SettingsScreenId
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TransmissionEntity
import org.robolectric.RobolectricTestRunner

/** Shorthand for this file's one specific [AndroidComposeTestRule] instantiation — keeps later
 * receiver-type usages under ktlint's line-length limit without wrapping generics awkwardly. */
private typealias ReaderComposeTestRule = AndroidComposeTestRule<ActivityScenarioRule<ReaderActivity>, ReaderActivity>

/** Round 5's helpers were written against this shorter name; both spellings name the same rule type. */
private typealias ReaderComposeRule = ReaderComposeTestRule

/**
 * R-129 (V3 Reader validation @3e2d4ee, `results/ui-audit/register.md`): the drawer's own report
 * — "Robolectric did not catch it because no `SaveableStateRegistry` is installed under
 * `createComposeRule`" — is exactly right: every test this package (and WP5/6/7/8's own) shipped
 * before this one composed the reader's screens through `createComposeRule()`
 * (`OrtNavHostDestinationDispatchTest`, `ReaderAccessibilityTest`) or by calling a `*Content`
 * composable directly (`CaptureStatusContentTest` and siblings) — neither path installs a real
 * `SaveableStateRegistry`, so a `rememberSaveable` call on a type with no registered `Saver`
 * (`LogContent.kt`'s `LogQuickFilterId`, a plain `sealed interface`) never gets the chance to throw.
 * A real `Activity`, launched the way the validator launches one, does install one — which is
 * exactly what crashed L01–L05 100% of the time on the device and never once in this suite.
 *
 * This class closes that gap: for every [ReaderDestination] with [ReaderDestination.hasScreen], and
 * for the four drill-ins reachable from real list rows, it launches a real [ReaderActivity] (via
 * [ActivityScenarioRule], the same disposal path [org.ort.app.ui.ReaderActivityTest] already uses,
 * built here with a custom launch `Intent` rather than that class's default one so each case can
 * open straight to its own destination through [ReaderActivity.EXTRA_DESTINATION] — the
 * [ReaderNavigator] seam WP3's round 3 addendum built), asserts the destination's own root actually
 * composed (never just "no exception" — a concrete node from that screen must be displayed), then
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
 * [ReaderActivity.EXTRA_SESSION_ID] — every other case launches with none.
 *
 * `R_129_LOG_composes_and_survives_recreation` is written exactly like every other case here — no
 * `assertThrows`, no inversion — and is expected to **fail today**, for the one real, filed reason
 * (`LogContent.kt:50`'s unsaved `rememberSaveable<LogQuickFilterId>`), while WP5 fixes it
 * concurrently in its own file. Once that fix lands this case passes with no change here. Every
 * other case in this class, run alone (`--tests
 * "org.ort.app.ui.navigation.ReaderActivityDestinationSmokeTest"`) or as part of a fresh JVM, is
 * green.
 *
 * **This class run as part of the full, unforked `:app:testDebugUnitTest` alongside everything else
 * could leave the shared JVM's Compose test environment unable to reach idle for whatever unrelated
 * test happened to compose next** (`AppNotIdleException`, "Compose did not get idle... infinite
 * composition loop", surfacing in a completely different file — `ActivityPatternChartTest` and
 * `CaptureStatusScreenTest` both observed, on different runs, neither touched by this class at all)
 * — not this class's own cases failing, a *later* one's. [ReaderActivity]'s own `resolveSessionId`
 * doc comment already names the exact mechanism and origin of this: "building a real `ReaderActivity`
 * with a non-null session id starts `OrtNavHost`'s ... polling loops ... which a Robolectric-driven
 * test never gets a chance to cleanly cancel," and records that its own author avoided it precisely
 * by testing `resolveSessionId` as a pure function rather than building a real activity — the same
 * constraint R-129 asks this class to cross anyway, since only a real `Activity`'s real
 * `SaveableStateRegistry` can catch a `rememberSaveable` bug at all.
 *
 * **Fixed at the root, not chased further in this file:** `app/build.gradle.kts` now runs this one
 * class as its own Gradle `Test` task (`smokeTestDebugUnitTest`), excluded from `testDebugUnitTest`
 * — see that file's own comment. A different `Test` task is always a fresh JVM worker process,
 * never one shared with `testDebugUnitTest`'s own run, so whatever this class's own real `Activity`
 * instances leave behind in the Compose test environment can no longer reach any test outside this
 * class, regardless of what it is or whether it is ever fully cleaned up. `results/ui-audit/README.md`'s
 * own gate list names both tasks now — `check`/`build` still run this class, just in its own process.
 * Three code-level mitigations stay in this file too, on the theory that a smaller footprint here is
 * still worth having even with the task split doing the real isolating: [runReaderActivity] drives
 * every `ActivityScenarioRule` through `Lifecycle.State.DESTROYED` explicitly (rather than trusting
 * disposal-order alone) and turns `mainClock.autoAdvance` off first, so no live poll loop's timer can
 * fire and hand the disposing composition one more frame to recompose while that teardown runs; and
 * [destinationIntent] hands `ReaderActivity` a non-null session id — the one thing that starts
 * `LogContent`/`ThreadContent`'s own *additional*, session-gated poll loops on top of the drawer's
 * and `NowContent`'s own unconditional ones — only for the one case that actually needs real seeded
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
                endedAt = null,
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
                // Non-null: `LogViewData`/`ThreadListMapper` both group by this — the thread
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
        // (confirmed by reading `ActivityDao.kt` before writing this) — the `Stations` list and its
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

    // Round 5 (R-200): `SEARCH` no longer shows the host's `ScreenHeader` ("Open navigation") —
    // `SearchContent` now draws its own back chevron instead (`SearchScreen.kt`'s
    // `search-back-chevron`, `contentDescription = "Back"`) — so this case checks for that marker,
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

    @Test
    fun `R_129_SETTINGS_composes_and_survives_recreation`() = assertComposesAndSurvives(ReaderDestination.SETTINGS)

    // Round 5 (R-090/R-139/F6/F9): `EXTRA_DESTINATION=SETTINGS` with `EXTRA_SETTINGS_SCREEN` set
    // lands directly on a sub-screen — `SettingsContent.initialScreen`'s real target now — rather
    // than the root every other `SETTINGS` case in this class exercises. `RIG` chosen arbitrarily
    // among the eight non-`ASSETS` sub-screens (`ASSETS` dispatches to `ModelsContent`, a different
    // package's own file, already covered by its own tests); every sub-screen shares
    // `SettingsSubScreen`'s one dispatch and the same `DrillInHeader(parentLabel = "Settings", ...)`
    // this asserts on, so this one case stands for all eight.
    //
    // Found by writing this case, reported rather than silently worked around (out of this row's
    // file to fix — see this round's own report): the host still renders its own `ScreenHeader`
    // ("Open navigation") for every `SETTINGS` case, sub-screen included — `OrtNavHost.kt` has no
    // way to know a sub-screen is showing, since that state is entirely internal to
    // `SettingsContent` (confirmed by reading that file). `SettingsRootScreen` needs the host's
    // header (R-130, it draws none of its own); every *sub*-screen draws its own `DrillInHeader`
    // too, so a sub-screen reached this way shows both at once — a real, live double-header this
    // assertion deliberately does not (and structurally cannot, without editing `ui/settings/**`)
    // guard against; it only proves the sub-screen itself renders and survives `recreate()`.
    @Test
    fun `R_129_SETTINGS_RIG_initialScreen_composes_and_survives_recreation`() {
        runReaderActivity(ReaderDestination.SETTINGS, settingsScreen = SettingsScreenId.RIG) { rule ->
            rule.waitUntilContentDescriptionExists("Back to Settings")

            rule.activityRule.scenario.recreate()
            rule.waitForIdle()

            rule.waitUntilContentDescriptionExists("Back to Settings")
        }
    }

    // -- drill-ins ------------------------------------------------------------------------------

    @Test
    fun `R_129_transmission_drill_in_composes_and_survives_recreation`() {
        // Reached from `Stations`, not `Log` — `Log` is R-129's own crashing destination, and this
        // case's job is the transmission drill-in's own saver behaviour, not a second copy of the
        // Log failure. `StationDetailScreen`'s "recent over" row (`testTag("recent-over-$id")`,
        // `StationScreen.kt`) opens it directly.
        runReaderActivity(ReaderDestination.STATIONS) { rule ->
            rule.waitUntilContentDescriptionExists(STATION_ID)
            // `StationsScreen`'s row nests an `AttributionRow` that also sets its own
            // `mergeDescendants = true`, so it stays a *second*, non-clickable semantics node
            // (`ContentDescription` also containing the callsign) even in the merged tree — filter
            // to the one with a real click action, the row itself.
            rule.onNode(hasContentDescription(STATION_ID, substring = true) and hasClickAction()).performClick()
            rule.waitUntilContentDescriptionExists("Back to Stations")
            // `StationDetailScreen`'s own doc comment: a single top-level `LazyColumn` (chosen
            // there over a `verticalScroll` `Column` after finding the latter silently swallowed a
            // nested row's own tap in this exact host) — the "recent over" row is not composed
            // until scrolled to. `StationScreenTest.kt`'s own established pattern for this row —
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
            // (`ContentDescription` also containing the callsign) even in the merged tree — filter
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
            // which nests a second, non-clickable `AttributionRow` also naming it) — `hasClickAction`
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
        // drawer icon is this marker — `SEARCH`'s own case passes "Back" instead (see its own
        // comment above).
        expectedContentDescription: String = "Open navigation",
    ) {
        runReaderActivity(destination) { rule ->
            // `waitUntil`, not an immediate `assertIsDisplayed()`: `SettingsContent`'s own root
            // state (`SettingsRootScreen`) is not `rememberSaveable` — every fresh composition,
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
     * caller-chosen `Intent` — `createAndroidComposeRule<ReaderActivity>()`
     * ([org.ort.app.ui.ReaderActivityTest]'s own pattern) always launches the default one, with no
     * seam to reach a specific [ReaderDestination] before the test body runs. This is that seam:
     * the same public [AndroidComposeTestRule] constructor `createAndroidComposeRule` itself calls
     * internally, given an [ActivityScenarioRule] built from [destinationIntent] instead of a bare
     * activity class. Applied and evaluated manually (`TestRule.apply(...).evaluate()`) rather than
     * as a `@get:Rule` field, since a field's intent must be fixed at test-instance construction —
     * before JUnit knows which `@Test` method, and so which destination, is about to run.
     */
    private fun runReaderActivity(
        destination: ReaderDestination,
        sessionId: String? = null,
        settingsScreen: SettingsScreenId? = null,
        body: (rule: ReaderComposeTestRule) -> Unit,
    ) {
        val activityRule =
            ActivityScenarioRule<ReaderActivity>(destinationIntent(destination, sessionId, settingsScreen))
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
                // timer — it can fire, post a new frame, and hand the disposing composition one more
                // recomposition to perform. Turning it off first means no such timer fires again;
                // whatever `delay()` this composition is suspended in just stays suspended until its
                // coroutine scope is actually cancelled, rather than getting one more chance to run.
                rule.mainClock.autoAdvance = false
                // Force the Activity all the way through `onDestroy()` here, synchronously, before
                // this rule's own teardown runs — `waitForIdle()` alone does not prove a poll loop's
                // coroutine has actually been cancelled (a suspended `delay()` reads as idle,
                // correctly), only that disposal has *started* — moving through `DESTROYED`
                // explicitly, then idling once more, is what confirms it has actually finished. The
                // rule's own `apply()`-driven teardown (`ActivityScenarioRule.after()` →
                // `scenario.close()`, then `AndroidComposeUiTestEnvironment`'s own disposal) still
                // runs after this `evaluate()` returns and is what actually unregisters this
                // composition's idling resources — nothing here registers one of its own to leak.
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
     * [sessionId] defaults to `null` — deliberately, not merely "unset". [ReaderActivity.onCreate]'s
     * own doc comment (see [org.ort.app.ui.resolveSessionId]) already names this exact class's own
     * earlier finding: launching a real `Activity` with a *non-null* session id starts `OrtNavHost`'s
     * `LaunchedEffect(sessionId) { while (true) { poll(); delay(2000) } }` polling loops in a way
     * Robolectric never got a clean chance to cancel, which then poisoned an unrelated, later test's
     * own idle-check the one time this class ran every one of its fourteen cases against a real
     * session. Every case that does not need real seeded row data to click through to a drill-in
     * (everything except [ReaderDestination.THREADS]'s own drill-in — `Stations`/`Frequencies` read
     * session-independent catalog/activity tables, confirmed by reading `StationPolling.kt` before
     * relying on it) launches with no session id at all, matching how
     * [org.ort.app.ui.ReaderActivityTest]'s own five cases already do — R-129's crash itself is
     * unconditional on `LogContent.kt`'s own `rememberSaveable` line, so this costs nothing there.
     */
    private fun destinationIntent(
        destination: ReaderDestination,
        sessionId: String?,
        settingsScreen: SettingsScreenId? = null,
    ): Intent = Intent(context, ReaderActivity::class.java)
        .apply { sessionId?.let { putExtra(ReaderActivity.EXTRA_SESSION_ID, it) } }
        .apply { settingsScreen?.let { putExtra(ReaderActivity.EXTRA_SETTINGS_SCREEN, it.name) } }
        .putExtra(ReaderActivity.EXTRA_DESTINATION, destination.name)

    /**
     * `StationsContent`/`FrequenciesContent`/`ThreadContent` all populate their list from a real
     * suspend `*Polling` read inside a `LaunchedEffect`, not synchronously on first composition —
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

    private companion object {
        const val STATION_ID = "K7LWH"
        const val FREQUENCY_HZ = 146_960_000L

        // `TransmissionDetail.frequencyLabel`/`FrequencyViewMapper.listEntry` — "146.960" (Log,
        // Frequencies list rows) — matches both call sites' formatting for this Hz value.
        const val FREQUENCY_LABEL = "146.960"
    }
}
