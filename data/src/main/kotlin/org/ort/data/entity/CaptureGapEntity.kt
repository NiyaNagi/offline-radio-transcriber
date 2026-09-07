package org.ort.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Functional spec §8 `CaptureGap`. First-class data (FR-RUN-12) — silence that was never listened to. */
public enum class CaptureGapCause { INTERRUPTION, ROUTE_CHANGE, DEVICE_LOST, STORAGE, UNKNOWN }

@Entity(tableName = "capture_gap")
public data class CaptureGapEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val startedAt: Long,
    val endedAt: Long?,
    val cause: CaptureGapCause,
    val recoveredAutomatically: Boolean,
)
