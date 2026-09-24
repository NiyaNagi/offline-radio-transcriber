package org.ort.app.ui.setup

import org.ort.app.permissions.PermissionsState
import org.ort.core.capture.CaptureMode
import org.ort.core.capture.RigTransportKind

/**
 * Everything [SetupStateMachine] needs from [SetupStore] to decide which step to show, as one
 * immutable snapshot — so the decision itself stays a plain data-in/data-out function, the same
 * split the pre-WP9 flow established between [org.ort.app.permissions.PermissionsFlow] (pure) and
 * `MainActivity` (the `Context`-touching glue around it).
 *
 * **P39 (D58) removed four fields from this snapshot**, and each one is a gate that stopped being a
 * gate rather than a fact that stopped being recorded — all four still live on [SetupStore]:
 * `jurisdictionNoticeSeen` and `analyticsConsentSeen` are written by [SetupActivity.onBegin]
 * (folded onto Welcome, AC-166/AC-180), `notificationsSkipped` by the first-capture-start ask, and
 * `overnightStepSeen` by whatever surfaces the *Keep capture running* prompt. Nothing reads them to
 * decide a step any more, which is exactly the point: a gate whose evidence cannot exist before
 * capture cannot be a pre-capture gate (AC-189 as amended, R-1161).
 */
public data class SetupSnapshot(
    val welcomeSeen: Boolean,
    /** D33/FR-CAP-8. `null` until the mode has been chosen. */
    val captureMode: CaptureMode?,
    /** The Bluetooth-permission step's own "Not now — use USB instead". */
    val bluetoothPermissionDeclined: Boolean,
    val selectedInputId: String?,
    val inputVerified: Boolean,
    val levelInBand: Boolean,
    /**
     * R-1170's second half, routed here by P39. `Continue` on [SetupStep.LISTEN] is never disabled
     * for validation (AC-201) — a quiet band at two in the morning used to strand the operator with
     * no forward, no back and no later. Tapping it out of band records **unresolved but
     * acknowledged**, which is a different state from not-yet-reached: the flow proceeds, and
     * [READY]'s Level row renders amber with a way back.
     *
     * This is deliberately **not** the shape [inputVerified] has, and the difference is
     * constitution IV. An unverified *route* has no acknowledged escape at all: recording the room
     * instead of the radio is the highest-consequence silent failure in the system, so that gate is
     * unconditional below. A quiet *level* is visible to the operator on the meter in front of them
     * and is theirs to judge.
     */
    val levelAcknowledged: Boolean,
    val radioChoice: RadioChoice?,
    /** FR-RIG-13. `null` until the rig transport has been chosen. */
    val rigTransport: RigTransportKind?,
    /** Whether the Bluetooth rig checklist has reached `RigLinkState.Verified`. */
    val rigBluetoothVerified: Boolean,
    /**
     * P39 (D58): whether a rig module with a **real CAT implementation** exists to talk to at all.
     * The whole rig branch is entered only when this is true and the chosen mode implies a rig —
     * `false` on every build shipped today, because no rig module has a CAT implementation
     * ([RigLinkPort]'s own doc comment), so that branch simply does not appear. Showing an operator
     * a "which transport?" question about a radio nothing can actually command is the same defect
     * class as a confident wrong callsign, arriving as a wizard page.
     */
    val rigModuleAvailable: Boolean,
    /**
     * P22 (FR-AST-10..12, AC-184, AC-188): whether every model the detected tier requires is either
     * bundled or already installed and verified. Not a [SetupStore] preference — [SetupStore] has no
     * way to know what the manifest or the installed-asset state currently is, so
     * [SetupStore.snapshot] defaults it to `true` and [SetupActivity]'s own `currentSnapshot()`
     * overrides it with the freshly read fact. It is also what [setupTotalSteps] counts against.
     */
    val requiredModelsInstalled: Boolean,
    /**
     * Whether `Start capture` has ever been tapped. **Read by [SetupStateMachine.isComplete] only —
     * never by [SetupStateMachine.stepFor]** (P39, D58's first structural rule). Gating the terminal
     * screen behind this latch is what left the flow with no terminal state on re-entry: once the
     * flag was true, [SetupStep.READY] could never be shown again, `stepFor` returned `null`, and
     * setup handed control back to the router that had just sent it here (R-1161).
     */
    val setupComplete: Boolean,
)

