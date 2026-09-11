package org.ort.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
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
 *
 * R-205: R-152's floor was not enough by itself — found on an emulator, not just at a larger font
 * scale, that the guide's own 52dp/56dp widths are already too narrow for real content ("16:28:56",
 * "146.960") in [OrtType.timeFreq] even at the guide's own scale (1.0): a `Text` given room to
 * wrap onto a second line will do exactly that rather than ask its parent for more width, so
 * `widthIn(min = …)` alone never got the chance to grow the column — the wrap happened first.
 * [rememberTimeColumnWidth]/[rememberFreqColumnWidth] fix the root cause two ways: `maxLines = 1`
 * and `softWrap = false` on every time/frequency `Text` forbid the wrap outright, and the floor
 * itself is `LOG_TIME_COLUMN`/`LOG_FREQ_COLUMN` widened, if needed, to whatever
 * [rememberTextMeasurer] actually measures a representative value to need in [OrtType.timeFreq]
 * at the *real*, current density/font scale — correct against real font metrics on whatever host
 * renders it, not a guessed constant that can quietly drift out of date the way 52dp/56dp did.
 */

/** Time column floor — 52dp at the guide's own scale, so every row's time aligns down the whole
 * log there (guide §6.5/§5); grows past 52dp rather than colliding with what follows it at a
 * larger font scale. R-205: callers render at [rememberTimeColumnWidth] instead, which never
 * returns less than this constant. */
public val LOG_TIME_COLUMN: Dp = 52.dp

/** Frequency column floor — 56dp at the guide's own scale. R-205: callers render at
 * [rememberFreqColumnWidth] instead, which never returns less than this constant. */
public val LOG_FREQ_COLUMN: Dp = 56.dp

/** Signal column floor — 24dp at the guide's own scale. R-505: callers render at
 * [rememberSignalColumnWidth] instead, which never returns less than this constant — see that
 * function's own doc for why the constant alone is not enough past the guide's own scale. */
private val SIGNAL_COLUMN = 24.dp

/** R-505 (`search-corpus/Q03-results-park@2x.png`): the widest real shape a [LogRowViewState.
 * signalLabel]/[ColumnHeaderRow] signal label takes — `"S" + one digit`, [ReaderTransmissionViewStateMapper.
 * signalLabel]'s own `"S%.0f"` format applied to an S-meter reading (S0-S9). */
private const val SIGNAL_COLUMN_SAMPLE = "S9"

/** R-505: [LOG_TIME_COLUMN]/[LOG_FREQ_COLUMN]/[rememberCallsignColumnWidth] already measured their
 * own column's real, current width in [rememberMonoColumnWidth] rather than trusting a guessed
 * constant (R-205/R-373) — [SIGNAL_COLUMN] alone never got the same treatment, so [LogRow]'s own
 * one-line/stacked gate ([oneLineWidthFor]) kept comparing the row's real width against an
 * estimate that stayed fixed at 24dp regardless of font scale, while the signal `Text` beside it
 * (["S9"][SIGNAL_COLUMN_SAMPLE] in [OrtType.signal]) genuinely grew past 24dp at a larger scale.
 * The gate then wrongly kept choosing the one-line layout past the point the row actually had room
 * for it, squeezing the weighted callsign column under its own floor to make space — the callsign
 * (`maxLines = 1, softWrap = false`, no wrap opportunity) then overflowed its allocated width and
 * ran into the signal text beside it ("KE7QRS"/"KA7LWH" clipped by the "S7" figure, the register's
 * own repro). Measuring this column the same [rememberMonoColumnWidth] way the others already do
 * is the fix — the gate and the signal `Text`'s own width now agree, on whatever host renders
 * them, the way time/freq/callsign already did. */
@Composable
public fun rememberSignalColumnWidth(): Dp =
    rememberMonoColumnWidth(SIGNAL_COLUMN_SAMPLE, SIGNAL_COLUMN, style = OrtType.signal)

/** R-505: [LogRow]/[ColumnHeaderRow]'s shared one-line/stacked gate, pulled out as a plain function
 * — not inlined into either composable — so the decision itself (rather than a rendered pixel,
 * which this package's own [logRowTranscriptStyle]/`meterColorFor` doc comments already found this
 * host's Robolectric setup cannot reliably verify) is directly unit-testable: given the same four
 * column widths and gap, this always returns the same total, with no font metrics or composition
 * involved. Both callers must include [signalWidth] in the sum whenever a signal column is
 * possible for them, or the R-505 gap comes back for whichever column they forgot it for. */
internal fun oneLineWidthFor(timeWidth: Dp, freqWidth: Dp, callsignWidth: Dp, signalWidth: Dp, gap: Dp): Dp =
    timeWidth + gap + freqWidth + gap + callsignWidth + gap + signalWidth

/** R-205: HH:MM:SS, the widest real shape [LogRowViewState.timeLabel]/`GapRow`/`RejectedRow`'s
 * `timeLabel` ever take — every caller in this codebase uses this exact format, so measuring this
 * one representative value (rather than each row's own, different, digits) is what keeps every
 * row's time column the same width, aligned down the whole log. */
