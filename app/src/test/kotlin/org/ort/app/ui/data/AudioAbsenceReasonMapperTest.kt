package org.ort.app.ui.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * This task (constitution I, III): [AudioAbsenceReasonMapper.from]'s pure decision — no `Context`,
 * no I/O — see [AudioAbsenceReason]'s own kdoc for why [AudioAbsenceReason.PrunedByRetentionBudget]
 * is a real, modelled case that this mapper is never handed a real signal to construct today
 * (D40/register R-1037 forbid any automatic over-audio deletion), and why a session that could not
 * be read reads as [AudioAbsenceReason.Unknown], never a guessed [AudioAbsenceReason.NeverRetained].
 */
class AudioAbsenceReasonMapperTest {

    @Test
    fun `hasAudio true never needs a reason`() {
        val reason = AudioAbsenceReasonMapper.from(
            hasAudio = true,
            sessionKnown = true,
            overAudioRemovedAtMillis = 12_345L,
        )

        assertEquals(null, reason)
    }

    @Test
    fun `an operator removal timestamp reads as RemovedByOperator with its real date`() {
        val removedAt = epochMillisFor(2026, 8, 8)

        val reason = AudioAbsenceReasonMapper.from(
            hasAudio = false,
            sessionKnown = true,
            overAudioRemovedAtMillis = removedAt,
        )

        assertEquals(AudioAbsenceReason.RemovedByOperator("8 Aug"), reason)
    }

    @Test
    fun `no removal timestamp at all reads as NeverRetained, never a guessed removal`() {
        val reason = AudioAbsenceReasonMapper.from(
            hasAudio = false,
            sessionKnown = true,
            overAudioRemovedAtMillis = null,
        )

        assertEquals(AudioAbsenceReason.NeverRetained, reason)
    }

    @Test
    fun `a session that could not be read reads as Unknown, never NeverRetained`() {
        val reason = AudioAbsenceReasonMapper.from(
            hasAudio = false,
            sessionKnown = false,
            overAudioRemovedAtMillis = null,
        )

        assertEquals(AudioAbsenceReason.Unknown, reason)
    }

    @Test
    fun `operator removal always wins over a prunedByRetentionBudget signal, when both are somehow set`() {
        val reason = AudioAbsenceReasonMapper.from(
            hasAudio = false,
            sessionKnown = true,
            overAudioRemovedAtMillis = epochMillisFor(2026, 8, 8),
            prunedByRetentionBudgetAtMillis = epochMillisFor(2026, 8, 1),
        )

        assertEquals(AudioAbsenceReason.RemovedByOperator("8 Aug"), reason)
    }

    @Test
    fun `a real prunedByRetentionBudget signal, when one is ever supplied, reads with its own date`() {
        val reason = AudioAbsenceReasonMapper.from(
            hasAudio = false,
            sessionKnown = true,
            overAudioRemovedAtMillis = null,
            prunedByRetentionBudgetAtMillis = epochMillisFor(2026, 8, 8),
        )

        assertEquals(AudioAbsenceReason.PrunedByRetentionBudget("8 Aug"), reason)
    }

    private fun epochMillisFor(year: Int, month: Int, day: Int): Long =
        java.time.LocalDate.of(year, month, day).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
}
