package org.ort.data.dao

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.data.OrtDatabase
import org.ort.data.TestFixtures
import org.ort.data.entity.LabelCertainty
import org.ort.data.entity.LabelOutcome
import org.ort.data.entity.TransmissionLabelEntity
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/** FR-OBS-4: one transmission's operator-recorded label, and the count RC01's "Labelled" chip
 * reads. */
@RunWith(RobolectricTestRunner::class)
public class TransmissionLabelDaoTest {

    private lateinit var db: OrtDatabase

    @Before
    public fun openDatabase() {
        db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
    }

    @Test
    @Requirement("FR-OBS-4")
    public fun a_transmission_with_no_label_reads_null_never_fabricated(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        db.transmissionDao().insert(TestFixtures.transmission("TX1", sessionId = "S1"))

        assertNull(db.transmissionLabelDao().getByTransmissionId("TX1"))
    }

    @Test
    @Requirement("FR-OBS-4")
    public fun upsert_writes_every_field_and_is_readable_back_by_transmissionId(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        db.transmissionDao().insert(TestFixtures.transmission("TX1", sessionId = "S1"))

        db.transmissionLabelDao().upsert(
            TransmissionLabelEntity(
                transmissionId = "TX1",
                markedForTraining = true,
                outcome = LabelOutcome.SPEECH,
                doubled = false,
                truthCallsign = "K7ABC",
                callsignCertainty = LabelCertainty.CERTAIN,
                tacticalCallsign = null,
                note = "clear copy",
                rating = "good",
                labelledAtMillis = 5_000L,
            ),
        )

        val label = db.transmissionLabelDao().getByTransmissionId("TX1")
        assertEquals(true, label?.markedForTraining)
        assertEquals(LabelOutcome.SPEECH, label?.outcome)
        assertEquals("K7ABC", label?.truthCallsign)
        assertEquals(LabelCertainty.CERTAIN, label?.callsignCertainty)
        assertEquals("good", label?.rating)
        assertEquals(5_000L, label?.labelledAtMillis)
    }

    @Test
    @Requirement("FR-OBS-4")
    public fun a_second_upsert_replaces_the_first_rather_than_keeping_a_history(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        db.transmissionDao().insert(TestFixtures.transmission("TX1", sessionId = "S1"))
        db.transmissionLabelDao().upsert(
            TransmissionLabelEntity(transmissionId = "TX1", markedForTraining = false, labelledAtMillis = 1_000L),
        )

        db.transmissionLabelDao().upsert(
            TransmissionLabelEntity(
                transmissionId = "TX1",
                markedForTraining = true,
                rating = "good",
                labelledAtMillis = 2_000L,
            ),
        )

        val label = db.transmissionLabelDao().getByTransmissionId("TX1")
        assertEquals(true, label?.markedForTraining)
        assertEquals("good", label?.rating)
        assertEquals(2_000L, label?.labelledAtMillis)
    }

    @Test
    @Requirement("FR-OBS-4")
    public fun countMarkedForTrainingBySession_counts_only_this_sessions_marked_overs(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        db.sessionDao().insert(TestFixtures.session("S2"))
        db.transmissionDao().insert(TestFixtures.transmission("TX1", sessionId = "S1"))
        db.transmissionDao().insert(TestFixtures.transmission("TX2", sessionId = "S1"))
        db.transmissionDao().insert(TestFixtures.transmission("TX3", sessionId = "S2"))
        db.transmissionLabelDao().upsert(
            TransmissionLabelEntity(transmissionId = "TX1", markedForTraining = true, labelledAtMillis = 1_000L),
        )
        db.transmissionLabelDao().upsert(
            TransmissionLabelEntity(transmissionId = "TX2", markedForTraining = false, labelledAtMillis = 1_000L),
        )
        db.transmissionLabelDao().upsert(
            TransmissionLabelEntity(transmissionId = "TX3", markedForTraining = true, labelledAtMillis = 1_000L),
        )

        assertEquals(1, db.transmissionLabelDao().countMarkedForTrainingBySession("S1"))
        assertEquals(1, db.transmissionLabelDao().countMarkedForTrainingBySession("S2"))
    }
}
