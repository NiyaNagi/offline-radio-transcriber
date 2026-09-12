package org.ort.app.fieldreport.bundle

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.data.OrtDatabase
import org.ort.data.entity.StationEntity
import org.ort.data.entity.VoiceprintEntity
import org.robolectric.RobolectricTestRunner
import java.util.Base64

/**
 * D38/FR-SPK-20 (amended): [VoiceprintEmbeddingsProducer] is the one field-report producer allowed
 * to read [VoiceprintEntity.embedding] at all, and it must round-trip the real bytes (base64), not
 * a placeholder or a hash.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceprintEmbeddingsProducerTest {

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    private fun voiceprint(id: String, embedding: ByteArray, boundStationId: String?) = VoiceprintEntity(
        id = id,
        embedding = embedding,
        memberCount = 3,
        centroidUpdatedAt = null,
        boundStationId = boundStationId,
        bindingConfidence = null,
        lastConfirmedAt = null,
        isEnrolled = true,
        enrolmentObservationCount = 3,
        enrolmentSessionIds = null,
        enrolledAt = null,
        lastMatchedAt = null,
        bindingSource = null,
        embeddingModelId = "test-embedder",
        embeddingModelVersion = "1",
    )

    private fun station(id: String, callsign: String) = StationEntity(
        id = id,
        callsign = callsign,
        firstHeardAt = null,
        lastHeardAt = 0L,
        transmissionCount = 0,
        isUserPinned = false,
        notes = null,
        userName = null,
        frequenciesHeard = null,
        activityByHourDow = null,
        potaRefs = null,
        spokenGrids = null,
        ituRegionFromPrefix = null,
        overCountsByAttributionState = null,
    )

    @Test
    fun `a station-bound voiceprint's real embedding bytes round-trip through base64`() = runTest {
        val embedding = byteArrayOf(1, 2, 3, 4, 5, -1, -2)
        db.catalogDao().insert(station("ST1", "K7ABC"))
        db.catalogDao().insert(voiceprint("V1", embedding, boundStationId = "ST1"))

        val bytes = VoiceprintEmbeddingsProducer.produce(context)
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val array = json.getJSONArray("voiceprints")
        assertEquals(1, array.length())
        val entry = array.getJSONObject(0)
        assertEquals("V1", entry.getString("id"))
        assertEquals(3, entry.getInt("memberCount"))
        assertTrue(Base64.getDecoder().decode(entry.getString("embeddingBase64")).contentEquals(embedding))
    }

    @Test
    fun `a voiceprint never bound to any station is honestly absent, not fabricated`() = runTest {
        db.catalogDao().insert(voiceprint("V2", byteArrayOf(9), boundStationId = null))

        val bytes = VoiceprintEmbeddingsProducer.produce(context)
        val array = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("voiceprints")
        assertEquals(0, array.length())
    }

    @Test
    fun `no stations at all produces an empty, well-formed array`() = runTest {
        val bytes = VoiceprintEmbeddingsProducer.produce(context)
        val array = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("voiceprints")
        assertEquals(0, array.length())
    }
}
