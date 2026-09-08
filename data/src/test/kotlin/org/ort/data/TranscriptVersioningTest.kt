package org.ort.data

import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/** technical design §8.3 — append-only transcripts, exactly one current, nothing deleted (P9). */
@RunWith(RobolectricTestRunner::class)
public class TranscriptVersioningTest {

    private lateinit var db: OrtDatabase

    @Before
    public fun openDatabase() {
        db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
    }

    private fun transcript(id: String, transmissionId: String, text: String, current: Boolean, createdAt: Long) =
        TranscriptEntity(
            id = id,
            transmissionId = transmissionId,
            pass = TranscriptPass.B,
            text = text,
            modelId = "distil-small.en",
            modelVersion = "1",
            quantization = null,
            decodeParams = null,
            noSpeechProb = null,
            confidence = 0.9,
            isCurrent = current,
            createdAt = createdAt,
        )

    @Test
    @Requirement("AC-31")
    public fun a_superseded_transcript_stays_retrievable_and_exactly_one_current_is_enforced(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX1"))
        val dao = db.transcriptDao()

        dao.supersede(transcript("T1", "TX1", "first pass", current = true, createdAt = 1L))
        assertEquals("first pass", dao.getCurrent("TX1")!!.text)

        dao.supersede(transcript("T2", "TX1", "reprocessed", current = true, createdAt = 2L))
        assertEquals("reprocessed", dao.getCurrent("TX1")!!.text)

        val all = dao.getAllVersions("TX1")
        assertEquals(2, all.size)
        assertEquals(setOf("T1", "T2"), all.map { it.id }.toSet())
        assertEquals(false, all.single { it.id == "T1" }.isCurrent) // superseded, but still there
        assertEquals(true, all.single { it.id == "T2" }.isCurrent)
    }

    @Test
    @Requirement("AC-31")
    public fun two_current_transcripts_for_one_transmission_violate_the_partial_unique_index(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX1"))
        val dao = db.transcriptDao()

        dao.insert(transcript("T1", "TX1", "a", current = true, createdAt = 1L))
        var threw = false
        try {
            // Bypasses supersede()'s clearCurrent — the index must catch it independently.
            dao.insert(transcript("T2", "TX1", "b", current = true, createdAt = 2L))
        } catch (e: SQLiteConstraintException) {
            threw = true
        }
        assertTrue("a second isCurrent=1 row for the same transmission must violate idx_transcript_one_current", threw)
    }

    /**
     * FR-ASR-7 — the model identity, version, quantization and decode parameters that produced a
     * transcript are stored alongside it, not just its text, so a result can be traced back to
     * exactly what produced it (constitution VI: "no number without its provenance"). Every field
     * here is distinct from every other transcript fixture in this file to rule out an accidental
     * pass from a hardcoded default.
     */
    @Test
    @Requirement("FR-ASR-7")
    public fun FR_ASR_7_model_identity_version_quantization_and_decode_params_survive_alongside_the_transcript(): Unit =
        runTest {
            db.sessionDao().insert(TestFixtures.session())
            db.transmissionDao().insert(TestFixtures.transmission("TX1"))
            val dao = db.transcriptDao()

            dao.supersede(
                TranscriptEntity(
                    id = "T1",
                    transmissionId = "TX1",
                    pass = TranscriptPass.B,
                    text = "whiskey seven november",
                    modelId = "distil-small.en-int8",
                    modelVersion = "2024.11.3",
                    quantization = "int8",
                    decodeParams = """{"beam_size":5,"temperature":0.0}""",
                    noSpeechProb = 0.02f,
                    confidence = 0.87,
                    isCurrent = true,
                    createdAt = 1L,
                ),
            )

            val stored = dao.getCurrent("TX1")!!
            assertEquals("distil-small.en-int8", stored.modelId)
            assertEquals("2024.11.3", stored.modelVersion)
            assertEquals("int8", stored.quantization)
            assertEquals("""{"beam_size":5,"temperature":0.0}""", stored.decodeParams)

            // And it survives retrieval through getAllVersions too, not just getCurrent.
            val versioned = dao.getAllVersions("TX1").single { it.id == "T1" }
            assertEquals("distil-small.en-int8", versioned.modelId)
            assertEquals("2024.11.3", versioned.modelVersion)
            assertEquals("int8", versioned.quantization)
            assertEquals("""{"beam_size":5,"temperature":0.0}""", versioned.decodeParams)
        }
}
