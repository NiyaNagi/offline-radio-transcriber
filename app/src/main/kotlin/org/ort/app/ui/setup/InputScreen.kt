package org.ort.app.ui.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.RadioRow
import org.ort.app.ui.components.RowTone
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType
import org.ort.core.capture.AudioRouteKind
import org.ort.core.capture.CaptureMode
import org.ort.core.capture.CaptureModePresets

/**
 * S04 (`Setup-Input.dc.html`, R-081) — every real route [InputRouteEnumerator] found, each as a
 * WP2 [RadioRow] using its `subtitle` (real device type · native rate, plus the route's advisory
 * where it has one) and `tone = RowTone.Warning` only where [dimsTheRow] says the app genuinely
 * cannot recommend the route — never for the built-in mic or Bluetooth, both of which are
 * supported capture modes under D33/D34 (WP2's own follow-up closed the earlier
 * gap this screen used to work around — `RadioRow` had no subtitle slot or colour override; it
 * has both now). `Refresh` sits beside the title (guide's title-trailing action); `Verify this
 * input` only advances once a route is chosen (R-1170, below, for how that refusal is expressed).
 *
 * **R-1170 (register; the operator, on the device: *"keep the continue button lit up at all
 * times"*)**: `Verify this input` used to be `enabled = state.selectedId != null`. A disabled
 * button says nothing about *what* is wrong, is routinely below the contrast floor, and —
 * decisively — `disabled` removes the control from the focus order, so a screen-reader operator
 * cannot reach the very thing blocking them. It is now lit at all times; the tap validates and
 * [SetupValidationNotice] says what is missing. The gate itself is unchanged: [onVerify] is not
 * called until a route is chosen.
 *
 * [InputRouteOption.icon] (register R-122, validator finding): `RadioRow` itself has no icon slot —
 * confirmed by reading `ui/components/Controls.kt` before writing this, a real WP2 gap this
 * package's report names explicitly — so a small leading [Icon] is composed alongside it here,
 * outside `RadioRow`, rather than either editing that shared file (outside this row's owned files)
 * or hand-rolling a look-alike radio row from scratch.
 */
@Composable
public fun InputScreen(
    state: InputViewState,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onVerify: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    var showWhatIsMissing by remember { mutableStateOf(false) }
    val whatIsMissing = inputValidationMessage(state)
    SetupScaffold(
        step = SetupStep.INPUT,
        title = "Input",
        subtitle = "Which of these carries the radio's audio?",
        onBack = onBack,
        titleTrailing = {
            TextAction(text = "Refresh", onClick = onRefresh, modifier = Modifier.testTag("setup-input-refresh"))
        },
        bottomActions = {
            SetupValidationNotice(
                message = whatIsMissing.takeIf { showWhatIsMissing },
                modifier = Modifier.testTag("setup-input-validation"),
            )
            PrimaryButton(
                text = "Verify this input",
                onClick = { if (whatIsMissing == null) onVerify() else showWhatIsMissing = true },
                modifier = Modifier.fillMaxWidth().testTag("setup-input-verify"),
            )
        },
    ) {
        if (state.presetLabel != null || state.presetUnavailableText != null) {
            PresetChip(
                modeLabel = state.presetLabel,
                unavailableText = state.presetUnavailableText,
                modifier = Modifier.testTag("setup-input-preset-chip"),
            )
        }
        if (state.routes.isEmpty()) {
            Text(
                text = "No input devices were found. Connect the radio's audio adapter and tap Refresh.",
                style = OrtType.bodyProse,
                color = OrtColors.textMuted,
            )
        }
        Column {
            state.routes.forEach { route ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    route.icon?.let {
                        Icon(
                            imageVector = it,
                            contentDescription = null,
                            tint = if (route.advisory.dimsTheRow()) OrtColors.textIconDim else OrtColors.textIcon,
                            modifier = Modifier.padding(end = 11.dp).size(18.dp),
                        )
                    }
                    RadioRow(
                        label = route.label,
                        selected = route.id == state.selectedId,
                        onClick = { onSelect(route.id) },
                        subtitle = route.subtitle,
                        tone = if (route.advisory.dimsTheRow()) RowTone.Warning else RowTone.Neutral,
                        modifier = Modifier.weight(1f).testTag("setup-input-route-${route.id}"),
                    )
                }
            }
        }
        Text(
            text = "Most USB adapters do not offer 16 kHz. The app takes the device's native rate " +
                "and resamples deterministically, so the same audio always produces the same samples.",
            style = OrtType.cardBody,
            color = OrtColors.textDim,
        )
    }
}

