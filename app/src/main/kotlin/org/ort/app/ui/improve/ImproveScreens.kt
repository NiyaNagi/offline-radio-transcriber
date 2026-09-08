package org.ort.app.ui.improve

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.CheckboxRow
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.EmptyState
import org.ort.app.ui.components.KeyValueRow
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.ProgressBar
import org.ort.app.ui.components.SecondaryButton
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

private val IMPROVE_CARD_SHAPE = RoundedCornerShape(10.dp)

/**
 * `Improve.dc.html` (R-091, P12, FR-REP-1..4/8): what can get better and why, grouped by
 * [org.ort.data.entity.SessionEntity.deviceTier] below the current tier, counts real throughout.
 * P12: framed as "can get better", never "broken" — the empty state below says exactly that.
 *
 * R-130 (round 4, System validator): draws no `ScreenHeader` of its own — `OrtNavHost`'s
 * `NavHostBody` already renders one for the whole `IMPROVE_RECORDS` destination before dispatching
 * to `ImproveContent`, so a second one here stacked two bare drawer-icon rows. [onDrawer] stays a
 * parameter (unused in this file) only so `ImproveContent`'s signature and `OrtNavHost.kt`'s call
 * site — outside this package's row — need no edit.
 */
@Suppress("UnusedParameter") // onDrawer: kept only so ImproveContent's signature needs no edit — see kdoc above.
@Composable
public fun ImproveScreen(
    state: ImproveRootViewState,
    onDrawer: () -> Unit,
    onImproveAll: () -> Unit,
    onOpenGroup: (ImproveGroupViewState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = OrtSpacing.lg),
        ) {
            Text(
                text = "Improve records",
                style = OrtType.screenTitle,
                color = OrtColors.textHigh,
                modifier = Modifier.padding(top = OrtSpacing.sm),
            )
            Text(
                text = "This phone can do more than some of these were done with",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.md),
            )

            if (state.totalOverCount == 0) {
                EmptyState(
                    message = "Nothing can get better right now.",
                    subMessage = "Every recorded session is at tier ${state.currentTierLabel} — this " +
                        "phone's own capability — or better. Nothing here is broken; there is simply " +
                        "nothing below current capability to reprocess.",
                )
            } else {
                // R-141 (round 4, System validator): the summary now sits in the `bg/card` container
                // the board draws it in, and its count uses the shared [Plurals] helper — "1 overs"
                // was this exact row before this fix.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(OrtColors.bgCard, IMPROVE_CARD_SHAPE)
                        .padding(14.dp),
                ) {
                    Text(
                        text = Plurals.count(state.totalOverCount, "over") + " can get better",
                        style = OrtType.figure,
                        color = OrtColors.textHigh,
                    )
                    PrimaryButton(
                        text = "Improve all ${state.totalOverCount}",
                        onClick = onImproveAll,
                        modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.sm),
                    )
                }

                SectionHeader(label = "Why, and how many", modifier = Modifier.padding(top = OrtSpacing.md))
                state.groups.forEach { group -> ImproveGroupRow(group = group, onClick = { onOpenGroup(group) }) }
                if (state.everythingElseCount > 0) {
                    Text(
                        text = "Everything else is at this phone's best",
                        style = OrtType.rowTitle,
                        color = OrtColors.textBody,
                        modifier = Modifier.padding(top = OrtSpacing.sm),
                    )
                    Text(
                        text = "${Plurals.count(state.everythingElseCount, "over")} · tier " +
                            "${state.currentTierLabel} · current models",
                        style = OrtType.subLine,
                        color = OrtColors.textDim,
                    )
                }
                Text(
                    text = "Groups overlap where an over qualifies more than one way. Capturing in the " +
                        "field on a spare phone and improving at home is how this is meant to be used.",
                    style = OrtType.cardBody,
                    color = OrtColors.textFaint,
                    modifier = Modifier.padding(top = OrtSpacing.lg, bottom = OrtSpacing.lg),
                )
            }
        }
    }
}