private const val TIME_COLUMN_SAMPLE = "16:28:56"

/** R-205: NNN.NNN MHz, the widest real shape a frequency label takes in this codebase. */
private const val FREQ_COLUMN_SAMPLE = "146.960"

/** R-205: [LOG_TIME_COLUMN] widened, if the real, current font metrics need it, to fit
 * [TIME_COLUMN_SAMPLE] on one line in [OrtType.timeFreq] — measured with [rememberTextMeasurer],
 * not guessed, so it is correct on this host's actual fonts rather than only the guide's. */
@Composable
public fun rememberTimeColumnWidth(): Dp = rememberMonoColumnWidth(TIME_COLUMN_SAMPLE, LOG_TIME_COLUMN)

/** R-205: [LOG_FREQ_COLUMN] widened, if needed, to fit [FREQ_COLUMN_SAMPLE] — see
 * [rememberTimeColumnWidth]. */
@Composable
public fun rememberFreqColumnWidth(): Dp = rememberMonoColumnWidth(FREQ_COLUMN_SAMPLE, LOG_FREQ_COLUMN)

/** R-373 (`overnight/L01-log-pass3@2x.png`, `search-corpus/Q03-results-2x-clean-pass4.png`): the
 * real defect [rememberTimeColumnWidth]/[rememberFreqColumnWidth] already fixed for the time/freq
 * columns, still open for [LogRow]'s weighted station/transcript column — nothing ever gave it a
 * floor, so at a large font scale it could be measured arbitrarily narrow, and a callsign (one
 * unbroken mono token, no word-break opportunity of its own) split character-by-character
 * ("KE7QRS" → "KE7QR"/"S") rather than wrapping as a whole word the way a transcript's real words
 * mostly can. [CALLSIGN_COLUMN_SAMPLE] is the widest realistic amateur callsign shape (prefix +
 * digit + suffix); [rememberCallsignColumnWidth] is that measured in [OrtType.callsignRow] at the
 * real, current density/font scale, the same [rememberTextMeasurer]-backed pattern as the time/
 * freq columns — one shared floor across every row, not each row's own (possibly shorter)
 * callsign, so the column stays aligned down the whole log the way time/freq already do. */
private const val CALLSIGN_COLUMN_SAMPLE = "KE7QRS"

/** R-560 (`overnight/L01-log@2x-end.png`): the widest realistic combined shape an AMBIGUOUS row's
 * marker line takes — [CALLSIGN_COLUMN_SAMPLE] beside its own AMBIGUOUS "or <alternate>" — measured
 * as its own separate string (`OrtType.subLine`, [AttributionRow]'s own style for it) rather than
 * folded into [CALLSIGN_COLUMN_SAMPLE]'s single mono measurement, since the two run in different
 * text styles in the real row. [rememberCallsignColumnWidth] sums both plus the real gap
 * ([AttributionRow]'s own `Spacer(OrtSpacing.xs)`) so the one-line gate reserves genuine room for
 * an alternate, not just the primary callsign alone — R-505's own fix for the signal column, same
 * reasoning. */
private const val ALTERNATE_COLUMN_SAMPLE = "or $CALLSIGN_COLUMN_SAMPLE"

/** R-560: the UNKNOWN state's own "unknown station" (`AttributionRow`'s own [OrtType.textAction],
 * italic) is prose, not a callsign — it has real word-break opportunities, unlike every other
 * branch this file's own R-373 fix already forbids wrapping for — so it is deliberately left
 * wrappable (`softWrap` stays this package's own default `true`, never set `false`). What still
 * broke it at a large font scale: nothing ever floored the column at the width its own *longest
 * single word* ("station") needs, so a column narrower than that forced a mid-word break as
 * Compose's own last resort ("unkno"/"wn station") — the same root cause R-373 already named for a
 * callsign, just without a word boundary to fall back to. Flooring the column at this word's own
 * width is what actually guarantees "wraps at a word boundary or not at all", never mid-word. */
private const val UNKNOWN_STATION_WIDEST_WORD = "station"

/** R-373/R-560: [CALLSIGN_COLUMN_SAMPLE] measured in [OrtType.callsignRow], [ALTERNATE_COLUMN_SAMPLE]
 * in [OrtType.subLine] (plus the real `Spacer(OrtSpacing.xs)` gap between them), and
 * [UNKNOWN_STATION_WIDEST_WORD] in [OrtType.textAction] — the widest of the three is what
 * [LogRow] applies as the station/transcript column's own `widthIn(min = …)` floor. */
@Composable
public fun rememberCallsignColumnWidth(): Dp {
    val callsignWidth = rememberMonoColumnWidth(CALLSIGN_COLUMN_SAMPLE, floor = 0.dp, style = OrtType.callsignRow)
    val alternateWidth = rememberMonoColumnWidth(ALTERNATE_COLUMN_SAMPLE, floor = 0.dp, style = OrtType.subLine)
    val unknownWordWidth =
        rememberMonoColumnWidth(UNKNOWN_STATION_WIDEST_WORD, floor = 0.dp, style = OrtType.textAction)
    val combinedAmbiguousWidth = callsignWidth + OrtSpacing.xs + alternateWidth
    return maxOf(callsignWidth, combinedAmbiguousWidth, unknownWordWidth)
}

