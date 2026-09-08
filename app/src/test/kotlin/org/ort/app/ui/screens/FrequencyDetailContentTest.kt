package org.ort.app.ui.screens

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.FrequencyDetailView
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.robolectric.RobolectricTestRunner

/**
 * `FrequencyDetailContent`'s own real read path (R-276, register, spec, coordinator round
 * 2026-09-08) — proves [FrequencyDetailContent]'s `initialView` parameter lands the operator
 * directly on `Frequency-Change` when reopened from the Log (R-276's "The N overs" round trip via
 * [org.ort.app.ui.data.TimeWindow]/`onOpenOvers`), never back on the drill-in's own root, against
 * a real (file-backed) [OrtDatabase] — the same pattern `StationDetailContentTest` already uses.
 */
@RunWith(RobolectricTestRunner::class)
class FrequencyDetailContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
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
        stationId = null,
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

    private fun ComposeContentTestRule.waitUntilTextExists(text: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `R_276_back initialView Change opens directly on Frequency-Change, not the drill-in root`() {
        runBlocking {
            db.sessionDao().insert(session())
            db.transmissionDao().insert(transmission())
        }

        composeTestRule.setContent {
            FrequencyDetailContent(
                context = context,
                frequencyHz = 146_960_000L,
                onBack = {},
                initialView = FrequencyDetailView.Change,
            )
        }

        // `Frequency-Change`'s own closing paragraph opener — it has no equivalent on the
        // drill-in's root (`FrequencyDetailScreen` never renders this sentence), so its presence
        // proves `sub` was seeded from `initialView`, never defaulted back to `Detail`.
        composeTestRule.waitUntilTextExists("A departure is a finding, not an alarm.")
    }
}
