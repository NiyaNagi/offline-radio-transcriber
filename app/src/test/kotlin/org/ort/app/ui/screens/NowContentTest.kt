package org.ort.app.ui.screens

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.components.LOADING_STATE_TEST_TAG
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.AttributionState
import org.ort.core.SystemClock
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.pipeline.capture.VadAvailability
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * The "Now" destination's polling wrapper (ui-conformance-plan WP4, R-172). Proves the halt-
 * severity session-keying fix against a real (file-backed) `OrtDatabase`, the same pattern
 * `CaptureStatusContentTest` already uses for the sibling destination.
 */
@RunWith(RobolectricTestRunner::class)
class NowContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    @After
    fun resetProcessWideAvailability() {
        AsrAvailability.reset()
        VadAvailability.reset()
        CaptureState.idle(clearSession = true)
        ShedStatus.reset()
        ThermalStatus.reset()
        RigStatus.reset()
        StorageForecast.reset()
        InputStatus.reset()
        LevelStatus.reset()
    }

    private fun ComposeContentTestRule.waitUntilTextExists(text: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis) {
            onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // A recent, not-epoch-zero start: `ActivityPatternMapper.buildPattern` (called by
    // `NowViewStateMapper.active`) walks every real hour between session start and "now" —
    // starting at 1970 against a 2026 clock would iterate roughly half a million hours for no
    // reason this test cares about.
    private fun session(id: String, captureMode: String? = null) = SessionEntity(
        id = id,
        startedAt = SystemClock.wallMillis() - 3_600_000L,
        endedAt = null,
        profileId = null,
        deviceTier = null,
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
        captureMode = captureMode,
    )

    private fun transmission(id: String, sessionId: String, samplePosition: Long) = TransmissionEntity(
        id = id,
        sessionId = sessionId,
        threadId = null,
        startedAtUtc = samplePosition,
        endedAtUtc = samplePosition + 1_000L,
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
        processingState = TransmissionState.CAPTURED,
        rejectionReason = null,
        samplePosition = samplePosition,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    @Test
    @Requirement("R-172")
    fun `R_172 Now follows CaptureState's live session, never the host's stale sessionId argument`() {
        runBlocking {
            db.sessionDao().insert(session("S1"))
            db.transmissionDao().insert(transmission("S1-tx1", "S1", 1L))
            db.sessionDao().insert(session("S2"))
            db.transmissionDao().insert(transmission("S2-tx1", "S2", 1L))
            db.transmissionDao().insert(transmission("S2-tx2", "S2", 2L))
        }
        CaptureState.capturing("S2")

        composeTestRule.setContent {
            OrtTheme { NowContent(context = context, sessionId = "S1", onOpenTransmission = {}, onOpenStation = {}) }
        }

        composeTestRule.waitUntilTextExists("2 overs")
        composeTestRule.onNodeWithText("2 overs · 0 stations", substring = true).assertExists()
        composeTestRule.onNodeWithText("1 overs · 0 stations", substring = true).assertDoesNotExist()
    }

    // checklist row E2-G02 (N01b's persistent room-audio disclosure).
    @Test
    @Requirement("FR-CAP-3a")
    fun `FR_CAP_3a a local-microphone session shows the room-audio chip through the real read path`() {
        runBlocking {
            db.sessionDao().insert(session("ROOM-1", captureMode = "LOCAL_MICROPHONE"))
            db.transmissionDao().insert(transmission("ROOM-1-tx1", "ROOM-1", 1L))
        }
        CaptureState.capturing("ROOM-1")

        composeTestRule.setContent {
            OrtTheme {
                NowContent(context = context, sessionId = "ROOM-1", onOpenTransmission = {}, onOpenStation = {})
            }
        }

        composeTestRule.waitUntilTextExists("Room audio")
        composeTestRule.onNodeWithText("Room audio", substring = true).assertExists()
    }

    // R-1074 (register, halt, constitution I): before that fix, `Now`'s chart bucketed a session
    // into whole *clock* hours and a tap opened exactly `[sessionStart, sessionStart + 1h)` no
    // matter what the session's own real gaps were. R-1074 replaced that with
    // `SessionCoverageMapper.buildSegments`'s real, gap-boundary-accurate segments (R-1069's own
    // fix, reused) — a tap now opens the *segment's own real span*, never a fabricated hour. This
    // test still proves the real DB-to-tap-to-callback wiring (its own original purpose), updated
    // to assert the contract R-1074 actually establishes: bar 0 here is the real listening segment
    // *before* a real, recorded gap, so its own real end is the gap's own real start — a value that
    // would be wildly wrong (short by nearly 59 minutes) under the old whole-hour contract, and is
    // fully deterministic (unlike the session's still-live, `SystemClock`-derived "now") because it
    // comes from a fixed, already-recorded gap row rather than the still-running session's open end.
    @Test
    @Requirement("R-1041")
    fun `R_1041 a tapped bar opens the Log filtered to its own real segment window, real read path`() {
        val sessionStart = SystemClock.wallMillis() - 3_600_000L
        val gapStart = sessionStart + 10_000L
        val gapEnd = sessionStart + 20_000L
        runBlocking {
            db.sessionDao().insert(session("HOUR-1").copy(startedAt = sessionStart))
            // Inside the real listening segment bar 0 is: before the gap, well after session start.
            db.transmissionDao().insert(transmission("HOUR-1-tx1", "HOUR-1", sessionStart + 5_000L))
            db.captureGapDao().insert(
                CaptureGapEntity(
                    id = "HOUR-1-G1",
                    sessionId = "HOUR-1",
                    startedAt = gapStart,
                    endedAt = gapEnd,
                    cause = CaptureGapCause.INTERRUPTION,
                    recoveredAutomatically = true,
                ),
            )
        }
        CaptureState.capturing("HOUR-1")

        var openedFrom: Long? = null
        var openedTo: Long? = null
        composeTestRule.setContent {
            OrtTheme {
                NowContent(
                    context = context,
                    sessionId = "HOUR-1",
                    onOpenTransmission = {},
                    onOpenStation = {},
                    onOpenHour = { fromMillis, toMillis ->
                        openedFrom = fromMillis
                        openedTo = toMillis
                    },
                )
            }
        }

        composeTestRule.waitUntilTextExists("1 over")
        composeTestRule.onNodeWithTag("activity-bar-0").performClick()

        // R-1074: the segment's own real start and end — never `sessionStart + 1h` (the old,
        // fabricated whole-hour window), and never left open to "now" either, since a real gap
        // closes this segment off well before the still-live session's own open end.
        assert(openedFrom == sessionStart) { "expected the real session start ($sessionStart), got $openedFrom" }
        assert(openedTo != null) { "expected a real window, got none — the bar carried no click action" }
        // A small float-fraction-round-trip tolerance (see `NowViewStateMapper.segmentRealWindows`'s
        // own kdoc) — many orders of magnitude tighter than the ~59-minute error the old whole-hour
        // contract would have produced for this exact case.
        val toleranceMillis = 50L
        assert(Math.abs(openedTo!! - (gapStart - 1)) <= toleranceMillis) {
            "expected the segment's own real end, the real gap's own start minus one (${gapStart - 1}), " +
                "got $openedTo — the old whole-hour contract would have produced ${sessionStart + 3_600_000L - 1}"
        }
        // R-1041's own point: the window must actually contain the tapped-over's real timestamp.
        val transmissionAt = sessionStart + 5_000L
        assert(transmissionAt in openedFrom!!..openedTo!!) {
            "expected the tapped over's own timestamp ($transmissionAt) to fall inside the opened " +
                "window [$openedFrom, $openedTo] — a tap that does not land on its own overs is useless"
        }
    }

    /**
     * Register R-1051 (halt, constitution I/IV): before this fix, `NowContent`'s own initial
     * `remember` value was `NowViewState.Idle(...)` directly — a real "Not capturing" claim, not a
     * placeholder — so a session that is genuinely live from the very first frame (this test's own
     * `CaptureState.capturing`, set before composition) still read "Not capturing" until the first
     * poll landed. `mainClock.autoAdvance = false` freezes recomposition before `setContent`
     * returns, so the very first composed frame — before the `LaunchedEffect` poll can ever be
     * observed — is what this test inspects. Reverting the fix (seeding `NowViewState.Idle`
     * again) makes this fail with "Not capturing" visible in that first frame instead.
     */
    @Test
    @Requirement("R-1051")
    fun `R_1051 Now's first frame is loading, never Not capturing, for an already-live session`() {
        runBlocking {
            db.sessionDao().insert(session("LIVE-1"))
            db.transmissionDao().insert(transmission("LIVE-1-tx1", "LIVE-1", 1L))
        }
        CaptureState.capturing("LIVE-1")

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            OrtTheme {
                NowContent(context = context, sessionId = "LIVE-1", onOpenTransmission = {}, onOpenStation = {})
            }
        }

        composeTestRule.onNodeWithTag(LOADING_STATE_TEST_TAG).assertExists()
        composeTestRule.onNodeWithText("Not capturing", substring = true).assertDoesNotExist()

        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitUntilTextExists("1 over")
        composeTestRule.onNodeWithTag(LOADING_STATE_TEST_TAG).assertDoesNotExist()
    }
}
