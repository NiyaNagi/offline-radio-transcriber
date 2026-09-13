package org.ort.app.ui.data

import org.ort.pipeline.capture.ArchiveWriteRateForecast
import org.ort.pipeline.capture.OverAudioBudgetState
import java.util.Locale

/**
 * `Capture.dc.html` (N08, R-1036/R-1037, D39/D40): the merged Capture surface's own Storage row —
 * the over-audio budget's persistent warning (FR-STO-3e, AC-156/157/160) beside the continuous
 * archive's default-on state and monthly rate (FR-STO-3f, AC-158/159), both real, both recomputed
 * fresh every read, never a one-time event and never a fabricated figure (constitution I/VI).
 *
 * Deliberately its own small type family rather than importing
 * [org.ort.app.ui.recordings.OverAudioCardViewState]/[org.ort.app.ui.recordings.ArchiveCardViewState]
 * — `ui/recordings` is not this package's row to depend on (the same "duplicate the small mapping
 * rather than take a cross-package dependency" choice [LiveMonitorOversViewState]'s own file doc
 * comment already makes) — but the two screens read the *identical* real sources
 * ([OverAudioBudgetState], [ArchiveWriteRateForecast.State]) so they can never disagree about
 * whether the budget is exceeded or which of estimated/measured the rate is.
 */
public data class CaptureOverAudioViewState(
    public val usedBytes: Long,
    /** `null` = no budget set (FR-STO-3's own distinct third state — never confused with 0 or
     * "unlimited"). */
    public val budgetGb: Int?,
    public val fractionUsed: Float?,
    /** D40/AC-157: real, recomputed every read — never a one-time event. */
    public val exceeded: Boolean,
    /** AC-160: the exact copy already established on RC01's own budget card
     * (`RecordingsScreen.kt`'s `OverAudioBudgetRow`) — one warning vocabulary, never a second one
     * invented for this screen. Rendered as part of the ordinary flow, visible without a tap. */
    public val warningLabel: String,
)

public data class CaptureArchiveViewState(
    public val enabled: Boolean,
    public val usedBytes: Long,
    public val budgetGb: Int,
    public val fractionUsed: Float,
    /** "about 15 GB a month (estimated)" before anything has been measured this session, or the
     * real measured figure once [ArchiveWriteRateForecast] has one — [isMeasuredRate] is carried
     * alongside so a caller never has to parse this string to tell which it is (constitution VI:
     * never show an estimate as a measurement, or the reverse). */
    public val monthlyRateLabel: String,
    public val isMeasuredRate: Boolean,
)

public data class CaptureStorageViewState(
    public val overAudio: CaptureOverAudioViewState,
    public val archive: CaptureArchiveViewState,
) {
    public companion object {
        /** [CapturePolling]'s own honest pre-first-poll placeholder — every field zeroed/off, never
         * a claim about real usage before the first real read replaces it. */
        public val LOADING: CaptureStorageViewState = CaptureStorageViewState(
            overAudio = CaptureOverAudioViewState(
                usedBytes = 0L,
                budgetGb = null,
                fractionUsed = null,
                exceeded = false,
                warningLabel = "warns when full · never deleted without you",
            ),
            archive = CaptureArchiveViewState(
                enabled = true,
                usedBytes = 0L,
                budgetGb = DEFAULT_ARCHIVE_BUDGET_GB,
                fractionUsed = 0f,
                monthlyRateLabel = "about $ARCHIVE_ESTIMATED_MONTHLY_GB GB a month (estimated)",
                isMeasuredRate = false,
            ),
        )
    }
}

/** D39's own default archive budget (`ArchiveSettingsStore.DEFAULT_ARCHIVE_BUDGET_GB`), duplicated
 * as a plain constant rather than a `:pipeline` import purely for [CaptureStorageViewState.LOADING]'s
 * own inert placeholder — the first real poll always replaces it. */
private const val DEFAULT_ARCHIVE_BUDGET_GB: Int = 60

/** D39's own static figure — an estimate, not a measurement, until [ArchiveWriteRateForecast] has
 * real data for this session. Shared by [CaptureStorageViewState.LOADING] and [CaptureStorageMapper]
 * so the two can never quote a different placeholder figure. */
private const val ARCHIVE_ESTIMATED_MONTHLY_GB: Int = 15

/**
 * Pure builders (no `Context`, no I/O) — [CaptureStoragePolling] gathers every real input and calls
 * these. Mirrors [org.ort.app.ui.recordings.RecordingsViewStateMapper]'s own `overAudioCard`/
 * `archiveCard` shape exactly (confirmed by reading that file before writing this) so the two
 * screens can never quietly diverge on what "exceeded" or "estimated" means.
 */
public object CaptureStorageMapper {

    private const val BYTES_PER_GB: Double = 1_000_000_000.0

    public fun from(
        overAudioBudget: OverAudioBudgetState,
        archiveEnabled: Boolean,
        archiveBudgetGb: Int,
        archiveUsedBytes: Long,
        archiveRateState: ArchiveWriteRateForecast.State,
    ): CaptureStorageViewState = CaptureStorageViewState(
        overAudio = overAudio(overAudioBudget),
        archive = archive(archiveEnabled, archiveBudgetGb, archiveUsedBytes, archiveRateState),
    )

    /** FR-STO-3e/D40/AC-156/157/160: [OverAudioBudgetState] is already the real, recomputed-every-
     * read fact ([org.ort.pipeline.capture.overAudioBudgetState]'s own kdoc) — this only formats it,
     * never re-derives "exceeded" a second way. */
    public fun overAudio(budget: OverAudioBudgetState): CaptureOverAudioViewState {
        val budgetBytes = budget.budgetBytes
        return CaptureOverAudioViewState(
            usedBytes = budget.usedBytes,
            budgetGb = budgetBytes?.let { (it / BYTES_PER_GB).toInt() },
            fractionUsed = budgetBytes?.let { (budget.usedBytes.toFloat() / it.toFloat()).coerceIn(0f, 1f) },
            exceeded = budget.exceeded,
            warningLabel = if (budget.exceeded) {
                "Over budget · never deleted without you"
            } else {
                "warns when full · never deleted without you"
            },
        )
    }

    /** FR-STO-3f/D39/AC-158/159: [rateState] outranks [ARCHIVE_ESTIMATED_MONTHLY_GB]'s own static
     * figure the moment a real measurement exists (constitution VI — a measured rate outranks an
     * estimate; an estimate is never shown unlabelled as if it were one). */
    public fun archive(
        enabled: Boolean,
        budgetGb: Int,
        usedBytes: Long,
        rateState: ArchiveWriteRateForecast.State,
    ): CaptureArchiveViewState {
        val budgetBytes = budgetGb * BYTES_PER_GB
        val rateLabel = when (rateState) {
            ArchiveWriteRateForecast.State.NotYetMeasured ->
                "about $ARCHIVE_ESTIMATED_MONTHLY_GB GB a month (estimated)"
            is ArchiveWriteRateForecast.State.Measured ->
                "%.1f GB a month (measured)".format(Locale.ROOT, rateState.bytesPerMonth / BYTES_PER_GB)
        }
        return CaptureArchiveViewState(
            enabled = enabled,
            usedBytes = usedBytes,
            budgetGb = budgetGb,
            fractionUsed = if (budgetBytes > 0) (usedBytes / budgetBytes).toFloat().coerceIn(0f, 1f) else 0f,
            monthlyRateLabel = rateLabel,
            isMeasuredRate = rateState is ArchiveWriteRateForecast.State.Measured,
        )
    }
}
