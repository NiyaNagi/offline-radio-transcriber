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
 * **Mode — step 1, and the surface that asks for the microphone (P39, D58, AC-204).**
 *
 * Three rows, one plain sub-line each, and **no `Continue`**: a tap is the answer, and D58's own
 * instruction was to *"drop the user into the capture mode dialog"* rather than make them confirm a
 * choice they have already made. [onChoose] drives every downstream write — the preset application,
 * the system microphone dialog, and the advance.
 *
 * **The microphone rationale rides here, and there is no explainer screen in front of the system
 * dialog** (AC-204). The published evidence is that a rationale *at the point of asking* outperforms
 * both no rationale and a dedicated screen, and that a dedicated screen is request fatigue; the flow
 * used to spend a whole numbered stage on one. [MICROPHONE_RATIONALE] is in the shape that evidence
 * supports — *this app needs X so you can Y* — and names what happens next, so the dialog is not a
 * surprise.
 *
 * **Copy (D58).** The operator's complaint was aimed squarely at this screen among others. The old
 * sub-lines were written for someone who already owns the reference setup (*"CAT over Bluetooth ·
 * audio by cable or Bluetooth, your choice"*), and the second audience for this product is a scanner
 * listener with no licence. Each row now says, in plain words, where the sound comes from — which is
 * the only thing this question decides for them. The two paragraphs of preset theory that used to sit
 * underneath are gone: nothing on this screen is reversible-in-a-way-worth-explaining, and the one
 * fact worth keeping (it can be changed later) is one short line.
 */
@Composable
public fun ModeScreen(
    onChoose: (CaptureMode) -> Unit,
    totalSteps: Int = SETUP_STEPS_WITHOUT_DOWNLOAD,
    onExitToApp: (() -> Unit)? = null,
) {
    SetupScaffold(
        step = SetupStep.MODE,
        totalSteps = totalSteps,
        title = "How is the radio connected?",
        subtitle = "This sets where the audio comes from.",
        onBack = null,
        onExitToApp = onExitToApp,
        bottomActions = {},
    ) {
        Column {
            NavigationRow(
                title = "Local microphone",
                subtitle = "the phone listens to a handheld or speaker nearby",
                onClick = { onChoose(CaptureMode.LOCAL_MICROPHONE) },
                modifier = Modifier.testTag("setup-mode-local-mic"),
                icon = OrtIcons.builtInMic,
            )
            NavigationRow(
                title = "USB-connected radio",
                subtitle = "an audio cable from the radio into the phone",
                onClick = { onChoose(CaptureMode.USB_RADIO) },
                modifier = Modifier.testTag("setup-mode-usb"),
                icon = OrtIcons.usbAudio,
            )
            NavigationRow(
                title = "Bluetooth-connected radio",
                subtitle = "the radio's audio arrives over Bluetooth",
                onClick = { onChoose(CaptureMode.BLUETOOTH_RADIO) },
                modifier = Modifier.testTag("setup-mode-bluetooth"),
                icon = OrtIcons.bluetooth,
            )
        }
        Text(
            text = MICROPHONE_RATIONALE,
            style = OrtType.cardBody,
            color = OrtColors.textDim,
            modifier = Modifier.testTag("setup-mode-microphone-rationale"),
        )
        Text(
            text = "You can change this later in Settings.",
            style = OrtType.signal,
            color = OrtColors.textFaint,
            modifier = Modifier.testTag("setup-mode-changeable-later"),
        )
    }
}

/**
 * AC-204's rationale, on the surface that fires the request. *Needs X so you can Y*, then what
 * happens next — Android's own dialog follows the tap immediately, and an operator who has not been
 * told that reads it as the app grabbing at something.
 *
 * It says *the microphone* and not *record audio* deliberately: every capture route on this screen,
 * including a USB adapter, is the `RECORD_AUDIO` permission as far as Android is concerned, and the
 * operator's own mental model of "the microphone permission" is the one the system dialog will use.
 */
internal const val MICROPHONE_RATIONALE: String =
    "The app needs the microphone so it can record what the radio hears — Android asks next."
