package org.ort.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * R-023/R-024 (ui-conformance-plan WP2): the shared control set from guide §6.3-6.7/6.10-6.12 and
 * `Controls.dc.html`. None of these existed before R-023; every screen was hand-rolling `Text` +
 * `clickable` with no pressed state and the text's own height as the hit area (R-024's `halt`
 * finding). Every control here has a real >=44dp target.
 */

// ---------------------------------------------------------------------------------------------
// 6.7 Buttons and actions
// ---------------------------------------------------------------------------------------------

/** guide §6.7: 13sp/500 `accent/green`, a real 44dp target, `bg/pressed` while pressed,
 * `text/disabled` when [enabled] is false. This is the default action style for a dense
 * instrument — most actions are text, not filled buttons.
 *
 * R-380 (`S12` `Fix`, dumped as `<node text="" content-desc="" clickable="true" focusable="true">
 * <node text="Fix" focusable="false"/></node>`): the trailing `semantics(mergeDescendants = true)
 * {}` this carried was an *empty* block — depending on merge-from-descendants alone to pull
 * [text] up from the child `Text` into the clickable node's own accessible name, which this
 * package's whole "nested merge boundary" history already found unreliable in this Compose
 * version (`AttributionRow`-in-`LogRow`, `TextField`'s own `RequestFocus`/`SetText`, `KeyValueRow`-
 * in-`ReadyScreen`, each an earlier entry in this file's `CHANGELOG.md`) — confirmed on a real
 * device to fail here too, for the *outer clickable node's own name*, not only for what bubbles
 * into a second ancestor. The fix already established for the working cases
 * (`LogRow`/`LiveBar`/`KeyValueRow`: an *explicit*, literal `contentDescription` in the same
 * `semantics` block, never left to merge alone) is what this now does too: [text] is composed
 * directly, on the same node `clickable` itself lives on. */
@Composable
public fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val color = if (!enabled) OrtColors.textDisabled else OrtColors.accentGreen
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .background(
                if (pressed && enabled) OrtColors.bgPressed else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp)
            .clearAndSetSemantics {
                contentDescription = text
                role = Role.Button
                if (enabled) {
                    onClick(label = null) {
                        onClick()
                        true
                    }
                } else {
                    disabled()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = text, style = OrtType.textAction.copy(fontWeight = FontWeight.Medium), color = color)
    }
}

/** guide §6.7: the filled button — setup and destructive actions only. 48dp tall, `accent/green`
 * fill, `accent/on-green` text; disabled is `bg/chip`/`text/disabled`.
 *
 * R-380 (`Start capture`, among others): this carried no `semantics` of its own at all — the label
 * lived purely on the child `Text`, one real device confirmed a `clickable` node does not reliably
 * absorb via merge alone (see [TextAction]'s own doc comment for the full finding). [text] is now
 * composed explicitly on the same node `clickable` lives on, the pattern every fixed composable in
 * this file now shares. */
@Composable
public fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val bg = if (enabled) OrtColors.accentGreen else OrtColors.bgChip
    val fg = if (enabled) OrtColors.accentOnGreen else OrtColors.textDisabled
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .background(bg, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp)
            .clearAndSetSemantics {
                contentDescription = text
                role = Role.Button
                if (enabled) {
                    onClick(label = null) {
                        onClick()
                        true
                    }
                } else {
                    disabled()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = OrtType.control.copy(fontWeight = FontWeight.Medium), color = fg)
    }
}

/** guide §6.7: the outlined companion to [PrimaryButton] — 44dp, `line/chip` border. R-380: see
 * [PrimaryButton]'s own doc comment — the identical fix. */
@Composable
public fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val fg = if (enabled) OrtColors.textBody else OrtColors.textDisabled
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .border(1.dp, OrtColors.lineChip, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp)
            .clearAndSetSemantics {
                contentDescription = text
                role = Role.Button
                if (enabled) {
                    onClick(label = null) {
                        onClick()
                        true
                    }
                } else {
                    disabled()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = OrtType.control.copy(fontWeight = FontWeight.Medium), color = fg)
    }
}

/** guide §6.7: `halt/fill`/`halt/on-fill` — the one destructive button style in the product. R-380:
 * see [PrimaryButton]'s own doc comment — the identical fix. */
@Composable
public fun DestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .background(OrtColors.haltFill, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp)
            .clearAndSetSemantics {
                contentDescription = text
                role = Role.Button
                if (enabled) {
                    onClick(label = null) {
                        onClick()
                        true
                    }
                } else {
                    disabled()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = OrtType.control.copy(fontWeight = FontWeight.Medium), color = OrtColors.haltOnFill)
    }
}

// ---------------------------------------------------------------------------------------------
// 6.3 Filter chip
// ---------------------------------------------------------------------------------------------

