package org.ort.app.ui.screens

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.AttributionState
import org.ort.core.SystemClock
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
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
}
