@file:Suppress("MatchingDeclarationName") // RigBluetoothViewState is one of several public declarations here.

package org.ort.app.ui.setup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.RadioRow
import org.ort.app.ui.components.RowTone
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType

/** S10b's whole view-state (`Setup-Rig-Bluetooth.dc.html`, D33/D34, FR-RIG-14/15). [linkState] is
 * `null` before the operator has picked a paired device at all — no checklist row renders yet. */
public data class RigBluetoothViewState(
    val rigDisplayName: String,
    val devices: List<PairedDevice>,
    val selectedAddress: String?,
    val linkState: RigLinkState?,
)

/**
 * S10b (`Setup-Rig-Bluetooth.dc.html`, D33/D34) — the paired-device list (SPP-capable selectable,
 * headset-only listed dim and not selectable, unknown capability shown as such and still
 * selectable — never guessed as incapable, constitution I), `Pair a device in system settings`,
 * `Refresh`, and the open -> identify -> verify checklist driven by [RigLinkPort]'s
 * [RigLinkState] flow (see that file's own doc comment for why the real `:rig-bluetooth` adapter
 * is not yet wired in here). `Continue` enables only once [RigLinkState.Verified] is reached
 * (E2-E10); a [RigLinkState.Lost]/[RigLinkState.Failed]/[RigLinkState.NoPermission] renders as a
 * banner, never a blank screen (E2-E11, FR-RIG-15).
 */
@Composable
public fun RigBluetoothScreen(
    state: RigBluetoothViewState,
    onSelectDevice: (String) -> Unit,
    onPairInSettings: () -> Unit,
    onRefresh: () -> Unit,
    onContinue: () -> Unit,
    onUseUsbInstead: () -> Unit,
) {
    SetupScaffold(
        step = SetupStep.RIG_BLUETOOTH,
        title = "Connect the ${state.rigDisplayName}",
        subtitle = "Bluetooth on the radio: Menu › Bluetooth › On, then pair it in Android settings",
        onBack = null,
        bottomActions = {
            PrimaryButton(
                text = "Continue",
                onClick = onContinue,
                enabled = state.linkState is RigLinkState.Verified,
                modifier = Modifier.fillMaxWidth().testTag("setup-rig-bt-continue"),
            )
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextAction(
                    text = "Use USB instead",
                    onClick = onUseUsbInstead,
                    modifier = Modifier.testTag("setup-rig-bt-use-usb"),
                )
            }
        },
    ) {
        Column {
            Text(text = "Paired devices".uppercase(), style = OrtType.sectionLabel, color = OrtColors.textFaint)
            Column {
                state.devices.forEach { device ->
                    PairedDeviceRow(
                        device = device,
                        selected = device.address == state.selectedAddress,
                        onSelect = { onSelectDevice(device.address) },
                    )
                }
            }
            // R-805 (register, tour run, font scale 2.0): with neither action weighted, both
            // measured against the row's full width and "Pair a device in system settings"'
            // own multi-line wrap left "Refresh" placed in whatever sliver `SpaceBetween`
            // computed from the *wrapped* left block's reported width, not its own text —
            // one letter per line down the right edge. `weight(1f)` on the left action alone
            // means Compose measures the unweighted "Refresh" first, at its own intrinsic
            // (never-shrunk) size, then gives the left action only what remains to wrap into.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextAction(
                    text = "Pair a device in system settings",
                    onClick = onPairInSettings,
                    modifier = Modifier.weight(1f).testTag("setup-rig-bt-pair-in-settings"),
                )
                TextAction(text = "Refresh", onClick = onRefresh, modifier = Modifier.testTag("setup-rig-bt-refresh"))
            }
        }

        state.linkState?.let { linkState ->
            bannerFor(linkState)?.let {
                org.ort.app.ui.components.Banner(
                    title = it.first,
                    body = it.second,
                    tone = org.ort.app.ui.components.BannerTone.DEGRADED,
                    modifier = Modifier.fillMaxWidth().testTag("setup-rig-bt-lost-banner"),
                )
            }
            Column {
                ChecklistRow(
                    label = "Serial link open",
                    state = checklistStateFor(linkState, stage = 1),
                    modifier = Modifier.testTag("setup-rig-bt-checklist-open"),
                )
                ChecklistRow(
                    label = "Identify the rig",
                    state = checklistStateFor(linkState, stage = 2),
                    modifier = Modifier.testTag("setup-rig-bt-checklist-identify"),
                )
                ChecklistRow(
                    label = "Verify the command set",
                    state = checklistStateFor(linkState, stage = 3),
                    modifier = Modifier.testTag("setup-rig-bt-checklist-verify"),
                )
            }
        }

        Text(
            text = "If the link drops during a session the app keeps capturing, reads the last " +
                "frequency as stale and reconnects on its own. It never pairs or unpairs anything.",
            style = OrtType.cardBody,
            color = OrtColors.textDim,
        )
    }
}

