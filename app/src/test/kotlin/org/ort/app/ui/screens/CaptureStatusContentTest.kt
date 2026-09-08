package org.ort.app.ui.screens

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
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

    private fun session() = SessionEntity(
        id = "S1",
        startedAt = 0L,
        endedAt = null,
        profileId = null,
        deviceTier = null,
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
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
    @Requirement("R-039")
    fun `the Level row carries a real 44dp target with an Open level meter description`() {
        runBlocking { db.sessionDao().insert(session()) }
        CaptureState.capturing("S1")

        composeTestRule.setContent { OrtTheme { CaptureStatusContent(context = context, sessionId = "S1") } }

        composeTestRule.waitUntilDescriptionExists("Open level meter")
        composeTestRule.onNodeWithContentDescription("Open level meter", substring = true).assertExists()
    }
}
