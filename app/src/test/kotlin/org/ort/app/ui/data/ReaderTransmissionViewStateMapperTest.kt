package org.ort.app.ui.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.Attribution
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.entity.TransmissionEntity

/**
 * `TransmissionDetail` -> `TransmissionListEntryViewState`, compose-agnostic (build-plan P14).
 *
 * FR-UI-1: a transmission appears in the live view as it is captured, newest first, and a
 * superseded partial is *visibly* superseded rather than silently replaced. The ordering half of
 * FR-UI-1 is established by `ReaderPollingTest` (real DB read order); this class tests the other
 * half — that the mapping itself never hides a supersession or fabricates a transcript.
 *
 * FR-RUN-9 (audit F-003): `FAILED` and `REJECTED` are terminal states distinct from "still
 * pending" and must render distinct text, not just a null-transcript fallback shared with a
 * transmission that has simply not been processed yet.
 */
class ReaderTransmissionViewStateMapperTest {

    private fun detail(
        id: String = "01ARZ3NDEKTSV4RRFFQ69G5FAV",
        currentTranscriptText: String? = null,
        supersededTranscriptTexts: List<String> = emptyList(),
        attribution: Attribution = Attribution.unknown(),
        hasAudio: Boolean = false,
        processingState: TransmissionState = TransmissionState.CAPTURED,
        rejectionReason: String? = null,
    ) = TransmissionDetail(
        id = id,
        startedAtUtcMillis = 1_000L,
        frequencyHz = 146_960_000L,
        durationMs = 4_200L,
        signalStrength = 7.0,
        attribution = attribution,
        currentTranscriptText = currentTranscriptText,
        supersededTranscriptTexts = supersededTranscriptTexts,
        hasAudio = hasAudio,
        processingState = processingState,
        rejectionReason = rejectionReason,
    )

    @Test
    fun `FR_UI_1 a transmission with no transcript row yet shows the honest not-yet-transcribed label`() {
        val view = ReaderTransmissionViewStateMapper.listEntry(detail(currentTranscriptText = null))

        assertEquals("(captured, not yet transcribed)", view.transcriptText)
    }

    @Test
    fun `FR_UI_1 a transmission with a current transcript and no earlier versions carries no revision note`() {
        val view = ReaderTransmissionViewStateMapper.listEntry(
            detail(
                currentTranscriptText = "this is whiskey seven november papa charlie",
                supersededTranscriptTexts = emptyList(),
            ),
        )

        assertEquals("this is whiskey seven november papa charlie", view.transcriptText)
        assertNull(view.revisionNote)
    }

    @Test
    fun `FR_UI_1 a superseded partial is visibly marked, never silently replaced`() {
        val view = ReaderTransmissionViewStateMapper.listEntry(
            detail(
                currentTranscriptText = "roger that, good copy on the repeater this morning",
                supersededTranscriptTexts = listOf("roger that good copy on the repeat this morning"),
            ),
        )

        assertNotNull(view.revisionNote)
        assertTrue(view.revisionNote!!.contains("1"), "expected the revision count in the note: ${view.revisionNote}")
    }

    @Test
    fun `FR_UI_1 multiple superseded versions are counted, not collapsed to a boolean`() {
        val view = ReaderTransmissionViewStateMapper.listEntry(
            detail(
                currentTranscriptText = "final text",
                supersededTranscriptTexts = listOf("first partial", "second partial"),
            ),
        )

        assertTrue(view.revisionNote!!.contains("2"), "expected 2 earlier versions in the note: ${view.revisionNote}")
    }

    @Test
    fun `frequency renders in MHz and the attribution is carried through unchanged`() {
        val attribution = Attribution.confirmed("W7NPC", 0.95)
        val view = ReaderTransmissionViewStateMapper.listEntry(detail(attribution = attribution))

        assertEquals("146.960", view.frequencyLabel)
        assertEquals(attribution, view.attribution)
    }

    @Test
    fun `a null frequency renders honestly rather than as zero`() {
        val view = ReaderTransmissionViewStateMapper.listEntry(detail().copy(frequencyHz = null))

        assertEquals("—", view.frequencyLabel)
    }

    @Test
    fun `FR_RUN_9 a FAILED transmission renders a failure label distinct from the pending label`() {
        val view = ReaderTransmissionViewStateMapper.listEntry(
            detail(currentTranscriptText = null, processingState = TransmissionState.FAILED),
        )

        val message = "expected a failure label: ${view.transcriptText}"
        assertTrue(view.transcriptText.contains("fail", ignoreCase = true), message)
        assertNotEquals("(captured, not yet transcribed)", view.transcriptText)
    }

