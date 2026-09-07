package org.ort.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.ort.core.AttributionState
import org.ort.core.TransmissionState

/**
 * Functional spec §8 `Transmission`, `processingState` taking its values **only** from
 * [TransmissionState] (FR-RUN-7). `STALE` is deliberately absent — it is derived from stored
 * pass fingerprints, not stored (technical design §7.2) — but [isReprocessCandidate] is the
 * flag FR-RUN-4 sets when shedding or a fingerprint mismatch marks the row for reprocessing.
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
    val executionProvider: String?,
    val isReprocessCandidate: Boolean = false,
) {
    /**
     * The derived on-disk path for this transmission's audio (technical design §12.2): paths
     * are computed, never stored, so a row and its file cannot disagree about *where* the file
     * should be — only about whether it exists (FR-AST-8).
     */
    public fun audioPath(): String = "audio/$sessionId/$id.flac"
}
