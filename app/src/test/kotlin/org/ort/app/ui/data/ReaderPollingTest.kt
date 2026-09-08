package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.VadAvailability
import org.ort.testing.Requirement
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

    @After
    fun resetProcessWideAvailability() {
        // AsrAvailability/VadAvailability/CaptureState/ShedStatus are all process-wide holders
        // (not persisted, by design — see their own doc comments); reset so this test's fixtures
        // never leak into another test.
        AsrAvailability.reset()
        VadAvailability.reset()
        CaptureState.idle()
        ShedStatus.reset()
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 currentStatus reads the real ASR and VAD availability, not a healthy default`(): Unit = runTest {
        db.sessionDao().insert(session())
        AsrAvailability.unavailable("no model at /data/models/asr")
        VadAvailability.stub("Silero model not installed")

        val view = ReaderPolling.currentStatus(context, "S1", startedAtWallMillis = 0L)

        assertTrue(view.asrStatusLabel.contains("no model at /data/models/asr"))
        assertTrue(view.vadStatusLabel.contains("Silero model not installed"))
        assertEquals(
            "No transcription model installed — transcripts will not appear",
            view.transcriptionUnavailableMessage,
        )
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 currentStatus reports not started when the service has not set availability yet`(): Unit = runTest {
        db.sessionDao().insert(session())

        val view = ReaderPolling.currentStatus(context, "S1", startedAtWallMillis = 0L)

        assertTrue(view.asrStatusLabel.contains("not started"))
        assertFalse(view.asrStatusLabel.contains("available ("))
    }

    @Test
    @Requirement("FR-RUN-5")
    fun `FR_RUN_5 currentStatus reads the real shed level and backlog once capture has started`(): Unit = runTest {
        db.sessionDao().insert(session())
        CaptureState.capturing("S1")
        ShedStatus.update(level = 2, backlog = 7)

        val view = ReaderPolling.currentStatus(context, "S1", startedAtWallMillis = 0L)

        assertEquals(2, view.shedLevel)
        assertEquals("Level 2 — speaker identity paused", view.shedLevelLabel)
        assertEquals(7, view.backlog)
        assertTrue(view.backlogLabel.contains("7"))
    }

    @Test
    @Requirement("FR-RUN-5")
    fun `FR_RUN_5 currentStatus reports not measured before capture has ever started, never a fabricated zero`(): Unit =
        runTest {
            db.sessionDao().insert(session())

            val view = ReaderPolling.currentStatus(context, "S1", startedAtWallMillis = 0L)

            assertNull(view.shedLevel)
            assertEquals("Not measured", view.shedLevelLabel)
            assertNull(view.backlog)
            assertEquals("Not measured", view.backlogLabel)
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

    private fun hourTx(
        id: String,
        sessionId: String,
        hour: Int,
        samplePosition: Long,
        stationId: String? = null,
        frequencyHz: Long? = 146_960_000L,
    ) = transmission(
        id,
        sessionId = sessionId,
        samplePosition = samplePosition,
        startedAtUtc = hour * 3_600_000L,
        frequencyHz = frequencyHz,
        attribution = FixtureAttribution(
            state = if (stationId != null) AttributionState.CONFIRMED else AttributionState.UNKNOWN,
            stationId = stationId,
            confidence = if (stationId != null) 0.9 else null,
        ),
    )

    @Test
    fun `FR_UI_9 stationDetail returns every transmission heard from that station, across sessions`(): Unit = runTest {
        db.sessionDao().insert(session("S1"))
        db.sessionDao().insert(
            SessionEntity("S2", 0L, 3_600_000L * 24, null, null, "test", null, null, OrtDatabase.SCHEMA_VERSION),
        )
        db.transmissionDao().insert(hourTx("TX1", "S1", hour = 2, samplePosition = 1L, stationId = "W7NPC"))
        db.transmissionDao().insert(hourTx("TX2", "S2", hour = 2, samplePosition = 2L, stationId = "W7NPC"))
        db.transmissionDao().insert(hourTx("TX3", "S1", hour = 3, samplePosition = 3L, stationId = "K7LWH"))

        val detail = ReaderPolling.stationDetail(context, "W7NPC", nowMillis = 3_600_000L * 25)

        assertEquals(listOf("TX1", "TX2"), detail.transmissions.map { it.id })
        assertEquals(2, detail.transmissionCount)
    }

    @Test
    fun `FR_UI_10 frequencyDetail returns every transmission heard on that frequency`(): Unit = runTest {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(hourTx("TX1", "S1", hour = 2, samplePosition = 1L, frequencyHz = 146_960_000L))
        db.transmissionDao().insert(hourTx("TX2", "S1", hour = 3, samplePosition = 2L, frequencyHz = 146_520_000L))

        val detail = ReaderPolling.frequencyDetail(context, 146_960_000L, nowMillis = 3_600_000L * 4)

        assertEquals(listOf("TX1"), detail.transmissions.map { it.id })
    }

    @Test
    fun `FR_UI_12 stationDetail reads a capture gap as not-listening, never as silence`(): Unit = runTest {
        db.sessionDao().insert(
            SessionEntity("S1", 0L, 3_600_000L * 5, null, null, "test", null, null, OrtDatabase.SCHEMA_VERSION),
        )
        // A gap covering hour-of-day 1 entirely — never heard from this station in that hour
        // because the app was not listening, not because the station was quiet.
        db.captureGapDao().insert(
            CaptureGapEntity(
                id = "G1",
                sessionId = "S1",
                startedAt = 3_600_000L,
                endedAt = 3_600_000L * 2,
                cause = CaptureGapCause.INTERRUPTION,
                recoveredAutomatically = true,
            ),
        )
        db.transmissionDao().insert(hourTx("TX1", "S1", hour = 0, samplePosition = 1L, stationId = "W7NPC"))

        val detail = ReaderPolling.stationDetail(context, "W7NPC", nowMillis = 3_600_000L * 5)

        val hour0 = detail.activityPattern.single { it.hourOfDayUtc == 0 }
        val hour1 = detail.activityPattern.single { it.hourOfDayUtc == 1 }
        val hour2 = detail.activityPattern.single { it.hourOfDayUtc == 2 }
        assertEquals(HourActivityState.HEARD, hour0.state)
        assertEquals(HourActivityState.NOT_LISTENING, hour1.state)
        assertEquals(HourActivityState.SILENT_WHILE_LISTENING, hour2.state)
    }

    @Test
    fun `listStationSummaries and listFrequencySummaries surface everything ever recorded`(): Unit = runTest {
        db.catalogDao().insert(
            StationEntity(
                id = "W7NPC",
                callsign = "W7NPC",
                firstHeardAt = 0L,
                lastHeardAt = 0L,
                transmissionCount = 1,
                isUserPinned = false,
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
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(hourTx("TX1", "S1", hour = 1, samplePosition = 1L, frequencyHz = 146_960_000L))

        val stations = ReaderPolling.listStationSummaries(context)
        val frequencies = ReaderPolling.listFrequencySummaries(context)

        assertEquals(listOf("W7NPC"), stations.map { it.stationId })
        assertEquals(listOf(146_960_000L), frequencies.map { it.frequencyHz })
    }
}
