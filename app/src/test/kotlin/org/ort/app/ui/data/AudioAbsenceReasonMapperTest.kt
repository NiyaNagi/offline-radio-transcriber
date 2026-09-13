package org.ort.app.ui.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * This task (constitution I, III): [AudioAbsenceReasonMapper.from]'s pure decision — no `Context`,
 * no I/O — see [AudioAbsenceReason]'s own kdoc for why [AudioAbsenceReason.PrunedByRetentionBudget]
 * and [AudioAbsenceReason.NeverRetained] are both real, modelled cases this mapper is never handed a
 * real signal to construct today (D40/register R-1037 forbid any automatic over-audio deletion, and
 * no `:data` table ever records "this over's audio was, by design, never kept" — see that type's
 * own kdoc, "coordinator review", for the full account) — both a session that could not be read at
 * all and one that was read but simply recorded no removal read [AudioAbsenceReason.Unknown].
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
    fun `a session that recorded no removal at all reads as Unknown, never a guessed NeverRetained`() {
        // Coordinator review (halt): no explicit `:data` record backs "never retained" — an
        // unrecorded absence is honestly Unknown, indistinguishable here from an unrecorded
        // automatic prune, a lost file, or a build predating a column this schema does not have.
        val reason = AudioAbsenceReasonMapper.from(
            hasAudio = false,
            sessionKnown = true,
            overAudioRemovedAtMillis = null,
        )

        assertEquals(AudioAbsenceReason.Unknown, reason)
    }

    @Test
    fun `a session that could not be read at all also reads as Unknown, the identical honest admission`() {
        val reason = AudioAbsenceReasonMapper.from(
            hasAudio = false,
            sessionKnown = false,
            overAudioRemovedAtMillis = null,
        )

        assertEquals(AudioAbsenceReason.Unknown, reason)
    }

    @Test
    fun `NeverRetained is never constructed by this mapper against any input`() {
        // The sealed case is kept only for a genuine future explicit record (this type's own kdoc)
        // — every combination this mapper can actually be called with today must avoid it.
        val everyRealCombination = listOf(
            AudioAbsenceReasonMapper.from(hasAudio = false, sessionKnown = true, overAudioRemovedAtMillis = null),
            AudioAbsenceReasonMapper.from(hasAudio = false, sessionKnown = false, overAudioRemovedAtMillis = null),
            AudioAbsenceReasonMapper.from(
                hasAudio = false,
                sessionKnown = true,
                overAudioRemovedAtMillis = epochMillisFor(2026, 8, 8),
            ),
        )

        assertEquals(false, everyRealCombination.any { it is AudioAbsenceReason.NeverRetained })
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
