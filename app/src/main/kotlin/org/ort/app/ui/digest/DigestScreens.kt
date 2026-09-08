package org.ort.app.ui.digest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.EmptyState
import org.ort.app.ui.components.SecondaryButton
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.Tile
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/** `Digest.dc.html` (FR-DIG-1..6): the night's digest, salience-ordered, with `Full log`.
 * [DigestViewState.items]/[DigestViewState.notKnown] are exactly what [DigestPolling] could derive
 * for real — see that object's own doc comment for which item kinds it does not invent. */
@Composable
public fun DigestScreen(
    state: DigestViewState,
    onBack: () -> Unit,
    onOpenItem: (DigestItemViewState) -> Unit,
    onFullLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Session", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = state.headline, style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = "${state.timeRangeLabel} · ${state.overCount} overs · ${state.stationCount} stations · " +
                    "${state.bandCount} band(s)" + if (state.gapCount > 0) " · ${state.gapCount} gap(s)" else "",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            if (state.items.isEmpty() && state.notKnown.isEmpty()) {
                EmptyState(
                    message = "Nothing salient found for this session.",
                    subMessage = "The full log is always the complete record.",
                )
            }

            if (state.items.isNotEmpty()) {
                SectionHeader(label = "Worth knowing", modifier = Modifier.padding(top = OrtSpacing.md))
                state.items.forEach { item -> DigestRow(item = item, onClick = { onOpenItem(item) }) }
            }

            if (state.notKnown.isNotEmpty()) {
                SectionHeader(label = "Not known tonight", modifier = Modifier.padding(top = OrtSpacing.md))
                state.notKnown.forEach { item ->
                    Column(modifier = Modifier.padding(vertical = OrtSpacing.xs)) {
                        Text(text = item.headline, style = OrtType.bodyProse, color = OrtColors.textSecondary)
                        Text(text = item.subLine, style = OrtType.subLine, color = OrtColors.textDim)
                    }
                }
            }

            SectionHeader(label = "By the numbers", modifier = Modifier.padding(top = OrtSpacing.md))
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
            ) {
                Tile(figure = "${state.overCount}", caption = "overs", modifier = Modifier.fillMaxWidth())
                Tile(figure = state.attributedPercentLabel, caption = "attributed", modifier = Modifier.fillMaxWidth())
                Tile(figure = "${state.rejectedCount}", caption = "rejected", modifier = Modifier.fillMaxWidth())
            }

            SecondaryButton(
                text = "Full log",
                onClick = onFullLog,
                modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.lg, bottom = OrtSpacing.lg),
            )
        }
    }
}

@Composable
private fun DigestRow(item: DigestItemViewState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = OrtSpacing.sm)
            .semantics(mergeDescendants = true) { contentDescription = "${item.headline}. ${item.subLine}" },
    ) {
        DigestDot(ambiguous = item.ambiguousTone, modifier = Modifier.padding(top = 5.dp, end = OrtSpacing.sm))
        Column {
            Text(text = item.headline, style = OrtType.bodyProse, color = OrtColors.textHigh)
            Text(
                text = item.subLine,
                style = OrtType.subLine,
                color = OrtColors.textFaint,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun DigestDot(ambiguous: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(9.dp)) {
        if (ambiguous) {
            drawCircle(
                color = OrtColors.accentAmber,
                radius = size.minDimension / 2 - 0.75.dp.toPx(),
                style = Stroke(1.5.dp.toPx()),
            )
            clipRect(right = size.minDimension / 2) {
                drawCircle(color = OrtColors.accentAmber, radius = size.minDimension / 2 - 0.75.dp.toPx())
            }
        } else {
            drawCircle(color = OrtColors.accentGreen, radius = size.minDimension / 2)
        }
    }
}

/** `Digest-Item.dc.html` (FR-DIG-2a/2c, P2): one finding expanded, with its evidence and
 * `Why this is notable` — the [DigestItemViewState.reason] FR-DIG-2c requires be visible. */
@Composable
public fun DigestItemScreen(
    item: DigestItemViewState,
    onBack: () -> Unit,
    onOpenTheOvers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = OrtSpacing.lg)) {
        DrillInHeader(parentLabel = "Digest", onBack = onBack)
        Text(
            text = item.headline,
            style = OrtType.screenTitle,
            color = OrtColors.textHigh,
            modifier = Modifier.padding(top = OrtSpacing.sm),
        )
        Text(
            text = item.subLine,
            style = OrtType.subtitle,
            color = OrtColors.textDim,
            modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
        )

        SectionHeader(label = "Why this is notable", modifier = Modifier.padding(top = OrtSpacing.md))
        Text(
            text = item.reason,
            style = OrtType.bodyProse,
            color = OrtColors.textBody,
            modifier = Modifier.padding(top = OrtSpacing.xs),
        )

        SecondaryButton(
            text = "The ${item.transmissionIds.size} over(s)",
            onClick = onOpenTheOvers,
            modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.lg),
        )
    }
}
