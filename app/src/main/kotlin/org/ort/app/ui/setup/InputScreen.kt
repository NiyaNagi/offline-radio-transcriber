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
 * has both now). `Refresh` sits beside the title (guide's title-trailing action), `Verify this
 * input` is disabled until a route is chosen.
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
    SetupScaffold(
        step = SetupStep.INPUT,
        title = "Input",
        subtitle = "Which of these is the radio?",
        onBack = onBack,
        titleTrailing = {
            TextAction(text = "Refresh", onClick = onRefresh, modifier = Modifier.testTag("setup-input-refresh"))
        },
        bottomActions = {
            PrimaryButton(
                text = "Verify this input",
                onClick = onVerify,
                enabled = state.selectedId != null,
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

private fun audioRouteKindLabel(kind: AudioRouteKind): String = when (kind) {
    AudioRouteKind.BUILT_IN_MIC -> "Built-in microphone"
    AudioRouteKind.USB -> "USB audio"
    AudioRouteKind.WIRED_HEADSET -> "Wired headset"
    AudioRouteKind.BLUETOOTH_SCO -> "Bluetooth"
    AudioRouteKind.UNKNOWN -> "input"
}
