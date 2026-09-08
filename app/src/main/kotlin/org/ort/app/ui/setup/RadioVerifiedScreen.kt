package org.ort.app.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType
import org.ort.pipeline.capture.RigStatus

/**
 * S11 (`Setup-Rig-Verified.dc.html`, R-084) — renders a real [RigStatus.State.Connected] (or the
 * `lastKnown` half of a [RigStatus.State.Stale]). Since `:rig`/`:rig-usb` are unbuilt, the only
 * producer of either today is the debug scenario simulator's `rig-lost` scenario poking
 * [RigStatus] directly (WP0) — see [RadioUsbScreen]'s doc comment for the full accounting of what
 * is and is not reachable with real hardware right now (nothing).
 */
@Composable
public fun RadioVerifiedScreen(
    connected: RigStatus.State.Connected,
    onContinue: () -> Unit,
    onChangeRadio: () -> Unit,
) {
    SetupScaffold(
        step = SetupStep.RADIO_VERIFIED,
        title = "${connected.descriptor} connected",
        subtitle = "Identified and verified",
        onBack = null,
        bottomActions = {
            PrimaryButton(
                text = "Continue",
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().testTag("setup-radio-verified-continue"),
            )
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextAction(
                    text = "Change radio",
                    onClick = onChangeRadio,
                    modifier = Modifier.testTag("setup-radio-verified-change"),
                )
            }
        },
    ) {
        RigVerifiedContent(connected)
    }
}

/** The reading grid + verified command list — shared with [RadioUsbScreen]'s defensive
 * `Connected`/`Stale` fallback rendering so the two screens never drift on what "verified" shows. */
@Composable
internal fun RigVerifiedContent(connected: RigStatus.State.Connected) {
    Column {
        Text(text = "Reading now".uppercase(), style = OrtType.sectionLabel, color = OrtColors.textFaint)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            connected.bands.forEach { band ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(OrtColors.bgCard, RoundedCornerShape(10.dp))
                        .padding(13.dp)
                        .testTag("setup-radio-verified-band-${band.band}"),
                ) {
                    Text(
                        text = "Band ${band.band}".uppercase(),
                        style = OrtType.columnHeader,
                        color = OrtColors.textLow,
                    )
                    Text(
                        text = band.frequencyHz?.let { "%.3f".format(it / 1_000_000.0) } ?: "—",
                        style = OrtType.figure,
                        color = OrtColors.textHigh,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    if (band.squelchOpen) OrtColors.accentGreen else OrtColors.bgScreen,
                                    CircleShape,
                                ),
                        )
                        Text(
                            text = " ${band.mode ?: "—"} · ${if (band.squelchOpen) "squelch open" else "closed"}",
                            style = OrtType.subLine,
                            color = OrtColors.textMuted,
                        )
                    }
                }
            }
        }
    }
    Column {
        Text(text = "Verified command set".uppercase(), style = OrtType.sectionLabel, color = OrtColors.textFaint)
        listOf(
            "FQ" to "Frequency, per band",
            "BY" to "Squelch state — attributes each over to a band",
            "FO" to "Mode",
            "AI" to "Auto-information — changes arrive unpolled",
            "BL" to "Radio battery, for the status surface",
        ).forEach { (code, label) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = code,
                    style = OrtType.callsignCard,
                    color = OrtColors.textHigh,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Text(
                    text = label,
                    style = OrtType.transcript,
                    color = OrtColors.textBody,
                    modifier = Modifier.weight(1f),
                )
                Box(modifier = Modifier.size(9.dp).background(OrtColors.accentGreen, CircleShape))
            }
        }
    }
}
