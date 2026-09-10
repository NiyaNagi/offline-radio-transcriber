package org.ort.app.ui.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.app.permissions.PermissionsState
import org.ort.core.capture.CaptureMode
import org.ort.core.capture.RigTransportKind

/**
 * R-080 (ui-conformance-plan WP9), extended by D33/P19 WPD: the pure decision half of the guided
 * setup sequence — which [SetupStep] to show given the real permission state and what has been
 * configured so far ([SetupSnapshot]), with no `Context`/`Activity` at all. Every branch of
 * `Flow-Setup.dc.html`/`Flow-Mode.dc.html`'s sequence and its one halt (S06, reached only live
 * from S05 — never resumed into, per [SetupStep]'s own doc comment).
 */
class SetupStateMachineTest {

    private fun permissions(recordAudio: Boolean, notifications: Boolean, bluetoothConnect: Boolean = true) =
        PermissionsState(
            recordAudioGranted = recordAudio,
            notificationsGranted = notifications,
            isIgnoringBatteryOptimizationsDiagnosticOnly = false,
            bluetoothConnectGranted = bluetoothConnect,
        )

    @Suppress("LongParameterList")
    private fun snapshot(
        welcomeSeen: Boolean = true,
        captureMode: CaptureMode? = CaptureMode.USB_RADIO,
        bluetoothPermissionDeclined: Boolean = false,
        notificationsSkipped: Boolean = false,
        selectedInputId: String? = "usb-1",
        inputVerified: Boolean = true,
        levelInBand: Boolean = true,
        overnightStepSeen: Boolean = true,
        radioChoice: RadioChoice? = RadioChoice.NONE,
        rigTransport: RigTransportKind? = null,
        rigBluetoothVerified: Boolean = false,
        setupComplete: Boolean = false,
    ) = SetupSnapshot(
        welcomeSeen = welcomeSeen,
        captureMode = captureMode,
        bluetoothPermissionDeclined = bluetoothPermissionDeclined,
        notificationsSkipped = notificationsSkipped,
        selectedInputId = selectedInputId,
        inputVerified = inputVerified,
        levelInBand = levelInBand,
        overnightStepSeen = overnightStepSeen,
        radioChoice = radioChoice,
        rigTransport = rigTransport,
        rigBluetoothVerified = rigBluetoothVerified,
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

    // --- D33: SetupStep.MODE ---------------------------------------------------------------

    @Test
    fun `D33 welcome seen but no capture mode chosen shows Mode, before any permission`() {
        val step = SetupStateMachine.stepFor(
            permissions(recordAudio = false, notifications = false),
            micPermanentlyDenied = false,
            snapshot(captureMode = null),
        )
        assertEquals(SetupStep.MODE, step)
    }

    @Test
    fun `D33 a chosen capture mode proceeds past Mode to the permission gates`() {
        val step = SetupStateMachine.stepFor(
            permissions(recordAudio = false, notifications = false),
            micPermanentlyDenied = false,
            snapshot(captureMode = CaptureMode.LOCAL_MICROPHONE),
        )
        assertEquals(SetupStep.MICROPHONE, step)
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

    // --- D33: SetupStep.BLUETOOTH_PERMISSION -------------------------------------------------

    @Test
    fun `D33 Bluetooth mode with BLUETOOTH_CONNECT ungranted and not declined shows BluetoothPermission`() {
        val step = SetupStateMachine.stepFor(
            permissions(recordAudio = true, notifications = false, bluetoothConnect = false),
            micPermanentlyDenied = false,
            snapshot(captureMode = CaptureMode.BLUETOOTH_RADIO),
        )
        assertEquals(SetupStep.BLUETOOTH_PERMISSION, step)
    }

    @Test
    fun `D33 Bluetooth mode with BLUETOOTH_CONNECT granted skips BluetoothPermission`() {
        val step = SetupStateMachine.stepFor(
            permissions(recordAudio = true, notifications = false, bluetoothConnect = true),
            micPermanentlyDenied = false,
            snapshot(captureMode = CaptureMode.BLUETOOTH_RADIO),
        )
        assertEquals(SetupStep.NOTIFICATIONS, step)
    }

    @Test
    fun `D33 declining Bluetooth permission never returns to BluetoothPermission again`() {
        val step = SetupStateMachine.stepFor(
            permissions(recordAudio = true, notifications = false, bluetoothConnect = false),
            micPermanentlyDenied = false,
            // S02c's own "Not now" flips the mode to USB and records the decline (belt + braces).
            snapshot(captureMode = CaptureMode.USB_RADIO, bluetoothPermissionDeclined = true),
        )
        assertEquals(SetupStep.NOTIFICATIONS, step)
    }

    @Test
    fun `D33 USB and local-microphone modes never show BluetoothPermission regardless of the OS grant`() {
        val usbStep = SetupStateMachine.stepFor(
            permissions(recordAudio = true, notifications = false, bluetoothConnect = false),
            micPermanentlyDenied = false,
            snapshot(captureMode = CaptureMode.USB_RADIO),
        )
        val micStep = SetupStateMachine.stepFor(
            permissions(recordAudio = true, notifications = false, bluetoothConnect = false),
            micPermanentlyDenied = false,
            snapshot(captureMode = CaptureMode.LOCAL_MICROPHONE),
        )
        assertEquals(SetupStep.NOTIFICATIONS, usbStep)
        assertEquals(SetupStep.NOTIFICATIONS, micStep)
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

    // --- D33: SetupStep.RIG_TRANSPORT / RIG_BLUETOOTH ----------------------------------------

    @Test
    fun `D33 a real rig chosen with no transport yet shows RigTransport`() {
        val step = SetupStateMachine.stepFor(
            permissions(true, true),
            micPermanentlyDenied = false,
            snapshot(radioChoice = RadioChoice.TH_D75A, rigTransport = null),
        )
        assertEquals(SetupStep.RIG_TRANSPORT, step)
    }

    @Test
    fun `D33 choosing no radio never routes through RigTransport at all`() {
        val step = SetupStateMachine.stepFor(
            permissions(true, true),
            micPermanentlyDenied = false,
            snapshot(radioChoice = RadioChoice.NONE, rigTransport = null, setupComplete = false),
        )
        assertEquals(SetupStep.READY, step)
    }

    @Test
    fun `D33 USB transport chosen for a real rig proceeds straight through to Ready`() {
        val step = SetupStateMachine.stepFor(
            permissions(true, true),
            micPermanentlyDenied = false,
            snapshot(
                radioChoice = RadioChoice.TH_D75A,
                rigTransport = RigTransportKind.USB_SERIAL,
                setupComplete = false,
            ),
        )
        assertEquals(SetupStep.READY, step)
    }

    @Test
    fun `D33 Bluetooth transport chosen but not yet verified shows RigBluetooth`() {
        val step = SetupStateMachine.stepFor(
            permissions(true, true),
            micPermanentlyDenied = false,
            snapshot(
                radioChoice = RadioChoice.TH_D75A,
                rigTransport = RigTransportKind.BLUETOOTH_SPP,
                rigBluetoothVerified = false,
            ),
        )
        assertEquals(SetupStep.RIG_BLUETOOTH, step)
    }

    @Test
    fun `D33 Bluetooth transport verified proceeds straight through to Ready`() {
        val step = SetupStateMachine.stepFor(
            permissions(true, true),
            micPermanentlyDenied = false,
            snapshot(
                radioChoice = RadioChoice.TH_D75A,
                rigTransport = RigTransportKind.BLUETOOTH_SPP,
                rigBluetoothVerified = true,
                setupComplete = false,
            ),
        )
        assertEquals(SetupStep.READY, step)
    }

    /**
     * AC-130 (FR-CAP-9, FR-RIG-13) — written before S00's picker existed, per
     * `spec/e2e-capture-modes-plan.md` WPD's own instruction ("a picker built without it
     * hard-couples the axes and passes everything else"). Choosing Bluetooth mode presets
     * Bluetooth SPP for the rig and a wired route for audio (`CaptureModePresets.presetsFor`);
     * this test proves the **opposite** combination — Bluetooth *rig control* with **wired**
     * audio, the one combination no mode names by default — is still fully reachable by
     * overriding nothing about the rig axis and everything about the fact that a preset is not an
     * enforcement: [SetupStateMachine] never refuses `audioRouteKind = USB` (a route kind, not
     * [AudioRouteKind.WIRED_HEADSET], chosen here to prove the axis truly does not constrain the
     * other one at all — any override is admissible) alongside `rigTransport = BLUETOOTH_SPP`.
     */
    @Test
    fun `AC_130_bluetooth_control_with_wired_audio_is_reachable`() {
        // Mode chosen: Bluetooth. The operator then overrides the audio route to plain USB
        // (still not the mode's own Bluetooth-audio route) while keeping the rig's own
        // Bluetooth-SPP preset — the combination FR-RIG-13 says must never be blocked.
        val step = SetupStateMachine.stepFor(
            permissions(true, true, bluetoothConnect = true),
            micPermanentlyDenied = false,
            snapshot(
                captureMode = CaptureMode.BLUETOOTH_RADIO,
                selectedInputId = "usb-1",
                inputVerified = true,
                radioChoice = RadioChoice.TH_D75A,
                rigTransport = RigTransportKind.BLUETOOTH_SPP,
                rigBluetoothVerified = true,
                setupComplete = false,
            ),
        )
        assertEquals(SetupStep.READY, step)
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