/**
 * **R-1170 — what replaces a disabled primary button across the setup flow.**
 *
 * The rule the register row settles on: *never disable a primary button for validation state; keep
 * it lit, validate when it is tapped, and say specifically what is missing.* Disabling stays
 * defensible for one thing only — an action genuinely **in flight**, to stop a double submission
 * (that is unavailability, not validation, and [ModelsSetupScreen] is the one screen in this
 * package where it applies).
 *
 * This notice is the "say what is missing" half. It renders nothing at all until the operator has
 * actually asked for something, so it answers a tap rather than greeting them with a standing
 * scold. [LiveRegionMode.Assertive], not `Polite`: the text is the direct answer to a deliberate
 * tap that appeared to do nothing, so interrupting is the correct behaviour — a polite region can
 * queue behind whatever TalkBack is already saying and arrive long after the operator has given
 * up on the button. `accentAmberText` is the guide's own colour for a stated, recoverable problem
 * (§3/§6.8) — a sentence of explanation, not a banner.
 *
 * Declared here rather than in a file of its own so this change stays inside the files R-1170's
 * own brief assigns to it; every setup screen in this package shares it.
 */
@Composable
internal fun SetupValidationNotice(message: String?, modifier: Modifier = Modifier) {
    if (message == null) return
    Text(
        text = message,
        style = OrtType.subLine,
        color = OrtColors.accentAmberText,
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive },
    )
}

/**
 * R-1170: what S04's tap says when it refuses, or `null` when there is nothing to refuse. The two
 * cases are genuinely different problems and must not collapse into one sentence (constitution I):
 * an empty enumeration is hardware that is not attached, and the answer is the adapter and
 * `Refresh`; a populated list with nothing chosen is a decision the operator has not made yet.
 */
internal fun inputValidationMessage(state: InputViewState): String? = when {
    state.selectedId != null -> null
    state.routes.isEmpty() ->
        "No inputs were found. Connect the radio's audio adapter, then tap Refresh."
    else ->
        "Choose which input carries the radio's audio — tap one of the rows above, then verify it."
}

/**
 * **FR-CAP-2b: which advisories are a caution and which are simply a fact.**
 *
 * [RouteAdvisory.ROOM_AUDIO] and [RouteAdvisory.BLUETOOTH_DEGRADED] are *supported modes* (D33,
 * D34) — the operator picking the built-in mic or a Bluetooth link is choosing a thing the product
 * does, not overriding a guard, so the row stays in [RowTone.Neutral] and the consequence is
 * carried by the subtitle. Amber here would read as "do not choose this", which is exactly the
 * signal that made the mic look blocked when FR-CAP-3a had permitted it all along.
 *
 * The two that do dim are the two the app genuinely cannot recommend: a device type it could not
 * identify, and a platform route that is not a capture source at all.
 */
private fun RouteAdvisory?.dimsTheRow(): Boolean = when (this) {
    RouteAdvisory.UNRECOGNISED_TYPE, RouteAdvisory.NOT_A_CAPTURE_SOURCE -> true
    RouteAdvisory.ROOM_AUDIO, RouteAdvisory.BLUETOOTH_DEGRADED, null -> false
}

/** S04's preset chip, as the two mutually-exclusive facts [InputViewState] carries. */
public data class PresetChipState(val modeLabel: String?, val presetUnavailableText: String?)

