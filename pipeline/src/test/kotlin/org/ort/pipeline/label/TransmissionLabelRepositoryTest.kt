package org.ort.pipeline.label

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.LabelCertainty
import org.ort.data.entity.LabelOutcome
import org.ort.pipeline.PipelineTestFixtures
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import org.robolectric.RobolectricTestRunner

/**
 * FR-OBS-4, Q16, constitution VI. AGENTS.md's non-negotiable: "a label is operator ground truth,
 * never an attribution ... it must never promote anything to CONFIRMED".
 */
@RunWith(RobolectricTestRunner::class)
class TransmissionLabelRepositoryTest {

    @Test
    @Requirement("FR-OBS-4")
    fun `label writes every field with the real wall-clock moment as provenance`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))
        db.transmissionDao().insert(PipelineTestFixtures.transmission("TX1", sessionId = "S1"))
        val clock = TestClock(startWallMillis = 3_000L)

        TransmissionLabelRepository.label(
            db,
            "TX1",
            TransmissionLabelFields(
                markedForTraining = true,
                outcome = LabelOutcome.SPEECH,
                truthCallsign = "K7ABC",
                callsignCertainty = LabelCertainty.CERTAIN,
                rating = "good",
            ),
            clock = clock,
        )

        val label = TransmissionLabelRepository.get(db, "TX1")!!
        assertEquals(true, label.markedForTraining)
        assertEquals("K7ABC", label.truthCallsign)
        assertEquals("good", label.rating)
        assertEquals(3_000L, label.labelledAtMillis)
    }

    @Test
    @Requirement("FR-OBS-4")
    fun `truthCallsign without callsignCertainty is refused per the protocol`() {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        runBlocking {
            db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))
            db.transmissionDao().insert(PipelineTestFixtures.transmission("TX1", sessionId = "S1"))
        }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                TransmissionLabelRepository.label(
                    db,
                    "TX1",
                    TransmissionLabelFields(markedForTraining = false, truthCallsign = "K7ABC"),
                )
            }
        }
    }

    @Test
    @Requirement("FR-OBS-4")
    fun `a negative example -- no callsign heard -- needs no certainty`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))
        db.transmissionDao().insert(PipelineTestFixtures.transmission("TX1", sessionId = "S1"))

        TransmissionLabelRepository.label(
            db,
            "TX1",
            TransmissionLabelFields(markedForTraining = false, outcome = LabelOutcome.NON_SPEECH, truthCallsign = null),
        )

        val label = TransmissionLabelRepository.get(db, "TX1")!!
        assertNull(label.truthCallsign)
        assertNull(label.callsignCertainty)
    }

    @Test
    @Requirement("FR-OBS-4")
    fun `setMarkedForTraining flips only the mark and preserves an already-recorded label`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))
        db.transmissionDao().insert(PipelineTestFixtures.transmission("TX1", sessionId = "S1"))
        TransmissionLabelRepository.label(
            db,
            "TX1",
            TransmissionLabelFields(
                markedForTraining = false,
                truthCallsign = "K7ABC",
                callsignCertainty = LabelCertainty.CERTAIN,
                rating = "good",
            ),
        )

        TransmissionLabelRepository.setMarkedForTraining(db, "TX1", markedForTraining = true)

        val label = TransmissionLabelRepository.get(db, "TX1")!!
        assertEquals(true, label.markedForTraining)
        assertEquals("K7ABC", label.truthCallsign) // untouched
        assertEquals("good", label.rating) // untouched
    }

    /**
     * The discriminating test for AGENTS.md's non-negotiable rule: labelling a transmission —
     * including with a ground-truth callsign and a training mark — must never change its
     * attribution, and must never promote it to CONFIRMED.
     */
    @Test
    @Requirement("FR-OBS-4")
    fun `labelling a transmission never changes its attribution state or promotes it to CONFIRMED`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))
        db.transmissionDao().insert(
            PipelineTestFixtures.transmission("TX1", sessionId = "S1")
                .copy(attributionState = AttributionState.UNKNOWN, stationId = null),
        )

        TransmissionLabelRepository.label(
            db,
            "TX1",
            TransmissionLabelFields(
                markedForTraining = true,
                truthCallsign = "K7ABC",
                callsignCertainty = LabelCertainty.CERTAIN,
                rating = "good",
            ),
        )

        val transmission = db.transmissionDao().getById("TX1")!!
        assertEquals(
            "a label must never promote an attribution, CONFIRMED included",
            AttributionState.UNKNOWN,
            transmission.attributionState,
        )
        assertNull("a label must never write a stationId onto the transmission itself", transmission.stationId)
    }
}