@Composable
private fun rememberMonoColumnWidth(sample: String, floor: Dp, style: TextStyle = OrtType.timeFreq): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val measuredWidth = remember(density.density, density.fontScale, sample, style) {
        with(density) { measurer.measure(text = sample, style = style).size.width.toDp() }
    }
    return maxOf(floor, measuredWidth)
}

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
 * 44dp target, with an optional kebab slot.
 *
 * R-192: [kebabDescription] names what the kebab actually opens ("More" by default, unchanged for
 * every caller before this existed) — a screen whose kebab does something specific
 * (`Station-Detail.dc.html`'s "Station identity", for one) can say so instead of cloning this
 * whole header just to relabel one icon. [kebabTestTag] is `null` by default (no test tag at all,
 * matching every caller before this existed); a caller that needs to drive the kebab by tag rather
 * than by its content description can supply one. Both are ignored when [onKebab] is `null` — there
 * is no kebab to name. */
@Composable
public fun DrillInHeader(
    parentLabel: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onKebab: (() -> Unit)? = null,
    kebabDescription: String = "More",
    kebabTestTag: String? = null,
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
                    contentDescription = kebabDescription,
                    tint = OrtColors.textDim,
                    modifier = Modifier.size(19.dp)
                        .then(if (kebabTestTag != null) Modifier.testTag(kebabTestTag) else Modifier)
                        .clickable(role = Role.Button, onClickLabel = kebabDescription, onClick = onKebab),
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

/** `Rows.dc.html`'s column-header row: fixed time/freq columns matching every [LogRow] below it.
 * R-245 (`overnight/L01-log@2x.png`): every label here — including [stationLabel] on its flexible
 * `weight(1f)` column, which takes whatever width the fixed columns leave rather than being sized
 * to its own content — carries `maxLines = 1, softWrap = false`, so a short header word like
 * "STATION" is never itself broken mid-word ("STATIO"/"N") at a larger font scale; there is no
 * second line for a column *header* to wrap onto the way a data row's own content can.
 *
 * R-420 (`overnight/L01-log@2x.png`): stacks the same way [LogRow] does, and for the identical
 * reason — the SIG header/column ran off the right edge once TIME/FREQ/STATION's own real widths
 * left it no room. A [BoxWithConstraints] picks, from the row's own real, current width, the
 * existing one-line layout when it fits, or a stacked layout when it does not: STATION on its own
 * line, TIME/FREQ/SIG — together, never split from each other — on the line beneath it, mirroring
 * [LogRow]'s own marker-line/time-freq-signal split so a header always describes the row shape
 * beneath it. */
@Composable
public fun ColumnHeaderRow(
    modifier: Modifier = Modifier,
    stationLabel: String = "station",
    signalLabel: String = "sig",
) {
    val timeWidth = rememberTimeColumnWidth()
    val freqWidth = rememberFreqColumnWidth()
    val callsignWidth = rememberCallsignColumnWidth()
    val signalColumnWidth = rememberSignalColumnWidth()
    val gap = 10.dp
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = OrtSpacing.xs),
    ) {
        // R-505: the same shared gate [LogRow] uses, and the same real-measurement-not-a-fixed-
        // constant signal width — see [oneLineWidthFor]/[rememberSignalColumnWidth]'s own doc.
        val oneLineWidth = oneLineWidthFor(timeWidth, freqWidth, callsignWidth, signalColumnWidth, gap)
        if (maxWidth >= oneLineWidth) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                ColumnHeaderTimeText(Modifier.widthIn(min = timeWidth))
                ColumnHeaderFreqText(Modifier.widthIn(min = freqWidth))
                ColumnHeaderStationText(stationLabel, Modifier.weight(1f).widthIn(min = callsignWidth))
                ColumnHeaderSignalText(signalLabel, Modifier.widthIn(min = signalColumnWidth))
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                ColumnHeaderStationText(stationLabel, Modifier.fillMaxWidth())
                Row(
                    modifier = Modifier.padding(top = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    ColumnHeaderTimeText(Modifier.widthIn(min = timeWidth))
                    ColumnHeaderFreqText(Modifier.widthIn(min = freqWidth))
                    ColumnHeaderSignalText(signalLabel, Modifier.widthIn(min = signalColumnWidth))
                }
            }
        }
    }
}

