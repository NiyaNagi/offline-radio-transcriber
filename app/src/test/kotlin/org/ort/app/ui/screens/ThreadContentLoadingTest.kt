package org.ort.app.ui.screens

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
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
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * Register R-1022/R-1051 (halt, constitution I/IV): [ThreadContent]'s own initial value, before
 * this fix, was [org.ort.app.ui.data.ThreadListViewState.Empty] directly — a real, honest "nothing
 * captured yet" claim (also [org.ort.app.ui.data.ThreadListMapper.listState]'s genuine return for
 * an empty session), not a placeholder — so a live session with real, ungrouped overs rendered
 * "No overs yet." for up to one poll interval. `mainClock.autoAdvance = false` before `setContent`
 * freezes recomposition so the assertion right after inspects the composed tree before the
 * session-tied `LaunchedEffect` poll can land — the same idiom `LogContentLoadingTest` established.
 */
@RunWith(RobolectricTestRunner::class)
class ThreadContentLoadingTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        db = OrtDatabase.create(context)
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

    private fun transmission(id: String, sessionId: String) = TransmissionEntity(
        id = id,
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
        samplePosition = 0L,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    @Test
    @Requirement("R-1022")
    fun `R_1022 first frame is loading, never No overs yet, for a session that already has overs`() {
        runBlocking {
            db.sessionDao().insert(session("LIVE-1"))
            db.transmissionDao().insert(transmission("LIVE-1-tx1", "LIVE-1"))
        }

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            OrtTheme { ThreadContent(context = context, sessionId = "LIVE-1", onOpen = {}) }
        }

        composeTestRule.onNodeWithTag(LOADING_STATE_TEST_TAG).assertExists()
        composeTestRule.onNodeWithText("No overs yet", substring = true).assertDoesNotExist()

        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitUntilTextExists("not yet grouped")
        composeTestRule.onNodeWithTag(LOADING_STATE_TEST_TAG).assertDoesNotExist()
    }

    @Test
    @Requirement("R-1022")
    fun `a null session id reads as the honest empty state, never stuck loading`() {
        composeTestRule.setContent {
            OrtTheme { ThreadContent(context = context, sessionId = null, onOpen = {}) }
        }

        composeTestRule.onNodeWithText("No overs yet.", substring = true).assertExists()
        composeTestRule.onNodeWithTag(LOADING_STATE_TEST_TAG).assertDoesNotExist()
    }
}