/** guide §6.3: pill, 14px radius, `5px 11px` padding. Selected is `bg/chip` fill/`text/high`/500;
 * unselected is a `line/chip` outline/`text/muted`/400. The visible pill is small, so the tap
 * target is padded out to 44dp around it (guide §5's rule, not the pill's own bounds).
 *
 * R-383 (`L02` `Log-Filter`'s frequency chip row, `[38,418][164,516]` — 98–103px, under the 116px
 * floor, while the attribution chip row on the same sheet measures 126px): `heightIn(min = 44.dp)`
 * as the *last* modifier in the chain, nothing measurement-relevant appended after it — this
 * package's own established fix for a `heightIn` floor measuring short once more modifiers follow
 * it in the same chain (`KeyValueRow`/`LogRow`/`RejectedRow`/`GapRow`/`LogGroupHeader`/
 * `DrillInHeader`/`ScreenHeader`'s own outer-`Box`-carries-size, inner-content-carries-layout
 * pattern is the fuller version of the same fix; here the outer `Box` already carries nothing but
 * size and the click target, so reordering is the whole fix needed). [selectable] itself never
 * changes the chip's own visible size — only the invisible touch target grows.
 *
 * R-380/R-381 ("L02 chips"): [label] is now also composed explicitly into this node's own
 * `contentDescription` — `selectable()` alone carries the click action and the selected state
 * (`Role.Checkbox`) but never the visible label, which previously lived only on the inner `Text`
 * with nothing pulling it up onto this, the clickable node — see [TextAction]'s own doc comment
 * for the fuller finding this package confirmed on a real device. */
@Composable
public fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    // R-061 (WP7's `Filters` chip, `Search-Filters.dc.html`): an optional leading glyph before
    // the label, tinted to match the label. Null by default — no existing chip gains an icon.
    leadingIcon: ImageVector? = null,
) {
    Box(
        modifier = modifier
            .selectable(selected = selected, onClick = onClick, role = Role.Checkbox)
            .heightIn(min = 44.dp)
            .clearAndSetSemantics {
                contentDescription = label
                this.selected = selected
                role = Role.Checkbox
                onClick(label = null) {
                    onClick()
                    true
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .background(if (selected) OrtColors.bgChip else Color.Transparent, RoundedCornerShape(14.dp))
                .then(
                    if (selected) {
                        Modifier
                    } else {
                        Modifier.border(1.dp, OrtColors.lineChip, RoundedCornerShape(14.dp))
                    },
                )
                .padding(
                    start = 11.dp,
                    end = if (onDismiss != null) 9.dp else 11.dp,
                    top = 5.dp,
                    bottom = 5.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val labelColor = if (selected) OrtColors.textHigh else OrtColors.textMuted
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = labelColor,
                    modifier = Modifier.size(11.dp),
                )
            }
            Text(
                text = label,
                style = OrtType.chip.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
                color = labelColor,
            )
            if (onDismiss != null) {
                Icon(
                    imageVector = OrtIcons.dismiss,
                    contentDescription = "Remove $label filter",
                    tint = OrtColors.textChipX,
                    modifier = Modifier
                        .size(11.dp)
                        .clickable(role = Role.Button, onClickLabel = "Remove $label filter", onClick = onDismiss),
                )
            }
        }
    }
}

/** Applied filters scroll horizontally and never wrap (guide §6.3). */
@Composable
public fun FilterChipRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        content = content,
    )
}

// ---------------------------------------------------------------------------------------------
// 6.14 Badges
// ---------------------------------------------------------------------------------------------

/** guide §6.14's complete badge set. A badge never carries the only copy of a fact. */
public enum class BadgeKind { NEW, REVISED, CORRECTED, TIER, COUNT }

@Composable
public fun Badge(text: String, kind: BadgeKind, modifier: Modifier = Modifier) {
    val shape = if (kind == BadgeKind.COUNT) RoundedCornerShape(9.dp) else RoundedCornerShape(3.dp)
    val (bg, border, fg) = when (kind) {
        BadgeKind.NEW -> Triple(OrtColors.accentGreen, null, OrtColors.accentOnGreen)
        BadgeKind.REVISED -> Triple(Color.Transparent, OrtColors.badgeRevisedBorder, OrtColors.accentAmberText)
        BadgeKind.CORRECTED -> Triple(Color.Transparent, OrtColors.lineChip, OrtColors.textDim)
        BadgeKind.TIER -> Triple(Color.Transparent, OrtColors.lineChip, OrtColors.textDim)
        BadgeKind.COUNT -> Triple(OrtColors.accentAmber, null, OrtColors.accentOnAmber)
    }
    Box(
        modifier = modifier
            .background(bg, shape)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text = text.uppercase(), style = OrtType.badge, color = fg)
    }
}

