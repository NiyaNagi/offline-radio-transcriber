package org.ort.app.ui.failures

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.Banner
import org.ort.app.ui.components.BannerTone
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType

/**
 * F21 — `Fail-Asset-Swap.dc.html`. WP11b: a standalone screen (a new lexicon/model is staged but
 * a session is live). **No runtime signal today** — the asset lifecycle (install/verify/
 * activate/roll back/remove, guide §"Assets") is unbuilt; see [DebugFailureOverride]'s kdoc.
 * Register R-148: the Lexicon rows now carry their marker dot (solid for active, hollow ring for
 * staged) and every Option row its own sub-line, both dropped from the first pass; R-151: the
 * "Done" bar uses [FailureActionBarScaffold], not a plain `weight(1f)` split. Register R-448: the
 * board's own "‹ Models and lexicon" back header — [onDone] is the screen's own existing dismiss
 * (unchanged: still a documented no-op stub at the `FailureHost.kt` integration level, same as
 * every other action on this screen), so the header's back chevron reuses it. No
 * `FailureHostActions` callback maps to "Models and lexicon" navigation specifically today —
 * reported, not fabricated.
 */
@Composable
public fun FailAssetSwapScreen(
    state: AssetSwapViewState,
    onSelectOption: (Int) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OrtColors.bgScreen)
            .failureScreenInset()
            .testTag("failure-asset-swap-screen"),
    ) {
        FailureActionBarScaffold(
            content = {
                DrillInHeader(
                    parentLabel = "Models and lexicon",
                    onBack = onDone,
                    modifier = Modifier.testTag("failure-asset-swap-back"),
                )
                Text(
                    text = "Installed, not yet active",
                    style = OrtType.screenTitle,
                    color = OrtColors.textHigh,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
                Banner(
                    title = "A session is running — the swap waits",
                    body = "Replacing the lexicon under a live capture would make tonight's log half one " +
                        "version and half another, with no record of where the line is. So it does not happen.",
                    tone = BannerTone.DEGRADED,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                SectionLabel("Lexicon", modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                LexiconRow(marker = LexiconMarker.ACTIVE, label = state.activeLabel)
                LexiconRow(marker = LexiconMarker.STAGED, label = state.stagedLabel)
                SectionLabel("Options", modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                state.options.forEachIndexed { index, option ->
                    AssetSwapOptionRow(
                        option = option,
                        selected = index == state.selectedOption,
                        onClick = { onSelectOption(index) },
                        modifier = Modifier.testTag("failure-asset-swap-option-$index"),
                    )
                }
                Text(
                    text = "Every over records which lexicon and model resolved it. When an asset changes, " +
                        "the overs from before are the ones Improve records lists — the line is always visible.",
                    style = OrtType.cardBody,
                    color = OrtColors.textDim,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            },
            actionBar = {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
                    PrimaryButton(
                        text = "Done",
                        onClick = onDone,
                        modifier = Modifier.fillMaxWidth().testTag("failure-asset-swap-done"),
                    )
                }
            },
        )
    }
}

private enum class LexiconMarker { ACTIVE, STAGED }

@Composable
private fun LexiconRow(marker: LexiconMarker, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (marker) {
            LexiconMarker.ACTIVE -> Box(modifier = Modifier.size(9.dp).background(OrtColors.accentGreen, CircleShape))
            LexiconMarker.STAGED -> Box(
                modifier = Modifier
                    .size(9.dp)
                    .border(1.5.dp, OrtColors.accentGreen, CircleShape),
            )
        }
        Text(
            text = label,
            style = OrtType.control,
            color = if (marker == LexiconMarker.ACTIVE) OrtColors.textHigh else OrtColors.textBody,
        )
    }
}

@Composable
private fun AssetSwapOptionRow(
    option: AssetSwapOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .border(1.5.dp, if (selected) OrtColors.accentGreen else OrtColors.lineControl, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(modifier = Modifier.size(8.dp).background(OrtColors.accentGreen, CircleShape))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.label,
                style = OrtType.control,
                color = if (selected) OrtColors.textHigh else OrtColors.textBody,
            )
            Text(
                text = option.subLine,
                style = OrtType.subLine,
                color = OrtColors.textFaint,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