    @Test
    fun `FR_RUN_9 a REJECTED transmission renders its rejection reason, not the pending label`() {
        val view = ReaderTransmissionViewStateMapper.listEntry(
            detail(
                currentTranscriptText = null,
                processingState = TransmissionState.REJECTED,
                rejectionReason = "low confidence VAD boundary",
            ),
        )

        val message = "expected the reason in: ${view.transcriptText}"
        assertTrue(view.transcriptText.contains("low confidence VAD boundary"), message)
        assertNotEquals("(captured, not yet transcribed)", view.transcriptText)
    }

    @Test
    fun `FR_RUN_9 a REJECTED transmission with no recorded reason still renders distinctly from pending`() {
        val view = ReaderTransmissionViewStateMapper.listEntry(
            detail(currentTranscriptText = null, processingState = TransmissionState.REJECTED, rejectionReason = null),
        )

        assertNotEquals("(captured, not yet transcribed)", view.transcriptText)
    }

    @Test
    fun `FR_RUN_9 a still-pending CAPTURED or PROCESSING transmission keeps the honest pending label`() {
        val captured = ReaderTransmissionViewStateMapper.listEntry(
            detail(currentTranscriptText = null, processingState = TransmissionState.CAPTURED),
        )
        val processing = ReaderTransmissionViewStateMapper.listEntry(
            detail(currentTranscriptText = null, processingState = TransmissionState.PROCESSING),
        )

        assertEquals("(captured, not yet transcribed)", captured.transcriptText)
        assertEquals("(captured, not yet transcribed)", processing.transcriptText)
    }

    @Test
    fun `FR_RUN_9 the detail view renders the same failure label as the list entry`() {
        val view = ReaderTransmissionViewStateMapper.detailView(
            detail(currentTranscriptText = null, processingState = TransmissionState.FAILED),
        )

        val message = "expected a failure label: ${view.transcriptText}"
        assertTrue(view.transcriptText.contains("fail", ignoreCase = true), message)
    }

    // ---- R-1099: the shared "what does a correction reconstruct to" reconstruction ----

    private fun entity(
        id: String = "TX1",
        stationId: String? = "K7LWH",
        corrected: Boolean = false,
        attributionState: AttributionState = AttributionState.INFERRED,
        attributionConfidence: Double? = 0.7,
    ) = TransmissionEntity(
        id = id,
        sessionId = "S1",
        threadId = null,
        startedAtUtc = 0L,
        endedAtUtc = 1_000L,
        durationMs = 1_000L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = 146_960_000L,
        frequencyProvenance = "measured",
        mode = null,
        signalStrength = null,
        channelName = null,
        voiceprintId = null,
        attributionState = attributionState,
        stationId = stationId,
        attributionConfidence = attributionConfidence,
        attributionSourceTransmissionId = null,
        corrected = corrected,
        processingState = TransmissionState.COMPLETE,
        rejectionReason = null,
        samplePosition = 0L,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    @Test
    fun `R_1099 correctedAttributionOrNull is null for an uncorrected row`() {
        assertNull(ReaderTransmissionViewStateMapper.correctedAttributionOrNull(entity(corrected = false)))
    }

    @Test
    fun `R_1099 correctedAttributionOrNull is null when corrected but no station was ever recorded`() {
        assertNull(
            ReaderTransmissionViewStateMapper.correctedAttributionOrNull(entity(corrected = true, stationId = null)),
        )
    }

    @Test
    fun `R_1099 correctedAttributionOrNull reconstructs the exact shape a correction always writes`() {
        val attribution = ReaderTransmissionViewStateMapper.correctedAttributionOrNull(
            entity(corrected = true, stationId = "VE7ABC"),
        )

        assertNotNull(attribution)
        assertEquals(AttributionState.INFERRED, attribution!!.state)
        assertEquals("VE7ABC", attribution.stationId)
        assertNull(attribution.confidence)
        assertTrue(attribution.corrected, "a correction's attribution must carry the corrected lock")
    }

    @Test
    fun `R_1099 attributionFrom delegates to correctedAttributionOrNull for a corrected row`() {
        val corrected = entity(corrected = true, stationId = "VE7ABC")

        assertEquals(
            ReaderTransmissionViewStateMapper.correctedAttributionOrNull(corrected),
            ReaderTransmissionViewStateMapper.attributionFrom(corrected),
        )
    }

    @Test
    fun `R_1099 attributionFrom still reconstructs from state and confidence for an uncorrected row`() {
        val uncorrected = entity(corrected = false, attributionState = AttributionState.CONFIRMED, stationId = "K7LWH")

        val attribution = ReaderTransmissionViewStateMapper.attributionFrom(uncorrected)

        assertEquals(Attribution.confirmed("K7LWH", 0.7), attribution)
    }
}
