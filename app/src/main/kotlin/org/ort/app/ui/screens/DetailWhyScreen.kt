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
import org.ort.app.ui.components.ActionBar
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
 *
 * **Register R-1144.** [onNotRight]/[onConfirm] (both nullable, defaulted `null` so every existing
 * caller compiles and renders unchanged) are `Detail-Why.dc.html`'s own bottom action bar — the
 * identical `ActionBar("Not right?", "Confirm")` [TransmissionDetailScreen]'s own `BottomActionBar`
 * already draws for [org.ort.app.ui.data.DetailBodyViewState.Inferred], reused here rather than a
 * second, hand-rolled bar. Rendered only when **both** callbacks are supplied together — never one
 * without the other, since a caller that can wire `Confirm` genuinely has a resolved `stationId` to
 * confirm and one that cannot (AMBIGUOUS, UNKNOWN, already-CONFIRMED) has neither, exactly the same
 * gating `TransmissionDetailContent`'s own `WhyDestination` applies before ever passing them in —
 * see that composable's own doc comment for why this screen only ever draws the one bar shape the
 * artboard actually specifies, not a guess at what the other three attribution states would need.
 *
 * **Register R-1148, the per-candidate reasons — now built.** R-1144 declined to build these
 * because `CallsignGrammar.slotDetailsFor` computes one `SlotDetail` list **per lattice, not per
 * candidate**, and `DataPassBResultSink.persistCandidates` persisted that identical shared list
 * under every candidate's own row — a reason built from it would have been true by accident for
 * the chosen candidate and fabricated for every runner-up. `CallsignGrammar` now also records a
 * genuinely per-candidate [org.ort.lexicon.SlotAlignment] list, live, as each candidate's own path
 * is walked through the beam search, and `:pipeline`/`:data` persist it onto the same
 * `lattice_slot` rows (`candidateUnit`/`offeredByLattice`, schema v17). Each candidate's row below
 * shows [org.ort.app.ui.data.RankedCandidateViewState.reason] — one real difference between that
 * candidate's own path and what the lattice recorded, or nothing at all when no such difference
 * was recorded (see [org.ort.app.ui.data.DetailViewStateMapper]'s `reasonFor` for exactly which
 * case that is). Never a claim about acoustic confidence: register R-1121 established there is
 * none anywhere in this system, so a reason speaks only to presence, absence and mismatch.
 */
@Composable
public fun DetailWhyScreen(
    callsignLabel: String,
    why: DetailWhyViewState,
    onBack: () -> Unit,
    onNotRight: (() -> Unit)? = null,
    onConfirm: (() -> Unit)? = null,
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
        if (onNotRight != null && onConfirm != null) {
            ActionBar(
                secondaryLabel = "Not right?",
                onSecondary = onNotRight,
                primaryLabel = "Confirm",
                onPrimary = onConfirm,
            )
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
                            (if (candidate.chosen) ", chosen" else "") +
                            candidate.reason?.let { ", $it" }.orEmpty()
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
                // Register R-1148: one real, per-candidate fact -- absent, never a generic filler,
                // when no honest difference from the lattice was recorded for this candidate.
                candidate.reason?.let { reason ->
                    Text(
                        text = reason,
                        style = OrtType.subLine,
                        color = OrtColors.textFaint,
                    )
                }
            }
        }
        // R-1117 (register): `why.runnerUp` is always one of the entries already rendered above —
        // `DetailViewStateMapper.whyFor` builds it from the same `ranked` list `candidates` maps in
        // full (`ranked.firstOrNull { it !== chosen }`), so a second, separate "Runner-up · ..."
        // line here duplicated a row the list just drew, a defect against `Detail-Why.dc.html` (the
        // artboard lists every surviving candidate once, chosen or not, with no separate runner-up
        // callout at all). Removed rather than deduplicated in the mapper — `why.runnerUp` is still
        // real data other callers (`TransmissionDetailScreen`'s own inline preview) legitimately use.
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