@Composable
private fun ColumnHeaderTimeText(modifier: Modifier = Modifier) {
    Text(
        text = "time".uppercase(),
        style = OrtType.columnHeader,
        color = OrtColors.textDisabled,
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}

@Composable
private fun ColumnHeaderFreqText(modifier: Modifier = Modifier) {
    Text(
        text = "freq".uppercase(),
        style = OrtType.columnHeader,
        color = OrtColors.textDisabled,
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}

@Composable
private fun ColumnHeaderStationText(stationLabel: String, modifier: Modifier = Modifier) {
    Text(
        text = stationLabel.uppercase(),
        style = OrtType.columnHeader,
        color = OrtColors.textDisabled,
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}

/** R-505: its own floor now comes entirely from [modifier] (the caller's
 * [rememberSignalColumnWidth]) — see [LogRowSignalText]'s own doc for why. */
@Composable
private fun ColumnHeaderSignalText(signalLabel: String, modifier: Modifier = Modifier) {
    Text(
        text = signalLabel.uppercase(),
        style = OrtType.columnHeader,
        color = OrtColors.textDisabled,
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}

/** `Capture-Status.dc.html`'s key/value row: a key column sized to its content (a 96dp floor,
 * per the guide, but never a fixed ceiling — R-152: at large font scales a fixed-width column
 * cannot grow, so a long key runs directly into the value with no gap between them, e.g.
 * "Stations5"), a value + optional sub-line, and an optional trailing marker slot. The row's own
 * [OrtSpacing.sm] gap between columns is the floor that keeps key and value apart even when the
 * key's intrinsic width already reaches the value column's edge.
 *
 * R-381 (N04 `Capture-Status`'s Level row, dumped as `<node content-desc="" clickable="true"
 * focusable="true"><node content-desc="Level: … Open level meter" focusable="false"/></node>`):
 * before [onClick] existed, a caller that wanted the whole row tappable had no way to do that
 * *on this composable's own outer node* — wrapping this row in a caller-side `Modifier.clickable`
 * instead nests a second merge boundary inside the caller's own clickable node (this row's own
 * `semantics(mergeDescendants = true)`), which this package's whole "nested merge boundary"
 * history already found unreliable for carrying a name up to an *ancestor* on a real device (see
 * [org.ort.app.ui.components.TextAction]'s own doc comment for the fuller finding, now confirmed
 * to break the *immediate* clickable wrapper too, not only a second ancestor further out).
 * [onClick] (`null` default, every caller before this existed unaffected) puts the click and the
 * composed description on this row's *own* single node instead, the same fix already established
 * for [LogRow]/[LiveBar]: nothing for a caller to get wrong by wrapping externally. [onClickLabel]
 * (TalkBack's action hint, e.g. "Open level meter") is folded into the same composed description a
 * non-clickable row already builds, so the row still reads as one fact plus its one action, not two
 * unrelated stops. */
@Composable
public fun KeyValueRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    subLine: String? = null,
    trailingMarker: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    // R-265: a TalkBack traversal stop — without this, "Input"/"USB Audio Device"/"verified" are
    // three separate stops a screen-reader user has to swipe through individually instead of
    // hearing as the one fact row they visually are. `focusable()` (no `MutableInteractionSource`/
    // visual indication needed when [onClick] is null — nothing to show pressed) marks the merged
    // node as a real accessibility stop when this row is not clickable; when it is, `clickable()`
    // itself already carries that. `mergeDescendants = true` with an explicit `contentDescription`
    // is the same explicit-composition pattern this package now uses in `LogRow`/`RejectedRow`
    // rather than trusting merge behaviour alone.
    //
    // The outer `Box` (size/focus/semantics) wrapping an inner `Row` (padding/layout) is this
    // package's own established fix for a real, repeated Compose/Robolectric measurement bug: a
    // `heightIn(min = 44.dp)` followed later in the *same* modifier chain by more modifiers
    // (`padding`, and now `focusable`/`semantics` too) sometimes measures shorter than the
    // minimum — confirmed directly here (this row measured 36dp, exactly 44dp minus the 8dp
    // `padding(vertical = OrtSpacing.xs)` this chain also carries, the instant `focusable()`/
    // `semantics(...)` were appended after it) — the same defect `LogRow`/`GapRow`/`RejectedRow`/
    // `LogGroupHeader`/`DrillInHeader`/`ScreenHeader` already carry this exact structural fix for.
    val description = buildString {
        append(key)
        append(", ")
        append(value)
        subLine?.let {
            append(", ")
            append(it)
        }
        onClickLabel?.let {
            append(", ")
            append(it)
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .then(
                if (onClick != null) {
                    // R-381 (N04 Level row): a real device confirmed a plain, trailing
                    // `semantics(mergeDescendants = true) { ... }` does not reliably keep this
                    // node's own `contentDescription` on the *clickable* node itself once real
                    // child content (this row's own `Text`s) sits beneath it — see [TextAction]'s
                    // own doc comment for the full finding. `clearAndSetSemantics` is what this
                    // package now confirms actually works.
                    Modifier
                        .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
                        .clearAndSetSemantics {
                            contentDescription = description
                            // R-380 correction (WP2, gate-blocking) — see `LogRow`'s own doc
                            // comment above.
                            text = AnnotatedString(description)
                            role = Role.Button
                            onClick(label = onClickLabel) {
                                onClick()
                                true
                            }
                        }
                } else {
                    // The non-clickable, focusable-only case: R-265's own fix, already confirmed
                    // correct on a real device (V7's own pass-3 notes) — unchanged.
                    Modifier.focusable().semantics(mergeDescendants = true) { contentDescription = description }
                },
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = OrtSpacing.xs),
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
            // R-380 (WP2 next round): the same shape this file's own `LogRow`/`TextAction` were
            // already confirmed broken for and fixed on a real device — a plain, trailing
            // `semantics(mergeDescendants = true) { contentDescription = ... }` does not reliably
            // keep the description on the *clickable* node itself once real child content (this
            // row's own `Text`s) sits beneath it. `clearAndSetSemantics` is this package's own
            // confirmed-working fix.
            .clearAndSetSemantics {
                contentDescription = description
                // R-380 correction (WP2, gate-blocking) — see `LogRow`'s own doc comment above.
                text = AnnotatedString(description)
                role = Role.Button
                onClick(label = null) {
                    onClick()
                    true
                }
            },
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
            // R-380 (WP2 next round): carried no `semantics` of its own at all — the
            // `PrimaryButton`-before-fix shape (`Controls.kt`'s own doc comment) — the label lived
            // purely on the child `Text`, which a real device confirmed a `clickable` node does
            // not reliably absorb via merge alone. `clearAndSetSemantics` is the confirmed fix.
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = text
                // R-380 correction (WP2, gate-blocking) — see `LogRow`'s own doc comment above.
                this.text = AnnotatedString(text)
                role = Role.Button
                onClick(label = null) {
                    onClick()
                    true
                }
            },
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
    /** R-240 (`overnight/L01-log.png`): the kept/leading candidate's callsign to show beside
     * [alternate], for a state — AMBIGUOUS today — whose own [Attribution.stationId] is `null` by
     * design (`Attribution.ambiguous()` never carries one; more than one candidate survived, so
     * there is no single resolved station the data layer can name). Without this, an AMBIGUOUS
     * row reads only "or KE7QRS" — the primary candidate silently missing, both visually and from
     * the merged content description — which is the bug this field exists to let a caller fix.
     * `null` (every caller before this existed) falls back to [Attribution.stationId] exactly as
     * before, so CONFIRMED/INFERRED rows (which do carry one) are unaffected. */
    val callsign: String? = null,
    /** R-065: character ranges of [transcript] to render in `highlightGreen`, per
     * `Search-Results.dc.html`'s matched-word treatment (`.hit`) — a search match, not a state,
     * so it is additive and never the only way a row communicates anything. Empty by default so
     * every existing caller is unaffected. Out-of-bounds/reversed ranges are dropped rather than
     * crashing (constitution: never fabricate, never fail loudly on bad input from a caller). */
    val highlightRanges: List<IntRange> = emptyList(),
    /** E2-G04 (F23, FR-CAP-13): `true` for every over captured on a Bluetooth-audio session
     * (`Fail-Bluetooth-Audio.dc.html`'s row mark) — a badge per guide §6.14 (`text/dim` on
     * `line/chip`), never the only copy of the fact (the session's own DG04 facts, N04's Input
     * sub-line and F23's own banner all say so too). `false` (every caller before this existed)
     * renders exactly as before. */
    val btAudioMark: Boolean = false,
)

/** guide §6.5/`Rows.dc.html`: the densest row in the product.
 *
 * R-373: at a large font scale, the time/freq/signal columns' own real, measured widths
 * ([rememberTimeColumnWidth]/[rememberFreqColumnWidth]/[SIGNAL_COLUMN]) plus the station/
 * transcript column's own floor ([rememberCallsignColumnWidth]) can add up to more than the row
 * actually has — the guide's own "columns wrap as whole units, never intra-word" (§5): rather
 * than let the weighted column get squeezed narrower than its floor (the callsign-splitting
 * defect this id fixes), the whole layout switches to the station/transcript column on its own
 * line, with time/freq/signal — as one unit, never split from each other — on the line beneath
 * it. [BoxWithConstraints] decides which layout applies from the row's own real, current width,
 * not a guessed breakpoint. */
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
            .clearAndSetSemantics {
                val description = logRowDescription(state)
                contentDescription = description
                // R-380 correction (WP2, gate-blocking): `clearAndSetSemantics` alone erases the
                // merged-in child `Text`s' own `SemanticsProperties.Text`, so a `hasText(...)`
                // matcher on the default merged tree can no longer find this node even though
                // `contentDescription` does carry it — see `Controls.kt`'s `TextAction` doc for the
                // full finding. Declaring `text` here too (the same composed string) keeps both
                // routes working.
                text = AnnotatedString(description)
                role = Role.Button
                onClick(label = null) {
                    onClick()
                    true
                }
            },
    ) {
        val timeWidth = rememberTimeColumnWidth()
        val freqWidth = rememberFreqColumnWidth()
        val callsignWidth = rememberCallsignColumnWidth()
        val signalColumnWidth = rememberSignalColumnWidth()
        val gap = 10.dp
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 9.dp),
        ) {
            // R-505: the real, measured signal width whenever a signal column is possible for this
            // row at all — not just when this particular row happens to carry one — so the gate
            // reserves the same room every row in a list might need, the same reason the guide's
            // own column widths are shared floors rather than each row's own (possibly shorter)
            // content (this file's own R-205 doc comment).
            val oneLineWidth = oneLineWidthFor(timeWidth, freqWidth, callsignWidth, signalColumnWidth, gap)
            if (maxWidth >= oneLineWidth) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    LogRowTimeText(state, Modifier.widthIn(min = timeWidth))
                    LogRowFreqText(state, Modifier.widthIn(min = freqWidth))
                    Column(modifier = Modifier.weight(1f).widthIn(min = callsignWidth)) {
                        LogRowMarkerLine(state = state)
                        LogRowTranscriptText(state, Modifier.padding(top = 3.dp))
                    }
                    state.signalLabel?.let { LogRowSignalText(it, Modifier.widthIn(min = signalColumnWidth)) }
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LogRowMarkerLine(state = state)
                    LogRowTranscriptText(state, Modifier.padding(top = 3.dp))
                    Row(
                        modifier = Modifier.padding(top = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        LogRowTimeText(state, Modifier.widthIn(min = timeWidth))
                        LogRowFreqText(state, Modifier.widthIn(min = freqWidth))
                        state.signalLabel?.let { LogRowSignalText(it, Modifier.widthIn(min = signalColumnWidth)) }
                    }
                }
            }
        }
    }
}

