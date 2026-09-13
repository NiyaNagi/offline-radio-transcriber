package org.ort.app.ui.recordings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ort.pipeline.archive.ArchiveState
import org.ort.pipeline.archive.RecordingSessionSummary
import org.ort.pipeline.capture.ArchiveWriteRateForecast
import org.ort.pipeline.capture.OverAudioBudgetState
import org.ort.pipeline.capture.StorageAccounting
import org.ort.testing.Requirement

/**
 * `Recordings.dc.html` (RC01): [RecordingsViewStateMapper] is the pure decision behind
 * [RecordingsPolling.state] — every case here is a plain JVM test, no database or Android context.
 */
class RecordingsViewStateMapperTest {

    /** Every removal fact (archive and over-audio alike) bundled so [summary] itself stays under
     * detekt's `LongParameterList` — the same "bundle the request" reasoning [RecordingsBudgetInputs]
     * above exists for. */
    private data class Removal(
        val archiveState: ArchiveState = ArchiveState.NONE,
        val archiveRemovedAtMillis: Long? = null,
        val overAudioRemovedAtMillis: Long? = null,
    )

    /** [stationCount]/[gapCount] bundled for the same `LongParameterList` reason as [Removal]. */
    private data class Counts(val stationCount: Int = 0, val gapCount: Int = 0)

    private fun summary(
        id: String,
        startedAt: Long,
        endedAt: Long? = startedAt + 3_600_000L,
        overCount: Int = 1,
        failedCount: Int = 0,
        labelledCount: Int = 0,
        removal: Removal = Removal(),
        counts: Counts = Counts(),
    ) = RecordingSessionSummary(
        sessionId = id,
        startedAtMillis = startedAt,
        endedAtMillis = endedAt,
        overCount = overCount,
        failedCount = failedCount,
        labelledCount = labelledCount,
        stationCount = counts.stationCount,
        gapCount = counts.gapCount,
        overAudioRemovedAtMillis = removal.overAudioRemovedAtMillis,
        archiveState = removal.archiveState,
        archiveRemovedAtMillis = removal.archiveRemovedAtMillis,
    )

    private val emptyAccounting = StorageAccounting(
        audioBytes = 0L,
        modelBytes = 0L,
        recordBytes = 0L,
        lexiconBytes = 0L,
        bundledBytes = 0L,
        measuredAtMillis = 0L,
        archiveBytes = 0L,
    )

    private val defaultBudgetInputs = RecordingsBudgetInputs(
        overAudioBudget = OverAudioBudgetState(0L, null, exceeded = false),
        archiveEnabled = true,
        archiveBudgetGb = 60,
        archiveRateState = ArchiveWriteRateForecast.State.NotYetMeasured,
    )

    private fun map(
        summaries: List<RecordingSessionSummary>,
        accounting: StorageAccounting = emptyAccounting,
        budgetInputs: RecordingsBudgetInputs = defaultBudgetInputs,
        selectedFilter: RecordingsFilter = RecordingsFilter.ALL,
        liveSessionId: String? = null,
        nowMillis: Long = 100_000_000L,
    ) = RecordingsViewStateMapper.map(
        summaries = summaries,
        accounting = accounting,
        budgetInputs = budgetInputs,
        selectedFilter = selectedFilter,
        liveSessionId = liveSessionId,
        nowMillis = nowMillis,
    )

    @Test
    @Requirement("FR-STO-3")
    fun `sessions render newest first regardless of the order the summaries arrived in`() {
        val state = map(
            summaries = listOf(
                summary("old", startedAt = 1_000L),
                summary("new", startedAt = 9_000L),
            ),
        )
        assertEquals(listOf("new", "old"), state.sessions.map { it.id })
    }

    @Test
    @Requirement("FR-OBS-4")
    fun `a live session is labelled Tonight and carries a capturing badge over any other status badge`() {
        val state = map(
            summaries = listOf(summary("live", startedAt = 1_000L, endedAt = null, failedCount = 3)),
            liveSessionId = "live",
        )
        val row = state.sessions.single()
        assertEquals("Tonight", row.label)
        assertTrue(row.isLive)
        assertEquals(RecordingBadgeKind.CAPTURING, row.badges.first().kind)
    }

    @Test
    @Requirement("FR-STO-3")
    fun `a failed count beats a labelled count for the row's own status badge, matching the board`() {
        val state = map(summaries = listOf(summary("s", startedAt = 1_000L, failedCount = 2, labelledCount = 5)))
        val row = state.sessions.single()
        assertEquals(RecordingBadgeKind.FAILED, row.badges.first().kind)
        assertEquals("2 failed", row.badges.first().label)
    }

    @Test
    @Requirement("D39", "P9")
    fun `an archive removed session carries its own removal date, distinct from over-audio removal`() {
        val state = map(
            summaries = listOf(
                summary(
                    "s",
                    startedAt = 1_000L,
                    removal = Removal(
                        archiveState = ArchiveState.REMOVED,
                        archiveRemovedAtMillis = 1_694_476_800_000L, // 2023-09-12
                        overAudioRemovedAtMillis = 1_694_563_200_000L, // 2023-09-13
                    ),
                ),
            ),
        )
        val row = state.sessions.single()
        val archiveBadge = row.badges.single { it.kind == RecordingBadgeKind.ARCHIVE_REMOVED }
        val overAudioBadge = row.badges.single { it.kind == RecordingBadgeKind.OVER_AUDIO_REMOVED }
        assertTrue("expected the archive badge's own date", archiveBadge.label.contains("12 Sep"))
        assertTrue("expected the over-audio badge's own, different date", overAudioBadge.label.contains("13 Sep"))
        assertTrue(archiveBadge.label != overAudioBadge.label)
    }

