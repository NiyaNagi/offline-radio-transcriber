package org.ort.app.ui.setup

import org.ort.app.permissions.PermissionsFlow
import org.ort.app.permissions.PermissionsState

/**
 * Everything [SetupStateMachine] needs from [SetupStore] to decide which step to show, as one
 * immutable snapshot — so the decision itself stays a plain data-in/data-out function, the same
 * split the pre-WP9 flow established between [org.ort.app.permissions.PermissionsFlow] (pure) and
 * `MainActivity` (the `Context`-touching glue around it).
 */
public data class SetupSnapshot(
    val welcomeSeen: Boolean,
    val notificationsSkipped: Boolean,
    val selectedInputId: String?,
    val inputVerified: Boolean,
    val levelInBand: Boolean,
    val overnightStepSeen: Boolean,
    val radioChoice: RadioChoice?,
    val setupComplete: Boolean,
)

/**
 * R-080..R-084: the pure decision logic behind `Flow-Setup.dc.html`'s "each step verifiable
 * before proceeding, resumed at the first unverified step". [stepFor] is called after every state
 * change ([SetupActivity]'s own `refreshStep`, mirroring the pattern `MainActivity.refreshScreen`
 * already used pre-WP9) rather than driven by forward-only navigation, so leaving the sequence
 * mid-way and coming back always lands on the correct step with no separate "resume" code path to
 * drift out of sync with the "advance" path.
 *
 * [SetupStep.VERIFY] and [SetupStep.ROUTE_MISMATCH] are deliberately never returned here — both
 * are transient, live results of [SetupStep.INPUT]'s "Verify this input" action ([RouteCheck]),
 * not states worth resuming into on a fresh launch (see [SetupStep]'s own doc comment).
 * [SetupStep.OVERNIGHT]'s two actions ("Open the setting" / "Skip for now", `Setup-Battery.dc.html`)
 * both mark [SetupSnapshot.overnightStepSeen] — this step is walked once, like every other, but
 * (per `Flow-Setup.dc.html`'s own footnote) never gates capture again after that, which is why it
 * is absent from [isComplete].
 */
public object SetupStateMachine {

    /** `null` means every gate has cleared and [setupComplete][SetupSnapshot.setupComplete] has
     * been reached — [SetupActivity] hands back to `MainActivity`, which starts capture. */
    public fun stepFor(
        permissions: PermissionsState,
        micPermanentlyDenied: Boolean,
        snapshot: SetupSnapshot,
    ): SetupStep? = when {
        !snapshot.welcomeSeen -> SetupStep.WELCOME
        !permissions.recordAudioGranted && micPermanentlyDenied -> SetupStep.MICROPHONE_DENIED
        !permissions.recordAudioGranted -> SetupStep.MICROPHONE
        !permissions.notificationsGranted && !snapshot.notificationsSkipped -> SetupStep.NOTIFICATIONS
        snapshot.selectedInputId == null -> SetupStep.INPUT
        !snapshot.inputVerified -> SetupStep.INPUT
        !snapshot.levelInBand -> SetupStep.LEVEL
        !snapshot.overnightStepSeen -> SetupStep.OVERNIGHT
        snapshot.radioChoice == null -> SetupStep.RADIO
        !snapshot.setupComplete -> SetupStep.READY
        else -> null
    }

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
