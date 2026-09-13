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
import org.ort.pipeline.export.ExportAttribution
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

    // -----------------------------------------------------------------------------------------
    // R-1035: the exportable count before the write — "0 of 8 can be exported as QSOs; 7 have no
    // identified station", never discovered only after saving an empty file.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `R_1035 ADIF previewCount matches the operator's own log — 0 exportable of 8, 7 excluded`() = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L))
        db.catalogDao().insert(station("ST1", "KI7ABC"))
        db.transmissionDao().insert(
            transmission(
                "T-confirmed",
                "S1",
                AttributionState.CONFIRMED,
                stationId = "ST1",
                attributionConfidence = 0.9,
            ),
        )
        repeat(7) { index ->
            db.transmissionDao().insert(transmission("T-unknown-$index", "S1", AttributionState.UNKNOWN))
        }

        val preview = ExportCoordinator.previewCount(
            context,
            ExportRequest(scope = ExportRequestScope.EVERYTHING, format = ExportFileFormat.ADIF),
        )

        assertEquals(8, preview.totalCount)
        assertEquals(1, preview.exportableCount)
        assertEquals(7, preview.excludedCount)
    }

    @Test
    fun `R_1035 previewCount's exportableCount matches the real written ADIF record count exactly`() = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L))
        db.transmissionDao().insert(transmission("T1", "S1", AttributionState.UNKNOWN))
        db.transmissionDao().insert(transmission("T2", "S1", AttributionState.AMBIGUOUS))

        val request = ExportRequest(scope = ExportRequestScope.EVERYTHING, format = ExportFileFormat.ADIF)
        val preview = ExportCoordinator.previewCount(context, request)
        val adif = ExportCoordinator.build(context, request).toString(Charsets.UTF_8)

        assertEquals(0, preview.exportableCount)
        assertFalse("expected zero <EOR> records to match exportableCount of 0", adif.contains("<EOR>"))
    }

    @Test
    fun `R_1035 non-ADIF formats never exclude a record — exportableCount equals totalCount`() = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L))
        db.transmissionDao().insert(transmission("T1", "S1", AttributionState.UNKNOWN))
        db.transmissionDao().insert(transmission("T2", "S1", AttributionState.AMBIGUOUS))

        for (format in listOf(ExportFileFormat.CSV, ExportFileFormat.JSON, ExportFileFormat.TEXT)) {
            val preview = ExportCoordinator.previewCount(
                context,
                ExportRequest(scope = ExportRequestScope.EVERYTHING, format = format),
            )
            assertEquals("format $format", 2, preview.totalCount)
            assertEquals("format $format", 2, preview.exportableCount)
            assertEquals("format $format", 0, preview.excludedCount)
        }
    }

    @Test
    fun `R_1035 previewCount honours confirmedOnly exactly as build does, same effective record set`() = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L))
        db.catalogDao().insert(station("ST1", "KI7ABC"))
        db.transmissionDao().insert(
            transmission("T1", "S1", AttributionState.CONFIRMED, stationId = "ST1", attributionConfidence = 0.9),
        )
        db.transmissionDao().insert(transmission("T2", "S1", AttributionState.UNKNOWN))

        val preview = ExportCoordinator.previewCount(
            context,
            ExportRequest(
                scope = ExportRequestScope.EVERYTHING,
                format = ExportFileFormat.ADIF,
                confirmedOnly = true,
            ),
        )

        assertEquals(1, preview.totalCount)
        assertEquals(1, preview.exportableCount)
        assertEquals(0, preview.excludedCount)
    }

    // -----------------------------------------------------------------------------------------
    // R-1039 (halt): the Save file write path must never throw on real, reachable data — a
    // CONFIRMED/INFERRED transmission whose station carries no catalog callsign (the `station`
    // table is a lazily-populated secondary aggregate, never guaranteed to have a row for every
    // callsign a transmission was attributed to), and a corrected INFERRED row with no confidence
    // ([org.ort.core.Attribution.withCorrection]'s own real shape). Discriminating: each test below
    // was run against the pre-fix `toExportAttribution` (reverted locally) and observed to throw
    // `IllegalArgumentException` for exactly the case it now covers; restoring the fix makes it pass.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `R_1039 a CONFIRMED transmission with no catalog station row exports using its own stationId, never throws`() =
        runTest {
            db.sessionDao().insert(session("S1", startedAt = 0L))
            // Deliberately no `db.catalogDao().insert(station(...))` call — the real `overnight`
            // scenario's own shape for most of its CONFIRMED overs (`OvernightScenario.kt`): a
            // resolved callsign on the transmission row with no corresponding `station` catalog row
            // at all.
            db.transmissionDao().insert(
                transmission("T1", "S1", AttributionState.CONFIRMED, stationId = "W7NPC", attributionConfidence = 0.94),
            )

            val csv = ExportCoordinator.build(
                context,
                ExportRequest(scope = ExportRequestScope.EVERYTHING, format = ExportFileFormat.CSV),
            ).toString(Charsets.UTF_8)
            val adif = ExportCoordinator.build(
                context,
                ExportRequest(scope = ExportRequestScope.EVERYTHING, format = ExportFileFormat.ADIF),
            ).toString(Charsets.UTF_8)

            assertTrue("expected the transmission's own stationId used as the callsign", csv.contains("W7NPC"))
            assertTrue(csv.contains("CONFIRMED"))
            assertTrue("expected a real QSO record, not an excluded row", adif.contains("<CALL:5>W7NPC"))
        }

    @Test
    fun `R_1039 a station row whose callsign is null falls back to the transmission's own stationId, never throws`() =
        runTest {
            db.sessionDao().insert(session("S1", startedAt = 0L))
            db.catalogDao().insert(
                StationEntity(
                    id = "W7NPC",
                    callsign = null,
                    firstHeardAt = null,
                    lastHeardAt = null,
                    transmissionCount = 0,
                    notes = null,
                    userName = "the Tuesday net control",
                    frequenciesHeard = null,
                    activityByHourDow = null,
                    potaRefs = null,
                    spokenGrids = null,
                    ituRegionFromPrefix = null,
                    overCountsByAttributionState = null,
                ),
            )
            db.transmissionDao().insert(
                transmission("T1", "S1", AttributionState.CONFIRMED, stationId = "W7NPC", attributionConfidence = 0.94),
            )

            val csv = ExportCoordinator.build(
                context,
                ExportRequest(scope = ExportRequestScope.EVERYTHING, format = ExportFileFormat.CSV),
            ).toString(Charsets.UTF_8)

            assertTrue("expected the fallback to the transmission's own stationId", csv.contains("W7NPC"))
        }

    @Test
    fun `R_1039 a corrected INFERRED transmission with no confidence never throws — real callsign, empty confidence`() =
        runTest {
            db.sessionDao().insert(session("S1", startedAt = 0L))
            db.transmissionDao().insert(
                transmission(
                    "T1",
                    "S1",
                    AttributionState.INFERRED,
                    stationId = "KJ7ABC",
                    attributionConfidence = null,
                ),
            )

            val csv = ExportCoordinator.build(
                context,
                ExportRequest(scope = ExportRequestScope.EVERYTHING, format = ExportFileFormat.CSV),
            ).toString(Charsets.UTF_8)

            assertTrue(csv.contains("KJ7ABC"))
            assertTrue(csv.contains("INFERRED"))
            val row = csv.lines()[1].split(",")
            assertEquals("expected an empty confidence cell, never a fabricated value, got row: $row", "", row[9])
        }

    @Test
    fun `R_1039 a CONFIRMED transmission with a genuinely null stationId is exported honestly, never thrown on`() =
        runTest {
            db.sessionDao().insert(session("S1", startedAt = 0L))
            // A structurally anomalous row `Attribution.confirmed`'s own factory could never
            // produce (it requires a non-blank station id) — reachable only via a raw DAO write or
            // a corrupted restore, exactly the case `ExportAttribution.UnresolvedCallsign` exists
            // for.
            db.transmissionDao().insert(
                transmission("T1", "S1", AttributionState.CONFIRMED, stationId = null, attributionConfidence = 0.94),
            )

            val csv = ExportCoordinator.build(
                context,
                ExportRequest(scope = ExportRequestScope.EVERYTHING, format = ExportFileFormat.CSV),
            ).toString(Charsets.UTF_8)
            val adif = ExportCoordinator.build(
                context,
                ExportRequest(scope = ExportRequestScope.EVERYTHING, format = ExportFileFormat.ADIF),
            ).toString(Charsets.UTF_8)

            assertTrue("expected the real CONFIRMED state stated, never relabelled", csv.contains("CONFIRMED"))
            assertFalse(
                "UnresolvedCallsign must never be confused with AMBIGUOUS/UNKNOWN's own placeholder",
                csv.lines()[1].split(",")[8] == "UNIDENTIFIED",
            )
            assertFalse("no fabricated <EOR> record for a row with no nameable callsign", adif.contains("<EOR>"))
            val header = adif.substringBefore("<EOH>")
            assertTrue("expected the row's real CONFIRMED state named in the ADIF header", header.contains("CONFIRMED"))
        }

    @Test
    fun `R_1039 previewCount never throws for the same shape that used to crash the Settings-Export screen`() =
        runTest {
            db.sessionDao().insert(session("S1", startedAt = 0L))
            db.transmissionDao().insert(
                transmission("T1", "S1", AttributionState.CONFIRMED, stationId = "W7NPC", attributionConfidence = 0.94),
            )

            val preview = ExportCoordinator.previewCount(
                context,
                ExportRequest(scope = ExportRequestScope.EVERYTHING, format = ExportFileFormat.ADIF),
            )

            assertEquals(1, preview.totalCount)
            assertEquals(1, preview.exportableCount)
            assertEquals(0, preview.excludedCount)
        }

    @Test
    fun `R_1039 toExportAttribution never throws for a missing callsign — CONFIRMED and INFERRED alike`() {
        val confirmed = transmission("T1", "S1", AttributionState.CONFIRMED, attributionConfidence = 0.9)
        val inferred = transmission("T2", "S1", AttributionState.INFERRED, attributionConfidence = null)

        val confirmedAttribution = ExportCoordinator.toExportAttribution(confirmed, callsign = null)
        val inferredAttribution = ExportCoordinator.toExportAttribution(inferred, callsign = null)

        assertTrue(confirmedAttribution is ExportAttribution.UnresolvedCallsign)
        assertTrue(inferredAttribution is ExportAttribution.UnresolvedCallsign)
        assertEquals(AttributionState.CONFIRMED, (confirmedAttribution as ExportAttribution.UnresolvedCallsign).state)
        assertEquals(AttributionState.INFERRED, (inferredAttribution as ExportAttribution.UnresolvedCallsign).state)
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
