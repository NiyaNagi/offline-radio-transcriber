package org.ort.app.ui.digest

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.ort.app.testing.ortComposeTestRule
import org.ort.app.ui.components.LOADING_STATE_TEST_TAG
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.pipeline.capture.CaptureState
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * Register R-1022/R-1051 (halt, constitution I/IV): [SessionsContent]'s own local `Loading`
 * composable, before this fix, was a plain untagged "Loading…" `Text` — real, and never itself an
 * empty-state false claim (its own `list`/`detail` state was already nullable, distinct from real
 * data), but invisible to [org.ort.app.debug.tour.TourAccessibilityScroll.snapshot]'s structural
 * readiness check, which scans for [LOADING_STATE_TEST_TAG]. `mainClock.autoAdvance = false`
 * freezes recomposition before `setContent` returns, so the assertions right after it inspect the
 * composed tree before the `LaunchedEffect` poll can ever land — the same idiom
 * `LogContentLoadingTest` already established for the sibling defect on Log.
 */
@RunWith(RobolectricTestRunner::class)
class SessionsContentLoadingTest {

    private val composeTestRule = createComposeRule()
    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @get:Rule
    val ruleChain: TestRule = ortComposeTestRule(composeTestRule) { db.close() }

    @Before
    fun setUp() {
        db = OrtDatabase.create(context)
        CaptureState.idle(clearSession = true)
    }

    @After
    fun tearDown() {
        CaptureState.idle(clearSession = true)
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

    private fun transmission(id: String) = TransmissionEntity(
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
    fun `R_1022 first frame of the sessions list is the shared loading state, never blank or empty`() {
        runBlocking {
            db.sessionDao().insert(session())
            db.transmissionDao().insert(transmission("TX1"))
        }
        CaptureState.capturing("S1")

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            OrtTheme { SessionsContent(context = context, onDrawer = {}) }
        }

        composeTestRule.onNodeWithTag(LOADING_STATE_TEST_TAG).assertExists()

        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitUntilTextExists("Tonight")
        composeTestRule.onNodeWithTag(LOADING_STATE_TEST_TAG).assertDoesNotExist()
    }

    @Test
    @Requirement("R-1022")
    fun `R_1022 first frame of a session detail is the shared loading state, never blank`() {
        runBlocking {
            db.sessionDao().insert(session())
            db.transmissionDao().insert(transmission("TX1"))
        }
        CaptureState.capturing("S1")

        // `initialSessionId` (R-133) seeds `SessionsPage.Detail` directly on first composition, so
        // freezing the clock *before* `setContent` — the same idiom the list case above and
        // `LogContentLoadingTest` both use — catches the detail page's own
        // `detail by remember(...) { mutableStateOf<SessionDetailViewState?>(null) }` frame, never
        // a click's own settle-to-idle (which would drain the `LaunchedEffect` regardless of
        // `autoAdvance` and hide the exact frame this test needs to see).
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            OrtTheme { SessionsContent(context = context, onDrawer = {}, initialSessionId = "S1") }
        }

        composeTestRule.onNodeWithTag(LOADING_STATE_TEST_TAG).assertExists()

        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitUntilTextExists("Export")
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Export"))
        composeTestRule.onNodeWithTag(LOADING_STATE_TEST_TAG).assertDoesNotExist()
    }
}
