package org.ort.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.LatticeSlot
import org.ort.app.ui.components.LatticeSlotViewState
import org.ort.app.ui.components.PriorBar
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.data.DetailWhyViewState
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * R-051, FR-UI-8, `Detail-Why.dc.html`: "everything the resolver saw, in the order it used it" —
 * the exhaustive counterpart to [TransmissionDetailScreen]'s inline "why this callsign" preview.
 *
 * **Register R-320 (schema v5), fixed.** Section 1 now renders the winning candidate's real
 * per-slot [org.ort.app.ui.components.LatticeSlot] grid (unit, score, kept alternate, amber border
 * below threshold) from [DetailWhyViewState.winningSlots] — `:data`'s
 * [org.ort.data.entity.LatticeSlotEntity] (schema v5) closed the gap this screen's earlier revision
 * reported (`PhoneticLatticeEntity.unitsBlob` is still an opaque blob; the slot table is the real,
 * separately-written detail). A record from before schema v5 has no slot rows at all — shown as the
 * honest "not recorded for this over" line, never a placeholder grid (constitution I). Section 2's
 * grammar line is the winning candidate's own real `grammarValid` bit — **not** the board's own
 * "prefix K · region 7 · suffix LWH" per-component parse, which no API this package can reach
 * returns; shown as that bit alone, never an invented breakdown.
 */
@Composable
public fun DetailWhyScreen(
    callsignLabel: String,
    why: DetailWhyViewState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        DrillInHeader(parentLabel = callsignLabel, onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
            Text(text = "Why this callsign", style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = "Everything the resolver saw, in the order it used it",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (!why.hasData) {
                Text(
                    text = "No resolver output recorded for this transmission yet.",
                    style = OrtType.cardBody,
                    color = OrtColors.textMuted,
                    modifier = Modifier.padding(OrtSpacing.lg),
                )
                return@Column
            }
            LatticeSection(why)
            GrammarSection(why)
            CandidatesSection(why)
            PriorsSection(why)
        }
    }
}

@Composable
private fun LatticeSection(why: DetailWhyViewState) {
    Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
        SectionHeader(label = "1 · Phonetic lattice")
        Text(
            text = why.latticeSummary ?: "No lattice recorded for this transmission.",
            style = OrtType.control,
            color = OrtColors.textBody,
            modifier = Modifier.padding(top = OrtSpacing.xs),
        )
        if (why.winningSlots.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(OrtSpacing.xs),
                modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.sm),
            ) {
                why.winningSlots.forEach { slot ->
                    LatticeSlot(
                        state = LatticeSlotViewState(
                            unit = slot.unit,
                            score = slot.score,
                            alternate = slot.keptAlternate,
                            belowThreshold = slot.belowThreshold,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else if (why.latticeSummary != null) {
            Text(
                text = "Per-slot detail is not recorded for this over.",
                style = OrtType.subLine,
                color = OrtColors.textFaint,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )
        }
    }
}

/** Register R-320: the winning candidate's own real `grammarValid` bit — see this file's class doc
 * for why this is not the board's own per-component parse breakdown. */
@Composable
private fun GrammarSection(why: DetailWhyViewState) {
    val grammarValid = why.winningGrammarValid ?: return
    Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
        SectionHeader(label = "2 · Grammar")
        Text(
            text = if (grammarValid) {
                "Parsed as a valid callsign grammar."
            } else {
                "Did not parse as a valid callsign grammar."
            },
            style = OrtType.control,
            color = OrtColors.textBody,
            modifier = Modifier.padding(top = OrtSpacing.xs),
        )
    }
}

@Composable
private fun CandidatesSection(why: DetailWhyViewState) {
    Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
        SectionHeader(label = "3 · Candidates that survived")
        why.candidates.forEach { candidate ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = OrtSpacing.sm)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${candidate.callsign}, ${candidate.scoreLabel}" +
                            if (candidate.chosen) ", chosen" else ""
                    },
            ) {
                Text(
                    text = candidate.callsign,
                    style = OrtType.callsignRow,
                    color = if (candidate.chosen) OrtColors.textHigh else OrtColors.textBody,
                )
                Text(
                    text = candidate.scoreLabel + if (candidate.chosen) " · chosen" else "",
                    style = OrtType.cardBody,
                    color = if (candidate.chosen) OrtColors.scoreGood else OrtColors.textDim,
                )
            }
        }
        why.runnerUp?.let {
            Text(
                text = "Runner-up · ${it.callsign} (${it.scoreLabel})",
                style = OrtType.cardBody,
                color = OrtColors.textFaint,
                modifier = Modifier.padding(top = OrtSpacing.sm),
            )
        }
    }
}

@Composable
private fun PriorsSection(why: DetailWhyViewState) {
    if (why.priors.isEmpty()) return
    Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
        SectionHeader(label = "4 · Priors, for the chosen candidate")
        why.priors.forEach { prior -> PriorBar(state = prior, modifier = Modifier.padding(top = OrtSpacing.sm)) }
        Text(
            text = "A prior that argued against is negative and amber. One with no data yet reads " +
                "\"cold start\", not zero — the two are different facts.",
            style = OrtType.subLine,
            color = OrtColors.textFaint,
            modifier = Modifier.padding(top = OrtSpacing.sm),
        )
    }
}
