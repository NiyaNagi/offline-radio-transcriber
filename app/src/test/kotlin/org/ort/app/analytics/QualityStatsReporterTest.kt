package org.ort.app.analytics

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.robolectric.RobolectricTestRunner

/**
 * FR-ANL-2: [QualityStatsReporter] wires the real `:data` read to the real analytics queue —
 * Robolectric proves that wiring; [QualityStatsAggregatorTest] already proves the arithmetic
 * itself in isolation.
 */
@RunWith(RobolectricTestRunner::class)
class QualityStatsReporterTest {

    @Before
    fun resetSingleton() {
        AnalyticsAppWiring.resetForTest()
    }

    @After
    fun tearDown() {
        AnalyticsAppWiring.resetForTest()
    }

    private fun context() = ApplicationProvider.getApplicationContext<Application>()

    private fun transmission(id: String) = TransmissionEntity(
        id = id,
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
    @org.ort.testing.Requirement("FR-ANL-2")
    fun `FR_ANL_2_reportOnce submits a QualityStats event when transmissions exist`() = runTest {
        AnalyticsAppWiring.configureOnce(context())
        val db = OrtDatabase.create(context())
        db.sessionDao().insert(
            SessionEntity(
                id = "S1",
                startedAt = 0L,
                endedAt = null,
                profileId = null,
                deviceTier = null,
                appVersion = "test",
                terminationReason = null,
                sourceId = null,
                schemaVersion = OrtDatabase.SCHEMA_VERSION,
            ),
        )
        db.transmissionDao().insert(transmission("T1"))

        QualityStatsReporter.reportOnce(context())

        // Tier 1 is on by default (AC-172): the event above reached the real queue -- provable
        // without a package-private accessor by observing that runUploadOnce now finds something
        // queued (NotConfigured, not NothingQueued: no endpoint is set in a test build, D48).
        assertEquals(AnalyticsUploadRunOutcome.NotConfigured, AnalyticsAppWiring.runUploadOnce())
    }

    @Test
    @org.ort.testing.Requirement("FR-ANL-2")
    fun `FR_ANL_2_reportOnce submits nothing when there are no transmissions yet`() = runTest {
        AnalyticsAppWiring.configureOnce(context())
        OrtDatabase.create(context()) // opened, but nothing inserted

        QualityStatsReporter.reportOnce(context())

        assertEquals(AnalyticsUploadRunOutcome.NothingQueued, AnalyticsAppWiring.runUploadOnce())
    }
}
