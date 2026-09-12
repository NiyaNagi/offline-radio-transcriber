package org.ort.pipeline.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Register R-1009 (WPX). JSON is one of the four formats `Settings-Export.dc.html` already
 * offers; this is its real producer. Assertions read specific `"key":` substrings rather than
 * parsing with a library, deliberately (this module has no JSON parser dependency) — each
 * assertion is still a structural fact about the emitted text, never prose. */
class JsonExportWriterTest {

    private fun record(attribution: ExportAttribution, id: String = "t1") = ExportOverRecord(
        transmissionId = id,
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
    fun `FR_EXP_4 the attribution is a nested object that always carries its state, even for empty input`() {
        val json = JsonExportWriter.write(emptyList())
        assertEquals("[]", json.trim())
    }

    @Test
    fun `FR_EXP_4 a confirmed record's attribution object names the state and the real callsign`() {
        val json = JsonExportWriter.write(
            listOf(record(ExportAttribution.Confirmed("KI7ABC", 0.94, corrected = false))),
        )
        assertTrue(json.contains("\"state\":\"CONFIRMED\""))
        assertTrue(json.contains("\"callsign\":\"KI7ABC\""))
        assertTrue(json.contains("\"confidence\":0.94"))
        assertTrue(json.contains("\"transmissionId\":\"t1\""))
    }

    @Test
    fun `FR_EXP_4 an inferred record is never emitted with the confirmed state`() {
        val json = JsonExportWriter.write(
            listOf(record(ExportAttribution.Inferred("KI7ABC", 0.62, corrected = false))),
        )
        assertTrue(json.contains("\"state\":\"INFERRED\""))
        assertFalse(json.contains("\"state\":\"CONFIRMED\""))
    }

    @Test
    fun `FR_EXP_4 ambiguous and unknown records carry a null callsign and confidence, never a fabricated one`() {
        val ambiguous = JsonExportWriter.write(listOf(record(ExportAttribution.Ambiguous)))
        assertTrue(ambiguous.contains("\"state\":\"AMBIGUOUS\""))
        assertTrue(ambiguous.contains("\"callsign\":null"))
        assertTrue(ambiguous.contains("\"confidence\":null"))

        val unknown = JsonExportWriter.write(listOf(record(ExportAttribution.Unknown)))
        assertTrue(unknown.contains("\"state\":\"UNKNOWN\""))
        assertTrue(unknown.contains("\"callsign\":null"))
    }

    @Test
    fun `FR_EXP_2 a transcript with a quote and a newline is escaped into valid JSON string content`() {
        val json = JsonExportWriter.write(
            listOf(
                record(ExportAttribution.Confirmed("KI7ABC", 0.94, corrected = false)).copy(
                    transcriptText = "he said \"break\"\nover",
                ),
            ),
        )
        assertTrue(json.contains("\"transcriptText\":\"he said \\\"break\\\"\\nover\""))
    }

    @Test
    fun `FR_EXP_2 multiple records are emitted as one JSON array`() {
        val json = JsonExportWriter.write(
            listOf(
                record(ExportAttribution.Confirmed("KI7ABC", 0.94, corrected = false), id = "t1"),
                record(ExportAttribution.Unknown, id = "t2"),
            ),
        )
        assertTrue(json.trim().startsWith("["))
        assertTrue(json.trim().endsWith("]"))
        assertTrue(json.contains("\"transmissionId\":\"t1\""))
        assertTrue(json.contains("\"transmissionId\":\"t2\""))
    }
}
