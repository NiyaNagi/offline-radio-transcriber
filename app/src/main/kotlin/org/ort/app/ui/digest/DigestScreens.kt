package org.ort.app.ui.digest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.Badge
import org.ort.app.ui.components.BadgeKind
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.EmptyState
import org.ort.app.ui.components.SecondaryButton
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.components.Tile
import org.ort.app.ui.improve.Plurals
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
    // E2-G07 (DG05): `Read the overs` → the Log filtered to the card's own over time window, via
    // the same existing time-window filter seed `Frequency.dc.html`'s own "The N overs" stat
    // already uses (R-276) — never a new filtering mechanism. Defaulted so every existing caller
    // keeps compiling unchanged.
    onReadOvers: (fromMillis: Long, toMillis: Long) -> Unit = { _, _ -> },
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Session", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = state.headline, style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = "${state.timeRangeLabel} · ${Plurals.count(state.overCount, "over")} · " +
                    "${Plurals.count(state.stationCount, "station")} · ${Plurals.count(state.bandCount, "band")}" +
                    if (state.gapCount > 0) " · ${Plurals.count(state.gapCount, "gap")}" else "",
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

            // R-146: this section header used to render only when `items` was non-empty, so a
            // session with nothing in `Worth knowing` but something in `Not known tonight` skipped
            // straight to the second section with no acknowledgement of the first — the board
            // always shows `WORTH KNOWING`, with the app's own empty-state sentence when it has
            // nothing to report (the same pattern `Now`'s digest section already uses).
            if (state.items.isNotEmpty() || state.notKnown.isNotEmpty()) {
                SectionHeader(label = "Worth knowing", modifier = Modifier.padding(top = OrtSpacing.md))
                if (state.items.isEmpty()) {
                    Text(
                        text = "Nothing to report yet.",
                        style = OrtType.bodyProse,
                        color = OrtColors.textDim,
                        modifier = Modifier.padding(top = OrtSpacing.xs),
                    )
                } else {
                    state.items.forEach { item -> DigestRow(item = item, onClick = { onOpenItem(item) }) }
                }
            }

            // E2-G07 (DG05, FR-DIG-3, FR-DIG-6, FR-DIG-11): absent entirely — never an empty
            // section — when `state.prose` is `null` (FR-DIG-3a: disabled, or nothing generated
            // yet). Sits between Worth knowing and Not known tonight, per the board's own order.
            state.prose?.let { prose -> DigestProseSection(prose = prose, onReadOvers = onReadOvers) }

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
            // R-146 (round 4, System validator): same root cause as R-137's Diagnostics tiles —
            // `fillMaxWidth()` on each `Tile` (instead of `weight(1f)`) made every tile claim the
            // whole row's width, so only the first ("overs") ever rendered on screen.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
            ) {
                Tile(figure = "${state.overCount}", caption = "overs", modifier = Modifier.weight(1f))
                Tile(figure = state.attributedPercentLabel, caption = "attributed", modifier = Modifier.weight(1f))
                Tile(figure = "${state.rejectedCount}", caption = "rejected", modifier = Modifier.weight(1f))
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.lg, bottom = OrtSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
            ) {
                SecondaryButton(text = "Full log", onClick = onFullLog, modifier = Modifier.weight(1f))
                // R-146: no exporter exists in :app/:pipeline/:net (WP10's original entry, unchanged
                // since — grepped again before writing this) — disabled with an honest reason
                // rather than a fake "Export this night" success, per constitution I.
                SecondaryButton(
                    text = "Export this night",
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * `Digest-Prose.dc.html`'s "In their words" section (E2-G07, FR-DIG-3, FR-DIG-6, FR-DIG-11):
 * badged `generated`, one italic card per [DigestProseSectionViewState.cards], each with its own
 * `Read the overs` action, then [DigestProseSectionViewState.footnote] once beneath every card.
 */
@Composable
private fun DigestProseSection(
    prose: DigestProseSectionViewState,
    onReadOvers: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(top = OrtSpacing.md).testTag("digest-prose-section")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            SectionHeader(label = "In their words", modifier = Modifier.weight(1f, fill = false))
            Badge(text = "generated", kind = BadgeKind.TIER, modifier = Modifier.testTag("digest-prose-badge"))
        }
        prose.cards.forEach { card ->
            DigestProseCard(
                card = card,
                onReadOvers = { onReadOvers(card.fromMillis, card.toMillis) },
                modifier = Modifier.padding(top = OrtSpacing.sm),
            )
        }
        Text(
            text = prose.footnote,
            style = OrtType.subLine,
            color = OrtColors.textDim,
            modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.sm).testTag("digest-prose-footnote"),
        )
    }
}

@Composable
private fun DigestProseCard(card: DigestProseCardViewState, onReadOvers: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OrtColors.bgCard, RoundedCornerShape(10.dp))
            .padding(13.dp)
            .testTag("digest-prose-card"),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = card.subject, style = OrtType.callsignRow, color = OrtColors.textHigh)
            Text(text = "·", style = OrtType.subLine, color = OrtColors.textFaint)
            Text(text = card.detailLine, style = OrtType.subLine, color = OrtColors.textFaint)
        }
        Text(
            text = card.text,
            style = OrtType.bodyProse.copy(fontStyle = FontStyle.Italic),
            color = OrtColors.textSecondary,
            modifier = Modifier.padding(top = 7.dp),
        )
        // R-874 (register, spec): `fillMaxWidth()` plus `weight(1f, fill = false)` on the
        // *leading* label — `TextAction`'s own doc comment (`ui/components/Controls.kt`) names
        // this the required shape: Compose's `Row` measures the non-weighted `TextAction` first,
        // against the row's own real width, before the weighted label ever claims anything, so
        // the label yields (wraps, never the action) when both do not fit at font scale 2.0.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OrtSpacing.md),
        ) {
            Text(
                text = card.oversRangeLabel,
                style = OrtType.subLine,
                color = OrtColors.textFaint,
                modifier = Modifier.weight(1f, fill = false),
            )
            TextAction(text = "Read the overs", onClick = onReadOvers)
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
            text = "The " + Plurals.count(item.transmissionIds.size, "over"),
            onClick = onOpenTheOvers,
            modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.lg),
        )
    }
}
