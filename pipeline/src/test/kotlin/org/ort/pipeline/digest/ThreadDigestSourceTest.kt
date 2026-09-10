package org.ort.pipeline.digest

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.pipeline.PipelineTestFixtures
import org.robolectric.RobolectricTestRunner

/**
 * FR-DIG-3, AC-87: [ThreadDigestSource.pendingThreads] over a real (in-memory) `OrtDatabase` —
 * ended sessions only, already-summarized threads excluded, callsigns resolved only from
 * `CONFIRMED`/`INFERRED` attributions.
 */
@RunWith(RobolectricTestRunner::class)
class ThreadDigestSourceTest {

    private suspend fun station(db: OrtDatabase, id: String, callsign: String) {
        db.catalogDao().insert(
            StationEntity(
                id = id,
                callsign = callsign,
                firstHeardAt = 0L,
                lastHeardAt = 0L,
                notes = null,
                userName = null,
                frequenciesHeard = null,
                activityByHourDow = null,
                potaRefs = null,
                spokenGrids = null,
                ituRegionFromPrefix = null,
                overCountsByAttributionState = null,
            ),
        )
    }

    private suspend fun transcript(db: OrtDatabase, transmissionId: String, text: String) {
        db.transcriptDao().insert(
            TranscriptEntity(
                id = "$transmissionId-t1",
                transmissionId = transmissionId,
                pass = TranscriptPass.B,
                text = text,
                modelId = "m",
                modelVersion = "1",
                quantization = null,
                decodeParams = null,
                noSpeechProb = null,
                confidence = null,
                isCurrent = true,
                createdAt = 0L,
            ),
        )
    }

    @Test
    fun `only threads from ended sessions are pending`() = runTest {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session("live").copy(endedAt = null))
        db.sessionDao().insert(PipelineTestFixtures.session("ended").copy(endedAt = 1_000L))
        station(db, "W1AW", "W1AW")

        db.transmissionDao().insert(
            PipelineTestFixtures.transmission("tx-live", "live").copy(
                threadId = "thread-live",
                stationId = "W1AW",
                attributionState = AttributionState.CONFIRMED,
            ),
        )
        db.transmissionDao().insert(
            PipelineTestFixtures.transmission("tx-ended", "ended").copy(
                threadId = "thread-ended",
                stationId = "W1AW",
                attributionState = AttributionState.CONFIRMED,
            ),
        )
        transcript(db, "tx-live", "from a live session")
        transcript(db, "tx-ended", "from an ended session")

        val pending = ThreadDigestSource(db, InMemoryProseSummaryStore()).pendingThreads()

        assertEquals(listOf("thread-ended"), pending.map { it.threadId })
    }

    @Test
    fun `a thread already summarized is excluded`() = runTest {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session("s1").copy(endedAt = 1_000L))
        station(db, "W1AW", "W1AW")
        db.transmissionDao().insert(
            PipelineTestFixtures.transmission("tx1", "s1").copy(threadId = "thread-1", stationId = "W1AW"),
        )
        transcript(db, "tx1", "already summarized")
        val store = InMemoryProseSummaryStore()
        store.store(
            ProseSummary(
                threadId = "thread-1",
                text = "existing",
                sourceTransmissionIds = listOf("tx1"),
                generatedAtMillis = 0L,
                modelId = "m",
            ),
        )

        val pending = ThreadDigestSource(db, store).pendingThreads()

        assertTrue(pending.isEmpty())
    }

    @Test
    fun `resolved callsigns include only CONFIRMED and INFERRED attributions, never AMBIGUOUS or UNKNOWN`() = runTest {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session("s1").copy(endedAt = 1_000L))
        station(db, "W1AW", "W1AW")
        station(db, "K9ZZZ", "K9ZZZ")
        db.transmissionDao().insert(
            PipelineTestFixtures.transmission("tx1", "s1").copy(
                threadId = "thread-1",
                stationId = "W1AW",
                attributionState = AttributionState.CONFIRMED,
            ),
        )
        db.transmissionDao().insert(
            PipelineTestFixtures.transmission("tx2", "s1").copy(
                threadId = "thread-1",
                stationId = "K9ZZZ",
                attributionState = AttributionState.AMBIGUOUS,
            ),
        )
        transcript(db, "tx1", "confirmed speaker")
        transcript(db, "tx2", "ambiguous speaker")

        val pending = ThreadDigestSource(db, InMemoryProseSummaryStore()).pendingThreads()

        val thread = pending.single()
        assertEquals(setOf("W1AW"), thread.input.resolvedCallsigns)
        assertEquals(listOf("tx1", "tx2"), thread.sourceTransmissionIds)
        assertEquals(2, thread.input.transcripts.size)
    }
}
