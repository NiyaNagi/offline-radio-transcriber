package org.ort.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * WPARC (FR-RUN-12, constitution IV "capture never blocks, never drops, never lies"): the
 * continuous archive's own gap record. [org.ort.data.entity.CaptureGapEntity] names an interval
 * where **no audio was captured at all** (an interruption, a route drop); this names an interval
 * where audio *was* captured and over audio is unaffected, but the continuous-archive writer
 * itself failed to persist it (a FLAC verification failure, a disk error) — a different fact, so
 * a different table, following the same discipline: recorded, never silent, never deleted.
 *
 * [startSample]/[sampleCount] are on the session's own sample-accurate timeline (the same one
 * [org.ort.segment.Segmenter.position] and [org.ort.capture.android.archive.ContinuousArchiveWriter]
 * use) rather than wall-clock millis — an archive hole is defined in terms of the audio stream
 * that failed to archive, not of when the failure happened to be *detected*.
 */
@Entity(tableName = "archive_gap", indices = [Index("sessionId")])
public data class ArchiveGapEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val startSample: Long,
    val sampleCount: Long,
    /** A short, closed-vocabulary reason (e.g. `"verification_failed"`, `"io_error"`) — never a
     * caught exception's free-text message (constitution II: never assert/store prose a library
     * or the runtime chose). */
    val reason: String,
    val recordedAtMillis: Long,
)