/**
 * The pure decision logic behind the guided sequence's "each step verifiable before proceeding,
 * resumed at the first unverified step". [stepFor] is called after every state change
 * ([SetupActivity]'s own `refreshStep`) rather than driven by forward-only navigation, so leaving
 * the sequence mid-way and coming back always lands on the correct step with no separate "resume"
 * code path to drift out of sync with the "advance" path.
 *
 * **P39 (D58) fixed the two structural faults R-1161 was a symptom of.**
 *
 * 1. **The terminal state is a function of "no gates remain", never of a latch.** [stepFor] returns
 *    [SetupStep.READY] the moment every gate has cleared and is **non-null** — there is no longer a
 *    "nothing to show" answer at all. [SetupStep.READY] used to sit behind `!setupComplete`, so the
 *    flow's own last screen became unreachable the instant that flag was set and re-entry had
 *    nowhere to land.
 * 2. **Setup never treats "nothing left to do" as an instruction to hand control back.** Because
 *    this function cannot return `null`, [SetupActivity] has no null branch to hand back from. It
 *    leaves only on an explicit operator action — `Start capture`, or the exit back to the app. Two
 *    components that each defer to the other are a cycle by construction, not by accident, and that
 *    is precisely what `MainActivity.route` and `refreshStep`'s null branch were.
 *
 * [SetupStep.ROUTE_MISMATCH], [SetupStep.RADIO_USB] and [SetupStep.RADIO_VERIFIED] are deliberately
 * never returned here — all three are transient, live results of an action taken on an earlier step,
 * not states worth resuming into on a fresh launch. Neither is [SetupStep.OVERNIGHT], and that is
 * now a rule rather than an omission (AC-189 as amended, AC-199).
 */
public object SetupStateMachine {

    /**
     * The step to show. **Never `null`** — see this object's own doc comment for why that is the
     * whole of D58's second structural rule.
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    public fun stepFor(
        permissions: PermissionsState,
        micPermanentlyDenied: Boolean,
        snapshot: SetupSnapshot,
    ): SetupStep {
        if (!snapshot.welcomeSeen) return SetupStep.WELCOME
        // A permanently denied microphone is a real halt and outranks everything below it: the mode
        // surface fires the system dialog, and on this device that dialog will never appear again.
        if (!permissions.recordAudioGranted && micPermanentlyDenied) return SetupStep.MICROPHONE_DENIED
        // AC-204: there is no explainer step. The mode surface *is* the surface that asks for the
        // microphone, so an ungranted microphone resumes there — one more tap on a mode re-fires the
        // system dialog with the rationale already on screen.
        if (snapshot.captureMode == null || !permissions.recordAudioGranted) return SetupStep.MODE
        if (needsBluetoothPermission(permissions, snapshot)) return SetupStep.BLUETOOTH_PERMISSION
        if (snapshot.selectedInputId == null) return SetupStep.LISTEN
        // Constitution IV, and the one gate with no acknowledged escape: a route that is not the
        // selected device halts capture, and no amount of tapping past it may change that.
        if (!snapshot.inputVerified) return SetupStep.LISTEN
        if (!snapshot.levelInBand && !snapshot.levelAcknowledged) return SetupStep.LISTEN
        if (needsRigChoice(snapshot)) return SetupStep.RADIO
        if (needsRigTransport(snapshot)) return SetupStep.RIG_TRANSPORT
        if (needsRigBluetoothLink(snapshot)) return SetupStep.RIG_BLUETOOTH
        if (!snapshot.requiredModelsInstalled) return SetupStep.MODELS
        return SetupStep.READY
    }

    /** D33: Bluetooth mode only, and only while the platform permission genuinely gates anything —
     * a decline flips [SetupSnapshot.captureMode] away from [CaptureMode.BLUETOOTH_RADIO], so this
     * condition naturally stops firing the instant that happens;
     * [SetupSnapshot.bluetoothPermissionDeclined] guards the same case defensively. */
    private fun needsBluetoothPermission(permissions: PermissionsState, snapshot: SetupSnapshot): Boolean =
        snapshot.captureMode == CaptureMode.BLUETOOTH_RADIO &&
            !permissions.bluetoothConnectGranted &&
            !snapshot.bluetoothPermissionDeclined

