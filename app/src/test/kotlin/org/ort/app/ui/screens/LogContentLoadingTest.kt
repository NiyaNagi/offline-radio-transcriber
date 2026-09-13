package org.ort.app.ui.screens

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.pipeline.capture.RigStatus
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * Register R-1051 (halt, constitution I/IV): [LogContent]'s own initial value, before this fix,
 * was `LogPolling.noSessionState()` whenever `sessionId` was non-null too — a real, honest "no
 * overs" claim that is exactly wrong the moment a cold start (the OS killing the app mid-capture,
 * then restarting it) hands this composable a `sessionId` for a session that already holds overs.
 * `mainClock.autoAdvance = false` freezes recomposition before `setContent` returns, so the
 * assertions right after it inspect the composed tree before the session-tied `LaunchedEffect`
 * poll (`LogPolling.screenState`) can ever be observed. Reverting the fix (seeding
 * `LogPolling.noSessionState()` for a non-null `sessionId` again) makes this fail with "No overs
 * yet" visible in that first frame instead of the loading marker.
 */
@RunWith(RobolectricTestRunner::class)
class LogContentLoadingTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
        RigStatus.reset()
    }

    @After
    fun resetRigStatus() {
        RigStatus.reset()
    }

    private fun ComposeContentTestRule.waitUntilTextExists(text: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun session(id: String) = SessionEntity(
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
    @Requirement("R-1051")
    fun `R_1051 first frame is loading, never No overs yet, for a session that already has overs`() {
        runBlocking {
            db.sessionDao().insert(session("LIVE-1"))
            db.transmissionDao().insert(transmission("LIVE-1-tx1", "LIVE-1", 1L))
        }

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            OrtTheme { LogContent(context = context, sessionId = "LIVE-1", onOpen = {}) }
        }

        composeTestRule.onNodeWithTag(LOADING_STATE_TEST_TAG).assertExists()
        composeTestRule.onNodeWithText("No overs yet", substring = true).assertDoesNotExist()

        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitUntilTextExists("146.960")
        composeTestRule.onNodeWithTag(LOADING_STATE_TEST_TAG).assertDoesNotExist()
    }
}