/** R-373: [LogRow]'s time column, factored out so both the one-line and stacked layouts render
 * the identical `Text` (same `maxLines = 1, softWrap = false` — R-205's own fix for this exact
 * column, applied unchanged here) rather than two copies that could quietly drift apart. */
@Composable
private fun LogRowTimeText(state: LogRowViewState, modifier: Modifier = Modifier) {
    Text(
        text = state.timeLabel,
        style = OrtType.timeFreq,
        color = OrtColors.textTime,
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}

/** R-373: [LogRow]'s frequency column — see [LogRowTimeText]. */
@Composable
private fun LogRowFreqText(state: LogRowViewState, modifier: Modifier = Modifier) {
    Text(
        text = state.frequencyLabel,
        style = OrtType.timeFreq,
        color = OrtColors.textTime,
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}

/** R-373/R-505: [LogRow]'s signal column — see [LogRowTimeText]. Its own floor now comes entirely
 * from [modifier] (the caller's [rememberSignalColumnWidth], the same real-measurement-not-a-
 * guessed-constant fix [rememberCallsignColumnWidth] already applies) rather than a fixed constant
 * of its own — two different floors on the same column is exactly how the R-505 gap opened. */
@Composable
private fun LogRowSignalText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = OrtType.signal,
        color = OrtColors.textLow,
        modifier = modifier,
    )
}

