package org.ort.pipeline.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Register R-1009 (WPX), FR-EXP-1: "Export stations heard as ADIF, for logging software." ADIF's
 * `CALL` field is how a logging program identifies a QSO — it has no native vocabulary for "I do
 * not know who this was" (see the class kdoc on [AdifExportWriter] for the decision this writer
 * makes about that gap: never a fabricated `CALL`, never a silent drop).
 */
class AdifExportWriterTest {

    private fun record(attribution: ExportAttribution, id: String = "t1") = ExportOverRecord(
        transmissionId = id,
        sessionId = "s1",
        startedAtUtcMillis = 1_757_477_520_000L, // 2025-09-10T04:12:00Z
        endedAtUtcMillis = 1_757_477_525_000L,
        frequencyHz = 146_520_000L,
        mode = "FM",
        channelName = "Repeater 1",
        attribution = attribution,
        transcriptText = "this is KI7ABC on the repeater",
        transcriptModelId = "whisper-small",
        transcriptModelVersion = "1.2.0",
    )

    @Test
    fun `FR_EXP_1 a confirmed over becomes a real ADIF QSO record with its callsign, date, time and frequency`() {
        val adif = AdifExportWriter.write(
            listOf(record(ExportAttribution.Confirmed("KI7ABC", 0.94, corrected = false))),
        )
        assertTrue(adif.contains("<CALL:6>KI7ABC"))
        assertTrue(adif.contains("<QSO_DATE:8>20250910"))
        assertTrue(adif.contains("<TIME_ON:6>041200"))
        assertTrue(adif.contains("<FREQ:10>146.520000"))
        assertTrue(adif.contains("<MODE:2>FM"))
        assertTrue(adif.contains("<EOR>"))
        assertTrue(adif.contains("<EOH>"))
    }

    @Test
    fun `FR_EXP_4 a confirmed and an inferred record carry different APP_ORT_ATTRIBUTION_STATE fields`() {
        val confirmed = AdifExportWriter.write(
            listOf(record(ExportAttribution.Confirmed("KI7ABC", 0.94, corrected = false))),
        )
        val inferred = AdifExportWriter.write(
            listOf(record(ExportAttribution.Inferred("KI7ABC", 0.62, corrected = false))),
        )
        assertTrue(confirmed.contains("<APP_ORT_ATTRIBUTION_STATE:9>CONFIRMED"))
        assertTrue(inferred.contains("<APP_ORT_ATTRIBUTION_STATE:8>INFERRED"))
        assertFalse(inferred.contains("<APP_ORT_ATTRIBUTION_STATE:9>CONFIRMED")) {
            "an INFERRED record must never be tagged CONFIRMED"
        }
    }

    @Test
    fun `FR_EXP_4 an ambiguous or unknown over is never emitted as a QSO record with a fabricated callsign`() {
        val adif = AdifExportWriter.write(
            listOf(
                record(ExportAttribution.Ambiguous, id = "t-ambiguous"),
                record(ExportAttribution.Unknown, id = "t-unknown"),
            ),
        )
        assertFalse(adif.contains("<CALL:")) { "no callsign field may exist when nothing was identified" }
        assertFalse(adif.contains("<EOR>")) { "no QSO record may exist for an unidentified over" }
    }

    @Test
    fun `FR_EXP_4 an unidentified over is still named in the file, never silently dropped`() {
        val adif = AdifExportWriter.write(
            listOf(record(ExportAttribution.Ambiguous, id = "t-ambiguous-123")),
        )
        val header = adif.substringBefore("<EOH>")
        assertTrue(header.contains("t-ambiguous-123")) {
            "expected the excluded transmission id named in the header, got: $header"
        }
        assertTrue(header.contains("AMBIGUOUS"))
        assertTrue(header.contains("excluded", ignoreCase = true))
    }

    @Test
    fun `FR_EXP_1 a mix of confirmed, inferred and unidentified overs produces QSO records for the two named ones`() {
        val adif = AdifExportWriter.write(
            listOf(
                record(ExportAttribution.Confirmed("KI7ABC", 0.94, corrected = false), id = "t-confirmed"),
                record(ExportAttribution.Inferred("KJ7XYZ", 0.55, corrected = false), id = "t-inferred"),
                record(ExportAttribution.Unknown, id = "t-unknown"),
            ),
        )
        val eorCount = Regex("<EOR>").findAll(adif).count()
        assertEquals(2, eorCount) { "expected exactly 2 QSO records (confirmed + inferred), got:\n$adif" }
    }
}