@Composable
private fun ImproveGroupRow(group: ImproveGroupViewState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = OrtSpacing.sm)
            .semantics(mergeDescendants = true) { contentDescription = "${group.headline}. ${group.subLine}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = group.headline, style = OrtType.rowTitle, color = OrtColors.textHigh)
            Text(
                text = group.subLine,
                style = OrtType.subLine,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(text = "${group.overCount}", style = OrtType.callsignRow, color = OrtColors.textBody)
        Icon(
            imageVector = OrtIcons.chevron,
            contentDescription = null,
            tint = OrtColors.textSignal,
            modifier = Modifier.padding(start = OrtSpacing.sm),
        )
    }
}

/** `Improve-Select.dc.html`: scope + estimate. The three passes are fixed on (transcribe, resolve,
 * identity) — there is nothing to opt out of yet, since this build has no real per-pass toggle
 * wired to a runner that would honour it; offering an unwired checkbox would be dishonest.
 *
 * R-151 (round 4, System validator, "R02" half): the action bar and the scrolling body above it
 * are two siblings in one [Column] — the body takes [Modifier.weight] (whatever space the bar
 * doesn't use) and scrolls inside that fixed allocation, the bar keeps its own natural height
 * outside it. Because Compose lays out weighted and unweighted siblings by subtracting the
 * unweighted one's measured height from the total *before* measuring the weighted one, the button
 * can never sit on top of (or be clipped by) the scrolling content, at any font scale — unlike an
 * overlay/`Box` pinned bar, which needs the scrolling content's own bottom padding kept in sync
 * with the bar's height by hand. This is the "content scrolls above a bar with bottom padding = bar
 * height" pattern the finding names, applied the one way that cannot drift out of sync.
 */
@Composable
public fun ImproveSelectScreen(
    state: ImproveSelectViewState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        DrillInHeader(parentLabel = "Improve records", onBack = onBack)
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = OrtSpacing.lg),
        ) {
            Text(text = state.group.headline, style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = "${Plurals.count(state.group.overCount, "over")} · choose what to re-run",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            SectionHeader(label = "Passes", modifier = Modifier.padding(top = OrtSpacing.md))
            PassCheckboxRows(tierOrdinal = state.group.tierOrdinal)

            SectionHeader(label = "Never re-run", modifier = Modifier.padding(top = OrtSpacing.md))
            CheckboxRow(
                label = "Segmentation",
                checked = false,
                onCheckedChange = {},
                subLine = "where each over starts and ends is the one decision reprocessing cannot undo",
            )

            SectionHeader(label = "Estimate", modifier = Modifier.padding(top = OrtSpacing.md))
            KeyValueRow(
                key = "Time",
                value = state.estimatedSeconds?.let { "about ${it / 60} min" } ?: "not estimated",
                subLine = if (state.estimatedSeconds == null) {
                    "no real-time factor sampled yet this session"
                } else {
                    "runs behind live capture, never ahead of it"
                },
            )
            KeyValueRow(key = "Battery", value = "not estimated", subLine = "not measured in this build")
            KeyValueRow(key = "Storage", value = "not estimated", subLine = "superseded transcripts are kept")
            KeyValueRow(
                key = "Corrections",
                value = "${state.correctionsToReapply}",
                subLine = "already made are kept and re-applied on top",
            )
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.md)) {
            PrimaryButton(
                text = "Improve ${state.group.overCount} overs",
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** R-142 (register): a sub-line per pass, naming what it is and whether it already ran at this
 * group's tier — [tierOrdinal] is the one real fact [ImprovePolling] has about the group (see
 * [org.ort.data.entity.SessionEntity.deviceTier]); no per-tier model name is asserted here (this
 * build carries no tier→model-name table), so the sub-line states only what is real: the pass's
 * place in the pipeline and whether this tier already ran it. */
@Composable
private fun PassCheckboxRows(tierOrdinal: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        CheckboxRow(
            label = "Transcribe again with the current model",
            checked = true,
            onCheckedChange = {},
            subLine = "Pass B · re-runs whatever tier $tierOrdinal's model produced",
        )
        CheckboxRow(
            label = "Resolve callsigns from the audio",
            checked = true,
            onCheckedChange = {},
            subLine = "Pass C · " + if (tierOrdinal < PASS_C_TIER) {
                "not run at tier $tierOrdinal · resolves callsigns from the audio itself"
            } else {
                "already ran at tier $tierOrdinal · re-resolves from the audio itself"
            },
        )
        CheckboxRow(
            label = "Match voices to stations heard here",
            checked = true,
            onCheckedChange = {},
            subLine = "Identity · " + if (tierOrdinal < VOICE_MATCH_TIER) {
                "not run at tier $tierOrdinal · matches voices heard in this capture"
            } else {
                "already ran at tier $tierOrdinal · re-matches voices heard in this capture"
            },
        )
    }
}

