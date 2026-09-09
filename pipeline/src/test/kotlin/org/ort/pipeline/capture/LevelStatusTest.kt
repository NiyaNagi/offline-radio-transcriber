package org.ort.pipeline.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

/**
 * register R-112: before this existed, `:pipeline` published no level signal at all — the level
 * meter (`Level-Meter.dc.html`, `Capture-Status`'s Level row) could only ever read the honest
 * "not measured" state. Mirrors [ThermalStatusTest]'s own style: a plain process-wide holder,
 * never optimistic about what has not actually been measured yet.
 */
class LevelStatusTest {

    @BeforeEach
    fun reset() {
        LevelStatus.reset()
    }

    @Test
    @Requirement("R-112", "FR-CAP-3")
    fun `R_112_level_status_is_not_measured_until_the_first_frame`() {
        assertEquals(LevelStatus.State.NotMeasured, LevelStatus.state)
        assertTrue(LevelStatus.peakHistoryDbfs.isEmpty())
    }

    @Test
    @Requirement("R-112", "FR-CAP-3")
    fun `R_112_update_publishes_a_measured_snapshot_and_its_history`() {
        val measured = LevelStatus.State.Measured(
            peakDbfs = -14f,
            rmsDbfs = -18f,
            noiseFloorDbfs = -58f,
            clipped = false,
            clipCountLastSecond = 0,
            sampleRateHz = 48_000,
            updatedAtMillis = 1_000L,
        )
        LevelStatus.update(measured, peakHistoryDbfs = listOf(-40f, -30f, -14f))

        assertEquals(measured, LevelStatus.state)
        assertEquals(listOf(-40f, -30f, -14f), LevelStatus.peakHistoryDbfs)
    }

    @Test
    @Requirement("R-112")
    fun `R_112_reset_returns_to_the_honest_not_yet_measured_default`() {
        LevelStatus.update(
            LevelStatus.State.Measured(0f, -4f, -55f, clipped = true, clipCountLastSecond = 12, 48_000, 1_000L),
            peakHistoryDbfs = listOf(0f),
        )
        LevelStatus.reset()

        assertEquals(LevelStatus.State.NotMeasured, LevelStatus.state)
        assertTrue(LevelStatus.peakHistoryDbfs.isEmpty())
    }

    @Test
    @Requirement("R-419")
    fun `R_419_clippedSamplesThisSession is 0 before any frame, republishes the total, resets with the rest`() {
        assertEquals(0L, LevelStatus.clippedSamplesThisSession)

        LevelStatus.recordClippedSamplesThisSession(42L)
        assertEquals(42L, LevelStatus.clippedSamplesThisSession)

        LevelStatus.reset()
        assertEquals(0L, LevelStatus.clippedSamplesThisSession)
    }
}
