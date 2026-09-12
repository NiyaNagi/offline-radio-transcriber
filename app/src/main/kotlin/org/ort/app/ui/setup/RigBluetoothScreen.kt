@file:Suppress("MatchingDeclarationName") // RigBluetoothViewState is one of several public declarations here.

package org.ort.app.ui.setup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.RadioRow
import org.ort.app.ui.components.RowTone
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType
import java.util.Locale

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
 *
 * **R-1003/R-1005c (device field report — a trapped operator).** Three fixes against the artboard
 * as redrawn 2026-09-12 (`design/design-guide.md` §10.1's own commit):
 * - [onBack] restores the back chevron the board has always drawn (line 21 of the artboard) —
 *   `onBack = null` here was a conformance defect, not a design decision; [SetupActivity] is the
 *   one caller and now passes a real one.
 * - [onContinueWithoutConnecting] is the new middle action `Setup-Rig-Bluetooth.dc.html` draws
 *   between `Continue` and `Use USB instead`: a real escape for a Bluetooth-only rig, landing on
 *   FR-RIG-2's existing null-module/manual-frequency path exactly the way [SetupActivity]'s own
 *   report names ([ContinueWithoutConnectingAction]'s doc comment has the accessibility half).
 * - [onRequestBluetoothPermission] gives the [RigLinkState.NoPermission] banner an actual button —
 *   it used to say "Grant it from Settings" with nothing that did so.
 */
// R-1005c added the two new callbacks (onContinueWithoutConnecting, onRequestBluetoothPermission)
// and restored onBack -- every parameter here is a real, independently-wired action or the state
// this screen renders, matching this file's own established shape (SetupScaffold/Banner's own
// LongParameterList suppressions carry the identical justification: a deliberately flexible,
// heavily-wired setup screen, not a struct crying out to be bundled).
@Suppress("LongParameterList")
@Composable
public fun RigBluetoothScreen(
    state: RigBluetoothViewState,
    onSelectDevice: (String) -> Unit,
    onPairInSettings: () -> Unit,
    onRefresh: () -> Unit,
    onContinue: () -> Unit,
    onContinueWithoutConnecting: () -> Unit,
    onUseUsbInstead: () -> Unit,
    onRequestBluetoothPermission: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    SetupScaffold(
        step = SetupStep.RIG_BLUETOOTH,
        title = "Connect the ${state.rigDisplayName}",
        subtitle = "Bluetooth on the radio: Menu › Bluetooth › On, then pair it in Android settings",
        onBack = onBack,
        bottomActions = {
            RigBluetoothBottomActions(
                // R-1013/R-1014: VerifyTimedOut is partial success (Identified genuinely happened) —
                // Continue enables the same as a full Verified, never IdentifyTimedOut (nothing ever
                // answered at all). See RigLinkPort.kt's own class doc comment for the reasoning.
                verified = state.linkState is RigLinkState.Verified || state.linkState is RigLinkState.VerifyTimedOut,
                onContinue = onContinue,
                onContinueWithoutConnecting = onContinueWithoutConnecting,
                onUseUsbInstead = onUseUsbInstead,
            )
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
            RigBluetoothLinkSection(linkState = linkState, onRequestBluetoothPermission = onRequestBluetoothPermission)
        }

        Text(
            text = "If the link drops during a session the app keeps capturing, reads the last " +
                "frequency as stale and reconnects on its own. It never pairs or unpairs anything.",
            style = OrtType.cardBody,
            color = OrtColors.textDim,
        )
    }
}

/** [RigBluetoothScreen]'s own `bottomActions` — split out purely to keep that function's own
 * length under detekt's `LongMethod` threshold (R-1005c added the middle action), the same reason
 * every other small private composable in this file already is. */
@Composable
private fun RigBluetoothBottomActions(
    verified: Boolean,
    onContinue: () -> Unit,
    onContinueWithoutConnecting: () -> Unit,
    onUseUsbInstead: () -> Unit,
) {
    PrimaryButton(
        text = "Continue",
        onClick = onContinue,
        enabled = verified,
        modifier = Modifier.fillMaxWidth().testTag("setup-rig-bt-continue"),
    )
    ContinueWithoutConnectingAction(
        onClick = onContinueWithoutConnecting,
        modifier = Modifier.testTag("setup-rig-bt-continue-without-connecting"),
    )
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextAction(
            text = "Use USB instead",
            onClick = onUseUsbInstead,
            modifier = Modifier.testTag("setup-rig-bt-use-usb"),
        )
    }
}

/** [RigBluetoothScreen]'s own banner + checklist, once a device has been picked — split out purely
 * to keep that function's own length under detekt's `LongMethod` threshold, the same reason
 * [RigBluetoothBottomActions] already is. */
@Composable
private fun RigBluetoothLinkSection(linkState: RigLinkState, onRequestBluetoothPermission: () -> Unit) {
    bannerFor(linkState)?.let { (title, body) ->
        // R-1005b: the banner used to say "Grant it from Settings" with nothing that did so —
        // every other Lost/Failed banner has no action of its own (the checklist retries on its
        // own re-selection), so this is NoPermission-only.
        val isNoPermission = linkState == RigLinkState.NoPermission
        org.ort.app.ui.components.Banner(
            title = title,
            body = body,
            tone = org.ort.app.ui.components.BannerTone.DEGRADED,
            modifier = Modifier.fillMaxWidth().testTag("setup-rig-bt-lost-banner"),
            primaryActionLabel = if (isNoPermission) "Grant permission" else null,
            onPrimaryAction = if (isNoPermission) onRequestBluetoothPermission else null,
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
            // R-1014: the one place this checklist names a partial result rather than collapsing to
            // done/in-progress/pending — see verifyPartialDetail's own doc comment.
            detail = (linkState as? RigLinkState.VerifyTimedOut)?.let(::verifyPartialDetail),
        )
    }
}

/**
 * R-1014: the third checklist row's own detail line for [RigLinkState.VerifyTimedOut] — names how
 * many of how many declared capabilities were actually seen and which ones were not, so the
 * operator can tell from the screen alone what proceeding costs them (constitution I). Never a bare
 * "partially verified" with no specifics — [state.missingCapabilities] already carries exactly the
 * labels [RigPickerCatalogue.capabilityLabel] would draw for a full [RigLinkState.Verified], so this
 * is the same vocabulary, not a separate one invented for the partial case.
 */
internal fun verifyPartialDetail(state: RigLinkState.VerifyTimedOut): String {
    val seen = state.seenCapabilities.size
    val total = seen + state.missingCapabilities.size
    return "$seen of $total seen — missing " + state.missingCapabilities.joinToString(", ")
}

/** R-1013: the identify-timeout banner's own bound clause — the real, applied [timeoutMillis], never
 * a hardcoded number, formatted with [Locale.ROOT] so a comma-decimal locale never renders a
 * malformed sentence (constitution II's own history: two `:data` tests once passed on Windows and
 * failed on Linux for exactly this reason). */
internal fun formatTimeoutSeconds(timeoutMillis: Long): String =
    "%.1f".format(Locale.ROOT, timeoutMillis / 1_000.0) + "s"

/**
 * R-1005c (device field report, `Setup-Rig-Bluetooth.dc.html` redrawn 2026-09-12) — the escape
 * hatch for a Bluetooth-only rig that never links: `Continue` is gated on
 * [RigLinkState.Verified] alone, `Use USB instead` is no escape at all for a rig with no USB
 * transport, and the board's own back chevron ([RigBluetoothScreen]'s own [onBack]) was
 * suppressed. This lands on FR-RIG-2's existing null-module/manual-frequency path — see
 * `SetupActivity.onContinueWithoutRigLink`'s own doc comment for exactly what state setup ends in.
 *
 * **Accessibility (the artboard's own instruction — the caption sits directly beneath the action,
 * "so a screen reader reads them together")**: the established pattern in this codebase for
 * exactly this shape is `Modifier.clearAndSetSemantics` on the *outer* clickable node
 * (`ReadyScreen.kt`'s own `ReadySetupRow`, R-342/R-361) — a lone `contentDescription` on a plain
 * *sibling* `Text` does **not** fold into an ancestor's merged node on a real device, confirmed
 * there by an on-device dump. [TextAction] cannot be reused unmodified here for the same reason:
 * its own `clearAndSetSemantics` scopes the announced text to its own label alone (`Controls.kt`'s
 * own R-380 doc comment), which would leave the caption a second, separately-focusable stop (or,
 * worse per R-361, an empty one) rather than read together with the action as the board asks.
 * `clickable`'s own semantics contribution is itself replaced by `clearAndSetSemantics`, so the
 * click action is redeclared explicitly inside it — the same belt-and-suspenders shape
 * [TextAction]'s own `onClick(label = null) { ... }` already establishes, for the identical reason.
 */
@Composable
private fun ContinueWithoutConnectingAction(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .requiredHeightIn(min = 44.dp)
            .padding(top = 2.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = "Continue without connecting. " +
                    "The frequency is logged by hand until you link the radio."
                role = Role.Button
                onClick(label = null) {
                    onClick()
                    true
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Continue without connecting",
            style = OrtType.textAction.copy(fontWeight = FontWeight.Medium),
            color = OrtColors.accentGreen,
        )
        Text(
            text = "the frequency is logged by hand until you link the radio",
            style = OrtType.subLine,
            color = OrtColors.textDim,
            textAlign = TextAlign.Center,
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

/** [PARTIAL] is R-1014's own third state, distinct from both [DONE] and [PENDING] — never rendered
 * by colour alone (constitution VII): [ChecklistRow]'s own icon draws it as an amber ring around a
 * filled dot, a shape none of the other three share. */
private enum class ChecklistRowState { DONE, IN_PROGRESS, PARTIAL, PENDING }

/** Which stage (1 = link open, 2 = identify, 3 = verify) [linkState] has reached — see this file's
 * own class doc comment for why identify/verify collapse to consecutive states in this seam.
 * [RigLinkState.VerifyTimedOut] is handled as its own case, not folded into `reached`: it is
 * neither a plain "further along" state (stage 3 is not `DONE`, nothing further can complete this
 * attempt) nor a total failure (stages 1 and 2 genuinely happened, unlike [RigLinkState.Lost]/
 * [RigLinkState.Failed]/[RigLinkState.NoPermission]/[RigLinkState.IdentifyTimedOut], which all
 * collapse the whole checklist — the banner alone carries their detail, per this file's own
 * established pattern). */
private fun checklistStateFor(linkState: RigLinkState, stage: Int): ChecklistRowState {
    if (linkState is RigLinkState.VerifyTimedOut) {
        return if (stage <= 2) ChecklistRowState.DONE else ChecklistRowState.PARTIAL
    }
    val reached = when (linkState) {
        RigLinkState.Opening -> 0
        RigLinkState.Open -> 1
        is RigLinkState.Identified -> 2
        is RigLinkState.Verified -> 3
        is RigLinkState.Lost, is RigLinkState.Failed, RigLinkState.NoPermission,
        is RigLinkState.IdentifyTimedOut,
        // Unreachable: the guard above already returns for VerifyTimedOut. Named here only so this
        // `when` stays exhaustive against a future RigLinkState case the same way it always has.
        is RigLinkState.VerifyTimedOut,
        -> -1
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
    // R-1005b: this used to say "Grant it from Settings" with no button that did so — the banner
    // now carries a real "Grant permission" action ([RigBluetoothScreen]'s own doc comment).
    RigLinkState.NoPermission ->
        "Nearby devices permission is needed" to "Grant it below, or use USB instead."
    // R-1013: distinct in kind from Lost/Failed — the link is still open and nothing failed to
    // open, it is simply silent. States what was tried (opened, waited) and for how long, never a
    // generic error and never a hardcoded number (constitution I) — see RigLinkState.IdentifyTimedOut's
    // own doc comment for why Continue stays disabled here while "Continue without connecting" is
    // the honest way forward.
    is RigLinkState.IdentifyTimedOut ->
        "The radio never answered" to
            "The link opened, but nothing came back within ${formatTimeoutSeconds(linkState.timeoutMillis)}. " +
            "Check the radio is powered on and its data/CAT mode is on, then pick it again — " +
            "or continue without connecting."
    // R-1014: partial success, not a failure banner in the Lost/Failed sense — the checklist's own
    // third row (RigBluetoothLinkSection's own detail=) already names the specifics; this banner
    // only frames it as a choice being made, not a fault, since Continue is enabled for this state.
    is RigLinkState.VerifyTimedOut ->
        "Identified, but not every capability answered" to
            "Continuing will log this session with the command set only partially confirmed — see " +
            "the checklist below for exactly what was not seen."
    else -> null
}

@Composable
private fun ChecklistRow(
    label: String,
    state: ChecklistRowState,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
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
            // R-1014: a ring around a filled dot — a shape distinct from DONE's solid disc and
            // IN_PROGRESS's plain ring, not merely a different colour (constitution VII).
            ChecklistRowState.PARTIAL -> Canvas(modifier = Modifier.size(18.dp)) {
                drawCircle(color = OrtColors.accentAmber, style = Stroke(2.dp.toPx()))
                drawCircle(color = OrtColors.accentAmber, radius = size.minDimension / 2 - 5.dp.toPx())
            }
            ChecklistRowState.PENDING -> Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(androidx.compose.ui.graphics.Color.Transparent, CircleShape),
            )
        }
        Column {
            Text(
                text = label,
                style = OrtType.control,
                color = if (state == ChecklistRowState.PENDING) OrtColors.textLow else OrtColors.textBody,
            )
            if (detail != null) {
                Text(text = detail, style = OrtType.subLine, color = OrtColors.accentAmberText)
            }
        }
    }
}
