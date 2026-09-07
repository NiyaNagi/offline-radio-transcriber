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
