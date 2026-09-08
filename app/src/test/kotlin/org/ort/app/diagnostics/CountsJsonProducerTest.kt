package org.ort.app.diagnostics

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * WP11e (register R-137, `Settings-Diagnostics.dc.html`'s `counts.json`, AC-120): "overs by state,
 * rejections by reason … numbers only" — every value here is an `Int`, and no callsign or
 * transcript is ever read to produce one (this producer never queries a callsign, a transcript, a
 * station or a voiceprint table at all — see [DiagnosticsBundleBuilderTest] for the seeded-data
 * proof at the bundle level).
 */
@RunWith(RobolectricTestRunner::class)
class CountsJsonProducerTest {

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    private fun session(id: String) = SessionEntity(
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

    private fun transmission(
        id: String,
        sessionId: String,
        state: AttributionState,
        processingState: TransmissionState = TransmissionState.COMPLETE,
        rejectionReason: String? = null,
        corrected: Boolean = false,
    ) = TransmissionEntity(
        id = id,
        sessionId = sessionId,
        threadId = null,
        startedAtUtc = 0L,
        endedAtUtc = 1_000L,
        durationMs = 1_000L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = null,
        frequencyProvenance = "measured",
        mode = null,
        signalStrength = null,
        channelName = null,
        voiceprintId = null,
        attributionState = state,
        stationId = null,
        attributionConfidence = null,
        attributionSourceTransmissionId = null,
        corrected = corrected,
        processingState = processingState,
        rejectionReason = rejectionReason,
        samplePosition = 0L,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    @Test
    fun `FR_OBS_1 counts json totals sessions, overs, attribution states, rejections and corrections`() = runTest {
        db.sessionDao().insert(session("S1"))
        db.sessionDao().insert(session("S2"))
        db.transmissionDao().insert(transmission("TX1", "S1", AttributionState.CONFIRMED))
        db.transmissionDao().insert(transmission("TX2", "S1", AttributionState.UNKNOWN))
        db.transmissionDao().insert(
            transmission(
                "TX3",
                "S1",
                AttributionState.UNKNOWN,
                processingState = TransmissionState.REJECTED,
                rejectionReason = "VAD_NO_SPEECH: squelch tail, 0.4 s",
            ),
        )
        db.transmissionDao().insert(
            transmission(
                "TX4",
                "S2",
                AttributionState.CONFIRMED,
                corrected = true,
            ),
        )

        val bytes = CountsJsonProducer.produce(context)
        val json = JSONObject(String(bytes, Charsets.UTF_8))

        assertEquals(2, json.getInt("sessionsCount"))
        assertEquals(4, json.getInt("oversCount"))
        assertEquals(2, json.getJSONObject("attributionStateCounts").getInt("CONFIRMED"))
        assertEquals(2, json.getJSONObject("attributionStateCounts").getInt("UNKNOWN"))
        assertEquals(0, json.getJSONObject("attributionStateCounts").getInt("AMBIGUOUS"))
        assertEquals(1, json.getJSONObject("rejectionReasonCounts").getInt("VAD_NO_SPEECH"))
        assertEquals(1, json.getInt("correctedOversCount"))
    }

    @Test
    fun `AC_120 counts json contains no station or callsign strings, numbers only where it matters`() = runTest {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(transmission("TX1", "S1", AttributionState.CONFIRMED))

        val bytes = CountsJsonProducer.produce(context)
        val text = String(bytes, Charsets.UTF_8)

        assertFalse(text.contains("K7ABC"))
        assertFalse(text.lowercase().contains("callsign"))
        assertFalse(text.lowercase().contains("station"))
    }

    @Test
    fun `FR_OBS_1 an empty database reports honest zero counts, never omitted keys`() = runTest {
        val bytes = CountsJsonProducer.produce(context)
        val json = JSONObject(String(bytes, Charsets.UTF_8))

        assertEquals(0, json.getInt("sessionsCount"))
        assertEquals(0, json.getInt("oversCount"))
        assertEquals(0, json.getInt("correctedOversCount"))
    }
}
