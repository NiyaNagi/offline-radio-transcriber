package org.ort.app.ui.improve

import org.ort.pipeline.reprocess.ReprocessStatus

/**
 * R-091 (register, P12, FR-REP-1..11): view-states for `Improve`, `Improve-Select`,
 * `Improve-Running`, `Improve-Done`. Every count here is real — see [ImprovePolling] — because
 * P12 calls this "a headline capability", and a headline capability with fabricated numbers is
 * exactly what constitution I forbids.
 */
public data class ImproveGroupViewState(
    val id: String,
    val headline: String,
    val subLine: String,
    val overCount: Int,
    val transmissionIds: List<String>,
    /** R-142 (register): the real tier this group's sessions were captured at
     * ([org.ort.data.entity.SessionEntity.deviceTier]'s ordinal) — the one fact `Improve-Select`'s
     * per-pass sub-lines can honestly report about the group, without a tier-to-model-name table
     * this build does not have. */
    val tierOrdinal: Int,
)

public data class ImproveRootViewState(
    val totalOverCount: Int,
    val allTransmissionIds: List<String>,
    val currentTierLabel: String,
    val groups: List<ImproveGroupViewState>,
    val everythingElseCount: Int,
    /** register R-1067 round 2: non-null only when a real reprocess run finished while nobody was
     * on this screen to see it reach `Improve-Done` — built from the real, final
     * `org.ort.pipeline.reprocess.ReprocessRunSnapshot.Finished` WorkManager itself still carries
     * ([org.ort.pipeline.reprocess.ReprocessWorker]'s own stamped `WorkInfo.outputData`), never the
     * richer per-category summary (that is process-memory only and does not survive being away
     * when the run ended) — constitution I: a plain, real count, never a fabricated diff. */
    val justFinished: JustFinishedRun? = null,
)

/** See [ImproveRootViewState.justFinished]'s own doc comment. */
public data class JustFinishedRun(val doneCount: Int, val totalCount: Int)

public data class ImproveSelectViewState(
    val group: ImproveGroupViewState,
    /** `null` — no measured real-time factor exists yet this process, so a time estimate would be
     * fabricated (constitution I); the screen states that honestly instead of inventing one. */
    val estimatedSeconds: Long?,
    val correctionsToReapply: Int,
)

public data class ImproveRunningViewState(
    val headline: String,
    val doneCount: Int,
    val totalCount: Int,
    val paused: Boolean,
    /** FR-REP-6 (WP11d addendum, round 5): non-null exactly while `ReprocessStatus.state` reports
     * the engine's own capture-priority yield (`ReprocessStatus.State.Paused`) — distinct from
     * [paused], which is the operator's own Pause toggle. `FakeImproveRunner` never touches
     * `ReprocessStatus`, so this is `null` for every run that engine drives. */
    val autoPausedReason: String? = null,
    /** register R-1067 round 2 (coordinator item 2b): true exactly while the real
     * `org.ort.pipeline.reprocess.ReprocessRunSnapshot` observed for this run is `Waiting` —
     * `WorkInfo.State.ENQUEUED`, honestly ambiguous between "not yet picked up" and "a stopped
     * attempt WorkManager has requeued for retry" (see that class's own kdoc). The board must never
     * claim live progress or read as done while this is true. */
    val waitingToResume: Boolean = false,
)

public data class ImproveDoneViewState(
    val headline: String,
    val clearedCount: Int,
    /** R-143 (WP11d addendum, round 5): the real reprocess-run breakdown
     * (`ReprocessStatus.State.Done.summary`) once `RealImproveRunner` has driven a run — `null` for
     * `FakeImproveRunner`'s own run (no reprocessing engine touches it), in which case the screen
     * keeps its pre-existing honest "no reprocessing engine" note rather than showing zeroes that
     * would misrepresent "nothing changed" as a measured fact. */
    val summary: ReprocessStatus.Summary? = null,
)
