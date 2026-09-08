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
)

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