    @Test
    @Requirement("FR-STO-3d")
    fun `a session whose archive was never enabled carries no archive badge, never a fabricated one`() {
        val state = map(
            summaries = listOf(summary("s", startedAt = 1_000L, removal = Removal(archiveState = ArchiveState.NONE))),
        )
        assertTrue(
            state.sessions.single().badges.none {
                it.kind == RecordingBadgeKind.ARCHIVE_KEPT || it.kind == RecordingBadgeKind.ARCHIVE_REMOVED
            },
        )
    }

    @Test
    @Requirement("D40", "AC-160")
    fun `the over-audio card reports exceeded from the real budget state, and the fraction from the real budget`() {
        val exceededState = map(
            summaries = emptyList(),
            accounting = emptyAccounting.copy(audioBytes = 9_000_000_000L),
            budgetInputs = defaultBudgetInputs.copy(
                overAudioBudget = OverAudioBudgetState(9_000_000_000L, 8_000_000_000L, exceeded = true),
            ),
        )
        assertTrue(exceededState.budgets.overAudio.exceeded)
        assertEquals(8, exceededState.budgets.overAudio.budgetGb)
        assertTrue(exceededState.budgets.overAudio.fractionUsed!! >= 1f)

        val noBudgetState = map(
            summaries = emptyList(),
            budgetInputs = defaultBudgetInputs.copy(
                overAudioBudget = OverAudioBudgetState(1_000L, null, exceeded = false),
            ),
        )
        assertNull(noBudgetState.budgets.overAudio.budgetGb)
        assertNull(noBudgetState.budgets.overAudio.fractionUsed)
    }

    @Test
    @Requirement("D39", "FR-STO-3f", "constitution VI")
    fun `the archive rate is labelled estimated until a real measurement exists, then labelled measured`() {
        val estimated = map(
            summaries = emptyList(),
            budgetInputs = defaultBudgetInputs.copy(archiveRateState = ArchiveWriteRateForecast.State.NotYetMeasured),
        )
        assertTrue(estimated.budgets.archive.monthlyRateLabel.contains("estimated"))
        assertTrue(estimated.budgets.archive.monthlyRateLabel.contains("15 GB"))
        assertEquals(false, estimated.budgets.archive.isMeasuredRate)

        val measured = map(
            summaries = emptyList(),
            budgetInputs = defaultBudgetInputs.copy(
                archiveRateState = ArchiveWriteRateForecast.State.Measured(
                    bytesPerHour = 1_000_000.0,
                    bytesPerMonth = 20_000_000_000.0,
                ),
            ),
        )
        assertTrue(measured.budgets.archive.monthlyRateLabel.contains("measured"))
        assertTrue(measured.budgets.archive.monthlyRateLabel.contains("20.0 GB"))
        assertEquals(true, measured.budgets.archive.isMeasuredRate)
    }

    @Test
    @Requirement("RC01")
    fun `chip counts are computed against every session regardless of which filter is selected`() {
        val summaries = listOf(
            summary("a", startedAt = 1_000L, failedCount = 1),
            summary("b", startedAt = 2_000L, labelledCount = 1),
            summary(
                "c",
                startedAt = 3_000L,
                removal = Removal(archiveState = ArchiveState.REMOVED, archiveRemovedAtMillis = 1L),
            ),
        )
        val allSelected = map(summaries = summaries, selectedFilter = RecordingsFilter.ALL)
        val failuresSelected = map(summaries = summaries, selectedFilter = RecordingsFilter.HAS_FAILURES)

        val expectedCounts = mapOf(
            RecordingsFilter.HAS_FAILURES to 1,
            RecordingsFilter.LABELLED to 1,
            RecordingsFilter.ARCHIVE_REMOVED to 1,
        )
        for (chip in allSelected.filters.filter { it.count != null }) {
            assertEquals(expectedCounts.getValue(chip.filter), chip.count)
        }
        for (chip in failuresSelected.filters.filter { it.count != null }) {
            assertEquals(expectedCounts.getValue(chip.filter), chip.count)
        }
        assertEquals(1, failuresSelected.sessions.size)
        assertEquals(3, allSelected.sessions.size)
    }

    @Test
    @Requirement("RC01")
    fun `the headline names the real session count, over count, and earliest session's date`() {
        val state = map(
            summaries = listOf(
                summary("a", startedAt = 1_694_476_800_000L, overCount = 5), // 2023-09-12
                summary("b", startedAt = 1_690_000_000_000L, overCount = 7), // earlier
            ),
        )
        assertTrue(state.headline.contains("2 sessions"))
        assertTrue(state.headline.contains("12 overs"))
    }

    @Test
    @Requirement("RC01", "FR-RUN-12")
    fun `the row carries the real station and gap counts, distinct from the over count`() {
        val state = map(
            summaries = listOf(
                summary("a", startedAt = 1_000L, overCount = 41, counts = Counts(stationCount = 9, gapCount = 1)),
            ),
        )
        val row = state.sessions.single()
        assertEquals(41, row.overCount)
        assertEquals(9, row.stationCount)
        assertEquals(1, row.gapCount)
    }

    @Test
    @Requirement("RC01")
    fun `no sessions at all reports an honest headline, never a fabricated count`() {
        val state = map(summaries = emptyList())
        assertEquals("No sessions recorded yet", state.headline)
        assertTrue(state.sessions.isEmpty())
    }
}
