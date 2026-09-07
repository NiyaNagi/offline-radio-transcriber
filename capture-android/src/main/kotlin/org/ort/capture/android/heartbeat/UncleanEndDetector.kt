package org.ort.capture.android.heartbeat

/** Reported on next launch when the previous session ended without a clean shutdown (AC-5). */
public data class UncleanEndReport(val sessionId: String, val lastHeartbeatWallMillis: Long)

/** On-launch check (FR-SVC-5b → AC-5): reports the unclean end with its last-known heartbeat time. */
public class UncleanEndDetector(private val store: HeartbeatStore) {
    public fun detect(): UncleanEndReport? {
        val last = store.last() ?: return null
        return if (store.hadUncleanEnd()) UncleanEndReport(last.sessionId, last.wallMillis) else null
    }
}

/**
 * Liveness is established empirically from heartbeat continuity, never from
 * `isIgnoringBatteryOptimizations()` (NFR-8 → AC-65). The battery-exemption flag is accepted
 * here purely as an explicitly-unused, explicitly-named parameter so a caller cannot wire it
 * into the decision by accident — the type signature itself is the guard (constitution VII).
 */
public class LivenessChecker(private val maxSilenceMillis: Long = DEFAULT_MAX_SILENCE_MILLIS) {

    public fun isAlive(
        lastHeartbeatWallMillis: Long,
        nowWallMillis: Long,
        isIgnoringBatteryOptimizationsDiagnosticOnly: Boolean,
    ): Boolean {
        // Read to document intent at call sites; never influences the result (AC-65).
        @Suppress("UNUSED_EXPRESSION")
        isIgnoringBatteryOptimizationsDiagnosticOnly
        return (nowWallMillis - lastHeartbeatWallMillis) <= maxSilenceMillis
    }

    public companion object {
        public const val DEFAULT_MAX_SILENCE_MILLIS: Long = 90_000
    }
}
