package org.ort.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.ort.core.AttributionState
import org.ort.core.Tier
import org.ort.core.TransmissionState
import org.ort.core.capture.VadDetectorKind
import org.ort.core.capture.conformsToFrSeg1

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
 *
 * [vadDetector] (schema v14, FR-SEG-10, register R-1054, AC-162): which voice-activity detector
 * actually cut *this* transmission's boundaries -- [VadDetectorKind.SILERO]/`.TEN_VAD` when it was
 * one FR-SEG-1 names, [VadDetectorKind.ENERGY] for the RMS-energy fallback
 * (`org.ort.pipeline.capture.EnergyVadModel`), [VadDetectorKind.UNKNOWN] only for a pre-v14 row
 * (constitution I: never a fabricated `SILERO`). Set once, at persist time, by
 * `org.ort.pipeline.capture.RealSegmentSink` from the same detector its session's `Segmenter` is
 * actually running -- never re-derived later. [conformsToFrSeg1] is the one place that reads it to
 * answer "does this boundary conform to FR-SEG-1", so UI and export never re-derive that rule a
 * second, possibly-inconsistent way. [vadDetectorVersion] carries the model's own version string
 * when one is known -- `null` today for every detector (neither the Silero binding nor the energy
 * stand-in currently reports one anywhere this column could read from), never a guessed value.
 * [rigSquelchFusionApplied] (FR-SEG-5): whether rig squelch fusion actually gated this
 * transmission's boundaries -- `false` unconditionally today, because FR-SEG-5 fusion is not yet
 * built anywhere in `:pipeline` (confirmed by search: no segmentation code reads squelch state);
 * `false` is therefore the honest answer, not a placeholder guess, until a real fusion decision
 * exists to report from.
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
    val vadDetector: VadDetectorKind = VadDetectorKind.UNKNOWN,
    val vadDetectorVersion: String? = null,
    val rigSquelchFusionApplied: Boolean = false,
    /**
     * [threadJoinReason] (schema v15, register R-1098, FR-SPK-5, constitution I): the real
     * [ThreadJoinReason] [org.ort.pipeline.threading.ThreadGrouper.decide] actually returned for
     * this transmission's own threading decision — see [ThreadJoinReason]'s own doc comment for why
     * a machine conclusion the operator sees on every Log screen used to have no durable trace of
     * *why*. Set once, at persist time, by
     * [org.ort.pipeline.threading.RoomThreadRepository.startThread]/`.appendToThread` — never
     * revisited afterwards, the same "write once" discipline [vadDetector] and
     * [rigStateChangedMidTransmission] already use. `null` for a pre-v15 row (never fabricated) and
     * for any transmission threading has not yet run for — never conflated with a real, computed
     * reason.
     */
    val threadJoinReason: ThreadJoinReason? = null,
    /**
     * [inertControls] (schema v16, register R-1133, FR-ASR-5, FR-ASR-6; AC-6; constitution I,
     * VI): which hallucination controls could not evaluate at all on the most recent Pass B
     * outcome for this row — [org.ort.asrapi.PassBOutcome.inertControls]'s own doc comment names
     * the distinction from a control that ran and cleared the segment (e.g. `NO_SPEECH_PROB`,
     * permanently inert against the wired sherpa-onnx binding — register R-1121). Before this
     * column existed, `PassBOutcome.inertControls` was computed on every pass and then thrown
     * away the instant [org.ort.pipeline.passb.DataPassBResultSink] read it — inertness was
     * *representable* but not *inspectable*: no debug dump could show that AC-6 ran on five
     * controls rather than six for a given over.
     *
     * Stored as [org.ort.pipeline.passb.PassBFingerprintBuilder.inertControlsSignature]'s
     * deterministic, sorted, comma-joined plain-text rendering — see that function's own doc
     * comment for why this is legible text, not a hash, matching [executionProvider] and
     * [processedTier]'s own plain-text discipline. Empty string (`""`, never a fabricated
     * `null`) means a real outcome ran and every control that reached it could actually
     * evaluate; `null` means no Pass B outcome carrying this fact has ever been recorded for
     * this row (the segment-persist insert always writes `null` here, the same "nothing has
     * happened yet" convention [executionProvider] itself uses).
     *
     * Written by [org.ort.pipeline.passb.DataPassBResultSink.record] for both `Accepted` and
     * `Rejected` outcomes — both carry `PassBOutcome.inertControls` — never for `Failed`, which
     * did not genuinely evaluate any control at all (the same "provenance is a fact about a
     * genuine attempt" reasoning [processedTier] already documents).
     */
    val inertControls: String? = null,
) {
    /**
     * The derived on-disk path for this transmission's audio (technical design §12.2): paths
     * are computed, never stored, so a row and its file cannot disagree about *where* the file
     * should be — only about whether it exists (FR-AST-8).
     */
    public fun audioPath(): String = "audio/$sessionId/$id.flac"

    /**
     * FR-SEG-10: the one, data-layer answer to "does this transmission's boundary conform to
     * FR-SEG-1" — see [org.ort.core.capture.conformsToFrSeg1]'s own kdoc for why this must never be
     * re-derived a second way by a caller.
     */
    public fun conformsToFrSeg1(): Boolean = vadDetector.conformsToFrSeg1
}
