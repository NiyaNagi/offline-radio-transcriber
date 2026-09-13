package org.ort.app.ui.improve

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.ort.app.ui.ReaderActivity
import org.ort.app.ui.navigation.ReaderDestination
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.ShedStatus
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Shorthand, matching `org.ort.app.ui.navigation.ReaderActivityDestinationSmokeTest`'s own
 * identical typealias for the same [AndroidComposeTestRule] instantiation. */
private typealias ReaderComposeTestRule = AndroidComposeTestRule<ActivityScenarioRule<ReaderActivity>, ReaderActivity>

/**
 * Register (coordinator round, WPIMPROVE): [ImprovePage] used to live in a plain `remember`
 * (`ImproveContent.kt`), so a configuration change without `android:configChanges` covering it —
 * `ReaderActivity` declares none — recreated the Activity and silently dropped `Running`/`Done`
 * back to `Root`; mid-run, that hid a reprocess still genuinely in progress.
 *
 * A real [ReaderActivity] (not a bare `ComponentActivity` with test-injected content) is required
 * here, not a convenience: `ReaderActivity.onCreate` is what calls `setContent` on every
 * `recreate()`, which is what lets a real `SaveableStateRegistry` restore state at all — a bare
 * `ComponentActivity` whose content the *test* sets once via `composeTestRule.setContent {}` has
 * nothing of its own to re-attach a composition on `recreate()` (confirmed directly: doing exactly
 * that failed both cases below with "No compose hierarchies found in the app", not the assertions
 * this file exists to make). This file builds and applies its own minimal
 * `ActivityScenarioRule<ReaderActivity>` + `AndroidComposeTestRule`, the same shape
 * `org.ort.app.ui.navigation.ReaderActivityDestinationSmokeTest.runReaderActivity` already
 * establishes (that file owns `ui/navigation`, not `ui/improve` — its own helper is `private`, so
 * this is a fresh, self-contained copy of the same pattern, not a reused one), simplified to the
 * one fixed destination (`IMPROVE_RECORDS`) every case here needs — the seed must land in the
 * database *before* the rule launches the Activity (a `@Before` method runs too late: rule
 * application wraps around it), which is also why the precedent builds and evaluates its own rule
 * manually per test rather than as a declarative `@get:Rule` field.
 *
 * Fixture-seeding and the "no ASR model installed" ending follow
 * `ReaderActivityDestinationSmokeTest.seedImproveTierSession`/
 * `R_350_improve_done_install_action_opens_settings_assets_for_a_real_missing_model_failure`
 * exactly — a real T1-tier session, a real (if dummy) audio file at exactly the path
 * `FlacSegmentAudioProvider` reads, and a real [RealImproveRunner] run against no installed model.
 */