@Composable
private fun PairedDeviceRow(device: PairedDevice, selected: Boolean, onSelect: () -> Unit) {
    val selectable = device.sppCapable != false
    val subtitle = when (device.sppCapable) {
        true -> "${device.address} · serial port profile"
        false -> "headset profile only · no serial port · not a rig link"
        null -> "${device.address} · serial-port support unknown"
    }
    RadioRow(
        label = device.name,
        selected = selected,
        onClick = { if (selectable) onSelect() },
        subtitle = subtitle,
        tone = if (!selectable) RowTone.Warning else RowTone.Neutral,
        modifier = Modifier.testTag("setup-rig-bt-device-${device.address}"),
    )
}

private enum class ChecklistRowState { DONE, IN_PROGRESS, PENDING }

/** Which stage (1 = link open, 2 = identify, 3 = verify) [linkState] has reached — see this file's
 * own class doc comment for why identify/verify collapse to consecutive states in this seam. */
private fun checklistStateFor(linkState: RigLinkState, stage: Int): ChecklistRowState {
    val reached = when (linkState) {
        RigLinkState.Opening -> 0
        RigLinkState.Open -> 1
        is RigLinkState.Identified -> 2
        is RigLinkState.Verified -> 3
        is RigLinkState.Lost, is RigLinkState.Failed, RigLinkState.NoPermission -> -1
    }
    return when {
        reached < 0 -> ChecklistRowState.PENDING
        stage <= reached -> ChecklistRowState.DONE
        stage == reached + 1 -> ChecklistRowState.IN_PROGRESS
        else -> ChecklistRowState.PENDING
    }
}

/** E2-E11/FR-RIG-15: title + body for a dropped/failed/no-permission link — `null` for every
 * in-progress or successful state, which renders no banner at all. */
private fun bannerFor(linkState: RigLinkState): Pair<String, String>? = when (linkState) {
    is RigLinkState.Lost ->
        "The link dropped" to
            "${linkState.reason}. Pick the device again, or use USB instead — nothing captured is lost."
    is RigLinkState.Failed ->
        "Could not connect" to
            "${linkState.reason}. Check the radio is powered on and paired, then try again."
    RigLinkState.NoPermission ->
        "Nearby devices permission is needed" to "Grant it from Settings, or use USB instead."
    else -> null
}

@Composable
private fun ChecklistRow(label: String, state: ChecklistRowState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state) {
            ChecklistRowState.DONE -> Box(
                modifier = Modifier.size(18.dp).background(OrtColors.accentGreen, CircleShape),
            )
            ChecklistRowState.IN_PROGRESS -> Canvas(modifier = Modifier.size(18.dp)) {
                drawCircle(color = OrtColors.accentGreen, style = Stroke(2.dp.toPx()))
            }
            ChecklistRowState.PENDING -> Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(androidx.compose.ui.graphics.Color.Transparent, CircleShape),
            )
        }
        Text(
            text = label,
            style = OrtType.control,
            color = if (state == ChecklistRowState.PENDING) OrtColors.textLow else OrtColors.textBody,
        )
    }
}
