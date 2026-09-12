package org.ort.pipeline.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Register R-1009 (WPX), FR-EXP-3: "Export POTA-relevant activity (park reference, station,
 * frequency, time)." Only overs whose attributed station carries a park reference are
 * "POTA-relevant" — everything else is out of scope for this specific export, not for the general
 * log ([CsvExportWriter] already covers every over regardless of POTA). */
class PotaActivityExportWriterTest {

    private fun record(attribution: ExportAttribution, potaRefs: List<String> = emptyList(), id: String = "t1") =
        ExportOverRecord(
            transmissionId = id,
            sessionId = "s1",
            startedAtUtcMillis = 1_757_477_520_000L,
            endedAtUtcMillis = 1_757_477_525_000L,
            frequencyHz = 146_520_000L,
            mode = "FM",
            channelName = "Repeater 1",
            attribution = attribution,
            transcriptText = "this is KI7ABC activating K-1234",
            transcriptModelId = "whisper-small",
            transcriptModelVersion = "1.2.0",
            potaRefs = potaRefs,
        )

    @Test
    fun `FR_EXP_3 only overs whose station carries a park reference are included`() {
        val rows = PotaActivityExportWriter.write(
            listOf(
                record(ExportAttribution.Confirmed("KI7ABC", 0.94, corrected = false), potaRefs = listOf("K-1234")),
                record(ExportAttribution.Confirmed("KJ7XYZ", 0.90, corrected = false), potaRefs = emptyList()),
            ),
        )
        assertEquals(2, rows.lines().size) { "header + exactly one POTA-relevant row, got:\n$rows" }
        assertTrue(rows.contains("K-1234"))
        assertFalse(rows.contains("KJ7XYZ"))
    }

    @Test
    fun `FR_EXP_3 the row names park reference, station, frequency and time`() {
        val rows = PotaActivityExportWriter.write(
            listOf(
                record(
                    ExportAttribution.Confirmed("KI7ABC", 0.94, corrected = false),
                    potaRefs = listOf("K-1234"),
                ),
            ),
        )
        val header = rows.lines().first()
        for (column in listOf("park_reference", "station", "frequency_hz", "time_utc")) {
            assertTrue(header.contains(column)) { "expected $column in header, got $header" }
        }
        val row = rows.lines()[1]
        assertTrue(row.contains("K-1234"))
        assertTrue(row.contains("KI7ABC"))
        assertTrue(row.contains("146520000"))
        assertTrue(row.contains("2025-09-10T04:12:00Z"))
    }

    @Test
    fun `FR_EXP_4 a POTA row for an unidentified station names the park reference, never a fabricated callsign`() {
        val rows = PotaActivityExportWriter.write(
            listOf(record(ExportAttribution.Ambiguous, potaRefs = listOf("K-5678"))),
        )
        val row = rows.lines()[1]
        assertTrue(row.contains("K-5678"))
        assertTrue(row.contains("UNIDENTIFIED"))
        assertTrue(row.contains("AMBIGUOUS"))
    }

    @Test
    fun `FR_EXP_3 multiple park references on one over each get their own row`() {
        val rows = PotaActivityExportWriter.write(
            listOf(
                record(
                    ExportAttribution.Confirmed("KI7ABC", 0.94, corrected = false),
                    potaRefs = listOf("K-1234", "K-5678"),
                ),
            ),
        )
        assertEquals(3, rows.lines().size) { "header + one row per park reference, got:\n$rows" }
        assertTrue(rows.contains("K-1234"))
        assertTrue(rows.contains("K-5678"))
    }
}
