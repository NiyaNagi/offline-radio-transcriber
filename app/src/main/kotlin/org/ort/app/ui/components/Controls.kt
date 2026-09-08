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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * instrument — most actions are text, not filled buttons. */
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
            .semantics(mergeDescendants = true) {},
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = text, style = OrtType.textAction.copy(fontWeight = FontWeight.Medium), color = color)
    }
}

/** guide §6.7: the filled button — setup and destructive actions only. 48dp tall, `accent/green`
 * fill, `accent/on-green` text; disabled is `bg/chip`/`text/disabled`. */
@Composable
public fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val bg = if (enabled) OrtColors.accentGreen else OrtColors.bgChip
    val fg = if (enabled) OrtColors.accentOnGreen else OrtColors.textDisabled
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .background(bg, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = OrtType.control.copy(fontWeight = FontWeight.Medium), color = fg)
    }
}

/** guide §6.7: the outlined companion to [PrimaryButton] — 44dp, `line/chip` border. */
@Composable
public fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val fg = if (enabled) OrtColors.textBody else OrtColors.textDisabled
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .border(1.dp, OrtColors.lineChip, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = OrtType.control.copy(fontWeight = FontWeight.Medium), color = fg)
    }
}

/** guide §6.7: `halt/fill`/`halt/on-fill` — the one destructive button style in the product. */
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
            .padding(horizontal = 18.dp),
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
 * target is padded out to 44dp around it (guide §5's rule, not the pill's own bounds). */
@Composable
public fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.Checkbox),
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
            Text(
                text = label,
                style = OrtType.chip.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
                color = if (selected) OrtColors.textHigh else OrtColors.textMuted,
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

/** guide §6.11: a closed set is always a visible list with counts — never a tap-to-cycle label. */
@Composable
public fun RadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: String? = null,
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
        Text(
            text = label,
            style = OrtType.control,
            color = if (selected) OrtColors.textHigh else OrtColors.textBody,
            modifier = Modifier.weight(1f),
        )
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
