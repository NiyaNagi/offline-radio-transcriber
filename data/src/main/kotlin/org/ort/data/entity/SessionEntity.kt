package org.ort.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Functional spec §8 `Session`, plus the additional fields technical design §12.1 calls out. */
public enum class TerminationReason { USER, CRASH, KILLED, STORAGE, UNKNOWN }

/**
 * FR-CAP-13, AC-129 (schema v7): every session's capture mode, audio route and rig transport,
 * so a room-audio session and a radio session are distinguishable in the record and accuracy
 * figures are reportable per mode (FR-TST-8). All five are nullable — not because a v7-onward
 * session may lack them, but so a v6-and-earlier row, migrated forward, reads honestly as
 * "not tracked" rather than fabricating a value it never recorded (constitution I, FR-AST-6).
 *
 * Stored as raw strings rather than the `:core` enum types directly: `org.ort.core.capture.*`
 * is the source of truth for the closed sets, and `:data` only ever writes/reads their `.name`
 * — see [org.ort.data.dao.SessionDao.getCaptureInfo] for the minimal read shape a later reader
 * (WPC2, WPE, WPF) needs instead of the whole entity.
 */
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
    /** `org.ort.core.capture.CaptureMode.name`, e.g. `"LOCAL_MICROPHONE"`. `null` = not tracked (pre-v7). */
    val captureMode: String? = null,
    /** `org.ort.core.capture.AudioRouteKind.name`, e.g. `"USB"`. `null` = not tracked (pre-v7). */
    val audioRouteKind: String? = null,
    /** The routed device's human-readable label at session start (e.g. `"USB Audio Adapter"`). */
    val audioRouteLabel: String? = null,
    /** `org.ort.core.capture.BluetoothAudioProfile.name`. `null` when the route was not Bluetooth. */
    val bluetoothProfile: String? = null,
    /** `org.ort.core.capture.RigTransportKind.name`, or `null` when no rig transport was in use. */
    val rigTransport: String? = null,
)
