package org.ort.app.ui.improve

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
)

public data class ImproveDoneViewState(val headline: String, val clearedCount: Int)
