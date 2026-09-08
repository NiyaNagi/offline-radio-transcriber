package org.ort.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.ActionBar
import org.ort.app.ui.components.AttributionRow
import org.ort.app.ui.components.Badge
import org.ort.app.ui.components.BadgeKind
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.data.AffectedOverViewState
import org.ort.app.ui.data.CorrectionTier
import org.ort.app.ui.data.PropagationOutcome
import org.ort.app.ui.data.pluralize
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import org.ort.core.Attribution

/**
 * R-052, `Detail-Propagated.dc.html`: what changed, counted — never implied. Every count here is
 * real (see [org.ort.app.ui.data.CorrectionPolling]'s class doc for exactly which ones this
 * package can and cannot honestly compute), `Undo all` reverts every affected over as a further,
 * kept correction (nothing deleted — constitution III), and every affected row is listed with its
 * struck-through old callsign.
 */
@Composable
public fun PropagatedScreen(
    outcome: PropagationOutcome,
    onUndoAll: () -> Unit,
    onBackToOver: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(modifier = Modifier.padding(OrtSpacing.lg)) {
                Text(
                    text = "Corrected to ${outcome.newCallsign}",
                    style = OrtType.screenTitle,
                    color = OrtColors.textHigh,
                )
                // R-191: `Detail-Propagated.dc.html`'s subtitle names the method and time too
                // ("Was K7LWH · picked from the resolver's candidates · 06:20"), not just the old
                // callsign — both are the real `CorrectionRequest.tier`/`.correctedAtMillis` this
                // propagation was applied with, absent only for the (never on-screen) synthetic
                // outcome `undoAll` builds for its own internal bookkeeping.
                Text(
                    text = "Was ${outcome.previousCallsign ?: "unattributed"}" + subtitleClause(outcome),
                    style = OrtType.subtitle,
                    color = OrtColors.textDim,
                    modifier = Modifier.padding(top = OrtSpacing.xs),
                )
            }
            // R-191: `bg/card` around "What changed" — the board's own container, not a bare list.
            Column(
                modifier = Modifier
                    .padding(horizontal = OrtSpacing.lg)
                    .fillMaxWidth()
                    .background(OrtColors.bgCard, RoundedCornerShape(10.dp))
                    .padding(OrtSpacing.md),
            ) {
                SectionHeader(label = "What changed")
                // `ChangeRow` itself prefixes "$count " — `overWord` supplies only the pluralized
                // noun, or `pluralize(...)` would double the number ("6 6 overs re-attributed").
                val overWord = if (outcome.overCount == 1) "over" else "overs"
                ChangeRow(count = outcome.overCount, label = "$overWord re-attributed")
                // R-190: a genuine zero must not read as if the voiceprint went somewhere — no
                // station is named when it did not move.
                if (outcome.voiceprintReassigned) {
                    ChangeRow(count = 1, label = "voiceprint now belongs to ${outcome.newCallsign}")
                } else {
                    ChangeRow(count = 0, label = "voiceprint unchanged")
                }
                ChangeRow(count = outcome.priorsUpdatedCount, label = priorsUpdatedLabel(outcome))
                ChangeRow(count = outcome.deletedCount, label = "records deleted — every earlier attribution is kept")
                TextAction(
                    text = "Undo all ${outcome.overCount}",
                    onClick = onUndoAll,
                    modifier = Modifier.padding(top = OrtSpacing.sm).semantics { contentDescription = "Undo all" },
                )
            }
            Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
                SectionHeader(label = "The ${pluralize(outcome.overCount, "over")}, as they read now")
                outcome.affected.forEach { row -> AffectedRow(row) }
            }
        }
        ActionBar(
            secondaryLabel = "Back to the over",
            onSecondary = onBackToOver,
            primaryLabel = "Done",
            onPrimary = onDone,
        )
    }
}