// ---------------------------------------------------------------------------------------------
// 6.12 Progress
// ---------------------------------------------------------------------------------------------

/** guide §6.12: a 4px `line/default` track with an `accent/green` fill — never indeterminate. If
 * the length is unknown, the caller shows what is known ("27 of 64") beside this, not a spinner. */
@Composable
public fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(OrtColors.lineDefault, RoundedCornerShape(2.dp))
            .semantics { contentDescription = "${(progress.coerceIn(0f, 1f) * 100).toInt()} percent" },
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(OrtColors.accentGreen, RoundedCornerShape(2.dp)),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 6.10 Step indicator
// ---------------------------------------------------------------------------------------------

/** guide §6.10: setup-only. Equal segments, `accent/green` for done/current, `line/default` for
 * the rest, a `halt/text` segment where [haltedStep] names one, and a mono "n of N" counter. */
@Composable
public fun StepIndicator(steps: Int, currentStep: Int, modifier: Modifier = Modifier, haltedStep: Int? = null) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = if (haltedStep != null) {
                "Step $currentStep of $steps, halted at step $haltedStep"
            } else {
                "Step $currentStep of $steps"
            }
        },
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(text = "$currentStep of $steps", style = OrtType.signal, color = OrtColors.textDim)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (step in 1..steps) {
                val color = when {
                    step == haltedStep -> OrtColors.haltText
                    step <= currentStep -> OrtColors.accentGreen
                    else -> OrtColors.lineDefault
                }
                Box(modifier = Modifier.weight(1f).height(3.dp).background(color, RoundedCornerShape(2.dp)))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 6.11 Selection controls
// ---------------------------------------------------------------------------------------------

/** R-081: [RadioRow]'s tone — `Warning` is `Setup-Input.dc.html`'s refused built-in-mic row (an
 * amber [RadioRow.subtitle], never colour alone since the row is also the *last*, dimmest option
 * in the list and its subtitle states the refusal in words). */
public enum class RowTone { Neutral, Warning }

/** guide §6.11: a closed set is always a visible list with counts — never a tap-to-cycle label. */
@Composable
public fun RadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: String? = null,
    // R-081 (`Setup-Input.dc.html`): an explanatory line under the label — device kind/sample
    // rate normally, the refusal reason ("Not a radio — capture will refuse this route") when
    // [tone] is [RowTone.Warning]. Both null/Neutral by default, so existing rows are unchanged.
    subtitle: String? = null,
    tone: RowTone = RowTone.Neutral,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        RadioDot(selected = selected)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = OrtType.control,
                color = if (selected) OrtColors.textHigh else OrtColors.textBody,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = OrtType.chip,
                    color = if (tone == RowTone.Warning) OrtColors.accentAmberText else OrtColors.textDim,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        count?.let { Text(text = it, style = OrtType.signal, color = OrtColors.textFigure) }
    }
}

@Composable
private fun RadioDot(selected: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(16.dp)) {
        if (selected) {
            drawCircle(
                color = OrtColors.accentGreen,
                radius = size.minDimension / 2 - 0.75.dp.toPx(),
                style = Stroke(1.5.dp.toPx()),
            )
            drawCircle(color = OrtColors.accentGreen, radius = 4.dp.toPx())
        } else {
            drawCircle(
                color = OrtColors.lineControl,
                radius = size.minDimension / 2 - 0.75.dp.toPx(),
                style = Stroke(1.5.dp.toPx()),
            )
        }
    }
}

/** guide §6.11: 16dp checkbox, 4px radius, `accent/green` fill + `accent/on-green` check when on. */
@Composable
public fun CheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subLine: String? = null,
    count: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Checkbox),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        CheckboxBox(checked = checked)
        leading?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = OrtType.control, color = OrtColors.textHigh)
            subLine?.let { Text(text = it, style = OrtType.subLine, color = OrtColors.textDim) }
        }
        count?.let { Text(text = it, style = OrtType.signal, color = OrtColors.textFigure) }
    }
}

@Composable
private fun CheckboxBox(checked: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(16.dp)
            .background(if (checked) OrtColors.accentGreen else Color.Transparent, RoundedCornerShape(4.dp))
            .then(
                if (checked) {
                    Modifier
                } else {
                    Modifier.border(1.5.dp, OrtColors.lineControl, RoundedCornerShape(4.dp))
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = OrtIcons.check,
                contentDescription = null,
                tint = OrtColors.accentOnGreen,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

/** guide §6.11: 42x24 pill, `accent/green` + `accent/on-green` knob when on, `bg/chip` +
 * `text/signal` knob when off. A toggle carries its consequence as [subLine]. */
@Composable
public fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subLine: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = OrtType.control, color = OrtColors.textHigh)
            subLine?.let { Text(text = it, style = OrtType.subLine, color = OrtColors.textDim) }
        }
        ToggleKnob(checked = checked)
    }
}

