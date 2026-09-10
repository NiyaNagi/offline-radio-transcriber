package org.ort.app.ui.digest

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.ActivityPatternChart
import org.ort.app.ui.components.Badge
import org.ort.app.ui.components.BadgeKind
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.EmptyState
import org.ort.app.ui.components.KeyValueRow
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.components.drawHatchRegion
import org.ort.app.ui.improve.Plurals
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/** `Sessions.dc.html` (R-092, R-107, FR-UI-1, FR-RUN-12): earlier nights, each with span, counts,
 * gaps — the `TIER 1` chip and "can be improved" when [SessionRowViewState.canBeImproved].
 *
 * R-130 (round 4, System validator): draws no `ScreenHeader` of its own — `OrtNavHost`'s
 * `NavHostBody` already renders one for the whole `EARLIER_NIGHTS` destination before dispatching
 * to `SessionsContent`, so a second one here stacked two bare drawer-icon rows. [onDrawer] stays a
 * parameter (unused in this file) only so `SessionsContent`'s signature and `OrtNavHost.kt`'s call
 * site — outside this package's row — need no edit.
 */
@Suppress("UnusedParameter") // onDrawer: kept only so SessionsContent's signature needs no edit — see kdoc above.
@Composable
public fun SessionsScreen(
    state: SessionsViewState,
    onDrawer: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = OrtSpacing.lg),
        ) {
            Text(
                text = "Earlier nights",
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

            if (state.sessions.isEmpty()) {
                EmptyState(
                    message = "No sessions recorded yet.",
                    subMessage = "A night's capture appears here once it starts.",
                )
            } else {
                // R-144: a month header (`Sessions.dc.html`'s "September"/"August") whenever the
                // real month a session started in changes from the previous row — sessions arrive
                // newest-first, so this is a single forward pass, never a re-sort.
                var previousMonth: java.time.YearMonth? = null
                state.sessions.forEach { session ->
                    val month = java.time.Instant.ofEpochMilli(session.startedAtUtc)
                        .atZone(java.time.ZoneId.systemDefault())
                        .let { java.time.YearMonth.from(it) }
                    if (month != previousMonth) {
                        SectionHeader(
                            label = month.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US),
                            modifier = Modifier.padding(top = if (previousMonth == null) 0.dp else OrtSpacing.md),
                        )
                        previousMonth = month
                    }
                    SessionRow(session = session, onClick = { onOpen(session.id) })
                }
            }
        }
    }
}

/** R-141/R-144 (register, round 4 System validator): the one place [SessionRowViewState]'s
 * over/station/gap counts become "N over(s)" — used for both the row's visible sub-line and its
 * (otherwise merged-descendant) content description, so the two can never say different things. */
private fun sessionRowSummary(session: SessionRowViewState): String = buildString {
    append(session.timeRangeLabel)
    append(" · ${Plurals.count(session.overCount, "over")} · ${Plurals.count(session.stationCount, "station")}")
    if (session.gapCount > 0) append(" · ${Plurals.count(session.gapCount, "gap")}")
    session.uncleanEndLabel?.let { append(" · $it") }
    if (session.canBeImproved) append(" · can be improved")
}

@Composable
private fun SessionRow(session: SessionRowViewState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = "${session.label}. ${sessionRowSummary(session)}"
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = OrtSpacing.sm)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
    ) {
        Row(modifier = Modifier.weight(1f).padding(end = OrtSpacing.sm)) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(OrtSpacing.xs),
                ) {
                    Text(text = session.label, style = OrtType.rowTitle, color = OrtColors.textHigh)
                    session.tierChipLabel?.let { Badge(text = "TIER ${it.removePrefix("T")}", kind = BadgeKind.TIER) }
                }
                SessionRowSubLine(session = session, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Icon(imageVector = OrtIcons.chevron, contentDescription = null, tint = OrtColors.textSignal)
    }
}

/** R-144: the visible half of [sessionRowSummary] — split out so the gap clause, when present, can
 * carry the board's own hatch swatch + `accent/gap` amber rather than plain body text. The merged
 * [Row] content description above still reads the plain-string form, so nothing here changes what
 * TalkBack announces. */
@Composable
private fun SessionRowSubLine(session: SessionRowViewState, modifier: Modifier = Modifier) {
    val base = buildString {
        append(session.timeRangeLabel)
        append(" · ${Plurals.count(session.overCount, "over")} · ${Plurals.count(session.stationCount, "station")}")
        session.uncleanEndLabel?.let { append(" · $it") }
        if (session.canBeImproved) append(" · can be improved")
    }
    val bodyColor = if (session.canBeImproved) OrtColors.accentGreen else OrtColors.textDim
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text = base, style = OrtType.subLine, color = bodyColor)
        if (session.gapCount > 0) {
            Text(text = " · ", style = OrtType.subLine, color = bodyColor)
            androidx.compose.foundation.Canvas(modifier = Modifier.padding(end = 4.dp).size(7.dp, 6.dp)) {
                // R-250 (register, round 6 System validator pass 2, halt): this used to compute its
                // own stripe pitch as a fraction of the canvas width (`size.width / 3f`) — a canvas
                // laid out at zero width during a font-scale-2.0 relayout made that pitch zero too,
                // so the loop's own `x += stripeWidth * 2` never advanced and the draw pass spun
                // forever on the main thread (an ANR, never recovering). Reuses
                // `org.ort.app.ui.components.drawHatchRegion` — the same shared, fixed-dp-pitch
                // hatch `ActivityPatternChart`'s own not-listening texture already draws with,
                // which cannot degenerate this way since its pitch is a caller-supplied constant,
                // never derived from the canvas size.
                if (size.width > 0f && size.height > 0f) {
                    drawHatchRegion(
                        left = 0f,
                        top = 0f,
                        width = size.width,
                        height = size.height,
                        color = OrtColors.accentAmber,
                        pitchPx = 4.dp.toPx(),
                        strokeWidthPx = 2.dp.toPx(),
                    )
                }
            }
            Text(
                text = Plurals.count(session.gapCount, "gap"),
                style = OrtType.subLine,
                color = OrtColors.accentAmber,
            )
        }
    }
}