/**
 * `Detail-Propagated.dc.html`: "priors updated — on this repeater and recent corrections" — the
 * real, adjusted prior names (`PriorAdjustmentOutcome.name`, snake_case) rendered as prose, joined
 * the way the artboard joins exactly two. Falls back to the bare label when nothing was adjusted
 * (an unverified correction, or one scoped to this over only — see
 * [org.ort.app.ui.data.CorrectionPolling]'s class doc for why), never a fabricated name.
 */
private fun priorsUpdatedLabel(outcome: PropagationOutcome): String {
    val names = outcome.priorAdjustments.map { it.name.replace('_', ' ') }
    return if (names.isEmpty()) {
        "priors updated"
    } else {
        "priors updated — " + names.joinToString(" and ")
    }
}

/** R-191: "· picked from the resolver's candidates · 06:20" — real [CorrectionTier]/time, absent
 * only for the synthetic outcome `undoAll` builds internally (never shown on this screen). */
private fun subtitleClause(outcome: PropagationOutcome): String {
    val method = outcome.tier?.let { " · ${tierMethodLabel(it)}" }.orEmpty()
    val time = outcome.correctedAtMillis?.let { " · ${timeLabel(it)}" }.orEmpty()
    return method + time
}

private fun tierMethodLabel(tier: CorrectionTier): String = when (tier) {
    CorrectionTier.PICK_CANDIDATE -> "picked from the resolver's candidates"
    CorrectionTier.SEARCH_LEXICON -> "picked from stations heard"
    CorrectionTier.FREE_TEXT -> "typed, unverified"
    CorrectionTier.CONFIRM -> "confirmed"
}

/** `Detail-Propagated.dc.html`'s own subtitle clause is HH:MM ("06:20"), not this package's usual
 * HH:MM:SS meta-row precision — a correction time is read as "around when", not to the second. */
private fun timeLabel(utcMillis: Long): String {
    val totalMinutes = utcMillis / 60_000
    val hours = (totalMinutes / 60) % 24
    val minutes = totalMinutes % 60
    return "%02d:%02d".format(java.util.Locale.ROOT, hours, minutes)
}

@Composable
private fun ChangeRow(count: Int, label: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = OrtSpacing.xs)) {
        Text(
            text = "$count $label",
            style = OrtType.control,
            color = OrtColors.textHigh,
        )
    }
}

/**
 * R-191: `Detail-Propagated.dc.html`'s own rows carry a marker and a `CORRECTED` badge, not just
 * the struck-through old callsign. Every row here is the real result of
 * [org.ort.core.Attribution.withCorrection] (INFERRED, corrected — [CorrectionDao.recordCorrection]'s
 * own write shape, see `CorrectionPolling`'s class doc), so [AttributionRow] (WP2, reused rather
 * than a hand-rolled look-alike marker) renders the identical ring every other INFERRED,
 * corrected row in this app uses.
 */
@Composable
private fun AffectedRow(row: AffectedOverViewState) {
    val attribution = Attribution.unknown().withCorrection(row.newCallsign)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = OrtSpacing.sm)
            .semantics(mergeDescendants = true) {
                contentDescription = "${row.timeLabel}, corrected from ${row.oldCallsign ?: "unattributed"} " +
                    "to ${row.newCallsign}, ${row.transcriptExcerpt}"
            },
    ) {
        Text(text = row.timeLabel, style = OrtType.timeFreq, color = OrtColors.textFaint)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AttributionRow(attribution = attribution, callsign = row.newCallsign)
            row.oldCallsign?.let {
                Text(
                    text = it,
                    style = OrtType.signal.copy(textDecoration = TextDecoration.LineThrough),
                    color = OrtColors.textLow,
                    modifier = Modifier.padding(start = OrtSpacing.sm),
                )
            }
            Badge(
                text = "corrected",
                kind = BadgeKind.CORRECTED,
                modifier = Modifier.padding(start = OrtSpacing.sm),
            )
        }
        Text(text = row.transcriptExcerpt, style = OrtType.transcript, color = OrtColors.textSecondary)
    }
}
