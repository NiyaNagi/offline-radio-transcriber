package org.ort.pipeline.rig

import org.ort.core.capture.CaptureMode
import org.ort.rig.NullRigModule
import org.ort.rig.RigTransportKind

/**
 * WPC2: the frozen set of facts capture needs at session start (FR-CAP-8/9/12/13, FR-RIG-13) —
 * everything [org.ort.pipeline.capture.RealCaptureService] reads once, at the moment a session
 * starts, through [CaptureConfigurationStore.activateForNewSession], and never re-reads until the
 * next one (FR-CAP-12, AC-131).
 *
 * [mode] is the operator-facing fact recorded on the session row (FR-CAP-13). [rigId] and
 * [rigTransportKind] are independent of it (FR-RIG-13) — a mode only *presets* them
 * (`:core`'s `CaptureModePresets`), it never constrains them, so this type carries the actual
 * choice a session will run with, not just the mode's default pairing.
 *
 * [rigId] names an entry from [org.ort.rig.catalogue.RigCatalogue] ([NullRigModule.ID] for no rig
 * at all — FR-RIG-2). [rigTransportKind] is `null` exactly when no rig transport is in use; a
 * non-null value together with [rigId] `== NullRigModule.ID` is treated the same as "no rig" by
 * [org.ort.pipeline.rig.RigSupervisor] (constitution I: an invalid combination degrades to the
 * null module rather than asserting a transport that names no radio).
 */
public data class CaptureConfiguration(
    public val mode: CaptureMode,
    public val selectedInputId: String?,
    public val rigId: String = NullRigModule.ID,
    public val rigTransportKind: RigTransportKind? = null,
    public val rigParams: Map<String, String> = emptyMap(),
) {
    public companion object {
        /** FR-CAP-8's audio-only v1 default — local microphone, no rig (FR-RIG-2). */
        public val DEFAULT: CaptureConfiguration = CaptureConfiguration(
            mode = CaptureMode.LOCAL_MICROPHONE,
            selectedInputId = null,
        )
    }
}
