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

    /**
     * D45: `CatalogDao.allVoiceprints()` closes the gap this test used to document (register
     * R-1010) — a voiceprint never bound to a station is no longer silently dropped from the
     * upload, so it must appear here just like a bound one.
     */
    @Test
    fun `D45 a voiceprint never bound to any station is now reachable, not silently dropped`() = runTest {
        val embedding = byteArrayOf(9)
        db.catalogDao().insert(voiceprint("V2", embedding, boundStationId = null))

        val bytes = VoiceprintEmbeddingsProducer.produce(context)
        val array = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("voiceprints")
        assertEquals(1, array.length())
        val entry = array.getJSONObject(0)
        assertEquals("V2", entry.getString("id"))
        assertTrue(Base64.getDecoder().decode(entry.getString("embeddingBase64")).contentEquals(embedding))
    }

    @Test
    fun `no stations at all produces an empty, well-formed array`() = runTest {
        val bytes = VoiceprintEmbeddingsProducer.produce(context)
        val array = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("voiceprints")
        assertEquals(0, array.length())
    }

    // -----------------------------------------------------------------------------------------
    // WPDUMP: `count` backs the local-save checklist's own availability check for this category —
    // must agree with `produce`'s own array length in every one of the same fixtures above.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `WPDUMP count is zero with no stations at all`() = runTest {
        assertEquals(0, VoiceprintEmbeddingsProducer.count(context))
    }

    /** D45: the unbound voiceprint is now counted too — see the `produce`-side test above. */
    @Test
    fun `D45 WPDUMP count is one when the only voiceprint is never bound to a station`() = runTest {
        db.catalogDao().insert(voiceprint("V2", byteArrayOf(9), boundStationId = null))
        assertEquals(1, VoiceprintEmbeddingsProducer.count(context))
    }

    @Test
    fun `WPDUMP count is one with one station-bound voiceprint`() = runTest {
        db.catalogDao().insert(station("ST1", "K7ABC"))
        db.catalogDao().insert(voiceprint("V1", byteArrayOf(1, 2, 3), boundStationId = "ST1"))
        assertEquals(1, VoiceprintEmbeddingsProducer.count(context))
    }
}
