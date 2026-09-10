package org.ort.app.permissions

/** One step the onboarding/permissions flow can ask for. */
public enum class PermissionStep {
    RECORD_AUDIO,
    NOTIFICATIONS,
    BATTERY_EXEMPTION,
    DONE,
}

/**
 * The permissions/onboarding state machine (RECORD_AUDIO, foreground service, battery
 * exemption). `isIgnoringBatteryOptimizationsDiagnosticOnly` is read but **never** treated as
 * proof the app will keep running — it only decides whether to *show the request*, exactly the
 * same way the constitution treats it as a hint that lies on the reference device (NFR-8,
 * constitution IV). Actual liveness is reported separately, from the heartbeat
 * ([org.ort.pipeline.CaptureStatus.isAlive]).
 */
public data class PermissionsState(
    val recordAudioGranted: Boolean,
    val notificationsGranted: Boolean,
    val isIgnoringBatteryOptimizationsDiagnosticOnly: Boolean,
    /**
     * `BLUETOOTH_CONNECT` (API >= 31) — read by [org.ort.app.ui.setup.SetupStateMachine] to gate
     * [org.ort.app.ui.setup.SetupStep.BLUETOOTH_PERMISSION] in Bluetooth capture mode (D33,
     * FR-CAP-8, S02c). Below API 31 the dangerous permission does not exist at all — the legacy
     * `BLUETOOTH` permission is normal-protection and granted at install — so a caller SHALL
     * report `true` unconditionally on those platform levels rather than ever asking; this field
     * says only whether the app currently holds what it needs, never which platform level made
     * that true. Defaulted `true` so every existing call site (test or production) that predates
     * D33 continues to mean exactly what it always did: "Bluetooth is not blocking this flow".
     */
    val bluetoothConnectGranted: Boolean = true,
)

public object PermissionsFlow {

    /** The next step to present, or [PermissionStep.DONE] once every request has been made. */
    public fun nextStep(state: PermissionsState): PermissionStep = when {
        !state.recordAudioGranted -> PermissionStep.RECORD_AUDIO
        !state.notificationsGranted -> PermissionStep.NOTIFICATIONS
        !state.isIgnoringBatteryOptimizationsDiagnosticOnly -> PermissionStep.BATTERY_EXEMPTION
        else -> PermissionStep.DONE
    }

    /**
     * Explicitly documents the one thing this flow must never do: grant capture readiness from
     * the battery-exemption flag. Capture readiness is RECORD_AUDIO and NOTIFICATIONS only —
     * the battery step is requested for the OS's sake, not trusted for ours.
     */
    public fun captureIsPermitted(state: PermissionsState): Boolean =
        state.recordAudioGranted && state.notificationsGranted
}
