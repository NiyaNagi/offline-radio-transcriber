package org.ort.pipeline.export

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Register R-1009 (WPX). Plain-text is the fourth format `Settings-Export.dc.html` already
 * offers — a human-readable line per over, for reading rather than importing. */
class TextExportWriterTest {

    private fun record(attribution: ExportAttribution) = ExportOverRecord(
        transmissionId = "t1",
        sessionId = "s1",
        startedAtUtcMillis = 1_757_477_520_000L,
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
    fun `FR_EXP_4 a confirmed line names the callsign and the state together`() {
        val text = TextExportWriter.write(
            listOf(record(ExportAttribution.Confirmed("KI7ABC", 0.94, corrected = false))),
        )
        assertTrue(text.contains("KI7ABC"))
        assertTrue(text.contains("CONFIRMED"))
        assertTrue(text.contains("2025-09-10T04:12:00Z"))
        assertTrue(text.contains("146520000"))
    }

    @Test
    fun `FR_EXP_4 an inferred line never reads as confirmed`() {
        val text = TextExportWriter.write(
            listOf(record(ExportAttribution.Inferred("KI7ABC", 0.62, corrected = false))),
        )
        assertTrue(text.contains("INFERRED"))
        assertFalse(text.contains("CONFIRMED"))
    }

    @Test
    fun `FR_EXP_4 an ambiguous or unknown line never invents a callsign`() {
        // Deliberately no callsign anywhere in the transcript text itself here, so a callsign
        // appearing in the rendered line could only have leaked from the attribution cell.
        val record = record(ExportAttribution.Ambiguous).copy(transcriptText = "unreadable, too much noise")
        val ambiguous = TextExportWriter.write(listOf(record))
        assertTrue(ambiguous.contains("AMBIGUOUS"))
        assertTrue(ambiguous.contains("UNIDENTIFIED"))
        assertFalse(ambiguous.contains("KI7ABC")) { "expected no fabricated callsign, got: $ambiguous" }
    }
}
