package org.ort.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persists `org.ort.pipeline.digest.ProseSummary` (FR-DIG-3, FR-DIG-11 / T3, schema v8) — the
 * per-thread LLM prose summary the digest gate generates, distinct from [StationSummaryEntity]'s
 * per-station, cross-session accumulation (D29). One row per thread: a fresh generation for a
 * thread replaces its previous summary rather than accumulating a history, matching
 * `ProseSummaryStore`'s own "one summary per thread" contract. `:pipeline`'s
 * `RoomProseSummaryStore` maps this to/from the domain `ProseSummary` field-by-field.
 */
@Entity(tableName = "prose_summary")
public data class ProseSummaryEntity(
    @PrimaryKey val threadId: String,
    val text: String,
    val sourceTransmissionIds: List<String>,
    val generatedAtMillis: Long,
    val modelId: String,
)
