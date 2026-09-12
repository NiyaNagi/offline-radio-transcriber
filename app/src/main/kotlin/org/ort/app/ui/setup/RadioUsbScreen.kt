package org.ort.app.ui.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.ort.app.ui.components.FailedState
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.components.TextField
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.RigVerification

/**
 * S10 (`Setup-Rig-Usb.dc.html`, R-084) — the artboard's own checklist ("USB device attached",
 * "Waiting for USB permission", "Identify the rig", "Verify the command set") is a *live* progress
 * view this package cannot honestly drive: `:rig` and `:rig-usb` are unbuilt (register R-084 —
 * both modules hold only a `package-info.kt`), so there is no real USB-attach signal anywhere in
 * the app to report. Per the brief's explicit instruction, [RigStatus] is the only thing this
 * screen is real against: [RigStatus.State.Absent] (today's only real producer —
 * `RealCaptureService` calls `RigStatus.absent()` once per session, honestly) renders the failed
 * state below instead of a fabricated checklist (constitution I, guide §6.8's "a screen with
 * nothing to show because a capability is missing is failed, not empty"), with WP2's shared
 * [TextField] (its follow-up landed after this screen's first version, which had no way to actually
 * capture the frequency it invited the operator to enter by hand) to take a real MHz value before
 * falling back to manual frequency logging. [SetupActivity] normally routes a
 * [RigStatus.State.Connected]/[RigStatus.State.Stale] reading straight to [RadioVerifiedScreen]
 * instead of here, so that branch below is a defensive fallback, reachable today only through the
 * debug scenario simulator poking [RigStatus] directly (WP0), never through real hardware — see
 * this file's own `report` note in this package's final message for exactly what "reachable" means
 * for S10/S11 today: neither is reachable with real hardware (no rig module exists to drive them),
 * and only S11's *rendering* (not its entry path) is exercisable at all, via that simulator.
 *
 * R-344 (validator pass 4, halt): also the destination for S09's third row ("No radio — I will
 * enter the frequency"), routed here rather than straight to S12 so a real value is actually
 * collected — [SetupActivity.onChooseRig]'s own doc comment has the full account. The button
 * below is disabled until [parseMegahertzToHz] would accept the current text, so neither entry
 * path can proceed with a blank or unparseable frequency (constitution I: never silently lose a
 * fact) — [SetupActivity.onEnterFrequency] guards the same thing again, belt and suspenders.
 */
@Composable
public fun RadioUsbScreen(rigStatus: RigStatus.State, onBack: () -> Unit, onEnterFrequency: (Long?) -> Unit) {
    var frequencyText by remember { mutableStateOf("") }
    val enteredHz = parseMegahertzToHz(frequencyText)
    SetupScaffold(
        step = SetupStep.RADIO_USB,
        title = "Connect the radio",
        subtitle = "No rig support is built into this app yet",
        onBack = onBack,
        bottomActions = {
            PrimaryButton(
                text = "Enter the frequency instead",
                onClick = { onEnterFrequency(enteredHz) },
                enabled = enteredHz != null,
                modifier = Modifier.fillMaxWidth().testTag("setup-radio-usb-enter-frequency"),
            )
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextAction(text = "Back", onClick = onBack, modifier = Modifier.testTag("setup-radio-usb-back"))
            }
        },
    ) {
        when (rigStatus) {
            is RigStatus.State.Absent -> {
                FailedState(
                    title = "No rig support in this build yet",
                    body = "The rig module (\":rig\", \":rig-usb\") has no CAT implementation yet, so " +
                        "there is nothing real to detect, identify or verify over USB. Enter the " +
                        "frequency by hand for now — this can be changed later from Settings once a " +
                        "rig module ships.",
                    modifier = Modifier.testTag("setup-radio-usb-unsupported"),
                )
                TextField(
                    value = frequencyText,
                    onValueChange = { frequencyText = it },
                    label = "Frequency (MHz)",
                    placeholder = "145.230",
                    mono = true,
                    contentDescriptionText = "Frequency in megahertz",
                    modifier = Modifier.testTag("setup-radio-usb-frequency-field"),
                )
            }
            // R-1019 (register): shares RigVerifiedContent with S11 — a partially (or never)
            // verified reading must read the same honest "Command set" header that fix
            // established, never this defensive fallback's own separate copy still claiming
            // "Verified command set" regardless of RigStatus.verification.
            is RigStatus.State.Connected -> RigVerifiedContent(
                rigStatus,
                partiallyVerified = rigStatus.verification !is RigVerification.Full,
            )
            is RigStatus.State.Stale -> RigVerifiedContent(
                rigStatus.lastKnown,
                partiallyVerified = rigStatus.lastKnown.verification !is RigVerification.Full,
            )
        }
    }
}

/** `null` for a blank or unparseable entry — never a fabricated frequency (constitution I). A
 * valid MHz value (e.g. `"145.230"`) becomes an exact Hz [Long] the same way every other
 * frequency in this app is stored. */
internal fun parseMegahertzToHz(text: String): Long? {
    val mhz = text.trim().toDoubleOrNull() ?: return null
    if (mhz <= 0.0) return null
    return Math.round(mhz * MHZ_TO_HZ)
}

private const val MHZ_TO_HZ = 1_000_000.0
