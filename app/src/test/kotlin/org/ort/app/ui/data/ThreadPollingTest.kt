package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * `ThreadPolling` (`ThreadViewData.kt`'s own read path) reads through
 * [ReaderPolling.currentTransmissionDetails], so it inherited that file's own former
 * `attributionFrom` bug: `CorrectionDao.applyCorrectedAttribution` always writes
 * `attributionState = INFERRED` with `attributionConfidence = NULL`, which the old, un-shared
 * reconstruction downgraded to `Attribution.unknown()` for every corrected transmission — a
 * corrected over read as its real callsign on the Detail screen (`CorrectionPolling
 * .currentAttribution`'s own, separate reconstruction) but as UNKNOWN on the Threads screen. This
 * is the real DAO-backed proof that fixing the shared reconstruction fixes this path too, with no
 * edit of `ThreadViewData.kt`/`ThreadPolling` itself.
 */
@RunWith(RobolectricTestRunner::class)
class ThreadPollingTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: OrtDatabase

    private fun openDatabase() {
        db = OrtDatabase.create(context)
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

    private fun transmission(id: String, threadId: String, startedAtUtc: Long) = TransmissionEntity(
        id = id,
        sessionId = "S1",
        threadId = threadId,
        startedAtUtc = startedAtUtc,
        endedAtUtc = startedAtUtc + 1_000L,
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
        samplePosition = startedAtUtc,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    @Test
    @Requirement("FR-SPK-7")
    fun `FR_SPK_7 a corrected transmission renders its real callsign on the Threads screen, never UNKNOWN`(): Unit =
        runTest {
            openDatabase()
            db.sessionDao().insert(session())
            db.transmissionDao().insert(transmission("TX1", threadId = "THREAD1", startedAtUtc = 0L))
            db.correctionDao().applyCorrectedAttribution("TX1", "KA7LWH")

            val detail = ThreadPolling.threadDetail(context, "S1", "THREAD1")

            val over = detail?.overs?.single { it.transmissionId == "TX1" }
            assertEquals(AttributionState.INFERRED, over?.attribution?.state)
            assertEquals("KA7LWH", over?.attribution?.stationId)
            assertTrue(over?.attribution?.corrected == true)
        }
}
