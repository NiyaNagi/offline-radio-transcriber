package org.ort.app.ui.failures

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType

/**
 * F16 — `Fail-Usb.dc.html`. WP11b's own instruction: the *only* other halt-toned full-screen
 * treatment besides F1 (guide: the halt token family is used only for F1 and F16 — capture
 * stopped), drawn as a dialog over a dimmed screen rather than F1's plain full page, exactly as
 * the board does. **No runtime signal exists for this today** — see [DebugFailureOverride]'s
 * kdoc for precisely what `:capture-android`/[org.ort.pipeline.capture.RigStatus] would need.
 */
@Composable
public fun FailUsbScreen(
    state: UsbViewState,
    onGrantPermission: () -> Unit,
    onContinueWithoutRadio: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .failureScreenInset()
            .testTag("failure-usb-screen"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .background(OrtColors.bgRaised, RoundedCornerShape(14.dp))
                .border(1.dp, OrtColors.haltBorder, RoundedCornerShape(14.dp))
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(imageVector = OrtIcons.halt, contentDescription = null, tint = OrtColors.haltText)
                Text(
                    text = "The rig needs USB permission again",
                    style = OrtType.cardTitle,
                    color = OrtColors.textHigh,
                )
            }
            Text(
                text = "The cable to the radio came out at ${state.detachedAtLabel} and went back in at " +
                    "${state.reattachedAtLabel}. Android forgets USB permission on every re-attach, so until " +
                    "you answer its prompt the radio cannot be read.",
                style = OrtType.control,
                color = OrtColors.textBody,
                modifier = Modifier.padding(top = 12.dp),
            )
            Column(modifier = Modifier.padding(top = 14.dp)) {
                UsbFactRow(
                    dotColor = OrtColors.accentGreen,
                    text = "Audio capture is fine — the audio adapter is a different device",
                )
                UsbFactRow(
                    dotColor = OrtColors.accentAmber,
                    text = "Frequency is stale since ${state.detachedAtLabel} · " +
                        "${state.staleOversCount} overs so far marked",
                )
                UsbFactRow(
                    dotColor = OrtColors.haltFill,
                    text = "This is a realistic way to lose an overnight run — which is why it is this loud",
                )
            }
            Text(
                text = "Android's own prompt is behind this dialog. Tick Always open if it offers; it reduces " +
                    "but does not remove the problem. The notification and the status surface carry this too, " +
                    "in case you are not looking here.",
                style = OrtType.cardBody,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = 14.dp),
            )
            Column(modifier = Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    text = "Grant USB permission",
                    onClick = onGrantPermission,
                    modifier = Modifier.fillMaxWidth().testTag("failure-usb-grant-permission"),
                )
                TextAction(
                    text = "Continue without the radio — set the frequency by hand",
                    onClick = onContinueWithoutRadio,
                    modifier = Modifier.fillMaxWidth().testTag("failure-usb-continue-without-radio"),
                )
            }
        }
    }
}

@Composable
private fun UsbFactRow(dotColor: Color, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(modifier = Modifier.size(9.dp).background(dotColor, CircleShape))
        Text(text = text, style = OrtType.control, color = OrtColors.textBody)
    }
}
