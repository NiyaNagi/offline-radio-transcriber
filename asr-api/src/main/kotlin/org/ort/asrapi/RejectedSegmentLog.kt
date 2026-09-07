package org.ort.asrapi

import org.ort.asrapi.rules.RejectionRuleId
import org.ort.core.TransmissionId

/** One rejected segment, retained so it can be surfaced behind a UI filter (FR-ASR-6 -> AC-8). */
public data class RejectedSegmentRecord(
    val transmissionId: TransmissionId,
    val rule: RejectionRuleId,
    val detail: String,
    val audio: FloatArray,
)

/**
 * An in-memory stand-in for the `:data`-level rejected-segment query (that table and its Room
 * DAO are P5/`:data` territory, out of this prompt's module ownership). This exists to prove,
 * at the domain layer this prompt does own, the shape AC-8 requires: a rejection is a **result**
 * that is appended, never dropped, and remains queryable by rule — "reachable behind a filter",
 * not hidden. `:pipeline`/`:data` wire the real persisted equivalent.
 */
public class RejectedSegmentLog {
    private val records = mutableListOf<RejectedSegmentRecord>()

    public fun record(transmissionId: TransmissionId, outcome: PassBOutcome.Rejected, audio: FloatArray) {
        records += RejectedSegmentRecord(transmissionId, outcome.rule, outcome.detail, audio)
    }

    public val all: List<RejectedSegmentRecord> get() = records.toList()

    /** The UI filter AC-8 requires: rejected segments are reachable, not hidden. */
    public fun byRule(rule: RejectionRuleId): List<RejectedSegmentRecord> = records.filter { it.rule == rule }
}
