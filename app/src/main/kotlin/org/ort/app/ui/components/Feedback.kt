@file:Suppress("MatchingDeclarationName") // BannerTone is one of several public declarations here.

package org.ort.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * R-023 (ui-conformance-plan WP2): the six feedback treatments from guide §6.8-6.9/§Feedback,
 * never conflated (`Feedback.dc.html`). None of `Banner`, `Toast`, `Sheet`, `EmptyState` or
 * `FailedState` existed before this landed — every screen was improvising its own empty/failed
 * copy inline.
 */

// ---------------------------------------------------------------------------------------------
// Banner — amber degradation / red halting.
// ---------------------------------------------------------------------------------------------

/** Red is for one thing: capture has stopped and the operator must act. Every degradation is
 * amber (constitution: "if a screen is red and capture is still running, the screen is wrong"). */
public enum class BannerTone { DEGRADED, HALTING }

@Composable
public fun Banner(
    title: String,
    body: String,
    tone: BannerTone,
    modifier: Modifier = Modifier,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val bg = if (tone == BannerTone.HALTING) OrtColors.haltBg else OrtColors.bgRowGap
    val border = if (tone == BannerTone.HALTING) OrtColors.haltBorder else OrtColors.bannerAmberBorder
    val iconTint = if (tone == BannerTone.HALTING) OrtColors.haltText else OrtColors.accentGap
    val actionColor = if (tone == BannerTone.HALTING) OrtColors.haltText else OrtColors.accentAmber

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(8.dp))
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .padding(13.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title. $body" },
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(
            imageVector = if (tone == BannerTone.HALTING) OrtIcons.halt else OrtIcons.gapWarn,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(17.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = OrtType.subtitle.copy(fontWeight = FontWeight.Medium),
                color = OrtColors.textHigh,
            )
            Text(
                text = body,
                style = OrtType.cardBody,
                color = OrtColors.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (primaryActionLabel != null && onPrimaryAction != null) {
                BannerAction(text = primaryActionLabel, color = actionColor, onClick = onPrimaryAction)
            }
            if (secondaryActionLabel != null && onSecondaryAction != null) {
                BannerAction(text = secondaryActionLabel, color = actionColor, onClick = onSecondaryAction)
            }
        }
    }
}

@Composable
private fun BannerAction(text: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {},
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = text, style = OrtType.textAction.copy(fontWeight = FontWeight.Medium), color = color)
    }
}

// ---------------------------------------------------------------------------------------------
// Toast — transient confirmation, always with Undo.
// ---------------------------------------------------------------------------------------------

/** guide §Feedback: `bg/selected`, message + `Undo`. Every propagating action gets one and states
 * the blast radius ("6 overs updated"), not just success. */
@Composable
public fun Toast(message: String, onUndo: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(OrtColors.bgSelected, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = message },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Text(text = message, style = OrtType.subtitle, color = OrtColors.textHigh, modifier = Modifier.weight(1f))
        if (onUndo != null) {
            BannerAction(text = "Undo", color = OrtColors.accentGreen, onClick = onUndo)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sheet — guide §6.9.
// ---------------------------------------------------------------------------------------------

/**
 * guide §6.9: `bg/raised`, 18px top radius, a drag handle, title + optional `Clear all` slot,
 * then [content]. Dismissing (drag or scrim) and dimming the screen beneath to 22% are the host
 * screen's job — this composable is the sheet surface itself, not the scaffold around it.
 */
@Composable
public fun Sheet(
    title: String,
    modifier: Modifier = Modifier,
    onClearAll: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OrtColors.bgRaised, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .padding(top = 10.dp, start = 20.dp, end = 20.dp, bottom = 20.dp),
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .align(Alignment.CenterHorizontally)
                .background(OrtColors.lineHandle, RoundedCornerShape(2.dp)),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, style = OrtType.cardTitle, color = OrtColors.textHigh, modifier = Modifier.weight(1f))
            if (onClearAll != null) {
                TextAction(text = "Clear all", onClick = onClearAll)
            }
        }
        content()
    }
}

// ---------------------------------------------------------------------------------------------
// 6.8 Empty, loading, failed.
// ---------------------------------------------------------------------------------------------

/** guide §6.8: a plain sentence at page margin — never a spinner, never an illustration. */
@Composable
public fun EmptyState(message: String, modifier: Modifier = Modifier, subMessage: String? = null) {
    Column(
        modifier = modifier
            .padding(vertical = 22.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = if (subMessage != null) "$message. $subMessage" else message
            },
    ) {
        Text(text = message, style = OrtType.bodyProse, color = OrtColors.textMuted)
        subMessage?.let {
            Text(
                text = it,
                style = OrtType.cardBody,
                color = OrtColors.textFaint,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

/** guide §6.8: a screen with nothing to show because a *capability is missing* is failed, not
 * empty — amber, names the cause in operator terms, confirms nothing is being lost, carries the
 * recovery action. Never a bare "error". */
@Composable
public fun FailedState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .padding(vertical = 14.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title. $body" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FailedMarker()
            Text(text = title, style = OrtType.bodyProse, color = OrtColors.textHigh)
        }
        Text(
            text = body,
            style = OrtType.cardBody,
            color = OrtColors.textMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (actionLabel != null && onAction != null) {
            TextAction(text = actionLabel, onClick = onAction, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** `Feedback.dc.html`'s failed-state marker: the AMBIGUOUS shape reused generically to mean
 * "needs attention" — this is not an attribution, so it is drawn locally rather than through
 * [AttributionRow]. */
@Composable
private fun FailedMarker(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(9.dp)) {
        val d = size.minDimension
        drawCircle(color = OrtColors.accentAmber, radius = d / 2 - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        clipRect(right = d / 2) {
            drawCircle(color = OrtColors.accentAmber, radius = d / 2 - 0.75.dp.toPx())
        }
    }
}
