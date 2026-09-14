package org.ort.app.ui.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ort.pipeline.capture.ArchiveWriteRateForecast
import org.ort.pipeline.capture.OverAudioBudgetState
import org.ort.testing.Requirement

/**
 * `Capture.dc.html` (N08): [CaptureStorageMapper] is the pure decision behind the merged surface's
 * own Storage row — D40/FR-STO-3e/AC-156/AC-157/AC-160 (the over-audio budget's persistent warning)
 * and D39/FR-STO-3f/AC-158/AC-159 (the continuous archive's default-on state and monthly rate,
 * measured-or-estimated). Every case here is a plain JVM test, no database or Android context —
 * the same shape [org.ort.app.ui.recordings.RecordingsViewStateMapperTest] already proves this
 * exact pair of facts with (RC01) — N08 reads the identical real inputs
 * ([OverAudioBudgetState], [ArchiveWriteRateForecast.State]) rather than inventing a second
 * computation of either.
 */
class CaptureStorageViewStateTest {

    @Test
    @Requirement("D40", "FR-STO-3e", "AC-156", "AC-157", "AC-160")
    fun `the over-audio warning is real and persistent, not a one-time event, and carries the exact reused label`() {
        val exceeded = CaptureStorageMapper.overAudio(
            OverAudioBudgetState(9_000_000_000L, 8_000_000_000L, exceeded = true),
        )
        assertTrue(exceeded.exceeded)
        assertEquals(8, exceeded.budgetGb)
        assertTrue(exceeded.fractionUsed!! >= 1f)
        // AC-160: the exact copy already established on RC01's own budget card
        // (`RecordingsScreen.kt`'s `OverAudioBudgetRow`) — one warning vocabulary, never a second.
        assertEquals("Over budget · never deleted without you", exceeded.warningLabel)

        val nominal = CaptureStorageMapper.overAudio(OverAudioBudgetState(1_000L, 8_000_000_000L, exceeded = false))
        assertFalse(nominal.exceeded)
        assertEquals("warns when full · never deleted without you", nominal.warningLabel)
    }

    @Test
    @Requirement("D40", "FR-STO-3e")
    fun `no budget set is a real, distinct third state - never a fabricated fraction or budget figure`() {
        val noBudget = CaptureStorageMapper.overAudio(OverAudioBudgetState(1_000L, null, exceeded = false))
        assertNull(noBudget.budgetGb)
        assertNull(noBudget.fractionUsed)
        assertFalse(noBudget.exceeded)
    }

    @Test
    @Requirement("D39", "FR-STO-3f", "AC-158", "AC-159", "constitution VI")
    fun `the archive rate is labelled estimated until a real measurement exists, then labelled measured`() {
        val estimated = CaptureStorageMapper.archive(
            enabled = true,
            budgetGb = 60,
            usedBytes = 11_800_000_000L,
            rateState = ArchiveWriteRateForecast.State.NotYetMeasured,
        )
        assertTrue(estimated.monthlyRateLabel.contains("estimated"))
        assertTrue(estimated.monthlyRateLabel.contains("15 GB"))
        assertFalse(estimated.isMeasuredRate)

        val measured = CaptureStorageMapper.archive(
            enabled = true,
            budgetGb = 60,
            usedBytes = 11_800_000_000L,
            rateState = ArchiveWriteRateForecast.State.Measured(
                bytesPerHour = 1_000_000.0,
                bytesPerMonth = 20_000_000_000.0,
            ),
        )
        assertTrue(measured.monthlyRateLabel.contains("measured"))
        assertTrue(measured.monthlyRateLabel.contains("20.0 GB"))
        assertTrue(measured.isMeasuredRate)
    }

    @Test
    @Requirement("D39", "FR-STO-3f", "AC-158", "AC-159")
    fun `the archive disclosure states its real on-off state and usage, beside where the control reads it`() {
        val on = CaptureStorageMapper.archive(
            enabled = true,
            budgetGb = 60,
            usedBytes = 11_800_000_000L,
            rateState = ArchiveWriteRateForecast.State.NotYetMeasured,
        )
        assertTrue(on.enabled)
        assertEquals(11_800_000_000L, on.usedBytes)
        assertEquals(60, on.budgetGb)
        assertTrue(on.fractionUsed > 0f)

        val off = CaptureStorageMapper.archive(
            enabled = false,
            budgetGb = 60,
            usedBytes = 0L,
            rateState = ArchiveWriteRateForecast.State.NotYetMeasured,
        )
        assertFalse(off.enabled)
    }

    @Test
    @Requirement("D40", "D39", "FR-STO-3e", "FR-STO-3f")
    fun `from combines both real facts into one storage view-state, never a partial one`() {
        val state = CaptureStorageMapper.from(
            overAudioBudget = OverAudioBudgetState(9_000_000_000L, 8_000_000_000L, exceeded = true),
            archiveEnabled = true,
            archiveBudgetGb = 60,
            archiveUsedBytes = 11_800_000_000L,
            archiveRateState = ArchiveWriteRateForecast.State.NotYetMeasured,
        )
        assertTrue(state.overAudio.exceeded)
        assertEquals(60, state.archive.budgetGb)
    }
}
