package org.ort.app.ui.failures

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.Banner
import org.ort.app.ui.components.BannerTone
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.RadioRow
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType

/**
 * F21 — `Fail-Asset-Swap.dc.html`. WP11b: a standalone screen (a new lexicon/model is staged but
 * a session is live). **No runtime signal today** — the asset lifecycle (install/verify/
 * activate/roll back/remove, guide §"Assets") is unbuilt; see [DebugFailureOverride]'s kdoc.
 */
@Composable
public fun FailAssetSwapScreen(
    state: AssetSwapViewState,
    onSelectOption: (Int) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OrtColors.bgScreen)
            .testTag("failure-asset-swap-screen"),
    ) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                text = "Installed, not yet active",
                style = OrtType.screenTitle,
                color = OrtColors.textHigh,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
            Banner(
                title = "A session is running — the swap waits",
                body = "Replacing the lexicon under a live capture would make tonight's log half one version " +
                    "and half another, with no record of where the line is. So it does not happen.",
                tone = BannerTone.DEGRADED,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            SectionLabel("Lexicon", modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            Text(
                text = state.activeLabel,
                style = OrtType.control,
                color = OrtColors.textHigh,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            Text(
                text = state.stagedLabel,
                style = OrtType.control,
                color = OrtColors.textBody,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            SectionLabel("Options", modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            state.options.forEachIndexed { index, option ->
                RadioRow(
                    label = option,
                    selected = index == state.selectedOption,
                    onClick = { onSelectOption(index) },
                    modifier = Modifier.padding(horizontal = 20.dp).testTag("failure-asset-swap-option-$index"),
                )
            }
            Text(
                text = "Every over records which lexicon and model resolved it. When an asset changes, the " +
                    "overs from before are the ones Improve records lists — the line is always visible.",
                style = OrtType.cardBody,
                color = OrtColors.textDim,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
            PrimaryButton(
                text = "Done",
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().testTag("failure-asset-swap-done"),
            )
        }
    }
}
