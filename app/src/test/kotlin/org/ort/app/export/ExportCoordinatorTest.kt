package org.ort.app.export

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
import org.robolectric.RobolectricTestRunner

/**
 * Register R-1009 (WPX). [ExportCoordinator] is the `:app`-side half of the export feature — it
 * reads the real `:data` entities and hands `:pipeline`'s writers the format-agnostic
 * [org.ort.pipeline.export.ExportOverRecord] rows they need, scoped by [ExportRequestScope]
 * exactly the way `SettingsPolling.export`'s own `tonightOverCount`/`allOverCount` already define
 * "tonight" (the most recent session) and "everything" (every session) — read across, never
 * redefined a second, differently-scoped way.
 */
@RunWith(RobolectricTestRunner::class)
class ExportCoordinatorTest {

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    private fun session(id: String, startedAt: Long) = SessionEntity(
        id = id,
        startedAt = startedAt,
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
        attributionState: AttributionState,
        stationId: String? = null,
        attributionConfidence: Double? = null,
        frequencyHz: Long? = 146_520_000L,
    ) = TransmissionEntity(
        id = id,
        sessionId = sessionId,
        threadId = null,
        startedAtUtc = 1_757_477_520_000L,
        endedAtUtc = 1_757_477_525_000L,
        durationMs = 5_000L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = frequencyHz,
        frequencyProvenance = "measured",
        mode = "FM",
        signalStrength = null,
        channelName = "Repeater 1",
        voiceprintId = null,
        attributionState = attributionState,
        stationId = stationId,
        attributionConfidence = attributionConfidence,
        attributionSourceTransmissionId = null,
        processingState = TransmissionState.COMPLETE,
        rejectionReason = null,
        samplePosition = 0L,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    private fun station(id: String, callsign: String, potaRefs: List<String>? = null) = StationEntity(
        id = id,
        callsign = callsign,
        firstHeardAt = null,
        lastHeardAt = null,
        transmissionCount = 0,
        notes = null,
        userName = null,
        frequenciesHeard = null,
        activityByHourDow = null,
        potaRefs = potaRefs,
        spokenGrids = null,
        ituRegionFromPrefix = null,
        overCountsByAttributionState = null,
    )

    @Test
    fun `R_1009 tonight scopes to only the most recent session, same definition SettingsPolling_export uses`() =
        runTest {
            db.sessionDao().insert(session("S1", startedAt = 0L))
            db.sessionDao().insert(session("S2", startedAt = 1_000L))
            db.transmissionDao().insert(transmission("T1", "S1", AttributionState.UNKNOWN))
            db.transmissionDao().insert(transmission("T2", "S2", AttributionState.UNKNOWN))

            val bytes = ExportCoordinator.build(
                context,
                ExportRequest(scope = ExportRequestScope.TONIGHT, format = ExportFileFormat.CSV),
            )
            val csv = bytes.toString(Charsets.UTF_8)
            assertTrue(csv.contains("T2"))
            assertFalse("expected only S2's (the most recent session's) over, got:\n$csv", csv.contains("T1"))
        }

    @Test
    fun `R_1009 everything scopes to every session`() = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L))
        db.sessionDao().insert(session("S2", startedAt = 1_000L))
        db.transmissionDao().insert(transmission("T1", "S1", AttributionState.UNKNOWN))
        db.transmissionDao().insert(transmission("T2", "S2", AttributionState.UNKNOWN))

        val bytes = ExportCoordinator.build(
            context,
            ExportRequest(scope = ExportRequestScope.EVERYTHING, format = ExportFileFormat.CSV),
        )
        val csv = bytes.toString(Charsets.UTF_8)
        assertTrue(csv.contains("T1"))
        assertTrue(csv.contains("T2"))
    }

    @Test
    fun `R_1009 a confirmed over's real callsign, confidence and transcript reach the CSV`() = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L))
        db.catalogDao().insert(station("ST1", "KI7ABC"))
        db.transmissionDao().insert(
            transmission("T1", "S1", AttributionState.CONFIRMED, stationId = "ST1", attributionConfidence = 0.94),
        )
        db.transcriptDao().insert(
            TranscriptEntity(
                id = "TR1",
                transmissionId = "T1",
                pass = TranscriptPass.A,
                text = "this is KI7ABC",
                modelId = "whisper-small",
                modelVersion = "1.2.0",
                quantization = null,
                decodeParams = null,
                noSpeechProb = null,
                confidence = null,
                isCurrent = true,
                createdAt = 0L,
            ),
        )

        val bytes = ExportCoordinator.build(
            context,
            ExportRequest(scope = ExportRequestScope.EVERYTHING, format = ExportFileFormat.CSV),
        )
        val csv = bytes.toString(Charsets.UTF_8)
        assertTrue(csv.contains("CONFIRMED"))
        assertTrue(csv.contains("KI7ABC"))
        assertTrue(csv.contains("0.9400"))
        assertTrue(csv.contains("whisper-small"))
        assertTrue(csv.contains("this is KI7ABC"))
    }

    @Test
    fun `FR_EXP_4 an unknown over never carries a fabricated callsign through the coordinator`() = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L))
        db.transmissionDao().insert(transmission("T1", "S1", AttributionState.UNKNOWN))

        val bytes = ExportCoordinator.build(
            context,
            ExportRequest(scope = ExportRequestScope.EVERYTHING, format = ExportFileFormat.CSV),
        )
        val csv = bytes.toString(Charsets.UTF_8)
        assertTrue(csv.contains("UNKNOWN"))
        assertTrue(csv.contains("UNIDENTIFIED"))
    }

    @Test
    fun `FR_EXP_5 confirmedOnly true excludes every non-confirmed record`() = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L))
        db.catalogDao().insert(station("ST1", "KI7ABC"))
        db.transmissionDao().insert(
            transmission("T1", "S1", AttributionState.CONFIRMED, stationId = "ST1", attributionConfidence = 0.94),
        )
        db.transmissionDao().insert(transmission("T2", "S1", AttributionState.UNKNOWN))

        val bytes = ExportCoordinator.build(
            context,
            ExportRequest(scope = ExportRequestScope.EVERYTHING, format = ExportFileFormat.CSV, confirmedOnly = true),
        )
        val csv = bytes.toString(Charsets.UTF_8)
        assertTrue(csv.contains("T1"))
        assertFalse(csv.contains("T2"))
    }

    @Test
    fun `R_1009 includeTranscripts false omits transcript text and its model provenance`() = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L))
        db.catalogDao().insert(station("ST1", "KI7ABC"))
        db.transmissionDao().insert(
            transmission("T1", "S1", AttributionState.CONFIRMED, stationId = "ST1", attributionConfidence = 0.94),
        )
        db.transcriptDao().insert(
            TranscriptEntity(
                id = "TR1",
                transmissionId = "T1",
                pass = TranscriptPass.A,
                text = "SECRET-TRANSCRIPT-MARKER",
                modelId = "whisper-small",
                modelVersion = "1.2.0",
                quantization = null,
                decodeParams = null,
                noSpeechProb = null,
                confidence = null,
                isCurrent = true,
                createdAt = 0L,
            ),
        )

        val bytes = ExportCoordinator.build(
            context,
            ExportRequest(
                scope = ExportRequestScope.EVERYTHING,
                format = ExportFileFormat.CSV,
                includeTranscripts = false,
            ),
        )
        val csv = bytes.toString(Charsets.UTF_8)
        assertTrue("the log itself must still be present", csv.contains("KI7ABC"))
        assertFalse("transcript text must be omitted", csv.contains("SECRET-TRANSCRIPT-MARKER"))
        assertFalse("transcript model provenance must be omitted alongside it", csv.contains("whisper-small"))
    }

    @Test
    fun `R_1009 each format produces different, real bytes for the same scope`() = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L))
        db.transmissionDao().insert(transmission("T1", "S1", AttributionState.UNKNOWN))

        val request = { format: ExportFileFormat ->
            ExportRequest(scope = ExportRequestScope.EVERYTHING, format = format)
        }
        val adif = ExportCoordinator.build(context, request(ExportFileFormat.ADIF)).toString(Charsets.UTF_8)
        val csv = ExportCoordinator.build(context, request(ExportFileFormat.CSV)).toString(Charsets.UTF_8)
        val json = ExportCoordinator.build(context, request(ExportFileFormat.JSON)).toString(Charsets.UTF_8)
        val text = ExportCoordinator.build(context, request(ExportFileFormat.TEXT)).toString(Charsets.UTF_8)

        assertTrue(adif.contains("ADIF_VER"))
        assertTrue(csv.contains("transmission_id"))
        assertTrue(json.trim().startsWith("["))
        assertFalse("text format has no CSV header", text.contains("transmission_id"))
    }

    @Test
    fun `R_1009 suggestedFileName carries the real extension for each format`() {
        assertEquals(
            "adi",
            ExportCoordinator.suggestedFileName(
                ExportRequest(ExportRequestScope.EVERYTHING, ExportFileFormat.ADIF),
            ).substringAfterLast('.'),
        )
        assertEquals(
            "csv",
            ExportCoordinator.suggestedFileName(
                ExportRequest(ExportRequestScope.EVERYTHING, ExportFileFormat.CSV),
            ).substringAfterLast('.'),
        )
        assertEquals(
            "json",
            ExportCoordinator.suggestedFileName(
                ExportRequest(ExportRequestScope.EVERYTHING, ExportFileFormat.JSON),
            ).substringAfterLast('.'),
        )
        assertEquals(
            "txt",
            ExportCoordinator.suggestedFileName(
                ExportRequest(ExportRequestScope.EVERYTHING, ExportFileFormat.TEXT),
            ).substringAfterLast('.'),
        )
    }
}
