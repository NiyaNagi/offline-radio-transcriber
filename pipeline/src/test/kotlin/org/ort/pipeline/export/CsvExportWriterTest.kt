package org.ort.pipeline.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Register R-1009 (WPX), FR-EXP-2: "CSV with all fields including confidence and provenance." */
class CsvExportWriterTest {

    private fun record(attribution: ExportAttribution, transcriptText: String? = "this is KI7ABC on the repeater") =
        ExportOverRecord(
            transmissionId = "t1",
            sessionId = "s1",
            startedAtUtcMillis = 1_757_477_520_000L, // 2025-09-10T04:12:00Z
            endedAtUtcMillis = 1_757_477_525_000L,
            frequencyHz = 146_520_000L,
            mode = "FM",
            channelName = "Repeater 1",
            attribution = attribution,
            transcriptText = transcriptText,
            transcriptModelId = "whisper-small",
            transcriptModelVersion = "1.2.0",
        )

    @Test
    fun `FR_EXP_2 the header names every field including confidence and provenance`() {
        val csv = CsvExportWriter.write(emptyList())
        val header = csv.lines().first()
        for (column in listOf(
            "transmission_id", "session_id", "started_at_utc", "ended_at_utc", "frequency_hz",
            "mode", "channel_name", "attribution_state", "callsign", "confidence", "corrected",
            "transcript_model_id", "transcript_model_version", "transcript_text",
        )) {
            assertTrue(header.contains(column)) { "expected header to name $column, got $header" }
        }
    }

    @Test
    fun `FR_EXP_2 a confirmed row carries its real callsign, confidence and provenance`() {
        val csv = CsvExportWriter.write(
            listOf(record(ExportAttribution.Confirmed("KI7ABC", 0.94, corrected = false))),
        )
        val row = csv.lines()[1]
        assertTrue(row.contains("CONFIRMED"))
        assertTrue(row.contains("KI7ABC"))
        assertTrue(row.contains("0.9400"))
        assertTrue(row.contains("whisper-small"))
        assertTrue(row.contains("1.2.0"))
        assertTrue(row.contains("146520000")) { "expected the real raw Hz value, got $row" }
    }

    @Test
    fun `FR_EXP_2 timestamps are ISO-8601 UTC, never a locale-dependent format`() {
        val csv = CsvExportWriter.write(listOf(record(ExportAttribution.Unknown)))
        val row = csv.lines()[1]
        assertTrue(row.contains("2025-09-10T04:12:00Z")) { "expected ISO-8601 UTC, got $row" }
    }

    @Test
    fun `FR_EXP_4 an ambiguous row never carries a callsign or confidence, but states its state`() {
        val csv = CsvExportWriter.write(listOf(record(ExportAttribution.Ambiguous)))
        val row = csv.lines()[1]
        assertTrue(row.contains("AMBIGUOUS"))
        assertTrue(row.contains("UNIDENTIFIED"))
        assertFalse(row.contains("0.9400"))
    }

    @Test
    fun `FR_EXP_4 an unknown row never carries a callsign or confidence, but states its state`() {
        val csv = CsvExportWriter.write(listOf(record(ExportAttribution.Unknown)))
        val row = csv.lines()[1]
        assertTrue(row.contains("UNKNOWN"))
        assertTrue(row.contains("UNIDENTIFIED"))
    }

    @Test
    fun `FR_EXP_4 an inferred row is never indistinguishable from a confirmed one`() {
        val confirmedCsv = CsvExportWriter.write(
            listOf(record(ExportAttribution.Confirmed("KI7ABC", 0.94, corrected = false))),
        )
        val inferredCsv = CsvExportWriter.write(
            listOf(record(ExportAttribution.Inferred("KI7ABC", 0.94, corrected = false))),
        )
        assertFalse(confirmedCsv.lines()[1] == inferredCsv.lines()[1]) {
            "a CONFIRMED and an INFERRED row with identical callsign/confidence must still differ"
        }
        assertTrue(inferredCsv.lines()[1].contains("INFERRED"))
        assertFalse(inferredCsv.lines()[1].contains("CONFIRMED"))
    }

    @Test
    fun `FR_EXP_2 a transcript containing a comma and a quote is safely escaped, never corrupting columns`() {
        val csv = CsvExportWriter.write(
            listOf(
                record(
                    ExportAttribution.Confirmed("KI7ABC", 0.94, corrected = false),
                    transcriptText = "he said, \"break, break\"",
                ),
            ),
        )
        val dataLine = csv.lines()[1]
        // A naive split on comma would produce more than 14 fields if the transcript were not quoted.
        assertEquals(14, splitCsvLine(dataLine).size) { "expected exactly 14 columns, got: $dataLine" }
        assertTrue(dataLine.contains("\"he said, \"\"break, break\"\"\""))
    }

    /** A minimal RFC4180-aware splitter for this test only — never used by the writer itself. */
    private fun splitCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    fields += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        fields += current.toString()
        return fields
    }
}
