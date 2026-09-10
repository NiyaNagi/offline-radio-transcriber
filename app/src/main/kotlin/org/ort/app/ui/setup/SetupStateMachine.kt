package org.ort.app.ui.setup

import org.ort.app.permissions.PermissionsFlow
import org.ort.app.permissions.PermissionsState
import org.ort.core.capture.CaptureMode
import org.ort.core.capture.RigTransportKind

/**
 * Everything [SetupStateMachine] needs from [SetupStore] to decide which step to show, as one
 * immutable snapshot — so the decision itself stays a plain data-in/data-out function, the same
 * split the pre-WP9 flow established between [org.ort.app.permissions.PermissionsFlow] (pure) and
 * `MainActivity` (the `Context`-touching glue around it).
 */
public data class SetupSnapshot(
    val welcomeSeen: Boolean,
    /** D33/FR-CAP-8. `null` until S00 is walked. */
    val captureMode: CaptureMode?,
    /** S02c's "Not now — use USB instead". */
    val bluetoothPermissionDeclined: Boolean,
    val notificationsSkipped: Boolean,
    val selectedInputId: String?,
    val inputVerified: Boolean,
    val levelInBand: Boolean,
    val overnightStepSeen: Boolean,
    val radioChoice: RadioChoice?,
    /** FR-RIG-13. `null` until S09b is walked (never reached at all when [radioChoice] is
     * [RadioChoice.NONE]). */
    val rigTransport: RigTransportKind?,
    /** Whether S10b's checklist has reached `RigLinkState.Verified` — see [SetupStore]'s own doc
     * comment. */
    val rigBluetoothVerified: Boolean,
    val setupComplete: Boolean,
)

/**
 * R-080..R-084 (ui-conformance-plan WP9), extended by D33/P19 WPD: the pure decision logic behind
 * `Flow-Setup.dc.html`/`Flow-Mode.dc.html`'s "each step verifiable before proceeding, resumed at
 * the first unverified step". [stepFor] is called after every state change ([SetupActivity]'s own
 * `refreshStep`, mirroring the pattern `MainActivity.refreshScreen` already used pre-WP9) rather
 * than driven by forward-only navigation, so leaving the sequence mid-way and coming back always
 * lands on the correct step with no separate "resume" code path to drift out of sync with the
 * "advance" path.
 *
 * [SetupStep.VERIFY], [SetupStep.ROUTE_MISMATCH], [SetupStep.RADIO_USB] and
 * [SetupStep.RADIO_VERIFIED] are deliberately never returned here — all four are transient, live
 * results of an action taken on an earlier step ([RouteCheck] from [SetupStep.INPUT]'s "Verify
 * this input"; [SetupActivity.onConnectRigTransport]'s live [org.ort.pipeline.capture.RigStatus] read from
 * [SetupStep.RADIO]/[SetupStep.RIG_TRANSPORT]), not states worth resuming into on a fresh launch
 * (see [SetupStep]'s own doc comment). **[SetupStep.RIG_TRANSPORT] and [SetupStep.RIG_BLUETOOTH]
 * are different** (D33, FR-RIG-13): which rig-control transport to use is a genuinely separate,
 * resumable decision from which rig was chosen — a process death between choosing the TH-D75A and
 * picking Bluetooth-vs-USB for it must resume at [SetupStep.RIG_TRANSPORT], not skip past it the
 * way [SetupStep.RADIO_USB]/[SetupStep.RADIO_VERIFIED] already (by long-standing, unchanged
 * design) skip past themselves.
 *
 * [SetupStep.OVERNIGHT]'s two actions ("Open the setting" / "Skip for now", `Setup-Battery.dc.html`)
 * both mark [SetupSnapshot.overnightStepSeen] — this step is walked once, like every other, but
 * (per `Flow-Setup.dc.html`'s own footnote) never gates capture again after that, which is why it
 * is absent from [isComplete].
 */