private const val VOICE_MATCH_TIER = 2
private const val PASS_C_TIER = 3

/** `Improve-Running.dc.html`: progress, pausable, capture unaffected. No fabricated per-item diff
 * (see [ImproveRunner]'s kdoc for why) — the honest content is the count and the honesty note. */
@Composable
public fun ImproveRunningScreen(
    state: ImproveRunningViewState,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = OrtSpacing.lg)) {
        Text(
            text = if (state.paused) "Paused" else "Improving",
            style = OrtType.screenTitle,
            color = OrtColors.textHigh,
            modifier = Modifier.padding(top = OrtSpacing.sm),
        )
        Text(
            text = "${state.headline} · ${state.doneCount} of ${state.totalCount}",
            style = OrtType.subtitle,
            color = OrtColors.textDim,
            modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
        )
        ProgressBar(progress = if (state.totalCount == 0) 0f else state.doneCount.toFloat() / state.totalCount)

        Text(
            text = "Live capture is unaffected — it always has priority. Pausing keeps what is done. " +
                "Cancelling keeps what is done too — nothing is rolled back.",
            style = OrtType.cardBody,
            color = OrtColors.textBody,
            modifier = Modifier.padding(top = OrtSpacing.md),
        )
        Text(
            text = "No reprocessing engine exists in this build (see this package's report) — this " +
                "run only clears the reprocess-candidate mark on each over; it does not rewrite any " +
                "transcript, attribution or callsign.",
            style = OrtType.cardBody,
            color = OrtColors.textFaint,
            modifier = Modifier.padding(top = OrtSpacing.sm),
        )

        Column(modifier = Modifier.weight(1f)) {}

        Column(modifier = Modifier.fillMaxWidth().padding(bottom = OrtSpacing.lg)) {
            SecondaryButton(
                text = if (state.paused) "Resume" else "Pause",
                onClick = onPause,
                modifier = Modifier.fillMaxWidth(),
            )
            TextAction(
                text = "Cancel",
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.xs),
            )
        }
    }
}

/** `Improve-Done.dc.html`: counts, and — honestly — no sample diff, for the same reason
 * [ImproveRunningScreen] shows none (see [ImproveRunner]'s kdoc). */
@Composable
public fun ImproveDoneScreen(state: ImproveDoneViewState, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = OrtSpacing.lg)) {
        Text(
            text = "Done",
            style = OrtType.screenTitle,
            color = OrtColors.textHigh,
            modifier = Modifier.padding(top = OrtSpacing.sm),
        )
        Text(
            text = state.headline,
            style = OrtType.subtitle,
            color = OrtColors.textDim,
            modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
        )
        Text(
            text = "${state.clearedCount} overs no longer marked as reprocessing candidates",
            style = OrtType.control,
            color = OrtColors.textBody,
        )
        Text(
            text = "Nothing is deleted (P9): every current record was kept as-is. No content was " +
                "rewritten — see this package's report for what a real reprocessing pass still needs.",
            style = OrtType.cardBody,
            color = OrtColors.textFaint,
            modifier = Modifier.padding(top = OrtSpacing.sm),
        )
        Column(modifier = Modifier.weight(1f)) {}
        PrimaryButton(
            text = "Done",
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().padding(bottom = OrtSpacing.lg),
        )
    }
}
