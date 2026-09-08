package org.ort.app.ui.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.app.permissions.PermissionsState

/**
 * R-080 (ui-conformance-plan WP9): the pure decision half of the guided setup sequence — which
 * [SetupStep] to show given the real permission state and what has been configured so far
 * ([SetupSnapshot]), with no `Context`/`Activity` at all (the same split
 * `org.ort.app.SetupScreenSelectionTest` established for the pre-WP9 flow, which this file
 * replaces — that flow's three screens are now steps 1-2 of this one, MICROPHONE/MICROPHONE_DENIED/
 * NOTIFICATIONS below). Every branch of `Flow-Setup.dc.html`'s linear sequence and its one halt
 * (S06, reached only live from S05 — never resumed into, per [SetupStep]'s own doc comment).
 */
class SetupStateMachineTest {

    private fun permissions(recordAudio: Boolean, notifications: Boolean) = PermissionsState(
        recordAudioGranted = recordAudio,
        notificationsGranted = notifications,
        isIgnoringBatteryOptimizationsDiagnosticOnly = false,
    )

    private fun snapshot(
        welcomeSeen: Boolean = true,
        notificationsSkipped: Boolean = false,
        selectedInputId: String? = "usb-1",
        inputVerified: Boolean = true,
        levelInBand: Boolean = true,
        overnightStepSeen: Boolean = true,
        radioChoice: RadioChoice? = RadioChoice.NONE,
        setupComplete: Boolean = false,
    ) = SetupSnapshot(
        welcomeSeen = welcomeSeen,
        notificationsSkipped = notificationsSkipped,
        selectedInputId = selectedInputId,
        inputVerified = inputVerified,
        levelInBand = levelInBand,
        overnightStepSeen = overnightStepSeen,
        radioChoice = radioChoice,
        setupComplete = setupComplete,
    )

    @Test
    fun `R_080 a never-begun setup shows Welcome before any permission is checked`() {
        val step = SetupStateMachine.stepFor(
            permissions(recordAudio = false, notifications = false),
            micPermanentlyDenied = false,
            snapshot(welcomeSeen = false),
        )
        assertEquals(SetupStep.WELCOME, step)
    }

    @Test
    fun `R_080 microphone not granted and not permanently denied shows Microphone`() {
        val step = SetupStateMachine.stepFor(
            permissions(recordAudio = false, notifications = false),
            micPermanentlyDenied = false,
            snapshot(),
        )
        assertEquals(SetupStep.MICROPHONE, step)
    }

    @Test
    fun `R_085 microphone not granted and permanently denied shows MicrophoneDenied`() {
        val step = SetupStateMachine.stepFor(
            permissions(recordAudio = false, notifications = false),
            micPermanentlyDenied = true,
            snapshot(),
        )
        assertEquals(SetupStep.MICROPHONE_DENIED, step)
    }

    @Test
    fun `permanent denial is only consulted while the microphone is actually not granted`() {
        val step = SetupStateMachine.stepFor(
            permissions(recordAudio = true, notifications = false),
            micPermanentlyDenied = true,
            snapshot(),
        )
        assertEquals(SetupStep.NOTIFICATIONS, step)
    }

    @Test
    fun `R_080 notifications not granted and not skipped shows Notifications`() {
        val step = SetupStateMachine.stepFor(
            permissions(recordAudio = true, notifications = false),
            micPermanentlyDenied = false,
            snapshot(),
        )
        assertEquals(SetupStep.NOTIFICATIONS, step)
    }

    @Test
    fun `R_080 notifications skipped proceeds past Notifications even though ungranted`() {
        val step = SetupStateMachine.stepFor(
            permissions(recordAudio = true, notifications = false),
            micPermanentlyDenied = false,
            snapshot(notificationsSkipped = true, selectedInputId = null),
        )
        assertEquals(SetupStep.INPUT, step)
    }

    @Test
    fun `R_081 no input chosen shows Input`() {
        val step = SetupStateMachine.stepFor(
            permissions(true, true),
            micPermanentlyDenied = false,
            snapshot(selectedInputId = null),
        )
        assertEquals(SetupStep.INPUT, step)
    }

    @Test
    fun `R_081 an input chosen but not yet verified resumes on Input, not the transient Verify screen`() {
        val step = SetupStateMachine.stepFor(
            permissions(true, true),
            micPermanentlyDenied = false,
            snapshot(selectedInputId = "usb-1", inputVerified = false),
        )
        assertEquals(SetupStep.INPUT, step)
    }

    @Test
    fun `R_082 level not yet in band shows Level`() {
        val step = SetupStateMachine.stepFor(
            permissions(true, true),
            micPermanentlyDenied = false,
            snapshot(levelInBand = false),
        )
        assertEquals(SetupStep.LEVEL, step)
    }

    @Test
    fun `R_083 overnight step not yet seen shows Overnight`() {
        val step = SetupStateMachine.stepFor(
            permissions(true, true),
            micPermanentlyDenied = false,
            snapshot(overnightStepSeen = false),
        )
        assertEquals(SetupStep.OVERNIGHT, step)
    }

    @Test
    fun `R_084 no radio choice made shows Radio`() {
        val step = SetupStateMachine.stepFor(
            permissions(true, true),
            micPermanentlyDenied = false,
            snapshot(radioChoice = null),
        )
        assertEquals(SetupStep.RADIO, step)
    }

    @Test
    fun `everything configured but Start capture not yet tapped shows Ready`() {
        val step = SetupStateMachine.stepFor(
            permissions(true, true),
            micPermanentlyDenied = false,
            snapshot(setupComplete = false),
        )
        assertEquals(SetupStep.READY, step)
    }

    @Test
    fun `everything configured and Start capture tapped returns null, meaning hand back to MainActivity`() {
        val step = SetupStateMachine.stepFor(
            permissions(true, true),
            micPermanentlyDenied = false,
            snapshot(setupComplete = true),
        )
        assertNull(step)
    }

    @Test
    fun `a revoked microphone permission after setup completed still routes back to Microphone`() {
        val step = SetupStateMachine.stepFor(
            permissions(recordAudio = false, notifications = true),
            micPermanentlyDenied = false,
            snapshot(setupComplete = true),
        )
        assertEquals(SetupStep.MICROPHONE, step)
    }

    // --- isComplete: MainActivity's fast-path check ---------------------------------------------

    @Test
    fun `R_080 isComplete is true only once setupComplete is set and both permissions are granted`() {
        assertTrue(SetupStateMachine.isComplete(permissions(true, true), snapshot(setupComplete = true)))
    }

    @Test
    fun `isComplete is false while setupComplete has not been reached`() {
        assertFalse(SetupStateMachine.isComplete(permissions(true, true), snapshot(setupComplete = false)))
    }

    @Test
    fun `isComplete is false when a permission was revoked after setup finished`() {
        assertFalse(SetupStateMachine.isComplete(permissions(false, true), snapshot(setupComplete = true)))
    }
}
