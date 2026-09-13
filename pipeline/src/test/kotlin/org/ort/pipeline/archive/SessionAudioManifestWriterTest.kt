package org.ort.pipeline.archive

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.pipeline.export.ExportAttribution

/**
 * `SessionAudioExport`'s `manifest.json` producer. Assertions read specific `"key":` substrings
 * rather than parsing with a library, deliberately (no JSON parser on this module's classpath) —
 * the same discipline `org.ort.pipeline.export.JsonExportWriterTest`'s own kdoc states.
 */
class SessionAudioManifestWriterTest {

    private fun over(
        id: String = "TX1",
        attribution: ExportAttribution = ExportAttribution.Confirmed("KI7ABC", 0.94, corrected = false),
        audioIncluded: Boolean = true,
    ) = SessionAudioExportOverManifest(
        transmissionId = id,
        startedAtUtcMillis = 1_757_477_520_000L,
        endedAtUtcMillis = 1_757_477_525_000L,
        durationMs = 5_000L,
        frequencyHz = 146_520_000L,
        frequencyProvenance = "measured",
        mode = "FM",
        attribution = attribution,
        transcriptText = "this is KI7ABC on the repeater",
        transcriptModelId = "whisper-small",
        transcriptModelVersion = "1.2.0",
        processedTier = "T1",
        executionProvider = "cpu",
        audioIncluded = audioIncluded,
    )

    private fun manifest(overs: List<SessionAudioExportOverManifest> = listOf(over())) = SessionAudioExportManifest(
        formatVersion = SessionAudioExport.MANIFEST_FORMAT_VERSION,
        sessionId = "S1",
        startedAtUtcMillis = 1_757_477_000_000L,
        endedAtUtcMillis = 1_757_478_000_000L,
        appVersion = "0.1.1",
        exportedAtUtcMillis = 1_757_479_000_000L,
        target = SessionAudioExportTarget.BOTH,
        overAudio = SessionAudioExportAudioHalf(removedAtUtcMillis = null, includedInThisExport = true),
        archive = SessionAudioExportArchiveHalf(
            state = ArchiveState.KEPT,
            removedAtUtcMillis = null,
            includedInThisExport = true,
        ),
        overs = overs,
    )

    @Test
    fun `FR_STO_6 the manifest names its own format version, session id and app version`() {
        val json = SessionAudioManifestWriter.write(manifest())

        assertTrue(json.contains("\"formatVersion\": 1"))
        assertTrue(json.contains("\"sessionId\": \"S1\""))
        assertTrue(json.contains("\"appVersion\": \"0.1.1\""))
    }

    @Test
    fun `P9 a removed over-audio half states its removal date rather than reading as never-included`() {
        val json = SessionAudioManifestWriter.write(
            manifest().copy(
                overAudio = SessionAudioExportAudioHalf(removedAtUtcMillis = 4_242L, includedInThisExport = false),
            ),
        )
        val overAudioObject = json.substringAfter("\"overAudio\": ").substringBefore("\"archive\"")

        assertTrue(overAudioObject.contains("\"includedInThisExport\":false"))
        assertFalse(overAudioObject.contains("\"removedAtUtc\":null"))
    }

    @Test
    fun `constitution_I an archive that was never kept states NONE, never conflated with REMOVED`() {
        val json = SessionAudioManifestWriter.write(
            manifest().copy(
                archive = SessionAudioExportArchiveHalf(
                    state = ArchiveState.NONE,
                    removedAtUtcMillis = null,
                    includedInThisExport = false,
                ),
            ),
        )

        assertTrue(json.contains("\"state\":\"NONE\""))
        assertFalse(json.contains("\"state\":\"REMOVED\""))
    }

    @Test
    fun `FR_EXP_4 a confirmed over names the state and the real callsign together`() {
        val json = SessionAudioManifestWriter.write(manifest())

        assertTrue(json.contains("\"state\":\"CONFIRMED\""))
        assertTrue(json.contains("\"callsign\":\"KI7ABC\""))
        assertTrue(json.contains("\"confidence\":0.94"))
    }

    @Test
    fun `FR_EXP_4 ambiguous and unknown overs carry a null callsign and confidence, never a fabricated one`() {
        val ambiguousOver = over(attribution = ExportAttribution.Ambiguous)
        val ambiguous = SessionAudioManifestWriter.write(manifest(listOf(ambiguousOver)))
        assertTrue(ambiguous.contains("\"state\":\"AMBIGUOUS\""))
        assertTrue(ambiguous.contains("\"callsign\":null"))
        assertTrue(ambiguous.contains("\"confidence\":null"))

        val unknownOver = over(attribution = ExportAttribution.Unknown)
        val unknown = SessionAudioManifestWriter.write(manifest(listOf(unknownOver)))
        assertTrue(unknown.contains("\"state\":\"UNKNOWN\""))
        assertTrue(unknown.contains("\"callsign\":null"))
    }

    @Test
    fun `R_1039 an unresolved callsign carries its reason as the note, never a fabricated callsign`() {
        val json = SessionAudioManifestWriter.write(
            manifest(
                listOf(
                    over(
                        attribution = ExportAttribution.UnresolvedCallsign(
                            state = org.ort.core.AttributionState.CONFIRMED,
                            reason = "data integrity defect",
                        ),
                    ),
                ),
            ),
        )

        assertTrue(json.contains("\"state\":\"CONFIRMED\""))
        assertTrue(json.contains("\"callsign\":null"))
        assertTrue(json.contains("\"note\":\"data integrity defect\""))
    }

    @Test
    fun `an over whose audio was not included states that plainly rather than omitting the row`() {
        val json = SessionAudioManifestWriter.write(manifest(listOf(over(audioIncluded = false))))

        assertTrue(json.contains("\"transmissionId\":\"TX1\""))
        assertTrue(json.contains("\"audioIncluded\":false"))
    }

    @Test
    fun `an empty session's overs array is empty, never a fabricated row`() {
        val json = SessionAudioManifestWriter.write(manifest(emptyList()))

        assertTrue(json.contains("\"overs\": []"))
    }
}
