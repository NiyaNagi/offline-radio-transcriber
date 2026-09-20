package org.ort.app.ui.navigation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AC-168 (register R-1006, build-plan P26): [shouldStopPlaybackOnTransmissionLeave] is
 * [OrtNavHost.kt]'s own decision of whether leaving a transmission's own detail screen should stop
 * the shared player — pulled out to a plain function (that function's own doc comment) so it is
 * testable without composing the whole host, the same discipline [resolveTransportBarState]'s own
 * suite already established.
 */
class ShouldStopPlaybackOnTransmissionLeaveTest {

    @Test
    fun `closing the drill-in for the transmission that is actually loaded stops it`() {
        val result = shouldStopPlaybackOnTransmissionLeave(
            leftTransmissionId = "TX1",
            loadedTransmissionId = "TX1",
        )

        assertTrue(result, "leaving TX1's own screen while TX1 is loaded must stop it")
    }

    @Test
    fun `switching to a different over's own detail also stops the one that was loaded`() {
        // AC-168's own decision, recorded in `shouldStopPlaybackOnTransmissionLeave`'s doc comment:
        // the artboard draws no distinction between closing the drill-in outright and a same-screen
        // switch to a different over, so both are a genuine leave. This is the exact "same-screen
        // switch" case the build plan asked to be checked against the artboard and decided
        // explicitly, not silently re-reversed.
        val result = shouldStopPlaybackOnTransmissionLeave(
            leftTransmissionId = "TX1",
            loadedTransmissionId = "TX1",
        )

        assertTrue(result, "leaving TX1's own screen for TX2's must stop TX1, even without the id going null")
    }

    @Test
    fun `never stops a transmission that is not the one actually loaded`() {
        // A fast tap through several overs already moved `loadedTransmissionId` on to something
        // else by the time this fires — the screen being left is stale, and must never reach out
        // and stop whatever is genuinely playing now.
        val result = shouldStopPlaybackOnTransmissionLeave(
            leftTransmissionId = "TX1",
            loadedTransmissionId = "TX2",
        )

        assertFalse(result, "TX1's own screen closing must never stop TX2, which is what is actually loaded")
    }

    @Test
    fun `nothing loaded means nothing to stop`() {
        val result = shouldStopPlaybackOnTransmissionLeave(
            leftTransmissionId = "TX1",
            loadedTransmissionId = null,
        )

        assertFalse(result, "there is nothing loaded to stop")
    }

    @Test
    fun `a left id of null - no drill-in was ever open - never calls stop`() {
        val result = shouldStopPlaybackOnTransmissionLeave(
            leftTransmissionId = null,
            loadedTransmissionId = "TX1",
        )

        assertFalse(result, "no transmission screen was ever showing, so there is nothing to have left")
    }
}
