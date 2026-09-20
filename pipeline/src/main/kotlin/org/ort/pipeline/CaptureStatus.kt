package org.ort.pipeline

import org.ort.capture.android.heartbeat.HeartbeatStore
import org.ort.capture.android.heartbeat.LivenessChecker
import org.ort.capture.android.heartbeat.UncleanEndDetector
import org.ort.capture.android.heartbeat.UncleanEndReport
import org.ort.core.Clock
import org.ort.pipeline.analytics.AnalyticsBridge
import org.ort.pipeline.analytics.CaptureHeartbeatAnalytics
import org.ort.pipeline.rig.CaptureConfiguration
import org.ort.pipeline.shed.ShedController
import org.ort.telemetry.AnalyticsEventFactory
import org.ort.telemetry.AnalyticsTier1Payload

/**
 * What the app's status surface shows (FR-UI-7, FR-PLT-1 — build-plan P8's status-surface
 * obligation for `:app`). `:app` may not depend on `:capture-android` directly (module graph),
 * so `:pipeline` — which may — assembles this from `HeartbeatStore`, [ShedController] and the
 * capture state, and re-exports the capture-android types it wraps (`api`, not
 * `implementation`, in this module's `build.gradle.kts`) so `:app` can read them without a new
 * declared edge.
 *
 * [pendingConfiguration] (WPC2, FR-CAP-12, AC-131, E2-D05): non-null exactly when
 * [org.ort.pipeline.rig.CaptureConfigurationStore.pendingConfiguration] is — a mode/rig change
 * written to the store while this session runs, waiting to apply at the next one. CF11's amber
 * banner reads this field, never re-deriving it. Trailing and defaulted so every pre-existing call
 * site — `:app`'s own construction of this class in tests included — keeps compiling unchanged.
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
    val pendingConfiguration: CaptureConfiguration? = null,
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
        pendingConfiguration: CaptureConfiguration? = null,
    ): CaptureStatus {
        val last = heartbeatStore.last()
        val nowWallMillis = clock.wallMillis()
        val alive = last != null &&
            livenessChecker.isAlive(last.wallMillis, nowWallMillis, isIgnoringBatteryOptimizationsDiagnosticOnly)
        // P28 follow-up (FR-ANL-2, NFR-8): capture uptime and heartbeat gaps, sampled here rather
        // than on the audio frame path itself (constitution IV) — only while a session is genuinely
        // capturing, so an idle poll never reports a fabricated "gap" against a session that has not
        // started. See CaptureHeartbeatAnalytics's own doc comment for why this class, not a new one.
        if (isCapturing) {
            // runCatching: constitution IV — an analytics failure of any kind (an unwired bridge, a
            // throwing provenance/queue) must never propagate into the status surface's own read.
            runCatching {
                CaptureHeartbeatAnalytics.sample(sessionId, elapsedMillis, alive, nowWallMillis)?.let { sampled ->
                    AnalyticsBridge.submit(
                        AnalyticsEventFactory.tier1(
                            AnalyticsBridge.baseProvenance().copy(sessionId = sessionId),
                            AnalyticsTier1Payload.CaptureHeartbeat(
                                uptimeMs = sampled.uptimeMs,
                                gapCount = sampled.gapCount,
                                gapDurationMs = sampled.gapDurationMs,
                            ),
                        ),
                    )
                }
            }
        }
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
            pendingConfiguration = pendingConfiguration,
        )
    }
}
