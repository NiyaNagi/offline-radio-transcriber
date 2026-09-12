package org.ort.pipeline.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Register R-1009 (WPX), FR-EXP-5: "Offer a filtered export of confirmed-only records for users
 * who want a conservative log." */
class ConfirmedOnlyExportFilterTest {

    private fun record(attribution: ExportAttribution, id: String) = ExportOverRecord(
        transmissionId = id,
        sessionId = "s1",
        startedAtUtcMillis = 0L,
        endedAtUtcMillis = null,
        frequencyHz = null,
        mode = null,
        channelName = null,
        attribution = attribution,
        transcriptText = null,
        transcriptModelId = null,
        transcriptModelVersion = null,
    )

    @Test
    fun `FR_EXP_5 only CONFIRMED records survive the filter`() {
        val records = listOf(
            record(ExportAttribution.Confirmed("KI7ABC", 0.94, corrected = false), "confirmed"),
            record(ExportAttribution.Inferred("KJ7XYZ", 0.62, corrected = false), "inferred"),
            record(ExportAttribution.Ambiguous, "ambiguous"),
            record(ExportAttribution.Unknown, "unknown"),
        )
        val filtered = confirmedOnly(records)
        assertEquals(1, filtered.size)
        assertEquals("confirmed", filtered.single().transmissionId)
        assertTrue(filtered.single().attribution is ExportAttribution.Confirmed)
    }

    @Test
    fun `FR_EXP_5 an empty input produces an empty result, never an error`() {
        assertEquals(emptyList<ExportOverRecord>(), confirmedOnly(emptyList()))
    }

    @Test
    fun `FR_EXP_5 the confirmed-only CSV never contains an inferred or unidentified row`() {
        val records = listOf(
            record(ExportAttribution.Confirmed("KI7ABC", 0.94, corrected = false), "confirmed"),
            record(ExportAttribution.Inferred("KJ7XYZ", 0.62, corrected = false), "inferred"),
        )
        val csv = CsvExportWriter.write(confirmedOnly(records))
        assertEquals(2, csv.lines().size) { "header + exactly one confirmed row" }
        assertTrue(csv.contains("KI7ABC"))
        assertTrue(!csv.contains("KJ7XYZ"))
    }
}
