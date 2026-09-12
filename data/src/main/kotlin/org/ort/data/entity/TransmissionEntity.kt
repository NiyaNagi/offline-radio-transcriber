package org.ort.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.ort.core.AttributionState
import org.ort.core.Tier
import org.ort.core.TransmissionState

/**
 * Functional spec §8 `Transmission`, `processingState` taking its values **only** from
 * [TransmissionState] (FR-RUN-7). `STALE` is deliberately absent — it is derived from stored
 * pass fingerprints, not stored (technical design §7.2) — but [isReprocessCandidate] is the
 * flag FR-RUN-4 sets when shedding or a fingerprint mismatch marks the row for reprocessing.
 *
 * [processedTier] (schema v4, register R-204 follow-up, FR-REP-2/9; register R-1033 amendment): the
 * tier the most recent completed-or-rejected Pass B/C run actually ran this transmission at —
 * `null` until a pass first finishes for it. **Since R-1033, this includes live capture, not only
 * reprocessing**: [org.ort.pipeline.passb.DataPassBResultSink.record] — the one sink every real
 * `PassB` (live or reprocessed, [org.ort.pipeline.passb.PassBFactory.create]'s own `sink`) writes
 * through — stamps it via [org.ort.data.dao.TransmissionDao.setProcessedTier] on both a completed
 * and a rejected outcome (both mean "Pass B genuinely ran at this tier"), never on a failed one
 * (which means it did not). Before R-1033, live capture always ran at `Tier.T0` and this column
 * stayed `null` until an explicit reprocess touched the record — an unrecorded live tier, the same
 * provenance hole [executionProvider] had (constitution VI: "no number without ... provenance");
 * a transcript whose tier is unknown cannot be compared with one whose tier is known.
 * [org.ort.pipeline.reprocess.ReprocessRunner] also calls [org.ort.data.dao.TransmissionDao.setProcessedTier]
 * itself, redundantly (the identical value, from the same [org.ort.core.PassFingerprint.tier]) —
 * harmless, and left as-is rather than removed by a change outside this package's ownership. This
 * is what lets a read path answer "which records still need improving" without re-offering one a
 * reprocess already brought current ([org.ort.data.dao.TransmissionDao.idsBelowProcessedTier]).
 *
 * [rigStateChangedMidTransmission] (schema v9, WPC3, FR-RIG-6): the rig reported a different
 * reading before this transmission ended than it had at the start -- either a genuine
 * mid-transmission frequency/squelch change, or (D23) both bands of a dual-receive rig were open
 * at the transmission's start and [frequencyHz] is an ambiguous pick between them
 * (`org.ort.pipeline.rig.RigSupervisor.bandAtTransmissionStart`'s own kdoc states the exact rule).
 * Set once, at persist time, from `org.ort.pipeline.rig.FrequencyReading.changedDuringTransmission`
 * -- never revisited afterwards (constitution III). `false` by default, meaning "no rig-state
 * change was ever recorded" -- never conflated with "no rig was connected", which
 * [frequencyProvenance] already states separately (constitution I).
 */
@Entity(
    tableName = "transmission",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("sessionId", "samplePosition"),
        Index("startedAtUtc"),
        Index("attributionState"),
        Index("isReprocessCandidate"),
    ],
)
public data class TransmissionEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val threadId: String?,
    val startedAtUtc: Long,
    val endedAtUtc: Long?,
    val durationMs: Long,
    val audioFormat: String,
    val preRollMs: Int,
    val postRollMs: Int,
    val frequencyHz: Long?,
    val frequencyProvenance: String,
    val mode: String?,
    val signalStrength: Double?,
    val channelName: String?,
    val voiceprintId: String?,
    val attributionState: AttributionState,
    val stationId: String?,
    val attributionConfidence: Double?,
    val attributionSourceTransmissionId: String?,
    val corrected: Boolean = false,
    val processingState: TransmissionState,
    val rejectionReason: String?,
    // technical design §8: authoritative timeline and reprocessing provenance.
    val samplePosition: Long,
    val monotonicStartNanos: Long,
    val utcOffsetMinutes: Int,
    val calibrationId: String?,
    val enhancementApplied: List<String> = emptyList(),
    /**
     * Register R-1032 (constitution VI: "no number without ... execution provider"): the real
     * execution provider ([org.ort.core.PassFingerprint.provider], e.g. `"cpu"`) the most recent
     * Pass B/C run for this transmission actually ran on — `null` until a real pass has ever run
     * for it (the segment-persist insert always writes `null` here; nothing else has happened yet).
     * [org.ort.data.dao.TransmissionDao.setExecutionProvider] is the only writer, called by
     * [org.ort.pipeline.passb.DataPassBResultSink.record] for every outcome, live capture and
     * reprocessing alike — before that call existed, [PassFingerprint.provider] was computed and
     * thrown away, and this column silently stayed `null` forever on every real device.
     */
    val executionProvider: String?,
    val isReprocessCandidate: Boolean = false,
    val processedTier: Tier? = null,
    val rigStateChangedMidTransmission: Boolean = false,
) {
    /**
     * The derived on-disk path for this transmission's audio (technical design §12.2): paths
     * are computed, never stored, so a row and its file cannot disagree about *where* the file
     * should be — only about whether it exists (FR-AST-8).
     */
    public fun audioPath(): String = "audio/$sessionId/$id.flac"
}
