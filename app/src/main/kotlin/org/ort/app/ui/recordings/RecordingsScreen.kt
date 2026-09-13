package org.ort.app.ui.recordings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.EmptyState
import org.ort.app.ui.components.FilterChip
import org.ort.app.ui.components.FilterChipRow
import org.ort.app.ui.components.LOADING_STATE_TEST_TAG
import org.ort.app.ui.components.ProgressBar
import org.ort.app.ui.components.ScreenHeader
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.improve.Plurals
import org.ort.app.ui.navigation.toGigabyteLabel
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * `Recordings.dc.html` (RC01, design-intent row RC01): every session newest-first, both storage
 * budgets stated as the policies they are, and the filter chips. Absorbs `Sessions.dc.html` (DG03)
 * as the home for sessions (IA-1) — see `Drawer.kt`'s own doc comment for how the drawer row itself
 * is retired while `SessionsContent`'s own DG04/Digest sub-screens stay reachable through the
 * `Settings-Storage` "Review" link, unchanged.
 *
 * R-1051 (register: initial state is not empty state): [state] is nullable — `null` renders
 * [RecordingsLoading], never [EmptyState], so a cold start never claims "no recordings" before the
 * first real query has returned.
 */
@Composable
public fun RecordingsScreen(
    state: RecordingsViewState?,
    onDrawer: () -> Unit,
    onSelectFilter: (RecordingsFilter) -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenOverAudioBudget: () -> Unit,
    onTurnOffArchive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(onDrawer = onDrawer)
        if (state == null) {
            RecordingsLoading()
            return@Column
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = OrtSpacing.lg),
        ) {
            Text(
                text = "Recordings",
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

            RecordingsBudgetsCard(
                budgets = state.budgets,
                onOpenOverAudioBudget = onOpenOverAudioBudget,
                onTurnOffArchive = onTurnOffArchive,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            FilterChipRow(modifier = Modifier.fillMaxWidth().testTag(RECORDINGS_FILTER_ROW_TEST_TAG)) {
                state.filters.forEach { chip ->
                    FilterChip(
                        label = chip.count?.let { "${chip.label} $it" } ?: chip.label,
                        selected = chip.filter == state.selectedFilter,
                        onClick = { onSelectFilter(chip.filter) },
                    )
                }
            }

            if (state.sessions.isEmpty()) {
                EmptyState(
                    message = "No recordings match this filter.",
                    subMessage = "A night's capture appears here once it starts.",
                    modifier = Modifier.padding(top = OrtSpacing.md),
                )
            } else {
                RecordingsSessionList(sessions = state.sessions, onOpenSession = onOpenSession)
            }
        }
    }
}

@Composable
private fun RecordingsLoading(modifier: Modifier = Modifier) {
    // Register R-1022 (WPDIGINIT, minimal addition — this screen's own `RECORDINGS_LOADING_TEST_TAG`
    // is unchanged and still the tag this package's own tests key on): also carries the shared
    // `LOADING_STATE_TEST_TAG` on the outer container, a second, independent node from the `Text`
    // below, so `org.ort.app.debug.tour.TourAccessibilityScroll.snapshot`'s structural readiness
    // scan (which only knows the shared tag) waits for this screen's real data too.
    Column(modifier = modifier.padding(OrtSpacing.lg).testTag(LOADING_STATE_TEST_TAG)) {
        Text(
            text = "Loading…",
            style = OrtType.subtitle,
            color = OrtColors.textDim,
            modifier = Modifier.semantics { contentDescription = "Loading recordings" }
                .testTag(RECORDINGS_LOADING_TEST_TAG),
        )
    }
}

/** R-144-style month grouping ("This week" for the current week, else the month name) — mirrors
 * `SessionsScreen`'s own forward pass over an already newest-first list. */
@Composable
private fun RecordingsSessionList(
    sessions: List<RecordingsSessionRowViewState>,
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        var previousMonth: YearMonth? = null
        sessions.forEach { session ->
            val started = Instant.ofEpochMilli(session.startedAtUtc).atZone(ZoneId.of("UTC"))
            val month = YearMonth.from(started)
            if (month != previousMonth) {
                SectionHeader(
                    label = month.month.getDisplayName(TextStyle.FULL, Locale.US),
                    modifier = Modifier.padding(top = if (previousMonth == null) 0.dp else OrtSpacing.md),
                )
                previousMonth = month
            }
            RecordingsSessionRow(session = session, onClick = { onOpenSession(session.id) })
        }
    }
}

