package org.ort.app.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.StepIndicator
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * R-080 (ui-conformance-plan WP9): the shell every step of the guided sequence shares except
 * [WelcomeScreen] (`Setup-Welcome.dc.html`'s header is the app wordmark, not this step chrome) —
 * the 44dp status-bar inset, a 44dp back target (guide §5), WP2's [StepIndicator] (guide §6.10),
 * the title/subtitle pair, the screen's own scrollable content, and a [bottomActions] slot each
 * screen fills with whatever `Controls.dc.html` combination its own board shows (a single
 * `Verify this input`, `Continue` + a secondary text action, a halting primary + a retry, or S09's
 * bare "Not now" with no filled button at all) — the variety across boards is real, not
 * accidental, so this scaffold does not force one shape on it.
 */
@Composable
public fun SetupScaffold(
    step: SetupStep,
    title: String,
    subtitle: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    titleTrailing: (@Composable () -> Unit)? = null,
    bottomActions: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OrtColors.bgScreen)
            .windowInsetsPadding(WindowInsets.statusBars)
            .testTag("setup-screen-${step.name}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable(onClickLabel = "Back", role = Role.Button, onClick = onBack)
                        .testTag("setup-back"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = OrtIcons.back,
                        contentDescription = "Back",
                        tint = OrtColors.textIcon,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
            Row(modifier = Modifier.weight(1f)) {}
            val index = step.indicatorIndex()
            if (index != null) {
                Text(
                    text = "$index of $SETUP_TOTAL_STEPS",
                    style = OrtType.signal,
                    color = OrtColors.textDim,
                )
            }
        }
        step.indicatorIndex()?.let { index ->
            StepIndicator(
                steps = SETUP_TOTAL_STEPS,
                currentStep = index,
                haltedStep = if (step.isHalted()) index else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = OrtSpacing.lg),
            )
        }
        Row(
            modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.md),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = OrtType.screenTitle, color = OrtColors.textHigh)
                Text(text = subtitle, style = OrtType.subtitle, color = OrtColors.textDim)
            }
            titleTrailing?.invoke()
        }
        Column(
            modifier = Modifier
                .padding(horizontal = OrtSpacing.lg)
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(OrtSpacing.md),
        ) {
            content()
        }
        Column(
            modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.md),
            verticalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
        ) {
            bottomActions()
        }
    }
}

/** `RationaleCard`/similar boards' green-dot bullet list (`Setup-Mic.dc.html`,
 * `Setup-Battery.dc.html`'s "less likely, not guaranteed" paragraph). */
@Composable
public fun DotPointCard(points: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OrtColors.bgCard, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .padding(OrtSpacing.md + OrtSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
    ) {
        points.forEach { point ->
            Row {
                Box(
                    modifier = Modifier
                        .padding(top = 5.dp, end = 11.dp)
                        .size(9.dp)
                        .background(OrtColors.accentGreen, androidx.compose.foundation.shape.CircleShape),
                )
                Text(text = point, style = OrtType.subtitle, color = OrtColors.textBody)
            }
        }
    }
}

/** The halting banner shared by `Setup-Mic-Denied.dc.html` and `Setup-Route-Mismatch.dc.html` —
 * thin wrapper over WP2's [org.ort.app.ui.components.Banner] fixed to
 * [org.ort.app.ui.components.BannerTone.HALTING], since every use in this package is a hard halt. */
@Composable
public fun SetupHaltBanner(title: String, body: String, modifier: Modifier = Modifier) {
    org.ort.app.ui.components.Banner(
        title = title,
        body = body,
        tone = org.ort.app.ui.components.BannerTone.HALTING,
        modifier = modifier,
    )
}

/** A numbered step row (`1  Apps -> ... -> Permissions`), used by both S02b and S08. */
@Composable
public fun NumberedStep(index: Int, text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = OrtSpacing.sm)) {
        Text(
            text = "$index",
            style = OrtType.signal,
            color = OrtColors.textLow,
            modifier = Modifier.size(width = 22.dp, height = 16.dp),
        )
        Text(text = text, style = OrtType.control, color = OrtColors.textBody)
    }
}

/** guide §6.11's read-only device row shape (icon + title/subtitle) with a trailing chevron —
 * `Setup-Rig.dc.html`'s three options, which navigate on tap rather than toggling a radio state. */
@Composable
public fun NavigationRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    titleColor: Color = OrtColors.textBody,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClickLabel = title, role = Role.Button, onClick = onClick)
            .padding(vertical = OrtSpacing.sm)
            .semantics(mergeDescendants = true) { contentDescription = "$title. $subtitle" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) OrtColors.accentGreen else OrtColors.textIconDim,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = OrtType.rowTitle.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                color = titleColor,
            )
            Text(text = subtitle, style = OrtType.timeFreq, color = OrtColors.textDim)
        }
        Icon(
            imageVector = OrtIcons.chevron,
            contentDescription = null,
            tint = OrtColors.textLow,
            modifier = Modifier.size(15.dp),
        )
    }
}
