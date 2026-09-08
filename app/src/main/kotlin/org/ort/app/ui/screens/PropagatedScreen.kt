package org.ort.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.ort.app.ui.components.ActionBar
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.data.AffectedOverViewState
import org.ort.app.ui.data.PropagationOutcome
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

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
                Text(
                    text = "Was ${outcome.previousCallsign ?: "unattributed"}",
                    style = OrtType.subtitle,
                    color = OrtColors.textDim,
                    modifier = Modifier.padding(top = OrtSpacing.xs),
                )
            }
            Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
                SectionHeader(label = "What changed")
                ChangeRow(count = outcome.overCount, label = "overs re-attributed")
                ChangeRow(
                    count = if (outcome.voiceprintReassigned) 1 else 0,
                    label = "voiceprint now belongs to ${outcome.newCallsign}",
                )
                ChangeRow(count = outcome.priorsUpdatedCount, label = priorsUpdatedLabel(outcome))
                ChangeRow(count = outcome.deletedCount, label = "records deleted — every earlier attribution is kept")
                TextAction(
                    text = "Undo all ${outcome.overCount}",
                    onClick = onUndoAll,
                    modifier = Modifier.padding(top = OrtSpacing.sm).semantics { contentDescription = "Undo all" },
                )
            }
            Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
                SectionHeader(label = "The ${outcome.overCount} overs, as they read now")
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

@Composable
private fun AffectedRow(row: AffectedOverViewState) {
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
        Text(
            text = row.newCallsign,
            style = OrtType.callsignRow.copy(fontWeight = FontWeight.SemiBold),
            color = OrtColors.textHigh,
        )
        row.oldCallsign?.let {
            Text(
                text = it,
                style = OrtType.signal.copy(textDecoration = TextDecoration.LineThrough),
                color = OrtColors.textLow,
            )
        }
        Text(text = row.transcriptExcerpt, style = OrtType.transcript, color = OrtColors.textSecondary)
    }
}
