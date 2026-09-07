package org.ort.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Which pass produced a transcript row (functional spec §8; distinct from the pipeline-wide `PassId`). */
public enum class TranscriptPass { A, B, REPROCESS }

/**
 * Functional spec §8 `Transcript`. Append-only: superseding writes a new row and flips
 * [isCurrent] in one transaction, nothing is deleted (FR-REP-3 → AC-31, P9). Exactly one
 * `isCurrent = 1` row per transmission is enforced by a **partial unique index**, added by hand
 * in [org.ort.data.OrtDatabase] because Room's `@Index` cannot express a `WHERE` clause
 * (technical design §8.3, §12.1).
 */
@Entity(
    tableName = "transcript",
    foreignKeys = [
        ForeignKey(
            entity = TransmissionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transmissionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("transmissionId")],
)
public data class TranscriptEntity(
    @PrimaryKey val id: String,
    val transmissionId: String,
    val pass: TranscriptPass,
    val text: String,
    val modelId: String,
    val modelVersion: String,
    val quantization: String?,
    val decodeParams: String?,
    val noSpeechProb: Float?,
    val confidence: Double?,
    val isCurrent: Boolean,
    val createdAt: Long,
)
