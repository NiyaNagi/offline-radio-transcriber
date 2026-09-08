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
 * S02 (`Setup-Mic.dc.html`, R-080) — re-homed from `MainActivity` (WP1 has merged; its interim
 * flow's three composables move here unchanged in substance, now built on [SetupScaffold] and
 * WP2's [PrimaryButton]/[DotPointCard] rather than the hand-rolled versions `MainActivity` used
 * before shared components existed). String resources are WP1's (`res/values/strings.xml`,
 * outside this package's ownership) — referenced, not duplicated.
 */
@Composable
public fun MicrophoneScreen(onAllow: () -> Unit, onBack: (() -> Unit)? = null) {
    SetupScaffold(
        step = SetupStep.MICROPHONE,
        title = stringResource(R.string.setup_mic_title),
        subtitle = stringResource(R.string.setup_mic_subtitle),
        onBack = onBack,
        bottomActions = {
            PrimaryButton(
                text = stringResource(R.string.setup_mic_allow),
                onClick = onAllow,
                modifier = Modifier.fillMaxWidth().testTag("setup-mic-allow"),
            )
        },
    ) {
        Text(text = stringResource(R.string.setup_mic_body), style = OrtType.bodyProse, color = OrtColors.textSecondary)
        DotPointCard(
            points = listOf(
                stringResource(R.string.setup_mic_point_1),
                stringResource(R.string.setup_mic_point_2),
                stringResource(R.string.setup_mic_point_3),
            ),
        )
    }
}

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
public fun MicrophoneDeniedScreen(onOpenSettings: () -> Unit, onCheckAgain: () -> Unit, onBack: (() -> Unit)? = null) {
    SetupScaffold(
        step = SetupStep.MICROPHONE_DENIED,
        title = stringResource(R.string.setup_mic_denied_title),
        subtitle = stringResource(R.string.setup_mic_denied_subtitle),
        onBack = onBack,
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
