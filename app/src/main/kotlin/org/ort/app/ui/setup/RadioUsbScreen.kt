package org.ort.app.ui.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.ort.app.ui.components.FailedState
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.TextAction
import org.ort.pipeline.capture.RigStatus

/**
 * S10 (`Setup-Rig-Usb.dc.html`, R-084) — the artboard's own checklist ("USB device attached",
 * "Waiting for USB permission", "Identify the rig", "Verify the command set") is a *live* progress
 * view this package cannot honestly drive: `:rig` and `:rig-usb` are unbuilt (register R-084 —
 * both modules hold only a `package-info.kt`), so there is no real USB-attach signal anywhere in
 * the app to report. Per the brief's explicit instruction, [RigStatus] is the only thing this
 * screen is real against: [RigStatus.State.Absent] (today's only real producer —
 * `RealCaptureService` calls `RigStatus.absent()` once per session, honestly) renders the failed
 * state below instead of a fabricated checklist (constitution I, guide §6.8's "a screen with
 * nothing to show because a capability is missing is failed, not empty"). [SetupActivity] normally
 * routes a [RigStatus.State.Connected]/[RigStatus.State.Stale] reading straight to
 * [RadioVerifiedScreen] instead of here, so the branch below is a defensive fallback, reachable
 * today only through the debug scenario simulator poking [RigStatus] directly (WP0), never through
 * real hardware — see this file's own `report` note in this package's final message for exactly
 * what "reachable" means for S10/S11 today: neither is reachable with real hardware (no rig
 * module exists to drive them), and only S11's *rendering* (not its entry path) is exercisable at
 * all, via that simulator.
 */
@Composable
public fun RadioUsbScreen(rigStatus: RigStatus.State, onBack: () -> Unit, onEnterFrequencyInstead: () -> Unit) {
    SetupScaffold(
        step = SetupStep.RADIO_USB,
        title = "Connect the radio",
        subtitle = "No rig support is built into this app yet",
        onBack = onBack,
        bottomActions = {
            PrimaryButton(
                text = "Enter the frequency instead",
                onClick = onEnterFrequencyInstead,
                modifier = Modifier.fillMaxWidth().testTag("setup-radio-usb-enter-frequency"),
            )
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextAction(text = "Back", onClick = onBack, modifier = Modifier.testTag("setup-radio-usb-back"))
            }
        },
    ) {
        when (rigStatus) {
            is RigStatus.State.Absent -> FailedState(
                title = "No rig support in this build yet",
                body = "The rig module (\":rig\", \":rig-usb\") has no CAT implementation yet, so " +
                    "there is nothing real to detect, identify or verify over USB. Enter the " +
                    "frequency by hand for now — this can be changed later from Settings once a " +
                    "rig module ships.",
                modifier = Modifier.testTag("setup-radio-usb-unsupported"),
            )
            is RigStatus.State.Connected -> RigVerifiedContent(rigStatus)
            is RigStatus.State.Stale -> RigVerifiedContent(rigStatus.lastKnown)
        }
    }
}
