package org.ort.app.analytics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * R-1123: "there is no ANR watchdog (`isAnr` is permanently null)". [AnrWatchdog] pings the main
 * thread on a fixed cadence and reports a stall the first time a ping goes unanswered for a full
 * cycle. Entirely plain JVM + `kotlinx-coroutines-test` virtual time — no Robolectric, no real
 * `Handler`/`Looper`, no real 5-second sleep. [postToMainThread] is a fake the test fully controls:
 * invoking it immediately simulates a responsive main thread; never invoking it simulates a
 * stalled one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnrWatchdogTest {

    private val checkIntervalMillis = 5_000L

    @Test
    fun `R_1123_a main thread that always answers immediately never reports a stall`(): TestResult = runTest {
        var stallCount = 0
        val watchdog = AnrWatchdog(
            postToMainThread = { runnable -> runnable() }, // answers every ping instantly
            onStallDetected = { stallCount++ },
            checkIntervalMillis = checkIntervalMillis,
        )

        watchdog.start(this)
        advanceTimeBy(checkIntervalMillis * 10)
        watchdog.stop()

        assertEquals(0, stallCount, "a responsive main thread must never be reported as stalled")
    }

    @Test
    fun `R_1123_a main thread that never answers is reported as a stall`(): TestResult = runTest {
        var stallCount = 0
        val watchdog = AnrWatchdog(
            postToMainThread = { }, // never invokes the runnable — the main thread is hung
            onStallDetected = { stallCount++ },
            checkIntervalMillis = checkIntervalMillis,
        )

        watchdog.start(this)
        advanceTimeBy(checkIntervalMillis + 1)
        watchdog.stop()

        assertEquals(1, stallCount, "an unanswered main thread must be reported exactly once")
    }

    @Test
    fun `R_1123_a stall is reported only once while it continues, not on every check cycle`(): TestResult = runTest {
        var stallCount = 0
        val watchdog = AnrWatchdog(
            postToMainThread = { },
            onStallDetected = { stallCount++ },
            checkIntervalMillis = checkIntervalMillis,
        )

        watchdog.start(this)
        advanceTimeBy(checkIntervalMillis * 5)
        watchdog.stop()

        assertEquals(1, stallCount, "a continuing stall must not be reported again every cycle")
    }

    /**
     * Each cycle's ping for iteration N+1 is sent the instant iteration N's check completes —
     * inside the *same* [advanceTimeBy] call that reached iteration N's own check time, since
     * nothing suspends in between. So a flag flip only affects the ping sent *after* the
     * [advanceTimeBy] call in which it is made, and that ping is only checked one further
     * [checkIntervalMillis] later — hence four steps, not two, to see the watchdog notice a
     * recovery and then a fresh stall.
     */
    @Test
    fun `R_1123_recovering from a stall allows a later, separate stall to be reported again`(): TestResult = runTest {
        var stallCount = 0
        var mainThreadHung = true
        val watchdog = AnrWatchdog(
            postToMainThread = { runnable -> if (!mainThreadHung) runnable() },
            onStallDetected = { stallCount++ },
            checkIntervalMillis = checkIntervalMillis,
        )

        watchdog.start(this)
        // iteration 1 checked: unanswered -> stall; iteration 2's ping sent while still hung
        advanceTimeBy(checkIntervalMillis + 1)
        assertEquals(1, stallCount, "the first stall must be reported")

        mainThreadHung = false // the main thread recovers
        // iteration 2 checked: unanswered but already reported; iteration 3's ping sent while recovered
        advanceTimeBy(checkIntervalMillis + 1)
        assertEquals(1, stallCount, "no new stall yet — the recovered ping has not been checked")

        mainThreadHung = true // it hangs again before the recovered ping is even checked
        // iteration 3 checked: answered -> no stall, but resets the "already reported" gate; iteration 4's ping sent
        // while hung again
        advanceTimeBy(checkIntervalMillis + 1)
        assertEquals(1, stallCount, "the recovered ping must not itself count as a stall")

        // iteration 4 checked: unanswered, and the gate was reset -> a genuinely new stall
        advanceTimeBy(checkIntervalMillis + 1)
        watchdog.stop()

        assertEquals(2, stallCount, "a second, separate stall after a real recovery must be reported too")
    }
}