// ---------------------------------------------------------------------------------------------
// Fields — Controls.dc.html's "Fields" panel.
// ---------------------------------------------------------------------------------------------

/** guide §3: the `halt` colour family is reserved for capture stopped, never a field-validation
 * cue — R-063.
 * [FieldTone.Halt] (the default, matching every caller before this existed) is for the rare field
 * whose error really does mean something halting; [FieldTone.Degraded] is the ordinary amber
 * "this didn't take" cue (`Search-Unavailable.dc.html`'s "text not applied"). */
public enum class FieldTone { Halt, Degraded }

/**
 * `Controls.dc.html`'s field: 44dp, `bg/current` ground, `line/strong` border (`accent/green`
 * focused or non-empty), placeholder in `text/signal`, mono when [mono] (a callsign or frequency
 * field). [errorText], when given, renders below in [errorTone]'s colour. [leadingIcon] and
 * [trailingAction] sit inside the bordered box itself (a search glyph, a clear `×`), per
 * `Search.dc.html` — not outside it, which was R-060's gap. [keyboardOptions]/[keyboardActions]
 * reach the underlying [BasicTextField] directly (a search field's IME action, for one).
 *
 * This composable's one emitted node **is** the [BasicTextField] itself — [label], the bordered
 * box, [leadingIcon], [trailingAction] and [errorText] are all rendered inside its
 * `decorationBox`, the same architecture Material3's own `TextField` uses, precisely so [modifier]
 * and [contentDescriptionText] land on the one real, focusable, editable node rather than on a
 * wrapping layer above it. That gives every caller both things at once, on the same node, with no
 * special-case query: `Modifier.weight(1f)` inside a `Row` is honoured (this is the composable's
 * own root, the direct child the `Row` measures — R-060's weight gap), and
 * `onNodeWithContentDescription(x).performTextInput(...)` / `performImeAction()` — and
 * `onNodeWithTag(callerTag).performTextInput(...)` for a caller that tags via [modifier] instead —
 * work exactly as `RequestFocus`/`SetText` on a plain `BasicTextField` always have, because
 * semantics merging an ancestor's way into a descendant's `RequestFocus`/`SetText` is not reliable
 * in this Compose version (verified by experiment), so this field never depends on it.
 */
@Suppress("LongParameterList") // every parameter is an independent, optional field concern.
@Composable
public fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    mono: Boolean = false,
    errorText: String? = null,
    contentDescriptionText: String? = null,
    singleLine: Boolean = true,
    leadingIcon: ImageVector? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    errorTone: FieldTone = FieldTone.Halt,
) {
    val degraded = errorText != null && errorTone == FieldTone.Degraded
    val borderColor = when {
        degraded -> OrtColors.bannerAmberBorder
        errorText != null -> OrtColors.haltBorder
        value.isNotEmpty() -> OrtColors.accentGreen
        else -> OrtColors.lineStrong
    }
    val textStyle = (if (mono) OrtType.control.copy(fontFamily = FontFamily.Monospace) else OrtType.control)
        .copy(color = OrtColors.textHigh)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = textStyle,
        cursorBrush = SolidColor(OrtColors.accentGreen),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = contentDescriptionText ?: label ?: placeholder.orEmpty()
            },
        decorationBox = { innerTextField ->
            Column {
                label?.let {
                    Text(
                        text = it,
                        style = OrtType.sectionLabel,
                        color = OrtColors.textFaint,
                        modifier = Modifier.padding(bottom = OrtSpacing.xs),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .background(OrtColors.bgCurrent, RoundedCornerShape(8.dp))
                        .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                        .padding(horizontal = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    if (leadingIcon != null) {
                        Icon(
                            imageVector = leadingIcon,
                            contentDescription = null,
                            tint = OrtColors.textDim,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(text = placeholder, style = OrtType.control, color = OrtColors.textSignal)
                        }
                        innerTextField()
                    }
                    trailingAction?.invoke()
                }
                errorText?.let {
                    Text(
                        text = it,
                        style = OrtType.subLine,
                        color = if (degraded) OrtColors.accentAmberText else OrtColors.haltText,
                        modifier = Modifier.padding(top = OrtSpacing.xs),
                    )
                }
            }
        },
    )
}

@Composable
private fun ToggleKnob(checked: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(42.dp)
            .height(24.dp)
            .background(if (checked) OrtColors.accentGreen else OrtColors.bgChip, RoundedCornerShape(12.dp))
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .background(if (checked) OrtColors.accentOnGreen else OrtColors.textSignal, CircleShape),
        )
    }
}
