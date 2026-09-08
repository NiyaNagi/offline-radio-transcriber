package org.ort.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persists [org.ort.pipeline.shed.ShedController.ShedEvent] (technical design §7.3), which today
 * lives only in that controller's in-memory `events` list — F-021. Fields mirror that class's
 * shape (`level`, `reason`, `atWallMillis`) plus what FR-RUN-3/4/5 need to make shedding
 * surfaced, reprocessing-eligible and observable once persisted: the level *before* the
 * transition (not just the level entered), which session it happened in, a closed trigger
 * category alongside the free-text reason, monotonic time (the controller already tracks
 * `enteredAtMonotonic`), and the sample position in the stream at which it occurred.
 *
 * `:pipeline` does not depend on `:data` (module graph), so this entity does not reference
 * [org.ort.pipeline.shed.ShedController.ShedEvent] directly — a future persist call there maps
 * one to the other field-by-field.
 */
public enum class ShedTrigger { BATTERY, BACKLOG, STORAGE }

@Entity(tableName = "shed_event")
public data class ShedEventEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val levelBefore: Int,
    val levelAfter: Int,
    val trigger: ShedTrigger,
    val reason: String,
    val atWallMillis: Long,
    val atMonotonicNanos: Long,
    /** Position (e.g. sample index) in the audio/session stream the event occurred at, when known. */
    val samplePosition: Long?,
)
