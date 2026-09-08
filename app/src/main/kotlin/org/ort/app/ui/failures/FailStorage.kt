package org.ort.app.ui.failures

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.Banner
import org.ort.app.ui.components.BannerTone
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * F6 — `Fail-Storage.dc.html`, hard-floor stage. WP11b, register R-100: a full-screen takeover,
 * the second of the two "never continue silently" states this package builds against a real
 * signal — [org.ort.pipeline.capture.StorageForecast.State.AtFloor] with
 * [org.ort.pipeline.capture.CaptureState.State.Failed] (see `FailureMapper.kt`'s kdoc; both are
 * always true together — `RealCaptureService.stopForStorageExhaustion` sets them in the same
 * call). AC-78/constitution IV: "never stop capture silently" — reached here, not before.
 */
@Composable
public fun FailStorageHaltScreen(
    state: StorageHaltViewState,
    onFreeUpSpace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OrtColors.bgScreen)
            .failureScreenInset()
            .testTag("failure-storage-halt-screen"),
    ) {
        FailureActionBarScaffold(
            content = {
                Row9(state.freeLabel)
                Banner(
                    title = "Storage exhausted — ${state.freeLabel}",
                    body = "Free space fell below the ${state.floorLabel} floor. Audio, then transcripts, " +
                        "then capture itself — capture has now stopped, and it stopped loudly: this screen, " +
                        "the notification and the status surface all say so. Free up space and start a new " +
                        "session.",
                    tone = BannerTone.HALTING,
                    primaryActionLabel = "Free up space",
                    onPrimaryAction = onFreeUpSpace,
                    modifier = Modifier.padding(horizontal = OrtSpacing.lg).padding(top = 16.dp),
                )
            },
            actionBar = {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = OrtSpacing.lg, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PrimaryButton(
                        text = "Free up space",
                        onClick = onFreeUpSpace,
                        modifier = Modifier.fillMaxWidth().testTag("failure-storage-halt-free-space"),
                    )
                }
            },
        )
    }
}

@Composable
private fun Row9(freeLabel: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(start = OrtSpacing.lg, top = 10.dp, end = OrtSpacing.lg)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            HaltDot()
            Text(text = "Halted", style = OrtType.screenTitle, color = OrtColors.textHigh)
        }
        Text(
            text = "storage exhausted · $freeLabel",
            style = OrtType.subtitle,
            color = OrtColors.textDim,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
