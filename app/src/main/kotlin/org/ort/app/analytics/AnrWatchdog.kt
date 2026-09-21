package org.ort.app.analytics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * R-1123: "there is no ANR watchdog (`isAnr` is permanently null)" — this is one. `Thread
 * .UncaughtExceptionHandler` ([CrashCaptureHandler]) fires for an uncaught exception, never for a
 * hung main thread, so a genuinely stalled UI produces nothing at all today. This pings the main
 * thread on a fixed cadence from a background coroutine and calls [onStallDetected] the first time
 * one full [checkIntervalMillis] cycle passes with the ping still unanswered — the same "post a
 * token, see if it comes back" technique every real ANR watchdog (including Android's own, and
 * every third-party crash SDK constitution V forbids linking here) uses; the difference is this one
 * runs with no HTTP client and no dependency outside `:app`.
 *
 * A stall is reported once per episode, not once per cycle: [reportedForCurrentStall] is cleared
 * the moment a ping is next answered, so a main thread stuck for thirty seconds produces one
 * event, not six, and a *second*, later stall (after a real recovery) is reported again.
 *
 * **Dependency-injected on purpose, so this is a plain JVM/coroutines-test class with no
 * Robolectric.** [postToMainThread] is a plain `(() -> Unit) -> Unit` rather than a captured
 * `Handler(Looper.getMainLooper())` — the one real caller ([org.ort.app.OrtApplication]) supplies
 * `Handler(Looper.getMainLooper())::post`; a test supplies a fake that either answers immediately
 * (no stall) or never answers at all (a stall), and drives time with
 * [kotlinx.coroutines.test.runTest]'s virtual clock via [checkIntervalMillis] rather than a real
 * multi-second sleep.
 */
public class AnrWatchdog(
    private val postToMainThread: (() -> Unit) -> Unit,
    private val onStallDetected: () -> Unit,
    private val checkIntervalMillis: Long = DEFAULT_CHECK_INTERVAL_MILLIS,
) {
    @Volatile
    private var answeredCurrentPing: Boolean = true

    @Volatile
    private var reportedForCurrentStall: Boolean = false

    private var job: Job? = null

    /** Starts the ping loop in [scope] — a no-op if already started. Never suspends the caller: the
     * loop runs entirely on whatever dispatcher [scope] provides. */
    public fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                answeredCurrentPing = false
                postToMainThread {
                    answeredCurrentPing = true
                    reportedForCurrentStall = false
                }
                delay(checkIntervalMillis)
                if (!answeredCurrentPing && !reportedForCurrentStall) {
                    reportedForCurrentStall = true
                    onStallDetected()
                }
            }
        }
    }

    /** Test/shutdown-only: stops the ping loop. */
    public fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        /** How long a ping may go unanswered before this reports a stall — 5 s, the same
         * main-thread-unresponsive window Android's own platform ANR dialog uses for a foreground
         * input event, so a report here means roughly "the platform itself would also call this an
         * ANR," not an arbitrarily stricter or looser bar. */
        const val DEFAULT_CHECK_INTERVAL_MILLIS: Long = 5_000L
    }
}
