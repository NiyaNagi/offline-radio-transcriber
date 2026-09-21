package org.ort.app.ui.failures

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
}