@RunWith(RobolectricTestRunner::class)
class ImproveContentActivityTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        ShedStatus.reset()
    }

    @After
    fun tearDown() {
        ShedStatus.reset()
        CaptureState.idle(clearSession = true)
    }

    private fun seedTierSession(sessionId: String, transmissionId: String): Unit = runBlocking {
        val db = OrtDatabase.create(context)
        val audioFile = File(context.filesDir, "audio/$sessionId/$transmissionId.flac")
        audioFile.parentFile?.mkdirs()
        audioFile.writeBytes(ByteArray(2048))
        db.sessionDao().insert(
            SessionEntity(
                id = sessionId,
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
                id = transmissionId,
                sessionId = sessionId,
                threadId = null,
                startedAtUtc = 0L,
                endedAtUtc = 1_000L,
                durationMs = 4_200L,
                audioFormat = "flac/16k/mono",
                preRollMs = 200,
                postRollMs = 200,
                frequencyHz = 146_960_000L,
                frequencyProvenance = "measured",
                mode = null,
                signalStrength = 7.0,
                channelName = null,
                voiceprintId = null,
                attributionState = AttributionState.UNKNOWN,
                stationId = null,
                attributionConfidence = null,
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
    }

    /** Builds and runs a real [AndroidComposeTestRule] over a real [ReaderActivity] launched
     * straight onto `IMPROVE_RECORDS` — see this class's own doc comment for why this cannot be a
     * declarative `@get:Rule` field (the seed must land before the rule launches the Activity) or
     * a bare `ComponentActivity` (recreate() needs the real Activity's own `setContent` call). */
    private fun runImproveActivity(body: (rule: ReaderComposeTestRule) -> Unit) {
        val activityRule = ActivityScenarioRule<ReaderActivity>(
            Intent(context, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_DESTINATION, ReaderDestination.IMPROVE_RECORDS.name),
        )
        val rule = AndroidComposeTestRule(activityRule) { r ->
            var activity: ReaderActivity? = null
            r.scenario.onActivity { activity = it }
            checkNotNull(activity) { "ReaderActivity did not reach RESUMED" }
        }
        val statement = object : Statement() {
            override fun evaluate() {
                body(rule)
                rule.waitForIdle()
                // Same reasoning as the precedent's own teardown: freeze the clock, then force the
                // Activity all the way through DESTROYED before this rule's own teardown runs, so a
                // live poll loop's coroutine is confirmed cancelled rather than merely suspended.
                rule.mainClock.autoAdvance = false
                rule.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
                rule.waitForIdle()
            }
        }
        val description = Description.createTestDescription(
            ImproveContentActivityTest::class.java,
            "runImproveActivity",
        )
        rule.apply(statement, description).evaluate()
    }

    /**
     * Deterministic, not a race against a real (fast, model-less) reprocess run: arming the
     * engine's own capture-priority yield (FR-REP-6 — `ReprocessRunner.isCaptureBusy`) before the
     * run starts freezes it at its very first item forever, publishing
     * `ReprocessStatus.State.Paused` — [ImproveRunningScreen]'s own "waiting — capture is busy"
     * line — so this test observes a real, stable `Running` board rather than hoping to catch one
     * mid-flight before it races to `Done` on its own.
     */
    @Test
    fun `R_1064 Running survives a real Activity recreation, not bounced back to Root`() {
        seedTierSession("s-running", "s-running-tx")

        runImproveActivity { rule ->
            val groupRow = hasText("Captured at tier 1", substring = true) and hasClickAction()
            rule.waitUntil(15_000) { rule.onAllNodes(groupRow).fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(groupRow).performClick()

            val startButton = hasText("Improve 1 overs") and hasClickAction()
            rule.waitUntil(15_000) { rule.onAllNodes(startButton).fetchSemanticsNodes().isNotEmpty() }

            // Arm the freeze *after* Select has already resolved its own real state but *before*
            // the tap that starts the run.
            CaptureState.capturing("live-session")
            ShedStatus.update(level = 3, backlog = 0) // ReprocessRunner.BUSY_SHED_LEVEL_THRESHOLD
            rule.onNode(startButton).performClick()

            rule.waitUntil(15_000) {
                rule.onAllNodes(hasText("waiting", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }

            rule.activityRule.scenario.recreate()
            rule.waitForIdle()

            // The discriminating assertion: still the Running board (the real capture-priority
            // note republished by a freshly restarted run against the same still-armed freeze),
            // never the root's own list content.
            rule.waitUntil(15_000) {
                rule.onAllNodes(hasText("waiting", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("This phone can do more", substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun `R_1064 Done survives a real Activity recreation, keeping its real summary`() {
        seedTierSession("s-done", "s-done-tx")

        runImproveActivity { rule ->
            val improveAllButton = hasText("Improve all", substring = true) and hasClickAction()
            rule.waitUntil(15_000) { rule.onAllNodes(improveAllButton).fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(improveAllButton).performClick()

            // A real reprocess run against a real, model-less `filesDir` -- genuine queue-drain
            // work, not a fixed delay; neither `CaptureState` nor `ShedStatus` is armed busy here,
            // unlike the `Running` case above, so this one is left to complete on its own.
            val clearedLine = hasText("no longer marked as reprocessing candidates", substring = true)
            rule.waitUntil(30_000) { rule.onAllNodes(clearedLine).fetchSemanticsNodes().isNotEmpty() }

            rule.activityRule.scenario.recreate()
            rule.waitForIdle()

            // The discriminating assertion: the real summary line is shown again after
            // recreation -- never the root's own list content, and never a summary reset to a
            // fabricated default.
            rule.waitUntil(15_000) { rule.onAllNodes(clearedLine).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("This phone can do more", substring = true).assertDoesNotExist()
        }
    }
}
