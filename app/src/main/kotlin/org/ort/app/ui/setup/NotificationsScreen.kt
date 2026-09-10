package org.ort.app.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import org.ort.app.R
import org.ort.app.ui.components.NotificationCard
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType

/**
 * S03 (`Setup-Notify.dc.html`, R-080) — re-homed from `MainActivity`. The preview card is WP2's
 * shared [NotificationCard] (`Capture-Notification.dc.html`'s own component, landed in the WP2
 * follow-up) — this screen's earlier hand-rolled markup, built from tokens directly because no
 * such component existed yet, is gone. `Allow` / `Skip` — skip never gates capture (constitution:
 * notifications are diagnostic-only).
 */
@Composable
public fun NotificationsScreen(onAllow: () -> Unit, onSkip: () -> Unit, onBack: (() -> Unit)? = null) {
    SetupScaffold(
        step = SetupStep.NOTIFICATIONS,
        title = stringResource(R.string.setup_notify_title),
        subtitle = stringResource(R.string.setup_notify_subtitle),
        onBack = onBack,
        bottomActions = {
            PrimaryButton(
                text = stringResource(R.string.setup_notify_allow),
                onClick = onAllow,
                modifier = Modifier.fillMaxWidth().testTag("setup-notify-allow"),
            )
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextAction(
                    text = stringResource(R.string.setup_notify_skip),
                    onClick = onSkip,
                    modifier = Modifier.testTag("setup-notify-skip"),
                )
            }
        },
    ) {
        Text(
            text = stringResource(R.string.setup_notify_body),
            style = OrtType.bodyProse,
            color = OrtColors.textSecondary,
            modifier = Modifier.background(androidx.compose.ui.graphics.Color.Magenta),
        )
        // Setup-Notify.dc.html's own illustrative reading -- the same figures the pre-WP2-follow-up
        // markup showed, now through the shared component `Capture-Notification.dc.html` names.
        NotificationCard(
            icon = OrtIcons.frequencies,
            title = "Capturing",
            elapsedLabel = "6:42",
            countLabel = "412 overs",
            secondLine = "145.230 and 146.960 · tier 3 · 38.2 GB used",
            primaryActionLabel = "Open",
            onPrimaryAction = {},
            secondaryActionLabel = "Stop",
            onSecondaryAction = {},
            modifier = Modifier.fillMaxWidth().testTag("setup-notify-preview"),
        )
        Text(
            text = stringResource(R.string.setup_notify_footer),
            style = OrtType.cardBody,
            color = OrtColors.textDim,
        )
    }
}
