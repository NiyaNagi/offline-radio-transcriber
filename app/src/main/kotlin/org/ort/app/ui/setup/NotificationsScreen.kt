package org.ort.app.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.ort.app.R
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * S03 (`Setup-Notify.dc.html`, R-080) — re-homed from `MainActivity`. WP2 ships no dedicated
 * `Notification`-style card component (checked every file under `ui/components` — no such name;
 * report to the lead), so [NotificationPreviewCard] below builds the board's own
 * card markup directly from tokens, per the brief's fallback instruction. `Allow` / `Skip` — skip
 * never gates capture (constitution: notifications are diagnostic-only).
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
        )
        NotificationPreviewCard()
        Text(
            text = stringResource(R.string.setup_notify_footer),
            style = OrtType.cardBody,
            color = OrtColors.textDim,
        )
    }
}

/** `Setup-Notify.dc.html`'s illustrative preview — static, not the real notification
 * (`RealCaptureService`/`CaptureNotificationBuilder` own that, register R-102). */
@Composable
private fun NotificationPreviewCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OrtColors.bgPage, RoundedCornerShape(12.dp))
            .border(1.dp, OrtColors.lineSection, RoundedCornerShape(12.dp))
            .padding(OrtSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = OrtIcons.frequencies,
                contentDescription = null,
                tint = OrtColors.accentGreen,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = stringResource(R.string.setup_notify_preview_app),
                style = OrtType.subLine,
                color = OrtColors.textDim,
                modifier = Modifier.padding(start = 9.dp).weight(1f),
            )
            Text(
                text = stringResource(R.string.setup_notify_preview_now),
                style = OrtType.signal,
                color = OrtColors.textLow,
            )
        }
        Text(
            text = stringResource(R.string.setup_notify_preview_title),
            style = OrtType.subtitle.copy(fontWeight = FontWeight.Medium),
            color = OrtColors.textHigh,
            modifier = Modifier.padding(top = 7.dp),
        )
        Text(
            text = stringResource(R.string.setup_notify_preview_body),
            style = OrtType.cardBody,
            color = OrtColors.textMuted,
        )
        Row(modifier = Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(
                text = stringResource(R.string.setup_notify_preview_open),
                style = OrtType.subtitle.copy(fontWeight = FontWeight.Medium),
                color = OrtColors.accentGreen,
            )
            Text(
                text = stringResource(R.string.setup_notify_preview_stop),
                style = OrtType.subtitle.copy(fontWeight = FontWeight.Medium),
                color = OrtColors.accentGreen,
            )
        }
    }
}
