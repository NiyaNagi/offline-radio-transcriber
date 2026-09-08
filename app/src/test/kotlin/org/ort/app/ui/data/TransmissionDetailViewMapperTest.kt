package org.ort.app.ui.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.Attribution

/**
 * Build-plan P16: `detailView` carries the inspection surface (FR-UI-8) and the labelled-sample
 * defaults (FR-OBS-4) through from [TransmissionDetail], which build-plan P14 did not need since
 * neither existed yet.
 */
class TransmissionDetailViewMapperTest {

    private fun detail(
        inspection: InspectionViewState = InspectionViewState.EMPTY,
        sessionId: String = "SESSION01",
        samplePosition: Long = 16_000L,
        durationMs: Long = 2_000L,
    ) = TransmissionDetail(
        id = "TX1",
        sessionId = sessionId,
        samplePosition = samplePosition,
        startedAtUtcMillis = 1_000L,
        frequencyHz = 146_960_000L,
        durationMs = durationMs,
        signalStrength = 7.0,
        attribution = Attribution.unknown(),
        currentTranscriptText = null,
        supersededTranscriptTexts = emptyList(),
        hasAudio = false,
        inspection = inspection,
    )

    @Test
    fun `the inspection surface is carried through unchanged`() {
        val inspection = InspectionViewState(
            lattice = LatticeInspectionViewState("TEXT_DERIVED", "v1", 1L),
            candidates = emptyList(),
        )

        val view = ReaderTransmissionViewStateMapper.detailView(detail(inspection = inspection))

        assertEquals(inspection, view.inspection)
    }

    @Test
    fun `an empty inspection surface is carried through honestly, not defaulted to something else`() {
        val view = ReaderTransmissionViewStateMapper.detailView(detail())

        assertTrue(view.inspection.isEmpty)
    }

    @Test
    fun `FR_OBS_4 the labelled-sample defaults derive session id and the sample window at 16kHz`() {
        val view = ReaderTransmissionViewStateMapper.detailView(
            detail(sessionId = "S9", samplePosition = 16_000L, durationMs = 2_000L),
        )

        assertEquals("S9", view.sessionId)
        assertEquals(16_000L, view.startSample)
        assertEquals(48_000L, view.endSample)
    }
}