/** R-373: [LogRow]'s transcript — see [LogRowTimeText]. Word-wraps normally (Compose's own
 * default `softWrap = true`, no `maxLines`): unlike the callsign, a transcript is expected to run
 * onto more than one line, and now that its column always has at least
 * [rememberCallsignColumnWidth]'s own floor (both layouts apply it — full row width in the
 * stacked case, `widthIn(min = …)` in the one-line case), the mid-word breaks the register found
 * ("activatio"/"n") stop happening in practice: they were a symptom of the same unbounded-shrink
 * defect the callsign fix addresses directly, not a separate transcript-only problem needing its
 * own line-breaking configuration. */
@Composable
private fun LogRowTranscriptText(state: LogRowViewState, modifier: Modifier = Modifier) {
    Text(
        text = highlightedTranscript(state.transcript, state.highlightRanges),
        style = logRowTranscriptStyle(state.partial),
        color = if (state.partial != null) OrtColors.textTime else OrtColors.textSecondary,
        modifier = modifier,
    )
}

/** [LogRow]'s own merged description — built explicitly, not left to `semantics(mergeDescendants
 * = true)` alone, because [AttributionRow] (rendered inside [LogRowMarkerLine]) is itself a
 * `mergeDescendants` boundary, and this Compose version does not reliably carry a
 * `contentDescription` up through a *second*, outer merge boundary around it (confirmed directly,
 * the side finding an earlier entry in this file's `CHANGELOG.md` recorded — validators reading
 * this exact merged description, "filled circle, Confirmed, W7NPC", found nothing at all before
 * this fix). Composing it explicitly is also what keeps the transcript itself reachable: setting
 * *any* explicit `contentDescription` on a node replaces what an accessibility service reads for
 * it — a merged `Text` list is what it would otherwise fall back to — so this deliberately
 * includes everything that list already carried (time, frequency, the transcript, the signal
 * figure) alongside the attribution words and badge label the plain merge was missing, rather
 * than trading one gap for a new one.
 *
 * Uses [attributionStateDescription] — state prose, callsign, alternate — not the full
 * [attributionRowDescription] `AttributionRow` itself carries (which is prefixed with the shape
 * word, "filled circle"/etc.): the shape is `AttributionRow`'s own node's business, and copying
 * its *entire* description here would make `LogRow`'s new description an exact substring
 * duplicate of that separate, still-independently-queryable node — collapsing a real caller's
 * "find the row by its content description" query from one match to two
 * (`ui/screens/LogScreenTest.kt`, outside this package, does exactly that; confirmed empirically
 * while landing this fix). Dropping the shape word keeps both unambiguous. */
