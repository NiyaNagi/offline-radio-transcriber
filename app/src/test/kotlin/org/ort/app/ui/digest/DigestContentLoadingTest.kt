package org.ort.app.ui.digest

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
 * Register R-1022/R-1051 (halt, constitution I/IV): [DigestContent]'s own initial `state` was
 * already the nullable seed a real empty digest never is — never the R-1051-class false-empty bug
 * itself — but before this fix, the not-yet-loaded branch rendered a local, untagged "Loading…"
 * `Text`, invisible to [org.ort.app.debug.tour.TourAccessibilityScroll.snapshot]'s structural
 * readiness scan for [LOADING_STATE_TEST_TAG]. `mainClock.autoAdvance = false` before `setContent`
 * freezes recomposition so the assertion right after inspects the composed tree before the
 * session-tied `LaunchedEffect` poll (`DigestPolling.digest`) can land — the same idiom
 * `LogContentLoadingTest`/`SessionsContentLoadingTest` already use.
 */
@RunWith(RobolectricTestRunner::class)
class DigestContentLoadingTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        db = OrtDatabase.create(context)
    }

    private fun ComposeContentTestRule.waitUntilTextExists(text: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis) {
            runCatching { onNodeWithText(text, substring = true).assertExists() }.isSuccess
        }
    }

    private fun session() = SessionEntity(
        id = "S1",
        startedAt = 0L,
        endedAt = 3_600_000L,
        profileId = null,
        deviceTier = null,
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
    )

    private fun transmission(id: String, stationId: String? = null) = TransmissionEntity(
        id = id,
        sessionId = "S1",
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
        attributionState = if (stationId != null) AttributionState.CONFIRMED else AttributionState.UNKNOWN,
        stationId = stationId,
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
    fun `R_1022 first frame of the digest is the shared loading state, never blank`() {
        runBlocking {
            db.sessionDao().insert(session())
            db.transmissionDao().insert(transmission("TX1", stationId = "W7NEW"))
        }

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            OrtTheme {
                DigestContent(
                    context = context,
                    sessionId = "S1",
                    onBack = {},
                    onOpenItem = {},
                    onFullLog = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(LOADING_STATE_TEST_TAG).assertExists()

        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitUntilTextExists("W7NEW heard for the first time")
        composeTestRule.onNodeWithTag(LOADING_STATE_TEST_TAG).assertDoesNotExist()
    }
}
