package org.ort.pipeline.analytics

/**
 * FR-ANL-2's "capture uptime and heartbeat gaps (NFR-8)" tier-1 stat, sampled from
 * [org.ort.pipeline.CaptureStatus]'s own liveness fact — never from the audio frame path itself
 * (constitution IV): a "gap" here is a span where [org.ort.pipeline.CaptureStatus.isAlive] read
 * `false` between two samples, the identical liveness fact the status surface already shows the
 * operator (`LivenessChecker`, `:capture-android`), not a second, independently-derived notion of
 * a gap.
 *
 * A process-lifetime singleton, the same pattern `ThermalStatus`/`VadAvailability` already use in
 * this package: the [org.ort.pipeline.CaptureStatusRepository] that produces each sample is
 * recreated on every poll (`ReaderPolling.statusRepository`, `:app`) and carries no state of its
 * own between calls, so the running total has to live somewhere else.
 *
 * [sample] is cheap and non-blocking — a map lookup and some arithmetic, never I/O — called from
 * whatever already-existing, off-the-audio-path polling loop reads [org.ort.pipeline.CaptureStatus]
 * (today, `:app`'s status screen; constitution IV's "never blocks" is satisfied by this being pure
 * computation, not by being rare).
 */
public object CaptureHeartbeatAnalytics {

    /** Emit a summary at most this often (by accumulated session uptime), so a long session does
     * not go silent for tier 1 nor spam the queue on every status poll. */
    public const val EMIT_INTERVAL_MILLIS: Long = 5 * 60 * 1000L

    /** What [sample] reports is ready to submit as a tier-1 `CaptureHeartbeat` event. */
    public data class Sample(val uptimeMs: Long, val gapCount: Int, val gapDurationMs: Long)

    private class SessionState {
        var wasAlive: Boolean = true
        var gapStartWallMillis: Long? = null
        var gapCount: Int = 0
        var gapDurationMs: Long = 0L
        var lastEmittedElapsedMillis: Long = 0L
    }

    private val sessions = mutableMapOf<String, SessionState>()

    /**
     * Call on every status poll while capture is active. [elapsedMillis] is the session's own
     * uptime ([org.ort.pipeline.CaptureStatus.elapsedMillis]); [isAlive] and [nowWallMillis] are
     * exactly what that same poll already computed
     * ([org.ort.pipeline.CaptureStatusRepository.current]'s own liveness read). Returns a [Sample]
     * to submit only once [EMIT_INTERVAL_MILLIS] of uptime has accumulated since the last one for
     * this [sessionId]; `null` on every other call (most of them).
     */
    public fun sample(sessionId: String, elapsedMillis: Long, isAlive: Boolean, nowWallMillis: Long): Sample? {
        val state = sessions.getOrPut(sessionId) { SessionState() }
        when {
            state.wasAlive && !isAlive -> state.gapStartWallMillis = nowWallMillis
            !state.wasAlive && isAlive -> {
                val start = state.gapStartWallMillis
                if (start != null) {
                    state.gapDurationMs += (nowWallMillis - start).coerceAtLeast(0L)
                    state.gapCount += 1
                }
                state.gapStartWallMillis = null
            }
        }
        state.wasAlive = isAlive

        if (elapsedMillis - state.lastEmittedElapsedMillis < EMIT_INTERVAL_MILLIS) return null
        state.lastEmittedElapsedMillis = elapsedMillis
        return Sample(uptimeMs = elapsedMillis, gapCount = state.gapCount, gapDurationMs = state.gapDurationMs)
    }

    /** Test-only reset — the same pattern this package's other process-lifetime singletons use. */
    public fun resetForTest() {
        sessions.clear()
    }
}
