package org.ort.app.ui.failures

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

/**
 * Register R-1008 ("app-wide", `Feedback.dc.html`): [PushedToastChannel] is the push half of
 * [FailureHost]'s own toast slot — a screen that already knows an action's result (a correction
 * applied, a delete, anything that used to have "nowhere to land" once the operator navigated
 * away from the screen that performed it) hands it a [RecoveryToast] directly, rather than
 * waiting for the polled [RecoveryAnnouncer] path (which only ever infers a toast from two
 * consecutive *signal* snapshots, never a screen's own one-shot result).
 *
 * These tests are plain [kotlinx.coroutines.channels.Channel] behaviour, proven without Compose or
 * [FailureHost] at all — the two guarantees this row actually asks for ("not lost", "not shown
 * twice") are properties of the channel itself, independent of anything drawn on screen; the
 * Compose-level proof that [FailureHost] actually wires this channel to its own toast slot lives
 * in `PushedToastFailureHostTest`.
 */
class PushedToastChannelTest {

    @AfterEach
    fun resetChannel() {
        PushedToastChannel.resetForTest()
    }

    @Test
    @Requirement("R-1008")
    fun `R_1008 a toast pushed before anything ever receives is not lost, only buffered`() = runTest {
        // The exact shape of "an action's result arrives while the operator is mid-navigation" —
        // nothing is collecting yet when this call happens.
        PushedToastChannel.push(RecoveryToast("early", "6 overs updated"))

        val received = PushedToastChannel.receive()

        assertEquals(RecoveryToast("early", "6 overs updated"), received)
    }

    @Test
    @Requirement("R-1008")
    fun `R_1008 several toasts pushed before any receive are each delivered exactly once, in order`() = runTest {
        PushedToastChannel.push(RecoveryToast("first", "Corrected to K7LWH"))
        PushedToastChannel.push(RecoveryToast("second", "Corrected to W1AW"))

        val first = PushedToastChannel.receive()
        val second = PushedToastChannel.receive()

        assertEquals("first", first.id)
        assertEquals("second", second.id)
    }

    @Test
    @Requirement("R-1008")
    fun `R_1008 a received toast is consumed - a second receive does not redeliver it`() = runTest {
        PushedToastChannel.push(RecoveryToast("once", "Deleted"))
        PushedToastChannel.receive()

        // No second push happened — a second receive must suspend rather than redeliver the first
        // toast a second time. `tryReceive` never suspends, so a channel genuinely drained (rather
        // than one that would eventually redeliver) reports failure immediately.
        val second = PushedToastChannel.tryReceiveForTest()

        assertNull(second)
    }

    @Test
    @Requirement("R-1008")
    fun `R_1008 onUndo defaults to null so every existing polled recovery toast keeps working unchanged`() {
        val toast = RecoveryToast("rig", "Radio reconnected")

        assertNull(toast.onUndo)
    }

    /**
     * Register R-1139: [PushedToastChannel.undoScope] is what makes a pushed toast's own `onUndo` —
     * a plain, non-suspending callback — able to run a real suspend write at all, from wherever it
     * is actually tapped, long after whatever screen built the closure is gone. Proven directly
     * against the real, shared scope (not a fake one): work launched on it genuinely runs, and
     * genuinely does not share a job with a caller's own short-lived scope.
     */
    /**
     * **`runBlocking`, not `runTest`, and the distinction is the whole reason this test was flaky
     * (register R-1180).** Every other test in this file drives a plain [Channel] and nothing else,
     * so `runTest`'s virtual clock is exactly right for them. These last two are different: they
     * launch on [PushedToastChannel.undoScope], which is a **real** `SupervisorJob() +
     * Dispatchers.IO` scope, deliberately, because a scope that can be faked away is not the thing
     * R-1139 needs proven. Inside `runTest`, `withTimeout(5_000)` counts *virtual* time, and the
     * virtual clock jumps forward the instant the test scheduler has nothing left to run — which is
     * immediately, since the real work is on `Dispatchers.IO` and the scheduler cannot see it. The
     * 5-second timeout therefore raced the IO dispatcher rather than bounding it, passed whenever
     * IO happened to win, and failed on a loaded hosted runner (Release `35827213621`,
     * `TimeoutCancellationException: Timed out after 5s of _virtual_ time`). `runBlocking` makes the
     * timeout real wall-clock seconds, which is what a real scope needs and what this always meant.
     */
    @Test
    @Requirement("R-1139")
    fun `R_1139 undoScope actually runs work launched on it`() = runBlocking {
        val ran = Channel<Unit>(Channel.UNLIMITED)

        PushedToastChannel.undoScope.launch { ran.trySend(Unit) }

        withTimeout(5_000) { ran.receive() }
        Unit
    }

    /** Real time, not virtual — see the test above for why. */
    @Test
    @Requirement("R-1139")
    fun `R_1139 undoScope is not cancelled by an unrelated coroutine scope completing`() = runBlocking {
        // Stands in for a producer's own short-lived `rememberCoroutineScope()`-style scope, which
        // this file's own class kdoc names as the trap: cancelled the moment the screen that built
        // the closure leaves composition. `undoScope` must still be usable afterward.
        val shortLived = CoroutineScope(Job())
        shortLived.launch {}
        shortLived.cancel()

        val ran = Channel<Unit>(Channel.UNLIMITED)
        PushedToastChannel.undoScope.launch { ran.trySend(Unit) }
        withTimeout(5_000) { ran.receive() }

        assertTrue(PushedToastChannel.undoScope.isActive)
    }
}
