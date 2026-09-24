package org.ort.app.ui.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.RadioRow
import org.ort.app.ui.components.RowTone
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType
import org.ort.core.capture.AudioRouteKind
import org.ort.core.capture.CaptureMode
import org.ort.core.capture.CaptureModePresets

/**
 * The route-list section of [ListenScreen] (`Setup-Listen.dc.html`) — every real route
 * [InputRouteEnumerator] found, each as a WP2 [RadioRow] using its `subtitle` (real device type ·
 * native rate, plus the route's advisory where it has one) and `tone = RowTone.Warning` only where
 * [dimsTheRow] says the app genuinely cannot recommend the route.
 *
 * **P39 (D58): this was `InputScreen`, a step of its own.** It is now the first of three sections on
 * one screen, because picking a route, proving the route and setting its level are one task with one
 * failure mode — a silent one (constitution IV) — and splitting it across three wizard pages made an
 * operator answer three times for one decision. The scaffold, the title and the primary action moved
 * to [ListenScreen]; nothing about what this section draws changed.
 *
 * [InputRouteOption.icon] (register R-122): `RadioRow` itself has no icon slot, so a small leading
 * [Icon] is composed alongside it here rather than editing that shared file.
 */
@Composable
internal fun InputSection(
    routes: List<InputRouteOption>,
    selectedId: String?,
    presetLabel: String?,
    presetUnavailableText: String?,
    onSelect: (String) -> Unit,
) {
    if (presetLabel != null || presetUnavailableText != null) {
        PresetChip(
            modeLabel = presetLabel,
            unavailableText = presetUnavailableText,
            modifier = Modifier.testTag("setup-input-preset-chip"),
        )
    }
    if (routes.isEmpty()) {
        Text(
            text = "No inputs were found. Connect the radio's audio adapter and tap Refresh.",
            style = OrtType.bodyProse,
            color = OrtColors.textMuted,
        )
    }
    Column {
        routes.forEach { route ->
            // R-1187 (register; the R-1017/R-880 family): `Alignment.Top`, not `CenterVertically`.
            // Centring the leading icon against the *row* -- and, inside [RadioRow], the marker
            // against the whole label+subtitle column -- puts both controls beside the sub-line the
            // moment the subtitle wraps to a second line, leaving the label floating above them. The
            // row then reads as a heading followed by an unrelated control, which on this screen is
            // the shape that gets the wrong input picked. Every route wraps on the emulator (each is
            // named `sdk_gphone64_x86_64`) and any USB adapter with a long name will wrap on a real
            // device, so this is not a test-environment artefact. `RigBluetoothScreen`'s own
            // `PairedDeviceRow` already carries the identical fix for the identical shape; this
            // caller -- then one screen of twelve, now the screen no first run can skip -- was left
            // behind by it.
            Row(verticalAlignment = Alignment.Top) {
                route.icon?.let {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = if (route.advisory.dimsTheRow()) OrtColors.textIconDim else OrtColors.textIcon,
                        modifier = Modifier
                            .padding(end = 11.dp)
                            .size(18.dp)
                            .testTag("setup-input-route-icon-${route.id}"),
                    )
                }
                RadioRow(
                    label = route.label,
                    selected = route.id == selectedId,
                    onClick = { onSelect(route.id) },
                    subtitle = route.subtitle,
                    tone = if (route.advisory.dimsTheRow()) RowTone.Warning else RowTone.Neutral,
                    modifier = Modifier.weight(1f).testTag("setup-input-route-${route.id}"),
                    verticalAlignment = Alignment.Top,
                )
            }
        }
    }
}

/**
 * **R-1170 — what replaces a disabled primary button across the setup flow** (AC-201).
 *
 * The rule: *never disable a primary button for validation state; keep it lit, validate when it is
 * tapped, and say specifically what is missing.* Disabling stays defensible for one thing only — an
 * action genuinely **in flight**, to stop a double submission (that is unavailability, not
 * validation, and [ModelsSetupScreen] is the one screen in this package where it applies).
 *
 * This notice is the "say what is missing" half. It renders nothing at all until the operator has
 * actually asked for something, so it answers a tap rather than greeting them with a standing scold.
 * [LiveRegionMode.Assertive], not `Polite`: the text is the direct answer to a deliberate tap that
 * appeared to do nothing, so interrupting is the correct behaviour — a polite region can queue behind
 * whatever TalkBack is already saying and arrive long after the operator has given up on the button.
 * `accentAmberText` is the guide's own colour for a stated, recoverable problem (§3/§6.8).
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
 * R-1170: what the `Verify this input` tap says when it refuses, or `null` when there is nothing to
 * refuse. The two cases are genuinely different problems and must not collapse into one sentence
 * (constitution I): an empty enumeration is hardware that is not attached, and the answer is the
 * adapter and `Refresh`; a populated list with nothing chosen is a decision the operator has not made
 * yet.
 */
internal fun inputValidationMessage(routes: List<InputRouteOption>, selectedId: String?): String? = when {
    selectedId != null -> null
    routes.isEmpty() ->
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

/** The preset chip's two mutually-exclusive facts. */
public data class PresetChipState(val modeLabel: String?, val presetUnavailableText: String?)

/**
 * R-816 (reviewer finding, FR-CAP-9, halt): the chip used to claim "preset by <mode>" the instant
 * a [captureMode] was chosen, regardless of whether [CaptureModePresets.presetsFor]'s preferred
 * route was actually among [routes] — a device offering only the built-in mic under USB-radio mode
 * still showed "preset by USB-connected radio" with nothing preselected, a preset that named a
 * route the operator's own hardware does not have. Pure and Context-free so it is testable without
 * Robolectric's `InputRouteEnumerator` seam.
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
 * R-902 (reviewer A2, run 3, spec): the preset pre-select used `firstOrNull`, which always picks
 * *a* match, never asking whether it was the only one. Constitution I: with two-or-more routes of the
 * preset's own kind enumerated (two USB devices, say), there is no honest single "the" preset route
 * to guess at. [routes.singleOrNull] is the whole fix: exactly one match pre-selects it, zero or
 * several leave nothing selected — the operator's own tap remains how a genuine choice gets made.
 */
public fun presetInputRouteFor(captureMode: CaptureMode?, routes: List<InputRouteOption>): InputRouteOption? {
    val presetKind = captureMode?.let(CaptureModePresets::presetsFor)?.preferredRouteKind ?: return null
    return routes.singleOrNull { it.routeKind == presetKind }
}

/**
 * R-852 (validator V8, spec, FR-CAP-9): override-tracking used to fire only when [presetRouteId] was
 * non-null, so picking a route under the "none attached, choose a route" chip never recorded an
 * override at all, and that chip could never change afterward. An explicit pick is an override the
 * instant it is not literally the one honest preset match (constitution I: the operator's own choice,
 * once made, must never keep reading as "still on the preset"). `false` with no [captureMode] at
 * all — there is no preset to override yet.
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
