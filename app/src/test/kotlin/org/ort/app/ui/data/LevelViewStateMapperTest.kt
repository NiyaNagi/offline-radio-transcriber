package org.ort.app.ui.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.pipeline.capture.LevelStatus
import org.ort.testing.Requirement

/** `Level-Meter.dc.html`'s facts, pure (ui-conformance-plan WP4, R-039, R-112). */
class LevelViewStateMapperTest {

    @Test
    @Requirement("R-039")
    fun `R_039 NotMeasured renders the honest failed reason, never fabricated bars`() {
        val view = LevelViewStateMapper.from(LevelStatus.State.NotMeasured, emptyList(), "USB Audio Device")

        assertNull(view.peakDbfsLabel)
        assertTrue(view.historyDbfs.isEmpty())
        assertTrue(view.notMeasuredReason != null)
    }

    @Test
    @Requirement("R-112")
    fun `R_112 a measured reading carries peak RMS-derived headroom and the real history through`() {
        val history = listOf(-40f, -20f, -14f)
        val view = LevelViewStateMapper.from(
            level = LevelStatus.State.Measured(
                peakDbfs = -14f,
                rmsDbfs = -20f,
                noiseFloorDbfs = -58f,
                clipped = false,
                clipCountLastSecond = 0,
                sampleRateHz = 16_000,
                updatedAtMillis = 0L,
            ),
            history = history,
            inputLabel = "USB Audio Device",
        )

        assertEquals("-14 dBFS", view.peakDbfsLabel)
        assertEquals("-58 dBFS", view.noiseFloorDbfsLabel)
        assertEquals(-58f, view.noiseFloorDbfsRaw)
        assertEquals("14 dB", view.headroomLabel)
        assertEquals(history, view.historyDbfs)
        assertNull(view.notMeasuredReason)
    }

    @Test
    fun `a noise floor not yet tracked renders honestly null, never a fabricated figure`() {
        val view = LevelViewStateMapper.from(
            level = LevelStatus.State.Measured(
                peakDbfs = -14f,
                rmsDbfs = -20f,
                noiseFloorDbfs = null,
                clipped = false,
                clipCountLastSecond = 0,
                sampleRateHz = 16_000,
                updatedAtMillis = 0L,
            ),
            history = emptyList(),
            inputLabel = "USB Audio Device",
        )

        assertNull(view.noiseFloorDbfsLabel)
        assertNull(view.noiseFloorDbfsRaw)
    }

    @Test
    fun `clipCountLastSecond is carried through as the honest per-second count, not a session total`() {
        val view = LevelViewStateMapper.from(
            level = LevelStatus.State.Measured(
                peakDbfs = 0f,
                rmsDbfs = -4f,
                noiseFloorDbfs = -58f,
                clipped = true,
                clipCountLastSecond = 7,
                sampleRateHz = 16_000,
                updatedAtMillis = 0L,
            ),
            history = emptyList(),
            inputLabel = "USB Audio Device",
        )

        assertEquals("7", view.clippedLastSecondLabel)
    }
}