private fun logRowDescription(state: LogRowViewState): String {
    val parts = mutableListOf(state.timeLabel, state.frequencyLabel)
    when (state.partial) {
        LogRowPartial.HEARING -> parts += "hearing…"
        LogRowPartial.RESOLVING -> parts += "resolving…"
        null -> state.attribution?.let { attribution ->
            parts += attributionStateDescription(attribution, state.callsign ?: attribution.stationId, state.alternate)
        }
    }
    parts += state.transcript
    state.signalLabel?.let { parts += it }
    state.badge?.let {
        parts += when (it) {
            LogRowBadge.NEW -> "new"
            LogRowBadge.CORRECTED -> "corrected"
            LogRowBadge.REVISED -> "revised"
        }
    }
    if (state.btAudioMark) parts += "bt audio"
    return parts.joinToString(", ")
}

/** R-246 (`pass-a-partial/L03.png`, `Log-Partial.dc.html`): "provisional must look provisional" —
 * the whole row, not only its "hearing…"/"resolving…" label, so [LogRow]'s transcript itself goes
 * italic too, for exactly the states that already mean "provisional" (derived from [partial]
 * rather than a separate `provisional` flag on [LogRowViewState]: `LogRowPartial` already carries
 * that fact, and a second field could only ever agree or silently disagree with the first — never
 * add real information). A plain function, not inlined into the `Text` call, so this decision is
 * directly unit-testable without rendering Compose at all — `TextStyle`/`FontStyle` are ordinary
 * data classes, not something that needs a running composition to compare (the pattern R-054's
 * `scrubFraction`/R-128's `meterColorFor` already established in this package, for the same
 * reason: a rendered *pixel* isn't reliably verifiable via this host's Robolectric setup, but the
 * pure decision that produces it is). */
internal fun logRowTranscriptStyle(partial: LogRowPartial?): TextStyle =
    if (partial != null) OrtType.transcript.copy(fontStyle = FontStyle.Italic) else OrtType.transcript

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

/** R-244 (`overnight/L01-log@2x.png`): at a large font scale, a real callsign plus [state]'s
 * badge no longer both fit on one line — the badge previously had nowhere to go but clip to a
 * sliver or vanish entirely, since a plain `Row` never wraps. A `FlowRow` fixes the actual defect
 * (a badge must never clip, guide §5/AC-63) without touching [AttributionRow]'s own internal
 * layout: the attribution (shape + callsign, or the AMBIGUOUS " or <alternate>") stays the one
 * atomic unit it already was — [AttributionRow] itself is shared far too widely to change its own
 * wrap behaviour from here — and [state]'s trailing `Badge` (`NEW`/`REVISED`/`CORRECTED`) is the
 * one thing that now wraps below it when there is no more room, rather than being cut off.
 *
 * R-420 (`overnight/L01-log@2x.png`): the INFERRED score chip is a *second* atomic unit, now
 * pulled out of [AttributionRow]'s own call ([AttributionRow.showScore] `= false` here) and
 * rendered as its own `FlowRow` item, right after it — free to wrap onto its own line below the
 * callsign the same way the badge already does, rather than staying welded to the callsign inside
 * `AttributionRow`'s own non-wrapping `Row` (where it previously collapsed into a one-character-
 * per-line stack once there was no room for both, colliding with the SIG column). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogRowMarkerLine(state: LogRowViewState, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(OrtSpacing.xs),
    ) {
        when (state.partial) {
            LogRowPartial.HEARING -> {
                HearingMeter(modifier = Modifier.align(Alignment.CenterVertically))
                Text(
                    text = "hearing…",
                    style = OrtType.subLine.copy(fontStyle = FontStyle.Italic),
                    color = OrtColors.textDim,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }

            LogRowPartial.RESOLVING -> {
                InProgressRing(
                    size = 9.dp,
                    color = OrtColors.textSignal,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                Text(
                    text = "resolving…",
                    style = OrtType.subLine.copy(fontStyle = FontStyle.Italic),
                    color = OrtColors.textDim,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }

            null -> state.attribution?.let { attribution ->
                AttributionRow(
                    attribution = attribution,
                    // R-240: `state.callsign` first — the caller's kept-candidate override for a
                    // state (AMBIGUOUS) whose own Attribution.stationId is null by design — falling
                    // back to attribution.stationId so CONFIRMED/INFERRED are unaffected, exactly
                    // AttributionRow's own default.
                    callsign = state.callsign ?: attribution.stationId,
                    alternate = state.alternate,
                    // R-420: rendered as this FlowRow's own separate item, below.
                    showScore = false,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                if (attribution.state == AttributionState.INFERRED) {
                    attribution.confidence?.let { confidence ->
                        ScoreChip(confidence = confidence, modifier = Modifier.align(Alignment.CenterVertically))
                    }
                }
            }
        }
        state.badge?.let { badge ->
            val (label, kind) = when (badge) {
                LogRowBadge.NEW -> "new" to BadgeKind.NEW
                LogRowBadge.CORRECTED -> "corrected" to BadgeKind.CORRECTED
                LogRowBadge.REVISED -> "revised" to BadgeKind.REVISED
            }
            Badge(text = label, kind = kind, modifier = Modifier.align(Alignment.CenterVertically))
        }
        // E2-G04 (F23, FR-CAP-13): the `bt audio` mark — guide §6.14's `CORRECTED` styling
        // (`text/dim` on a `line/chip` outline), its own badge slot beside whatever else this row
        // already carries (a row can be both `NEW` and Bluetooth-audio at once).
        if (state.btAudioMark) {
            Badge(text = "bt audio", kind = BadgeKind.CORRECTED, modifier = Modifier.align(Alignment.CenterVertically))
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
                    // R-380 (WP2 next round): carried no `semantics` of its own at all when
                    // clickable — the `PrimaryButton`-before-fix shape (`Controls.kt`'s own doc
                    // comment); `clearAndSetSemantics` is the confirmed fix.
                    Modifier
                        .heightIn(min = 44.dp)
                        .clickable(role = Role.Button, onClick = onClick)
                        .clearAndSetSemantics {
                            contentDescription = label
                            // R-380 correction (WP2, gate-blocking) — see `LogRow`'s own doc
                            // comment above.
                            text = AnnotatedString(label)
                            role = Role.Button
                            onClick(label = null) {
                                onClick()
                                true
                            }
                        }
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
 * conflated with a quiet band (FR-UI-12, FR-RUN-12). [bluetoothAudioDropped] (R-838, register,
 * design) swaps the ordinary [OrtIcons.gapWarn] circle for [OrtIcons.interruptedConnector] — a
 * structural flag from the row's own real [org.ort.data.entity.CaptureGapCause], never a check
 * against [label]'s own prose. `false` (every caller before this existed) renders exactly as
 * before. */
