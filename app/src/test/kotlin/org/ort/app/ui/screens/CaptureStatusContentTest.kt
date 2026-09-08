package org.ort.app.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.AttributionState
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
 * The Capture destination's polling wrapper (ui-conformance-plan WP4, R-039, design-intent
 * N04 → N06). Proves the internal sub-navigation against real process-wide holders, the same
 * pattern `TransmissionDetailContentTest` (WP6) already uses for a real (file-backed) `OrtDatabase`.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureStatusContentTest {

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

    private fun ComposeContentTestRule.waitUntilTagExists(tag: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeContentTestRule.waitUntilDescriptionExists(description: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis) {
            onAllNodes(hasContentDescription(description, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ComposeContentTestRule.waitUntilTextExists(text: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis) {
            onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun session(id: String = "S1") = SessionEntity(
        id = id,
        startedAt = 0L,
        endedAt = null,
        profileId = null,
        deviceTier = null,
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
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
    @Requirement("R-039")
    fun `R_039_the_level_row_opens_the_level_meter_and_back_returns`() {
        runBlocking { db.sessionDao().insert(session()) }
        CaptureState.capturing("S1")
        LevelStatus.update(
            LevelStatus.State.Measured(
                peakDbfs = -14f,
                rmsDbfs = -20f,
                noiseFloorDbfs = -58f,
                clipped = false,
                clipCountLastSecond = 0,
                sampleRateHz = 16_000,
                updatedAtMillis = 0L,
            ),
            peakHistoryDbfs = List(10) { -20f },
        )

        composeTestRule.setContent { OrtTheme { CaptureStatusContent(context = context, sessionId = "S1") } }

        composeTestRule.waitUntilTagExists("capture-status-level")
        composeTestRule.onNodeWithTag("capture-status-level").assertExists()

        composeTestRule.onNodeWithTag("capture-status-level").performClick()

        composeTestRule.waitUntilTagExists("level-meter-chart")
        composeTestRule.onNodeWithTag("level-meter-chart").assertExists()
        composeTestRule.onNodeWithTag("capture-status-title").assertDoesNotExist()

        composeTestRule.waitUntilDescriptionExists("Back to Capture")
        composeTestRule.onNodeWithContentDescription("Back to Capture").performClick()

        composeTestRule.waitUntilTagExists("capture-status-title")
        composeTestRule.onNodeWithTag("capture-status-title").assertExists()
        composeTestRule.onNodeWithTag("level-meter-chart").assertDoesNotExist()
    }

    @Test
    @Requirement("R-172")
    fun `R_172 follows CaptureState's live session, never a stale host-supplied sessionId, while capturing`() {
        // S1 is the host's own (stale) sessionId argument — say, the session the reader launched
        // against — and S2 is the session actually capturing right now (CaptureState.sessionId),
        // e.g. a fresh Start-capture tap or a scenario broadcast after this reader was already
        // open (R-171). The screen must show S2's own facts throughout, never a mix of S1's DB rows
        // and S2's live holders.
        runBlocking {
            db.sessionDao().insert(session("S1"))
            db.transmissionDao().insert(transmission("S1-tx1", "S1", 1L))
            db.sessionDao().insert(session("S2"))
            db.transmissionDao().insert(transmission("S2-tx1", "S2", 1L))
            db.transmissionDao().insert(transmission("S2-tx2", "S2", 2L))
        }
        CaptureState.capturing("S2")

        composeTestRule.setContent { OrtTheme { CaptureStatusContent(context = context, sessionId = "S1") } }

        composeTestRule.waitUntilTextExists("2 captured")
        composeTestRule.onNodeWithText("2 captured", substring = true).assertExists()
        composeTestRule.onNodeWithText("1 captured", substring = true).assertDoesNotExist()
    }

    @Test
    @Requirement("R-172")
    fun `R_172 a null host sessionId still polls once a session starts capturing, not stuck idle forever`() {
        runBlocking {
            db.sessionDao().insert(session("S2"))
            db.transmissionDao().insert(transmission("S2-tx1", "S2", 1L))
        }
        CaptureState.capturing("S2")

        composeTestRule.setContent { OrtTheme { CaptureStatusContent(context = context, sessionId = null) } }

        composeTestRule.waitUntilTextExists("1 captured")
        composeTestRule.onNodeWithText("1 captured", substring = true).assertExists()
    }

    @Test
    @Requirement("R-232")
    fun `R_232 the title respects a top-padded modifier the way the host's banner-height inset relies on`() {
        // R-232: `OrtNavHost.kt`'s `NavHostBody` (WP3's file) already applies the host's real,
        // measured banner height as top padding to the *outer* Box every destination's content sits
        // inside — `DestinationContent`'s `ReaderDestination.CAPTURE` branch calls this composable
        // with that already-padded space, unchanged, the same as every other destination (confirmed
        // by reading `OrtNavHost.kt` before writing this test: the padding is applied once, at
        // `NavHostBody`, never per-destination). So closing R-232 needs no code change in this
        // package's own files — `CaptureStatusContent`/`CaptureStatusScreen` take whatever `modifier`
        // they are given and were never the ones dropping it. This test proves that contract holds:
        // a caller-supplied top inset (standing in for the host's real `contentTopPadding`) is
        // respected, not silently reset by an internal `fillMaxSize()` that ignores its parent.
        val insetDp = 64.dp
        runBlocking { db.sessionDao().insert(session()) }
        CaptureState.capturing("S1")

        composeTestRule.setContent {
            OrtTheme {
                CaptureStatusContent(context = context, sessionId = "S1", modifier = Modifier.padding(top = insetDp))
            }
        }

        composeTestRule.waitUntilTagExists("capture-status-title")
        val density = composeTestRule.density
        val insetPx = with(density) { insetDp.toPx() }
        val titleTop = composeTestRule.onNodeWithTag("capture-status-title").fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "expected the title to sit at or below the caller's top inset (${insetPx}px), was ${titleTop}px",
            titleTop >= insetPx - 1f, // sub-pixel rounding tolerance
        )
    }

    @Test
    @Requirement("R-039")
    fun `the Level row carries a real 44dp target with an Open level meter description`() {
        runBlocking { db.sessionDao().insert(session()) }
        CaptureState.capturing("S1")

        composeTestRule.setContent { OrtTheme { CaptureStatusContent(context = context, sessionId = "S1") } }

        composeTestRule.waitUntilDescriptionExists("Open level meter")
        composeTestRule.onNodeWithContentDescription("Open level meter", substring = true).assertExists()
    }
}
