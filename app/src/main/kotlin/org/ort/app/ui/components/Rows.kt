package org.ort.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import org.ort.core.Attribution
import org.ort.core.AttributionState

/**
 * R-023 (ui-conformance-plan WP2): the row family from guide §6.5 and `Rows.dc.html` — the
 * densest part of the product (P7). Time and frequency are fixed-width columns so they align
 * down the whole log at the guide's own text scale; the station block grows; signal is
 * right-aligned mono.
 *
 * R-152: every column built from these constants is applied as a `widthIn(min = …)` floor, never
 * a `width(…)` ceiling — at a large font scale the guide's own column widths are too narrow for
 * the scaled text they hold, and a hard `width()` lets that overflow run straight into whatever
 * sits to its right with no visible gap (`Session`/`Capture-Status`'s "Stations5"). A floor keeps
 * every row's columns aligned at the guide's scale (1.0) — where content is always narrower than
 * the floor — and lets a column grow past it, never collide, at 2.0.
 */

/** Time column floor — 52dp at the guide's own scale, so every row's time aligns down the whole
 * log there (guide §6.5/§5); grows past 52dp rather than colliding with what follows it at a
 * larger font scale. */
public val LOG_TIME_COLUMN: androidx.compose.ui.unit.Dp = 52.dp

/** Frequency column floor — 56dp at the guide's own scale. */
public val LOG_FREQ_COLUMN: androidx.compose.ui.unit.Dp = 56.dp

private val SIGNAL_COLUMN = 24.dp

// ---------------------------------------------------------------------------------------------
// Section / header rows.
// ---------------------------------------------------------------------------------------------

/** guide: 11sp uppercase section label + an optional trailing [TextAction] (`Main.dc.html`'s
 * "STATIONS HEARD · All 19"). */
@Composable
public fun SectionHeader(
    label: String,
    modifier: Modifier = Modifier,
    trailingActionLabel: String? = null,
    onTrailingAction: (() -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label.uppercase(),
            style = OrtType.sectionLabel,
            color = OrtColors.textFaint,
            modifier = Modifier.weight(1f),
        )
        if (trailingActionLabel != null && onTrailingAction != null) {
            TextAction(text = trailingActionLabel, onClick = onTrailingAction)
        }
    }
}

/** `Detail.dc.html`'s top row: a 20dp chevron + the parent destination's label in `accent/green`,
 * 44dp target, with an optional kebab slot. */
@Composable
public fun DrillInHeader(
    parentLabel: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onKebab: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxWidth().heightIn(min = 44.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = OrtIcons.back,
                contentDescription = "Back to $parentLabel",
                tint = OrtColors.accentGreen,
                modifier = Modifier
                    .size(20.dp)
                    .clickable(role = Role.Button, onClickLabel = "Back to $parentLabel", onClick = onBack),
            )
            Text(text = parentLabel, style = OrtType.bodyProse, color = OrtColors.accentGreen)
            Spacer(modifier = Modifier.weight(1f))
            if (onKebab != null) {
                Icon(
                    imageVector = OrtIcons.more,
                    contentDescription = "More",
                    tint = OrtColors.textDim,
                    modifier = Modifier.size(19.dp)
                        .clickable(role = Role.Button, onClickLabel = "More", onClick = onKebab),
                )
            }
        }
    }
}

/** `Main.dc.html`'s top row: drawer icon, an optional live dot + mono elapsed, an optional search
 * icon. No title text — the screen title renders below it, separately. */
@Composable
public fun ScreenHeader(
    onDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    liveElapsedLabel: String? = null,
    onSearch: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxWidth().heightIn(min = 44.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = OrtIcons.drawer,
                contentDescription = "Open navigation",
                tint = OrtColors.textIcon,
                modifier = Modifier.size(21.dp)
                    .clickable(role = Role.Button, onClickLabel = "Open navigation", onClick = onDrawer),
            )
            Spacer(modifier = Modifier.weight(1f))
            if (liveElapsedLabel != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(modifier = Modifier.size(7.dp).background(OrtColors.accentGreen, CircleShape))
                    Text(text = liveElapsedLabel, style = OrtType.timeFreq, color = OrtColors.textIconDim)
                }
            }
            if (onSearch != null) {
                Icon(
                    imageVector = OrtIcons.search,
                    contentDescription = "Search",
                    tint = OrtColors.textDim,
                    modifier = Modifier.size(19.dp)
                        .clickable(role = Role.Button, onClickLabel = "Search", onClick = onSearch),
                )
            }
        }
    }
}

