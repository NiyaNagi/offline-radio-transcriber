package org.ort.app.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType
import org.ort.capture.android.AudioDeviceDescriptor

/** S06 (`Setup-Route-Mismatch.dc.html`, R-081) — the one halt in the whole sequence
 * (`Flow-Setup.dc.html`: "no continue"). No `Continue` exists on this screen at all; the only way
 * forward is [onChooseAnotherInput] (back to S04) or [onTryAgain] (re-run the check on the same
 * selection — the mismatch may have been transient, e.g. USB permission not yet granted).
 */
@Composable
public fun RouteMismatchScreen(
    mismatch: RouteCheckState.Mismatch,
    onChooseAnotherInput: () -> Unit,
    onTryAgain: () -> Unit,
) {
    SetupScaffold(
        step = SetupStep.ROUTE_MISMATCH,
        title = "That is not the radio",
        subtitle = "Capture will not start on this route",
        onBack = null,
        bottomActions = {
            PrimaryButton(
                text = "Choose another input",
                onClick = onChooseAnotherInput,
                modifier = Modifier.fillMaxWidth().testTag("setup-route-mismatch-choose-another"),
            )
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextAction(
                    text = "Try this one again",
                    onClick = onTryAgain,
                    modifier = Modifier.testTag("setup-route-mismatch-try-again"),
                )
            }
        },
    ) {
        SetupHaltBanner(
            title = "Audio is coming from the built-in microphone",
            body = "You chose ${mismatch.selected.label}, but Android routed the recording to the " +
                "phone's own mic. Nothing has been recorded.",
        )
        MismatchChecks(mismatch)
        Column {
            Text(text = "Usually one of".uppercase(), style = OrtType.sectionLabel, color = OrtColors.textFaint)
            listOf(
                "The adapter is plugged in but the phone has not granted it USB permission yet.",
                "Another app holds the USB device.",
                "The adapter is input-only and Android fell back to the phone mic silently.",
            ).forEach { reason ->
                Text(
                    text = reason,
                    style = OrtType.subtitle,
                    color = OrtColors.textBody,
                    modifier = Modifier.padding(top = 9.dp),
                )
            }
        }
    }
}

@Composable
private fun MismatchChecks(mismatch: RouteCheckState.Mismatch) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = OrtIcons.check,
            contentDescription = "done",
            tint = OrtColors.accentOnGreen,
            modifier = Modifier.size(20.dp).background(OrtColors.accentGreen, CircleShape).padding(4.dp),
        )
        Text(
            text = "Opened at native rate",
            style = OrtType.control,
            color = OrtColors.textHigh,
            modifier = Modifier.padding(start = 13.dp),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = OrtIcons.dismiss,
            contentDescription = "does not match",
            tint = OrtColors.haltOnFill,
            modifier = Modifier.size(20.dp).background(OrtColors.haltFill, CircleShape).padding(4.dp),
        )
        Column(modifier = Modifier.padding(start = 13.dp)) {
            Text(text = "Routed device does not match", style = OrtType.control, color = OrtColors.textHigh)
            Text(
                text = "chosen ${describeDevice(mismatch.selected)} · " +
                    "routed ${mismatch.routed?.let(::describeDevice) ?: "nothing"}",
                style = OrtType.timeFreq,
                color = OrtColors.textDim,
            )
        }
    }
}

/**
 * R-122 (validator finding, register R-120..R-125): on a device where every route's raw label is
 * identical (the validator's own emulator — every entry reads `sdk_gphone64_x86_64`), "chosen X ·
 * routed X" gave the operator no way to tell the two apart. [DeviceTypeNaming.forKind] is the same
 * real-type resolution S04 uses (a device the operator picked always has a real
 * [org.ort.capture.android.AudioDeviceKind] from `:capture-android`'s own enumeration, so this
 * never needs the `AudioManager`-backed lookup S04 falls back to for finer-grained types).
 */
private fun describeDevice(descriptor: AudioDeviceDescriptor): String =
    "${descriptor.label} (${DeviceTypeNaming.forKind(descriptor.kind).label})"