/** `Recordings.dc.html`'s own row sub-line: "N overs · N stations[ · N gap(s)]" — three real,
 * distinct facts ([RecordingSessionSummary][org.ort.pipeline.archive.RecordingSessionSummary]'s
 * own `stationCount`/`gapCount`, widened for exactly this), never the over count alone. The gap
 * clause is omitted, not shown as "0 gaps", when this session genuinely had none (constitution I:
 * an absent fact is stated as absent, not as a zero literal the artboard never draws either —
 * compare "41 overs · 9 stations · 1 gap" against "33 overs · 7 stations" on the board itself). */
private fun sessionCountsLabel(session: RecordingsSessionRowViewState): String = buildString {
    append(Plurals.count(session.overCount, "over"))
    append(" · ")
    append(Plurals.count(session.stationCount, "station"))
    if (session.gapCount > 0) {
        append(" · ")
        append(Plurals.count(session.gapCount, "gap"))
    }
}

private fun sessionRowDescription(session: RecordingsSessionRowViewState): String = buildString {
    append(session.label)
    append(". ")
    append(session.timeRangeLabel)
    append(" · ")
    append(session.durationLabel)
    append(" · ")
    append(sessionCountsLabel(session))
    session.badges.forEach { append(" · ${it.label}") }
}

@Composable
private fun RecordingsSessionRow(
    session: RecordingsSessionRowViewState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = OrtSpacing.sm)
            .semantics(mergeDescendants = true) { contentDescription = sessionRowDescription(session) }
            .testTag("recordings-session-row-${session.id}"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = session.label, style = OrtType.rowTitle, color = OrtColors.textHigh)
            Text(
                text = "${session.timeRangeLabel} · ${session.durationLabel}",
                style = OrtType.subLine,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = sessionCountsLabel(session),
                style = OrtType.subLine,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            session.badges.forEach { badge ->
                RecordingBadge(badge = badge, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** `.badge` (`Recordings.dc.html`): a small mono pill, one background/foreground pair per
 * [RecordingBadgeKind] — [RecordingBadgeKind.ARCHIVE_REMOVED]/[RecordingBadgeKind.OVER_AUDIO_REMOVED]
 * share the board's dashed-border `b-gone` treatment (a plain border here, Compose draws no dashed
 * stroke helper this file needs to add for one badge) so a removed-audio badge always reads visually
 * distinct from a live/kept one, never merely by its text. */
@Composable
private fun RecordingBadge(badge: RecordingBadgeViewState, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(4.dp)
    val (background, foreground, borderColor) = when (badge.kind) {
        RecordingBadgeKind.CAPTURING, RecordingBadgeKind.LABELLED ->
            Triple(OrtColors.accentGreen.copy(alpha = 0.14f), OrtColors.accentGreen, null)
        RecordingBadgeKind.FAILED ->
            Triple(OrtColors.accentAmber.copy(alpha = 0.14f), OrtColors.accentAmber, null)
        RecordingBadgeKind.ARCHIVE_KEPT ->
            Triple(androidx.compose.ui.graphics.Color.Transparent, OrtColors.textDim, OrtColors.lineChip)
        RecordingBadgeKind.ARCHIVE_REMOVED, RecordingBadgeKind.OVER_AUDIO_REMOVED ->
            Triple(androidx.compose.ui.graphics.Color.Transparent, OrtColors.textFaint, OrtColors.lineChip)
    }
    Row(
        modifier = modifier
            .background(background, shape)
            .then(
                if (borderColor != null) {
                    Modifier.border(1.dp, borderColor, shape)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(text = badge.label, style = OrtType.badge, color = foreground)
    }
}

/**
 * Both budgets, one card (`Recordings.dc.html`): over audio warns and is never deleted without the
 * operator (D40/R-1037); the archive states on/off, usage, the monthly rate and offers `Turn off`
 * beside the statement (D39/R-1036, FR-STO-3f).
 */
@Composable
private fun RecordingsBudgetsCard(
    budgets: RecordingsBudgetsViewState,
    onOpenOverAudioBudget: () -> Unit,
    onTurnOffArchive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OrtColors.bgCard, RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "Storage settings", role = Role.Button, onClick = onOpenOverAudioBudget)
            .padding(horizontal = OrtSpacing.md, vertical = OrtSpacing.sm)
            .testTag(RECORDINGS_BUDGETS_CARD_TEST_TAG),
    ) {
        OverAudioBudgetRow(budgets.overAudio)
        ArchiveBudgetRow(budgets.archive, onTurnOffArchive)
    }
}

@Composable
private fun OverAudioBudgetRow(state: OverAudioCardViewState, modifier: Modifier = Modifier) {
    val usedLabel = state.usedBytes.toGigabyteLabel()
    val budgetLabel = state.budgetGb?.let { "$it GB" }
    Column(modifier = modifier.testTag(RECORDINGS_OVER_AUDIO_ROW_TEST_TAG)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                text = "Over audio",
                style = OrtType.control,
                color = OrtColors.textHigh,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = budgetLabel?.let { "$usedLabel of $it" } ?: "$usedLabel · no budget set",
                style = OrtType.subLine,
                color = OrtColors.textDim,
            )
        }
        if (state.fractionUsed != null) {
            ProgressBar(progress = state.fractionUsed, modifier = Modifier.padding(top = 5.dp))
        }
        Text(
            // AC-160: the warning shows without a tap the instant the budget is exceeded — the
            // sub-line itself carries it, not a separate banner someone has to notice.
            text = if (state.exceeded) {
                "Over budget · never deleted without you"
            } else {
                "warns when full · never deleted without you"
            },
            style = OrtType.subLine,
            color = if (state.exceeded) OrtColors.accentAmber else OrtColors.textDim,
            modifier = Modifier.padding(top = 3.dp).testTag(RECORDINGS_OVER_AUDIO_WARNING_TEST_TAG),
        )
    }
}