/** `Rows.dc.html`'s column-header row: fixed time/freq columns matching every [LogRow] below it. */
@Composable
public fun ColumnHeaderRow(
    modifier: Modifier = Modifier,
    stationLabel: String = "station",
    signalLabel: String = "sig",
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = OrtSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "time".uppercase(),
            style = OrtType.columnHeader,
            color = OrtColors.textDisabled,
            modifier = Modifier.widthIn(min = LOG_TIME_COLUMN),
        )
        Text(
            text = "freq".uppercase(),
            style = OrtType.columnHeader,
            color = OrtColors.textDisabled,
            modifier = Modifier.widthIn(min = LOG_FREQ_COLUMN),
        )
        Text(
            text = stationLabel.uppercase(),
            style = OrtType.columnHeader,
            color = OrtColors.textDisabled,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = signalLabel.uppercase(),
            style = OrtType.columnHeader,
            color = OrtColors.textDisabled,
            modifier = Modifier.widthIn(min = SIGNAL_COLUMN),
        )
    }
}

/** `Capture-Status.dc.html`'s key/value row: a key column sized to its content (a 96dp floor,
 * per the guide, but never a fixed ceiling — R-152: at large font scales a fixed-width column
 * cannot grow, so a long key runs directly into the value with no gap between them, e.g.
 * "Stations5"), a value + optional sub-line, and an optional trailing marker slot. The row's own
 * [OrtSpacing.sm] gap between columns is the floor that keeps key and value apart even when the
 * key's intrinsic width already reaches the value column's edge. */
@Composable
public fun KeyValueRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    subLine: String? = null,
    trailingMarker: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = OrtSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
    ) {
        Text(
            text = key,
            style = OrtType.control,
            color = OrtColors.textDim,
            modifier = Modifier.widthIn(min = 96.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = value, style = OrtType.control, color = OrtColors.textHigh)
            subLine?.let { Text(text = it, style = OrtType.subLine, color = OrtColors.textDim) }
        }
        trailingMarker?.invoke()
    }
}

/** [NavRow]'s tone — `NotBuilt` is a destination the guide describes but no module has shipped
 * yet (`Settings-Rig.dc.html`'s rig row before a rig module exists, for one): the row still
 * navigates (there is something to show — an honest "not built yet" state, constitution I), it
 * just reads visibly dimmer than a working destination so the operator isn't surprised by what
 * they find. `Neutral` (the default) is every ordinary destination row. */
public enum class NavRowTone { Neutral, NotBuilt }

/**
 * R-131 (`Settings.dc.html`/`Improve.dc.html`/`Sessions.dc.html`'s row family): an 18dp leading
 * [icon] (optional — a few destinations, like a bare "Improve" list entry, have none),
 * [rowTitle], an optional [subLine] status that may wrap onto a second line rather than truncate
 * (a real status sentence outranks a single line, per the guide's own multi-line rows elsewhere),
 * an optional [trailing] slot for a value or a [Badge] (mono where it is a value, per
 * `Controls.dc.html` — not this component's to impose a style on, so it is a free composable
 * slot),
 * and an always-present trailing [OrtIcons.chevron] — every row here goes somewhere, so the
 * affordance is not optional the way [icon]/[subLine]/[trailing] are.
 *
 * A real >=44dp target, `Role.Button`, one merged content description ("<title>. <subLine>",
 * or bare `rowTitle` when there is no sub-line — never a dangling ". "), and `bg/pressed` while
 * pressed (the [TextAction]/guide §6.7 pattern, not a ripple).
 */
