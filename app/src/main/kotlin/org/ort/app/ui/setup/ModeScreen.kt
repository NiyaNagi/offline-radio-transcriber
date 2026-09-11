package org.ort.app.ui.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType
import org.ort.core.capture.CaptureMode

/**
 * S00 (`Setup-Mode.dc.html`, D33, FR-CAP-8/FR-CAP-9) — the very first content step (stage 1 of 8):
 * three [NavigationRow]s, exact board copy, each stating what it presets and what it costs.
 * Tapping a mode is the only interaction — the board carries no filled `Continue` button, only the
 * footer's "changeable later" line. [onChoose] alone drives every downstream write (preset
 * application, advancing to the next step) — this screen stays a pure function of nothing but its
 * own static copy.
 */
@Composable
public fun ModeScreen(onChoose: (CaptureMode) -> Unit) {
    SetupScaffold(
        step = SetupStep.MODE,
        title = "How is the radio connected?",
        subtitle = "Sets the audio route and the rig link. Both stay changeable.",
        onBack = null,
        bottomActions = {},
    ) {
        Column {
            NavigationRow(
                title = "Local microphone",
                subtitle = "a handheld near the phone · room audio · frequency by hand",
                onClick = { onChoose(CaptureMode.LOCAL_MICROPHONE) },
                modifier = Modifier.testTag("setup-mode-local-mic"),
                icon = OrtIcons.builtInMic,
            )
            NavigationRow(
                title = "USB-connected radio",
                subtitle = "audio adapter and CAT on the cable · the reference setup",
                onClick = { onChoose(CaptureMode.USB_RADIO) },
                modifier = Modifier.testTag("setup-mode-usb"),
                icon = OrtIcons.usbAudio,
            )
            NavigationRow(
                title = "Bluetooth-connected radio",
                subtitle = "CAT over Bluetooth · audio by cable or Bluetooth, your choice",
                onClick = { onChoose(CaptureMode.BLUETOOTH_RADIO) },
                modifier = Modifier.testTag("setup-mode-bluetooth"),
                icon = OrtIcons.bluetooth,
            )
        }
        Text(
            text = "A mode presets two independent things — where the audio comes from, and " +
                "where the frequency and squelch come from. Each step after this shows the " +
                "preset and lets you change it. Bluetooth audio is narrowband and costs " +
                "accuracy; the app marks every session recorded that way so it is never " +
                "mistaken for cabled audio.",
            style = OrtType.cardBody,
            color = OrtColors.textDim,
        )
        Text(
            text = "Change the mode any time from Settings › Input and level › Capture mode. " +
                "A change applies at the next session.",
            style = OrtType.signal,
            color = OrtColors.textFaint,
        )
    }
}
