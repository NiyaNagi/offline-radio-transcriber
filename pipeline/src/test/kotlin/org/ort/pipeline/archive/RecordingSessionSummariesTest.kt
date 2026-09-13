package org.ort.pipeline.archive

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity
import org.ort.data.entity.TransmissionLabelEntity
import org.ort.pipeline.PipelineTestFixtures
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/** `Recordings.dc.html` (RC01): the per-session row RC01's list renders from. */
@RunWith(RobolectricTestRunner::class)
class RecordingSessionSummariesTest {

    @Test
    @Requirement("FR-STO-3", "FR-OBS-4")
    fun `a session with two overs, one failed and one labelled, reports every real count`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1").copy(startedAt = 1_000L, endedAt = 9_000L))
        db.transmissionDao().insert(
            PipelineTestFixtures.transmission("TX1", sessionId = "S1")
                .copy(processingState = TransmissionState.COMPLETE),
        )
        db.transmissionDao().insert(
            PipelineTestFixtures.transmission("TX2", sessionId = "S1")
                .copy(processingState = TransmissionState.FAILED),
        )
        db.transmissionLabelDao().upsert(
            TransmissionLabelEntity(transmissionId = "TX1", markedForTraining = true, labelledAtMillis = 1L),
        )

        val summary = recordingSessionSummaries(db).single()

        assertEquals("S1", summary.sessionId)
        assertEquals(1_000L, summary.startedAtMillis)
        assertEquals(9_000L, summary.endedAtMillis)
        assertEquals(2, summary.overCount)
        assertEquals(1, summary.failedCount)
        assertEquals(1, summary.labelledCount)
    }

    @Test
    @Requirement("FR-STO-3d")
    fun `archive and over-audio removal facts are carried through from the real session row`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))
        db.sessionDao().setArchiveKept("S1")
        db.sessionDao().setArchiveRemoved("S1", removedAtMillis = 5_000L)
        db.sessionDao().setOverAudioRemoved("S1", removedAtMillis = 6_000L)

        val summary = recordingSessionSummaries(db).single()

        assertEquals(ArchiveState.REMOVED, summary.archiveState)
        assertEquals(5_000L, summary.archiveRemovedAtMillis)
        assertEquals(6_000L, summary.overAudioRemovedAtMillis)
    }

    @Test
    @Requirement("FR-STO-3")
    fun `a session with no archive and no removed over audio reads NONE and null, never fabricated`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))

        val summary = recordingSessionSummaries(db).single()

        assertEquals(ArchiveState.NONE, summary.archiveState)
        assertEquals(null, summary.archiveRemovedAtMillis)
        assertEquals(null, summary.overAudioRemovedAtMillis)
        assertEquals(0, summary.overCount)
        assertEquals(0, summary.failedCount)
        assertEquals(0, summary.labelledCount)
        assertEquals(0, summary.stationCount)
        assertEquals(0, summary.gapCount)
    }

    /** design-intent row RC01 (`Recordings.dc.html`'s own "N overs · N stations · N gaps"
     * sub-line) — the artboard's real, distinct facts, not the over count repeated three times. */
    @Test
    @Requirement("FR-STO-3", "FR-RUN-12")
    fun `station and gap counts are real, distinct facts, never derived from the over count`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))
        db.transmissionDao().insert(
            PipelineTestFixtures.transmission("TX1", sessionId = "S1").copy(stationId = "W7NPC"),
        )
        db.transmissionDao().insert(
            PipelineTestFixtures.transmission("TX2", sessionId = "S1").copy(stationId = "W7NPC"),
        )
        db.transmissionDao().insert(
            PipelineTestFixtures.transmission("TX3", sessionId = "S1").copy(stationId = "K7LWH"),
        )
        db.transmissionDao().insert(PipelineTestFixtures.transmission("TX4", sessionId = "S1"))
        db.captureGapDao().insert(
            CaptureGapEntity(
                id = "G1",
                sessionId = "S1",
                startedAt = 1_000L,
                endedAt = 2_000L,
                cause = CaptureGapCause.INPUT_LOST,
                recoveredAutomatically = true,
            ),
        )

        val summary = recordingSessionSummaries(db).single()

        assertEquals(4, summary.overCount)
        assertEquals("2 distinct stations, never a repeat of the over count", 2, summary.stationCount)
        assertEquals(1, summary.gapCount)
    }
}