@Composable
public fun NavRow(
    rowTitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subLine: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    tone: NavRowTone = NavRowTone.Neutral,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val titleColor = if (tone == NavRowTone.NotBuilt) OrtColors.textFaint else OrtColors.textHigh
    val iconTint = if (tone == NavRowTone.NotBuilt) OrtColors.textFaint else OrtColors.textDim
    val description = subLine?.let { "$rowTitle. $it" } ?: rowTitle

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(if (pressed) OrtColors.bgPressed else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
    ) {
        icon?.let {
            Icon(imageVector = it, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = rowTitle, style = OrtType.control, color = titleColor)
            subLine?.let {
                Text(
                    text = it,
                    style = OrtType.chip,
                    color = OrtColors.textDim,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        trailing?.invoke()
        Icon(
            imageVector = OrtIcons.chevron,
            contentDescription = null,
            tint = OrtColors.textDisabled,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** `Detail.dc.html`'s bottom two-button bar. */
@Composable
public fun ActionBar(
    secondaryLabel: String,
    onSecondary: () -> Unit,
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        ActionBarButton(
            text = secondaryLabel,
            bg = OrtColors.lineSection,
            fg = OrtColors.textHigh,
            weight = FontWeight.Medium,
            onClick = onSecondary,
            modifier = Modifier.weight(1f),
        )
        ActionBarButton(
            text = primaryLabel,
            bg = OrtColors.accentGreen,
            fg = OrtColors.accentOnGreen,
            weight = FontWeight.SemiBold,
            onClick = onPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ActionBarButton(
    text: String,
    bg: Color,
    fg: Color,
    weight: FontWeight,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .background(bg, RoundedCornerShape(9.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = OrtType.control.copy(fontWeight = weight), color = fg)
    }
}

/** guide §6.18: one figure in a `bg/card` card, for band readouts, digest numbers, diagnostics. */
@Composable
public fun Tile(figure: String, caption: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(OrtColors.bgCard, RoundedCornerShape(9.dp))
            .padding(vertical = OrtSpacing.md, horizontal = OrtSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = figure, style = OrtType.figure, color = OrtColors.textHigh)
        Text(
            text = caption,
            style = OrtType.subLine,
            color = OrtColors.textFaint,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// The log row family.
// ---------------------------------------------------------------------------------------------

/** A marker-slot occupant that is not an attribution (guide §6.13) — a streaming Pass A partial
 * ([HEARING]) or a resolved transcript still waiting on attribution ([RESOLVING]). Neither ever
 * coexists with a callsign. */
public enum class LogRowPartial { HEARING, RESOLVING }

/** The three badges a log row can carry beyond its attribution state (guide §6.14). */
public enum class LogRowBadge { NEW, CORRECTED, REVISED }

/**
 * One `LogRow`'s render-ready state. Exactly one of [partial]/[attribution] is non-null:
 * [partial] for a Pass A row nothing has been resolved for yet, [attribution] for every other
 * row (the four closed states, reused directly from `:core` rather than re-derived — an
 * attribution without its state is a bug at the data layer, and that stays true here).
 */
public data class LogRowViewState(
    val id: String,
    val timeLabel: String,
    val frequencyLabel: String,
    val transcript: String,
    val partial: LogRowPartial? = null,
    val attribution: Attribution? = null,
    val alternate: String? = null,
    val signalLabel: String? = null,
    val badge: LogRowBadge? = null,
    /** R-065: character ranges of [transcript] to render in `highlightGreen`, per
     * `Search-Results.dc.html`'s matched-word treatment (`.hit`) — a search match, not a state,
     * so it is additive and never the only way a row communicates anything. Empty by default so
     * every existing caller is unaffected. Out-of-bounds/reversed ranges are dropped rather than
     * crashing (constitution: never fabricate, never fail loudly on bad input from a caller). */
    val highlightRanges: List<IntRange> = emptyList(),
)

/** guide §6.5/`Rows.dc.html`: the densest row in the product. */
@Composable
public fun LogRow(state: LogRowViewState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val rowBackground = if (state.attribution?.state == AttributionState.AMBIGUOUS) {
        OrtColors.bgRowAmbiguous
    } else {
        Color.Transparent
    }
    // A Box carries the 44dp floor and the click target; the Row inside is free to lay its
    // columns out without a trailing `padding()` modifier fighting that outer minimum (an inner
    // Row with its own `heightIn` + `padding` chain measured short on some content combinations —
    // R-024/FR-A11Y-2's 44dp floor must hold for every row, not just the ones with two full lines).
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(rowBackground)
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = state.timeLabel,
                style = OrtType.timeFreq,
                color = OrtColors.textTime,
                modifier = Modifier.widthIn(min = LOG_TIME_COLUMN),
            )
            Text(
                text = state.frequencyLabel,
                style = OrtType.timeFreq,
                color = OrtColors.textTime,
                modifier = Modifier.widthIn(min = LOG_FREQ_COLUMN),
            )
            Column(modifier = Modifier.weight(1f)) {
                LogRowMarkerLine(state = state)
                Text(
                    text = highlightedTranscript(state.transcript, state.highlightRanges),
                    style = OrtType.transcript,
                    color = if (state.partial != null) OrtColors.textTime else OrtColors.textSecondary,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            state.signalLabel?.let {
                Text(
                    text = it,
                    style = OrtType.signal,
                    color = OrtColors.textLow,
                    modifier = Modifier.widthIn(min = SIGNAL_COLUMN),
                )
            }
        }
    }
}

/** R-065: [transcript] with [ranges] painted in `highlightGreen`/`textHigh`
 * (`Search-Results.dc.html`'s `.hit` span) — out-of-bounds and empty/reversed ranges are dropped
 * silently rather than crashing a row over a caller's off-by-one. */
private fun highlightedTranscript(transcript: String, ranges: List<IntRange>): AnnotatedString {
    if (ranges.isEmpty()) return AnnotatedString(transcript)
    val spans = ranges.mapNotNull { range ->
        val start = range.first.coerceIn(0, transcript.length)
        val end = (range.last + 1).coerceIn(0, transcript.length)
        if (end <= start) {
            null
        } else {
            AnnotatedString.Range(
                SpanStyle(background = OrtColors.highlightGreen, color = OrtColors.textHigh),
                start,
                end,
            )
        }
    }
    return AnnotatedString(text = transcript, spanStyles = spans)
}

@Composable
private fun LogRowMarkerLine(state: LogRowViewState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.xs),
    ) {
        when (state.partial) {
            LogRowPartial.HEARING -> {
                HearingMeter()
                Text(
                    text = "hearing…",
                    style = OrtType.subLine.copy(fontStyle = FontStyle.Italic),
                    color = OrtColors.textDim,
                )
            }

            LogRowPartial.RESOLVING -> {
                InProgressRing(size = 9.dp, color = OrtColors.textSignal)
                Text(
                    text = "resolving…",
                    style = OrtType.subLine.copy(fontStyle = FontStyle.Italic),
                    color = OrtColors.textDim,
                )
            }

            null -> state.attribution?.let { attribution ->
                AttributionRow(
                    attribution = attribution,
                    alternate = state.alternate,
                )
            }
        }
        state.badge?.let { badge ->
            val (label, kind) = when (badge) {
                LogRowBadge.NEW -> "new" to BadgeKind.NEW
                LogRowBadge.CORRECTED -> "corrected" to BadgeKind.CORRECTED
                LogRowBadge.REVISED -> "revised" to BadgeKind.REVISED
            }
            Badge(text = label, kind = kind)
        }
    }
}

@Composable
private fun HearingMeter(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.height(9.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(4.dp, 9.dp, 6.dp).forEach { barHeight ->
            Box(modifier = Modifier.width(2.dp).height(barHeight).background(OrtColors.meterIdle))
        }
    }
}

/** `Rows.dc.html`'s group header: instrument density, `bg/group` ground, an icon + a "QSO · 4
 * overs · 2 stations" label. Tapping opens the thread when [onClick] is given. */
@Composable
public fun LogGroupHeader(label: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(OrtColors.bgGroup)
            .then(
                if (onClick != null) {
                    Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = OrtIcons.threads,
                contentDescription = null,
                tint = OrtColors.textTime,
                modifier = Modifier.size(11.dp),
            )
            Text(text = label, style = OrtType.signal.copy(fontWeight = FontWeight.Medium), color = OrtColors.textTime)
        }
    }
}

/** `Rows.dc.html`'s gap row: the app was not listening, and says so with the reason — never
 * conflated with a quiet band (FR-UI-12, FR-RUN-12). */
@Composable
public fun GapRow(timeLabel: String, label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(OrtColors.bgRowGap)
            .heightIn(min = 44.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$timeLabel, $label" },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = timeLabel,
                style = OrtType.signal,
                color = OrtColors.accentGap,
                modifier = Modifier.widthIn(min = LOG_TIME_COLUMN),
            )
            Icon(
                imageVector = OrtIcons.gapWarn,
                contentDescription = null,
                tint = OrtColors.accentGap,
                modifier = Modifier.size(13.dp),
            )
            Text(text = label, style = OrtType.subLine, color = OrtColors.accentAmberDim)
        }
    }
}

/** `Rows.dc.html`'s rejected row: dimmed but present, with its reason. Its audio is retained and
 * it remains openable — nothing is deleted quietly (constitution III). */
@Composable
public fun RejectedRow(
    timeLabel: String,
    frequencyLabel: String,
    reason: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    // R-043 (`Log-Rejected.dc.html`'s `.why` line): the model/segmenter's own reason in prose,
    // e.g. "0.4 s of noise after the carrier dropped." Optional and additive — a row with no
    // `why` renders exactly as before.
    why: String? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .then(
                if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier,
            )
            .alpha(0.45f)
            .semantics(mergeDescendants = true) {
                contentDescription = if (why != null) {
                    "$timeLabel, $frequencyLabel, rejected, $reason, $why"
                } else {
                    "$timeLabel, $frequencyLabel, rejected, $reason"
                }
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = timeLabel,
                style = OrtType.timeFreq,
                color = OrtColors.textTime,
                modifier = Modifier.widthIn(min = LOG_TIME_COLUMN),
            )
            Text(
                text = frequencyLabel,
                style = OrtType.timeFreq,
                color = OrtColors.textTime,
                modifier = Modifier.widthIn(min = LOG_FREQ_COLUMN),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "rejected · $reason".uppercase(),
                    style = OrtType.columnHeader,
                    color = OrtColors.textFaint,
                )
                // guide (`.why`): 12px sans, text/dim — OrtType.chip is the guide's nearest named
                // 12sp non-mono row.
                why?.let {
                    Text(
                        text = it,
                        style = OrtType.chip,
                        color = OrtColors.textDim,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// NotificationCard — Capture-Notification.dc.html's collapsed/expanded persistent notification.
// ---------------------------------------------------------------------------------------------

/** One `NotificationCard` expanded key/value row (`Capture-Notification.dc.html`'s "Last over",
 * "Input", "Storage" rows). */
public data class NotificationCardRow(val key: String, val value: String)

/**
 * `Capture-Notification.dc.html`'s one persistent notification, drawn to guide §6.19: a 34dp
 * icon disc, title + mono elapsed + count on one line, a second line (amber when [degraded]),
 * and — only when supplied — the expanded key/value rows and up to two text actions. Collapsed
 * (the default: no [expandedRows], no actions) is what WP9's setup preview and WP11b's failure
 * screens need; the same composable also renders the expanded state so there is never a second
 * notification, just this one with more on it (the guide's own rule).
 */
@Suppress("LongParameterList") // every parameter is an independent, optional notification field.
@Composable
public fun NotificationCard(
    icon: ImageVector,
    title: String,
    elapsedLabel: String,
    countLabel: String,
    secondLine: String,
    modifier: Modifier = Modifier,
    degraded: Boolean = false,
    expandedRows: List<NotificationCardRow> = emptyList(),
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .background(OrtColors.bgNotification, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 13.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title, $elapsedLabel, $countLabel, $secondLine"
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier.size(34.dp).background(OrtColors.bgPressed, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (degraded) OrtColors.accentAmber else OrtColors.accentGreen,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = title,
                        style = OrtType.subtitle.copy(fontWeight = FontWeight.Medium),
                        color = OrtColors.textHigh,
                    )
                    // guide: 12.5px mono elapsed — OrtType.timeFreq (12sp mono) is the nearest named row.
                    Text(text = elapsedLabel, style = OrtType.timeFreq, color = OrtColors.accentGreenDim)
                    Text(text = "· $countLabel", style = OrtType.cardBody, color = OrtColors.textDim)
                }
                Text(
                    text = secondLine,
                    style = OrtType.cardBody,
                    color = if (degraded) OrtColors.accentAmberDim else OrtColors.textMuted,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (expandedRows.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(start = 46.dp, top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                expandedRows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = row.key,
                            style = OrtType.cardBody,
                            color = OrtColors.textFaint,
                            // R-152: a floor, not a ceiling — see LOG_TIME_COLUMN's doc.
                            modifier = Modifier.widthIn(min = 62.dp),
                        )
                        Text(text = row.value, style = OrtType.cardBody, color = OrtColors.textPrior)
                    }
                }
            }
        }
        if (primaryActionLabel != null || secondaryActionLabel != null) {
            Row(
                modifier = Modifier.padding(start = 46.dp, top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                if (primaryActionLabel != null) {
                    TextAction(text = primaryActionLabel, onClick = onPrimaryAction ?: {})
                }
                if (secondaryActionLabel != null) {
                    TextAction(text = secondaryActionLabel, onClick = onSecondaryAction ?: {})
                }
            }
        }
    }
}
