package org.ort.app.ui.improve

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.ProgressBar
import org.ort.app.ui.components.ScreenHeader
import org.ort.app.ui.components.SecondaryButton
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * `Improve.dc.html` (R-091, P12, FR-REP-1..4/8): what can get better and why, grouped by
 * [org.ort.data.entity.SessionEntity.deviceTier] below the current tier, counts real throughout.
 * P12: framed as "can get better", never "broken" — the empty state below says exactly that.
 */
@Composable
public fun ImproveScreen(
    state: ImproveRootViewState,
    onDrawer: () -> Unit,
    onImproveAll: () -> Unit,
    onOpenGroup: (ImproveGroupViewState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(onDrawer = onDrawer)
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
                Text(
                    text = "${state.totalOverCount} overs can get better",
                    style = OrtType.figure,
                    color = OrtColors.textHigh,
                )
                PrimaryButton(
                    text = "Improve all ${state.totalOverCount}",
                    onClick = onImproveAll,
                    modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.sm, bottom = OrtSpacing.md),
                )

                SectionHeader(label = "Why, and how many")
                state.groups.forEach { group -> ImproveGroupRow(group = group, onClick = { onOpenGroup(group) }) }
                if (state.everythingElseCount > 0) {
                    Text(
                        text = "Everything else is at this phone's best",
                        style = OrtType.rowTitle,
                        color = OrtColors.textBody,
                        modifier = Modifier.padding(top = OrtSpacing.sm),
                    )
                    Text(
                        text = "${state.everythingElseCount} overs · tier ${state.currentTierLabel} · current models",
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
 * wired to a runner that would honour it; offering an unwired checkbox would be dishonest. */
@Composable
public fun ImproveSelectScreen(
    state: ImproveSelectViewState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Improve records", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = state.group.headline, style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = "${state.group.overCount} overs · choose what to re-run",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            SectionHeader(label = "Passes", modifier = Modifier.padding(top = OrtSpacing.md))
            CheckboxRow(label = "Transcribe again with the current model", checked = true, onCheckedChange = {})
            CheckboxRow(label = "Resolve callsigns from the audio", checked = true, onCheckedChange = {})
            CheckboxRow(label = "Match voices to stations heard here", checked = true, onCheckedChange = {})

            SectionHeader(label = "Never re-run", modifier = Modifier.padding(top = OrtSpacing.md))
            CheckboxRow(
                label = "Segmentation",
                checked = false,
                onCheckedChange = {},
                subLine = "where each over starts and ends is the one decision reprocessing cannot undo",
            )

            SectionHeader(label = "Estimate", modifier = Modifier.padding(top = OrtSpacing.md))
            Text(
                text = "Time: " + (
                    state.estimatedSeconds?.let { "about ${it / 60} min" }
                        ?: "not measured — no real-time factor sampled yet this session"
                    ),
                style = OrtType.control,
                color = OrtColors.textBody,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )
            Text(
                text = "Corrections: ${state.correctionsToReapply} already made are kept and re-applied on top",
                style = OrtType.control,
                color = OrtColors.textBody,
            )
            Text(
                text = "Battery and storage are not estimated in this build.",
                style = OrtType.subLine,
                color = OrtColors.textFaint,
                modifier = Modifier.padding(top = 2.dp),
            )

            PrimaryButton(
                text = "Improve ${state.group.overCount} overs",
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.lg, bottom = OrtSpacing.lg),
            )
        }
    }
}

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