/**
 * R-816 (reviewer finding, FR-CAP-9, halt): the chip used to claim "preset by <mode>" the instant
 * a [captureMode] was chosen, regardless of whether [CaptureModePresets.presetsFor]'s preferred
 * route was actually among [routes] — a device offering only the built-in mic under USB-radio mode
 * still showed "preset by USB-connected radio" with nothing preselected, a preset that named a
 * route the operator's own hardware does not have. Pure and Context-free so it is testable without
 * Robolectric's `InputRouteEnumerator` seam: pass it real [InputRouteOption]s from a
 * [org.ort.capture.android.fake.FakeAudioIo]-backed enumerator instead.
 */
public fun presetChipStateFor(
    captureMode: CaptureMode?,
    modeOverridden: Boolean,
    routes: List<InputRouteOption>,
): PresetChipState {
    if (captureMode == null || modeOverridden) return PresetChipState(null, null)
    val presetKind = CaptureModePresets.presetsFor(captureMode).preferredRouteKind
    return if (routes.any { it.routeKind == presetKind }) {
        PresetChipState(modeLabel = captureMode.operatorLabel, presetUnavailableText = null)
    } else {
        PresetChipState(
            modeLabel = null,
            presetUnavailableText = "preset ${audioRouteKindLabel(presetKind)} — none attached, choose a route",
        )
    }
}

/**
 * R-902 (reviewer A2, run 3, spec): under `mode-local-mic` S04 named "Local microphone" as the
 * preset in [presetChipStateFor]'s own chip but pre-selected no row at all, leaving `Verify this
 * input` disabled — nothing the operator did wrong, since [SetupActivity]'s own pre-select logic
 * used `firstOrNull`, which always picks *a* match, never asking whether it was the only one.
 * Constitution I: with two-or-more routes of the preset's own kind enumerated (two USB devices,
 * say), there is no honest single "the" preset route to guess at — pre-selecting the first one
 * anyway would silently choose *for* the operator among options this mode's own preset does not
 * actually distinguish between. [routes.singleOrNull] is the whole fix: exactly one match
 * pre-selects it, zero or several leave [InputViewState.selectedId] `null`, same as an unresolved
 * preset today — the operator's own tap remains how a genuine choice gets made either way.
 */
public fun presetInputRouteFor(captureMode: CaptureMode?, routes: List<InputRouteOption>): InputRouteOption? {
    val presetKind = captureMode?.let(CaptureModePresets::presetsFor)?.preferredRouteKind ?: return null
    return routes.singleOrNull { it.routeKind == presetKind }
}

/**
 * R-852 (validator V8, spec, FR-CAP-9): [SetupActivity.RenderInput]'s own override-tracking used to
 * fire only when [presetRouteId] was non-null, so picking a route under the "none attached, choose
 * a route" chip ([presetChipStateFor]'s own [PresetChipState.presetUnavailableText] case —
 * [presetInputRouteFor] returning `null` because nothing matched, or several did) never recorded an
 * override at all, and that chip could never change afterward. An explicit pick is an override the
 * instant it is not literally the one honest preset match — including every pick when there was no
 * such match to begin with (constitution I: the operator's own choice, once made, must never keep
 * reading as "still on the preset"). `false` with no [captureMode] at all — there is no preset to
 * override yet (never reachable through [SetupActivity]'s own dispatch, since S04 is gated on a
 * capture mode already being chosen, but honest regardless).
 */
public fun isAudioRouteOverride(captureMode: CaptureMode?, presetRouteId: String?, selectedId: String): Boolean =
    captureMode != null && selectedId != presetRouteId

private fun audioRouteKindLabel(kind: AudioRouteKind): String = when (kind) {
    AudioRouteKind.BUILT_IN_MIC -> "Built-in microphone"
    AudioRouteKind.USB -> "USB audio"
    AudioRouteKind.WIRED_HEADSET -> "Wired headset"
    AudioRouteKind.BLUETOOTH_SCO -> "Bluetooth"
    AudioRouteKind.UNKNOWN -> "input"
}
