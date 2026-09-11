package org.ort.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Functional spec §8 `CaptureGap`. First-class data (FR-RUN-12) — silence that was never listened
 * to. Room stores this enum by **name**, not ordinal (`cause` is a `TEXT` column — see
 * `data/schemas/org.ort.data.OrtDatabase/2.json`), so appending values here is schema-safe; do not
 * reorder or rename an existing entry, which would silently reinterpret every already-persisted row.
 *
 * register R-106 added [CALL], [INPUT_LOST], [OS_STOPPED] and [ROUTE_LOST] — every prior value is
 * unchanged. See `GapPersister.causeFor`'s own kdoc for exactly which of these a real running
 * capture can produce today, and which cannot yet be distinguished from another.
 */
public enum class CaptureGapCause {
    INTERRUPTION,
    ROUTE_CHANGE,
    DEVICE_LOST,
    STORAGE,
    UNKNOWN,

    /**
     * An incoming call took the microphone (F15). See `GapPersister.causeFor`'s kdoc for what
     * "distinguishable" means here.
     */
    CALL,

    /** A read error or the device itself going away — the more precisely-named successor to [DEVICE_LOST]. */
    INPUT_LOST,

    /**
     * F5: the OS killed the process without a clean shutdown. Persisted on the *previous*
     * session, from its last heartbeat to the moment of detection.
     */
    OS_STOPPED,

    /** F9/AC-2: a route-mismatch halt. See `GapPersister.causeFor`'s kdoc for why no gap reaches this today. */
    ROUTE_LOST,

    /**
     * WPC3 (schema v9, FR-CAP-5, F23): a Bluetooth **audio** route dropping mid-session — the more
     * precisely-named sibling of [INPUT_LOST] for exactly this route kind, so a Bluetooth dropout
     * is distinguishable in the log from a USB or built-in-mic one. See `GapPersister.causeFor`'s
     * own kdoc for how this is told apart from [INPUT_LOST]. Appended last, per this enum's own
     * "do not reorder" rule.
     */
    BLUETOOTH_AUDIO_LOST,
}

@Entity(tableName = "capture_gap")
public data class CaptureGapEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val startedAt: Long,
    val endedAt: Long?,
    val cause: CaptureGapCause,
    val recoveredAutomatically: Boolean,
)
