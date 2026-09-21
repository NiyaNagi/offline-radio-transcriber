package org.ort.pipeline.threading

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.data.OrtDatabase
import org.ort.data.entity.ThreadJoinReason
import org.ort.data.entity.ThreadKind
import org.ort.pipeline.PipelineTestFixtures
import org.robolectric.RobolectricTestRunner

/**
 * Register R-1118 (FR-SPK-29, FR-SPK-30, AC-164): [RoomThreadRepository.participantOrder] used to
 * filter to overs carrying a resolved [org.ort.data.entity.TransmissionEntity.stationId], so an
 * `UNKNOWN` (or, since R-1110, `AMBIGUOUS` — every real resolution today without a calibrator) over
 * was silently absent from the order a net check-in roster (FR-SPK-30) would be built from — exactly
 * the check-ins that need an operator's attention. Real (in-memory) [OrtDatabase], real
 * [RoomThreadRepository], no fake — the defect lived in the raw-SQL write path itself.
 */
@RunWith(RobolectricTestRunner::class)
class RoomThreadRepositoryTest {

    private suspend fun freshDb(): OrtDatabase {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session())
        return db
    }

    private suspend fun seedTransmission(
        db: OrtDatabase,
        id: String,
        samplePosition: Long,
        startedAtUtc: Long,
        frequencyHz: Long?,
        stationId: String? = null,
        voiceprintId: String? = null,
    ) {
        db.transmissionDao().insert(
            PipelineTestFixtures.transmission(id).copy(
                samplePosition = samplePosition,
                startedAtUtc = startedAtUtc,
                endedAtUtc = startedAtUtc + 2_000L,
                frequencyHz = frequencyHz,
                stationId = stationId,
                voiceprintId = voiceprintId,
            ),
        )
    }

    @Test
    fun `R_1118 an unattributed over still occupies its own slot in the participant order, not dropped`() = runTest {
        val db = freshDb()
        val repo = RoomThreadRepository(db)
        val freq = 146_520_000L
        seedTransmission(db, "TX1", 0, 0, freq, stationId = "STATION-K7ABC")
        // TX2 is an ordinary UNKNOWN over -- no resolved station, no voiceprint match either.
        seedTransmission(db, "TX2", 1, 10_000, freq, stationId = null, voiceprintId = null)

        val closure1 = repo.closureFor("TX1")!!
        val threadId = repo.startThread(closure1, ThreadKind.QSO, ThreadJoinReason.FIRST_TRANSMISSION_IN_SESSION)
        val closure2 = repo.closureFor("TX2")!!
        repo.appendToThread(threadId, closure2, ThreadKind.QSO, ThreadJoinReason.SAME_FREQUENCY_WITHIN_GAP)

        val order = db.catalogDao().getThread(threadId)!!.participantOrder
        assertEquals(
            "the unattributed second over must still occupy its own roster slot, in order, never be silently dropped",
            listOf("STATION-K7ABC", "UNKNOWN"),
            order,
        )
    }

    @Test
    fun `R_1118 a voice-matched, station-unresolved over is tracked by its voiceprint, not folded into UNKNOWN`() =
        runTest {
            val db = freshDb()
            val repo = RoomThreadRepository(db)
            val freq = 146_520_000L
            // A recurring, voice-matched check-in whose callsign has never resolved to a station
            // (AMBIGUOUS/UNKNOWN, R-1110's own honest default) must still read as the same
            // participant across repeats, not as two indistinguishable "UNKNOWN" entries.
            seedTransmission(db, "TX1", 0, 0, freq, stationId = null, voiceprintId = "VOICE-1")
            seedTransmission(db, "TX2", 1, 10_000, freq, stationId = null, voiceprintId = "VOICE-1")

            val closure1 = repo.closureFor("TX1")!!
            val threadId = repo.startThread(closure1, ThreadKind.QSO, ThreadJoinReason.FIRST_TRANSMISSION_IN_SESSION)
            val closure2 = repo.closureFor("TX2")!!
            repo.appendToThread(threadId, closure2, ThreadKind.QSO, ThreadJoinReason.SAME_FREQUENCY_WITHIN_GAP)

            val order = db.catalogDao().getThread(threadId)!!.participantOrder
            assertEquals(listOf("VOICE-1", "VOICE-1"), order)
        }

    @Test
    fun `R_1118 a thread starting on an unattributed over still records that first slot`() = runTest {
        val db = freshDb()
        val repo = RoomThreadRepository(db)
        seedTransmission(db, "TX1", 0, 0, 146_520_000L, stationId = null, voiceprintId = null)

        val closure = repo.closureFor("TX1")!!
        val threadId = repo.startThread(closure, ThreadKind.UNKNOWN, ThreadJoinReason.FIRST_TRANSMISSION_IN_SESSION)

        assertEquals(listOf("UNKNOWN"), db.catalogDao().getThread(threadId)!!.participantOrder)
    }
}
