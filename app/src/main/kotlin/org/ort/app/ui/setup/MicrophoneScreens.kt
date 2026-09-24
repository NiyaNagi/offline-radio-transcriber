package org.ort.app.ui.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import org.ort.app.R
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType

/**
 * **The microphone *explainer* screen is deleted (P39, D58, AC-204).** `SetupStep.MICROPHONE` used to
 * render a whole numbered stage — a title, a body paragraph and three bullets — in front of Android's
 * own permission dialog. D58 removed it outright: a rationale at the point of asking measurably
 * outperforms both no rationale and a dedicated screen, and a dedicated screen in front of a system
 * dialog is request fatigue. The rationale now rides on [ModeScreen], the surface the request is fired
 * from ([ModeScreen.MICROPHONE_RATIONALE]), and the dialog follows the mode tap directly.
 *
 * The `setup_mic_*` string resources it used are in `res/values/strings.xml`, which this package does
 * not own; they are left in place rather than orphaned silently in someone else's file, and are named
 * in this change's CHANGELOG entry so they can be swept with the rest of that file.
 *
 * [MicrophoneDeniedScreen] below is untouched and stays exactly as it was: a permanently denied
 * microphone is a real halt, not an explainer.
 */
/**
 * S02b (`Setup-Mic-Denied.dc.html`, R-085) — the interim mic-denied screen, re-homed and kept
 * green (this row's register status is already `fixed`; WP9's job is folding it in, not changing
 * its behaviour). `MainActivity.onResume`-equivalent re-checking lives in [SetupActivity], unchanged.
 *
 * [onBack] (R-223, validator pass 2): the board draws the header chevron here like every other
 * step (confirmed by reading `Setup-Mic-Denied.dc.html` before writing this — it is not a board
 * that omits it for halt states), so it is rendered — wired to [SetupActivity]'s ordinary
 * back-stack `onBack`, same as everywhere else, not a special-cased action of its own. `null` only
 * so this screen's own tests can render it without wiring a real activity behind it.
 */
@Composable
public fun MicrophoneDeniedScreen(
    onOpenSettings: () -> Unit,
    onCheckAgain: () -> Unit,
    onBack: (() -> Unit)? = null,
    onExitToApp: (() -> Unit)? = null,
) {
    SetupScaffold(
        step = SetupStep.MICROPHONE_DENIED,
        title = stringResource(R.string.setup_mic_denied_title),
        subtitle = stringResource(R.string.setup_mic_denied_subtitle),
        onBack = onBack,
        onExitToApp = onExitToApp,
        bottomActions = {
            PrimaryButton(
                text = stringResource(R.string.setup_mic_denied_open_settings),
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth().testTag("setup-mic-denied-open-settings"),
            )
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextAction(
                    text = stringResource(R.string.setup_mic_denied_check_again),
                    onClick = onCheckAgain,
                    modifier = Modifier.testTag("setup-mic-denied-check-again"),
                )
            }
        },
    ) {
        SetupHaltBanner(
            title = stringResource(R.string.setup_mic_denied_banner_title),
            body = stringResource(R.string.setup_mic_denied_banner_body),
        )
        Column {
            Text(
                text = stringResource(R.string.setup_mic_denied_steps_label).uppercase(),
                style = OrtType.sectionLabel,
                color = OrtColors.textFaint,
            )
            listOf(
                stringResource(R.string.setup_mic_denied_step_1),
                stringResource(R.string.setup_mic_denied_step_2),
                stringResource(R.string.setup_mic_denied_step_3),
            ).forEachIndexed { index, step -> NumberedStep(index = index + 1, text = step) }
        }
        Text(
            text = stringResource(R.string.setup_mic_denied_footer),
            style = OrtType.cardBody,
            color = OrtColors.textDim,
        )
    }
}