    /**
     * P39 (D58): the rig branch is entered only when there is a rig module with a real CAT
     * implementation to talk to **and** the chosen mode implies a rig at all. A local-microphone
     * operator was never going to answer "which rig?" usefully, and on current builds
     * [SetupSnapshot.rigModuleAvailable] is `false`, so nobody is.
     */
    private fun needsRigChoice(snapshot: SetupSnapshot): Boolean =
        rigBranchApplies(snapshot) && snapshot.radioChoice == null

    /** FR-RIG-13: a real rig was chosen (never [RadioChoice.NONE], which has no transport to pick
     * at all) but [SetupSnapshot.rigTransport] has not been decided yet. */
    private fun needsRigTransport(snapshot: SetupSnapshot): Boolean = rigBranchApplies(snapshot) &&
        snapshot.radioChoice != null &&
        snapshot.radioChoice != RadioChoice.NONE &&
        snapshot.rigTransport == null

    /** D34/FR-RIG-14/15: Bluetooth SPP was chosen as the rig transport but the open -> identify ->
     * verify checklist has not reached [SetupStore.rigBluetoothVerified] yet. */
    private fun needsRigBluetoothLink(snapshot: SetupSnapshot): Boolean = rigBranchApplies(snapshot) &&
        snapshot.rigTransport == RigTransportKind.BLUETOOTH_SPP &&
        !snapshot.rigBluetoothVerified

    private fun rigBranchApplies(snapshot: SetupSnapshot): Boolean =
        snapshot.rigModuleAvailable && snapshot.captureMode?.impliesARig() == true

    private fun CaptureMode.impliesARig(): Boolean = when (this) {
        CaptureMode.USB_RADIO, CaptureMode.BLUETOOTH_RADIO -> true
        CaptureMode.LOCAL_MICROPHONE -> false
    }

    /**
     * `MainActivity`'s fast-path check: true only once the operator has tapped `Start capture`
     * *and* the permission that genuinely gates capture is still granted right now — a microphone
     * revoked after setup finished must send the operator back through [stepFor], never straight
     * into capture (constitution IV: a route that is not verified never silently proceeds; the same
     * discipline applies to the permission that gates capture at all).
     *
     * **P39 (D58): the notification permission is no longer part of this.** It never gated capture
     * in substance — a foreground service starts without it, the notification simply is not
     * displayed — and it only appeared here because `NOTIFICATIONS` used to be a setup step whose
     * skip flag `MainActivity` folded into `notificationsGranted`. With the ask moved to first
     * capture start there is no such flag to fold, and leaving the clause in place would have
     * rebuilt R-1161's cycle exactly: `Start capture` sets the latch, `MainActivity` finds capture
     * "not permitted" over a notification permission, routes back to Setup, Setup finds every gate
     * clear and shows [SetupStep.READY] again, forever. [org.ort.app.permissions.PermissionsFlow.captureIsPermitted]
     * itself is unchanged — it is not this package's file, and it still describes the pre-D58 flow's
     * own rule correctly for the callers that want it.
     */
    public fun isComplete(permissions: PermissionsState, snapshot: SetupSnapshot): Boolean =
        snapshot.setupComplete && permissions.recordAudioGranted
}
