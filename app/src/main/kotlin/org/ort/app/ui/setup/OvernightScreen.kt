package org.ort.app.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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

/**
 * S08 (`Setup-Battery.dc.html`, R-083) — fixes register R-083's finding: the battery-exemption
 * intent no longer fires automatically (`org.ort.app.MainActivity` did that unconditionally
 * before WP9; that call is removed as part of this package's `MainActivity.kt` rewrite). It now
 * fires only from [onOpenSetting] — the same `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent,
 * with the rationale and "the API lies" caveat shown first. `Skip for now` proceeds without ever
 * firing it; neither action gates capture (constitution IV: liveness is proven by heartbeat, never
 * by `isIgnoringBatteryOptimizations()`).
 */
@Composable
public fun OvernightScreen(onOpenSetting: () -> Unit, onSkip: () -> Unit) {
    SetupScaffold(
        step = SetupStep.OVERNIGHT,
        title = "Running overnight",
        subtitle = "Ask Android not to end capture to save power",
        onBack = null,
        bottomActions = {
            PrimaryButton(
                text = "Open the setting",
                onClick = onOpenSetting,
                modifier = Modifier.fillMaxWidth().testTag("setup-overnight-open-setting"),
            )
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextAction(
                    text = "Skip for now",
                    onClick = onSkip,
                    modifier = Modifier.testTag("setup-overnight-skip"),
                )
            }
        },
    ) {
        Text(
            text = "Some phones end background apps aggressively, and an overnight session is " +
                "exactly what they end. Exempting this app from battery optimisation makes that " +
                "less likely.",
            style = OrtType.bodyProse,
            color = OrtColors.textSecondary,
        )
        DotPointCard(
            points = listOf(
                "This is less likely, not guaranteed. On some devices the setting reports exempt " +
                    "and the OS ends the app anyway. The app never trusts that report — it proves " +
                    "it is alive by heartbeat, and shows you a gap if it was not.",
            ),
        )
        Column {
            NumberedStep(index = 1, text = "Open the system setting")
            NumberedStep(index = 2, text = "Choose Don't optimise or Unrestricted")
            NumberedStep(index = 3, text = "Come back here")
        }
        Row(
            modifier = Modifier.fillMaxWidth().testTag("setup-overnight-state-row"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(5.dp).background(OrtColors.markerUnknown, CircleShape))
            Text(
                text = "Not yet exempt — this never blocks capture",
                style = OrtType.subLine,
                color = OrtColors.textDim,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
