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
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.ort.app.testing.ortComposeTestRule
import org.ort.app.ui.components.LOADING_STATE_TEST_TAG
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TransmissionEntity
import org.robolectric.RobolectricTestRunner

/**
 * Register R-1022/R-1051 (halt, constitution I/IV): [StationsContent]'s own initial `allStations`
 * was `emptyList()`, indistinguishable from "queried, and genuinely no stations heard" —
 * [StationsListScreen] rendered "No stations heard yet" for up to one load, even for a device
 * with a real history, exactly the defect class [StationsListState.loading]'s own kdoc names.
 * `mainClock.autoAdvance = false` before `setContent` freezes recomposition so the assertion right
 * after inspects the composed tree before `LaunchedEffect(Unit)`'s poll can land — the same idiom
 * `LogContentLoadingTest` established.
 */
@RunWith(RobolectricTestRunner::class)
class StationsContentLoadingTest {

    private val composeTestRule = createComposeRule()
    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @get:Rule
    val ruleChain: TestRule = ortComposeTestRule(composeTestRule) { db.close() }

    @Before
    fun openDatabase() {
        context.deleteDatabase(OrtDatabase.DATABASE_NAME)
        db = OrtDatabase.create(context)
    }

    private fun ComposeContentTestRule.waitUntilTextExists(text: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
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

    private fun station() = StationEntity(
        id = "WA7HJR",
        callsign = "WA7HJR",
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
    )

    private fun transmission() = TransmissionEntity(
        id = "TX1",
        sessionId = "S1",
        threadId = null,
        startedAtUtc = 0L,
        endedAtUtc = 1_000L,
        durationMs = 1_000L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = 146_960_000L,
        frequencyProvenance = "measured",
        mode = "FM",
        signalStrength = 7.0,
        channelName = null,
        voiceprintId = null,
        attributionState = AttributionState.CONFIRMED,
        stationId = "WA7HJR",
        attributionConfidence = 0.9,
        attributionSourceTransmissionId = null,
        corrected = false,
        processingState = TransmissionState.CAPTURED,
        rejectionReason = null,
        samplePosition = 0L,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    @Test
    fun `R_1022 first frame is loading, never No stations heard yet, for a device with real history`() {
        runBlocking {
            db.sessionDao().insert(session())
            db.catalogDao().insert(station())
            db.transmissionDao().insert(transmission())
        }

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent { StationsContent(context = context, onOpen = {}) }

        composeTestRule.onNodeWithTag(LOADING_STATE_TEST_TAG).assertExists()
        composeTestRule.onNodeWithText("No stations heard yet", substring = true).assertDoesNotExist()

        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitUntilTextExists("WA7HJR")
        composeTestRule.onNodeWithTag(LOADING_STATE_TEST_TAG).assertDoesNotExist()
    }
}
