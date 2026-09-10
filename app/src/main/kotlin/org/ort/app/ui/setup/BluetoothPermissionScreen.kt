package org.ort.app.ui.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.ort.app.ui.components.Banner
import org.ort.app.ui.components.BannerTone
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType

/**
 * S02c (`Setup-Bluetooth-Permission.dc.html`, D33, FR-CAP-8/FR-RIG-14) — the `BLUETOOTH_CONNECT`
 * rationale, walked only in Bluetooth capture mode ([SetupStateMachine]'s own gate), after
 * [SetupStep.MICROPHONE]. `Allow nearby devices` requests the OS permission ([onAllow]); `Not
 * now — use USB instead` ([onNotNow]) flips the mode to USB and proceeds — never a dead end.
 */
@Composable
public fun BluetoothPermissionScreen(onAllow: () -> Unit, onNotNow: () -> Unit) {
    SetupScaffold(
        step = SetupStep.BLUETOOTH_PERMISSION,
        title = "Nearby devices",
        subtitle = "Bluetooth mode needs one more permission",
        onBack = null,
        bottomActions = {
            PrimaryButton(
                text = "Allow nearby devices",
                onClick = onAllow,
                modifier = Modifier.fillMaxWidth().testTag("setup-bt-permission-allow"),
            )
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextAction(
                    text = "Not now — use USB instead",
                    onClick = onNotNow,
                    modifier = Modifier.testTag("setup-bt-permission-not-now"),
                )
            }
        },
    ) {
        Text(
            text = "Android calls it \"Nearby devices\". It lets the app see which radios are " +
                "paired and open a serial link to the one you choose. Nothing is scanned for, " +
                "nothing is advertised, and the app never pairs anything itself — pairing stays " +
                "in the system Bluetooth settings.",
            style = OrtType.bodyProse,
            color = OrtColors.textSecondary,
        )
        DotPointCard(
            points = listOf(
                "Read the paired-device list — so the rig can be picked by name",
                "Open a serial link to it — the same CAT commands the USB cable carries",
                "Use a Bluetooth headset-class device as the input — only if you choose it on the " +
                    "Input step",
            ),
        )
        Column {
            Banner(
                title = "Denying it keeps the USB path working",
                body = "Without this permission the Bluetooth rows on the next steps are shown " +
                    "but cannot be chosen, and the mode falls back to USB. Nothing else changes.",
                tone = BannerTone.DEGRADED,
                modifier = Modifier.fillMaxWidth().testTag("setup-bt-permission-decline-banner"),
            )
        }
    }
}