@Composable
private fun ArchiveBudgetRow(state: ArchiveCardViewState, onTurnOff: () -> Unit, modifier: Modifier = Modifier) {
    val usedLabel = state.usedBytes.toGigabyteLabel()
    Column(modifier = modifier.padding(top = OrtSpacing.sm).testTag(RECORDINGS_ARCHIVE_ROW_TEST_TAG)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                text = "Raw archive",
                style = OrtType.control,
                color = OrtColors.textHigh,
                modifier = Modifier.weight(1f),
            )
            Text(text = "$usedLabel of ${state.budgetGb} GB", style = OrtType.subLine, color = OrtColors.textDim)
        }
        ProgressBar(progress = state.fractionUsed, modifier = Modifier.padding(top = 5.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = (if (state.enabled) "on" else "off") +
                    " · ${state.monthlyRateLabel} · oldest archive removed first, overs kept",
                style = OrtType.subLine,
                color = OrtColors.textDim,
                modifier = Modifier.weight(1f, fill = false),
            )
            TextAction(
                text = if (state.enabled) "Turn off" else "Turn on",
                onClick = onTurnOff,
                modifier = Modifier.testTag(RECORDINGS_ARCHIVE_TOGGLE_TEST_TAG),
            )
        }
    }
}

public const val RECORDINGS_LOADING_TEST_TAG: String = "recordings-loading"
public const val RECORDINGS_BUDGETS_CARD_TEST_TAG: String = "recordings-budgets-card"
public const val RECORDINGS_OVER_AUDIO_ROW_TEST_TAG: String = "recordings-over-audio-row"
public const val RECORDINGS_OVER_AUDIO_WARNING_TEST_TAG: String = "recordings-over-audio-warning"
public const val RECORDINGS_ARCHIVE_ROW_TEST_TAG: String = "recordings-archive-row"
public const val RECORDINGS_ARCHIVE_TOGGLE_TEST_TAG: String = "recordings-archive-toggle"
public const val RECORDINGS_FILTER_ROW_TEST_TAG: String = "recordings-filter-row"
