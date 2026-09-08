package org.ort.app.ui.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType

/**
 * S09 (`Setup-Rig.dc.html`, R-084) — the three [RadioChoice]s as [NavigationRow]s (guide §6.11's
 * "closed set is a visible list", each navigating on tap rather than a separate `Continue`; the
 * board itself has no filled button, only `Not now`). Selecting TH-D75A or another CAT rig moves
 * to [RadioUsbScreen]/[RadioVerifiedScreen] depending on [org.ort.pipeline.capture.RigStatus] —
 * see [SetupActivity] for that dispatch, which this screen does not know about.
 */
@Composable
public fun RadioScreen(onChoose: (RadioChoice) -> Unit, onNotNow: () -> Unit) {
    SetupScaffold(
        step = SetupStep.RADIO,
        title = "Radio",
        subtitle = "Read the frequency and squelch from the rig itself",
        onBack = null,
        bottomActions = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextAction(
                    text = "Not now — you can connect one later from Settings",
                    onClick = onNotNow,
                    modifier = Modifier.testTag("setup-radio-not-now"),
                )
            }
        },
    ) {
        Text(
            text = "Without a radio connection every over is logged against the frequency you " +
                "type in. With one, the app reads the frequency, mode and squelch per band as " +
                "they change — and on a dual-band rig, attributes each over to the band whose " +
                "squelch opened.",
            style = OrtType.bodyProse,
            color = OrtColors.textSecondary,
        )
        Column {
            NavigationRow(
                title = "Kenwood TH-D75A",
                subtitle = "USB serial · verified command set · both bands",
                onClick = { onChoose(RadioChoice.TH_D75A) },
                modifier = Modifier.testTag("setup-radio-th-d75a"),
                icon = OrtIcons.rig,
            )
            NavigationRow(
                title = "Another rig with a serial CAT interface",
                subtitle = "Needs a rig module · frequency only unless the module says more",
                onClick = { onChoose(RadioChoice.OTHER_CAT_RIG) },
                modifier = Modifier.testTag("setup-radio-other-rig"),
                icon = OrtIcons.rig,
            )
            NavigationRow(
                title = "No radio — I will enter the frequency",
                subtitle = "Scanner, or a rig with no CAT port",
                onClick = { onChoose(RadioChoice.NONE) },
                modifier = Modifier.testTag("setup-radio-none"),
                icon = OrtIcons.frequencies,
            )
        }
    }
}
