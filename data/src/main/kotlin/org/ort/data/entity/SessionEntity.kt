package org.ort.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Functional spec §8 `Session`, plus the additional fields technical design §12.1 calls out. */
public enum class TerminationReason { USER, CRASH, KILLED, STORAGE, UNKNOWN }

@Entity(tableName = "session")
public data class SessionEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val endedAt: Long?,
    val profileId: String?,
    val deviceTier: String?,
    val appVersion: String?,
    val terminationReason: TerminationReason?,
    /** Reserved for future multi-radio capture (Q6), unused in v1. */
    val sourceId: String?,
    val schemaVersion: Int,
    val gapCount: Int = 0,
    val shedEvents: Int = 0,
)
