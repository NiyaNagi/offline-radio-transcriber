package org.ort.pipeline

import org.ort.capture.android.heartbeat.HeartbeatStore
import org.ort.capture.android.heartbeat.LivenessChecker
import org.ort.capture.android.heartbeat.UncleanEndDetector
import org.ort.capture.android.heartbeat.UncleanEndReport
import org.ort.core.Clock
import org.ort.pipeline.shed.ShedController

/**
 * What the app's status surface shows (FR-UI-7, FR-PLT-1 — build-plan P8's status-surface
 * obligation for `:app`). `:app` may not depend on `:capture-android` directly (module graph),
 * so `:pipeline` — which may — assembles this from `HeartbeatStore`, [ShedController] and the
 * capture state, and re-exports the capture-android types it wraps (`api`, not
 * `implementation`, in this module's `build.gradle.kts`) so `:app` can read them without a new
 * declared edge.
 */
public data class CaptureStatus(
    val sessionId: String,
    val isCapturing: Boolean,
    val elapsedMillis: Long,
    val transmissionCount: Int,
    val gapCount: Int,
    val shedLevel: Int,
    val isAlive: Boolean,
    val lastHeartbeatWallMillis: Long?,
    val uncleanEndFromPreviousLaunch: UncleanEndReport?,
)

/**
 * Assembles [CaptureStatus] from its sources. Liveness always comes from [LivenessChecker], and
 * `isIgnoringBatteryOptimizationsDiagnosticOnly` is threaded through unchanged rather than
 * dropped, so a caller cannot quietly start trusting it later (AC-65, constitution IV).
 */
public class CaptureStatusRepository(
    private val heartbeatStore: HeartbeatStore,
    private val shedController: ShedController,
    private val clock: Clock,
    private val livenessChecker: LivenessChecker = LivenessChecker(),
) {
    private val uncleanEndDetector = UncleanEndDetector(heartbeatStore)

    /** Read once at app launch, before a new session starts (AC-5). */
    public fun uncleanEndFromPreviousLaunch(): UncleanEndReport? = uncleanEndDetector.detect()

    public fun current(
        sessionId: String,
        isCapturing: Boolean,
        elapsedMillis: Long,
        transmissionCount: Int,
        gapCount: Int,
        isIgnoringBatteryOptimizationsDiagnosticOnly: Boolean,
    ): CaptureStatus {
        val last = heartbeatStore.last()
        val alive = last != null &&
            livenessChecker.isAlive(last.wallMillis, clock.wallMillis(), isIgnoringBatteryOptimizationsDiagnosticOnly)
        return CaptureStatus(
            sessionId = sessionId,
            isCapturing = isCapturing,
            elapsedMillis = elapsedMillis,
            transmissionCount = transmissionCount,
            gapCount = gapCount,
            shedLevel = shedController.currentLevel,
            isAlive = alive,
            lastHeartbeatWallMillis = last?.wallMillis,
            uncleanEndFromPreviousLaunch = null,
        )
    }
}
