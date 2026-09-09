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
    @Requirement("R-419")
    fun `R_419 clippedThisSessionLabel reads the real session total, not clipCountLastSecond`() {
        val view = LevelViewStateMapper.from(
            level = LevelStatus.State.Measured(
                peakDbfs = 0f,
                rmsDbfs = -4f,
                noiseFloorDbfs = -58f,
                clipped = true,
                // Deliberately different from clippedSamplesThisSession below, so a test that
                // read the wrong field would fail.
                clipCountLastSecond = 7,
                sampleRateHz = 16_000,
                updatedAtMillis = 0L,
            ),
            history = emptyList(),
            inputLabel = "USB Audio Device",
            clippedSamplesThisSession = 340L,
        )

        assertEquals("340", view.clippedThisSessionLabel)
    }

    @Test
    @Requirement("R-419")
    fun `R_419 clippedThisSessionLabel is honestly zero before any session has clipped`() {
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
            history = emptyList(),
            inputLabel = "USB Audio Device",
        )

        assertEquals("0", view.clippedThisSessionLabel)
    }

    @Test
    @Requirement("R-175")
    fun `R_175 weakestOverLabel is carried straight through, honestly null when the caller has none`() {
        val withLabel = LevelViewStateMapper.from(
            level = measured(peakDbfs = -14f),
            history = emptyList(),
            inputLabel = "USB Audio Device",
            weakestOverLabel = "S2",
        )
        assertEquals("S2", withLabel.weakestOverLabel)

        val withoutLabel = LevelViewStateMapper.from(
            level = measured(peakDbfs = -14f),
            history = emptyList(),
            inputLabel = "USB Audio Device",
        )
        assertNull(withoutLabel.weakestOverLabel)
    }

    @Test
    @Requirement("R-175")
    fun `R_175 the band-state sentence reads in-band, above, below or clipping per the real peak`() {
        assertEquals("In the band. Nothing to adjust.", mapped(measured(peakDbfs = -14f)).bandStateSentence)
        assertEquals("Above the band. Turn the volume down.", mapped(measured(peakDbfs = -4f)).bandStateSentence)
        assertEquals("Below the band. Turn the volume up.", mapped(measured(peakDbfs = -40f)).bandStateSentence)
        assertEquals(
            "Clipping. Turn the volume down.",
            mapped(measured(peakDbfs = 0f, clipped = true)).bandStateSentence,
        )
    }

    private fun measured(peakDbfs: Float, clipped: Boolean = false): LevelStatus.State.Measured =
        LevelStatus.State.Measured(
            peakDbfs = peakDbfs,
            rmsDbfs = peakDbfs - 6f,
            noiseFloorDbfs = -58f,
            clipped = clipped,
            clipCountLastSecond = if (clipped) 3 else 0,
            sampleRateHz = 16_000,
            updatedAtMillis = 0L,
        )

    private fun mapped(level: LevelStatus.State.Measured): LevelViewState =
        LevelViewStateMapper.from(level = level, history = emptyList(), inputLabel = "USB Audio Device")
}