public object SetupStateMachine {

    /** `null` means every gate has cleared and [setupComplete][SetupSnapshot.setupComplete] has
     * been reached — [SetupActivity] hands back to `MainActivity`, which starts capture. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    public fun stepFor(
        permissions: PermissionsState,
        micPermanentlyDenied: Boolean,
        snapshot: SetupSnapshot,
    ): SetupStep? {
        if (!snapshot.welcomeSeen) return SetupStep.WELCOME
        if (snapshot.captureMode == null) return SetupStep.MODE
        if (!permissions.recordAudioGranted && micPermanentlyDenied) return SetupStep.MICROPHONE_DENIED
        if (!permissions.recordAudioGranted) return SetupStep.MICROPHONE
        if (needsBluetoothPermission(permissions, snapshot)) return SetupStep.BLUETOOTH_PERMISSION
        if (!permissions.notificationsGranted && !snapshot.notificationsSkipped) return SetupStep.NOTIFICATIONS
        if (snapshot.selectedInputId == null) return SetupStep.INPUT
        if (!snapshot.inputVerified) return SetupStep.INPUT
        if (!snapshot.levelInBand) return SetupStep.LEVEL
        if (!snapshot.overnightStepSeen) return SetupStep.OVERNIGHT
        if (snapshot.radioChoice == null) return SetupStep.RADIO
        if (needsRigTransport(snapshot)) return SetupStep.RIG_TRANSPORT
        if (needsRigBluetoothLink(snapshot)) return SetupStep.RIG_BLUETOOTH
        if (!snapshot.setupComplete) return SetupStep.READY
        return null
    }

    /** D33/S02c: Bluetooth mode only, and only while the platform permission genuinely gates
     * anything — a decline flips [SetupSnapshot.captureMode] away from
     * [CaptureMode.BLUETOOTH_RADIO] (S02c's own "Not now — use USB instead"), so this condition
     * naturally stops firing the instant that happens; [SetupSnapshot.bluetoothPermissionDeclined]
     * guards the same case defensively, the same belt-and-suspenders style
     * [SetupSnapshot.notificationsSkipped] already uses above. */
    private fun needsBluetoothPermission(permissions: PermissionsState, snapshot: SetupSnapshot): Boolean =
        snapshot.captureMode == CaptureMode.BLUETOOTH_RADIO &&
            !permissions.bluetoothConnectGranted &&
            !snapshot.bluetoothPermissionDeclined

    /** FR-RIG-13: a real rig was chosen (never [RadioChoice.NONE], which has no transport to pick
     * at all) but [SetupSnapshot.rigTransport] has not been decided yet. */
    private fun needsRigTransport(snapshot: SetupSnapshot): Boolean =
        snapshot.radioChoice != null && snapshot.radioChoice != RadioChoice.NONE && snapshot.rigTransport == null

    /** D34/FR-RIG-14/15: Bluetooth SPP was chosen as the rig transport but S10b's open -> identify
     * -> verify checklist has not reached [SetupStore.rigBluetoothVerified] yet. */
    private fun needsRigBluetoothLink(snapshot: SetupSnapshot): Boolean =
        snapshot.rigTransport == RigTransportKind.BLUETOOTH_SPP && !snapshot.rigBluetoothVerified

    /**
     * `MainActivity`'s fast-path check (register R-080's "MainActivity becomes the router"): true
     * only once the operator has tapped `Start capture` on S12 *and* the two capture-gating
     * permissions are still granted right now — a permission revoked after setup finished must
     * send the operator back through [stepFor], never straight into capture (constitution IV: a
     * route that is not verified never silently proceeds; the same discipline applies to the
     * permissions that gate capture at all).
     */
    public fun isComplete(permissions: PermissionsState, snapshot: SetupSnapshot): Boolean =
        snapshot.setupComplete && PermissionsFlow.captureIsPermitted(permissions)
}
