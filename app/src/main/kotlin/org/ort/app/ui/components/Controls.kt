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
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
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
 * directly, on the same node `clickable` itself lives on.
 *
 * R-510-class (found landing that id's own device check, `Sheet`'s "Clear all" — no caller modifier
 * at all, yet measured 72px/27.4dp on `emulator-5554`, `wm density 420`, against a required 115.5px
 * — the raw, unfloored content height, `OrtType.textAction`'s own line plus zero added padding):
 * `heightIn(min = 44.dp)` here was the *first* modifier in the chain, with `.background()`/
 * `.clickable()`/`.padding()` all measurement-relevant and all appended *after* it — precisely the
 * shape [FilterChip]'s own R-383 doc comment already named as this package's known-broken order.
 * `requiredHeightIn`, [FilterChip]'s own R-510 fix, closes both failure modes at once (a caller's
 * own tighter modifier, and a measurement-relevant modifier later in the same chain) since it does
 * not coerce its own floor into whatever surrounds it either way.
 *
 * R-863 (register, design, validator V9 — the same defect class WPD already found and fixed
 * locally on S10b's own `Refresh`, R-805): an unweighted `TextAction` sitting after a plain, non-
 * weighted leading sibling in a `Row` (`Settings-Assets.dc.html`'s "Install from a file" after a
 * long asset-part label, `Digest-Prose.dc.html`'s "Read the overs" after the over-count label) let
 * that sibling's own wrap claim the row's width first, squeezing this action's own text down to one
 * word — sometimes one letter — per line.
 *
 * `Modifier.width(IntrinsicSize.Max)` was tried first and does not hold here: measured `0` wide at
 * font scale 2.0 on both real sites (`ModelsScreenTest`/`DigestScreensTest`'s own `R_863` cases
 * caught this directly — the fix was reverted only after failing its own new tests, never merged
 * unverified). `Modifier.wrapContentWidth(unbounded = true)`, applied here once, is what actually
 * holds: it measures this action's content against an *unbounded* width regardless of whatever the
 * incoming constraint offers, so it always lays out on one line at its own true text width — the
 * same proven technique `ActivityPatternChart`'s own hour-axis labels already use for an equivalent
 * "never let a tight column force-wrap this label" defect. Because this action now always reports
 * its own real, unwrapped width to the `Row` it sits in, a `Row` with no other flexible child left
 * simply overflows onto whichever sibling has nowhere else to shrink — matching S10b's own manual
 * `weight(1f)`-on-the-sibling fix (a `Row` still correctly gives a *weighted* sibling only the
 * remainder once this action's own real width is known), without requiring every call site to
 * remember to add it. Placed *before* [modifier] is fully applied — i.e., inside the caller's own
 * chain — so a caller that deliberately stretches this action (`Modifier.weight(1f)`, S10b's own
 * *leading* action) still can: `weight` is read by the parent `Row` from the outside and hands this
 * node a fixed constraint before this modifier ever runs. */
@Composable
public fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val color = if (!enabled) OrtColors.textDisabled else OrtColors.accentGreen
    Box(
        modifier = modifier
            // R-863: see this composable's own doc comment above.
            .wrapContentWidth(align = Alignment.Start, unbounded = true)
            // R-510-class: `requiredHeightIn`, not `heightIn` — see this composable's own doc
            // comment.
            .requiredHeightIn(min = 44.dp)
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
                // R-380 correction (WP2, gate-blocking): `clearAndSetSemantics` alone erases the
                // child `Text`'s own `SemanticsProperties.Text`, so a `hasText(...)` matcher on the
                // default merged tree — several real smoke tests use exactly this shape — can no
                // longer find this node even though `contentDescription` does carry it. Declaring
                // `text` here too (the same string) keeps both routes working — device-verified
                // (see `CHANGELOG.md`) that `contentDescription` still wins what TalkBack
                // announces when both are present, so this does not double-announce.
                this.text = AnnotatedString(text)
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
 * this file now shares.
 *
 * R-510-class: `requiredHeightIn`, not `heightIn` — see [TextAction]'s own doc comment for the
 * device finding this generalises from. */
@Composable
public fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val bg = if (enabled) OrtColors.accentGreen else OrtColors.bgChip
    val fg = if (enabled) OrtColors.accentOnGreen else OrtColors.textDisabled
    Box(
        modifier = modifier
            .requiredHeightIn(min = 48.dp)
            .background(bg, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp)
            .clearAndSetSemantics {
                contentDescription = text
                // R-380 correction (WP2, gate-blocking) — see [TextAction]'s own doc comment.
                this.text = AnnotatedString(text)
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
 * [PrimaryButton]'s own doc comment — the identical fix. R-510-class: see [TextAction]'s own doc
 * comment — the identical fix. */
@Composable
public fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val fg = if (enabled) OrtColors.textBody else OrtColors.textDisabled
    Box(
        modifier = modifier
            .requiredHeightIn(min = 44.dp)
            .border(1.dp, OrtColors.lineChip, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp)
            .clearAndSetSemantics {
                contentDescription = text
                // R-380 correction (WP2, gate-blocking) — see [TextAction]'s own doc comment.
                this.text = AnnotatedString(text)
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
 * see [PrimaryButton]'s own doc comment — the identical fix. R-510-class: see [TextAction]'s own
 * doc comment — the identical fix. */
@Composable
public fun DestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .requiredHeightIn(min = 44.dp)
            .background(OrtColors.haltFill, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp)
            .clearAndSetSemantics {
                contentDescription = text
                // R-380 correction (WP2, gate-blocking) — see [TextAction]'s own doc comment.
                this.text = AnnotatedString(text)
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
 * unselected is a `line/chip` outline/`text/muted`/400. R-565: the pill itself now fills the 44dp
 * floor (guide §5) — the fill/border are drawn on the same node the floor is measured on, not a
 * smaller inner wrapper centred inside it (see this composable's own R-565 doc comment below).
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
 * for the fuller finding this package confirmed on a real device.
 *
 * R-543: `clearAndSetSemantics` (the fix just above) genuinely removes every descendant from the
 * exported accessibility tree on a real device — confirmed there for [onDismiss]'s own dismiss
 * icon specifically, which used to be its own separate, labelled, focusable node ("Remove $label
 * filter") a screen reader could reach independently of the chip's own selection target, and after
 * the R-380/R-381 fix could not be reached at all. Rather than restructure the chip's own layout
 * (the dismiss icon's hit target deliberately sits *inside* the same visual pill, consuming its
 * own touch before the chip's own `selectable` ever sees it — changing that risks the touch
 * geometry, not just the semantics), [onDismiss] is exposed as a
 * [androidx.compose.ui.semantics.CustomAccessibilityAction] on the chip's own node instead — the
 * idiomatic Compose shape for "a secondary action nested inside a single accessible control"
 * (TalkBack's own local context menu, not a second on-screen stop). The dismiss icon's own visible
 * `Icon`/`contentDescription`/`clickable` are unchanged — sighted/touch interaction is identical to
 * before; only the *screen-reader* route to it changed shape.
 *
 * R-510 (`overnight/L02-filter-sheet.png`, `@2x` — the frequency chip row's own real fill height
 * measured 24dp at 1.0 and 37.7dp at 2.0, under the 44dp floor even after R-383's reordering fix):
 * a plain `heightIn(min = 44.dp)` only ever *raises* whatever floor the incoming constraints
 * already carry — it can never violate a *tighter* bound a caller's own modifier fixed further out
 * in the chain (`modifier`, this composable's own leftmost/outermost parameter, ahead of every
 * modifier this file adds), so a caller able to pass its own fixed/narrower height genuinely
 * undercuts it — exactly what "enforce the 44dp floor inside the component so no caller can
 * undercut it" names. `requiredHeightIn(min = 44.dp)` is this package's fix: unlike `heightIn`, it
 * does not coerce its own floor into the incoming constraints — it is enforced regardless of them,
 * the standard defensive-minimum-touch-target technique for a component that must guarantee its
 * own floor independent of its caller (`ControlsTest.kt`'s own `R_510` test pins this directly).
 *
 * R-565 (Reviewer B, `overnight/L02-filter-sheet.png`/`@2x`): `requiredHeightIn` grew the *touch
 * target* — this node's own semantics/measured bounds — to 48dp, exactly as R-510 intended, but the
 * *painted* pill (`.background()`/`.border()`) stayed on the inner `Row`, which only ever wrapped
 * its own, much shorter content (5dp padding + one line of `OrtType.chip`) — so the visible chip
 * itself stayed ~24dp at 1.0/~32dp at 2.0, an invisible, larger touch box centred around a
 * genuinely undersized pill. The guide's own floor is for the *control*, not only its hit-testing
 * — `.background()`/`.border()` moved onto this outer `Box`, the one node `requiredHeightIn`
 * already carries, so the pill drawn is the same box that's guaranteed 44dp; the inner `Row` now
 * carries only content padding/arrangement, never the fill. */
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
            // R-510: `requiredHeightIn`, not `heightIn` — see this composable's own doc comment.
            .requiredHeightIn(min = 44.dp)
            // R-565: the pill's own fill/border, drawn on the same box the 44dp floor lives on —
            // see this composable's own doc comment.
            .background(if (selected) OrtColors.bgChip else Color.Transparent, RoundedCornerShape(14.dp))
            .then(
                if (selected) {
                    Modifier
                } else {
                    Modifier.border(1.dp, OrtColors.lineChip, RoundedCornerShape(14.dp))
                },
            )
            .clearAndSetSemantics {
                contentDescription = label
                // R-380 correction (WP2, gate-blocking) — see [TextAction]'s own doc comment.
                this.text = AnnotatedString(label)
                this.selected = selected
                role = Role.Checkbox
                onClick(label = null) {
                    onClick()
                    true
                }
                // R-543: see this composable's own doc comment — the dismiss icon reaches a
                // screen reader as a custom action on this node, not as an independent one.
                if (onDismiss != null) {
                    customActions = listOf(
                        CustomAccessibilityAction(label = "Remove $label filter") {
                            onDismiss()
                            true
                        },
                    )
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
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
    // rate, plus the route's advisory where it has one (FR-CAP-2b). [tone] is [RowTone.Warning]
    // only for a route the app cannot recommend at all, never for a supported-but-disclosed one
    // such as the built-in mic or Bluetooth — see `InputScreen.dimsTheRow`.
    // Both null/Neutral by default, so existing rows are unchanged.
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
 *
 * R-381 (`Search.dc.html`'s field, dumped as `<node content-desc="" class="android.widget.EditText"
 * clickable="true" focusable="true"><node content-desc="Search text" focusable="false"/><node
 * text="…" focusable="false"/></node>` — the field's own node exported *empty* despite the
 * `semantics { contentDescription = … }` above): this composable deliberately does **not** switch
 * that block to `clearAndSetSemantics` the way every clickable control elsewhere in this package
 * did for the identical-looking defect (`TextAction`, `LogRow`, `FilterChip` — see `CHANGELOG.md`).
 * [BasicTextField] attaches its own real editable-text actions (`SetText`, `InsertTextAtCursor`,
 * `RequestFocus`, `GetTextLayoutResult`, …) directly on this same node; `clearAndSetSemantics`
 * replaces a node's *entire* exported config, and this field has no safe way to redeclare those
 * internal, framework-owned actions the way `TextAction` redeclares its own `onClick`. Instead: the
 * [label] and [placeholder] `Text`s below are marked `Modifier.semantics { invisibleToUser() }` —
 * their words are already carried by this node's own [contentDescriptionText]/[label]/[placeholder]
 * fallback chain above, so exporting them a *second* time, as their own separate descendant nodes
 * (which is what left "Search text" on a orphaned child rather than the field's own name), serves
 * no one; hiding them individually removes the redundant children without touching the field's own
 * real actions at all. [leadingIcon] (decorative, `contentDescription = null`) and [trailingAction]
 * (real, interactive content a caller supplies — e.g. a clear `×` button with its own literal
 * `contentDescription` and `clickable`) are deliberately left fully visible to accessibility: they
 * are not redundant with this field's own name, and a real device confirmed a leaf `Icon` with a
 * literal `contentDescription` plus `clickable` (no descendants of its own) already survives as its
 * own separate stop — the "reference shape" this package's other fixes cite. [errorText] is left
 * alone for the same reason: it states something the field's own description does not.
 */
@Suppress("LongParameterList") // every parameter is an independent, optional field concern.
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class) // invisibleToUser() (R-381)
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
    // R-381 device iteration (round 2, `emulator-5554`), all three candidates tried in the order
    // given, all confirmed via a fresh `uiautomator dump` after each, none closed it — the finding
    // is written up in full in this round's own `CHANGELOG.md` entry, not repeated here at length:
    // (1) `contentDescription` alone directly on this modifier — the field's own exported `EditText`
    // node still showed `content-desc=""`, unchanged from the pre-existing defect. (2) adding
    // `this.text = …` alongside it — still `content-desc=""`/`text=""`, and the node's own exported
    // class changed from `EditText` to a generic `TextView`, a regression this round reverted rather
    // than kept. (3) wrapping in an outer `Box` and moving semantics there — the *wrapper's own*
    // node also exported empty, and the inner field gained `NAF="true"` (`uiautomator`'s own
    // "not accessibility friendly" marker). This round settled on (1), the least-regressive of the
    // three (preserves the real `EditText` classification, changes nothing else observably), and
    // stops rather than risk a fourth device-unverified guess.
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
            .semantics(mergeDescendants = true) {
                contentDescription = contentDescriptionText ?: label ?: placeholder.orEmpty()
            },
        decorationBox = { innerTextField ->
            Column {
                label?.let {
                    Text(
                        text = it,
                        style = OrtType.sectionLabel,
                        color = OrtColors.textFaint,
                        // R-381: this word is already carried by the field's own node (see this
                        // composable's own doc comment) — hidden individually so it does not also
                        // export as a redundant, orphaned child.
                        modifier = Modifier
                            .padding(bottom = OrtSpacing.xs)
                            .semantics { invisibleToUser() },
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
                            Text(
                                text = placeholder,
                                style = OrtType.control,
                                color = OrtColors.textSignal,
                                // R-381: see this composable's own doc comment — already carried
                                // by the field's own node when no explicit label is given.
                                modifier = Modifier.semantics { invisibleToUser() },
                            )
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
