package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
import org.robolectric.RobolectricTestRunner

/**
 * FR-UI-1, real `:data` read path (build-plan P14): a transmission appears newest first, a
 * transmission with no transcript row is shown honestly rather than as empty or hidden, and a
 * superseded partial stays visible rather than being silently replaced by whatever pass ran next.
 * `ReaderTransmissionViewStateMapperTest` covers the pure mapping; this covers that the real DAOs
 * feed it correctly, against a real (in-memory) Room database — no fake stands in for `:data`
 * here, since `:data`'s own P5 test suite already proves the entities/DAOs themselves.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderPollingTest {

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        // Deliberately *not* in-memory: `ReaderPolling` itself opens `OrtDatabase.create(context)`
        // with its default (file-backed) storage on every call, and a fresh `create()` call does
        // not share state with an in-memory instance held elsewhere. Using the same on-disk
        // database here is what makes this test exercise the real read path rather than a second,
        // disconnected database.
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

    /** Bundles the attribution columns tests care about, keeping [transmission]'s own parameter count down. */
    private data class FixtureAttribution(
        val state: AttributionState = AttributionState.UNKNOWN,
        val stationId: String? = null,
        val confidence: Double? = null,
    )

    private fun transmission(
        id: String,
        sessionId: String = "S1",
        samplePosition: Long,
        startedAtUtc: Long = samplePosition,
        frequencyHz: Long? = 146_960_000L,
        signalStrength: Double? = 7.0,
        attribution: FixtureAttribution = FixtureAttribution(),
    ) = TransmissionEntity(
        id = id,
        sessionId = sessionId,
        threadId = null,
        startedAtUtc = startedAtUtc,
        endedAtUtc = startedAtUtc + 1_000L,
        durationMs = 4_200L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = frequencyHz,
        frequencyProvenance = "measured",
        mode = null,
        signalStrength = signalStrength,
        channelName = null,
        voiceprintId = null,
        attributionState = attribution.state,
        stationId = attribution.stationId,
        attributionConfidence = attribution.confidence,
        attributionSourceTransmissionId = null,
        processingState = TransmissionState.CAPTURED,
        rejectionReason = null,
        samplePosition = samplePosition,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

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
    fun `FR_UI_1 transmissions come back newest first`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX-OLD", samplePosition = 1L))
        db.transmissionDao().insert(transmission("TX-NEW", samplePosition = 2L))

        val details = ReaderPolling.currentTransmissionDetails(context, "S1")

        assertEquals(listOf("TX-NEW", "TX-OLD"), details.map { it.id })
    }

    @Test
    fun `FR_UI_1 a transmission with no transcript row is honestly reported, not hidden`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", samplePosition = 1L))

        val details = ReaderPolling.currentTransmissionDetails(context, "S1")

        assertEquals(1, details.size)
        assertNull(details.single().currentTranscriptText)
    }

    @Test
    fun `FR_UI_1 a superseded transcript stays visible alongside the current one`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", samplePosition = 1L))
        db.transcriptDao().supersede(transcript("T1", "TX1", "first partial", current = true, createdAt = 1L))
        db.transcriptDao().supersede(transcript("T2", "TX1", "final transcript", current = true, createdAt = 2L))

        val detail = ReaderPolling.currentTransmissionDetails(context, "S1").single()

        assertEquals("final transcript", detail.currentTranscriptText)
        assertEquals(listOf("first partial"), detail.supersededTranscriptTexts)
    }

    @Test
    fun `a confirmed attribution round trips its station and confidence`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(
            transmission(
                "TX1",
                samplePosition = 1L,
                attribution = FixtureAttribution(AttributionState.CONFIRMED, "W7NPC", 0.95),
            ),
        )

        val detail = ReaderPolling.currentTransmissionDetails(context, "S1").single()

        assertEquals(AttributionState.CONFIRMED, detail.attribution.state)
        assertEquals("W7NPC", detail.attribution.stationId)
        assertEquals(0.95, detail.attribution.confidence)
    }

    @Test
    fun `a transmission with no retained audio file reports hasAudio false rather than crashing`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", samplePosition = 1L))

        val detail = ReaderPolling.currentTransmissionDetails(context, "S1").single()

        assertFalse(detail.hasAudio)
    }

    @Test
    fun `transmissionDetail looks up a single transmission by id`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", samplePosition = 1L))

        val detail = ReaderPolling.transmissionDetail(context, "TX1")

        assertEquals("TX1", detail?.id)
    }
}