/** `Session.dc.html` (FR-RUN-12, FR-RUN-16): span, coverage (WP2's [ActivityPatternChart] with
 * gaps as not-listening), the gap list with each [org.ort.data.entity.CaptureGapCause] as prose,
 * and session facts. `Log` opens the LOG destination filtered to this exact session. */
@Composable
public fun SessionDetailScreen(
    state: SessionDetailViewState,
    onBack: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenDigest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Earlier nights", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = state.label, style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = "${state.timeRangeLabel} · ${state.durationLabel} · " +
                    (state.uncleanEndLabel ?: "ended cleanly"),
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            // R-145 (round 4, System validator): `SectionHeader` already draws "Coverage" — passing
            // a second `title` to `ActivityPatternChart` drew it again immediately below. `title =
            // null` keeps the chart's own summary/description intact, just without its own label.
            SectionHeader(label = "Coverage", modifier = Modifier.padding(top = OrtSpacing.md))
            ActivityPatternChart(
                pattern = state.coverage,
                title = null,
                summaryLabel = "Session coverage",
                notListeningLabel = state.notListeningLabel,
                modifier = Modifier.padding(top = OrtSpacing.sm),
            )

            if (state.gaps.isNotEmpty()) {
                SectionHeader(label = "Gaps", modifier = Modifier.padding(top = OrtSpacing.md))
                state.gaps.forEach { gap ->
                    KeyValueRow(
                        key = gap.timeLabel,
                        value = "${gap.durationLabel} · ${gap.causeLabel}",
                        subLine = gap.resumedLabel,
                    )
                }
            }

            SectionHeader(label = "Session", modifier = Modifier.padding(top = OrtSpacing.md))
            // R-145 (round 4, System validator): `Overs` gains the board's own `Log` link — this
            // row is the one place the session's overs are summarised, so it is also where a reader
            // reaches for the full list.
            KeyValueRow(
                key = "Overs",
                value = "${state.overCount}",
                subLine = "${state.rejectedCount} rejected · ${state.failedCount} failed",
                trailingMarker = { TextAction(text = "Log", onClick = onOpenLog) },
            )
            KeyValueRow(key = "Stations", value = "${state.stationCount}")
            KeyValueRow(key = "Frequencies", value = state.frequencyLabels.joinToString(" · ").ifEmpty { "none" })
            // E2-G03 (DG04, FR-CAP-13, amended 2026-09-10): Mode leads the v7 fact rows, per
            // `Session.dc.html`'s own order — Mode, Input, Rig link.
            KeyValueRow(key = "Mode", value = state.modeLabel, modifier = Modifier.testTag("session-detail-mode"))
            KeyValueRow(key = "Input", value = state.inputLabel, modifier = Modifier.testTag("session-detail-input"))
            KeyValueRow(
                key = "Rig link",
                value = state.rigLinkLabel,
                modifier = Modifier.testTag("session-detail-rig-link"),
            )
            KeyValueRow(key = "Tier", value = state.tierLabel)
            KeyValueRow(key = "Audio", value = "${state.audioSizeLabel} retained · lossless")
            // R-145: `Models.dc.html`'s row names the exact ASR/VAD/lexicon versions a session ran
            // with — real per-transcript data (`TranscriptEntity.modelId`), but reading it needs
            // `TranscriptDao`, which touches the `transcript_fts` virtual table Robolectric's
            // bundled SQLite has no fts5 module for (confirmed the hard way in this package's own
            // round-3 test notes) — querying it here would make every `SessionDetailScreen` test
            // environment-dependent. The row still renders, honestly, rather than being silently
            // dropped.
            KeyValueRow(key = "Models", value = "not tracked per session in this build")

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.lg, bottom = OrtSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
            ) {
                SessionActionChip(text = "Digest", onClick = onOpenDigest, modifier = Modifier.weight(1f))
                SessionActionChip(text = "Log", onClick = onOpenLog, modifier = Modifier.weight(1f))
                // R-146/R-145: no exporter exists in :app/:pipeline/:net — disabled with the same
                // honest reason `Digest`'s own `Export this night` gives, not a fake success.
                SessionActionChip(text = "Export", onClick = {}, enabled = false, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** `Session.dc.html`'s equal-weight `bg/chip`-filled action row (R-145: was outlined
 * [SecondaryButton]s, not the board's filled chip style) — local to this package for the same
 * reason [SettingsNavRow] is local to `ui/settings` (no shared component of this exact shape). */
@Composable
private fun SessionActionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .background(OrtColors.bgChip, androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = OrtType.control,
            color = if (enabled) OrtColors.textHigh else OrtColors.textDisabled,
        )
    }
}
