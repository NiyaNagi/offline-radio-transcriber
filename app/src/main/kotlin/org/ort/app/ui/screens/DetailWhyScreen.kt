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
import org.ort.app.ui.components.DrillInHeader
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
 * **Honest gap (report this):** the artboard's step 1 is per-slot phonetic-lattice detail — each
 * unit's score and kept alternate, with an amber border below threshold. `:data`'s
 * [org.ort.data.entity.PhoneticLatticeEntity.unitsBlob] is an opaque blob with no defined per-unit
 * shape yet (that entity's own doc comment: "§9's types own the shape" — a `:lexicon` type never
 * wired to `:data`), so this screen cannot honestly render [org.ort.app.ui.components.LatticeSlot]
 * boxes with real per-letter scores without inventing a blob format no writer produces
 * (constitution I: never fabricate a number). It shows the lattice's real source and model instead,
 * and says plainly that per-slot detail is not recorded — never a fabricated slot row.
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
        if (why.latticeSummary != null) {
            Text(
                text = "Per-slot detail (each unit's score and kept alternate) is not recorded yet.",
                style = OrtType.subLine,
                color = OrtColors.textFaint,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )
        }
    }
}

@Composable
private fun CandidatesSection(why: DetailWhyViewState) {
    Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
        SectionHeader(label = "2 · Candidates that survived")
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
        SectionHeader(label = "3 · Priors, for the chosen candidate")
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
