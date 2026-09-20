package org.ort.telemetry

import kotlinx.serialization.Serializable

/**
 * FR-ANL-8: every event carries this envelope — constitution VI ("no number without its
 * provenance") applied to the fourth outbound channel. Every field here is required to exist on
 * the type (nullable only where the fact genuinely may not exist yet, e.g. [sessionId] before any
 * capture session has run), never optional in the sense of "may be omitted from the payload" —
 * this is the same non-optional-at-the-type-level discipline constitution I already requires of
 * an attribution's confidence state.
 */
@Serializable
public data class AnalyticsProvenance(
    /** A random, resettable id — never the device's hardware identity (FR-ANL-11). */
    val installId: String,
    /** `null` before any capture session has produced one yet (e.g. a setup-funnel event). */
    val sessionId: String?,
    /** `null` when the event does not concern one transmission in particular. */
    val overId: String?,
    val appVersion: String,
    val buildHash: String,
    val modelIds: List<String>,
    val modelShas: List<String>,
    val executionProvider: String,
    val deviceModel: String,
    val soc: String,
    val detectedTier: String,
    /** D33's capture-mode axis, as its own name — `null` before setup has chosen one. */
    val captureMode: String?,
    /** `null` when no rig module is in use (e.g. local microphone). */
    val rigModule: String?,
    /** `null` when the event does not concern one band in particular. */
    val band: String?,
    val schemaVersion: Int,
)

/** The schema version this build of `:telemetry` emits. Bump alongside a field-list change to
 * either tier so `tools/analytics`' loader can tell old and new rows apart (FR-ANL-8). */
public const val ANALYTICS_SCHEMA_VERSION: Int = 1