@Composable
public fun GapRow(
    timeLabel: String,
    label: String,
    modifier: Modifier = Modifier,
    bluetoothAudioDropped: Boolean = false,
) {
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
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.widthIn(min = rememberTimeColumnWidth()),
            )
            Icon(
                imageVector = if (bluetoothAudioDropped) OrtIcons.interruptedConnector else OrtIcons.gapWarn,
                contentDescription = null,
                tint = OrtColors.accentGap,
                // R-838: see Banner's own identical testTag doc comment (Feedback.kt) — proves the
                // real cause actually reached the rendered icon, never a check against label prose.
                modifier = Modifier.size(13.dp).testTag(
                    if (bluetoothAudioDropped) "gap-row-icon-interrupted" else "gap-row-icon-default",
                ),
            )
            Text(text = label, style = OrtType.subLine, color = OrtColors.accentAmberDim)
        }
    }
}

/** `Rows.dc.html`'s rejected row: dimmed but present, with its reason. Its audio is retained and
 * it remains openable — nothing is deleted quietly (constitution III).
 *
 * R-242 (`Log-Rejected.dc.html`'s `DUR` column, `overnight/L05-rejected.png`): [durationLabel] is
 * the segment's own length ("0.4s") — `null` (every caller before this existed) renders exactly
 * as before, no `DUR` column at all, rather than an empty one. */
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
    durationLabel: String? = null,
) {
    val description = rejectedRowDescription(timeLabel, frequencyLabel, reason, why, durationLabel)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .then(rejectedRowInteractionModifier(onClick, description)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = timeLabel,
                style = OrtType.timeFreq,
                color = OrtColors.textTime,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.widthIn(min = rememberTimeColumnWidth()),
            )
            Text(
                text = frequencyLabel,
                style = OrtType.timeFreq,
                color = OrtColors.textTime,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.widthIn(min = rememberFreqColumnWidth()),
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
            // guide (`.s`): 11px mono, text/low — the same DUR-column treatment `LogRow`'s own
            // trailing signal figure uses (`OrtType.signal`/`OrtColors.textLow`).
            durationLabel?.let {
                Text(
                    text = it,
                    style = OrtType.signal,
                    color = OrtColors.textLow,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

private fun rejectedRowDescription(
    timeLabel: String,
    frequencyLabel: String,
    reason: String,
    why: String?,
    durationLabel: String?,
): String = buildString {
    append(timeLabel)
    append(", ")
    append(frequencyLabel)
    append(", rejected, ")
    append(reason)
    why?.let {
        append(", ")
        append(it)
    }
    durationLabel?.let {
        append(", ")
        append(it)
    }
}

/** [RejectedRow]'s interaction/dim modifier, split out of the composable itself only to keep it
 * under detekt's `LongMethod` — R-381 (WP2 next round): a `.clickable(...)` node followed by a
 * *separate* `semantics(mergeDescendants = true) { contentDescription = ... }` is the exact
 * two-stage shape this package's own R-380/R-381 finding proved does not survive to a real device
 * even with an explicit description — `clearAndSetSemantics` on the same node `clickable` lives on
 * is the confirmed fix, used here when [onClick] is given; the non-clickable branch is unchanged. */
private fun rejectedRowInteractionModifier(onClick: (() -> Unit)?, description: String): Modifier =
    if (onClick != null) {
        Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .alpha(0.45f)
            .clearAndSetSemantics {
                contentDescription = description
                // R-380 correction (WP2, gate-blocking) — see `LogRow`'s own doc comment.
                text = AnnotatedString(description)
                role = Role.Button
                onClick(label = null) {
                    onClick()
                    true
                }
            }
    } else {
        Modifier
            .alpha(0.45f)
            .semantics(mergeDescendants = true) { contentDescription = description }
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
